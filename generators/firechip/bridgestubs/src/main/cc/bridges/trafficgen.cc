// See LICENSE for license details

#include "trafficgen.h"
#include "trafficgen_socket_protocol.h"
#include "trafficgen_lane_assignment.h"
#include "bridges/cpu_managed_stream.h"
#include "core/simif.h"

#include <arpa/inet.h>
#include <algorithm>
#include <boost/archive/binary_iarchive.hpp>
#include <boost/archive/binary_oarchive.hpp>
#include <boost/serialization/access.hpp>
#include <boost/serialization/library_version_type.hpp>
#include <boost/serialization/map.hpp>
#include <boost/serialization/set.hpp>
#include <boost/serialization/string.hpp>
#include <boost/serialization/unordered_map.hpp>
#include <boost/serialization/unordered_set.hpp>
#include <boost/serialization/vector.hpp>
#include <cassert>
#include <cinttypes>
#include <cerrno>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <map>
#include <memory>
#include <numeric>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

char trafficgen_t::KIND;

namespace {
using namespace trafficgen_socket;

constexpr std::uint32_t kRoundExitScheduling = 0;
constexpr std::uint32_t kRoundExitCapacity = 1;
constexpr std::uint32_t kRoundExitBundleTableFull = 2;
constexpr std::uint32_t kRoundExitControl = 3;

constexpr std::uint32_t kGpuModelSocketAddr = INADDR_LOOPBACK;
constexpr std::size_t kXDMABufferAlignment = 4096;
// Must match TrafficGenBRAMAddressMap.lrcMetadataEntries. Execution stays paused
// across every chunk; TMA policy publication remains the final descriptor.
constexpr std::size_t kLrcMetadataEntries = 4096;
constexpr const char *kRoundLogBase = "/home/prashanth/FIRESIM_RUNS_DIR/sim_slot_0";
constexpr const char *kSocketRoundLogRoot =
    "/home/prashanth/FIRESIM_RUNS_DIR/sim_slot_0/bridge_socket_round_logs";

std::vector<socket_l2_access_t> current_round_all_l2_trace_steps;
std::set<socket_warp_key_t> current_round_blocked_warp_ids;

std::string errno_message(const std::string &prefix) {
  std::ostringstream oss;
  oss << prefix << ": " << std::strerror(errno);
  return oss.str();
}

std::string hex_u64(std::uint64_t value) {
  std::ostringstream oss;
  oss << "0x" << std::hex << value;
  return oss.str();
}

struct FreeDeleter {
  void operator()(std::uint8_t *ptr) const { std::free(ptr); }
};

using AlignedBytes = std::unique_ptr<std::uint8_t, FreeDeleter>;

AlignedBytes make_aligned_bytes(std::size_t size) {
  void *ptr = nullptr;
  const int rc = posix_memalign(&ptr, kXDMABufferAlignment, size);
  if (rc != 0) {
    throw std::runtime_error("posix_memalign failed: " +
                             std::string(std::strerror(rc)));
  }
  return AlignedBytes(static_cast<std::uint8_t *>(ptr));
}

void xdma_write_exact(CPUManagedStreamIO &xdma,
                      std::uint64_t addr,
                      const void *data,
                      std::size_t size,
                      const std::string &what) {
  const auto failed = std::numeric_limits<std::size_t>::max();
  const char *cursor = static_cast<const char *>(data);
  std::size_t done = 0;
  while (done < size) {
    errno = 0;
    const std::size_t written =
        xdma.cpu_managed_axi4_write(addr + done, cursor + done, size - done);
    if (written == failed) {
      throw std::runtime_error(what + " failed at " + hex_u64(addr + done) +
                               ": " + std::strerror(errno));
    }
    if (written == 0 || written > size - done) {
      throw std::runtime_error(
          what + " short write at " + hex_u64(addr + done) + ": " +
          std::to_string(written) + " of " + std::to_string(size - done));
    }
    done += written;
  }
}

void xdma_read_exact(CPUManagedStreamIO &xdma,
                     std::uint64_t addr,
                     void *data,
                     std::size_t size,
                     const std::string &what) {
  const auto failed = std::numeric_limits<std::size_t>::max();
  char *cursor = static_cast<char *>(data);
  std::size_t done = 0;
  while (done < size) {
    errno = 0;
    const std::size_t received =
        xdma.cpu_managed_axi4_read(addr + done, cursor + done, size - done);
    if (received == failed) {
      throw std::runtime_error(what + " failed at " + hex_u64(addr + done) +
                               ": " + std::strerror(errno));
    }
    if (received == 0 || received > size - done) {
      throw std::runtime_error(
          what + " short read at " + hex_u64(addr + done) + ": " +
          std::to_string(received) + " of " + std::to_string(size - done));
    }
    done += received;
  }
}

void create_directory_if_needed(const std::string &path) {
  if (mkdir(path.c_str(), 0755) == 0 || errno == EEXIST) {
    return;
  }
  throw std::runtime_error(errno_message("failed to create " + path));
}

void clear_round_log_entries(const std::filesystem::path &root,
                             bool include_recovery = false) {
  if (std::filesystem::exists(root)) {
    for (const auto &entry : std::filesystem::directory_iterator(root)) {
      const std::string name = entry.path().filename().string();
      if (name.rfind("round_", 0) == 0 ||
          (include_recovery && name.rfind("recovery_", 0) == 0)) {
        std::filesystem::remove_all(entry.path());
      }
    }
  }
  std::filesystem::create_directories(root);
}

std::string format_round_log_dir(std::uint64_t round_number) {
  char buffer[32];
  std::snprintf(buffer, sizeof(buffer), "round_%06" PRIu64, round_number);
  return std::string(buffer);
}

template <typename Snapshot>
void write_socket_snapshot(const std::string &path, const Snapshot &snapshot) {
  std::ofstream output(path, std::ios::binary);
  if (!output.is_open()) {
    throw std::runtime_error("failed to open socket round log for writing: " +
                             path);
  }

  boost::archive::binary_oarchive archive(output);
  archive << snapshot;
}

void log_socket_round_inputs_for_compare(
    const std::vector<socket_l2_access_t> &all_l2_trace_steps,
    const std::set<socket_warp_key_t> &blocked_warp_ids,
    const std::filesystem::path &socket_log_root,
    std::uint64_t round_number) {

  const std::string root_dir = socket_log_root.string();
  const std::string round_dir =
      root_dir + "/" + format_round_log_dir(round_number);
  const std::string l2_trace_steps_path = round_dir + "/all_l2_trace_steps.bin";
  const std::string blocked_warp_ids_path = round_dir + "/blocked_warp_ids.bin";

  create_directory_if_needed(root_dir);
  create_directory_if_needed(round_dir);

  SocketAllL2TraceStepsSnapshot l2_trace_steps_snapshot;
  l2_trace_steps_snapshot.steps = all_l2_trace_steps;
  SocketBlockedWarpIdsSnapshot blocked_warp_ids_snapshot;
  blocked_warp_ids_snapshot.blockedWarpIds = blocked_warp_ids;

  write_socket_snapshot(l2_trace_steps_path, l2_trace_steps_snapshot);
  write_socket_snapshot(blocked_warp_ids_path, blocked_warp_ids_snapshot);

  std::cout << "[bridge driver] logged socket round input snapshots: "
            << "round_dir=" << round_dir
            << " l2_accesses=" << all_l2_trace_steps.size()
            << " blocked_warps=" << blocked_warp_ids.size() << std::endl;
}

OrderedL2SubpartitionReservationsByCycle ordered_reservations(
    const L2SubpartitionReservationsByCycle &reservations) {
  OrderedL2SubpartitionReservationsByCycle ordered;
  for (const auto &[cycle, subpartitions] : reservations) {
    ordered[cycle].insert(subpartitions.begin(), subpartitions.end());
  }
  return ordered;
}

std::string plusarg_value(const std::vector<std::string> &args,
                          const std::string &key) {
  const std::string prefix = "+" + key + "=";
  for (const auto &arg : args) {
    if (arg.rfind(prefix, 0) == 0) {
      return arg.substr(prefix.size());
    }
  }
  return "";
}

std::uint64_t host_to_network_64(std::uint64_t value) {
  const std::uint32_t high =
      htonl(static_cast<std::uint32_t>(value >> 32));
  const std::uint32_t low =
      htonl(static_cast<std::uint32_t>(value & 0xffffffffULL));
  return (static_cast<std::uint64_t>(low) << 32) | high;
}

std::uint64_t network_to_host_64(std::uint64_t value) {
  const std::uint32_t high =
      ntohl(static_cast<std::uint32_t>(value >> 32));
  const std::uint32_t low =
      ntohl(static_cast<std::uint32_t>(value & 0xffffffffULL));
  return (static_cast<std::uint64_t>(low) << 32) | high;
}

void write_all(int fd, const void *buffer, std::size_t size) {
  const char *cursor = static_cast<const char *>(buffer);
  std::size_t remaining = size;
  while (remaining > 0) {
    const ssize_t written = send(fd, cursor, remaining, MSG_NOSIGNAL);
    if (written < 0) {
      if (errno == EINTR) {
        continue;
      }
      throw std::runtime_error(errno_message(
          "failed to send reservations to gpu_model_socket"));
    }
    if (written == 0) {
      throw std::runtime_error(
          "failed to send reservations to gpu_model_socket: "
          "peer disconnected");
    }
    cursor += written;
    remaining -= static_cast<std::size_t>(written);
  }
}

void read_all(int fd, void *buffer, std::size_t size) {
  char *cursor = static_cast<char *>(buffer);
  std::size_t remaining = size;
  while (remaining > 0) {
    const ssize_t received = recv(fd, cursor, remaining, 0);
    if (received < 0) {
      if (errno == EINTR) {
        continue;
      }
      throw std::runtime_error(errno_message(
          "failed to receive schedule from gpu_model_socket"));
    }
    if (received == 0) {
      throw std::runtime_error(
          "failed to receive schedule from gpu_model_socket: "
          "peer disconnected");
    }
    cursor += received;
    remaining -= static_cast<std::size_t>(received);
  }
}

template <typename T>
std::uint32_t checked_u32(T value, const char *field_name) {
  if (value > static_cast<T>(UINT32_MAX)) {
    std::cout << "[bridge driver] " << field_name
              << " out of range for target upload: "
              << static_cast<std::uint64_t>(value) << std::endl;
    std::abort();
  }
  return static_cast<std::uint32_t>(value);
}

template <typename T>
std::uint8_t checked_u8(T value, const char *field_name) {
  if (value > static_cast<T>(UINT8_MAX)) {
    std::cout << "[bridge driver] " << field_name
              << " out of range for target upload: "
              << static_cast<std::uint64_t>(value) << std::endl;
    std::abort();
  }
  return static_cast<std::uint8_t>(value);
}

} // namespace

using namespace trafficgen_socket;

class trafficgen_t::socket_client_t {
public:
  socket_client_t() = default;
  ~socket_client_t() {
    if (fd >= 0) {
      close(fd);
    }
  }

  socket_client_t(const socket_client_t &) = delete;
  socket_client_t &operator=(const socket_client_t &) = delete;

  bool is_connected() const { return fd >= 0; }

  void disconnect() {
    if (fd >= 0) {
      close(fd);
      fd = -1;
    }
  }

  void connect_loopback(std::uint16_t port) {
    disconnect();

    fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
      throw std::runtime_error(
          errno_message("failed to create gpu_model socket"));
    }

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(port);
    address.sin_addr.s_addr = htonl(kGpuModelSocketAddr);

    if (connect(fd, reinterpret_cast<sockaddr *>(&address), sizeof(address)) <
        0) {
      const std::string message = errno_message(
          "failed to connect to gpu_model_socket at "
          "127.0.0.1:" + std::to_string(port));
      close(fd);
      fd = -1;
      throw std::runtime_error(message);
    }
  }

  void send_frame(const std::string &payload) const {
    if (fd < 0) {
      throw std::runtime_error(
          "gpu_model socket is not connected");
    }
    if (payload.size() > 256ULL * 1024 * 1024)
      throw std::runtime_error("TrafficGen socket frame exceeds 256 MiB");

    const std::uint64_t payload_size =
        host_to_network_64(static_cast<std::uint64_t>(payload.size()));
    write_all(fd, &payload_size, sizeof(payload_size));
    if (!payload.empty()) {
      write_all(fd, payload.data(), payload.size());
    }
  }

  template <typename T>
  T recv_message() const {
    if (fd < 0) {
      throw std::runtime_error(
          "gpu_model socket is not connected");
    }

    std::uint64_t payload_size_network = 0;
    read_all(fd, &payload_size_network, sizeof(payload_size_network));
    const std::uint64_t payload_size =
        network_to_host_64(payload_size_network);

    if (payload_size > 256ULL * 1024 * 1024)
      throw std::runtime_error("TrafficGen socket frame exceeds 256 MiB");
    std::string payload(static_cast<std::size_t>(payload_size), '\0');
    if (payload_size > 0) {
      read_all(fd, payload.data(), payload.size());
    }
    return deserialize_message<T>(payload);
  }

private:
  int fd = -1;
};

static void pack_l2_access(const trafficgen_l2_access_t &access,
                           uint64_t *words,
                           bool use_rtl_engine) {
  words[0] = access.id;
  words[1] = access.address;
  words[2] = access.cycle_count;
  if (use_rtl_engine) {
    words[3] = access.m_bundle_id;
    words[4] = (access.m_wake_relevant_bundle ? 1ULL : 0ULL) |
               ((access.m_is_write ? 1ULL : 0ULL) << 1) |
               ((access.m_warp_blocked ? 1ULL : 0ULL) << 2) |
               (static_cast<std::uint64_t>(access.bundle_issue_count) << 3) |
               (static_cast<std::uint64_t>(access.bundle_generation) << 19) |
               (access.launch_id << 51);
    words[5] = access.launch_id >> 13;
    words[5] |= access.physical_token << 51;
    words[6] = access.physical_token >> 13;
    return;
  }
  words[3] = (static_cast<uint64_t>(access.m_set_index) << 32) |
             access.m_subpartition;
  words[4] = access.m_tag;
  words[5] = (static_cast<uint64_t>(access.sm_id) << 32) | access.m_mask;
  words[6] = (access.m_bundle_id & 0xffffffULL) << 40 |
             (static_cast<uint64_t>(access.warp_id) << 8) |
             static_cast<uint64_t>(access.scheduler_id);
  words[7] = (access.m_warp_blocked ? 1ULL : 0ULL) << 42 |
             (access.m_is_write ? 1ULL : 0ULL) << 41 |
             (access.m_wake_relevant_bundle ? 1ULL : 0ULL) << 40 |
             (access.m_bundle_id >> 24) |
             (static_cast<uint64_t>(access.bundle_issue_count) << 43);
}

static trafficgen_issued_access_point_t
unpack_issued_access(const uint64_t *words) {
  trafficgen_issued_access_point_t access{};
  access.request_uid = words[0];
  access.cycle_issued = words[1];
  access.address = words[2];
  access.is_write = (words[3] & 0x1ULL) != 0;
  access.launch_id = (words[3] >> 1) | (words[4] << 63);
  access.physical_token = (words[4] >> 1) | (words[5] << 63);
  return access;
}

trafficgen_t::trafficgen_t(simif_t &simif,
                           const TRAFFICGENBRIDGEMODULE_struct &mmio_addrs,
                           int /*trafficgenno*/,
                           const std::vector<std::string> &args,
                           uint64_t bram_base,
                           uint64_t raw_access_store_offset,
                           uint64_t raw_issued_access_writeback_store_offset,
                           uint64_t raw_completed_bundle_ids_offset,
                           uint64_t access_window_bytes,
                           uint64_t completed_window_bytes,
                           uint64_t use_rtl_engine,
                           uint64_t lane_count)
    : bridge_driver_t(simif, &KIND),
      mmio_addrs(mmio_addrs),
      bram_base(bram_base),
      raw_access_store_offset(raw_access_store_offset),
      raw_issued_access_writeback_store_offset(
          raw_issued_access_writeback_store_offset),
      raw_completed_bundle_ids_offset(raw_completed_bundle_ids_offset),
      access_window_bytes(access_window_bytes),
      completed_window_bytes(completed_window_bytes),
      use_rtl_engine(use_rtl_engine != 0),
      lane_count(static_cast<std::size_t>(lane_count)) {
  if (this->lane_count == 0) {
    throw std::invalid_argument("TrafficGen requires at least one lane");
  }
  if (access_window_bytes % this->lane_count != 0) {
    throw std::invalid_argument("TrafficGen access window is not lane divisible");
  }
  const auto port_arg = plusarg_value(args, "trafficgen-socket-port");
  if (!port_arg.empty()) {
    std::size_t consumed = 0;
    const auto port = std::stoul(port_arg, &consumed);
    if (consumed != port_arg.size() || port == 0 || port > 65535)
      throw std::invalid_argument("TrafficGen invalid socket port");
    socket_port = static_cast<std::uint16_t>(port);
  }
  const auto legacy_arg = plusarg_value(args, "trafficgen-legacy-registry-id");
  if (!legacy_arg.empty()) {
    std::size_t consumed = 0;
    legacy_registry_id = std::stoull(legacy_arg, &consumed);
    if (consumed != legacy_arg.size() || legacy_arg.front() == '-' || !legacy_registry_id)
      throw std::invalid_argument("TrafficGen invalid legacy registry ID");
  }
  const std::string round_log_dir =
      plusarg_value(args, "trafficgen-round-log-dir");
  round_log_root = std::filesystem::path(kRoundLogBase) /
                   (round_log_dir.empty() ? "trafficgen_bridge" : round_log_dir);
  socket_round_log_root = round_log_dir.empty()
      ? std::filesystem::path(kSocketRoundLogRoot)
      : round_log_root / "socket_inputs";
  boundary_log_path = plusarg_value(args, "trafficgen-boundary-log");
}

trafficgen_t::~trafficgen_t() = default;

void trafficgen_t::init() {
  if (!use_rtl_engine)
    throw std::runtime_error("TrafficGen socket protocol v7 requires the RTL engine; DPI LRC/multi-launch is unsupported");
  clear_round_log_entries(round_log_root, true);
  clear_round_log_entries(socket_round_log_root);
  std::ofstream lifecycle(round_log_root / "launches.csv");
  if (!lifecycle) throw std::runtime_error("TrafficGen cannot initialize lifecycle log");
  lifecycle << "cycle,event,launch_id,registry_id,slot\n";
  std::ofstream controls(round_log_root / "controls.csv");
  if (!controls) throw std::runtime_error("TrafficGen cannot initialize control log");
  controls << "cycle,new_launches,pending_requests,outstanding_bundles,end_of_launches\n";
  std::ofstream lrc_events(round_log_root / "lrc_events.csv");
  if (!lrc_events) throw std::runtime_error("TrafficGen cannot initialize LRC event log");
  lrc_events << "event,round,cycle,launch_id,request_uid,physical_id,recorded_service_id,bundle_id,address,is_write,action,source_ready_cycle\n";
  std::ofstream lrc_boundaries(round_log_root / "lrc_boundaries.csv");
  if (!lrc_boundaries) throw std::runtime_error("TrafficGen cannot initialize LRC boundary log");
  lrc_boundaries << "round,hardware_boundary,cycle,flags,exit_reason\n";
  if (!boundary_log_path.empty()) {
    std::ofstream boundaries(boundary_log_path);
    if (!boundaries) throw std::runtime_error("TrafficGen cannot initialize boundary log");
    boundaries << "boundary,round,start_cycle,end_cycle,requested_min_issue_cycle,"
                  "effective_min_issue_cycle,exit_reason,issued_requests,completed_bundles,"
                  "scheduler_exchange,control_waiting\n";
  }

  engine_round_number = 0;
  reset_workload_state();
  connect_gpu_model_socket();
  receive_scheduler_status();
}

void trafficgen_t::reset_workload_state() {
  last_lane_upload_bytes.assign(lane_count, {});
  completed_bundle_ids.fill(0);
  completed_bundle_count = 0;
  completed_bundle_read_issued = false;
  issued_access_writeback_entries.clear();
  issued_access_writeback_stream_bytes.clear();
  issued_access_writeback_count = 0;
  issued_access_writeback_bytes_received = 0;
  issued_access_writeback_read_issued = false;
  issued_lane_read_cursors.assign(lane_count, 0);
  issued_lane_snapshot_counts.assign(lane_count, 0);
  accumulated_issued_accesses.clear();
  accumulated_completed_bundle_ids.clear();
  current_round_all_l2_trace_steps.clear();
  current_round_blocked_warp_ids.clear();
  min_issue_cycle = 0;
  l2_accesses.clear();
  pending_accesses_by_cycle.clear();
  pending_access_cycle_by_id.clear();
  bundle_lane_owners.clear();
  bundle_member_counts.clear();
  promoted_bundles.assign(lane_count, std::nullopt);
  bundle_assignment_by_request_uid.clear();
  bundle_tracker.clear();
  lrc_ledger = trafficgen_lrc_ledger{};
  tma_policy = trafficgen_tma_policy{};
  tma_diagnostics = {}; tma_wake_cycles.clear(); ordinary_wake_cycles.clear();
  lrc_new_round = false;
  lrc_round_drain_only = false;
  lrc_metadata_committed = false;
  lrc_window_valid = false;
  lrc_reuse_window = false;
  lrc_pending_reports.clear();
  upload_written_to_bram = false;
  round_input_reserved_subpartitions.clear();
  state = trafficgen_state_t::IDLE;
}

void trafficgen_t::connect_gpu_model_socket() {
  if (!gpu_model_socket_client) {
    gpu_model_socket_client = std::make_unique<socket_client_t>();
  }

  gpu_model_socket_client->connect_loopback(socket_port);
  std::cout << "[bridge driver] connected to gpu_model_socket at 127.0.0.1:"
            << static_cast<unsigned>(socket_port) << std::endl;

  const std::uint64_t current_target_cycle =
      (static_cast<std::uint64_t>(
           read(mmio_addrs.current_cycle_after_issue_high))
       << 32) |
      static_cast<std::uint64_t>(
          read(mmio_addrs.current_cycle_after_issue_low));
  TrafficGenInitializationMessage message;
  message.currentCycle = current_target_cycle;
  last_reported_cycle = current_target_cycle;
  gpu_model_socket_client->send_frame(serialize_message(message));
  std::cout << "[bridge driver] sent initial target cycle to gpu_model_socket: cycle="
            << current_target_cycle << std::endl;
}

std::uint64_t trafficgen_t::read_target_cycle() {
  const auto cycle = (static_cast<std::uint64_t>(read(mmio_addrs.current_cycle_after_issue_high)) << 32) |
                     read(mmio_addrs.current_cycle_after_issue_low);
  if (cycle < last_reported_cycle)
    throw std::runtime_error("TrafficGen target cycle moved backwards");
  return cycle;
}

void trafficgen_t::capture_launch_requests() {
  const auto pending = read(mmio_addrs.launch_pending_mask);
  if (pending & ~0xfU) throw std::runtime_error("TrafficGen invalid launch pending mask");
  for (std::size_t index = 0; index < launch_slots.size(); ++index) {
    if (!(pending & (1U << index))) continue;
    write(mmio_addrs.launch_slot_index, index);
    const auto id = (static_cast<std::uint64_t>(read(mmio_addrs.launch_id_high)) << 32) |
                    read(mmio_addrs.launch_id_low);
    const auto registry = (static_cast<std::uint64_t>(read(mmio_addrs.launch_registry_id_high)) << 32) |
                          read(mmio_addrs.launch_registry_id_low);
    auto &slot = launch_slots[index];
    if (slot.launch_id == id && id != 0) {
      if (slot.registry_id != registry) throw std::runtime_error("TrafficGen live slot registry changed");
      continue; // Target has not consumed our staged status yet.
    }
    if (!id || close_sent || launch_records.count(id) ||
        (slot.status != FREE && slot.status != COMPLETE && slot.status != REJECTED && slot.status != ABORTED))
      throw std::runtime_error("TrafficGen invalid or reused launch slot identity=" + std::to_string(id));
    slot = {id, registry, QUEUED};
    launch_records.emplace(id, launch_record_t{index, registry});
  }
  submissions_closed = submissions_closed || read(mmio_addrs.close_submissions) != 0;
}

void trafficgen_t::publish_launch_status() {
  if (!read(mmio_addrs.target_paused))
    throw std::runtime_error("TrafficGen launch status publication requires paused target");
  for (std::size_t index = 0; index < launch_slots.size(); ++index) {
    const auto &slot = launch_slots[index];
    write(mmio_addrs.launch_slot_index, index);
    write(mmio_addrs.slot_status_id_low, static_cast<std::uint32_t>(slot.launch_id));
    write(mmio_addrs.slot_status_id_high, static_cast<std::uint32_t>(slot.launch_id >> 32));
    write(mmio_addrs.slot_status, slot.status);
  }
  const std::uint32_t flags = (submissions_closed ? 1U : 0U) |
      (close_sent ? 2U : 0U) | (scheduler_status.idle ? 4U : 0U) |
      (session_complete ? 8U : 0U) | (session_truncated ? 16U : 0U) |
      (session_failed ? 32U : 0U);
  write(mmio_addrs.session_status, flags);
  write(mmio_addrs.commit_launch_status, 1);
}

void trafficgen_t::receive_scheduler_status() {
  scheduler_status = gpu_model_socket_client->recv_message<SchedulingRoundStateMessage>();
  if (scheduler_status.currentCycle < last_reported_cycle)
    throw std::runtime_error("TrafficGen scheduler status cycle moved backwards");
  last_reported_cycle = scheduler_status.currentCycle;
  const auto update = [&](std::uint64_t id, slot_state_t next, const char *event) {
    const auto record = launch_records.find(id);
    if (record == launch_records.end()) throw std::runtime_error("TrafficGen status for unknown launch=" + std::to_string(id));
    auto &slot = launch_slots[record->second.slot];
    if (slot.launch_id != id || slot.status == COMPLETE || slot.status == REJECTED || slot.status == ABORTED)
      throw std::runtime_error("TrafficGen duplicate or stale launch status=" + std::to_string(id));
    if ((next == ACCEPTED || next == REJECTED) && slot.status != SUBMITTED)
      throw std::runtime_error("TrafficGen launch acceptance/rejection before submission");
    if (next == DISPATCHED && slot.status != ACCEPTED)
      throw std::runtime_error("TrafficGen launch dispatch before acceptance");
    if (next == COMPLETE && (slot.status < ACCEPTED || lrc_ledger.has_launch(id)))
      throw std::runtime_error("TrafficGen launch completed while requests remain");
    slot.status = next;
    std::ofstream log(round_log_root / "launches.csv", std::ios::app);
    if (!log) throw std::runtime_error("TrafficGen cannot write launch lifecycle log");
    log << scheduler_status.currentCycle << ',' << event << ',' << id << ','
        << record->second.registry_id << ',' << record->second.slot << '\n';
    std::cout << "[bridge driver] launch=" << id << " registry=" << record->second.registry_id
              << " slot=" << record->second.slot << ' ' << event << std::endl;
  };
  for (auto id : scheduler_status.acceptedLaunchIds) update(id, ACCEPTED, "accepted");
  for (auto id : scheduler_status.dispatchedLaunchIds) update(id, DISPATCHED, "dispatched");
  for (auto id : scheduler_status.completedLaunchIds) update(id, COMPLETE, "completed");
  for (const auto &rejected : scheduler_status.rejectedLaunches) {
    update(rejected.launchId, REJECTED, "rejected");
    std::cerr << "[bridge driver] launch=" << rejected.launchId << " rejected: " << rejected.reason << std::endl;
    // The historical application only reads global done. It cannot inspect
    // per-slot rejection, so its implicit launch must fail the host session.
    if (legacy_bootstrap_active)
      throw std::runtime_error("TrafficGen legacy launch rejected: " + rejected.reason);
  }
  if (scheduler_status.idle && (!pending_access_cycle_by_id.empty() || lrc_ledger.has_pending() || tma_policy.pending()))
    throw std::runtime_error("TrafficGen scheduler is idle while client requests remain");
  session_truncated = scheduler_status.truncated;
  if (scheduler_status.truncated) {
    fail_session("gpu_model truncated the session at its explicit round limit");
    return;
  }
  if (scheduler_status.mainLoopComplete) {
    if (!close_sent || !scheduler_status.idle)
      throw std::runtime_error("TrafficGen scheduler completed before submission closed and work drained");
    if (lrc_ledger.active_service_slots() || lrc_ledger.active_bundle_slots() ||
        !lrc_pending_reports.empty() || read(mmio_addrs.lrc_service_count) ||
        read(mmio_addrs.lrc_bundle_count) || read(mmio_addrs.lrc_timer_count) || read(mmio_addrs.lrc_report_count))
      throw std::runtime_error("TrafficGen session completed with undrained native LRC monitor state");
    session_complete = true;
  }
}

void trafficgen_t::fail_session(const std::string &reason) {
  session_failed = true;
  session_complete = false;
  for (auto &slot : launch_slots)
    if (slot.status >= QUEUED && slot.status <= DISPATCHED) slot.status = ABORTED;
  std::cerr << "[bridge driver] TrafficGen session failed: " << reason << std::endl;
  if (read(mmio_addrs.target_paused)) {
    publish_launch_status();
    write(mmio_addrs.resume_target, 1);
  }
  if (gpu_model_socket_client) gpu_model_socket_client->disconnect();
  state = trafficgen_state_t::IDLE;
}

void trafficgen_t::finish_scheduler_boundary() {
  if (session_failed) return;
  capture_launch_requests();
  publish_launch_status();
  if (session_complete) {
    // The final public response and all reports have drained. End the policy's
    // lifetime before publishing terminal status; this is not a scheduler round.
    if (tma_policy.pending()) throw std::runtime_error("TrafficGen terminal session retains TMA wake groups");
    if (!tma_policy.groups().empty() || tma_policy.edge_count()) {
      tma_policy.replace({},lrc_ledger);
      lrc_metadata_committed=false;
      upload_lrc_metadata();
      log_lrc_events();
    }
    write(mmio_addrs.trafficgen_done, 1);
    write(mmio_addrs.resume_target, 1);
    gpu_model_socket_client->disconnect();
    state = trafficgen_state_t::IDLE;
    return;
  }
  const bool queued = std::any_of(launch_slots.begin(), launch_slots.end(),
      [](const auto &slot) { return slot.status == QUEUED; });
  if (scheduler_status.idle && !queued && !(submissions_closed && !close_sent)) {
    // The server is waiting for control. Let target software run until its next
    // submission/close event, then use that boundary's fresh target cycle.
    write(mmio_addrs.resume_target, 1);
    state = trafficgen_state_t::IDLE;
  } else {
    state = trafficgen_state_t::SEND_RESERVED_PARTITIONS;
  }
}

void trafficgen_t::send_reserved_subpartitions_snapshot(
    const L2SubpartitionReservationsByCycle &reservations) {
  capture_launch_requests();
  RoundControlMessage message;
  message.currentCycle = read_target_cycle();
  boundary_start_cycle = message.currentCycle;
  message.reservedSubpartitionsByCycle = reservations;
  for (std::size_t index = 0; index < launch_slots.size(); ++index) {
    const auto &slot = launch_slots[index];
    if (slot.status == QUEUED) message.launches.push_back({slot.registry_id, slot.launch_id, index + 1});
  }
  message.endOfLaunches = submissions_closed;
  gpu_model_socket_client->send_frame(serialize_message(message));
  last_reported_cycle = message.currentCycle;
  std::ofstream controls(round_log_root / "controls.csv", std::ios::app);
  if (!controls) throw std::runtime_error("TrafficGen cannot write control log");
  controls << message.currentCycle << ',' << message.launches.size() << ','
           << pending_access_cycle_by_id.size() << ',' << bundle_tracker.size() << ','
           << message.endOfLaunches << '\n';
  close_sent = close_sent || message.endOfLaunches;
  for (auto &slot : launch_slots) {
    if (slot.status != QUEUED) continue;
    slot.status = SUBMITTED;
    std::ofstream log(round_log_root / "launches.csv", std::ios::app);
    if (!log) throw std::runtime_error("TrafficGen cannot write launch lifecycle log");
    log << message.currentCycle << ",submitted," << slot.launch_id << ',' << slot.registry_id
        << ',' << launch_records.at(slot.launch_id).slot << '\n';
  }
  // A previous idle status described the state before these new launches.
  if (!message.launches.empty()) scheduler_status.idle = false;
  publish_launch_status();
}

void trafficgen_t::clean_completed_bundle_metadata() {
  const std::set<std::uint64_t> completed(accumulated_completed_bundle_ids.begin(),
                                           accumulated_completed_bundle_ids.end());
  for (auto it = bundle_assignment_by_request_uid.begin(); it != bundle_assignment_by_request_uid.end();) {
    if (completed.count(it->second.bundle_id)) it = bundle_assignment_by_request_uid.erase(it);
    else ++it;
  }
  for (auto it = bundle_lane_owners.begin(); it != bundle_lane_owners.end();) {
    if (completed.count(it->first.second)) {
      bundle_member_counts.erase(it->first);
      it = bundle_lane_owners.erase(it);
    } else ++it;
  }
}

static bool access_warp_is_blocked(
    const trafficgen_l2_access_t &access,
    const std::set<socket_warp_key_t> &blocked_warp_ids) {
  const socket_warp_key_t key{
      access.sm_id,
      static_cast<unsigned>(access.scheduler_id),
      access.warp_id, access.launch_id};
  return blocked_warp_ids.find(key) != blocked_warp_ids.end();
}

void trafficgen_t::refresh_pending_access_blocked_annotations() {
  /*
    For every pending access, update the m_warp_blocked field based on the current round's blocked warp IDs
  */
  for (auto &[cycle, accesses] : pending_accesses_by_cycle) {
    (void)cycle;
    for (auto &access : accesses) {
      access.m_warp_blocked =
          access_warp_is_blocked(access, current_round_blocked_warp_ids);
    }
  }
}

void trafficgen_t::refresh_upload_access_blocked_annotations() {
  /*
    For every access in the current upload chunk, update the m_warp_blocked field based on the current round's blocked warp IDs
  */
  for (auto &access : l2_accesses) {
    access.m_warp_blocked =
        access_warp_is_blocked(access, current_round_blocked_warp_ids);
  }
}

void trafficgen_t::build_next_l2_access_chunk() {
  /*
    Build the set of accesses to upload to target for next scheduling round,
    rounded down to the nearest cycle boundary that fits in BRAM
  */

  l2_accesses.clear();
  refresh_pending_access_blocked_annotations();
  const size_t max_entries = access_window_bytes / L2_ACCESS_STREAM_BYTES;
  if (pending_accesses_by_cycle.empty()) {
    return;
  }
  if (max_entries == 0) {
    throw std::runtime_error("TrafficGen L2 access BRAM window has zero entry capacity");
  }

  if (use_rtl_engine) {
    const size_t bank_stride_bytes = access_window_bytes / lane_count;
    if (bank_stride_bytes % L2_ACCESS_STREAM_BYTES != 0) {
      throw std::runtime_error("TrafficGen access lane bank is not entry aligned");
    }
    const size_t lane_capacity = bank_stride_bytes / L2_ACCESS_STREAM_BYTES;
    if (lane_capacity == 0) {
      throw std::runtime_error("TrafficGen access lane bank has zero entry capacity");
    }

    if (trafficgen_bundle_recovery::has_promotions(promoted_bundles)) {
      l2_accesses = trafficgen_bundle_recovery::build_promoted_upload(
          pending_accesses_by_cycle, promoted_bundles, lane_capacity);
    } else {
      std::vector<size_t> lane_entries(lane_count, 0);
      for (const auto &[cycle, accesses] : pending_accesses_by_cycle) {
        (void)cycle;
        std::vector<size_t> bucket_lane_entries(lane_count, 0);
        for (const auto &access : accesses) {
          if (access.assigned_lane >= lane_count) {
            throw std::runtime_error("TrafficGen pending access has invalid replay lane");
          }
          ++bucket_lane_entries[access.assigned_lane];
        }

        bool bucket_fits = true;
        for (size_t lane = 0; lane < lane_count; ++lane) {
          bucket_fits &= lane_entries[lane] + bucket_lane_entries[lane] <= lane_capacity;
        }
        if (bucket_fits) {
          l2_accesses.insert(l2_accesses.end(), accesses.begin(), accesses.end());
          for (size_t lane = 0; lane < lane_count; ++lane) {
            lane_entries[lane] += bucket_lane_entries[lane];
          }
          continue;
        }

        // Stop at the first cycle that does not fit.  A fitting prefix from that
        // cycle is still useful, and the remainder is replayed by the next
        // capacity refill before any later-cycle access is uploaded.
        std::vector<size_t> access_lanes;
        access_lanes.reserve(accesses.size());
        for (const auto &access : accesses) access_lanes.push_back(access.assigned_lane);
        const size_t prefix = trafficgen_lane_assignment::fitting_lane_prefix(
            access_lanes, lane_entries, lane_capacity);
        l2_accesses.insert(l2_accesses.end(), accesses.begin(), accesses.begin() + prefix);
        break;
      }
    }

    if (l2_accesses.empty()) {
      throw std::runtime_error("TrafficGen could not fit a pending access in any lane bank");
    }
  } else {
    const auto &first_bucket = pending_accesses_by_cycle.begin()->second;
    if (first_bucket.size() > max_entries) {
      std::ostringstream oss;
      oss << "TrafficGen cycle bucket exceeds maxL2AccessEntries: cycle="
          << pending_accesses_by_cycle.begin()->first
          << " bucket_entries=" << first_bucket.size()
          << " capacity=" << max_entries;
      throw std::runtime_error(oss.str());
    }

    size_t chunk_entries = 0;
    for (const auto &[cycle, accesses] : pending_accesses_by_cycle) {
      (void)cycle;
      if (chunk_entries + accesses.size() > max_entries) {
        break;
      }
      l2_accesses.insert(l2_accesses.end(), accesses.begin(), accesses.end());
      chunk_entries += accesses.size();
    }
  }

  std::cout << "[bridge driver] built L2 access chunk: chunk_entries="
            << l2_accesses.size()
            << " pending_entries=" << pending_access_cycle_by_id.size()
            << " capacity=" << max_entries << std::endl;
}

L2SubpartitionReservationsByCycle trafficgen_t::build_reserved_subpartitions_from_pending() const {
  if (lrc_ledger.batch_active()) return lrc_ledger.reservations(last_reported_cycle);

  // create the {cycle : subpartition} reservation map from the un-issued accesses to send to scheduler

  L2SubpartitionReservationsByCycle reservations;
  for (const auto &[cycle, accesses] : pending_accesses_by_cycle) {
    auto &reserved_subpartitions = reservations[cycle];
    for (const auto &access : accesses) {
      reserved_subpartitions.insert(access.m_subpartition);
    }
    if (reserved_subpartitions.empty()) {
      reservations.erase(cycle);
    }
  }
  return reservations;
}

void trafficgen_t::depopulate_issued_accesses(
    const std::vector<trafficgen_issued_access_point_t> &issued_accesses,
    bool require_complete_upload) {
  trafficgen_bundle_recovery::remove_fresh_issued(
      l2_accesses, issued_accesses, pending_accesses_by_cycle,
      pending_access_cycle_by_id, require_complete_upload);
  if (lrc_ledger.batch_active()) {
    // Keep precisely the unissued part of the physical window. Rebuilding a
    // maximum-size chunk here could admit requests never uploaded to hardware;
    // retaining consumed members would break the eventual capacity check.
    l2_accesses.erase(std::remove_if(l2_accesses.begin(), l2_accesses.end(),
        [&](const auto &a) { return !pending_access_cycle_by_id.count({a.launch_id,a.id}); }),
        l2_accesses.end());
  }
  if (use_rtl_engine) {
    trafficgen_bundle_recovery::retire_issued_promotions(
        pending_accesses_by_cycle, promoted_bundles);
  }
}

void trafficgen_t::recover_full_bundle_tables(std::uint32_t lane_mask,
                                             std::uint64_t current_cycle) {
  if (!use_rtl_engine) {
    throw std::runtime_error("TrafficGen DPI engine reported an RTL bundle-table exit");
  }
  // Select resident bundles to finish and free a bundle-table slot.
  // note that the promoted bundle retains its original timing
  const auto bundles = trafficgen_bundle_recovery::summarize_pending(
      pending_accesses_by_cycle, bundle_lane_owners, bundle_member_counts, lane_count);
  trafficgen_bundle_recovery::select_promotions(
      bundles, lane_mask, current_cycle, promoted_bundles);
  build_next_l2_access_chunk();

  ++recovery_number;
  const auto recovery_dir = round_log_root / ("recovery_" + std::to_string(recovery_number));
  std::filesystem::create_directories(recovery_dir);
  std::ofstream selection(recovery_dir / "selection.csv");
  std::ofstream upload(recovery_dir / "upload.csv");
  if (!selection || !upload) {
    throw std::runtime_error("TrafficGen failed to open bundle recovery logs: " + recovery_dir.string());
  }
  selection << "recovery,hardware_boundary,gpu_round,current_cycle,lane_mask,lane,bundle_generation,bundle_id,remaining,original_count,latest_scheduled_cycle,newly_selected\n";
  for (std::size_t lane = 0; lane < lane_count; ++lane) {
    if (!promoted_bundles[lane]) continue;
    const auto &key = *promoted_bundles[lane];
    const auto &bundle = bundles.at(key);
    selection << recovery_number << ',' << hardware_boundary_number << ','
              << engine_round_number << ',' << current_cycle << ','
              << lane_mask << ',' << lane << ',' << key.first << ',' << key.second << ','
              << bundle.remaining << ',' << bundle.original_count << ',' << bundle.latest_cycle
              << ',' << ((lane_mask & (std::uint32_t{1} << lane)) != 0) << '\n';
    std::cout << "[bridge driver] bundle-table recovery=" << recovery_number
              << " hardware_boundary=" << hardware_boundary_number
              << " gpu_round=" << engine_round_number << " cycle=" << current_cycle
              << " lane=" << lane << " generation=" << key.first << " bundle=" << key.second
              << " remaining=" << bundle.remaining << std::endl;
  }
  upload << "lane,lane_index,launch_id,registry_id,request_uid,scheduled_cycle,bundle_generation,bundle_id,member_count,address,is_write,promoted\n";
  std::vector<std::size_t> lane_indices(lane_count, 0);
  for (const auto &access : l2_accesses) {
    const auto lane = access.assigned_lane;
    upload << lane << ',' << lane_indices[lane]++ << ',' << access.launch_id << ',' << access.registry_id << ',' << access.id << ',' << access.cycle_count
           << ',' << access.bundle_generation << ',' << access.m_bundle_id << ','
           << access.bundle_issue_count << ',' << access.address << ',' << access.m_is_write
           << ',' << (promoted_bundles[lane] && *promoted_bundles[lane] ==
                       trafficgen_bundle_recovery::key_of(access)) << '\n';
  }
  if (!selection || !upload) {
    throw std::runtime_error("TrafficGen failed to write bundle recovery logs: " + recovery_dir.string());
  }
}

void trafficgen_t::assign_replay_lanes_and_bundle_counts() {
  std::map<bundle_key_t, std::size_t> pending_bundle_counts;
  // Count the entire currently available GPU schedule, not just this BRAM
  // chunk.  The first member tells the RTL engine how many members remain in
  // this scheduler generation; capacity refills then continue that same
  // generation without allowing a partial bundle to complete early.
  for (const auto &[cycle, accesses] : pending_accesses_by_cycle) {
    (void)cycle;
    for (const auto &access : accesses) {
      ++pending_bundle_counts[{access.bundle_generation, access.m_bundle_id}];
    }
  }

  for (const auto &[key, count] : pending_bundle_counts) {
    trafficgen_lane_assignment::remember_member_count(
        bundle_member_counts, key, count);
  }

  if (use_rtl_engine) {
    const auto balance = trafficgen_lane_assignment::assign_bundles(
        pending_bundle_counts, lane_count, bundle_lane_owners);

    for (auto &[cycle, accesses] : pending_accesses_by_cycle) {
      (void)cycle;
      for (auto &access : accesses) {
        const bundle_key_t key{access.bundle_generation, access.m_bundle_id};
        access.assigned_lane = bundle_lane_owners.at(key);
        access.bundle_issue_count = bundle_member_counts.at(key);
        const trafficgen_bundle_assignment_t assignment{
            access.bundle_generation,
            access.m_bundle_id,
            access.assigned_lane,
            access.bundle_issue_count};
        const auto [it, inserted] =
            bundle_assignment_by_request_uid.emplace(trafficgen_request_key_t{access.launch_id, access.id}, assignment);
        if (!inserted &&
            (it->second.generation != assignment.generation ||
             it->second.bundle_id != assignment.bundle_id ||
             it->second.lane != assignment.lane ||
             it->second.member_count != assignment.member_count)) {
          throw std::runtime_error(
              "TrafficGen request UID changed bundle assignment across refills");
        }
      }
    }

    std::cout << "[bridge driver] bundle lane balance";
    for (size_t lane = 0; lane < lane_count; ++lane) {
      std::cout << " lane" << lane << "=" << balance.bundle_counts[lane]
                << "b/" << balance.access_counts[lane] << "a";
    }
    std::cout << std::endl;
    return;
  }

  for (auto &access : l2_accesses) {
    const auto count = pending_bundle_counts.at(
        {access.bundle_generation, access.m_bundle_id});
    access.bundle_issue_count = static_cast<std::uint16_t>(count);
  }

  std::vector<std::size_t> lane_counts(lane_count, 0);
  for (std::size_t begin = 0; begin < l2_accesses.size();) {
    std::size_t end = begin + 1;
    while (end < l2_accesses.size() &&
           l2_accesses[end].cycle_count == l2_accesses[begin].cycle_count) {
      ++end;
    }
    const std::size_t group_size = end - begin;
    if (group_size > lane_count) {
      throw std::runtime_error("TrafficGen schedule has more accesses in one cycle than lanes");
    }
    std::unordered_set<std::uint32_t> subpartitions;
    for (std::size_t i = begin; i < end; ++i) {
      if (!subpartitions.insert(l2_accesses[i].m_subpartition).second) {
        throw std::runtime_error(
            "TrafficGen schedule has duplicate L2 subpartition in one cycle");
      }
    }

    std::vector<std::size_t> lane_order(lane_count);
    std::iota(lane_order.begin(), lane_order.end(), 0);
    std::stable_sort(lane_order.begin(), lane_order.end(),
                     [&lane_counts](std::size_t lhs, std::size_t rhs) {
                       if (lane_counts[lhs] != lane_counts[rhs])
                         return lane_counts[lhs] < lane_counts[rhs];
                       return lhs < rhs;
                     });
    for (std::size_t i = 0; i < group_size; ++i) {
      const std::size_t lane = lane_order[i];
      l2_accesses[begin + i].assigned_lane = lane;
      ++lane_counts[lane];
    }
    begin = end;
  }

  const auto [min_it, max_it] = std::minmax_element(lane_counts.begin(), lane_counts.end());
  if (*max_it - *min_it > 1) {
    throw std::runtime_error("TrafficGen replay lane coloring is unbalanced");
  }
}

void trafficgen_t::log_engine_round_for_compare(
    const std::vector<trafficgen_issued_access_point_t> &issued_accesses,
    const std::vector<std::uint64_t> &completed_bundle_ids,
    std::uint64_t current_cycle_after_issue) const {
  if (engine_round_number == 0) {
    return;
  }

  const std::filesystem::path round_dir =
      round_log_root / format_round_log_dir(engine_round_number);
  create_directory_if_needed(round_log_root.string());
  create_directory_if_needed(round_dir.string());

  std::vector<IssuedAccessPoint> issued_snapshot_points;
  issued_snapshot_points.reserve(issued_accesses.size());
  for (const auto &access : issued_accesses) {
    IssuedAccessPoint point{};
    point.launchId = access.launch_id;
    point.requestUid = access.request_uid;
    point.cycleIssued = access.cycle_issued;
    point.address = access.address;
    point.isWrite = access.is_write;
    issued_snapshot_points.push_back(point);
  }

  write_socket_snapshot((round_dir / "reserved_subpartitions.bin").string(),
                        OrderedReservedSubpartitionsSnapshot{
                            round_input_reserved_subpartitions});
  write_socket_snapshot((round_dir / "all_l2_trace_steps.bin").string(),
                        SocketAllL2TraceStepsSnapshot{current_round_all_l2_trace_steps});
  write_socket_snapshot((round_dir / "blocked_warp_ids.bin").string(),
                        SocketBlockedWarpIdsSnapshot{current_round_blocked_warp_ids});
  write_socket_snapshot((round_dir / "min_issue_cycle.bin").string(),
                        MinIssueCycleSnapshot{min_issue_cycle});
  write_socket_snapshot((round_dir / "issued_accesses.bin").string(),
                        IssuedAccessesSnapshot{issued_snapshot_points});
  write_socket_snapshot((round_dir / "completed_bundle_ids.bin").string(),
                        CompletedBundleIdsSnapshot{completed_bundle_ids});
  write_socket_snapshot((round_dir / "current_cycle_after_issue.bin").string(),
                        CurrentCycleAfterIssueSnapshot{current_cycle_after_issue});

  std::ofstream lane_assignments(round_dir / "lane_assignments.csv");
  if (!lane_assignments.is_open()) {
    throw std::runtime_error("failed to write lane assignment snapshot: " +
                             (round_dir / "lane_assignments.csv").string());
  }
  lane_assignments << "launch_id,registry_id,request_uid,bundle_generation,bundle_id,lane,member_count\n";
  for (const auto &access : current_round_all_l2_trace_steps) {
    const auto assignment =
        bundle_assignment_by_request_uid.find({access.launchId, access.mUniqueId});
    if (assignment == bundle_assignment_by_request_uid.end()) {
      if (access.mBatchAction == LrcBatchAction::Attach || access.mBatchAction == LrcBatchAction::Reuse) continue;
      throw std::runtime_error(
          "TrafficGen missing bundle lane assignment while logging round");
    }
    lane_assignments << access.launchId << ',' << access.registryId << ',' << access.mUniqueId << ','
                     << assignment->second.generation << ','
                     << assignment->second.bundle_id << ','
                     << assignment->second.lane << ','
                     << assignment->second.member_count << '\n';
  }

  std::size_t reserved_subpartition_count = 0;
  for (const auto &[cycle, subpartitions] : round_input_reserved_subpartitions) {
    (void)cycle;
    reserved_subpartition_count += subpartitions.size();
  }
  std::ofstream manifest(round_dir / "manifest.txt");
  if (!manifest.is_open()) {
    throw std::runtime_error("failed to write round manifest: " +
                             (round_dir / "manifest.txt").string());
  }
  manifest << "round=" << engine_round_number << "\n";
  manifest << "files=reserved_subpartitions.bin,all_l2_trace_steps.bin,"
           << "blocked_warp_ids.bin,min_issue_cycle.bin,issued_accesses.bin,"
           << "completed_bundle_ids.bin,current_cycle_after_issue.bin,"
           << "lane_assignments.csv\n";
  manifest << "reserved_cycle_count=" << round_input_reserved_subpartitions.size() << "\n";
  manifest << "reserved_subpartition_count=" << reserved_subpartition_count << "\n";
  manifest << "all_l2_trace_steps_count=" << current_round_all_l2_trace_steps.size() << "\n";
  manifest << "blocked_warp_ids_count=" << current_round_blocked_warp_ids.size() << "\n";
  manifest << "min_issue_cycle=" << min_issue_cycle << "\n";
  manifest << "issued_accesses_count=" << issued_accesses.size() << "\n";
  manifest << "completed_bundle_ids_count=" << completed_bundle_ids.size() << "\n";
  manifest << "current_cycle_after_issue=" << current_cycle_after_issue << "\n";
}

void trafficgen_t::receive_schedule_from_gpu_model() {
  /*
    Read the L2 access schedule and blocked warp IDs from GPU model socket
  */
  if (!gpu_model_socket_client) {
    throw std::runtime_error(
        "gpu_model socket client is not initialized");
  }

  const SchedulerRoundMessage message =
      gpu_model_socket_client->recv_message<SchedulerRoundMessage>();

  ++engine_round_number;
  current_round_all_l2_trace_steps = message.allL2TraceSteps;
  current_round_blocked_warp_ids = message.blockedWarpIds;
  log_socket_round_inputs_for_compare(message.allL2TraceSteps,
                                      message.blockedWarpIds, socket_round_log_root,
                                      engine_round_number);

  min_issue_cycle = message.min_issue_cycle;
  std::vector<trafficgen_l2_access_t> fresh_accesses;
  fresh_accesses.reserve(message.allL2TraceSteps.size());
  const bool already_native = lrc_ledger.batch_active();
  if (!use_rtl_engine && trafficgen_tma_policy::nonempty(message.tmaWakePolicy))
    throw std::runtime_error("TrafficGen DPI backend does not support TMA wake policies");
  const auto physical_accesses = lrc_ledger.add_schedule(message.allL2TraceSteps,
      message.blockedWarpIds, last_reported_cycle, trafficgen_tma_policy::nonempty(message.tmaWakePolicy));
  tma_policy.replace(message.tmaWakePolicy,lrc_ledger);
  lrc_ledger.suppress_tma_wakes(tma_policy.known());
  tma_diagnostics = {}; tma_wake_cycles.clear(); ordinary_wake_cycles.clear();
  {
    write_socket_snapshot((round_log_root / ("tma_policy_" + std::to_string(engine_round_number) + ".bin")).string(),
                          message.tmaWakePolicy);
    std::ofstream outstanding(round_log_root / ("tma_outstanding_" + std::to_string(engine_round_number) + ".csv"));
    outstanding << "launch_id,request_uid\n";
    for (const auto &id : message.tmaWakePolicy.outstandingRequests)
      outstanding << id.launchId << ',' << id.requestUid << '\n';
    if (!outstanding) throw std::runtime_error("TrafficGen cannot write TMA outstanding-request snapshot");
    std::ofstream policy(round_log_root / ("tma_policy_" + std::to_string(engine_round_number) + ".csv"));
    policy << "epoch,group,launch,cta,barrier,phase,transfer,conservative,ready_cycle,compiled_floor,member_launch,member_uid\n";
    std::size_t slot=0;
    for (const auto &g : tma_policy.groups()) {
      const auto &p=g.source;
      auto prefix = [&] { policy << tma_policy.epoch() << ',' << slot << ',' << p.launchId << ',' << p.destinationCta
        << ',' << p.barrierAddress << ',' << p.phase << ',' << p.transferId << ',' << p.conservative
        << ',' << p.readyCycle << ',' << g.floor << ','; };
      if (p.requests.empty()) { prefix(); policy << ",\n"; }
      for (const auto &id : p.requests) { prefix(); policy << id.launchId << ',' << id.requestUid << '\n'; }
      ++slot;
    }
    if (!policy) throw std::runtime_error("TrafficGen cannot write TMA policy snapshot");
  }
  lrc_new_round = true;
  lrc_round_drain_only = lrc_ledger.drain_only();
  lrc_metadata_committed = false;
  for (const auto &wire_access : physical_accesses) {
    trafficgen_l2_access_t access{};
    access.id = wire_access.mUniqueId;
    access.launch_id = wire_access.launchId;
    access.registry_id = wire_access.registryId;
    const auto launch = launch_records.find(access.launch_id);
    if (launch == launch_records.end() || launch->second.registry_id != access.registry_id)
      throw std::runtime_error("TrafficGen access has unknown launch/registry identity");
    access.address = wire_access.mAddress;
    access.cycle_count = wire_access.mCycleCount;
    access.m_subpartition = checked_u32(
        static_cast<std::uint64_t>(wire_access.mSubpartition),
        "l2 access subpartition");
    access.m_set_index = checked_u32(
        static_cast<std::uint64_t>(wire_access.mSetIndex),
        "l2 access set index");
    access.m_tag = wire_access.mTag;
    access.m_mask = checked_u32(
        static_cast<std::uint64_t>(wire_access.mMask), "l2 access mask");
    access.sm_id = checked_u32(
        static_cast<std::uint64_t>(wire_access.smId), "l2 access sm id");
    access.scheduler_id = checked_u8(
        static_cast<std::uint64_t>(wire_access.schedulerId),
        "l2 access scheduler id");
    access.warp_id = checked_u32(
        static_cast<std::uint64_t>(wire_access.warpId), "l2 access warp id");
    access.m_bundle_id = wire_access.mLrcEnabled ?
        ((std::uint64_t{1} << 63) | wire_access.mPhysicalId) : wire_access.mBundleId;
    access.m_wake_relevant_bundle = wire_access.mWakeRelevantBundle;
    access.m_is_write = wire_access.mIsWrite;
    access.bundle_generation =
        checked_u32(engine_round_number, "bundle generation");
    fresh_accesses.push_back(access);
  }
  if (!lrc_ledger.batch_active()) bundle_tracker.add_schedule(fresh_accesses);
  else bundle_tracker.clear();
  for (const auto &access : fresh_accesses) {
    if (!pending_access_cycle_by_id.emplace(trafficgen_request_key_t{access.launch_id, access.id}, access.cycle_count).second)
      throw std::runtime_error("TrafficGen duplicate pending request identity");
    pending_accesses_by_cycle[access.cycle_count].push_back(access);
  }
  accumulated_issued_accesses.clear();
  accumulated_completed_bundle_ids.clear();

  lrc_reuse_window = already_native && lrc_window_valid && physical_accesses.empty() &&
      (!l2_accesses.empty() || pending_access_cycle_by_id.empty());
  if (lrc_reuse_window) {
    // Native wake interest lives in logical metadata. Neither physical lane
    // annotations nor replay cursors change for Attach/Reuse/empty schedules.
  } else if (use_rtl_engine) {
    refresh_pending_access_blocked_annotations();
    assign_replay_lanes_and_bundle_counts();
    build_next_l2_access_chunk();
  } else {
    build_next_l2_access_chunk();
    assign_replay_lanes_and_bundle_counts();
  }

  std::cout << "[bridge driver] received schedule from gpu_model_socket: "
            << "new_l2_accesses=" << message.allL2TraceSteps.size()
            << " pending_l2_accesses=" << pending_access_cycle_by_id.size()
            << " upload_accesses=" << l2_accesses.size()
            << " blocked_warps=" << message.blockedWarpIds.size()
            << " min_issue_cycle=" << min_issue_cycle << std::endl;
}

size_t trafficgen_t::process_completed_bundle_ids_stream() {
  // The target is paused, so the count and corresponding BRAM contents stay
  // stable until resume. Validate the count before issuing any XDMA read.
  completed_bundle_count = read(mmio_addrs.completed_bundle_count);
  if (completed_bundle_count > COMPLETED_BUNDLE_ID_COUNT) {
    std::ostringstream error;
    error << "TrafficGen completedBundleIds count exceeds capacity: count="
          << completed_bundle_count
          << " capacity=" << COMPLETED_BUNDLE_ID_COUNT;
    throw std::runtime_error(error.str());
  }

  // Eight 64-bit IDs occupy one 512-bit beat; ignore padding after the last ID.
  const size_t valid_bytes = static_cast<size_t>(completed_bundle_count) * sizeof(uint64_t);
  const size_t total_bytes =
      ((valid_bytes + STREAM_WIDTH_BYTES - 1) / STREAM_WIDTH_BYTES) * STREAM_WIDTH_BYTES;
  if (completed_window_bytes < total_bytes) {
    throw std::runtime_error("TrafficGen completed-bundle BRAM window is too small");
  }
  if (total_bytes != 0) {
    auto &xdma = simif.get_cpu_managed_stream_io();
    auto stream_bytes = make_aligned_bytes(total_bytes);
    xdma_read_exact(
        xdma,
        bram_base + raw_completed_bundle_ids_offset,
        stream_bytes.get(),
        total_bytes,
        "XDMA read while reading completedBundleIds");
    std::memcpy(completed_bundle_ids.data(), stream_bytes.get(), valid_bytes);
  }

  std::cout << "[bridge driver] completedBundleIds count="
            << completed_bundle_count << std::endl;
  return total_bytes;
}

void trafficgen_t::write_upload_lane_count(unsigned lane, std::uint32_t count) {
  if (lane >= lane_count) throw std::out_of_range("TrafficGen upload lane");
  write(mmio_addrs.upload_lane_count_index, lane);
  write(mmio_addrs.upload_lane_count_value, count);
  write(mmio_addrs.upload_lane_count_write, 1);
}

std::uint32_t trafficgen_t::read_issued_lane_count(unsigned lane) {
  if (lane >= lane_count) throw std::out_of_range("TrafficGen issued lane");
  write(mmio_addrs.issued_lane_count_index, lane);
  return static_cast<std::uint32_t>(read(mmio_addrs.issued_lane_count_value));
}

size_t
trafficgen_t::process_issued_access_writeback_stream() {
  if (issued_access_writeback_count == 0) {
    return 0;
  }
  const size_t total_bytes =
      static_cast<size_t>(issued_access_writeback_count) * STREAM_WIDTH_BYTES;
  if (issued_access_writeback_bytes_received >= total_bytes) {
    return 0;
  }

  if (total_bytes > access_window_bytes) {
    throw std::runtime_error("TrafficGen issued-access readback exceeds BRAM access window");
  }
  if (access_window_bytes % lane_count != 0) {
    throw std::runtime_error("TrafficGen issued-access window is not lane divisible");
  }
  const std::size_t bank_stride_bytes = access_window_bytes / lane_count;
  auto &xdma = simif.get_cpu_managed_stream_io();
  issued_access_writeback_entries.clear();
  issued_access_writeback_entries.reserve(issued_access_writeback_count);
  std::size_t summed_count = 0;
  for (std::size_t lane = 0; lane < lane_count; ++lane) {
    const auto first = issued_lane_read_cursors.at(lane);
    const auto count = issued_lane_snapshot_counts.at(lane) - first;
    const std::size_t lane_bytes = static_cast<std::size_t>(count) * STREAM_WIDTH_BYTES;
    summed_count += count;
    if (lane_bytes == 0) continue;
    auto lane_data = make_aligned_bytes(lane_bytes);
    xdma_read_exact(
        xdma,
        bram_base + raw_issued_access_writeback_store_offset + lane * bank_stride_bytes +
            static_cast<std::size_t>(first) * STREAM_WIDTH_BYTES,
        lane_data.get(), lane_bytes,
        "XDMA read while reading banked issuedAccessWriteback");
    for (std::uint32_t idx = 0; idx < count; ++idx) {
      const auto *words = reinterpret_cast<const std::uint64_t *>(
          lane_data.get() + static_cast<std::size_t>(idx) * STREAM_WIDTH_BYTES);
      auto access = unpack_issued_access(words);
      issued_access_writeback_entries.push_back(access);
    }
  }
  if (summed_count != issued_access_writeback_count) {
    throw std::runtime_error("TrafficGen issued per-lane counts do not match total");
  }
  std::stable_sort(issued_access_writeback_entries.begin(),
                   issued_access_writeback_entries.end(),
                   [](const auto &lhs, const auto &rhs) {
                     return lhs.cycle_issued < rhs.cycle_issued;
                   });
  issued_access_writeback_bytes_received = total_bytes;
  issued_lane_read_cursors = issued_lane_snapshot_counts;

  std::cout << "[bridge driver] issuedAccessWriteback count="
            << issued_access_writeback_count << std::endl;
  return total_bytes;
}

void trafficgen_t::write_schedule_to_bram() {
  if (upload_written_to_bram) {
    return;
  }
  if (lrc_reuse_window) {
    upload_lrc_metadata();
    std::cout << "[bridge driver] retaining native replay window: remaining_entries="
              << l2_accesses.size() << std::endl;
    upload_written_to_bram = true;
    return;
  }

  // instead of a blocked warp map in the RTL engine, we annotate the pending and upload accesses with 
  // the blocked warp status from current round's blocked warp IDs
  refresh_pending_access_blocked_annotations();
  refresh_upload_access_blocked_annotations();

  // using this lets it run in metasim and on FPGA because of how simif abstracts away the details of stream I/O for XDMA
  auto &xdma = simif.get_cpu_managed_stream_io();
  for (auto &access : l2_accesses)
    access.physical_token = lrc_ledger.stage_service({access.launch_id,access.id});
  upload_lrc_metadata();

  const size_t access_bytes = l2_accesses.size() * L2_ACCESS_STREAM_BYTES;
  if (access_bytes > access_window_bytes) {
    throw std::runtime_error("TrafficGen L2 access upload exceeds BRAM access window");
  }
  if (l2_accesses.size() > std::numeric_limits<std::uint32_t>::max()) {
    throw std::runtime_error("TrafficGen L2 access upload count exceeds MMIO width");
  }
  if (access_window_bytes % lane_count != 0) {
    throw std::runtime_error("TrafficGen access window is not lane divisible");
  }
  const std::size_t bank_stride_bytes = access_window_bytes / lane_count;
  std::vector<std::vector<const trafficgen_l2_access_t *>> lanes(lane_count);
  std::map<bundle_key_t, std::size_t> upload_bundle_owners;
  for (const auto &access : l2_accesses) {
    if (access.assigned_lane >= lane_count) {
      throw std::runtime_error("TrafficGen access has invalid replay lane");
    }
    if (use_rtl_engine) {
      const bundle_key_t key{access.bundle_generation, access.m_bundle_id};
      const auto persistent_owner = bundle_lane_owners.find(key);
      if (persistent_owner == bundle_lane_owners.end() ||
          persistent_owner->second != access.assigned_lane) {
        throw std::runtime_error("TrafficGen access disagrees with bundle lane owner");
      }
      const auto [owner, inserted] =
          upload_bundle_owners.emplace(key, access.assigned_lane);
      if (!inserted && owner->second != access.assigned_lane) {
        throw std::runtime_error("TrafficGen upload splits a bundle across lanes");
      }
      const auto member_count = bundle_member_counts.find(key);
      if (member_count == bundle_member_counts.end() ||
          member_count->second != access.bundle_issue_count) {
        throw std::runtime_error("TrafficGen access has inconsistent bundle member count");
      }
    }
    lanes[access.assigned_lane].push_back(&access);
  }
  for (std::size_t lane = 0; lane < lane_count; ++lane) {
    const std::size_t lane_bytes = lanes[lane].size() * L2_ACCESS_STREAM_BYTES;
    if (lane_bytes > bank_stride_bytes) {
      throw std::runtime_error("TrafficGen access lane exceeds bank capacity");
    }
    write_upload_lane_count(lane, static_cast<std::uint32_t>(lanes[lane].size()));
    if (lane_bytes == 0) continue;
    auto packed = make_aligned_bytes(lane_bytes);
    std::memset(packed.get(), 0, lane_bytes);
    auto *packed_words = reinterpret_cast<std::uint64_t *>(packed.get());
    for (std::size_t idx = 0; idx < lanes[lane].size(); ++idx) {
      pack_l2_access(
          *lanes[lane][idx],
          packed_words + idx * STREAM_WORDS_PER_BEAT,
          use_rtl_engine);
    }
    auto &previous = last_lane_upload_bytes.at(lane);
    if (previous.size() == lane_bytes &&
        std::memcmp(previous.data(), packed.get(), lane_bytes) == 0) {
      std::cout << "[bridge driver] reusing unchanged L2 access lane=" << lane
                << " entries=" << lanes[lane].size()
                << " bytes=" << lane_bytes << std::endl;
      continue;
    }
    std::cout << "[bridge driver] uploading L2 access lane=" << lane
              << " entries=" << lanes[lane].size()
              << " bytes=" << lane_bytes << std::endl;
    xdma_write_exact(
        xdma,
        bram_base + raw_access_store_offset + lane * bank_stride_bytes,
        packed.get(), lane_bytes,
        "XDMA write while uploading banked TrafficGen L2 accesses");
    // Remember only fully successful writes. All lane counts and upload commit
    // metadata are still published, including when every payload is unchanged.
    previous.assign(packed.get(), packed.get() + lane_bytes);
    std::cout << "[bridge driver] uploaded L2 access lane=" << lane
              << std::endl;
  }

  // write access metadata into MMIO registers
  const std::uint64_t max_cycle =
      trafficgen_bundle_recovery::maximum_scheduled_cycle(l2_accesses);
  write(mmio_addrs.upload_count, static_cast<uint32_t>(l2_accesses.size()));
  write(mmio_addrs.access_store_max_cycle_low,
        static_cast<uint32_t>(max_cycle & 0xffffffffULL));
  write(mmio_addrs.access_store_max_cycle_high,
        static_cast<uint32_t>(max_cycle >> 32));
  write(mmio_addrs.access_store_has_more,
        pending_access_cycle_by_id.size() > l2_accesses.size() ? 1 : 0);
  write(mmio_addrs.commit_upload, 1);
  issued_lane_read_cursors.assign(lane_count, 0);
  issued_lane_snapshot_counts.assign(lane_count, 0);
  lrc_window_valid = lrc_ledger.batch_active();

  upload_written_to_bram = true;
}

void trafficgen_t::upload_lrc_metadata() {
  if (lrc_metadata_committed) return;
  if (read(mmio_addrs.lrc_service_capacity) != 65536 ||
      read(mmio_addrs.lrc_bundle_capacity) != 65536 || read(mmio_addrs.lrc_timer_capacity) != 65536)
    throw std::runtime_error("TrafficGen requires native LRC monitor with 65536 service/bundle/timer slots");
  auto descriptors = lrc_ledger.descriptors(true);
  const auto wake_descriptors = tma_policy.descriptors(lrc_ledger);
  descriptors.insert(descriptors.end(),wake_descriptors.begin(),wake_descriptors.end());
  auto &xdma = simif.get_cpu_managed_stream_io();
  for (std::size_t offset = 0; offset < descriptors.size() || offset == 0; offset += kLrcMetadataEntries) {
    const auto count = std::min<std::size_t>(kLrcMetadataEntries, descriptors.size()-offset);
    if (count) {
      auto data = make_aligned_bytes(count * 64);
      std::memcpy(data.get(), descriptors.data()+offset, count * 64);
      xdma_write_exact(xdma, bram_base+LRC_METADATA_OFFSET, data.get(), count*64, "LRC metadata upload");
    }
    write(mmio_addrs.lrc_metadata_count, static_cast<std::uint32_t>(count));
    write(mmio_addrs.lrc_commit_metadata, 1);
    std::size_t polls = 0;
    while (!read(mmio_addrs.lrc_metadata_ready))
      if (++polls == 1000000) throw std::runtime_error("LRC metadata commit did not finish");
    if (read(mmio_addrs.lrc_error)) throw std::runtime_error("LRC monitor rejected metadata");
    if (!count) break;
  }
  write(mmio_addrs.lrc_enable, lrc_ledger.batch_active());
  write(mmio_addrs.lrc_refill_only, !lrc_new_round);
  write(mmio_addrs.lrc_drain_only, lrc_round_drain_only);
  const auto horizon = lrc_ledger.arrival_horizon();
  write(mmio_addrs.lrc_arrival_horizon_low, static_cast<std::uint32_t>(horizon));
  write(mmio_addrs.lrc_arrival_horizon_high, static_cast<std::uint32_t>(horizon >> 32));
  if (lrc_new_round) write(mmio_addrs.lrc_begin_round, 1);
  lrc_ledger.descriptors_committed();
  tma_policy.committed(lrc_ledger);
  lrc_new_round = false;
  lrc_metadata_committed = true;
}

void trafficgen_t::drain_lrc_reports() {
  if (read(mmio_addrs.lrc_error)) throw std::runtime_error("LRC native monitor error");
  const std::size_t count = read(mmio_addrs.lrc_report_count);
  if (count > 131072) throw std::runtime_error("LRC native report window overflow");
  std::vector<trafficgen_lrc_ledger::descriptor_t> records(count);
  if (count) {
    auto data = make_aligned_bytes(count * 64);
    xdma_read_exact(simif.get_cpu_managed_stream_io(), bram_base+LRC_REPORT_OFFSET,
                    data.get(), count*64, "LRC native reports");
    std::memcpy(records.data(), data.get(), count*64);
  }
  lrc_pending_reports.insert(lrc_pending_reports.end(),records.begin(),records.end());
  write(mmio_addrs.lrc_clear_reports,1);
}

void trafficgen_t::process_lrc_reports(std::uint64_t cycle) {
  drain_lrc_reports();
  for (const auto &r : lrc_pending_reports) {
    const auto kind = trafficgen_lrc_ledger::bits(r,0,8);
    const auto token = trafficgen_lrc_ledger::bits(r,8,64);
    const auto at = trafficgen_lrc_ledger::bits(r,72,64);
    if (at > cycle || (kind < 1 || kind > 4)) throw std::runtime_error("LRC invalid native event");
    if (kind == 1) lrc_ledger.returned(token,at);
  }
  lrc_ledger.advance(cycle);
  for (const auto &r : lrc_pending_reports)
    if (trafficgen_lrc_ledger::bits(r,0,8) == 2) {
      const auto id = trafficgen_lrc_ledger::bits(r,8,64), at = trafficgen_lrc_ledger::bits(r,72,64);
      if (lrc_ledger.batch_active()) {
        if (lrc_ledger.bundles().at(id).wake) ordinary_wake_cycles.insert(at);
        lrc_ledger.complete_bundle(id,at);
      }
      else lrc_ledger.ordinary_native_retired(id,at);
    }
  for (const auto &r : lrc_pending_reports) {
    const auto kind=trafficgen_lrc_ledger::bits(r,0,8);
    if (kind<3) continue;
    const auto id=trafficgen_lrc_ledger::bits(r,8,64), at=trafficgen_lrc_ledger::bits(r,72,64);
    tma_policy.wake(id,at,kind==4,lrc_ledger);
    tma_wake_cycles.insert(at);
    if (kind==4) ++tma_diagnostics.transferWakeups; else ++tma_diagnostics.phaseWakeups;
    std::ofstream log(round_log_root / "tma_wakes.csv",std::ios::app);
    log << engine_round_number << ',' << (id>>32) << ',' << static_cast<std::uint32_t>(id) << ',' << at << ',' << kind << '\n';
    if (!log) throw std::runtime_error("TrafficGen cannot write TMA wake log");
  }
  lrc_pending_reports.clear();
  lrc_metadata_committed = false;
}

void trafficgen_t::log_lrc_events() {
  const auto events = lrc_ledger.take_events();
  const auto pending_requests = lrc_ledger.pending_request_count();
  const auto pending_services = lrc_ledger.pending_service_count();
  std::ofstream state(round_log_root / "lrc_state.json");
  if (!state) throw std::runtime_error("TrafficGen cannot write LRC ledger state");
  state << "{\"pending_requests\":" << pending_requests << ",\"pending_services\":" << pending_services
        << ",\"pending_bundles\":" << lrc_ledger.bundles().size()
        << ",\"active_service_slots\":" << lrc_ledger.active_service_slots()
        << ",\"active_bundle_slots\":" << lrc_ledger.active_bundle_slots()
        << ",\"service_high_water\":" << lrc_ledger.service_high_water()
        << ",\"bundle_high_water\":" << lrc_ledger.bundle_high_water()
        << ",\"hardware_service_count\":" << read(mmio_addrs.lrc_service_count)
        << ",\"hardware_bundle_count\":" << read(mmio_addrs.lrc_bundle_count)
        << ",\"hardware_timer_count\":" << read(mmio_addrs.lrc_timer_count)
        << ",\"hardware_report_count\":" << read(mmio_addrs.lrc_report_count)
        << ",\"hardware_service_high_water\":" << read(mmio_addrs.lrc_service_high_water)
        << ",\"hardware_bundle_high_water\":" << read(mmio_addrs.lrc_bundle_high_water)
        << ",\"hardware_timer_high_water\":" << read(mmio_addrs.lrc_timer_high_water)
        << ",\"hardware_report_high_water\":" << read(mmio_addrs.lrc_report_high_water)
        << ",\"tma_pending\":" << tma_policy.pending()
        << ",\"tma_groups\":" << read(mmio_addrs.tma_group_count)
        << ",\"tma_edges\":" << read(mmio_addrs.tma_edge_count)
        << ",\"tma_timers\":" << read(mmio_addrs.tma_timer_count)
        << ",\"tma_group_high_water\":" << read(mmio_addrs.tma_group_high_water)
        << ",\"tma_edge_high_water\":" << read(mmio_addrs.tma_edge_high_water)
        << ",\"tma_timer_high_water\":" << read(mmio_addrs.tma_timer_high_water)
        << ",\"boundary_flags\":" << read(mmio_addrs.lrc_boundary_flags) << "}\n";
  if (events.empty()) return;
  std::ofstream output(round_log_root / "lrc_events.csv",std::ios::app);
  if (!output) throw std::runtime_error("TrafficGen cannot append LRC event log");
  for (const auto &e : events)
    output << e.event << ',' << engine_round_number << ',' << e.cycle << ',' << e.launch << ','
           << e.uid << ',' << e.physical << ',' << e.recorded << ',' << e.bundle << ','
           << e.address << ',' << e.write << ',' << trafficgen_lrc_ledger::action_name(e.action) << ','
           << e.source_ready_cycle << '\n';
  if (!output) throw std::runtime_error("TrafficGen failed writing LRC events");
}

void trafficgen_t::tick() {
  if (session_failed || session_complete) return;
  try { tick_impl(); }
  catch (const std::exception &error) { fail_session(error.what()); }
}

void trafficgen_t::tick_impl() {
  switch (state) {
  case trafficgen_state_t::IDLE:
    if (read(mmio_addrs.start_trafficgen) && read(mmio_addrs.target_paused)) {
      capture_launch_requests();
      // The old application has only a start pulse. Translate it once, with
      // an explicit dataset selection, so exactly the same ELF can exercise
      // the baseline and v2 replay engines without changing CPU/L2 traffic.
      if (launch_records.empty() && !submissions_closed) {
        if (!legacy_registry_id)
          throw std::runtime_error("TrafficGen legacy start requires +trafficgen-legacy-registry-id=<registry ID>");
        launch_slots[0] = {1, legacy_registry_id, QUEUED};
        launch_records.emplace(1, launch_record_t{0, legacy_registry_id});
        legacy_bootstrap_active = true;
        submissions_closed = true;
      }
      state = trafficgen_state_t::SEND_RESERVED_PARTITIONS;
    }
    break;
  case trafficgen_state_t::SEND_RESERVED_PARTITIONS:
    {
      /*
        GPU scheduler uses {cycle : subpartitions} map to avoid scheduling a new access to an L2 subpartition
        that already has a pending access in the traffic generator
      */

      const auto reservations = build_reserved_subpartitions_from_pending();
      std::cout << "[bridge driver] built reservedSubPartitionsByCycle from pending accesses: cycles="
                << reservations.size()
                << " pending_accesses=" << pending_access_cycle_by_id.size() << std::endl;

      // send the reservedSubPartitionsByCycle snapshot to gpu_model via socket
      round_input_reserved_subpartitions =
          ordered_reservations(reservations);
      send_reserved_subpartitions_snapshot(reservations);

      // read the L2 access schedule, blocked warp IDs, and min issue cycle from gpu_model via socket
      receive_schedule_from_gpu_model();

      // Reset upload progress and kick off host->target BRAM upload.
      upload_written_to_bram = false;

      // The compatibility scheduler supplies the literal legacy deadline and
      // UINT64_MAX when no memory issue horizon exists. Do not reinterpret a
      // finite value based on the driver's pending-work state.
      replay_min_issue_cycle = min_issue_cycle;
      write(mmio_addrs.min_issue_cycle_low,
            static_cast<uint32_t>(min_issue_cycle & 0xffffffffULL));
      write(mmio_addrs.min_issue_cycle_high,
            static_cast<uint32_t>(min_issue_cycle >> 32));

      state = trafficgen_state_t::UPLOAD_SCHEDULE;

      break;
    }
  case trafficgen_state_t::UPLOAD_SCHEDULE:

    // write schedule to bridge module via XDMA
    write_schedule_to_bram();

    // once the upload is complete, signal target to start round and unpause target clock
    if (read(mmio_addrs.upload_ready)) {

      std::cout << "[bridge driver] upload completed, entering traffic issuing stage" << std::endl;

      write(mmio_addrs.start_round, 1);
      write(mmio_addrs.resume_target, 1);
      state = trafficgen_state_t::ISSUING_TRAFFIC;
    }

    break;
  case trafficgen_state_t::ISSUING_TRAFFIC:
    if (read(mmio_addrs.lrc_report_drain_required)) {
      // The bridge holds an unprocessed target token. Drain only the report
      // FIFO; its issues are decoded at the eventual completed boundary.
      drain_lrc_reports();
      break;
    }
    // Wait for the target to finish generating memory traffic for the current round.

    // The target is already stopped at the token containing roundComplete.
    // Begin readback only once both the stable output and pause acknowledgement
    // are visible to the driver.
    if (read(mmio_addrs.round_complete) &&
        read(mmio_addrs.target_paused)) {

      // prepare to read back traffic generator outputs
      issued_access_writeback_entries.clear();
      issued_access_writeback_stream_bytes.clear();
      issued_access_writeback_count = 0;
      issued_access_writeback_bytes_received = 0;
      issued_access_writeback_read_issued = false;
      completed_bundle_read_issued = false;

      state = trafficgen_state_t::READING_TRAFFICGEN_OUTPUT;
    }
    break;
  case trafficgen_state_t::READING_TRAFFICGEN_OUTPUT:
    {

      // read issued accesses from bridge module 
      if (!issued_access_writeback_read_issued) {
        const auto total = static_cast<std::uint32_t>(read(mmio_addrs.issued_access_writeback_count));
        const auto lane_capacity = access_window_bytes / lane_count / STREAM_WIDTH_BYTES;
        std::size_t summed = 0, fresh = 0;
        for (std::size_t lane = 0; lane < lane_count; ++lane) {
          const auto count = read_issued_lane_count(lane);
          if (count > lane_capacity || count < issued_lane_read_cursors.at(lane))
            throw std::runtime_error("TrafficGen issued lane count regressed or exceeded capacity");
          issued_lane_snapshot_counts.at(lane) = count;
          summed += count;
          fresh += count - issued_lane_read_cursors.at(lane);
        }
        if (summed != total) throw std::runtime_error("TrafficGen cumulative issued lane counts do not match total");
        issued_access_writeback_count = static_cast<std::uint32_t>(fresh);
        issued_access_writeback_stream_bytes.assign(static_cast<size_t>(issued_access_writeback_count) * STREAM_WIDTH_BYTES, 0);
        issued_access_writeback_bytes_received = 0;
        issued_access_writeback_entries.clear();
        issued_access_writeback_read_issued = true;
      }
      if (issued_access_writeback_bytes_received < static_cast<size_t>(issued_access_writeback_count) * STREAM_WIDTH_BYTES) {
        process_issued_access_writeback_stream();
      }
      if (issued_access_writeback_bytes_received < static_cast<size_t>(issued_access_writeback_count) * STREAM_WIDTH_BYTES) {
        break;
      }

      // The read completes synchronously; zero IDs is also a completed read.
      if (!completed_bundle_read_issued) {
        process_completed_bundle_ids_stream();
        completed_bundle_read_issued = true;
      }

      const std::uint64_t target_cycle_after_issue = read_target_cycle();

      const std::uint32_t round_exit_reason =
          static_cast<std::uint32_t>(read(mmio_addrs.round_exit_reason));
      ++hardware_boundary_number;
      {
        std::ofstream log(round_log_root / "lrc_boundaries.csv",std::ios::app);
        if (!log) throw std::runtime_error("TrafficGen cannot append LRC boundary log");
        log << engine_round_number << ',' << hardware_boundary_number << ',' << target_cycle_after_issue
            << ',' << read(mmio_addrs.lrc_boundary_flags) << ',' << round_exit_reason << '\n';
      }
      if (round_exit_reason != kRoundExitScheduling &&
          round_exit_reason != kRoundExitCapacity &&
          round_exit_reason != kRoundExitBundleTableFull &&
          round_exit_reason != kRoundExitControl) {
        throw std::runtime_error("TrafficGen unknown round exit reason=" +
                                 std::to_string(round_exit_reason));
      }
      // Every physical boundary retires its fresh UIDs exactly once. The
      // accumulated vectors survive internal refills only as GPU feedback.
      depopulate_issued_accesses(issued_access_writeback_entries,
                                round_exit_reason == kRoundExitCapacity);
      for (const auto &point : issued_access_writeback_entries)
        if (point.cycle_issued > target_cycle_after_issue)
          throw std::runtime_error("TrafficGen issue lies after result cycle");
      for (const auto &point : issued_access_writeback_entries)
        lrc_ledger.issued({point.launch_id,point.request_uid},point.physical_token,point.cycle_issued);
      process_lrc_reports(target_cycle_after_issue);
      if (!lrc_ledger.batch_active()) bundle_tracker.record_issued(issued_access_writeback_entries);
      const std::vector<std::uint64_t> fresh_completed(completed_bundle_ids.begin(),
                                                       completed_bundle_ids.begin() + completed_bundle_count);
      if (!lrc_ledger.batch_active()) {
        bundle_tracker.complete(fresh_completed);
        for (const auto id : fresh_completed) {
          if (lrc_ledger.bundles().at(id).wake) ordinary_wake_cycles.insert(target_cycle_after_issue);
          lrc_ledger.complete_bundle(id,target_cycle_after_issue);
        }
      }
      accumulated_issued_accesses.insert(accumulated_issued_accesses.end(),
                                         issued_access_writeback_entries.begin(),
                                         issued_access_writeback_entries.end());
      for (size_t i = 0; i < completed_bundle_count; ++i) {
        accumulated_completed_bundle_ids.push_back(completed_bundle_ids[i]);
      }

      // MMIO may arrive after the engine has latched a capacity/recovery exit.
      // The bridge snapshots control at every boundary, so inspect it before
      // choosing a private refill that would otherwise hide the new launch.
      capture_launch_requests();
      const bool control_waiting = (submissions_closed && !close_sent) ||
          std::any_of(launch_slots.begin(), launch_slots.end(),
                      [](const auto &slot) { return slot.status == QUEUED; });
      if (!boundary_log_path.empty()) {
        const bool private_refill = !control_waiting &&
            (round_exit_reason == kRoundExitCapacity || round_exit_reason == kRoundExitBundleTableFull);
        std::ofstream boundaries(boundary_log_path, std::ios::app);
        if (!boundaries) throw std::runtime_error("TrafficGen cannot append boundary log");
        boundaries << hardware_boundary_number << ',' << engine_round_number << ','
                   << boundary_start_cycle << ',' << target_cycle_after_issue << ','
                   << min_issue_cycle << ',' << replay_min_issue_cycle << ',' << round_exit_reason << ','
                   << issued_access_writeback_entries.size() << ',' << completed_bundle_count << ','
                   << !private_refill << ',' << control_waiting << '\n';
        if (!boundaries) throw std::runtime_error("TrafficGen cannot write boundary log");
      }
      boundary_start_cycle = target_cycle_after_issue;
      if (round_exit_reason == kRoundExitBundleTableFull) {
        const auto lane_mask = static_cast<std::uint32_t>(read(mmio_addrs.bundle_table_full_lane_mask));
        recover_full_bundle_tables(lane_mask, target_cycle_after_issue);
      }
      if (!control_waiting && (round_exit_reason == kRoundExitCapacity ||
          round_exit_reason == kRoundExitBundleTableFull)) {
        lrc_reuse_window = false;
        if (pending_accesses_by_cycle.empty()) {
          throw std::runtime_error(
              "TrafficGen engine reported an internal refill exit without another "
              "host upload chunk");
        }

        if (round_exit_reason == kRoundExitCapacity) {
          std::cout << "[bridge driver] capacity exit at cycle="
                    << target_cycle_after_issue
                    << ", refilling accessStore from pending accesses="
                    << pending_access_cycle_by_id.size() << std::endl;
          if (use_rtl_engine) {
            assign_replay_lanes_and_bundle_counts();
            build_next_l2_access_chunk();
          } else {
            build_next_l2_access_chunk();
            assign_replay_lanes_and_bundle_counts();
          }
        }
        upload_written_to_bram = false;
        issued_access_writeback_entries.clear();
        issued_access_writeback_stream_bytes.clear();
        issued_access_writeback_count = 0;
        issued_access_writeback_bytes_received = 0;
        issued_access_writeback_read_issued = false;
        completed_bundle_read_issued = false;
        state = trafficgen_state_t::UPLOAD_SCHEDULE;
        break;
      }

      const bool engine_has_pending_work =
          read(mmio_addrs.has_pending_work) != 0;

      TrafficGenResultMessage message;
      message.trafficGenResult = lrc_ledger.take_result(target_cycle_after_issue);
      tma_diagnostics.ordinaryWakeups = ordinary_wake_cycles.size();
      for (const auto &point : message.trafficGenResult.completedAccesses)
        if (tma_policy.known().count({point.launchId,point.requestUid}) &&
            !tma_wake_cycles.count(point.cycleIssued) && !ordinary_wake_cycles.count(point.cycleIssued))
          ++tma_diagnostics.tmaCompletionsWithoutWakeup;
      const auto flags=read(mmio_addrs.lrc_boundary_flags);
      if (!tma_diagnostics.ordinaryWakeups && !tma_diagnostics.phaseWakeups && !tma_diagnostics.transferWakeups) {
        if (!lrc_ledger.batch_active()) {
          if (!engine_has_pending_work && !lrc_ledger.has_pending() && pending_access_cycle_by_id.empty())
            ++tma_diagnostics.drainReturns;
          else if (target_cycle_after_issue >= min_issue_cycle) ++tma_diagnostics.deadlineReturns;
          else ++tma_diagnostics.issueBoundaryReturns;
        } else if (!lrc_ledger.lrc_commands_active() && (flags & 8)) ++tma_diagnostics.drainReturns;
        else if (flags & 2) ++tma_diagnostics.deadlineReturns;
        else if (flags & 4) ++tma_diagnostics.issueBoundaryReturns;
        else if (flags & 8) ++tma_diagnostics.drainReturns;
      }
      message.trafficGenResult.wakeDiagnostics = tma_diagnostics;
      accumulated_issued_accesses.clear();
      for (const auto &point : message.trafficGenResult.issuedAccesses)
        accumulated_issued_accesses.push_back({point.requestUid,point.launchId,point.cycleIssued,
                                              point.address,point.isWrite,0});
      accumulated_completed_bundle_ids = message.trafficGenResult.completedBundleIds;
      message.hasPendingWork = engine_has_pending_work ||
          !pending_access_cycle_by_id.empty() || lrc_ledger.has_pending() || tma_policy.pending();
      log_lrc_events();
      log_engine_round_for_compare(accumulated_issued_accesses,
                                   accumulated_completed_bundle_ids,
                                   target_cycle_after_issue);

      if (!gpu_model_socket_client) {
        throw std::runtime_error(
            "gpu_model socket client is not initialized");
      }
      gpu_model_socket_client->send_frame(serialize_message(message));
      last_reported_cycle = target_cycle_after_issue;
      clean_completed_bundle_metadata();
      accumulated_issued_accesses.clear();
      accumulated_completed_bundle_ids.clear();
      receive_scheduler_status();
      if (session_complete && engine_has_pending_work)
        throw std::runtime_error("TrafficGen scheduler completed while engine requests remain");
      finish_scheduler_boundary();
    }
    break;
  }
}

void trafficgen_t::finish() {}

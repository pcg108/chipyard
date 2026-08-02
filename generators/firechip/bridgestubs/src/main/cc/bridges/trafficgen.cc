// See LICENSE for license details

#include "trafficgen.h"
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

constexpr std::uint32_t kRoundExitCapacity = 1;

constexpr std::uint16_t kGpuModelSocketPort = 50051;
constexpr std::uint32_t kGpuModelSocketAddr = INADDR_LOOPBACK;
constexpr std::size_t kXDMABufferAlignment = 4096;
constexpr const char *kRoundLogBase = "/home/prashanth/FIRESIM_RUNS_DIR/sim_slot_0";
constexpr const char *kSocketRoundLogRoot =
    "/home/prashanth/FIRESIM_RUNS_DIR/sim_slot_0/bridge_socket_round_logs";

struct TrafficGenInitializationMessage {
  std::uint64_t currentCycle = 0;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & currentCycle;
  }
};

struct ReservationsMessage {
  L2SubpartitionReservationsByCycle reservedSubpartitionsByCycle;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & reservedSubpartitionsByCycle;
  }
};

struct SchedulingRoundStateMessage {
  bool mainLoopComplete = false;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & mainLoopComplete;
  }
};

struct socket_warp_key_t {
  unsigned smId = 0;
  unsigned schedulerId = 0;
  unsigned warpId = 0;

  bool operator<(const socket_warp_key_t &other) const {
    return std::tie(smId, schedulerId, warpId) <
           std::tie(other.smId, other.schedulerId, other.warpId);
  }

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & smId;
    ar & schedulerId;
    ar & warpId;
  }
};

struct socket_l2_access_t {
  std::uint64_t mUniqueId = 0;
  std::uint64_t mAddress = 0;
  std::uint64_t mCycleCount = 0;
  unsigned mSubpartition = 0;
  unsigned mSetIndex = 0;
  std::uint64_t mTag = 0;
  unsigned mMask = 0;
  unsigned smId = 0;
  unsigned schedulerId = 0;
  unsigned warpId = 0;
  bool mIsWrite = false;
  std::uint64_t mBundleId = 0;
  bool mWakeRelevantBundle = false;
  std::string mKernelFolder;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & mUniqueId;
    ar & mAddress;
    ar & mCycleCount;
    ar & mSubpartition;
    ar & mSetIndex;
    ar & mTag;
    ar & mMask;
    ar & smId;
    ar & schedulerId;
    ar & warpId;
    ar & mIsWrite;
    ar & mBundleId;
    ar & mWakeRelevantBundle;
    ar & mKernelFolder;
  }
};

struct SchedulerRoundMessage {
  std::vector<socket_l2_access_t> allL2TraceSteps;
  std::uint64_t min_issue_cycle = 0;
  std::set<socket_warp_key_t> blockedWarpIds;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & allL2TraceSteps;
    ar & min_issue_cycle;
    ar & blockedWarpIds;
  }
};

struct SocketAllL2TraceStepsSnapshot {
  std::vector<socket_l2_access_t> steps;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & steps;
  }
};

struct SocketBlockedWarpIdsSnapshot {
  std::set<socket_warp_key_t> blockedWarpIds;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & blockedWarpIds;
  }
};

struct OrderedReservedSubpartitionsSnapshot {
  OrderedL2SubpartitionReservationsByCycle reservationsByCycle;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & reservationsByCycle;
  }
};

struct IssuedAccessPoint {
  std::uint64_t requestUid = 0;
  std::uint64_t cycleIssued = 0;
  std::uint64_t address = 0;
  bool isWrite = false;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & requestUid;
    ar & cycleIssued;
    ar & address;
    ar & isWrite;
  }
};

struct IssueScheduleResult {
  std::vector<IssuedAccessPoint> issuedAccesses;
  std::vector<std::uint64_t> completedBundleIds;
  std::uint64_t currentCycleAfterIssue = 0;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & issuedAccesses;
    ar & completedBundleIds;
    ar & currentCycleAfterIssue;
  }
};

struct TrafficGenResultMessage {
  IssueScheduleResult trafficGenResult;
  bool hasPendingWork = false;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & trafficGenResult;
    ar & hasPendingWork;
  }
};

struct IssuedAccessesSnapshot {
  std::vector<IssuedAccessPoint> issuedAccesses;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & issuedAccesses;
  }
};

struct CompletedBundleIdsSnapshot {
  std::vector<std::uint64_t> completedBundleIds;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & completedBundleIds;
  }
};

struct CurrentCycleAfterIssueSnapshot {
  std::uint64_t currentCycleAfterIssue = 0;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & currentCycleAfterIssue;
  }
};

struct MinIssueCycleSnapshot {
  std::uint64_t minIssueCycle = 0;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & minIssueCycle;
  }
};

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

void clear_round_log_entries(const std::filesystem::path &root) {
  if (std::filesystem::exists(root)) {
    for (const auto &entry : std::filesystem::directory_iterator(root)) {
      const std::string name = entry.path().filename().string();
      if (name.rfind("round_", 0) == 0) {
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
    const std::set<socket_warp_key_t> &blocked_warp_ids) {
  static std::uint64_t round_number = 0;
  ++round_number;

  const std::string root_dir = kSocketRoundLogRoot;
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
    const ssize_t written = send(fd, cursor, remaining, 0);
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
std::string serialize_message(const T &message) {
  std::ostringstream oss(std::ios::binary);
  boost::archive::binary_oarchive archive(oss);
  archive << message;
  return oss.str();
}

template <typename T>
T deserialize_message(const std::string &payload) {
  std::istringstream iss(payload, std::ios::binary);
  boost::archive::binary_iarchive archive(iss);
  T message{};
  archive >> message;
  return message;
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
          "127.0.0.1:50051");
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
               (static_cast<std::uint64_t>(access.bundle_generation) << 19);
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
  const std::string round_log_dir =
      plusarg_value(args, "trafficgen-round-log-dir");
  round_log_root = std::filesystem::path(kRoundLogBase) /
                   (round_log_dir.empty() ? "trafficgen_bridge" : round_log_dir);
}

trafficgen_t::~trafficgen_t() = default;

void trafficgen_t::init() {
  connect_gpu_model_socket();
  clear_round_log_entries(round_log_root);
  clear_round_log_entries(kSocketRoundLogRoot);

  engine_round_number = 0;
  reset_workload_state();
}

void trafficgen_t::reset_workload_state() {
  completed_bundle_ids.fill(0);
  completed_bundle_count = 0;
  completed_bundle_stream_bytes.fill(0);
  completed_bundle_bytes_received = 0;
  completed_bundle_read_issued = false;
  issued_access_writeback_entries.clear();
  issued_access_writeback_stream_bytes.clear();
  issued_access_writeback_count = 0;
  issued_access_writeback_bytes_received = 0;
  issued_access_writeback_read_issued = false;
  accumulated_issued_accesses.clear();
  accumulated_completed_bundle_ids.clear();
  current_round_all_l2_trace_steps.clear();
  current_round_blocked_warp_ids.clear();
  min_issue_cycle = 0;
  l2_accesses.clear();
  pending_accesses_by_cycle.clear();
  pending_access_cycle_by_id.clear();
  upload_written_to_bram = false;
  round_completion_pause_issued = false;
  round_input_reserved_subpartitions.clear();
  state = trafficgen_state_t::IDLE;
}

void trafficgen_t::connect_gpu_model_socket() {
  if (!gpu_model_socket_client) {
    gpu_model_socket_client = std::make_unique<socket_client_t>();
  }

  gpu_model_socket_client->connect_loopback(kGpuModelSocketPort);
  std::cout << "[bridge driver] connected to gpu_model_socket at 127.0.0.1:"
            << static_cast<unsigned>(kGpuModelSocketPort) << std::endl;

  const std::uint64_t current_target_cycle =
      (static_cast<std::uint64_t>(
           read(mmio_addrs.current_cycle_after_issue_high))
       << 32) |
      static_cast<std::uint64_t>(
          read(mmio_addrs.current_cycle_after_issue_low));
  TrafficGenInitializationMessage message;
  message.currentCycle = current_target_cycle;
  gpu_model_socket_client->send_frame(serialize_message(message));
  std::cout << "[bridge driver] sent initial target cycle to gpu_model_socket: cycle="
            << current_target_cycle << std::endl;
}

bool trafficgen_t::receive_main_loop_complete_from_gpu_model() const {
  if (!gpu_model_socket_client) {
    throw std::runtime_error(
        "gpu_model socket client is not initialized");
  }

  const SchedulingRoundStateMessage message =
      gpu_model_socket_client->recv_message<SchedulingRoundStateMessage>();
  std::cout << "[bridge driver] received scheduling round state from gpu_model_socket: mainLoopComplete="
            << (message.mainLoopComplete ? "true" : "false") << std::endl;
  return message.mainLoopComplete;
}

void trafficgen_t::send_reserved_subpartitions_snapshot(
    const L2SubpartitionReservationsByCycle &reservations) const {
  if (!gpu_model_socket_client) {
    throw std::runtime_error(
        "gpu_model socket client is not initialized");
  }

  ReservationsMessage message;
  message.reservedSubpartitionsByCycle = reservations;
  const std::string payload = serialize_message(message);
  gpu_model_socket_client->send_frame(payload);

  std::cout << "[bridge driver] sent reservedSubPartitionsByCycle to gpu_model_socket: cycles="
            << reservations.size() << " payload_bytes=" << payload.size() << std::endl;
}

static bool access_warp_is_blocked(
    const trafficgen_l2_access_t &access,
    const std::set<socket_warp_key_t> &blocked_warp_ids) {
  const socket_warp_key_t key{
      access.sm_id,
      static_cast<unsigned>(access.scheduler_id),
      access.warp_id};
  return blocked_warp_ids.find(key) != blocked_warp_ids.end();
}

void trafficgen_t::refresh_pending_access_blocked_annotations() {
  for (auto &[cycle, accesses] : pending_accesses_by_cycle) {
    (void)cycle;
    for (auto &access : accesses) {
      access.m_warp_blocked =
          access_warp_is_blocked(access, current_round_blocked_warp_ids);
    }
  }
}

void trafficgen_t::refresh_upload_access_blocked_annotations() {
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

  std::cout << "[bridge driver] built L2 access chunk: chunk_entries="
            << l2_accesses.size()
            << " pending_entries=" << pending_access_cycle_by_id.size()
            << " capacity=" << max_entries << std::endl;
}

L2SubpartitionReservationsByCycle trafficgen_t::build_reserved_subpartitions_from_pending() const {

  // create the cycle : subpartition reservation map from the un-issued accesses to send to scheduler

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
    const std::vector<trafficgen_issued_access_point_t> &issued_accesses) {
  std::unordered_set<std::uint64_t> issued_ids;
  issued_ids.reserve(issued_accesses.size());
  for (const auto &access : issued_accesses) {
    issued_ids.insert(access.request_uid);
  }

  for (auto bucket_it = pending_accesses_by_cycle.begin();
       bucket_it != pending_accesses_by_cycle.end();) {
    auto &accesses = bucket_it->second;
    accesses.erase(
        std::remove_if(accesses.begin(),
                       accesses.end(),
                       [this, &issued_ids](const trafficgen_l2_access_t &access) {
                         if (issued_ids.find(access.id) == issued_ids.end()) {
                           return false;
                         }
                         pending_access_cycle_by_id.erase(access.id);
                         return true;
                       }),
        accesses.end());
    if (accesses.empty()) {
      bucket_it = pending_accesses_by_cycle.erase(bucket_it);
    } else {
      ++bucket_it;
    }
  }
}

void trafficgen_t::depopulate_uploaded_l2_access_chunk() {
  std::unordered_set<std::uint64_t> uploaded_ids;
  uploaded_ids.reserve(l2_accesses.size());
  for (const auto &access : l2_accesses) {
    uploaded_ids.insert(access.id);
  }

  for (auto bucket_it = pending_accesses_by_cycle.begin();
       bucket_it != pending_accesses_by_cycle.end();) {
    auto &accesses = bucket_it->second;
    accesses.erase(
        std::remove_if(accesses.begin(),
                       accesses.end(),
                       [this, &uploaded_ids](const trafficgen_l2_access_t &access) {
                         if (uploaded_ids.find(access.id) == uploaded_ids.end()) {
                           return false;
                         }
                         pending_access_cycle_by_id.erase(access.id);
                         return true;
                       }),
        accesses.end());
    if (accesses.empty()) {
      bucket_it = pending_accesses_by_cycle.erase(bucket_it);
    } else {
      ++bucket_it;
    }
  }
}

void trafficgen_t::assign_replay_lanes_and_bundle_counts() {
  std::map<std::pair<std::uint32_t, std::uint64_t>, std::size_t> bundle_counts;
  // Count the entire currently available GPU schedule, not just this BRAM
  // chunk.  The first member tells the RTL engine how many members remain in
  // this scheduler generation; capacity refills then continue that same
  // generation without allowing a partial bundle to complete early.
  for (const auto &[cycle, accesses] : pending_accesses_by_cycle) {
    (void)cycle;
    for (const auto &access : accesses) {
      ++bundle_counts[{access.bundle_generation, access.m_bundle_id}];
    }
  }
  for (auto &access : l2_accesses) {
    const auto count =
        bundle_counts.at({access.bundle_generation, access.m_bundle_id});
    if (count == 0 || count > std::numeric_limits<std::uint16_t>::max()) {
      throw std::runtime_error("TrafficGen bundle member count exceeds packed width");
    }
    access.bundle_issue_count = static_cast<std::uint16_t>(count);
  }

  if (use_rtl_engine) {
    // The RTL engine accepts an ordered stream per lane and applies the real
    // L2 backpressure when a lane's head becomes eligible.  The socket
    // schedule can therefore contain more accesses than there are lanes with the same
    // requested cycle (and repeated subpartitions); distribute that stream
    // evenly without rewriting its cycles.  Any excess same-cycle accesses
    // remain at the lane heads and issue on later target cycles as resources
    // become available.
    for (std::size_t i = 0; i < l2_accesses.size(); ++i) {
      l2_accesses[i].assigned_lane =
          i % lane_count;
    }
    return;
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
           << "completed_bundle_ids.bin,current_cycle_after_issue.bin\n";
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
                                      message.blockedWarpIds);

  min_issue_cycle = message.min_issue_cycle;
  for (const auto &wire_access : message.allL2TraceSteps) {
    trafficgen_l2_access_t access{};
    access.id = wire_access.mUniqueId;
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
    access.m_bundle_id = wire_access.mBundleId;
    access.m_wake_relevant_bundle = wire_access.mWakeRelevantBundle;
    access.m_is_write = wire_access.mIsWrite;
    access.bundle_generation =
        checked_u32(engine_round_number, "bundle generation");
    if (pending_access_cycle_by_id.emplace(access.id, access.cycle_count).second) {
      pending_accesses_by_cycle[access.cycle_count].push_back(access);
    }
  }
  accumulated_issued_accesses.clear();
  accumulated_completed_bundle_ids.clear();

  refresh_pending_access_blocked_annotations();
  build_next_l2_access_chunk();
  assign_replay_lanes_and_bundle_counts();

  std::cout << "[bridge driver] received schedule from gpu_model_socket: "
            << "new_l2_accesses=" << message.allL2TraceSteps.size()
            << " pending_l2_accesses=" << pending_access_cycle_by_id.size()
            << " upload_accesses=" << l2_accesses.size()
            << " blocked_warps=" << message.blockedWarpIds.size()
            << " min_issue_cycle=" << min_issue_cycle << std::endl;
}

size_t trafficgen_t::process_completed_bundle_ids_stream() {

  /*
    read completed bundle IDs from bridge module using XDMA
  */

  // 64 bits per ID, so 8 IDs per 512-bit beat.
  const size_t total_bytes = COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES;
  if (completed_bundle_bytes_received >= total_bytes) {
    return 0;
  }

  if (completed_window_bytes < total_bytes) {
    throw std::runtime_error("TrafficGen completed-bundle BRAM window is too small");
  }
  auto &xdma = simif.get_cpu_managed_stream_io();
  auto stream_bytes = make_aligned_bytes(total_bytes);
  xdma_read_exact(
      xdma,
      bram_base + raw_completed_bundle_ids_offset,
      stream_bytes.get(),
      total_bytes,
      "XDMA read while reading completedBundleIds");
  std::memcpy(completed_bundle_stream_bytes.data(),
              stream_bytes.get(),
              total_bytes);
  completed_bundle_bytes_received += total_bytes;

  std::memcpy(completed_bundle_ids.data(),
              completed_bundle_stream_bytes.data(),
              sizeof(completed_bundle_ids));
  completed_bundle_count =
      static_cast<uint32_t>(read(mmio_addrs.completed_bundle_count));
  if (completed_bundle_count > COMPLETED_BUNDLE_ID_COUNT) {
    std::ostringstream error;
    error << "TrafficGen completedBundleIds count exceeds capacity: count="
          << completed_bundle_count
          << " capacity=" << COMPLETED_BUNDLE_ID_COUNT;
    throw std::runtime_error(error.str());
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
    const std::uint32_t lane_count = read_issued_lane_count(lane);
    const std::size_t lane_bytes = static_cast<std::size_t>(lane_count) * STREAM_WIDTH_BYTES;
    if (lane_bytes > bank_stride_bytes) {
      throw std::runtime_error("TrafficGen issued lane exceeds bank capacity");
    }
    summed_count += lane_count;
    if (lane_bytes == 0) continue;
    auto lane_data = make_aligned_bytes(lane_bytes);
    xdma_read_exact(
        xdma,
        bram_base + raw_issued_access_writeback_store_offset + lane * bank_stride_bytes,
        lane_data.get(), lane_bytes,
        "XDMA read while reading banked issuedAccessWriteback");
    for (std::uint32_t idx = 0; idx < lane_count; ++idx) {
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

  std::cout << "[bridge driver] issuedAccessWriteback count="
            << issued_access_writeback_count << std::endl;
  return total_bytes;
}

void trafficgen_t::write_schedule_to_bram() {
  if (upload_written_to_bram) {
    return;
  }

  refresh_pending_access_blocked_annotations();
  refresh_upload_access_blocked_annotations();

  // using this lets it run in metasim and on FPGA because of how simif abstracts away the details of stream I/O
  auto &xdma = simif.get_cpu_managed_stream_io();

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
  for (const auto &access : l2_accesses) {
    if (access.assigned_lane >= lane_count) {
      throw std::runtime_error("TrafficGen access has invalid replay lane");
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
    std::cout << "[bridge driver] uploading L2 access lane=" << lane
              << " entries=" << lanes[lane].size()
              << " bytes=" << lane_bytes << std::endl;
    xdma_write_exact(
        xdma,
        bram_base + raw_access_store_offset + lane * bank_stride_bytes,
        packed.get(), lane_bytes,
        "XDMA write while uploading banked TrafficGen L2 accesses");
    std::cout << "[bridge driver] uploaded L2 access lane=" << lane
              << std::endl;
  }

  // write access meta data into MMIO registers
  const std::uint64_t max_cycle =
      l2_accesses.empty() ? 0 : l2_accesses.back().cycle_count;
  write(mmio_addrs.upload_count, static_cast<uint32_t>(l2_accesses.size()));
  write(mmio_addrs.access_store_max_cycle_low,
        static_cast<uint32_t>(max_cycle & 0xffffffffULL));
  write(mmio_addrs.access_store_max_cycle_high,
        static_cast<uint32_t>(max_cycle >> 32));
  write(mmio_addrs.access_store_has_more,
        pending_access_cycle_by_id.size() > l2_accesses.size() ? 1 : 0);
  write(mmio_addrs.commit_upload, 1);

  upload_written_to_bram = true;
}

void trafficgen_t::push_upload_data() {
  write_schedule_to_bram();
}

void trafficgen_t::tick() {
  switch (state) {
  case trafficgen_state_t::IDLE:

    // Wait for the traffic generator to be kicked off.
    if (read(mmio_addrs.start_trafficgen)) {
      std::cout << "[bridge driver] start signal received, starting traffic generation" << std::endl;
      
      // pause the target clock 
      write(mmio_addrs.pause_target, 1);

      // The first workload is connected during init. Later workloads reconnect
      // to a freshly launched one-kernel scheduler after the prior scheduler
      // has reported global completion and disconnected.
      if (!gpu_model_socket_client ||
          !gpu_model_socket_client->is_connected()) {
        connect_gpu_model_socket();
      }

      // if the full kernel scheduling is complete, then return to IDLE
      if (receive_main_loop_complete_from_gpu_model()) {
        write(mmio_addrs.trafficgen_done, 1);
        write(mmio_addrs.pause_target, 1);
        gpu_model_socket_client->disconnect();
        reset_workload_state();
        break;
      }

      state = trafficgen_state_t::SEND_RESERVED_PARTITIONS;
    }

    break;
  case trafficgen_state_t::SEND_RESERVED_PARTITIONS:
    {
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

      write(mmio_addrs.min_issue_cycle_low,
            static_cast<uint32_t>(min_issue_cycle & 0xffffffffULL));
      write(mmio_addrs.min_issue_cycle_high,
            static_cast<uint32_t>(min_issue_cycle >> 32));

      state = trafficgen_state_t::UPLOAD_SCHEDULE;

      break;
    }
  case trafficgen_state_t::UPLOAD_SCHEDULE:

    // write schedule to bridge module via XDMA
    push_upload_data();

    // once the upload is complete, signal target to start round and unpause target clock
    if (read(mmio_addrs.upload_ready)) {

      std::cout << "[bridge driver] upload completed, entering traffic issuing stage" << std::endl;

      write(mmio_addrs.start_round, 1);
      write(mmio_addrs.pause_target, 1);
      round_completion_pause_issued = false;
      state = trafficgen_state_t::ISSUING_TRAFFIC;
    }

    break;
  case trafficgen_state_t::ISSUING_TRAFFIC:
    // Wait for the target to finish generating memory traffic for the current round.

    // pause target while we read back the results from this round and prepare for the next round
    if (read(mmio_addrs.round_complete) && !round_completion_pause_issued) {
      write(mmio_addrs.pause_target, 1);
      round_completion_pause_issued = true;
    }

    // Once round_complete is latched in the bridge, the target-visible round
    // outputs are stable. Do not wait for target_busy to fall after pausing:
    // the pause can otherwise prevent the target from taking the cycle that
    // presents the idle value to the bridge.
    if (round_completion_pause_issued && read(mmio_addrs.round_complete)) {

      // prepare to read back traffic generator outputs
      issued_access_writeback_entries.clear();
      issued_access_writeback_stream_bytes.clear();
      issued_access_writeback_count = 0;
      issued_access_writeback_bytes_received = 0;
      issued_access_writeback_read_issued = false;
      completed_bundle_stream_bytes.fill(0);
      completed_bundle_bytes_received = 0;
      completed_bundle_read_issued = false;

      state = trafficgen_state_t::READING_TRAFFICGEN_OUTPUT;
    }
    break;
  case trafficgen_state_t::READING_TRAFFICGEN_OUTPUT:
    {

      // read issued accesses from bridge module 
      if (!issued_access_writeback_read_issued) {
        issued_access_writeback_count = static_cast<uint32_t>(read(mmio_addrs.issued_access_writeback_count));
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

      // read completed bundle IDs from bridge module
      if (!completed_bundle_read_issued) {
        completed_bundle_stream_bytes.fill(0);
        completed_bundle_bytes_received = 0;
        completed_bundle_read_issued = true;
      }
      if (completed_bundle_bytes_received < COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES) {
        process_completed_bundle_ids_stream();
      }
      if (completed_bundle_bytes_received < COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES) {
        break;
      }

      const std::uint64_t target_cycle_after_issue =
          (static_cast<std::uint64_t>(read(mmio_addrs.current_cycle_after_issue_high)) << 32) |
          static_cast<std::uint64_t>(read(mmio_addrs.current_cycle_after_issue_low));

      std::unordered_set<std::uint64_t> uploaded_access_ids;
      uploaded_access_ids.reserve(l2_accesses.size());
      for (const auto &access : l2_accesses) {
        uploaded_access_ids.insert(access.id);
      }
      for (const auto &issued : issued_access_writeback_entries) {
        if (uploaded_access_ids.find(issued.request_uid) ==
            uploaded_access_ids.end()) {
          std::ostringstream error;
          error << "TrafficGen engine reported unknown issued request_uid="
                << issued.request_uid;
          throw std::runtime_error(error.str());
        }
        accumulated_issued_accesses.push_back(issued);
      }

      for (size_t i = 0; i < completed_bundle_count; ++i) {
        accumulated_completed_bundle_ids.push_back(completed_bundle_ids[i]);
      }

      const std::uint32_t round_exit_reason =
          static_cast<std::uint32_t>(read(mmio_addrs.round_exit_reason));
      if (round_exit_reason == kRoundExitCapacity) {
        depopulate_uploaded_l2_access_chunk();
        if (pending_accesses_by_cycle.empty()) {
          throw std::runtime_error(
              "TrafficGen engine reported a capacity exit without another "
              "host upload chunk");
        }

        std::cout << "[bridge driver] capacity exit at cycle="
                  << target_cycle_after_issue
                  << ", refilling accessStore from pending accesses="
                  << pending_access_cycle_by_id.size() << std::endl;
        build_next_l2_access_chunk();
        assign_replay_lanes_and_bundle_counts();
        upload_written_to_bram = false;
        issued_access_writeback_entries.clear();
        issued_access_writeback_stream_bytes.clear();
        issued_access_writeback_count = 0;
        issued_access_writeback_bytes_received = 0;
        issued_access_writeback_read_issued = false;
        completed_bundle_stream_bytes.fill(0);
        completed_bundle_bytes_received = 0;
        completed_bundle_read_issued = false;
        state = trafficgen_state_t::UPLOAD_SCHEDULE;
        break;
      }

      depopulate_issued_accesses(issued_access_writeback_entries);
      const bool engine_has_pending_work =
          read(mmio_addrs.has_pending_work) != 0;

      TrafficGenResultMessage message;
      message.trafficGenResult.issuedAccesses.reserve(
          accumulated_issued_accesses.size());
      for (const auto &issued : accumulated_issued_accesses) {
        IssuedAccessPoint issued_access_point{};
        issued_access_point.requestUid = issued.request_uid;
        issued_access_point.cycleIssued = issued.cycle_issued;
        issued_access_point.address = issued.address;
        issued_access_point.isWrite = issued.is_write;
        message.trafficGenResult.issuedAccesses.push_back(
            issued_access_point);
      }
      message.trafficGenResult.completedBundleIds =
          accumulated_completed_bundle_ids;
      message.trafficGenResult.currentCycleAfterIssue =
          target_cycle_after_issue;
      message.hasPendingWork = engine_has_pending_work;
      log_engine_round_for_compare(accumulated_issued_accesses,
                                   accumulated_completed_bundle_ids,
                                   target_cycle_after_issue);

      if (!gpu_model_socket_client) {
        throw std::runtime_error(
            "gpu_model socket client is not initialized");
      }
      gpu_model_socket_client->send_frame(serialize_message(message));
      accumulated_issued_accesses.clear();
      accumulated_completed_bundle_ids.clear();

      if (receive_main_loop_complete_from_gpu_model()) {
        // indicate to target that trafficgen is done and unpause target
        if (engine_has_pending_work || !pending_access_cycle_by_id.empty()) {
          throw std::runtime_error(
              "gpu_model completed a workload while TrafficGen still had "
              "pending work");
        }
        write(mmio_addrs.trafficgen_done, 1);
        write(mmio_addrs.pause_target, 1);
        gpu_model_socket_client->disconnect();
        reset_workload_state();
      } else {
        issued_access_writeback_entries.clear();
        issued_access_writeback_stream_bytes.clear();
        issued_access_writeback_count = 0;
        issued_access_writeback_bytes_received = 0;
        issued_access_writeback_read_issued = false;
        completed_bundle_stream_bytes.fill(0);
        completed_bundle_bytes_received = 0;
        completed_bundle_read_issued = false;
        state = trafficgen_state_t::SEND_RESERVED_PARTITIONS;
      }
    }
    break;
  }
}

void trafficgen_t::finish() {}

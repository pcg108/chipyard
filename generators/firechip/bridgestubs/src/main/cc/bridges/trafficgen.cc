// See LICENSE for license details

#include "trafficgen.h"
#include "core/simif.h"

#include <arpa/inet.h>
#include <algorithm>
#include <boost/archive/binary_iarchive.hpp>
#include <boost/archive/binary_oarchive.hpp>
#include <boost/serialization/access.hpp>
#include <boost/serialization/library_version_type.hpp>
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
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

char trafficgen_t::KIND;

namespace {

constexpr std::uint16_t kGpuModelSocketPort = 50051;
constexpr std::uint32_t kGpuModelSocketAddr = INADDR_LOOPBACK;

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
  std::uint64_t mL1ToL2Cycle = 0;
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
    ar & mL1ToL2Cycle;
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

struct IssuedAccessPoint {
  std::uint64_t requestUid = 0;
  std::uint64_t address = 0;
  std::uint64_t cycleIssued = 0;
  std::uint64_t l1ToL2Cycle = 0;
  std::uint64_t elapsedCycle = 0;
  unsigned smId = 0;
  unsigned schedulerId = 0;
  unsigned warpId = 0;
  bool isWrite = false;

private:
  friend class boost::serialization::access;

  template <class Archive>
  void serialize(Archive &ar, const unsigned int /*version*/) {
    ar & requestUid;
    ar & address;
    ar & cycleIssued;
    ar & l1ToL2Cycle;
    ar & elapsedCycle;
    ar & smId;
    ar & schedulerId;
    ar & warpId;
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

std::string errno_message(const std::string &prefix) {
  std::ostringstream oss;
  oss << prefix << ": " << std::strerror(errno);
  return oss.str();
}

void create_directory_if_needed(const std::string &path) {
  if (mkdir(path.c_str(), 0755) == 0 || errno == EEXIST) {
    return;
  }
  throw std::runtime_error(errno_message("failed to create " + path));
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

  const std::string root_dir =
      "/home/prashanth/FIRESIM_RUNS_DIR/sim_slot_0/bridge_socket_round_logs";
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

  void connect_loopback(std::uint16_t port) {
    if (fd >= 0) {
      close(fd);
      fd = -1;
    }

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

static uint32_t blocked_warp_index(uint32_t sm_id,
                                   uint32_t scheduler_id,
                                   uint32_t warp_id) {
  const uint32_t max_sm_id = 1u << trafficgen_t::BLOCKED_WARP_SM_BITS;
  const uint32_t max_scheduler_id =
      1u << trafficgen_t::BLOCKED_WARP_SCHEDULER_BITS;
  const uint32_t max_warp_id = 1u << trafficgen_t::BLOCKED_WARP_WARP_BITS;

  if (sm_id >= max_sm_id || scheduler_id >= max_scheduler_id ||
      warp_id >= max_warp_id) {
    std::cout << "[bridge driver] blocked warp tuple out of range: sm=" << sm_id
              << " scheduler=" << scheduler_id << " warp=" << warp_id
              << std::endl;
    std::abort();
  }

  return (sm_id << (trafficgen_t::BLOCKED_WARP_SCHEDULER_BITS +
                    trafficgen_t::BLOCKED_WARP_WARP_BITS)) |
         (scheduler_id << trafficgen_t::BLOCKED_WARP_WARP_BITS) | warp_id;
}

static void pack_l2_access(const trafficgen_l2_access_t &access,
                           uint64_t *words) {
  words[0] = access.id;
  words[1] = access.address;
  words[2] = access.cycle_count;
  words[3] = (static_cast<uint64_t>(access.m_set_index) << 32) |
             access.m_subpartition;
  words[4] = access.m_tag;
  words[5] = (static_cast<uint64_t>(access.sm_id) << 32) | access.m_mask;
  words[6] = (access.m_bundle_id & 0xffffffULL) << 40 |
             (static_cast<uint64_t>(access.warp_id) << 8) |
             static_cast<uint64_t>(access.scheduler_id);
  words[7] = (access.m_is_write ? 1ULL : 0ULL) << 41 |
             (access.m_wake_relevant_bundle ? 1ULL : 0ULL) << 40 |
             (access.m_bundle_id >> 24);
}

static trafficgen_l2_access_t unpack_l2_access(const uint64_t *words) {
  trafficgen_l2_access_t access{};
  access.id = words[0];
  access.address = words[1];
  access.cycle_count = words[2];
  access.m_subpartition = static_cast<uint32_t>(words[3] & 0xffffffffULL);
  access.m_set_index = static_cast<uint32_t>(words[3] >> 32);
  access.m_tag = words[4];
  access.m_mask = static_cast<uint32_t>(words[5] & 0xffffffffULL);
  access.sm_id = static_cast<uint32_t>(words[5] >> 32);
  access.scheduler_id = static_cast<uint8_t>(words[6] & 0xffULL);
  access.warp_id = static_cast<uint32_t>((words[6] >> 8) & 0xffffffffULL);
  access.m_bundle_id =
      ((words[7] & 0xffffffffffULL) << 24) | ((words[6] >> 40) & 0xffffffULL);
  access.m_wake_relevant_bundle = ((words[7] >> 40) & 0x1ULL) != 0;
  access.m_is_write = ((words[7] >> 41) & 0x1ULL) != 0;
  return access;
}

trafficgen_t::trafficgen_t(simif_t &simif,
                           StreamEngine &stream,
                           const TRAFFICGENBRIDGEMODULE_struct &mmio_addrs,
                           int /*trafficgenno*/,
                           const std::vector<std::string> & /*args*/,
                           int stream_to_host_idx,
                           int stream_to_host_depth,
                           int stream_from_host_idx,
                           int stream_from_host_depth)
    : streaming_bridge_driver_t(simif, stream, &KIND),
      mmio_addrs(mmio_addrs),
      stream_to_host_idx(stream_to_host_idx),
      stream_to_host_depth(stream_to_host_depth),
      stream_from_host_idx(stream_from_host_idx),
      stream_from_host_depth(stream_from_host_depth) {
  static_assert(BLOCKED_WARP_BITMAP_BITS % (STREAM_WIDTH_BYTES * 8) == 0,
                "Blocked warp bitmap must align to stream beats");
}

trafficgen_t::~trafficgen_t() = default;

void trafficgen_t::init() {
  connect_gpu_model_socket();

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
  reserved_subpartition_bytes_received = 0;
  reserved_subpartitions_metadata_latched = false;
  min_issue_cycle = 0;
  l2_accesses.clear();
  blocked_warp_bitmap.fill(0);
  upload_cursor = 0;
  blocked_warp_bitmap_upload_cursor = 0;
  upload_phase = trafficgen_upload_phase_t::done;
  round_completion_pause_issued = false;
  reserved_subpartitions_read_issued = false;
  reserved_subpartitions_base_idx = 0;
  reserved_subpartitions_base_cycle = 0;
  reserved_subpartitions_words.fill(0);
  reserved_subpartitions_by_cycle.clear();
  state = trafficgen_state_t::IDLE;
}

void trafficgen_t::connect_gpu_model_socket() {
  if (!gpu_model_socket_client) {
    gpu_model_socket_client = std::make_unique<socket_client_t>();
  }

  gpu_model_socket_client->connect_loopback(kGpuModelSocketPort);
  std::cout << "[bridge driver] connected to gpu_model_socket at 127.0.0.1:"
            << static_cast<unsigned>(kGpuModelSocketPort) << std::endl;
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

void trafficgen_t::send_reserved_subpartitions_snapshot() const {
  if (!gpu_model_socket_client) {
    throw std::runtime_error(
        "gpu_model socket client is not initialized");
  }

  ReservationsMessage message;
  message.reservedSubpartitionsByCycle = reserved_subpartitions_by_cycle;
  const std::string payload = serialize_message(message);
  gpu_model_socket_client->send_frame(payload);

  std::cout << "[bridge driver] sent reservedSubPartitionsByCycle to gpu_model_socket: cycles="
            << reserved_subpartitions_by_cycle.size() << " payload_bytes=" << payload.size() << std::endl;
}

void trafficgen_t::receive_schedule_from_gpu_model() {
  if (!gpu_model_socket_client) {
    throw std::runtime_error(
        "gpu_model socket client is not initialized");
  }

  const SchedulerRoundMessage message =
      gpu_model_socket_client->recv_message<SchedulerRoundMessage>();

  log_socket_round_inputs_for_compare(message.allL2TraceSteps,
                                      message.blockedWarpIds);

  min_issue_cycle = message.min_issue_cycle;
  l2_accesses.clear();
  l2_accesses.reserve(message.allL2TraceSteps.size());
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
    l2_accesses.push_back(access);
  }

  std::stable_sort(l2_accesses.begin(),
                   l2_accesses.end(),
                   [](const trafficgen_l2_access_t &lhs,
                      const trafficgen_l2_access_t &rhs) {
                     return lhs.cycle_count < rhs.cycle_count;
                   });

  blocked_warp_bitmap.fill(0);
  for (const auto &warp_key : message.blockedWarpIds) {
    const auto index =
        blocked_warp_index(warp_key.smId, warp_key.schedulerId, warp_key.warpId);
    blocked_warp_bitmap[index / 64] |= (1ULL << (index % 64));
  }

  std::cout << "[bridge driver] received schedule from gpu_model_socket: "
            << "l2_accesses=" << l2_accesses.size()
            << " blocked_warps=" << message.blockedWarpIds.size()
            << " min_issue_cycle=" << min_issue_cycle << std::endl;
}


size_t trafficgen_t::process_reserved_subpartitions_stream() {
  if (!reserved_subpartitions_metadata_latched) {
    // Snapshot metadata must stay fixed while we accumulate a paused target snapshot.

    // read base index into ring buffer
    reserved_subpartitions_base_idx = static_cast<uint32_t>(read(mmio_addrs.reserved_subpartitions_base_idx));
    if (reserved_subpartitions_base_idx >= STREAM_WORD_COUNT) {
      std::cout << "[bridge driver] reservedSubPartitions baseIdx out of range: "
                << reserved_subpartitions_base_idx << std::endl;
      std::abort();
    }

    // read base cycle corresponding to that base index
    reserved_subpartitions_base_cycle =
        (static_cast<std::uint64_t>(read(mmio_addrs.reserved_subpartitions_base_cycle_high)) << 32) |
        static_cast<std::uint64_t>(read(mmio_addrs.reserved_subpartitions_base_cycle_low));

    reserved_subpartitions_metadata_latched = true;
  }

  // stop if we have read the full buffer
  if (reserved_subpartition_bytes_received >= STREAM_BATCH_BYTES) {
    return 0;
  }

  // Try to pull between 0 to remaining bytes into reserved_subpartitions_words
  auto *snapshot_bytes = reinterpret_cast<uint8_t *>(reserved_subpartitions_words.data());
  const size_t remaining_bytes = STREAM_BATCH_BYTES - reserved_subpartition_bytes_received;
  const auto bytes_received = pull(this->stream_to_host_idx,
                                    snapshot_bytes + reserved_subpartition_bytes_received,
                                    remaining_bytes,
                                    0);

  // return if we have not pulled everything, if stream is empty, or we overflowed buffer                                    
  if (bytes_received == 0) {
    return 0;
  }
  if (bytes_received > remaining_bytes) {
    std::cout << "[bridge driver] reservedSubPartitionsByCycle overrun: "
              << "remaining=" << remaining_bytes
              << " got=" << bytes_received << std::endl;
    std::abort();
  }
  if (reserved_subpartition_bytes_received + bytes_received < STREAM_BATCH_BYTES) {
    return bytes_received;
  }

  // The stream buffer now holds the full streamed snapshot with one 256-bit
  // entry in the low half of each 512-bit beat; decode each logical cycle entry.
  reserved_subpartitions_by_cycle.clear();
  // iterate through every cycle
  for (size_t offset = 0; offset < STREAM_WORD_COUNT; ++offset) {

    // starting at base index, wrap around the ring buffer 
    const size_t ring_idx = (static_cast<size_t>(reserved_subpartitions_base_idx) + offset) % STREAM_WORD_COUNT;
    // get to the 256-bit payload in the low half of the streamed 512-bit beat
    const size_t beat_word_base = ring_idx * STREAM_WORDS_PER_BEAT;

    bool any_reserved = false;
    std::unordered_set<unsigned> reserved_subpartitions;
    // loop through the 256 bits in 64-bit chunks
    for (size_t word_idx = 0; word_idx < STREAM_WORDS_PER_ENTRY; ++word_idx) {

      const uint64_t word = reserved_subpartitions_words[beat_word_base + word_idx];

      // skip when no subpartitions are reserved for this chunk
      if (word == 0) {
        continue;
      }

      // if any bit is set, then add that index to the reserved subpartitions for this cycle
      any_reserved = true;
      for (unsigned bit_idx = 0; bit_idx < 64; ++bit_idx) {
        if ((word & (1ULL << bit_idx)) != 0) {
          reserved_subpartitions.insert(static_cast<unsigned>(word_idx * 64 + bit_idx));
        }
      }
    }
    if (!any_reserved) {
      continue;
    }

    // add this to the reserved_subpartitions_by_cycle map for the given cycle
    const std::uint64_t cycle = reserved_subpartitions_base_cycle + static_cast<std::uint64_t>(offset);
    reserved_subpartitions_by_cycle.emplace(cycle, std::move(reserved_subpartitions));
  }

  std::cout << "[bridge driver] decoded reservedSubPartitionsByCycle: cycles="
            << reserved_subpartitions_by_cycle.size()
            << " baseIdx=" << reserved_subpartitions_base_idx
            << " baseCycle=" << reserved_subpartitions_base_cycle << std::endl;

  return bytes_received;
}

size_t trafficgen_t::process_completed_bundle_ids_stream() {
  const size_t total_bytes = COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES;
  if (completed_bundle_bytes_received >= total_bytes) {
    return 0;
  }

  const size_t remaining_bytes = total_bytes - completed_bundle_bytes_received;
  const auto bytes_received =
      pull(this->stream_to_host_idx,
           completed_bundle_stream_bytes.data() + completed_bundle_bytes_received,
           remaining_bytes,
           0);
  if (bytes_received == 0) {
    return 0;
  }
  if (bytes_received > remaining_bytes) {
    std::cout << "[bridge driver] completedBundleIds overrun: remaining="
              << remaining_bytes << " got=" << bytes_received << std::endl;
    std::abort();
  }
  completed_bundle_bytes_received += bytes_received;
  if (completed_bundle_bytes_received < total_bytes) {
    return bytes_received;
  }

  std::memcpy(completed_bundle_ids.data(),
              completed_bundle_stream_bytes.data(),
              sizeof(completed_bundle_ids));
  completed_bundle_count =
      static_cast<uint32_t>(read(mmio_addrs.completed_bundle_count));

  std::cout << "[bridge driver] completedBundleIds count="
            << completed_bundle_count << std::endl;
  for (size_t i = 0; i < completed_bundle_count && i < COMPLETED_BUNDLE_ID_COUNT; ++i) {
    std::cout << "[bridge driver] completedBundleIds[" << i
              << "]=" << completed_bundle_ids[i] << std::endl;
  }

  return bytes_received;
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

  const size_t remaining_bytes = total_bytes - issued_access_writeback_bytes_received;
  const auto bytes_received =
      pull(this->stream_to_host_idx,
           issued_access_writeback_stream_bytes.data() +
               issued_access_writeback_bytes_received,
           remaining_bytes,
           0);
  if (bytes_received == 0) {
    return 0;
  }
  if (bytes_received > remaining_bytes) {
    std::cout << "[bridge driver] issuedAccessWriteback overrun: remaining="
              << remaining_bytes << " got=" << bytes_received << std::endl;
    std::abort();
  }
  issued_access_writeback_bytes_received += bytes_received;
  if (issued_access_writeback_bytes_received < total_bytes) {
    return bytes_received;
  }

  issued_access_writeback_entries.clear();
  issued_access_writeback_entries.reserve(issued_access_writeback_count);
  for (uint32_t access_idx = 0; access_idx < issued_access_writeback_count; ++access_idx) {
    const auto *beat_words = reinterpret_cast<const uint64_t *>(
        issued_access_writeback_stream_bytes.data() +
        static_cast<size_t>(access_idx) * STREAM_WIDTH_BYTES);
    issued_access_writeback_entries.push_back(unpack_l2_access(beat_words));
  }

  std::cout << "[bridge driver] issuedAccessWriteback count="
            << issued_access_writeback_count << std::endl;
  return bytes_received;
}

void trafficgen_t::push_upload_data() {
  if (upload_phase == trafficgen_upload_phase_t::done) {
    return;
  }

  // first upload the L2 access pattern
  if (upload_phase == trafficgen_upload_phase_t::l2_accesses) {
    const size_t total_bytes = l2_accesses.size() * L2_ACCESS_STREAM_BYTES;

    // move on when all access uploaded
    if (upload_cursor >= total_bytes) {
      upload_phase = trafficgen_upload_phase_t::blocked_warp_bitmap;
      return;
    }

    // find which l2_accesses entry contains the next unsent byte
    const size_t entry_index = upload_cursor / L2_ACCESS_STREAM_BYTES;
    // how far into that entry the next unsent byte is
    const size_t entry_byte_offset = upload_cursor % L2_ACCESS_STREAM_BYTES;
    // how much of the whole L2 access upload is still unsent 
    const size_t remaining_bytes = total_bytes - upload_cursor;

    // figure out how many whole entries we can pack into the next stream chunk given unsent byte offset and stream depth
    const size_t chunk_bytes = std::min(remaining_bytes, static_cast<size_t>(stream_from_host_depth) * L2_ACCESS_STREAM_BYTES);
    const size_t packed_bytes = entry_byte_offset + chunk_bytes;
    const size_t chunk_entries = (packed_bytes + L2_ACCESS_STREAM_BYTES - 1) / L2_ACCESS_STREAM_BYTES;

    // Pack enough whole entries to cover the byte range we still need to stream.
    std::vector<uint64_t> inbuf(chunk_entries * 8, 0);
    auto *words = inbuf.data();

    // pack the chunk of L2 accesses starting from the entry containing the next unsent byte
    for (size_t i = 0; i < chunk_entries; ++i) {
      pack_l2_access(l2_accesses[entry_index + i], words + (i * 8));
    }

    // send bytes from entry_byte_offset onwards
    auto *chunk_start = reinterpret_cast<uint8_t *>(inbuf.data()) + entry_byte_offset;
    const auto bytes_pushed = push(stream_from_host_idx, chunk_start, chunk_bytes, 0);
    upload_cursor += bytes_pushed;

    // if we sent all of them, move on to blocked warp bitmap
    if (upload_cursor >= total_bytes) {
      upload_phase = trafficgen_upload_phase_t::blocked_warp_bitmap;
    }
    return;
  }

  // next upload the blocked warp bitmap
  if (upload_phase == trafficgen_upload_phase_t::blocked_warp_bitmap) {
    const size_t total_bytes = BLOCKED_WARP_BITMAP_BEATS * STREAM_WIDTH_BYTES;

    // complete when all accesses uploaded
    if (blocked_warp_bitmap_upload_cursor >= total_bytes) {
      upload_phase = trafficgen_upload_phase_t::done;
      return;
    }

    const size_t remaining_bytes = total_bytes - blocked_warp_bitmap_upload_cursor;
    const size_t chunk_bytes = std::min(
        remaining_bytes,
        static_cast<size_t>(stream_from_host_depth) * STREAM_WIDTH_BYTES);

    auto *bitmap_bytes = reinterpret_cast<uint8_t *>(blocked_warp_bitmap.data());
    const auto bytes_pushed =
        push(stream_from_host_idx,
             bitmap_bytes + blocked_warp_bitmap_upload_cursor,
             chunk_bytes,
             0);
    blocked_warp_bitmap_upload_cursor += bytes_pushed;

    // complete when all beats sent
    if (blocked_warp_bitmap_upload_cursor >= total_bytes) {
      upload_phase = trafficgen_upload_phase_t::done;
    }
  }
}

void trafficgen_t::tick() {
  switch (state) {
  case trafficgen_state_t::IDLE:
    // Wait for the traffic generator to be kicked off.

    if (read(mmio_addrs.start_trafficgen)) {
      std::cout << "[bridge driver] start signal received, starting traffic generation" << std::endl;
      
      // pause the target clock 
      write(mmio_addrs.pause_target, 1);

      if (receive_main_loop_complete_from_gpu_model()) {
        write(mmio_addrs.pause_target, 1);
        state = trafficgen_state_t::IDLE;
        break;
      }

      // trigger bridge module to send reservedSubPartitionsByCycle by stream
      reserved_subpartition_bytes_received = 0;
      reserved_subpartitions_metadata_latched = false;
      reserved_subpartitions_base_idx = 0;
      reserved_subpartitions_base_cycle = 0;
      reserved_subpartitions_words.fill(0);
      reserved_subpartitions_by_cycle.clear();
      reserved_subpartitions_read_issued = false;

      state = trafficgen_state_t::READ_RESERVED_PARTITIONS;
    }

    break;
  case trafficgen_state_t::READ_RESERVED_PARTITIONS:
    if (!reserved_subpartitions_read_issued) {
      reserved_subpartition_bytes_received = 0;
      reserved_subpartitions_metadata_latched = false;
      reserved_subpartitions_base_idx = 0;
      reserved_subpartitions_base_cycle = 0;
      reserved_subpartitions_words.fill(0);
      reserved_subpartitions_by_cycle.clear();
      write(mmio_addrs.read_reserved_subpartitions, 1);
      reserved_subpartitions_read_issued = true;
    }
  
    // read from stream until we have received the full reservedSubPartitionByCycle bitmap
    reserved_subpartition_bytes_received += process_reserved_subpartitions_stream();
    if (reserved_subpartition_bytes_received >= STREAM_BATCH_BYTES) {
      std::cout << "[bridge driver] completed reading reservedSubPartitionsByCycle stream data, bytes received="
                << reserved_subpartition_bytes_received << std::endl;
      
      // send the reservedSubPartitionsByCycle snapshot to gpu_model via socket
      send_reserved_subpartitions_snapshot();

      // read the L2 access schedule, blocked warp IDs, and min issue cycle from gpu_model via socket
      receive_schedule_from_gpu_model();

      // Reset upload progress and kick off host->target streaming.
      upload_cursor = 0;
      blocked_warp_bitmap_upload_cursor = 0;
      upload_phase = l2_accesses.empty() ? trafficgen_upload_phase_t::blocked_warp_bitmap
                                         : trafficgen_upload_phase_t::l2_accesses;
      write(mmio_addrs.upload_count, static_cast<uint32_t>(l2_accesses.size()));
      write(mmio_addrs.upload_start, 1);
      write(mmio_addrs.min_issue_cycle_low,
            static_cast<uint32_t>(min_issue_cycle & 0xffffffffULL));
      write(mmio_addrs.min_issue_cycle_high,
            static_cast<uint32_t>(min_issue_cycle >> 32));
      reserved_subpartitions_read_issued = false;
      
      state = trafficgen_state_t::UPLOAD_SCHEDULE;
    }
  
    break;
  case trafficgen_state_t::UPLOAD_SCHEDULE:

    // write schedule to bridge module via stream
    push_upload_data();

    if (upload_phase == trafficgen_upload_phase_t::done &&
        (l2_accesses.empty() || read(mmio_addrs.upload_done)) &&
        read(mmio_addrs.blocked_warp_upload_done)) {

      std::cout << "[bridge driver] upload completed, entering traffic issuing stage" << std::endl;

      write(mmio_addrs.start_round, 1);
      // pause_target is a pulse-driven toggle in the bridge module.
      write(mmio_addrs.pause_target, 1);
      round_completion_pause_issued = false;
      state = trafficgen_state_t::ISSUING_TRAFFIC;
    }

    break;
  case trafficgen_state_t::ISSUING_TRAFFIC:
    // Wait for the target to finish generating memory traffic for the current round.

    if (read(mmio_addrs.round_complete) && !round_completion_pause_issued) {
      // Hold target fire low while bridge-side retire and reservation clear sweeps drain.
      write(mmio_addrs.pause_target, 1);
      round_completion_pause_issued = true;
    }

    if (round_completion_pause_issued &&
        !read(mmio_addrs.target_busy) &&
        read(mmio_addrs.round_complete)) {
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

      // read issued accesses with writeback info, completed bundle IDs, and other round results from the target via MMIO and stream, then send them to gpu_model via socket
      if (!issued_access_writeback_read_issued) {
        issued_access_writeback_count = static_cast<uint32_t>(read(mmio_addrs.issued_access_writeback_count));
        issued_access_writeback_stream_bytes.assign(static_cast<size_t>(issued_access_writeback_count) * STREAM_WIDTH_BYTES, 0);
        issued_access_writeback_bytes_received = 0;
        issued_access_writeback_entries.clear();
        if (issued_access_writeback_count != 0) {
          write(mmio_addrs.read_issued_access_writeback, 1);
        }
        issued_access_writeback_read_issued = true;
      }
      if (issued_access_writeback_bytes_received < static_cast<size_t>(issued_access_writeback_count) * STREAM_WIDTH_BYTES) {
        process_issued_access_writeback_stream();
      }
      if (issued_access_writeback_bytes_received < static_cast<size_t>(issued_access_writeback_count) * STREAM_WIDTH_BYTES) {
        break;
      }

      if (!completed_bundle_read_issued) {
        completed_bundle_stream_bytes.fill(0);
        completed_bundle_bytes_received = 0;
        write(mmio_addrs.read_completed_bundle_ids, 1);
        completed_bundle_read_issued = true;
      }
      if (completed_bundle_bytes_received < COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES) {
        process_completed_bundle_ids_stream();
      }
      if (completed_bundle_bytes_received < COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES) {
        break;
      }

      TrafficGenResultMessage message;
      message.trafficGenResult.issuedAccesses.reserve(issued_access_writeback_entries.size());
      for (const auto &access : issued_access_writeback_entries) {
        IssuedAccessPoint issued_access_point{};
        issued_access_point.requestUid = access.id;
        issued_access_point.address = access.address;
        issued_access_point.cycleIssued = access.cycle_count;
        issued_access_point.l1ToL2Cycle = 0;
        issued_access_point.elapsedCycle = 0;
        issued_access_point.smId = access.sm_id;
        issued_access_point.schedulerId = access.scheduler_id;
        issued_access_point.warpId = access.warp_id;
        issued_access_point.isWrite = access.m_is_write;
        message.trafficGenResult.issuedAccesses.push_back(issued_access_point);
      }

      message.trafficGenResult.completedBundleIds.clear();
      message.trafficGenResult.completedBundleIds.reserve(completed_bundle_count);
      for (size_t i = 0; i < completed_bundle_count && i < COMPLETED_BUNDLE_ID_COUNT; ++i) {
        message.trafficGenResult.completedBundleIds.push_back(completed_bundle_ids[i]);
      }
      message.trafficGenResult.currentCycleAfterIssue =
          (static_cast<std::uint64_t>(
               read(mmio_addrs.current_cycle_after_issue_high))
           << 32) |
          static_cast<std::uint64_t>(
              read(mmio_addrs.current_cycle_after_issue_low));
      message.hasPendingWork = read(mmio_addrs.has_pending_work) != 0;

      if (!gpu_model_socket_client) {
        throw std::runtime_error(
            "gpu_model socket client is not initialized");
      }
      gpu_model_socket_client->send_frame(serialize_message(message));

      if (receive_main_loop_complete_from_gpu_model()) {
        // Resume the target CPU so the target-side trafficgen test can observe
        // idle and finish after the final scheduling round has been reported.
        write(mmio_addrs.pause_target, 1);
        state = trafficgen_state_t::IDLE;
      } else {
        issued_access_writeback_entries.clear();
        issued_access_writeback_stream_bytes.clear();
        issued_access_writeback_count = 0;
        issued_access_writeback_bytes_received = 0;
        issued_access_writeback_read_issued = false;
        completed_bundle_stream_bytes.fill(0);
        completed_bundle_bytes_received = 0;
        completed_bundle_read_issued = false;
        reserved_subpartition_bytes_received = 0;
        reserved_subpartitions_metadata_latched = false;
        reserved_subpartitions_base_idx = 0;
        reserved_subpartitions_base_cycle = 0;
        reserved_subpartitions_words.fill(0);
        reserved_subpartitions_by_cycle.clear();
        reserved_subpartitions_read_issued = false;
        state = trafficgen_state_t::READ_RESERVED_PARTITIONS;
      }
    }
    break;
  }
}

void trafficgen_t::finish() {
  pull_flush(stream_to_host_idx);
}

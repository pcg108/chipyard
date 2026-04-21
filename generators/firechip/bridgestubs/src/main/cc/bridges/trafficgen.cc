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
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/socket.h>
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

std::string errno_message(const std::string &prefix) {
  std::ostringstream oss;
  oss << prefix << ": " << std::strerror(errno);
  return oss.str();
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
          "[trafficgen] failed to send reservations to gpu_model_socket"));
    }
    if (written == 0) {
      throw std::runtime_error(
          "[trafficgen] failed to send reservations to gpu_model_socket: "
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
          "[trafficgen] failed to receive schedule from gpu_model_socket"));
    }
    if (received == 0) {
      throw std::runtime_error(
          "[trafficgen] failed to receive schedule from gpu_model_socket: "
          "peer disconnected");
    }
    cursor += received;
    remaining -= static_cast<std::size_t>(received);
  }
}

std::string serialize_reservations_message(
    const L2SubpartitionReservationsByCycle &reserved_subpartitions_by_cycle) {
  ReservationsMessage message;
  message.reservedSubpartitionsByCycle = reserved_subpartitions_by_cycle;

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
    std::fprintf(stderr,
                 "[trafficgen] %s out of range for target upload: %" PRIu64
                 "\n",
                 field_name,
                 static_cast<std::uint64_t>(value));
    std::abort();
  }
  return static_cast<std::uint32_t>(value);
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
          errno_message("[trafficgen] failed to create gpu_model socket"));
    }

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(port);
    address.sin_addr.s_addr = htonl(kGpuModelSocketAddr);

    if (connect(fd, reinterpret_cast<sockaddr *>(&address), sizeof(address)) <
        0) {
      const std::string message = errno_message(
          "[trafficgen] failed to connect to gpu_model_socket at "
          "127.0.0.1:50051");
      close(fd);
      fd = -1;
      throw std::runtime_error(message);
    }
  }

  void send_frame(const std::string &payload) const {
    if (fd < 0) {
      throw std::runtime_error(
          "[trafficgen] gpu_model socket is not connected");
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
          "[trafficgen] gpu_model socket is not connected");
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
    std::fprintf(stderr,
                 "[trafficgen] blocked warp tuple out of range: sm=%u "
                 "scheduler=%u warp=%u\n",
                 sm_id,
                 scheduler_id,
                 warp_id);
    std::abort();
  }

  return (sm_id << (trafficgen_t::BLOCKED_WARP_SCHEDULER_BITS +
                    trafficgen_t::BLOCKED_WARP_WARP_BITS)) |
         (scheduler_id << trafficgen_t::BLOCKED_WARP_WARP_BITS) | warp_id;
}

static void pack_l2_access(const trafficgen_l2_access_t &access,
                           uint64_t *words) {

  // serialize one L2 access struct into 8 64-bit ints
  words[0] = (static_cast<uint64_t>(access.address) << 32) | access.id;
  words[1] = (static_cast<uint64_t>(access.m_subpartition) << 32) |
             access.cycle_count;
  words[2] = (static_cast<uint64_t>(access.m_tag) << 32) | access.m_set_index;
  words[3] = (static_cast<uint64_t>(access.sm_id) << 32) | access.m_mask;
  words[4] =
      (static_cast<uint64_t>(access.warp_id) << 32) | access.scheduler_id;
  words[5] = (static_cast<uint64_t>(access.m_wake_relevant_bundle) << 32) |
             access.m_bundle_id;
  words[6] = access.m_is_write ? 1ULL : 0ULL;
  words[7] = 0ULL;
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
  reserved_subpartition_bytes_received = 0;
  reserved_subpartitions_metadata_latched = false;
  min_issue_cycle = 0;
  l2_accesses.clear();
  blocked_warp_bitmap.fill(0);
  upload_cursor = 0;
  blocked_warp_bitmap_upload_cursor = 0;
  upload_phase = trafficgen_upload_phase_t::done;
  reserved_subpartitions_read_issued = false;
  completed_bundle_ids_read_issued = false;
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
  std::fprintf(stderr,
               "[trafficgen] connected to gpu_model_socket at 127.0.0.1:%u\n",
               static_cast<unsigned>(kGpuModelSocketPort));
}

void trafficgen_t::send_reserved_subpartitions_snapshot() const {
  if (!gpu_model_socket_client) {
    throw std::runtime_error(
        "[trafficgen] gpu_model socket client is not initialized");
  }

  const std::string payload =
      serialize_reservations_message(reserved_subpartitions_by_cycle);
  gpu_model_socket_client->send_frame(payload);

  std::fprintf(
      stderr,
      "[trafficgen] sent reservedSubPartitionsByCycle to gpu_model_socket: "
      "cycles=%zu payload_bytes=%zu\n",
      reserved_subpartitions_by_cycle.size(),
      payload.size());
}

void trafficgen_t::receive_schedule_from_gpu_model() {
  if (!gpu_model_socket_client) {
    throw std::runtime_error(
        "[trafficgen] gpu_model socket client is not initialized");
  }

  const SchedulerRoundMessage message =
      gpu_model_socket_client->recv_message<SchedulerRoundMessage>();

  min_issue_cycle = message.min_issue_cycle;
  l2_accesses.clear();
  l2_accesses.reserve(message.allL2TraceSteps.size());
  for (const auto &wire_access : message.allL2TraceSteps) {
    trafficgen_l2_access_t access{};
    access.id = checked_u32(wire_access.mUniqueId, "l2 access unique id");
    access.address = checked_u32(wire_access.mAddress, "l2 access address");
    access.cycle_count =
        checked_u32(wire_access.mCycleCount, "l2 access cycle count");
    access.m_subpartition = checked_u32(
        static_cast<std::uint64_t>(wire_access.mSubpartition),
        "l2 access subpartition");
    access.m_set_index = checked_u32(
        static_cast<std::uint64_t>(wire_access.mSetIndex),
        "l2 access set index");
    access.m_tag = checked_u32(wire_access.mTag, "l2 access tag");
    access.m_mask = checked_u32(
        static_cast<std::uint64_t>(wire_access.mMask), "l2 access mask");
    access.sm_id = checked_u32(
        static_cast<std::uint64_t>(wire_access.smId), "l2 access sm id");
    access.scheduler_id = checked_u32(
        static_cast<std::uint64_t>(wire_access.schedulerId),
        "l2 access scheduler id");
    access.warp_id = checked_u32(
        static_cast<std::uint64_t>(wire_access.warpId), "l2 access warp id");
    access.m_bundle_id =
        checked_u32(wire_access.mBundleId, "l2 access bundle id");
    access.m_wake_relevant_bundle = wire_access.mWakeRelevantBundle ? 1u : 0u;
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

  std::fprintf(stderr,
               "[trafficgen] received schedule from gpu_model_socket: "
               "l2_accesses=%zu blocked_warps=%zu min_issue_cycle=%" PRIu64
               "\n",
               l2_accesses.size(),
               message.blockedWarpIds.size(),
               min_issue_cycle);
}


size_t trafficgen_t::process_reserved_subpartitions_stream() {
  if (!reserved_subpartitions_metadata_latched) {
    // Snapshot metadata must stay fixed while we accumulate a paused target snapshot.

    // read base index into ring buffer
    reserved_subpartitions_base_idx = static_cast<uint32_t>(read(mmio_addrs.reserved_subpartitions_base_idx));
    if (reserved_subpartitions_base_idx >= STREAM_WORD_COUNT) {
      std::fprintf(stderr,
                   "[trafficgen] reservedSubPartitions baseIdx out of range: %u\n",
                   reserved_subpartitions_base_idx);
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
    std::fprintf(stderr,
                 "[trafficgen] reservedSubPartitionsByCycle overrun: "
                 "remaining=%zu got=%zu\n",
                 remaining_bytes,
                 bytes_received);
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

  std::fprintf(stderr,
               "[trafficgen] decoded reservedSubPartitionsByCycle: cycles=%zu baseIdx=%u baseCycle=%" PRIu64 "\n",
               reserved_subpartitions_by_cycle.size(),
               reserved_subpartitions_base_idx,
               reserved_subpartitions_base_cycle);

  return bytes_received;
}

size_t trafficgen_t::process_completed_bundle_ids_stream() {
  std::vector<uint8_t> outbuf(COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES, 0);
  const auto bytes_received =
      pull(this->stream_to_host_idx,
           outbuf.data(),
           COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES,
           COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES);
  if (bytes_received == 0) {
    return 0;
  }
  if (bytes_received != COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES) {
    std::fprintf(stderr,
                 "[trafficgen] expected %zu bytes for completedBundleIds, got %zu\n",
                 COMPLETED_BUNDLE_ID_BEATS * STREAM_WIDTH_BYTES,
                 bytes_received);
    std::abort();
  }

  auto *bundle_ids = reinterpret_cast<const uint32_t *>(outbuf.data());
  for (size_t i = 0; i < COMPLETED_BUNDLE_ID_COUNT; ++i) {
    completed_bundle_ids[i] = bundle_ids[i];
  }
  completed_bundle_count =
      static_cast<uint32_t>(read(mmio_addrs.completed_bundle_count));

  std::fprintf(stderr,
               "[trafficgen] completedBundleIds count=%u\n",
               completed_bundle_count);
  for (size_t i = 0; i < completed_bundle_count && i < COMPLETED_BUNDLE_ID_COUNT; ++i) {
    std::fprintf(stderr,
                 "[trafficgen] completedBundleIds[%zu]=%u\n",
                 i,
                 completed_bundle_ids[i]);
  }

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
      std::fprintf(stderr, "[trafficgen] start signal received, starting traffic generation\n");
      
      // pause the target clock 
      write(mmio_addrs.pause_target, 1);

      // trigger bridge module to send reservedSubPartitionsByCycle by stream
      reserved_subpartition_bytes_received = 0;
      reserved_subpartitions_metadata_latched = false;
      reserved_subpartitions_base_idx = 0;
      reserved_subpartitions_base_cycle = 0;
      reserved_subpartitions_words.fill(0);
      reserved_subpartitions_by_cycle.clear();
      write(mmio_addrs.read_reserved_subpartitions, 1);

      state = trafficgen_state_t::READ_RESERVED_PARTITIONS;
    }

    break;
  case trafficgen_state_t::READ_RESERVED_PARTITIONS:
  
    // read from stream until we have received the full reservedSubPartitionByCycle bitmap
    reserved_subpartition_bytes_received += process_reserved_subpartitions_stream();
    if (reserved_subpartition_bytes_received >= STREAM_BATCH_BYTES) {
      std::fprintf(stderr, "[trafficgen] completed reading reservedSubPartitionsByCycle stream data, bytes received=%zu\n", reserved_subpartition_bytes_received);
      
      // send the reservedSubPartitionsByCycle snapshot to gpu_model via socket
      send_reserved_subpartitions_snapshot();
      receive_schedule_from_gpu_model();

      // Reset upload progress and kick off host->target streaming.
      upload_cursor = 0;
      blocked_warp_bitmap_upload_cursor = 0;
      upload_phase = l2_accesses.empty() ? trafficgen_upload_phase_t::blocked_warp_bitmap
                                         : trafficgen_upload_phase_t::l2_accesses;
      write(mmio_addrs.upload_count, static_cast<uint32_t>(l2_accesses.size()));
      write(mmio_addrs.upload_start, 1);
      write(mmio_addrs.min_issue_cycle,
            checked_u32(min_issue_cycle, "min issue cycle"));
      
      state = trafficgen_state_t::UPLOAD_SCHEDULE;
    }
  
    break;
  case trafficgen_state_t::UPLOAD_SCHEDULE:

    push_upload_data();

    if (upload_phase == trafficgen_upload_phase_t::done &&
        (l2_accesses.empty() || read(mmio_addrs.upload_done)) &&
        read(mmio_addrs.blocked_warp_upload_done)) {

      std::fprintf(stderr, "[trafficgen] upload completed, entering traffic issuing stage\n");

      write(mmio_addrs.pause_target, 0);
      state = trafficgen_state_t::ISSUING_TRAFFIC;
    }

    break;
  case trafficgen_state_t::ISSUING_TRAFFIC:
    // Active traffic issuing flow will live here.

    // once target stops being busy, go back to READ_RESERVED_PARTITIONS or IDLE if we are done
    if (!read(mmio_addrs.target_busy)) {
      state = trafficgen_state_t::READ_RESERVED_PARTITIONS;
    }
    break;
  }



  


  if (read(mmio_addrs.completed_bundle_ids_valid) &&
      read(mmio_addrs.completed_bundle_count_valid) &&
      !completed_bundle_ids_read_issued) {
    write(mmio_addrs.read_completed_bundle_ids, 1);
    completed_bundle_ids_read_issued = true;
    process_completed_bundle_ids_stream();
  }
}

void trafficgen_t::finish() {
  pull_flush(stream_to_host_idx);
}

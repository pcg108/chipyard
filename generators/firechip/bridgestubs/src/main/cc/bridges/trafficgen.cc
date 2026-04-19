// See LICENSE for license details

#include "trafficgen.h"
#include "core/simif.h"

#include <algorithm>
#include <cassert>
#include <cinttypes>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <sstream>

char trafficgen_t::KIND;

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

static uint32_t load_min_issue_cycle(int trafficgenno,
                                     const std::vector<std::string> &args) {
  const std::string arg_prefix =
      "+trafficgen-min-issue-cycle" + std::to_string(trafficgenno) + "=";
  for (const auto &arg : args) {
    if (arg.find(arg_prefix) == 0) {
      return static_cast<uint32_t>(
          std::strtoul(arg.substr(arg_prefix.length()).c_str(), nullptr, 0));
    }
  }
  return 0;
}

static std::vector<trafficgen_l2_access_t>
load_l2_accesses(int trafficgenno, const std::vector<std::string> &args) {
  const std::string arg_prefix =
      "+trafficgen-l2access-file" + std::to_string(trafficgenno) + "=";
  std::string path;
  for (const auto &arg : args) {
    if (arg.find(arg_prefix) == 0) {
      path = arg.substr(arg_prefix.length());
      break;
    }
  }

  if (path.empty()) {
    return {};
  }

  std::ifstream input(path);
  if (!input) {
    std::fprintf(stderr,
                 "[trafficgen] could not open L2 access file '%s'\n",
                 path.c_str());
    std::abort();
  }

  std::vector<trafficgen_l2_access_t> accesses;
  std::string line;
  while (std::getline(input, line)) {
    auto comment = line.find('#');
    if (comment != std::string::npos) {
      line.erase(comment);
    }
    if (line.find_first_not_of(" \t\r\n") == std::string::npos) {
      continue;
    }

    for (auto &ch : line) {
      if (ch == ',') {
        ch = ' ';
      }
    }

    std::stringstream parser(line);
    trafficgen_l2_access_t access{};
    unsigned is_write = 0;
    if (!(parser >> access.id >> access.address >> access.cycle_count >>
          access.m_subpartition >> access.m_set_index >> access.m_tag >>
          access.m_mask >> access.sm_id >> access.scheduler_id >>
          access.warp_id >> is_write >> access.m_bundle_id >>
          access.m_wake_relevant_bundle)) {
      std::fprintf(stderr,
                   "[trafficgen] malformed L2 access line: %s\n",
                   line.c_str());
      std::abort();
    }
    access.m_is_write = is_write != 0;
    accesses.push_back(access);
  }

  std::stable_sort(accesses.begin(),
                   accesses.end(),
                   [](const trafficgen_l2_access_t &lhs,
                      const trafficgen_l2_access_t &rhs) {
                     return lhs.cycle_count < rhs.cycle_count;
                   });
  return accesses;
}

static std::array<uint64_t, trafficgen_t::BLOCKED_WARP_BITMAP_WORDS>
load_blocked_warp_bitmap(int trafficgenno, const std::vector<std::string> &args) {
  const std::string arg_prefix =
      "+trafficgen-blocked-warps-file" + std::to_string(trafficgenno) + "=";
  std::string path;
  for (const auto &arg : args) {
    if (arg.find(arg_prefix) == 0) {
      path = arg.substr(arg_prefix.length());
      break;
    }
  }

  std::array<uint64_t, trafficgen_t::BLOCKED_WARP_BITMAP_WORDS> bitmap{};
  bitmap.fill(0);

  if (path.empty()) {
    return bitmap;
  }

  std::ifstream input(path);
  if (!input) {
    std::fprintf(stderr,
                 "[trafficgen] could not open blocked warp file '%s'\n",
                 path.c_str());
    std::abort();
  }

  std::string line;
  while (std::getline(input, line)) {
    auto comment = line.find('#');
    if (comment != std::string::npos) {
      line.erase(comment);
    }
    if (line.find_first_not_of(" \t\r\n") == std::string::npos) {
      continue;
    }

    for (auto &ch : line) {
      if (ch == ',') {
        ch = ' ';
      }
    }

    std::stringstream parser(line);
    trafficgen_blocked_warp_t blocked_warp{};
    if (!(parser >> blocked_warp.sm_id >> blocked_warp.scheduler_id >>
          blocked_warp.warp_id)) {
      std::fprintf(stderr,
                   "[trafficgen] malformed blocked warp line: %s\n",
                   line.c_str());
      std::abort();
    }

    const auto index = blocked_warp_index(
        blocked_warp.sm_id, blocked_warp.scheduler_id, blocked_warp.warp_id);
    bitmap[index / 64] |= (1ULL << (index % 64));
  }

  return bitmap;
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
                           int trafficgenno,
                           const std::vector<std::string> &args,
                           int stream_to_host_idx,
                           int stream_to_host_depth,
                           int stream_from_host_idx,
                           int stream_from_host_depth)
    : streaming_bridge_driver_t(simif, stream, &KIND),
      mmio_addrs(mmio_addrs),
      stream_to_host_idx(stream_to_host_idx),
      stream_to_host_depth(stream_to_host_depth),
      stream_from_host_idx(stream_from_host_idx),
      stream_from_host_depth(stream_from_host_depth),
      min_issue_cycle(load_min_issue_cycle(trafficgenno, args)),
      blocked_warp_bitmap(load_blocked_warp_bitmap(trafficgenno, args)),
      l2_accesses(load_l2_accesses(trafficgenno, args)) {
  static_assert(BLOCKED_WARP_BITMAP_BITS % (STREAM_WIDTH_BYTES * 8) == 0,
                "Blocked warp bitmap must align to stream beats");
}

trafficgen_t::~trafficgen_t() = default;

void trafficgen_t::init() {
  
  completed_bundle_ids.fill(0);
  completed_bundle_count = 0;
  reserved_subpartition_bytes_received = 0;
  reserved_subpartitions_metadata_latched = false;
  upload_cursor = 0;
  blocked_warp_bitmap_upload_cursor = 0;
  upload_phase = l2_accesses.empty() ? trafficgen_upload_phase_t::blocked_warp_bitmap
                                     : trafficgen_upload_phase_t::l2_accesses;
  reserved_subpartitions_read_issued = false;
  completed_bundle_ids_read_issued = false;
  reserved_subpartitions_base_idx = 0;
  reserved_subpartitions_base_cycle = 0;
  reserved_subpartitions_words.fill(0);
  reserved_subpartitions_by_cycle.clear();
  state = trafficgen_state_t::IDLE;
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
    reserved_subpartitions_bytes_received += process_reserved_subpartitions_stream();
    if (reserved_subpartitions_bytes_received >= STREAM_BATCH_BYTES) {
      std::fprintf(stderr, "[trafficgen] completed reading reservedSubPartitionsByCycle stream data, bytes received=%zu\n", reserved_subpartitions_bytes_received);
      
      // todo: connect to GPU model and generate L2 accesses

      // Reset upload progress and kick off host->target streaming.
      upload_cursor = 0;
      blocked_warp_bitmap_upload_cursor = 0;
      upload_phase = l2_accesses.empty() ? trafficgen_upload_phase_t::blocked_warp_bitmap
                                         : trafficgen_upload_phase_t::l2_accesses;
      write(mmio_addrs.upload_count, static_cast<uint32_t>(l2_accesses.size()));
      write(mmio_addrs.upload_start, 1);
      write(mmio_addrs.min_issue_cycle, min_issue_cycle);
      
      state = trafficgen_state_t::UPLOAD_SCHEDULE;
    }
  
    break;
  case trafficgen_state_t::UPLOAD_SCHEDULE:

    push_upload_data();

    if (upload_phase == trafficgen_upload_phase_t::done &&
        (l2_accesses.empty() || read(mmio_addrs.upload_done)) &&
        read(mmio_addrs.blocked_warp_upload_done)) {

      std::fprintf(stderr, "[trafficgen] upload completed, entering traffic issuing stage\n");
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

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
  assert(stream_to_host_depth >= static_cast<int>(STREAM_BATCH_BEATS));
  static_assert(BLOCKED_WARP_BITMAP_BITS % (STREAM_WIDTH_BYTES * 8) == 0,
                "Blocked warp bitmap must align to stream beats");
}

trafficgen_t::~trafficgen_t() = default;

void trafficgen_t::init() {
  write(mmio_addrs.min_issue_cycle, min_issue_cycle);
  write(mmio_addrs.upload_count, static_cast<uint32_t>(l2_accesses.size()));
  write(mmio_addrs.upload_start, 1);
  completed_bundle_ids.fill(0);
  completed_bundle_count = 0;
  upload_cursor = 0;
  blocked_warp_bitmap_upload_cursor = 0;
  upload_phase = l2_accesses.empty() ? trafficgen_upload_phase_t::blocked_warp_bitmap
                                     : trafficgen_upload_phase_t::l2_accesses;
  reserved_subpartitions_read_issued = false;
  completed_bundle_ids_read_issued = false;
  reserved_subpartitions_base_idx = 0;
  reserved_subpartitions_base_cycle = 0;
  reserved_subpartitions_snapshot_words.fill(0);
  reserved_subpartitions_by_cycle.clear();
}


size_t trafficgen_t::process_reserved_subpartitions_stream() {
  reserved_subpartitions_base_idx = static_cast<uint32_t>(
      read(mmio_addrs.reserved_subpartitions_base_idx));
  if (reserved_subpartitions_base_idx >= STREAM_WORD_COUNT) {
    std::fprintf(stderr,
                 "[trafficgen] reservedSubPartitions baseIdx out of range: %u\n",
                 reserved_subpartitions_base_idx);
    std::abort();
  }
  reserved_subpartitions_base_cycle =
      (static_cast<std::uint64_t>(
           read(mmio_addrs.reserved_subpartitions_base_cycle_high))
       << 32) |
      static_cast<std::uint64_t>(
          read(mmio_addrs.reserved_subpartitions_base_cycle_low));

  const size_t expected_bytes = STREAM_BATCH_BEATS * STREAM_WIDTH_BYTES;
  std::vector<uint8_t> outbuf(expected_bytes, 0);
  const auto bytes_received =
      pull(this->stream_to_host_idx,
           outbuf.data(),
           expected_bytes,
           expected_bytes);
  if (bytes_received == 0) {
    return 0;
  }
  if (bytes_received != expected_bytes) {
    std::fprintf(stderr,
                 "[trafficgen] expected %zu bytes for reservedSubPartitionsByCycle, got %zu\n",
                 expected_bytes,
                 bytes_received);
    std::abort();
  }

  const auto *words = reinterpret_cast<const uint64_t *>(outbuf.data());
  for (size_t beat = 0; beat < STREAM_BATCH_BEATS; ++beat) {
    const size_t beat_word_base = beat * STREAM_WORDS_PER_BEAT;
    const size_t entry_base = beat * 2 * STREAM_WORDS_PER_ENTRY;
    std::memcpy(&reserved_subpartitions_snapshot_words[entry_base],
                &words[beat_word_base],
                STREAM_WORDS_PER_ENTRY * sizeof(uint64_t));
    std::memcpy(&reserved_subpartitions_snapshot_words[entry_base + STREAM_WORDS_PER_ENTRY],
                &words[beat_word_base + STREAM_WORDS_PER_ENTRY],
                STREAM_WORDS_PER_ENTRY * sizeof(uint64_t));
  }

  reserved_subpartitions_by_cycle.clear();
  for (size_t offset = 0; offset < STREAM_WORD_COUNT; ++offset) {
    const size_t ring_idx =
        (static_cast<size_t>(reserved_subpartitions_base_idx) + offset) %
        STREAM_WORD_COUNT;
    const size_t word_base = ring_idx * STREAM_WORDS_PER_ENTRY;
    bool any_reserved = false;
    std::unordered_set<unsigned> reserved_subpartitions;
    for (size_t word_idx = 0; word_idx < STREAM_WORDS_PER_ENTRY; ++word_idx) {
      const uint64_t word = reserved_subpartitions_snapshot_words[word_base + word_idx];
      if (word == 0) {
        continue;
      }
      any_reserved = true;
      for (unsigned bit_idx = 0; bit_idx < 64; ++bit_idx) {
        if ((word & (1ULL << bit_idx)) != 0) {
          reserved_subpartitions.insert(
              static_cast<unsigned>(word_idx * 64 + bit_idx));
        }
      }
    }
    if (!any_reserved) {
      continue;
    }
    const std::uint64_t cycle =
        reserved_subpartitions_base_cycle + static_cast<std::uint64_t>(offset);
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

  if (upload_phase == trafficgen_upload_phase_t::l2_accesses) {
    if (upload_cursor >= l2_accesses.size()) {
      upload_phase = trafficgen_upload_phase_t::blocked_warp_bitmap;
      return;
    }

    const size_t chunk_entries = std::min(
        l2_accesses.size() - upload_cursor,
        static_cast<size_t>(stream_from_host_depth));
    std::vector<uint64_t> inbuf(chunk_entries * 8, 0);
    auto *words = inbuf.data();
    for (size_t i = 0; i < chunk_entries; ++i) {
      pack_l2_access(l2_accesses[upload_cursor + i], words + (i * 8));
    }

    const auto bytes_to_push = chunk_entries * L2_ACCESS_STREAM_BYTES;
    const auto bytes_pushed =
        push(stream_from_host_idx, inbuf.data(), bytes_to_push, 0);
    upload_cursor += bytes_pushed / L2_ACCESS_STREAM_BYTES;
    if (upload_cursor >= l2_accesses.size()) {
      upload_phase = trafficgen_upload_phase_t::blocked_warp_bitmap;
    }
    return;
  }

  if (upload_phase == trafficgen_upload_phase_t::blocked_warp_bitmap) {
    if (blocked_warp_bitmap_upload_cursor >= BLOCKED_WARP_BITMAP_BEATS) {
      upload_phase = trafficgen_upload_phase_t::done;
      return;
    }

    const size_t remaining_beats =
        BLOCKED_WARP_BITMAP_BEATS - blocked_warp_bitmap_upload_cursor;
    const size_t chunk_beats = std::min(
        remaining_beats,
        static_cast<size_t>(stream_from_host_depth));
    const auto *bitmap_words =
        blocked_warp_bitmap.data() + (blocked_warp_bitmap_upload_cursor * 8);
    const auto bytes_to_push = chunk_beats * STREAM_WIDTH_BYTES;
    const auto bytes_pushed =
        push(stream_from_host_idx,
             const_cast<uint64_t *>(bitmap_words),
             bytes_to_push,
             0);
    blocked_warp_bitmap_upload_cursor += bytes_pushed / STREAM_WIDTH_BYTES;
    if (blocked_warp_bitmap_upload_cursor >= BLOCKED_WARP_BITMAP_BEATS) {
      upload_phase = trafficgen_upload_phase_t::done;
    }
  }
}

void trafficgen_t::tick() {

  // start traffic generation when target program writes to start register
  if (!trafficGenActive && read(mmio_addrs.start_trafficgen)) {

    std::fprintf(stderr, "[trafficgen] start signal received, starting traffic generation\n");
    trafficGenActive = true;

  } else {
    
    // if target not busy uploading a traffic pattern or running it, we can run the GPU model and upload the next traffic pattern if applicable
    if (!read(mmio_addrs.target_busy)) {
      
      // pause the target clock 
      write(mmio_addrs.pause_target, 1);

      // trigger bridge module to send reservedSubPartitionsByCycle by stream
      write(mmio_addrs.read_reserved_subpartitions, 1);
      process_reserved_subpartitions_stream();

    } else {
      
    }
  }


  push_upload_data();
  const bool next_busy = read(mmio_addrs.target_busy);
  if (next_busy != target_busy) {
    std::fprintf(stderr, "[trafficgen] target busy=%d\n", next_busy ? 1 : 0);
    target_busy = next_busy;
  }

  if (read(mmio_addrs.upload_done) && read(mmio_addrs.upload_overflow)) {
    std::fprintf(stderr,
                 "[trafficgen] upload overflow: target storage exhausted\n");
  }

  if (read(mmio_addrs.blocked_warp_upload_done) &&
      upload_phase != trafficgen_upload_phase_t::done) {
    std::fprintf(stderr,
                 "[trafficgen] blocked warp upload completed before host "
                 "driver finished streaming\n");
  }

  if (read(mmio_addrs.completed_bundle_ids_snapshot_valid) &&
      !completed_bundle_ids_read_issued) {
    write(mmio_addrs.read_completed_bundle_ids, 1);
    completed_bundle_ids_read_issued = true;
    process_completed_bundle_ids_stream();
  }
}

void trafficgen_t::finish() {
  pull_flush(stream_to_host_idx);
}

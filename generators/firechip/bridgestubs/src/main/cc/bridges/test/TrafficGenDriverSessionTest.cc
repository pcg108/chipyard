// Real driver + real GPU socket transport, with only MMIO/XDMA simulated.
#include "bridges/trafficgen.h"
#include "bridges/cpu_managed_stream.h"
#include "core/config.h"
#include "socket_transport.h"
#include <cassert>
#include <cerrno>
#include <cstring>
#include <future>
#include <iostream>
#include <limits>
#include <unistd.h>

// Minimal platform linkage; driver code and GPU transport are compiled intact.
simif_t::simif_t(const TargetConfig &c) : config(c) {}
simif_t::~simif_t() = default;
CPUManagedStreamIO &simif_t::get_cpu_managed_stream_io() { throw std::runtime_error("unimplemented"); }
FPGAManagedStreamIO &simif_t::get_fpga_managed_stream_io() { throw std::runtime_error("unimplemented"); }
int simif_t::run(simulation_t &) { return 0; }
widget_t::widget_t(simif_t &s, const void *k) : simif(s), kind(k) {}
widget_t::~widget_t() = default;
void bridge_driver_t::write(size_t addr, uint32_t data) { simif.write(addr, data); }
uint32_t bridge_driver_t::read(size_t addr) { return simif.read(addr); }
GPU::L2Access::L2Access() = default;
static const TargetConfig config{{1, 32, 32}, {1, 64, 64}, 1, std::nullopt, std::nullopt, {1, 1}, "test"};
static constexpr std::uint64_t launch_a = (1ULL << 63) + 1, launch_b = launch_a + 1;
static constexpr std::uint64_t launch_c = launch_a + 2, launch_bad = launch_a + 3;
static constexpr std::uint64_t bundle_a = (1ULL << 40) + 200, bundle_b = bundle_a + 1;
static constexpr std::size_t completed_bytes = 32768 * 8;

struct MockTarget : simif_t, CPUManagedStreamIO {
  TRAFFICGENBRIDGEMODULE_struct mmio{};
  struct Slot { std::uint64_t id = 0, registry = 0; unsigned state = 0; };
  std::array<Slot, 4> slots{}, staged{}, published{};
  std::map<std::size_t, std::uint32_t> regs;
  std::size_t issued_offset, completed_offset;
  std::vector<char> memory;
  unsigned completion_count_reads = 0;
  std::vector<std::size_t> completion_read_sizes;
  std::uint64_t cycle = 0;
  unsigned slot_index = 0, physical_round = 0, session_flags = 0, staged_flags = 0;
  bool paused = false, start = false, next_round = false, closed = false, done = false;
  bool queued_c = false, queued_bad = false;
  explicit MockTarget(std::size_t window_bytes = 64) : simif_t(::config),
      issued_offset(window_bytes), completed_offset(2 * window_bytes),
      memory(completed_offset + completed_bytes) {
    std::uint64_t next = 1000000;
#define REG(field) mmio.field = next++
    REG(start_trafficgen); REG(target_busy); REG(has_pending_work); REG(trafficgen_done);
    REG(resume_target); REG(target_paused); REG(start_round); REG(current_round_low); REG(current_round_high);
    REG(upload_count); REG(upload_lane_count_index); REG(upload_lane_count_value); REG(upload_lane_count_write);
    REG(access_store_max_cycle_low); REG(access_store_max_cycle_high); REG(access_store_has_more); REG(commit_upload);
    REG(round_complete); REG(upload_ready); REG(min_issue_cycle_low); REG(min_issue_cycle_high);
    REG(current_cycle_after_issue_low); REG(current_cycle_after_issue_high); REG(round_exit_reason); REG(dpi_state);
    REG(issued_access_writeback_count); REG(issued_lane_count_index); REG(issued_lane_count_value);
    REG(completed_bundle_count); REG(bundle_table_full_lane_mask); REG(launch_slot_index);
    REG(launch_registry_id_low); REG(launch_registry_id_high); REG(launch_id_low); REG(launch_id_high);
    REG(launch_pending_mask); REG(close_submissions); REG(slot_status_id_low); REG(slot_status_id_high);
    REG(slot_status); REG(session_status); REG(commit_launch_status);
#undef REG
  }
  void queue(unsigned index, std::uint64_t id, std::uint64_t registry, std::uint64_t now) {
    assert(slots[index].state == 0 || slots[index].state >= 5);
    slots[index] = {id, registry, 1}; cycle = now; paused = true; start = true;
  }
  uint32_t read(size_t addr) override {
    if (addr == mmio.target_paused) return paused;
    if (addr == mmio.start_trafficgen) return start;
    if (addr == mmio.current_cycle_after_issue_low) return cycle;
    if (addr == mmio.current_cycle_after_issue_high) return cycle >> 32;
    if (addr == mmio.launch_id_low) return slots[slot_index].id;
    if (addr == mmio.launch_id_high) return slots[slot_index].id >> 32;
    if (addr == mmio.launch_registry_id_low) return slots[slot_index].registry;
    if (addr == mmio.launch_registry_id_high) return slots[slot_index].registry >> 32;
    if (addr == mmio.launch_pending_mask) {
      unsigned mask = 0;
      for (unsigned i = 0; i < 4; ++i) if (slots[i].state == 1) mask |= 1U << i;
      return mask;
    }
    if (addr == mmio.close_submissions) return closed;
    if (addr == mmio.completed_bundle_count) { assert(paused); ++completion_count_reads; }
    return regs[addr];
  }
  void write(size_t addr, uint32_t value) override {
    regs[addr] = value;
    if (addr == mmio.launch_slot_index) slot_index = value;
    if (addr == mmio.slot_status_id_low)
      staged[slot_index].id = (staged[slot_index].id & 0xffffffff00000000ULL) | value;
    if (addr == mmio.slot_status_id_high)
      staged[slot_index].id = (staged[slot_index].id & 0xffffffffULL) | (static_cast<std::uint64_t>(value) << 32);
    if (addr == mmio.slot_status) staged[slot_index].state = value;
    if (addr == mmio.session_status) staged_flags = value;
    if (addr == mmio.commit_launch_status) { assert(paused); published = staged; session_flags = staged_flags; }
    if (addr == mmio.commit_upload) regs[mmio.upload_ready] = 1;
    if (addr == mmio.start_round) next_round = true;
    if (addr == mmio.trafficgen_done) done = value;
    if (addr != mmio.resume_target) return;
    assert(paused);
    for (unsigned i = 0; i < 4; ++i)
      if (published[i].id == slots[i].id) slots[i].state = published[i].state;
    paused = false; start = false;
    if (next_round) { next_round = false; run_round(); return; }
    if (done || (session_flags & 32)) return;
    // Software runs through an idle gap, then reuses a completed slot.
    assert(session_flags & 4);
    if (!queued_c) {
      assert(slots[0].state == 5 && slots[1].state == 5);
      queued_c = true; queue(0, launch_c, 10, 1000);
    } else if (!queued_bad) {
      assert(slots[0].state == 5);
      queued_bad = true; queue(2, launch_bad, 999, 2000); closed = true;
    }
  }
  virtual void run_round() {
    ++physical_round;
    if (physical_round == 2 || physical_round == 3) {
      // The scheduler explicitly supplies no deadline for this drain.
      // Keep that sentinel across the private capacity refill.
      assert(regs[mmio.min_issue_cycle_low] == 0xffffffffU &&
             regs[mmio.min_issue_cycle_high] == 0xffffffffU);
    }
    const bool has_access = regs[mmio.upload_count] != 0;
    const auto expected_launch = physical_round == 1 || physical_round == 3 ? launch_a : launch_b;
    const auto expected_uid = physical_round == 3 ? 8 : 7;
    std::uint64_t issued_words[8]{};
    std::uint64_t completed = 0;
    if (physical_round <= 3) {
      assert(has_access && regs[mmio.upload_count] == 1);
      std::uint64_t access[8]{}; std::memcpy(access, memory.data(), sizeof(access));
      const auto launch = (access[4] >> 51) | (access[5] << 13);
      assert(launch == expected_launch && access[0] == static_cast<unsigned>(expected_uid));
      assert(((access[4] >> 3) & 0xffff) == (launch == launch_a ? 2 : 1));
      issued_words[0] = access[0]; issued_words[1] = access[2]; issued_words[2] = access[1];
      issued_words[3] = 1 | (launch << 1); issued_words[4] = launch >> 63;
      if (physical_round == 2) completed = bundle_b;
      if (physical_round == 3) completed = bundle_a;
      cycle = physical_round == 1 ? 105 : physical_round == 2 ? 110 : 120;
    } else {
      assert(!has_access);
      cycle = (static_cast<std::uint64_t>(regs[mmio.min_issue_cycle_high]) << 32) | regs[mmio.min_issue_cycle_low];
      assert(cycle == (physical_round == 4 ? 1007 : 2000));
    }
    std::memcpy(memory.data() + issued_offset, issued_words, sizeof(issued_words));
    std::fill(memory.begin() + completed_offset, memory.end(), 0);
    std::memcpy(memory.data() + completed_offset, &completed, sizeof(completed));
    regs[mmio.issued_access_writeback_count] = has_access;
    regs[mmio.issued_lane_count_value] = has_access;
    regs[mmio.completed_bundle_count] = completed != 0;
    regs[mmio.has_pending_work] = physical_round == 1;
    regs[mmio.round_exit_reason] = physical_round <= 2 ? 1 : 0;
    regs[mmio.round_complete] = 1;
    paused = true;
    if (physical_round == 1) {
      // Launch arrives after capacity exit latched: still must reach server now.
      slots[1] = {launch_b, 20, 1};
    }
  }
  CPUManagedStreamIO &get_cpu_managed_stream_io() override { return *this; }
  uint32_t mmio_read(size_t addr) override { return read(addr); }
  size_t cpu_managed_axi4_write(size_t addr, const char *data, size_t bytes) override {
    assert(addr + bytes <= memory.size()); std::memcpy(memory.data() + addr, data, bytes); return bytes;
  }
  size_t cpu_managed_axi4_read(size_t addr, char *data, size_t bytes) override {
    if (addr >= completed_offset) {
      assert(paused && completion_count_reads == physical_round);
      const auto count = regs[mmio.completed_bundle_count];
      assert(count != 0 && count <= 32768 && addr == completed_offset);
      assert(bytes == ((static_cast<std::size_t>(count) + 7) / 8) * 64);
      completion_read_sizes.push_back(bytes);
    }
    assert(addr + bytes <= memory.size()); std::memcpy(data, memory.data() + addr, bytes); return bytes;
  }
  uint64_t get_beat_bytes() const override { return 64; }
};
GPU::L2Access access(std::uint64_t launch, std::uint64_t registry, std::uint64_t uid,
                     std::uint64_t bundle, std::uint64_t cycle) {
  GPU::L2Access a; a.launchId = launch; a.registryId = registry; a.mUniqueId = uid;
  a.mBundleId = bundle; a.mCycleCount = cycle; a.mSubpartition = 3;
  a.mAddress = 0x1000 + uid * 32; a.mIsWrite = true; a.mWakeRelevantBundle = false;
  a.mKernelFolder = "same_recorded_name"; return a;
}
void server_run(GPU::SocketServerTransport &server, bool truncate, bool disconnect) {
  server.AcceptClient();
  auto init = server.RecvMessage<GPU::TrafficGenInitializationMessage>();
  assert(init.protocolVersion == 2 && init.currentCycle == 0);
  GPU::SchedulingRoundStateMessage status;
  server.SendMessage(status);
  auto c = server.RecvMessage<GPU::RoundControlMessage>();
  assert(c.currentCycle == 100 && c.launches.size() == 1 && c.launches[0].launchId == launch_a && c.launches[0].streamId == 1 && !c.endOfLaunches);
  GPU::SchedulerRoundMessage schedule;
  schedule.min_issue_cycle = 200;
  schedule.allL2TraceSteps = {access(launch_a, 10, 7, bundle_a, 101), access(launch_a, 10, 8, bundle_a, 111)};
  server.SendMessage(schedule);
  auto r = server.RecvMessage<GPU::TrafficGenResultMessage>();
  assert(r.hasPendingWork && r.trafficGenResult.currentCycleAfterIssue == 105 && r.trafficGenResult.completedBundleIds.empty());
  assert(r.trafficGenResult.issuedAccesses.size() == 1 && r.trafficGenResult.issuedAccesses[0].launchId == launch_a);
  if (disconnect) return; // Tear down an active session with uncompleted stores.
  status.currentCycle = 105; status.idle = false; status.acceptedLaunchIds = {launch_a};
  status.truncated = truncate; server.SendMessage(status);
  if (truncate) return;
  c = server.RecvMessage<GPU::RoundControlMessage>();
  assert(c.currentCycle == 105 && c.launches.size() == 1 && c.launches[0].launchId == launch_b && c.launches[0].streamId == 2);
  assert(c.reservedSubpartitionsByCycle.at(111).count(3) == 1 && !c.endOfLaunches);
  schedule.allL2TraceSteps = {access(launch_b, 20, 7, bundle_b, 106)};
  schedule.min_issue_cycle = std::numeric_limits<std::uint64_t>::max();
  server.SendMessage(schedule);
  r = server.RecvMessage<GPU::TrafficGenResultMessage>();
  assert(!r.hasPendingWork && r.trafficGenResult.currentCycleAfterIssue == 120);
  assert(r.trafficGenResult.issuedAccesses.size() == 2);
  assert(r.trafficGenResult.issuedAccesses[0].launchId == launch_b && r.trafficGenResult.issuedAccesses[0].requestUid == 7);
  assert(r.trafficGenResult.issuedAccesses[1].launchId == launch_a && r.trafficGenResult.issuedAccesses[1].requestUid == 8);
  assert(r.trafficGenResult.completedBundleIds == std::vector<std::uint64_t>({bundle_b, bundle_a}));
  status.currentCycle = 120; status.idle = true; status.acceptedLaunchIds = {launch_b}; status.completedLaunchIds = {launch_a, launch_b};
  server.SendMessage(status);
  c = server.RecvMessage<GPU::RoundControlMessage>();
  assert(c.currentCycle == 1000 && c.launches.size() == 1 && c.launches[0].launchId == launch_c && c.launches[0].streamId == 1);
  assert(c.reservedSubpartitionsByCycle.empty() && !c.endOfLaunches);
  schedule.allL2TraceSteps.clear(); schedule.min_issue_cycle = 1007; server.SendMessage(schedule);
  r = server.RecvMessage<GPU::TrafficGenResultMessage>();
  assert(!r.hasPendingWork && r.trafficGenResult.currentCycleAfterIssue == 1007 && r.trafficGenResult.issuedAccesses.empty());
  status.currentCycle = 1007; status.acceptedLaunchIds = {launch_c}; status.completedLaunchIds = {launch_c}; server.SendMessage(status);
  c = server.RecvMessage<GPU::RoundControlMessage>();
  assert(c.currentCycle == 2000 && c.endOfLaunches && c.launches.size() == 1 && c.launches[0].registryId == 999);
  schedule.min_issue_cycle = 2000; server.SendMessage(schedule);
  r = server.RecvMessage<GPU::TrafficGenResultMessage>();
  assert(!r.hasPendingWork && r.trafficGenResult.currentCycleAfterIssue == 2000);
  status.currentCycle = 2000; status.mainLoopComplete = true; status.acceptedLaunchIds.clear(); status.completedLaunchIds.clear();
  status.rejectedLaunches = {{launch_bad, "unknown registryId"}}; server.SendMessage(status);
}
void run(bool truncate, bool disconnect = false) {
  auto server = std::make_unique<GPU::SocketServerTransport>(0);
  const auto port = server->GetPort();
  auto peer = std::async(std::launch::async,
      [server = std::move(server), truncate, disconnect]() mutable {
        server_run(*server, truncate, disconnect);
        server.reset();
      });
  MockTarget target;
  char pattern[] = "/tmp/trafficgen-driver-session-XXXXXX";
  const auto directory = mkdtemp(pattern); assert(directory);
  // Configuring the legacy option must not change native slot submissions or
  // their recoverable per-slot rejection behavior.
  const std::vector<std::string> args{"+trafficgen-socket-port=" + std::to_string(port),
                                     "+trafficgen-legacy-registry-id=1106",
                                     "+trafficgen-round-log-dir=" + std::string(directory)};
  trafficgen_t driver(target, target.mmio, 0, args, 0, 0, 64, 128, 64, completed_bytes, 1, 1);
  driver.init(); target.queue(0, launch_a, 10, 100);
  for (unsigned ticks = 0; ticks < 1000 && !target.done && !driver.terminate(); ++ticks) driver.tick();
  if (truncate || disconnect) {
    assert(driver.terminate() && driver.exit_code() != 0 && !target.done);
    assert((target.session_flags & 32) && !(target.session_flags & 8));
    assert(bool(target.session_flags & 16) == truncate);
    assert(target.slots[0].state == 7 && target.slots[1].state == 7);
  } else {
    assert(target.done && !driver.terminate() && driver.exit_code() == 0);
    assert(target.slots[0].id == launch_c && target.slots[0].state == 5 && target.slots[1].state == 5 && target.slots[2].state == 6);
    assert((target.session_flags & 15) == 15 && target.physical_round == 5);
  }
  assert(target.completion_count_reads == target.physical_round);
  assert(target.completion_read_sizes == (truncate || disconnect
      ? std::vector<std::size_t>{} : std::vector<std::size_t>{64, 64}));
  peer.get(); std::filesystem::remove_all(directory);
}

struct CompletionCountTarget : MockTarget {
  const unsigned completion_count;
  explicit CompletionCountTarget(unsigned count) : MockTarget(9 * 64), completion_count(count) {}
  void run_round() override {
    ++physical_round;
    const unsigned valid_count = completion_count <= 32768 ? completion_count : 0;
    assert(regs[mmio.upload_count] == valid_count);
    // Dirty padding ensures the driver forwards only the advertised IDs.
    std::fill(memory.begin() + completed_offset, memory.end(), 0xa5);
    for (unsigned i = 0; i < valid_count; ++i) {
      std::uint64_t descriptor[8]{};
      std::memcpy(descriptor, memory.data() + i * 64, sizeof(descriptor));
      const auto launch = (descriptor[4] >> 51) | (descriptor[5] << 13);
      assert(launch == launch_a);
      std::uint64_t issued[8]{descriptor[0], descriptor[2], descriptor[1],
                              1 | (launch << 1), launch >> 63};
      std::memcpy(memory.data() + issued_offset + i * 64, issued, sizeof(issued));
      const std::uint64_t bundle = bundle_a + i;
      std::memcpy(memory.data() + completed_offset + i * 8, &bundle, sizeof(bundle));
    }
    cycle = 200;
    regs[mmio.issued_access_writeback_count] = valid_count;
    regs[mmio.issued_lane_count_value] = valid_count;
    regs[mmio.completed_bundle_count] = completion_count;
    regs[mmio.has_pending_work] = 0;
    regs[mmio.round_exit_reason] = 0;
    regs[mmio.round_complete] = 1;
    paused = true;
  }
};

void run_completion_count(unsigned count) {
  const bool invalid = count > 32768;
  auto server = std::make_unique<GPU::SocketServerTransport>(0);
  const auto port = server->GetPort();
  auto peer = std::async(std::launch::async,
      [server = std::move(server), count, invalid]() mutable {
        server->AcceptClient();
        server->RecvMessage<GPU::TrafficGenInitializationMessage>();
        server->SendMessage(GPU::SchedulingRoundStateMessage{});
        const auto control = server->RecvMessage<GPU::RoundControlMessage>();
        assert(control.endOfLaunches && control.launches.size() == 1);
        GPU::SchedulerRoundMessage schedule;
        schedule.min_issue_cycle = 200;
        if (!invalid)
          for (unsigned i = 0; i < count; ++i)
            schedule.allL2TraceSteps.push_back(access(launch_a, 10, 100 + i, bundle_a + i, 101 + i));
        server->SendMessage(schedule);
        if (invalid) {
          bool disconnected = false;
          try { server->RecvMessage<GPU::TrafficGenResultMessage>(); }
          catch (const std::exception &) { disconnected = true; }
          assert(disconnected);
        } else {
          const auto result = server->RecvMessage<GPU::TrafficGenResultMessage>();
          assert(!result.hasPendingWork && result.trafficGenResult.currentCycleAfterIssue == 200);
          assert(result.trafficGenResult.issuedAccesses.size() == count);
          assert(result.trafficGenResult.completedBundleIds.size() == count);
          for (unsigned i = 0; i < count; ++i)
            assert(result.trafficGenResult.completedBundleIds[i] == bundle_a + i);
          GPU::SchedulingRoundStateMessage status;
          status.currentCycle = 200; status.mainLoopComplete = true; status.idle = true;
          status.acceptedLaunchIds = {launch_a}; status.completedLaunchIds = {launch_a};
          server->SendMessage(status);
        }
        server.reset();
      });
  CompletionCountTarget target(count);
  char pattern[] = "/tmp/trafficgen-driver-completions-XXXXXX";
  const auto directory = mkdtemp(pattern); assert(directory);
  const std::vector<std::string> args{"+trafficgen-socket-port=" + std::to_string(port),
                                     "+trafficgen-round-log-dir=" + std::string(directory)};
  trafficgen_t driver(target, target.mmio, 0, args, 0, 0, target.issued_offset,
                      target.completed_offset, target.issued_offset, completed_bytes, 1, 1);
  driver.init(); target.queue(0, launch_a, 10, 100); target.closed = true;
  for (unsigned ticks = 0; ticks < 1000 && !target.done && !driver.terminate(); ++ticks) driver.tick();
  assert(target.completion_count_reads == 1 && target.physical_round == 1);
  if (invalid) {
    assert(driver.terminate() && driver.exit_code() != 0 && !target.done);
    assert(target.session_flags & 32);
    assert(target.completion_read_sizes.empty());
  } else {
    assert(target.done && !driver.terminate());
    assert(target.completion_read_sizes == (count == 0 ? std::vector<std::size_t>{}
        : std::vector<std::size_t>{((static_cast<std::size_t>(count) + 7) / 8) * 64}));
  }
  peer.get(); std::filesystem::remove_all(directory);
}
struct UploadCacheTarget : MockTarget {
  bool fail_changed_write;
  unsigned payload_writes = 0;
  std::map<size_t, unsigned> metadata_writes;
  explicit UploadCacheTarget(bool fail) : fail_changed_write(fail) {}
  void write(size_t addr, uint32_t value) override {
    ++metadata_writes[addr];
    MockTarget::write(addr, value);
  }
  size_t cpu_managed_axi4_write(size_t addr, const char *data, size_t bytes) override {
    assert(addr == 0 && bytes == 64 && paused);
    ++payload_writes;
    if (fail_changed_write && payload_writes == 2) {
      errno = EIO;
      return std::numeric_limits<size_t>::max();
    }
    return MockTarget::cpu_managed_axi4_write(addr, data, bytes);
  }
  void run_round() override {
    ++physical_round;
    const unsigned expected_writes = physical_round <= 2 ? 1 : physical_round <= 4 ? 2 : 3;
    assert(payload_writes == expected_writes);
    // A cached payload still requires every count/metadata/commit write so the
    // bridge resets its read cursors and can replay the retained BRAM contents.
    for (const auto addr : {mmio.upload_lane_count_index, mmio.upload_lane_count_value,
         mmio.upload_lane_count_write, mmio.upload_count, mmio.access_store_max_cycle_low,
         mmio.access_store_max_cycle_high, mmio.access_store_has_more, mmio.commit_upload})
      assert(metadata_writes[addr] == physical_round);
    const bool has_access = physical_round <= 6;
    assert(regs[mmio.upload_count] == has_access);
    const bool issue = physical_round == 4 || physical_round == 6;
    const auto launch = physical_round <= 4 ? launch_a : launch_b;
    const auto bundle = physical_round <= 4 ? bundle_a : bundle_b;
    if (has_access) {
      std::uint64_t descriptor[8]{};
      std::memcpy(descriptor, memory.data(), sizeof(descriptor));
      assert(descriptor[0] == 7 && descriptor[2] == 101 && descriptor[3] == bundle);
      assert(((descriptor[4] >> 51) | (descriptor[5] << 13)) == launch);
      assert(static_cast<uint32_t>(descriptor[4] >> 19) == (physical_round <= 4 ? 1 : 5));
      assert(bool(descriptor[4] & 4) == (physical_round >= 3));
      assert(descriptor[6] == 0 && descriptor[7] == 0);
      if (issue) {
        std::uint64_t issued[8]{descriptor[0], cycle + 1, descriptor[1],
                                1 | (launch << 1), launch >> 63};
        std::memcpy(memory.data() + issued_offset, issued, sizeof(issued));
        std::memcpy(memory.data() + completed_offset, &bundle, sizeof(bundle));
      }
    }
    cycle = 100 + physical_round * 10;
    regs[mmio.issued_access_writeback_count] = issue;
    regs[mmio.issued_lane_count_value] = issue;
    regs[mmio.completed_bundle_count] = issue;
    regs[mmio.has_pending_work] = has_access && !issue;
    regs[mmio.round_exit_reason] = 0;
    regs[mmio.round_complete] = 1;
    paused = true;
    if (physical_round == 4) slots[1] = {launch_b, 20, 1};
    if (physical_round == 6) closed = true;
  }
};

void run_upload_cache(bool fail_changed_write) {
  auto server = std::make_unique<GPU::SocketServerTransport>(0);
  const auto port = server->GetPort();
  auto peer = std::async(std::launch::async,
      [server = std::move(server), fail_changed_write]() mutable {
        server->AcceptClient();
        server->RecvMessage<GPU::TrafficGenInitializationMessage>();
        server->SendMessage(GPU::SchedulingRoundStateMessage{});
        for (unsigned round = 1; round <= 7; ++round) {
          const auto control = server->RecvMessage<GPU::RoundControlMessage>();
          assert(control.currentCycle == 100 + (round - 1) * 10);
          assert(control.launches.size() == (round == 1 || round == 5 ? 1 : 0));
          assert(control.endOfLaunches == (round == 7));
          GPU::SchedulerRoundMessage schedule;
          schedule.min_issue_cycle = control.currentCycle + 10;
          if (round == 1) schedule.allL2TraceSteps = {access(launch_a, 10, 7, bundle_a, 101)};
          if (round == 5) schedule.allL2TraceSteps = {access(launch_b, 20, 7, bundle_b, 101)};
          if (round >= 3 && round <= 6)
            schedule.blockedWarpIds.insert({0, 0, 0, round <= 4 ? launch_a : launch_b});
          server->SendMessage(schedule);
          if (fail_changed_write && round == 3) {
            bool disconnected = false;
            try { server->RecvMessage<GPU::TrafficGenResultMessage>(); }
            catch (const std::exception &) { disconnected = true; }
            assert(disconnected);
            break;
          }
          const auto result = server->RecvMessage<GPU::TrafficGenResultMessage>();
          const bool issue = round == 4 || round == 6;
          assert(result.trafficGenResult.issuedAccesses.size() == (issue ? 1 : 0));
          assert(result.trafficGenResult.completedBundleIds.size() == (issue ? 1 : 0));
          GPU::SchedulingRoundStateMessage status;
          status.currentCycle = 100 + round * 10;
          if (round == 1) status.acceptedLaunchIds = {launch_a};
          if (round == 4) status.completedLaunchIds = {launch_a};
          if (round == 5) status.acceptedLaunchIds = {launch_b};
          if (round == 6) status.completedLaunchIds = {launch_b};
          status.idle = issue || round == 7;
          status.mainLoopComplete = round == 7;
          server->SendMessage(status);
        }
        server.reset();
      });
  UploadCacheTarget target(fail_changed_write);
  char pattern[] = "/tmp/trafficgen-driver-upload-cache-XXXXXX";
  const auto directory = mkdtemp(pattern); assert(directory);
  const std::vector<std::string> args{"+trafficgen-socket-port=" + std::to_string(port),
                                     "+trafficgen-round-log-dir=" + std::string(directory)};
  trafficgen_t driver(target, target.mmio, 0, args, 0, 0, 64, 128, 64, completed_bytes, 1, 1);
  driver.init(); target.queue(0, launch_a, 10, 100);
  for (unsigned ticks = 0; ticks < 1000 && !target.done && !driver.terminate(); ++ticks) driver.tick();
  if (fail_changed_write) {
    assert(driver.terminate() && driver.exit_code() != 0 && !target.done);
    assert(target.physical_round == 2 && target.payload_writes == 2);
    assert(target.metadata_writes[target.mmio.commit_upload] == 2);
    assert(target.session_flags & 32);
  } else {
    assert(target.done && !driver.terminate() && target.physical_round == 7);
    assert(target.payload_writes == 3 && target.metadata_writes[target.mmio.commit_upload] == 7);
  }
  peer.get(); std::filesystem::remove_all(directory);
}

void run_legacy_start(bool configured, bool rejected = false) {
  auto server = std::make_unique<GPU::SocketServerTransport>(0);
  const auto port = server->GetPort();
  auto peer = std::async(std::launch::async,
      [server = std::move(server), configured, rejected]() mutable {
        server->AcceptClient();
        server->RecvMessage<GPU::TrafficGenInitializationMessage>();
        server->SendMessage(GPU::SchedulingRoundStateMessage{});
        if (!configured) {
          bool disconnected = false;
          try { server->RecvMessage<GPU::RoundControlMessage>(); }
          catch (const std::exception &) { disconnected = true; }
          assert(disconnected);
          return;
        }
        const auto control = server->RecvMessage<GPU::RoundControlMessage>();
        assert(control.endOfLaunches && control.launches.size() == 1);
        assert(control.launches[0].registryId == 1106 && control.launches[0].launchId == 1);
        GPU::SchedulerRoundMessage schedule;
        schedule.min_issue_cycle = 200;
        server->SendMessage(schedule);
        const auto result = server->RecvMessage<GPU::TrafficGenResultMessage>();
        assert(!result.hasPendingWork && result.trafficGenResult.currentCycleAfterIssue == 200);
        GPU::SchedulingRoundStateMessage status;
        status.currentCycle = 200; status.mainLoopComplete = true;
        if (rejected) status.rejectedLaunches = {{1, "unknown registryId"}};
        else { status.acceptedLaunchIds = {1}; status.completedLaunchIds = {1}; }
        server->SendMessage(status);
      });
  CompletionCountTarget target(0);
  char pattern[] = "/tmp/trafficgen-driver-legacy-XXXXXX";
  const auto directory = mkdtemp(pattern); assert(directory);
  std::vector<std::string> args{"+trafficgen-socket-port=" + std::to_string(port),
                                 "+trafficgen-round-log-dir=" + std::string(directory)};
  if (configured) args.push_back("+trafficgen-legacy-registry-id=1106");
  trafficgen_t driver(target, target.mmio, 0, args, 0, 0, target.issued_offset,
                      target.completed_offset, target.issued_offset, completed_bytes, 1, 1);
  driver.init(); target.start = true; target.paused = true;
  for (unsigned ticks = 0; ticks < 1000 && !target.done && !driver.terminate(); ++ticks) driver.tick();
  assert(configured && !rejected ? target.done && !driver.terminate() : driver.terminate() && !target.done);
  if (rejected) assert(driver.exit_code() != 0 && (target.session_flags & 32));
  assert(target.slots[0].id == 0); // No target-side slot command was fabricated.
  peer.get(); std::filesystem::remove_all(directory);
}

int main() {
  run(false); run(true); run(false, true);
  run_completion_count(0); run_completion_count(1); run_completion_count(9);
  run_completion_count(32769);
  run_upload_cache(false); run_upload_cache(true);
  run_legacy_start(true); run_legacy_start(false); run_legacy_start(true, true);
  std::cout << "TrafficGen driver session tests passed: overlap, duplicate UIDs, store residency, refills, blocked-memory progress, idle reuse, compute-only, close/rejection, truncation, disconnect, completion DMA counts/bounds, upload cache and failed write\n";
}

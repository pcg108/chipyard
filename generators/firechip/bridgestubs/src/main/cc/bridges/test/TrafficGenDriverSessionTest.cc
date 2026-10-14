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
  struct Physical { std::uint64_t token = 0, bundle = 0; bool issued = false, returned = false; };
  std::map<trafficgen_request_key_t,Physical> physicals;
  std::vector<trafficgen_lrc_ledger::descriptor_t> lrc_reports;
  unsigned reports_round = 0;
  unsigned completion_count_reads = 0;
  std::vector<std::size_t> completion_read_sizes;
  std::vector<std::size_t> metadata_chunk_sizes;
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
    REG(lrc_enable); REG(lrc_metadata_count); REG(lrc_commit_metadata); REG(lrc_metadata_ready);
    REG(lrc_begin_round); REG(lrc_drain_only); REG(lrc_arrival_horizon_low); REG(lrc_arrival_horizon_high);
    REG(lrc_report_count); REG(lrc_clear_reports); REG(lrc_error); REG(lrc_service_capacity);
    REG(lrc_bundle_capacity); REG(lrc_timer_capacity); REG(lrc_refill_only);
    REG(lrc_report_drain_required);
    REG(lrc_service_count); REG(lrc_bundle_count); REG(lrc_timer_count);
    REG(lrc_service_high_water); REG(lrc_bundle_high_water); REG(lrc_timer_high_water); REG(lrc_report_high_water);
    REG(lrc_boundary_flags);
    REG(tma_group_count); REG(tma_edge_count); REG(tma_timer_count);
    REG(tma_group_high_water); REG(tma_edge_high_water); REG(tma_timer_high_water);
#undef REG
  }
  void queue(unsigned index, std::uint64_t id, std::uint64_t registry, std::uint64_t now) {
    assert(slots[index].state == 0 || slots[index].state >= 5);
    slots[index] = {id, registry, 1}; cycle = now; paused = true; start = true;
  }
  uint32_t read(size_t addr) override {
    if (addr == mmio.lrc_service_capacity || addr == mmio.lrc_bundle_capacity || addr == mmio.lrc_timer_capacity) return 65536;
    if (addr == mmio.lrc_metadata_ready) return 1;
    if (addr == mmio.lrc_report_count) {
      if (reports_round != physical_round) {
        reports_round = physical_round; lrc_reports.clear();
        for (unsigned i = 0; i < regs[mmio.completed_bundle_count] && i < 32768; ++i) {
          std::uint64_t bundle = 0; std::memcpy(&bundle,memory.data()+completed_offset+8*i,8);
          for (auto &[key,p] : physicals) {
            (void)key; if (!p.issued || p.returned || p.bundle != bundle) continue;
            trafficgen_lrc_ledger::descriptor_t report{};
            trafficgen_lrc_ledger::bits(report,0,8,1);
            trafficgen_lrc_ledger::bits(report,8,64,p.token);
            trafficgen_lrc_ledger::bits(report,72,64,cycle);
            lrc_reports.push_back(report); p.returned = true;
          }
        }
      }
      return lrc_reports.size();
    }
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
    if (addr == mmio.lrc_clear_reports) lrc_reports.clear();
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
    if (addr == 0x05000000) {
      assert(paused && bytes && bytes <= 4096*64 && bytes % 64 == 0);
      metadata_chunk_sizes.push_back(bytes / 64);
      return bytes;
    }
    if (addr < issued_offset) for (std::size_t offset = 0; offset < bytes; offset += 64) {
      std::uint64_t a[8]{}; std::memcpy(a,data+offset,64);
      const auto launch = (a[4]>>51) | (a[5]<<13);
      physicals[{launch,a[0]}] = {(a[5]>>51)|(a[6]<<13),a[3],false,false};
    }
    assert(addr + bytes <= memory.size()); std::memcpy(memory.data() + addr, data, bytes); return bytes;
  }
  size_t cpu_managed_axi4_read(size_t addr, char *data, size_t bytes) override {
    if (addr == 0x05800000) {
      assert(bytes == lrc_reports.size()*64); std::memcpy(data,lrc_reports.data(),bytes); return bytes;
    }
    if (addr >= completed_offset) {
      assert(paused && completion_count_reads == physical_round);
      const auto count = regs[mmio.completed_bundle_count];
      assert(count != 0 && count <= 32768 && addr == completed_offset);
      assert(bytes == ((static_cast<std::size_t>(count) + 7) / 8) * 64);
      completion_read_sizes.push_back(bytes);
    }
    assert(addr + bytes <= memory.size()); std::memcpy(data, memory.data() + addr, bytes);
    if (addr >= issued_offset && addr < completed_offset) for (std::size_t offset = 0; offset < bytes; offset += 64) {
      std::uint64_t a[8]{}; std::memcpy(a,data+offset,64);
      auto &p = physicals.at({(a[3]>>1)|(a[4]<<63),a[0]}); p.issued = true;
      a[4] |= p.token << 1; a[5] = p.token >> 63; std::memcpy(data+offset,a,64);
    }
    return bytes;
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
  assert(init.protocolVersion == 7 && init.currentCycle == 0);
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
  explicit CompletionCountTarget(unsigned count) :
      MockTarget(std::max(9U, std::min(count, 32768U)) * 64), completion_count(count) {}
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
    cycle = std::max(200U, 102U + valid_count);
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
        schedule.min_issue_cycle = std::max(200U, 102U + count);
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
          assert(!result.hasPendingWork && result.trafficGenResult.currentCycleAfterIssue == std::max(200U, 102U + count));
          assert(result.trafficGenResult.issuedAccesses.size() == count);
          assert(result.trafficGenResult.completedBundleIds.size() == count);
          for (unsigned i = 0; i < count; ++i)
            assert(result.trafficGenResult.completedBundleIds[i] == bundle_a + i);
          GPU::SchedulingRoundStateMessage status;
          status.currentCycle = std::max(200U, 102U + count); status.mainLoopComplete = true; status.idle = true;
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
  if (count == 4097) {
    // More logical metadata than one staging window, with one public round.
    assert(target.metadata_chunk_sizes.size() >= 3);
    assert(target.metadata_chunk_sizes[0] == 4096 && target.metadata_chunk_sizes[1] == 4096);
  }
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
    if (addr == 0x05000000) return MockTarget::cpu_managed_axi4_write(addr,data,bytes);
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
      assert((descriptor[6] >> 51) == 0 && descriptor[7] == 0);
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

struct LrcTarget : MockTarget {
  unsigned pressure_drains = 0;
  void write(size_t addr,uint32_t value) override {
    if (addr == mmio.lrc_clear_reports && regs[mmio.lrc_report_drain_required]) {
      ++pressure_drains;
      assert(physical_round == 1 && !regs[mmio.round_complete]);
      regs[mmio.lrc_report_drain_required] = 0;
      regs[mmio.round_complete] = 1;
    }
    MockTarget::write(addr,value);
  }
  void report(unsigned kind,std::uint64_t token,std::uint64_t at) {
    trafficgen_lrc_ledger::descriptor_t r{};
    trafficgen_lrc_ledger::bits(r,0,8,kind); trafficgen_lrc_ledger::bits(r,8,64,token);
    trafficgen_lrc_ledger::bits(r,72,64,at); lrc_reports.push_back(r);
  }
  void run_round() override {
    ++physical_round; reports_round = physical_round; lrc_reports.clear();
    assert(regs[mmio.lrc_enable] == 1);
    // Socket-round drain classification cannot change after the first private
    // capacity refill, even though earlier physical returns have drained.
    assert(regs[mmio.lrc_drain_only] == 0);
    const bool issue = physical_round < 3;
    assert(regs[mmio.upload_count] == 1); // Reuse keeps the exhausted native window.
    std::uint64_t out[8]{};
    if (issue) {
      std::uint64_t a[8]{}; std::memcpy(a,memory.data(),64);
      const auto launch=(a[4]>>51)|(a[5]<<13), token=(a[5]>>51)|(a[6]<<13);
      assert(launch == launch_a && a[0] == (physical_round == 1 ? 7 : 9));
      out[0]=a[0]; out[1]=a[2]+1; out[2]=a[1]; out[3]=launch<<1; out[4]=launch>>63;
      report(1,token,physical_round == 1 ? 12 : 25);
      if (physical_round == 1) report(2,100,12);
      else { report(2,102,25); report(2,101,30); }
    }
    if (issue) {
      std::memcpy(memory.data()+issued_offset,out,64);
      regs[mmio.issued_access_writeback_count]=1;
      regs[mmio.issued_lane_count_value]=1;
    }
    regs[mmio.completed_bundle_count]=0;
    regs[mmio.has_pending_work]=physical_round == 1;
    regs[mmio.round_exit_reason]=physical_round == 1 ? 1 : 0;
    // Expose the first reports before the final boundary/issued readback. The
    // driver must retain them while performing only a private report drain.
    regs[mmio.lrc_report_drain_required]=physical_round == 1;
    regs[mmio.round_complete]=physical_round != 1;
    cycle=physical_round == 1 ? 15 : physical_round == 2 ? 30 : 40;
    paused=true;
    assert(regs[mmio.lrc_refill_only] == unsigned(physical_round == 2));
  }
};
static GPU::L2Access lrc_member(std::uint64_t uid,std::uint64_t bundle,std::uint64_t at,
                               GPU::LrcBatchAction action,std::uint64_t physical) {
  auto a=access(launch_a,10,uid,bundle,at);
  a.mAddress=0x1000+physical*32; a.mIsWrite=false; a.mWakeRelevantBundle=bundle!=0;
  a.mLrcEnabled=a.mHasLrcService=true; a.mLrcServiceId=physical+1000;
  a.mPhysicalId=physical; a.mLrcLatency=10; a.mSourceReadyCycle=at-1;
  a.mBatchAction=action; a.warpId=uid; return a;
}
void run_lrc() {
  auto server=std::make_unique<GPU::SocketServerTransport>(0); const auto port=server->GetPort();
  auto peer=std::async(std::launch::async,[server=std::move(server)]() mutable {
    server->AcceptClient(); server->RecvMessage<GPU::TrafficGenInitializationMessage>();
    server->SendMessage(GPU::SchedulingRoundStateMessage{});
    auto control=server->RecvMessage<GPU::RoundControlMessage>();
    assert(control.currentCycle==0 && control.endOfLaunches);
    GPU::SchedulerRoundMessage schedule; schedule.min_issue_cycle=50;
    auto a=lrc_member(7,100,10,GPU::LrcBatchAction::Issue,1);
    auto b=lrc_member(8,101,30,GPU::LrcBatchAction::Attach,1);
    a.mContributors={a,b};
    auto c=lrc_member(9,102,20,GPU::LrcBatchAction::Issue,2); c.mContributors={c};
    schedule.allL2TraceSteps={a,c}; schedule.blockedWarpIds.insert(b.GetWarpKey());
    server->SendMessage(schedule);
    const auto first=server->RecvMessage<GPU::TrafficGenResultMessage>();
    const auto &r=first.trafficGenResult;
    assert(!first.hasPendingWork && r.currentCycleAfterIssue==30);
    assert(r.issuedAccesses.size()==3 && r.completedAccesses.size()==3);
    assert(r.physicalAccesses.size()==2 && r.physicalCompletions.size()==2);
    assert(r.physicalAccesses[0].cycleIssued==11 && r.physicalAccesses[1].cycleIssued==21);
    assert(r.physicalCompletions[0].requestUids==std::vector<std::uint64_t>({7,8}));
    assert(r.physicalCompletions[0].cycleCompleted==12);
    assert(r.completedBundleIds==std::vector<std::uint64_t>({100,102,101}));
    const auto future=std::find_if(r.completedAccesses.begin(),r.completedAccesses.end(),[](const auto &p){return p.requestUid==8;});
    assert(future!=r.completedAccesses.end() && future->cycleIssued==30);
    GPU::SchedulingRoundStateMessage status; status.idle=false; status.currentCycle=30; status.acceptedLaunchIds={launch_a};
    server->SendMessage(status); control=server->RecvMessage<GPU::RoundControlMessage>();
    assert(control.reservedSubpartitionsByCycle.empty());
    auto reuse=lrc_member(10,0,40,GPU::LrcBatchAction::Reuse,1); reuse.mContributors={reuse};
    schedule.allL2TraceSteps={reuse}; schedule.min_issue_cycle=40; schedule.blockedWarpIds.clear();
    server->SendMessage(schedule); const auto last=server->RecvMessage<GPU::TrafficGenResultMessage>();
    assert(!last.hasPendingWork && last.trafficGenResult.currentCycleAfterIssue==40);
    assert(last.trafficGenResult.reusedAccesses.size()==1 && last.trafficGenResult.completedAccesses.size()==1);
    assert(last.trafficGenResult.physicalAccesses.empty() && last.trafficGenResult.physicalCompletions.empty());
    status.acceptedLaunchIds.clear(); status.completedLaunchIds={launch_a}; status.idle=true; status.mainLoopComplete=true; status.currentCycle=40;
    server->SendMessage(status);
  });
  LrcTarget target; char pattern[]="/tmp/trafficgen-driver-lrc-XXXXXX"; const auto directory=mkdtemp(pattern); assert(directory);
  trafficgen_t driver(target,target.mmio,0,{"+trafficgen-socket-port="+std::to_string(port),
      "+trafficgen-round-log-dir="+std::string(directory)},0,0,64,128,64,completed_bytes,1,1);
  driver.init(); target.queue(0,launch_a,10,0); target.closed=true;
  for (unsigned ticks=0;ticks<1000 && !target.done && !driver.terminate();++ticks) driver.tick();
  assert(target.done && !driver.terminate() && target.physical_round==3 && target.pressure_drains==1);
  peer.get(); std::filesystem::remove_all(directory);
}

// Two independent append-only issue banks survive socket boundaries. The fifth
// request is outside the first window, forcing a private refill after several
// retained resumes; a later fresh Issue forces a second real replacement.
struct RetainedWindowTarget : MockTarget {
  std::array<unsigned,2> upload_counts{}, issued_counts{};
  std::map<std::uint64_t,std::pair<unsigned,unsigned>> resident;
  std::vector<std::size_t> issued_read_addresses;
  unsigned commits=0, request_beats=0, upload_count_writes=0, lane_count_writes=0;
  RetainedWindowTarget() : MockTarget(256) {}
  uint32_t read(size_t addr) override {
    if (addr==mmio.issued_lane_count_value) return issued_counts.at(regs[mmio.issued_lane_count_index]);
    return MockTarget::read(addr);
  }
  void write(size_t addr,uint32_t value) override {
    if (addr==mmio.upload_count) ++upload_count_writes;
    if (addr==mmio.upload_lane_count_write) {
      ++lane_count_writes;
      upload_counts.at(regs[mmio.upload_lane_count_index])=regs[mmio.upload_lane_count_value];
    }
    if (addr==mmio.commit_upload) {
      ++commits; issued_counts.fill(0); resident.clear();
      for (unsigned lane=0;lane<2;++lane) for (unsigned i=0;i<upload_counts[lane];++i) {
        std::uint64_t uid=0; std::memcpy(&uid,memory.data()+lane*128+i*64,8);
        assert(resident.emplace(uid,std::make_pair(lane,i)).second);
      }
    }
    MockTarget::write(addr,value);
  }
  size_t cpu_managed_axi4_write(size_t addr,const char *data,size_t bytes) override {
    if (addr<issued_offset) request_beats+=bytes/64;
    return MockTarget::cpu_managed_axi4_write(addr,data,bytes);
  }
  size_t cpu_managed_axi4_read(size_t addr,char *data,size_t bytes) override {
    if (addr>=issued_offset && addr<completed_offset) {
      assert(bytes==64); issued_read_addresses.push_back(addr);
    }
    return MockTarget::cpu_managed_axi4_read(addr,data,bytes);
  }
  void issue(std::uint64_t uid,std::uint64_t at) {
    const auto [lane,index]=resident.at(uid);
    assert(index==issued_counts[lane]);
    std::uint64_t request[8]{}; std::memcpy(request,memory.data()+lane*128+index*64,64);
    std::uint64_t result[8]{uid,at,request[1],launch_a<<1,launch_a>>63,0,0,0};
    std::memcpy(memory.data()+issued_offset+lane*128+issued_counts[lane]*64,result,64);
    ++issued_counts[lane];
  }
  void report(unsigned kind,std::uint64_t token,std::uint64_t at) {
    trafficgen_lrc_ledger::descriptor_t r{};
    trafficgen_lrc_ledger::bits(r,0,8,kind); trafficgen_lrc_ledger::bits(r,8,64,token);
    trafficgen_lrc_ledger::bits(r,72,64,at); lrc_reports.push_back(r);
  }
  void returned(std::uint64_t uid,std::uint64_t bundle,std::uint64_t at) {
    report(1,physicals.at({launch_a,uid}).token,at); report(2,bundle,at);
  }
  void run_round() override {
    ++physical_round; reports_round=physical_round; lrc_reports.clear();
    assert(regs[mmio.lrc_enable]==1);
    assert(commits==(physical_round<=4 ? 1U : physical_round==5 ? 2U : 3U));
    assert(regs[mmio.lrc_refill_only]==unsigned(physical_round==5));
    assert(regs[mmio.lrc_drain_only]==unsigned(physical_round==8));
    switch (physical_round) {
      case 1: issue(7,10); issue(9,11); returned(7,101,12); cycle=12; break;
      case 2: returned(9,102,16); report(2,106,16); cycle=16; break;
      case 3: issue(11,20); returned(11,103,21); cycle=21; break;
      case 4: issue(13,30); cycle=30; break;
      case 5: returned(13,104,31); cycle=31; break;
      case 6: issue(15,40); returned(15,105,42); cycle=42; break;
      case 7: issue(17,50); cycle=50; break;
      case 8: returned(17,107,52); cycle=52; break;
      default: assert(false);
    }
    regs[mmio.issued_access_writeback_count]=issued_counts[0]+issued_counts[1];
    regs[mmio.completed_bundle_count]=0;
    regs[mmio.has_pending_work]=physical_round!=8;
    regs[mmio.round_exit_reason]=physical_round==4 ? 1 : 0;
    regs[mmio.round_complete]=1; paused=true;
  }
};
void run_retained_window() {
  auto server=std::make_unique<GPU::SocketServerTransport>(0); const auto port=server->GetPort();
  auto peer=std::async(std::launch::async,[server=std::move(server)]() mutable {
    server->AcceptClient(); server->RecvMessage<GPU::TrafficGenInitializationMessage>();
    server->SendMessage(GPU::SchedulingRoundStateMessage{});
    const std::array<std::uint64_t,7> cycles{12,16,21,31,42,50,52};
    const std::array<std::map<std::uint64_t,std::uint64_t>,7> issued{{
      {{7,10},{9,11}}, {{10,14}}, {{8,18},{11,20}}, {{13,30}}, {{15,40}}, {{17,50}}, {}}};
    const std::array<std::map<std::uint64_t,std::uint64_t>,7> completed{{
      {{7,12}}, {{9,16},{10,16}}, {{8,18},{11,21}}, {{13,31}}, {{15,42}}, {}, {{17,52}}}};
    const std::array<std::vector<std::uint64_t>,7> bundles{{{101},{102,106},{103},{104},{105},{},{107}}};
    const std::array<unsigned,7> physical_issues{2,0,1,1,1,1,0}, physical_returns{1,1,1,1,1,0,1};
    for (unsigned round=0;round<7;++round) {
      const auto control=server->RecvMessage<GPU::RoundControlMessage>();
      assert(control.currentCycle==(round ? cycles[round-1] : 0));
      assert(control.endOfLaunches);
      GPU::SchedulerRoundMessage schedule; schedule.min_issue_cycle=std::numeric_limits<std::uint64_t>::max();
      auto add=[&](std::uint64_t uid,std::uint64_t bundle,std::uint64_t at,GPU::LrcBatchAction action,std::uint64_t physical) {
        auto a=lrc_member(uid,bundle,at,action,physical); a.mContributors={a};
        schedule.allL2TraceSteps.push_back(a);
      };
      if (!round) {
        add(7,101,10,GPU::LrcBatchAction::Issue,1); add(9,102,11,GPU::LrcBatchAction::Issue,2);
        add(11,103,20,GPU::LrcBatchAction::Issue,3); add(13,104,30,GPU::LrcBatchAction::Issue,4);
        add(15,105,40,GPU::LrcBatchAction::Issue,5);
      } else if (round==1) add(10,106,14,GPU::LrcBatchAction::Attach,2);
      else if (round==2) add(8,0,18,GPU::LrcBatchAction::Reuse,1);
      else if (round==4) add(17,107,50,GPU::LrcBatchAction::Issue,6);
      server->SendMessage(schedule);
      const auto message=server->RecvMessage<GPU::TrafficGenResultMessage>(); const auto &r=message.trafficGenResult;
      assert(r.currentCycleAfterIssue==cycles[round] && message.hasPendingWork==(round!=6));
      auto points=[](const auto &entries) {
        std::map<std::uint64_t,std::uint64_t> result;
        for (const auto &p:entries) { assert(p.launchId==launch_a); assert(result.emplace(p.requestUid,p.cycleIssued).second); }
        return result;
      };
      assert(points(r.issuedAccesses)==issued[round] && points(r.completedAccesses)==completed[round]);
      assert(r.completedBundleIds==bundles[round]);
      assert(r.physicalAccesses.size()==physical_issues[round] && r.physicalCompletions.size()==physical_returns[round]);
      if (round==1) assert(r.physicalCompletions.at(0).requestUids==std::vector<std::uint64_t>({9,10}));
      assert(r.reusedAccesses.size()==unsigned(round==2));
      GPU::SchedulingRoundStateMessage status; status.currentCycle=cycles[round]; status.idle=round==6;
      if (!round) status.acceptedLaunchIds={launch_a};
      if (round==6) { status.completedLaunchIds={launch_a}; status.mainLoopComplete=true; }
      server->SendMessage(status);
    }
  });
  RetainedWindowTarget target; char pattern[]="/tmp/trafficgen-driver-retained-XXXXXX";
  const auto directory=mkdtemp(pattern); assert(directory);
  trafficgen_t driver(target,target.mmio,0,{"+trafficgen-socket-port="+std::to_string(port),
      "+trafficgen-round-log-dir="+std::string(directory)},0,0,256,512,256,completed_bytes,1,2);
  driver.init(); target.queue(0,launch_a,10,0); target.closed=true;
  for (unsigned ticks=0;ticks<1000 && !target.done && !driver.terminate();++ticks) driver.tick();
  assert(target.done && !driver.terminate() && target.physical_round==8);
  assert(target.commits==3 && target.upload_count_writes==3 && target.lane_count_writes==6);
  // Four initial requests, the private refill, and the fresh sixth request.
  // The ordinary byte-identical payload cache also saves E's replacement DMA.
  assert(target.request_beats==6);
  assert(target.issued_read_addresses==std::vector<std::size_t>({256,384,320,448,256,384}));
  peer.get(); std::filesystem::remove_all(directory);
}

int main() {
  run(false); run(true); run(false, true);
  run_completion_count(0); run_completion_count(1); run_completion_count(9);
  run_completion_count(4097); run_completion_count(32769);
  run_upload_cache(false); run_upload_cache(true);
  run_legacy_start(true); run_legacy_start(false); run_legacy_start(true, true);
  run_lrc(); run_retained_window();
  std::cout << "TrafficGen driver session tests passed: overlap, duplicate UIDs, store residency, refills, blocked-memory progress, idle reuse, compute-only, close/rejection, truncation, disconnect, completion DMA counts/bounds, upload cache, failed write, LRC future admission/reuse/private refill/report-pressure drain, retained native windows with two-lane suffix reads\n";
}

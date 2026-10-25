# L2Perf bridge contract

This optional bridge samples passive cache event taps in the cache target clock
domain. It does not feed cache control. Each accepted simulation token represents
one target cycle; host servicing time never contributes target cycles.

Cumulative counters are 64 bits and wrap modulo 2^64. START, SNAPSHOT, STOP and
DRAIN_END capture cumulative values **before** the same token's increments.
Therefore a window is `[START, STOP)`: START-cycle events are included and
STOP-cycle events belong to the separate drain interval. Gauge values instead
capture instantaneous pre-edge target state. Gauges must not be subtracted or
presented as accumulated work. Counters never reset at measurement boundaries.

Every marker freezes the entire counter vector in a two-record queue. The host
indexes the frozen head and reads low/high words before explicitly acknowledging
it. A full queue backpressures a token containing a marker. Ordinary tokens may
continue until the next marker; no required marker can be dropped. This changes
host execution speed, not the sequence or timing of target cycles.

The target coordinator must emit START, zero or more SNAPSHOTs, STOP, then
DRAIN_END. It must complete DRAIN_END before a new START. The host checks both
bridge record sequences and target marker sequences. Reset after any marker
emits a RESET record and restarts target marker sequencing at one. Reset during
an active or draining window marks that window invalid. Reset while idle records
an informational epoch boundary. Cumulative evidence and bridge record sequence
remain intact across target reset; target-reset tokens do not accumulate events.

Record error bits 0..15 carry target control errors. Bridge bits are:

- 16: invalid marker/state transition.
- 17: marker does not identify the active window.
- 18: target reset interrupted an active or draining window.
- 19: target marker sequence was not consecutive.

`+l2perf-output0=/path/prefix` selects output files (default `l2perf0`):

- `.csv`: raw per-counter rows for every frozen marker, with actual target cycles.
- `.schema.json`: bank/requester/name/unit and cumulative-versus-gauge descriptors.
- `.summary.json`: atomic live JSON with window, intermediate snapshot, and drain
  intervals. Cumulative deltas use unsigned modulo subtraction; gauges provide
  start/end values. `complete` requires every window's STOP and DRAIN_END and no
  errors. `finished` distinguishes end-of-run from a currently idle collector.

Cycle origin is the first accepted target-clock token after host reset, including
cycles during target reset. HPM and existing FASED samples have different capture
paths and must not be labelled cycle-aligned with these snapshots. Requester
classes aggregate all plugins sharing that backend. Bank stalls overlap and do
not form additive components of total stall time.

`L2PerfAccumulatorSpec` directly tests boundary inclusion, queue pressure,
snapshot preservation, invalid/reset/sequence detection and 64-bit RTL wrap.
`L2PerfDriverTest.cc` tests the real C++ driver against independently supplied
frozen MMIO records, including indexed/split reads, interval classification,
unsigned wrap, gauges, and missing or reset-interrupted windows.

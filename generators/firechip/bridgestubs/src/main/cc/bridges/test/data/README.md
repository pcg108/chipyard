# Kernel 2 stalled-round fixture

`kernel2_round911_pending.csv` is the preserved pending-access snapshot from the
September 15, 2026 kernel 2 metasim run, originally saved at
`analysis/kernel2_round911_20260915/snapshots/pending_at_round911.csv`.
It contains 7,356 accesses pending after round 910. The test removes UID 826226,
the sole request issued in the captured round-911 prefix, giving the frozen
state of 7,355 pending requests and 272 resident bundle entries (17 per lane).

The CSV retains the original lane ordering, scheduled timestamps, generation,
bundle identity, and immutable member counts. The regression models finite
bundle tables with immediate memory completions; RTL tests separately cover
delayed completion, acknowledgement, and output backpressure.

SHA-256 of the unmodified snapshot:
`1c0f788ebeac62de14eb9a9bd54ff06a097ae77abbb86e6d7c269744ac82daf4`

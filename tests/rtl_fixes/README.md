# Focused RTL correctness regressions

This branch pins three private component repositories containing five RTL fixes.
`fixes.json` records the individual commits. Existing application, accelerator,
and unrelated working-tree changes are outside this branch.

Validation is pending. The focused runner will elaborate current Scala sources
and test Rocket trap-PC priority, deferred-interrupt instruction retention,
cache probe/metadata ordering, Saturn page dependencies, and Shuttle rejected
divide/square-root retirement. Generated RTL and bulk logs belong in scratch.

The existing interrupt and memory harnesses are imported from the ILLIXR
regressions. Cache C++ fixtures preserve the legal coherent-manager stimuli;
the main-tree runner will extract modules from fresh elaboration without
editing functional RTL. Negative controls use isolated source checkouts.

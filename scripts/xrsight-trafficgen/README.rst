Rebuilding the working XRSight TrafficGen v7 bitstream
=====================================================

This branch contains the hardware used by the completed U250 build: four Rocket
harts, Saturn on every hart, FP32 and INT8 Gemmini on hart 0, and TrafficGen with
load-request coalescing, TMA, mbarriers, 4,096 metadata entries and 136-bit report
storage (512-bit DMA records). The host clock is 30 MHz, with NORETIMING.

Source pins
-----------

The gitlinks, not dependency branch tips, select the sources. The Rocket,
Saturn and Shuttle correctness fixes were already pinned on this branch.
Gemmini, inclusive-cache and FireSim now point to forks containing their exact
working-build commits::

  generators/rocket-chip                  2c0e4784c46f1a67ef39699c7d67aef65e3ea8c0
  generators/saturn                       9e04c8c6c70a4b4db5989f9a16a89679183a5d1d
  generators/shuttle                      e789aa207148ade9eaed79f12841102597f5a0a5
  generators/gemmini                      8d6d83b7a68f30ab971c8a4e78083f2bda1c9c92
  generators/rocket-chip-inclusive-cache  9242577cfa92e7c8f411ffa724ce55cf87bb9063
  sims/firesim                            282ed1ae3588f323b48f6efe8e9d6c8860bee27f

The final three commits are published on ``xrsight-trafficgen-v7`` in the
corresponding ``pcg108`` repositories. Access to the declared SSH remotes is
required for the first three. Do not use ``git submodule update --remote``;
that replaces the tested pins with moving branch tips.

Fresh checkout and environment
------------------------------

Use Linux x86-64, Git, Conda, CMake, a system C/C++ compiler, and Vivado 2022.1
with support for the Alveo U250. The packaged full Conda lockfile supplies the
Chipyard tool versions. Follow the repository's normal host prerequisites;
Conda does not install Vivado or configure localhost SSH. The FireSim manager
must be able to SSH to localhost noninteractively.

From a directory with sufficient space for sources, toolchains and implementation
outputs::

  git clone --branch trafficgen-target-cycle git@github.com:pcg108/chipyard.git
  cd chipyard
  ./scripts/init-submodules-no-riscv-tools.sh --saturn --gemmini
  ./scripts/build-setup.sh riscv-tools --skip-precompile
  source env.sh
  conda install -y -p "$PWD/.conda-env" -c conda-forge 'boost-cpp=1.85.0=h44aadfe_2'

Boost is an additional driver dependency absent from the base lockfile; the
command above pins the package version and build used by the working driver.

Precompilation is deferred until espresso is built below; the hardware is still
compiled by the elaboration stage. For an existing checkout after pulling this
change, run ``git submodule sync --recursive`` before initializing submodules.

Choose an absolute output directory; it need not be the original build host's
scratch path. The default is ``.xrsight-build`` within the checkout::

  export XRSIGHT_TG_WORK=/absolute/path/to/xrsight-build
  mkdir -p "$XRSIGHT_TG_WORK"
  cmake -S generators/constellation/espresso -B "$XRSIGHT_TG_WORK/espresso-build" -DBUILD_DOC=OFF
  cmake --build "$XRSIGHT_TG_WORK/espresso-build" -j4
  export PATH="$XRSIGHT_TG_WORK/espresso-build:$PATH"
  source /path/to/Vivado/2022.1/settings64.sh

Build and check
---------------

Run from the Chipyard checkout::

  bash scripts/xrsight-trafficgen/build.sh elaborate
  python3 scripts/xrsight-trafficgen/check-elaboration.py --output "$XRSIGHT_TG_WORK/elaboration.json"
  bash scripts/xrsight-trafficgen/build.sh driver
  bash scripts/xrsight-trafficgen/build.sh bitstream

``build.sh`` generates absolute-path FireSim configuration files beneath
``$XRSIGHT_TG_WORK/config`` from the checked-in templates. The templates are
not intended to be passed directly to the FireSim manager. Logs, memory-guard
records and build outputs are also stored under this work directory.

The pinned FireSim source includes the route clock-delay setting used by the
working build (``route.optimizeLeafClkProgDlys=false``), four Vivado workers,
and its U250 platform changes. No local patch or old checkpoint is required
to start synthesis. The guard stops if available RAM falls below 48 GiB, any
worker reaches 64 GiB RSS, or the kernel OOM count increases. A stop leaves
``STOPPED_NO_AUTORESTART.json``; investigate it before explicitly resuming.
No automatic timing recovery is enabled. The completed implementation required
about eleven hours after resuming its optimized checkpoint, with very high
BRAM occupancy. Runtime and timing closure can vary between builds.

Validation and provenance
-------------------------

``build-manifest.json`` records the completed hardware's revisions and RTL /
bitstream hashes. The source hardware commit is
``131e04efc20ddaad6547df24d127fdbdd3a2b46b``; later packaging and fresh-checkout
changes do not modify its hardware. Hashes identify the historical artifacts;
a new Vivado run is not guaranteed to produce an identical bitstream file.

The reference GPU model is ``pcg108/gpu_model`` commit
``7b6abcafe312c21e87393181410eb2f2aea1d938``, socket protocol 7. Compile the model
and FireSim driver against the same Boost serialization version (1.85.0 in the
working build). Protocol version agreement alone does not ensure compatible
Boost archives. On a different host, pass an absolute writable directory via
``+trafficgen-round-log-dir=/absolute/path/to/logs`` to override the historical
driver logging default. Model sources, trace datasets and generated bitstreams are
not embedded in this repository.

Model validation additionally requires system OpenCV development headers and
libraries (including ``/usr/include/opencv4``), zlib development files, and
Python plotting dependencies, as specified by the model repository.

For optional model/C++/RTL validation, clone the model into
``$XRSIGHT_TG_WORK/model-src``, check out the reference commit, and run
``validate-v7.sh model``, ``validate-v7.sh cpp`` and ``validate-v7.sh rtl`` from
this directory, with ``XRSIGHT_TG_WORK`` exported. Build metasim with
``build.sh metasim``. The completed v7 FPGA and fresh metasim both passed
Hopper Gemma kernel 6632 with 868 scheduling rounds and final cycle 198337.

The ``start-v7.py``, ``continue-v7.py``, ``finish-v7.py``, ``retry-*`` and
``finish-recovery.py`` scripts preserve the original host's orchestration and
checkpoint-recovery history. They are not fresh-build entry points. Use the
commands above when building in a new checkout.

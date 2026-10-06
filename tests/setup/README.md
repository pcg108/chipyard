# Conda bootstrap packaging regression

The default bootstrap requires the full Linux RISC-V lockfile. Earlier commit
974da28f4 included only the lean lockfile, while the development checkout also
had an untracked full lockfile. A fresh clone therefore reached `conda-lock
install`, printed generic usage, and exited with code 1.

Both lockfiles are now packaged. Setup checks for the selected lockfile before
creating environments and reports its missing path explicitly. The full
lockfile is the preserved development artifact, SHA-256:

```
b6d601ee36d0c3aa1026833c5dddf53592638b59d06824b1fb89846295da7a7a
```

It pins `riscv-tools=1.0.6` (RISC-V GCC 13.2.0), host GCC 13.2.0,
`conda-lock=2.5.7`, and `sysroot_linux-64=2.34`. This is an archived resolved
package set, not a new solve of the source YAML (which specifies sysroot 2.35).
The existing host-glibc-triggered regeneration behavior and explicit
`--use-unpinned-deps` path are unchanged. A regenerated environment is not the
same frozen package set.

Run the regression without creating Conda environments:

```bash
python3 tests/setup/test_conda_lock.py
```

For the real step-1 integration test, use a new checkout with no `.conda-env` or
`.conda-lock-env`, initialize Conda in the shell, then run:

```bash
./build-setup.sh -s 2 -s 3 -s 4 -s 5 -s 6 -s 7 -s 8 -s 9 -s 10 -s 11
source env.sh
"$PWD/.conda-env/riscv-tools/bin/riscv64-unknown-elf-gcc" -dumpfullversion
```

The 2026-10-06 validation installed the full lockfile into an empty prefix and
compiled an FP64 RVV intrinsic fixture using `-march=rv64gcv_zvl256b`,
`-mabi=lp64d`, and `-mcmodel=medany`. Downloads/package caches were available;
this is a fresh environment installation, not a cache-free network test.
The actual step-1 integration command above also completed successfully from
the isolated checkout, creating both environments and a working `env.sh`; its
compiler passed the same RVV fixture.
It does not validate the later submodule, FireSim, firmware or FPGA build steps.

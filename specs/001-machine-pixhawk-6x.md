# Spec 001 (M1): `pixhawk-6x` machine and toolchain bring-up

- **Status:** Draft
- **Created:** 2026-07-25
- **Depends on:** [000-architecture.md](000-architecture.md)
- **Delivers:** A `pixhawk-6x` MACHINE that can cross-build a bare-metal
  Cortex-M7 binary which runs in Renode's STM32H743 model — validated
  end-to-end **before** any PX4/NuttX code is involved.

## 1. Rationale

Every later milestone stacks on the machine definition, the toolchain,
and the emulator. Bringing them up against oe-core's tiny
`baremetal-helloworld` isolates failures: if M2 (PX4 firmware) breaks,
the machine/toolchain/Renode triad is already known-good. This
milestone is entirely independent of which PX4 version/recipe layout
this layer carries — it only needs oe-core and this layer's presence
on `BBLAYERS`.

This milestone also closes architecture decision §4.1 (toolchain
strategy) with build evidence, and absorbs the M0 audit tasks that
gate it.

## 2. Requirements

- **REQ-1** — A `conf/machine/pixhawk-6x.conf` selecting a Cortex-M7
  tune with the hard-float double-precision FPU (`fpv5-d16`; the
  STM32H753 has a double-precision FPU and upstream PX4 builds fmu-v6x
  with `-mfpu=fpv5-d16`). The tune must come from oe-core's
  `conf/machine/include/arm/` tunes on `wrynose` (this layer's target
  Yocto Project LTS) if one matches; a layer-local tune include is
  acceptable only if oe-core's cortex-m7 tune cannot express fpv5-d16
  (record which in §6).
- **REQ-2** — A multiconfig fragment `conf/multiconfig/pixhawk6x.conf`
  setting `MACHINE = "pixhawk-6x"` and the chosen `TCLIBC`, so firmware
  builds never require the user's primary DISTRO/MACHINE to change.
- **REQ-3** — ~~`bitbake mc:pixhawk6x:baremetal-helloworld` succeeds
  from a clean TMPDIR with `BB_NO_NETWORK = "1"` after fetching.~~
  **Revised: oe-core's `baremetal-helloworld` cannot be made to work
  on a real bare-metal machine, not just a `COMPATIBLE_MACHINE`
  metadata restriction.** Its upstream source
  (`github.com/ahcbb6/baremetal-helloqemu`) only has startup
  code/linker scripts for specific QEMU machine models, selected via
  `BAREMETAL_QEMUARCH:qemu*` — there is no build target and no
  mapping for a real chip, so even overriding `COMPATIBLE_MACHINE`
  produces an unbuildable `BAREMETAL_QEMUARCH=""`. **REQ-3 is now:**
  `bitbake -e mc:pixhawk6x:<any recipe>` resolves cleanly for the real
  `pixhawk-6x` machine (proving the machine/tune/toolchain
  configuration itself is sound), with `TUNE_CCARGS` containing the
  exact flags confirmed correct in §6. Producing a genuinely working
  ELF for `pixhawk-6x` requires real STM32H753 startup code and a
  linker script — that work is subsumed by M2/M3 (PX4 itself is that
  "hello world", and M3 is where it first needs to actually boot), not
  duplicated here with a bespoke trivial recipe.
- **REQ-4** — The resulting ELF runs under Renode using upstream
  `platforms/cpus/stm32h743.repl` plus a layer-provided board overlay,
  and emits its greeting on the emulated UART.
- **REQ-5** — A repeatable invocation for REQ-4 lives in the layer
  (script or Renode `.resc` + robot file under `recipes-renode/` or
  `scripts/`), runnable both interactively and headless (CI).
- **REQ-6** — The machine sets `MACHINE_FEATURES`, serial console
  variable, and flash/RAM layout variables (`STM32H7_FLASH_BASE`,
  sizes) in `conf/machine/include/stm32h7.inc` so M2/M3 reuse them.
- **REQ-7** — Toolchain ADR in spec 000 §4.1 updated from *pending* to
  *decided*, citing the build evidence from this milestone.

## 3. Non-goals

- No PX4, NuttX, or px4io code builds in this milestone.
- No flashing of real hardware (M5).
- No decision on `baremetal-image.bbclass` vs plain `deploy` for the
  *PX4* recipes — evaluate here (helloworld uses baremetal-image by
  default), decide in spec 002.

## 4. Design sketch

### 4.1 Machine — implemented

`conf/machine/pixhawk-6x.conf`, `conf/machine/include/stm32h7.inc`,
`conf/machine/include/arm/armv7m/tune-cortexm7-fpv5d16.inc`, and
`conf/multiconfig/pixhawk6x.conf` now exist for real (not a sketch).
`DEFAULTTUNE = "cortexm7hf-fpv5d16"` — the "hf" is embedded directly
in the tune name because `TUNE_PKGARCH` auto-computes as
`ARMPKGARCH + ARMPKGSFX_EABI("hf") + ARMPKGSFX_FPU("-fpv5d16")`, and
oe-core's sanity checker rejects a `PACKAGE_EXTRA_ARCHS` that doesn't
literally contain that string (hit this as a real error — see §6).
Verified via `bitbake -e mc:pixhawk6x:<recipe with relaxed
COMPATIBLE_MACHINE>` that this produces exactly
`TUNE_CCARGS=" -mcpu=cortex-m7 -march=armv7e-m -mfpu=fpv5-d16
-mfloat-abi=hard"`, matching the flags confirmed from real NuttX
source in REQ-1's evidence.

The remaining open items below were resolved during implementation
(record answers in §6):

- ~~Exact oe-core tune include and `DEFAULTTUNE` name providing
  `armv7em` + `fpv5-d16` hard-float on `wrynose`~~ — **resolved: no
  such tune exists in oe-core.** `conf/machine/include/arm/armv7m/
  tune-cortexm7.inc` defines only the plain `cortexm7` tune (no FPU
  variant at all). `fpv5-d16` (double-precision) has zero definition
  anywhere in oe-core's tune files — `feature-arm-neon.inc` only
  defines the single-precision `vfpv5spd16` (`-mfpu=fpv5-sp-d16`),
  and it's wired only into the ARMv8-M chain
  (`arch-armv8m-main.inc`), which `tune-cortexm7.inc`/
  `arch-armv7em.inc` don't include. **A layer-local tune addition is
  required** (REQ-1's documented fallback condition is met). Confirmed
  separately, from real NuttX source rather than assumption, that PX4
  genuinely needs `fpv5-d16`: `platforms/nuttx/NuttX/nuttx/arch/arm/
  src/armv7-m/Toolchain.defs` sets `-mfpu=fpv5-d16` when
  `CONFIG_ARCH_CORTEXM7=y` and `CONFIG_ARCH_DPFPU=y`, and
  `arch/arm/src/stm32h7/Kconfig` `select`s `ARCH_HAVE_DPFPU` for the
  STM32H7 chip variants fmu-v6x uses. Implementation should add a new
  `TUNE_FEATURES` value (e.g. `fpv5d16`) to a layer-local
  `armv7m/tune-cortexm7.inc` override or a machine-local include,
  following `feature-arm-neon.inc`'s `vfpv5spd16` entry as the
  pattern but with `-mfpu=fpv5-d16` and the hard-float calling
  convention.
- `TCLIBC`: `"newlib"` vs `"baremetal"` for the helloworld gate. This
  choice is *for M1 only*; PX4/NuttX's libc story is settled in M2
  (NuttX provides its own libc — the toolchain runtime mainly
  contributes libgcc/libm).

### 4.2 Renode harness

- `renode/pixhawk6x.repl`: `using "platforms/cpus/stm32h743.repl"` plus
  board deltas (memory aliases as needed by the helloworld link
  address, UART wiring).
- `renode/pixhawk6x-helloworld.resc`: creates the machine, loads the
  ELF from `DEPLOY_DIR_IMAGE`, starts emulation.
- Robot test asserting the greeting string on the UART analyzer;
  invoked headless (`renode-test`) in CI.
- Renode version floor: the release documented in NuttX's Renode guide
  notes for STM32H7 (record the tested version in §6; Renode is a host
  prerequisite per spec 000 §4.6).

### 4.3 M0 audit tasks absorbed here

Run once, outside bitbake, from a PX4-Autopilot checkout at
`release/1.17` (SRCREV `d6f12ad1c4f70ad3230afd7d86e971421e02fef4` —
matching this layer's `px4-autopilot_1.17.0.bb`) and attach results to
this spec's PR:

- `make px4_fmu-v6x_default` under a network monitor: list every fetch
  that escapes the source tree (candidates: none expected beyond
  submodules if `GIT_SUBMODULES_ARE_EVIL` holds — verify).
- Submodule delta: submodules the fmu-v6x config needs that
  `px4_sitl_default` does not (known: NuttX kernel + apps; confirm
  tflite_micro, mip_sdk, sbgECom, gps/devices usage on v1.17.0
  specifically — pins differ from other PX4 versions).
- Host tool delta versus `px4-autopilot.inc`'s existing DEPENDS list
  (already large and mostly self-sufficient per spec 000 §4.3 — the
  open question is what NuttX/fmu-v6x adds on top, not basic gaps).
- Confirm nested-build inventory: px4io config name
  (`px4_io-v2_default`), bootloader config (`px4_fmu-v6x_bootloader`),
  ROMFS embedding mechanism and the CMake switches that disable the
  nested builds.
- NSH console UART for fmu-v6x from
  `boards/px4/fmu-v6x/nuttx-config/*/defconfig`
  (`CONFIG_*_SERIAL_CONSOLE`) — feeds REQ-6 and M3.

## 5. Acceptance criteria

- **AC-1** — **Done, per REQ-3's revision.** On `wrynose`:
  `bitbake -e mc:pixhawk6x:<recipe>` resolves cleanly for the real
  `pixhawk-6x` machine with the correct `TUNE_CCARGS`/`TUNE_PKGARCH`
  (verified in §6). The offline (`BB_NO_NETWORK=1`) full-build
  criterion moves to M2/M3 where a real buildable target
  (`px4-firmware`) exists for this machine.
- **AC-2** — `renode-test` run of the harness passes: UART analyzer
  sees a real boot greeting within 10 virtual seconds (REQ-4/5) — this
  now naturally becomes M3's PX4-boots-in-Renode gate rather than a
  separate trivial-recipe test, since REQ-3's revision established
  there is no meaningful trivial target for real `pixhawk-6x`
  hardware to boot before then.
- **AC-3** — CI job (or documented equivalent invocation) covering
  AC-1 + AC-2 exists and is green.
- **AC-4** — Spec 000 §4.1 marked decided; §6 below filled in; M0
  audit artifacts attached (REQ-7, §4.3).
- **AC-5** — Existing posix builds are provably unaffected:
  `bitbake px4-autopilot` (the `1.17.0` recipe) output unchanged
  (buildhistory or sstate equivalence) with the new machine/multiconfig
  files present.

## 6. Implementation record (fill during/after implementation)

**Interim validation done 2026-07-25** (against `machine/qemuarm`, an
oe-core stock machine — *not* yet `pixhawk-6x`/Cortex-M7, see caveat
below): `TCLIBC = "baremetal"` + `bitbake baremetal-helloworld` in a
bitbake-setup `sitl`/`pixhawk6x`-style environment (oe-core + this
layer) completed all 2023 tasks with zero errors (3 WARNINGs, all
transient upstream-mirror `do_fetch` fallbacks, not `BB_NO_NETWORK=1`
clean — REQ-3's offline requirement is not yet verified strictly).
The resulting `baremetal-helloworld-image-qemuarm.elf` (ARM EABI5,
statically linked, built against the `cortexa15t2hf-neon-oe-eabi`
tune qemuarm defaults to) was run directly with `qemu-system-arm -M
virt,highmem=off -cpu cortex-a15 -kernel ...elf` and printed `Hello
OpenEmbedded on ARM!` — a genuine, executed confirmation of the
baremetal toolchain path, not just a green build log.

**What this does and doesn't confirm:** it validates that
`TCLIBC=baremetal` + this layer coexisting on `BBLAYERS` + oe-core's
baremetal image class all work together end-to-end on `wrynose`. It
does **not** yet validate REQ-1 (no `pixhawk-6x` MACHINE or
`fpv5-d16`/Cortex-M7 tune exists yet), REQ-4/AC-2 (ran directly under
`qemu-system-arm`, not Renode/`stm32h743.repl`), or REQ-3's strict
`BB_NO_NETWORK=1` offline requirement.

| Item | Decision / evidence |
|---|---|
| oe-core tune include + DEFAULTTUNE | **Implemented.** No existing oe-core tune covers Cortex-M7 + fpv5-d16, so `conf/machine/include/arm/armv7m/tune-cortexm7-fpv5d16.inc` adds a layer-local `cortexm7hf-fpv5d16` tune (name embeds "hf" because `TUNE_PKGARCH` auto-computes it that way — first attempt named it `cortexm7-fpv5d16` and oe-core's sanity checker rejected it: "PACKAGE_ARCHS ... does not contain TUNE_PKGARCH (cortexm7hf-fpv5d16)"). `DEFAULTTUNE = "cortexm7hf-fpv5d16"` in `conf/machine/pixhawk-6x.conf`. |
| fpv5-d16 availability on wrynose | **Not available anywhere in oe-core's tune files** — confirmed and worked around (see row above). `bitbake -e` confirms `TUNE_CCARGS=" -mcpu=cortex-m7 -march=armv7e-m -mfpu=fpv5-d16 -mfloat-abi=hard"` — the exact flags NuttX's own `Toolchain.defs` uses for `CONFIG_ARCH_CORTEXM7=y`+`CONFIG_ARCH_DPFPU=y`. |
| TCLIBC for M1 | **`"baremetal"` confirmed working** for oe-core's `baremetal-helloworld` on `wrynose` against the *interim* `qemuarm` machine (see interim validation above). Set in `conf/multiconfig/pixhawk6x.conf` for the real machine too, but REQ-3's revision means no real recipe has exercised it against `pixhawk-6x` yet — that's M2's `px4-firmware`. |
| Renode version tested | _tbd — not yet attempted; blocked on M2 producing a real bootable ELF, since REQ-3's revision established `baremetal-helloworld` can't provide one for real hardware_ |
| Toolchain ADR outcome (000 §4.1) | **Decided and implemented: Option B.** `recipes-devtools/external-arm-toolchain/gcc-arm-none-eabi_15.3.rel1.bb` vendors the prebuilt-toolchain recipe pattern from `git.yoctoproject.org/meta-arm` (MIT-licensed, copyright retained), adapted for this layer's oe-core (its SPDX-style `LICENSE` syntax isn't supported here — rewritten using an existing common-license token). Verified for real: `bitbake gcc-arm-none-eabi-native` completes, and the staged `arm-none-eabi-gcc` successfully compiles a `.c` file with `pixhawk-6x`'s exact resolved flags (`-mcpu=cortex-m7 -march=armv7e-m -mfpu=fpv5-d16 -mfloat-abi=hard`), producing a valid ARM EABI5 object. |
| M0 audit artifact links | Folded into specs 000 §4.3 and 002 §2 (fmu-v6x board audit against the real v1.17.0 tree) rather than a separate artifact file. |

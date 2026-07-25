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
- **REQ-3** — `bitbake mc:pixhawk6x:baremetal-helloworld` succeeds from
  a clean TMPDIR with `BB_NO_NETWORK = "1"` after fetching.
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

### 4.1 Machine

```
# conf/machine/pixhawk-6x.conf (sketch — validate tune names in impl)
#@TYPE: Machine
#@NAME: Holybro Pixhawk 6X FMU (STM32H753, Cortex-M7)

require conf/machine/include/stm32h7.inc

# oe-core cortex-m7 tune; confirm fpv5-d16 (DP) vs fpv5-sp-d16 (SP).
require conf/machine/include/arm/armv7m/tune-cortexm7.inc
DEFAULTTUNE = "cortexm7hfd"        # placeholder — see REQ-1 evidence

SERIAL_CONSOLES = "57600;ttyS0"    # placeholder — Renode UART mapping
```

Open items to resolve during implementation (record answers in §6):

- Exact oe-core tune include and `DEFAULTTUNE` name providing
  `armv7em` + `fpv5-d16` hard-float on `wrynose` (this layer's target
  Yocto Project LTS release).
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

- **AC-1** — On `wrynose`:
  `bitbake mc:pixhawk6x:baremetal-helloworld` from clean TMPDIR
  succeeds offline (REQ-3).
- **AC-2** — `renode-test` run of the harness passes: UART analyzer
  sees the helloworld greeting within 10 virtual seconds (REQ-4/5).
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
| oe-core tune include + DEFAULTTUNE | _tbd — qemuarm's default (`cortexa15t2hf-neon-oe-eabi`) confirmed working for the baremetal-image class in general; the actual `fpv5-d16` Cortex-M7 tune is still unresearched_ |
| fpv5-d16 availability on wrynose | _tbd_ |
| TCLIBC for M1 | **`"baremetal"` confirmed working** for oe-core's `baremetal-helloworld` on `wrynose` (see interim validation above) |
| Renode version tested | _tbd — not yet attempted; interim validation used `qemu-system-arm` directly instead_ |
| Toolchain ADR outcome (000 §4.1) | _tbd_ |
| M0 audit artifact links | _tbd_ |

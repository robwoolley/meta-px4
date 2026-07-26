# Spec 000: NuttX/STM32H7 firmware support in meta-px4

- **Status:** Draft (rewritten 2026-07-25 against the actual
  `robwoolley/meta-px4` repository after an earlier draft was
  mistakenly written against an unrelated, less complete fork)
- **Created:** 2026-07-25
- **Scope:** Architecture and milestone roadmap for cross-building PX4 as
  NuttX firmware for the Holybro Pixhawk 6X FMU (STM32H753) from this
  layer, with Renode as the emulation target.

## 1. Background

meta-px4 currently builds PX4 **v1.17.0** (`release/1.17`,
`recipes-px4/px4-autopilot/px4-autopilot_1.17.0.bb`) for the **posix**
platform only, with `CONFIG` hardcoded to `px4_sitl_default` in the
recipe's `EXTRA_OECMAKE` (there is no `PX4_CONFIG` override variable in
the current recipe, despite older documentation suggesting one). The
layer carries its own recipes in `recipes-devtools/` for every
python/native tool that oe-core and meta-openembedded/meta-python don't
provide (`empy`, `cerberus` — a newer version than meta-python's,
`lark-parser`, `pyros-genmsg`, `pymavlink`, `pyulog`, `nunavut`),
several explicitly ported from meta-ros since meta-ros does not itself
expose them as buildable native recipes. This was verified empirically
during M0 (§9): `bitbake -e px4-autopilot` resolves with zero errors
against nothing more than `openembedded-core/meta` +
`meta-openembedded/meta-oe` + `meta-openembedded/meta-python` +
`meta-px4` — no meta-ros layer needed at all.

The documented quick-start path builds `px4-autopilot` via **kas**,
layered on ELISA's Space Grade Linux (`meta-sgl`,
`kas/px4-sgl-qemuarm64.yml`); that pulls in whatever wider
meta-openembedded coverage SGL itself depends on. Our M0 validation
shows a lighter bitbake-setup-driven path (oe-core + meta-openembedded
+ meta-px4, no SGL) also fully resolves and is the more natural
substrate for adding a bare, non-Linux MACHINE — SGL is a Linux
distribution and has no bearing on a bare-metal Cortex-M7 target.

The README's "Known limitations" section already lists NuttX configs
as out of scope ("they spawn nested full PX4 builds ... that this
layer does not handle"). This spec removes that limitation in stages.
The end state:

- A `pixhawk-6x` MACHINE that cross-builds `px4_fmu-v6x_default` as a
  bare-metal firmware artifact, offline, from pinned sources.
- The firmware boots in Renode's STM32H743 model and on real hardware.
- A simulation path (PX4 SIH running inside Renode) usable in CI.
- One bitbake invocation (multiconfig) can produce both the existing
  posix/SITL build and the FMU firmware.

## 2. Goals

- **G1** — Reproducible, offline firmware builds: no network access at
  do_configure/do_compile; all submodules (including NuttX kernel and
  apps) pinned in `SRC_URI`, following this layer's existing
  conventions (`gitsm://` + `GIT_SUBMODULES_ARE_EVIL`, per the posix
  recipe's pattern).
- **G2** — Firmware artifacts (`.elf`, `.bin`, `.px4`) published via
  `DEPLOY_DIR_IMAGE`, flashable on a Pixhawk 6X with `px_uploader.py`.
- **G3** — Emulated boot in Renode gated in CI (boot to NSH, uORB up).
- **G4** — Automated simulated flight (SIH-in-Renode + MAVSDK) in CI.
- **G5** — SBOM/license manifests cover the firmware like any other
  recipe output.

## 3. Non-goals

- Modeling the Pixhawk 6X sensor suite (SPI IMUs, barometers, SDMMC,
  px4io serial protocol) in Renode. Simulation uses SIH, which needs
  only CPU, memory, timers, and UART fidelity.
- Supporting NuttX boards other than fmu-v6x (structure for it, don't
  implement it).
- Packaging ground-control GUI software (see §10 Future work).
- Secure boot / signed firmware (`*_secureboot.px4board` configs).
- Fixing pre-existing staleness unrelated to this effort (e.g. the
  `PX4_CONFIG` override the README describes but the recipe doesn't
  implement, or the `kas/px4-sgl-qemuarm64.yml` file's leftover
  `meta-aerospace` repo key/comment from before the layer's rename) —
  flagged here for awareness, fixed separately if the maintainer wants.

## 4. High-level architecture

```
meta-px4/
├── conf/machine/
│   ├── pixhawk-6x.conf              # STM32H753 FMU (Cortex-M7, fpv5-d16)
│   └── include/stm32h7.inc          # shared H7 settings, flash/RAM layout
├── conf/multiconfig/
│   ├── sitl.conf                    # existing posix world (unchanged)
│   └── pixhawk6x.conf               # MACHINE=pixhawk-6x, baremetal TCLIBC
├── recipes-px4/
│   ├── px4-autopilot/                       # existing posix recipe (unchanged)
│   ├── px4-firmware/px4-firmware_1.17.0.bb  # -DCONFIG=px4_fmu-v6x_default
│   ├── px4-io-firmware/                     # px4_io-v2_default (Cortex-M3)
│   └── px4-bootloader/                      # px4_fmu-v6x_bootloader
├── recipes-devtools/                # existing native codegen tools (unchanged)
├── recipes-renode/                  # board .repl overlays, robot tests
└── specs/                           # this directory
```

Following this layer's existing convention (one recipe file per PX4
release, e.g. `px4-autopilot_1.17.0.bb`), the NuttX firmware gets its
**own recipe** (`px4-firmware`) rather than a `PX4_CONFIG` override of
`px4-autopilot` — consistent with how the posix recipe hardcodes its
`CONFIG` today, and cleaner given the NuttX build needs a materially
different toolchain, DEPENDS, and install step (deploy, not package).

Key decisions (each gets its own ADR section or spec before
implementation):

### 4.1 Toolchain strategy (DECISION PENDING — resolved by M0/M1)

PX4's NuttX platform drives compilation from its own
`Toolchain-arm-none-eabi.cmake` plus NuttX Kconfig, and expects
`arm-none-eabi-*` binaries on PATH.

- **Option A — OE-built newlib cross toolchain** (`TCLIBC = "newlib"`),
  with a naming/flag shim so PX4 and NuttX find it. Full OE control of
  flags, pure-OE SBOM. Higher patching cost; NuttX's build system is a
  second consumer of the toolchain beyond PX4's cmake.
- **Option B — `arm-none-eabi` toolchain packaged as a cross recipe**,
  PX4's own toolchain file drives flags; OE provides environment,
  pinning, and artifact handling only. Fast bring-up; OE `TUNE_CCARGS`
  become advisory for this recipe.

Plan of record: **B first (M2), A as a hardening follow-up** — the
firmware binary must be validated on hardware either way, and B
unblocks every downstream milestone.

### 4.2 Nested builds become multiconfig dependencies

`px4_fmu-v6x_default` normally builds the px4io firmware (STM32F103,
Cortex-M3 — a different tune) as a nested PX4 build and embeds it in
the ROMFS. In this layer:

- `px4-io-firmware` builds `px4_io-v2_default` in its own (multiconfig
  or same-machine, see spec 002) context and deploys
  `px4_io-v2_default.bin`.
- `px4-firmware` consumes it via a `mc:`/deploy dependency and passes
  it to the ROMFS generator, with the nested build disabled.
- Same pattern for `px4-bootloader` (`px4_fmu-v6x_bootloader` config).

### 4.3 Native/python DEPENDS: confirmed by a full build, not just resolution

Unlike an earlier (mistaken) draft of this spec, M0 confirmed the
posix recipe's full native DEPENDS list — including the packages this
layer carries itself (`empy`, `cerberus`, `pyros-genmsg`, `pymavlink`,
`pyulog`, `nunavut`, `lark-parser`) plus everything from oe-core and
meta-openembedded/meta-python (`numpy`, `matplotlib`, `sympy`, `lxml`,
`pycryptodome`, etc.) — resolves *and a full `bitbake px4-autopilot`
build completes*, producing a working `px4` binary (verified by
running it directly against its own recipe-sysroot and getting
correct `--help` output). This took three real fixes surfaced by
actually running the build, not just checking `bitbake -e`:

1. `python3-empy.inc` set `S` relative to `${WORKDIR}` instead of
   `${UNPACKDIR}`, a bitbake 2.18/wrynose compatibility break (same
   class of issue already fixed elsewhere in this layer for
   git-fetched recipes; this one fetches a tarball with a custom
   `SRCNAME` so the line had to be repointed, not dropped).
2. `pymavlink`'s upstream `setup.py` declares
   `setup_requires=['future']`, which setuptools' legacy
   `fetch_build_eggs` mechanism tries to satisfy via `pip wheel` from
   PyPI at build time regardless of what's already staged — failing
   outright under network isolation. Patched it out and added the
   already-available `python3-future-native` to `DEPENDS` so nothing
   is actually lost.
3. `px4-autopilot_1.17.0.bb`'s `EXTRA_OECMAKE` never set
   `UXRCE_DDS_CLIENT_USE_SYSTEM_LIBS=ON`, despite the option (and the
   patch that adds it) existing specifically so PX4 links this
   layer's own `microxrceddsclient`/`microcdr` recipes instead of
   running its own network-fetching nested build. The flag was simply
   missing from the recipe.

NuttX/fmu-v6x may still pull in additional PX4 submodules or host
tools the posix config doesn't need (tensorflow_lite_micro, mip_sdk,
sbgECom drivers, etc.), and may hit its own version of fix #3 above if
any nested-build CMake options aren't wired the same way for the
NuttX config; auditing that delta is M0's remaining job (§9).

### 4.4 Firmware is deploy-only

Firmware recipes inherit `deploy` (evaluate `baremetal-image.bbclass`
in M1); nothing installs into a rootfs on the firmware side. The
*companion* image (posix multiconfig) may package the `.px4` artifact
plus `px_uploader.py` so a companion computer can flash the FMU —
that packaging lives on the Linux side.

### 4.5 Version coupling

The README's version-coupling table (for `microxrceddsclient`,
`microcdr`, `cyclonedds-px4-native` against PX4's submodule pins) gains
rows for NuttX kernel (`platforms/nuttx/NuttX/nuttx`) and NuttX apps
(`platforms/nuttx/NuttX/apps`), and should note that `nunavut`,
`lark-parser`, `pymavlink`, and `pyulog` are also PX4-submodule-adjacent
and may need version bumps alongside a PX4 SRCREV bump, same as the
python native tools already are. M0 produces the definitive pin list
for v1.17.0 / `release/1.17`.

### 4.6 Renode as the emulation target

Renode ships `platforms/cpus/stm32h743.repl` (H753 is the same die plus
crypto). A board overlay `.repl` in `recipes-renode/` adds Pixhawk 6X
specifics: flash aliasing/layout, the NSH console UART, and a MAVLink
UART exposed as a socket for host-side tools. Known upstream gaps
(simplified PWR/RCC; NuttX's documented
`CONFIG_STM32H7_PWR_IGNORE_ACTVOSRDY` interaction) are handled in the
NuttX board config, not by forking Renode. Renode itself is a **host
prerequisite**, not an OE recipe, until CI needs pinning (revisit in
M6).

## 5. Milestones

Each milestone is implemented only after its spec (requirements +
machine-checkable acceptance criteria) is merged.

| # | Milestone | Spec | Acceptance gate |
|---|-----------|------|-----------------|
| M0 | Build-input audit of `px4_fmu-v6x_default` + toolchain ADR | folded into 001 + this doc | Inventory of network fetches, submodules, host tools, nested builds; §4.1 decided |
| M1 | `pixhawk-6x` machine + toolchain bring-up | [001](001-machine-pixhawk-6x.md) | `baremetal-helloworld` output on emulated UART in Renode, in CI |
| M2 | Offline `px4-firmware` / `px4-io-firmware` / `px4-bootloader` recipes | [002](002-px4-firmware.md) | `.px4`/`.elf` deployed; builds with `BB_NO_NETWORK=1`; two builds byte-comparable |
| M3 | PX4 boots in Renode | [003](003-renode-boot.md) | Robot test: boot ELF → `nsh>` → `uorb status`, `ver all` pass |
| M4 | SIH-in-Renode simulation + MAVLink bridge (mavlink-router recipe on the companion side) | 004 (TBD) | Scripted MAVSDK arm→takeoff→land against Renode in CI |
| M5 | Hardware validation on Pixhawk 6X | 005 (TBD) | Boots from OE-built `.px4` via `px_uploader.py`; all sensor drivers probe |
| M6 | Productization: single-invocation multiconfig build, eSDK, `yocto-check-layer`, CI pipeline, docs | 006 (TBD) | CI runs M1/M3/M4 gates per commit |

## 6. Risks

- **R1 — Toolchain impedance mismatch** (highest): NuttX + PX4 both
  make assumptions about the compiler. Mitigated by Option B first and
  the M1 hello-world gate before PX4 enters the picture.
- **R2 — Renode H7 fidelity**: PWR/RCC are simplified; timer fidelity
  under virtual time may affect SIH (M4 carries the research risk, not
  M3). Fallback: SIH on real hardware, Renode kept for boot/CI only.
- **R3 — PX4 SRCREV bumps**: every bump now re-syncs NuttX pins too,
  plus the layer's own carried tool recipes (nunavut, pymavlink,
  pyulog, lark-parser) per §4.5.
- **R4 — ROMFS/nested-build divergence**: PX4 upstream may change how
  px4io/bootloader artifacts are embedded. Isolated in `px4-firmware`'s
  patches; audit on every bump.

## 7. Compatibility

- Layer series: this layer currently targets `wrynose` only
  (`LAYERSERIES_COMPAT_meta-px4 = "wrynose"`); a separate `scarthgap`
  branch exists upstream with minor recipe differences. This work
  targets `wrynose`/`master`.
- Existing posix users are unaffected: all NuttX support is additive
  (new machine, new recipes, new multiconfig).

## 8. Development environment

A `bitbake-setup` JSON configuration (`oe-px4/meta-px4-wrynose.conf.json`
in the sibling `oe-px4` workspace) materializes two profiles:

- **`sitl`** — `openembedded-core/meta` + `meta-openembedded/meta-oe` +
  `meta-openembedded/meta-python` + `meta-px4` (this layer, symlinked
  as a local source). Verified via `bitbake -e px4-autopilot`: zero
  unresolved DEPENDS.
- **`pixhawk6x`** — `openembedded-core/meta` + `meta-px4` only, with
  QEMU ARM machines (`qemuarm`/`qemuarm64`) as interim stand-ins until
  `conf/machine/pixhawk-6x.conf` lands in M1.

## 9. M0 audit tasks (absorbed into spec 001)

Run once, outside bitbake, from a PX4-Autopilot checkout at
`release/1.17` (SRCREV `d6f12ad1c4f70ad3230afd7d86e971421e02fef4`),
mirroring the M0 validation already done for the posix recipe:

- `make px4_fmu-v6x_default` under a network monitor: list every fetch
  that escapes the source tree.
- Submodule delta: submodules the fmu-v6x config needs that
  `px4_sitl_default` does not (known candidates: NuttX kernel + apps;
  confirm tflite_micro, mip_sdk, sbgECom, gps/devices usage on v1.17.0
  specifically, since submodule pins differ from the v1.18.0-beta1
  pins an earlier draft of this spec incorrectly used).
- Host tool delta versus `px4-autopilot.inc`'s existing (now much
  larger and mostly self-sufficient) DEPENDS list.
- Confirm nested-build inventory: px4io config name
  (`px4_io-v2_default`), bootloader config (`px4_fmu-v6x_bootloader`),
  ROMFS embedding mechanism and the CMake switches that disable the
  nested builds.
- NSH console UART for fmu-v6x from
  `boards/px4/fmu-v6x/nuttx-config/*/defconfig`
  (`CONFIG_*_SERIAL_CONSOLE`) — feeds M1's REQ-6 and M3.

## 10. Future work (explicitly out of scope for M0–M6)

- **QGroundControl packaging.** QGC remains a host/workstation tool for
  all milestones above (it connects over UDP/TCP via mavlink-router or
  a Renode socket bridge). Packaging QGC in OE only makes sense if a
  dedicated **ground-station device image** (kiosk/tablet, Herelink-style)
  enters the roadmap: it would require meta-qt6, GStreamer, and a
  substantial recipe with no upstream Yocto support to lean on. If
  pursued, it becomes its own machine + spec series, independent of the
  firmware milestones.
- Additional NuttX boards (fmu-v6c, io-v2 variants beyond what M2 needs).
- Secure boot configs (`bootloader_secureboot`, `secureboot`).
- UAVCAN/DroneCAN peripheral firmware in ROMFS.
- Toolchain Option A migration (OE-built newlib cross toolchain) if
  Option B ships first.
- Packaging Renode itself (or pinning via container) for hermetic CI.
- Fixing the pre-existing staleness noted in §3's non-goals
  (`PX4_CONFIG` doc/recipe mismatch, `kas/px4-sgl-qemuarm64.yml`'s
  leftover `meta-aerospace` naming).

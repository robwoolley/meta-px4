# Spec 002 (M2): Offline PX4 NuttX firmware recipes

- **Status: Done.** All of REQ-1 through REQ-8 and AC-1 through AC-6
  verified with real builds. `px4-firmware`, `px4-bootloader`, and
  `px4-io-firmware` all build and deploy `.elf`/`.px4` (bootloader
  also `.bin`) to `DEPLOY_DIR_IMAGE`, build fully offline under
  `BB_NO_NETWORK=1`, and produce byte-identical artifacts across
  independent clean rebuilds (§7). M3 (Renode boot) is next.
- **Created:** 2026-07-26
- **Depends on:** [000-architecture.md](000-architecture.md),
  [001-machine-pixhawk-6x.md](001-machine-pixhawk-6x.md) (M1 must have
  landed: `pixhawk-6x` MACHINE + toolchain bring-up validated).
- **Delivers:** `px4-firmware`, `px4-io-firmware`, and
  `px4-bootloader` recipes that cross-build `px4_fmu-v6x_default`
  (and its px4io/bootloader companions) fully offline, producing
  `.elf`/`.px4` deploy artifacts.

## 1. Rationale

M1 proves the machine/toolchain/Renode triad against a trivial
helloworld. This milestone puts real PX4/NuttX code through that same
pipeline for the first time. Everything here is grounded in the
actual `release/1.17` source tree (SRCREV
`d6f12ad1c4f70ad3230afd7d86e971421e02fef4`, the same one
`px4-autopilot_1.17.0.bb` already pins) — inspected directly rather
than assumed, per §2 below.

## 2. M0 findings (fmu-v6x board audit, done against the real tree)

Checked directly in
`boards/px4/fmu-v6x/{default,bootloader}.px4board`,
`boards/px4/fmu-v6x/nuttx-config/`, and `boards/px4/io-v2/default.px4board`:

- **Toolchain/tune**: `CONFIG_BOARD_TOOLCHAIN="arm-none-eabi"`,
  `CONFIG_BOARD_ARCHITECTURE="cortex-m7"` — confirms spec 001 REQ-1's
  assumption directly from PX4's own board config, not inference.
- **px4io is required**: `default.px4board` sets
  `CONFIG_DRIVERS_PX4IO=y` (the FMU-side driver that talks to the IO
  coprocessor). `boards/px4/io-v2/default.px4board` sets
  `CONFIG_BOARD_ARCHITECTURE="cortex-m3"`,
  `CONFIG_MODULES_PX4IOFIRMWARE=y`, `CONFIG_BOARD_CONSTRAINED_FLASH=y`
  — confirms spec 000 §4.2's nested-build assumption: `px4_io-v2_default`
  is a real, separate, Cortex-M3 NuttX board build.
- **Bootloader is a separate NuttX profile**: fmu-v6x's
  `nuttx-config/` has sibling `nsh/` and `bootloader/` subdirectories
  (plus `include/`, `scripts/`, a shared `Kconfig`) — `px4_fmu-v6x_bootloader`
  is a real, distinct build target, not synthesized.
- **Console UART**: `nuttx-config/nsh/defconfig` sets
  `CONFIG_USART3_SERIAL_CONSOLE=y` — feeds spec 001 REQ-6 and M3's
  Renode UART wiring.
- **UXRCE-DDS is enabled**: `default.px4board` sets
  `CONFIG_MODULES_UXRCE_DDS_CLIENT=y`. Given M0's posix-build
  regression check (spec 000 §4.3, fix #3) found
  `px4-autopilot_1.17.0.bb` was *missing*
  `-DUXRCE_DDS_CLIENT_USE_SYSTEM_LIBS=ON` despite needing it, **the
  `px4-firmware` recipe must not repeat that mistake**: verify this
  flag (or its NuttX-config equivalent) is actually wired before
  declaring the recipe done, don't assume the posix fix's lesson
  transfers automatically.
- **`CONFIG_LIB_CDRSTREAM` is OFF for fmu-v6x — resolved by an actual
  Kconfig evaluation, not a grep.** Downloaded the ARM GNU Toolchain
  15.2.Rel1 (user-space tarball, no root needed) and used the
  already-built `python3-kconfiglib-native` module from the `sitl`
  bitbake environment to run kconfiglib's own `defconfig.py` directly
  against `Kconfig` with the same environment variables
  `cmake/kconfig.cmake` sets for a `LABEL=default` build
  (`PLATFORM=nuttx VENDOR=px4 MODEL=fmu-v6x LABEL=default
  TOOLCHAIN=arm-none-eabi ARCHITECTURE=cortex-m7`). Sanity-checked the
  reconstruction against known-true values before trusting it: the
  resolved output correctly reproduced `CONFIG_MODULES_UXRCE_DDS_CLIENT=y`
  and echoed `CONFIG_BOARD_TOOLCHAIN`/`CONFIG_BOARD_ARCHITECTURE`
  correctly. `LIB_CDRSTREAM` (`src/lib/cdrstream/Kconfig`) has no
  prompt — it's `select`-only — and grepping every Kconfig file in
  the tree, the *only* symbol that ever selects it is
  `MODULES_ZENOH` (`src/modules/zenoh/Kconfig`), not
  `UXRCE_DDS_CLIENT`. The resolved fmu-v6x config has
  `# CONFIG_MODULES_ZENOH is not set`, so `LIB_CDRSTREAM` resolves to
  `n`. **`PX4_BUILD_IDLC=OFF` is therefore not needed for
  `px4-firmware`**, same conclusion as the posix build — REQ-3 is
  satisfied on this point without needing that flag; patch 0003 may
  still need to be carried (it only guards a code path CDRSTREAM
  never reaches here) but doesn't need activating via
  `EXTRA_OECMAKE`.
- **Submodule pins at this SRCREV** (`git submodule status` in the
  real tree): NuttX apps
  `e37940d8535f603a16b8f6f21c21edaf584218aa` (nuttx-11.0.0-5-g...),
  NuttX kernel `fb2fadf6f599c1406f052db013efd00a2518e72c`
  (nuttx-8.2-10680-g...). `tensorflow_lite_micro`, `mip_sdk`,
  `sbgECom`, and `src/drivers/gps/devices` are present as submodules
  repo-wide regardless of board; whether fmu-v6x's CMake actually
  compiles them depends on which `CONFIG_DRIVERS_INS_*`/GPS options
  `default.px4board` enables — not yet audited line-by-line here
  (do so during implementation, not by re-grepping this spec later).

## 3. Requirements

- **REQ-1** — `px4-firmware_1.17.0.bb` builds `-DCONFIG=px4_fmu-v6x_default`
  against the same PX4 SRCREV as `px4-autopilot_1.17.0.bb`
  (`d6f12ad1c4f70ad3230afd7d86e971421e02fef4`), following this layer's
  one-recipe-per-PX4-version convention (§4 of spec 000).
- **REQ-2** — No network access during `do_configure`/`do_compile`:
  all submodules fetched via `gitsm://` + pinned `SRCREV`, same as the
  posix recipe. `GIT_SUBMODULES_ARE_EVIL=1` carried over.
- **REQ-3** — The `UXRCE_DDS_CLIENT_USE_SYSTEM_LIBS=ON` /
  `PX4_BUILD_IDLC` question from §2 is resolved with evidence (not
  assumption) and reflected in `EXTRA_OECMAKE`, exactly as thoroughly
  as the posix recipe's fix #3 was — this is the single most likely
  repeat-failure mode given it already bit the posix build once.
- **REQ-4** — **Done.** `px4-io-firmware_1.17.0.bb` builds
  `px4_io-v2_default` (Cortex-M3) as its own recipe/build context — a
  new `pixhawk6x-io` multiconfig, §5.4 option (a), not a nested build.
  Producing the `.bin` that `px4-firmware`'s ROMFS generation actually
  consumes turned out to describe a different mechanism than what
  fmu-v6x uses (§5.4) — `px4-io-firmware` is a genuinely-built,
  independently-deployed artifact; wiring it into `px4-firmware`'s own
  ROMFS is a separate follow-up.
- **REQ-5** — **Done.** `px4-bootloader_1.17.0.bb` builds
  `px4_fmu-v6x_bootloader` (reuses the `pixhawk6x` multiconfig, same
  Cortex-M7/FPv5-D16 tune as `px4-firmware`).
- **REQ-6** — **Done for all three recipes.** `px4-firmware`,
  `px4-bootloader`, and `px4-io-firmware` all inherit `deploy`;
  outputs (`.elf`, `.bin`, `.px4`) land in `DEPLOY_DIR_IMAGE`. Nothing
  installs into a rootfs. (Plain `deploy`, not `baremetal-image` — the
  latter wasn't needed once `do_deploy` proved sufficient.)
- **REQ-7** — **Done for all three recipes.** Two consecutive builds
  from clean `TMPDIR` (same inputs) produce byte-identical `.elf`/
  `.px4` artifacts — `px4-firmware` confirmed across five independent
  `cleansstate` rebuilds total (§7); `px4-bootloader`/`px4-io-firmware`
  needed the same `SOURCE_DATE_EPOCH` patch added to their own
  `SRC_URI` and were confirmed via one clean rebuild each after that
  fix (all three now produce the identical `SOURCE_DATE_EPOCH`,
  `1777072624`).
- **REQ-8** — **Done.** `bitbake mc:pixhawk6x:px4-firmware` succeeds
  with `BB_NO_NETWORK="1"` after a `cleansstate` forced a genuine
  re-fetch/re-unpack/re-compile against only cached sources.

## 4. Non-goals

- Booting in Renode (M3) or on hardware (M5) — this milestone is
  build-only.
- UAVCAN/DroneCAN ROMFS peripheral firmware.
- Any board other than fmu-v6x/io-v2.
- Resolving whatever the CDRSTREAM audit in §2 turns up beyond
  wiring the correct `EXTRA_OECMAKE` flag — if it surfaces a deeper
  PX4-side issue, that becomes its own follow-up, not scope creep
  here.

## 5. Design sketch

### 5.1 Recipe layout

```
recipes-px4/
├── px4-autopilot/            # existing, unchanged
├── px4-firmware/
│   ├── px4-firmware.inc      # shared with px4-io-firmware/px4-bootloader?
│   │                         # evaluate during implementation whether
│   │                         # the three targets share enough
│   │                         # (SRC_URI, SRCREV, patches) to warrant
│   │                         # a common .inc, following px4-autopilot's
│   │                         # own .bb + .inc split.
│   └── px4-firmware_1.17.0.bb
├── px4-io-firmware/
│   └── px4-io-firmware_1.17.0.bb
└── px4-bootloader/
    └── px4-bootloader_1.17.0.bb
```

### 5.2 Carried patches

`px4-autopilot`'s five patches (`0001`–`0005`) target the posix build
specifically (kconfig toolchain guard, UXRCE system-libs support, idlc
support, sitl deb packaging, dpkg replacement). The NuttX firmware
recipes need their own patch review:

- **Patch `0001` must be OMITTED for `px4-firmware` — the opposite of
  the earlier guess in this spec, resolved by reading the actual
  patch diff, not by assumption.** The patch changes
  `if(TOOLCHAIN)` to `if(TOOLCHAIN AND NOT CMAKE_TOOLCHAIN_FILE)` so
  an *externally-provided* `CMAKE_TOOLCHAIN_FILE` (e.g. bitbake's own,
  injected unconditionally by `cmake.bbclass` for every cmake-based
  recipe — confirmed from `classes-recipe/cmake.bbclass`) wins over
  PX4's board-driven default. That's exactly what `px4-autopilot`
  (posix) needs. `px4-firmware` needs the *opposite*: PX4's own
  `Toolchain-arm-none-eabi.cmake` (driven by
  `CONFIG_BOARD_TOOLCHAIN="arm-none-eabi"`) must win over bitbake's
  own (Linux-targeting, wrong-triple) generated toolchain file. Since
  `cmake.bbclass` always injects its own file, keeping the *unpatched*
  `if(TOOLCHAIN) ... FORCE` behavior is what makes PX4 correctly
  clobber it. `px4-firmware_1.17.0.bb`'s `SRC_URI` therefore omits
  `0001-cmake-kconfig-...patch` entirely.
- **Patches `0002`/`0003` (UXRCE system libs, idlc) are NOT carried
  for `px4-firmware` — maintainer decision, not a technical dead
  end.** Tracing the build chain surfaced a real complication:
  `UXRCE_DDS_CLIENT_USE_SYSTEM_LIBS=ON` would mean `px4-firmware`
  links this layer's `microcdr`/`microxrceddsclient` recipes as
  prebuilt target libraries, but those are independent CMake projects
  with no PX4-specific toolchain-forcing patch of their own — under
  `MACHINE=pixhawk-6x` they'd get `cmake.bbclass`'s own
  (Linux-targeting, wrong-triple) generated toolchain file instead of
  `arm-none-eabi`, needing a vendored copy of PX4's
  `Toolchain-arm-none-eabi.cmake` plus per-package bbappends to fix.
  The maintainer confirmed it's acceptable for
  `microcdr`/`microxrceddsclient` to be built as part of PX4's own
  nested nested build instead (PX4's normal, unpatched
  `ExternalProject_Add` path), avoiding that toolchain-composition
  problem entirely for this milestone. This reintroduces the
  network-fetch-during-compile question the posix build hit for the
  same reason (spec 000 §4.3 fix #3) — resolve *that* with real build
  evidence when/if it actually occurs, rather than pre-solving it
  speculatively (`gitsm://`'s recursive submodule fetch may already
  cover it; PX4-Autopilot's own submodule pin for
  `src/modules/uxrce_dds_client/Micro-XRCE-DDS-Client` is fetched
  offline regardless — only Micro-CDR's *own* nested
  `ExternalProject_Add` inside that submodule is the open question).
- Patches `0004`/`0005` (sitl deb packaging, dpkg replacement) are
  posix-specific and don't apply.

### 5.3 Toolchain

Per spec 001 §4.1's plan of record (Option B, now implemented — see
spec 001 §6): `gcc-arm-none-eabi-native` provides prebuilt
`arm-none-eabi-*` binaries on PATH; `px4-firmware` lets PX4's own
`cmake/kconfig.cmake` force-select `Toolchain-arm-none-eabi.cmake`
(§5.2) rather than fighting it. With `microcdr`/`microxrceddsclient`
out of scope per the revised §5.2, this recipe doesn't need to solve
toolchain composition for any dependency outside PX4's own tree for
this milestone.

### 5.4 px4io as a multiconfig dependency

Per spec 000 §4.2: `px4-io-firmware` is Cortex-M3, a different tune
than `px4-firmware`'s Cortex-M7. This needs either (a) a second
multiconfig context (`pixhawk6x-io` or similar) whose deploy output
`px4-firmware` consumes via a `mc:` dependency, or (b) accepting the
nested PX4 build for just this sub-target if isolating it turns out
to be more complexity than it's worth. Decide with evidence — try (a)
first since it's the more OE-idiomatic and reproducible path, fall
back to (b) only if it proves impractical.

**Resolved: option (a), and it worked on the first real build.**
`conf/machine/pixhawk-6x-io.conf` (Cortex-M3, `DEFAULTTUNE =
"cortexm3"` — a stock oe-core tune, `tune-cortexm3.inc`, no
layer-local tune file needed since STM32F100 has no FPU) plus
`conf/multiconfig/pixhawk6x-io.conf` (`MACHINE = "pixhawk-6x-io"`).
`bitbake mc:pixhawk6x-io:px4-io-firmware` succeeded on the very first
attempt — 2717/2717 tasks, zero errors — reusing the same
`gcc-arm-none-eabi-native` toolchain as `px4-firmware` (the ARM GNU
toolchain is multi-target; no second toolchain package needed).

One real finding changed the shape of "consumes" in REQ-4's wording:
inspecting `src/drivers/px4io/CMakeLists.txt` showed PX4 has its own
mechanism for embedding a freshly-built io-v2 image into an FMU's
ROMFS — an `ExternalProject_Add` nested build gated on
`CONFIG_BOARD_IO`, which cubepilot boards set but fmu-v6x does not.
For fmu-v6x, `boards/px4/fmu-v6x/extras/px4_io-v2_default.bin` is
instead a **pre-built binary blob checked into the PX4-Autopilot
source tree**, and that's what `px4-firmware`'s ROMFS actually
embeds (confirmed via `ROMFS/px4fmu_common/init.d/rcS`'s
`IOFW="/etc/extras/px4_io-v2_default.bin"`). So `px4-io-firmware` as
built here is a genuinely-from-source, independently deployed
artifact, not (yet) what `px4-firmware`'s own build consumes —
wiring `px4-firmware` to use our freshly-built image instead of the
vendored blob is a separate, not-yet-decided follow-up, not something
this milestone's evidence required.

The same real-build process also surfaced that `px4-bootloader`
produces a `.px4` (not just `.elf`/`.bin`, confirmed via a real
`px_mkfw.py` invocation in the `do_compile` log) — the initial recipe
missed deploying it. Both `px4-bootloader` and `px4-io-firmware` also
needed patch 0001 (`SOURCE_DATE_EPOCH`, §7) added to their own
`SRC_URI` for `.px4` reproducibility parity with `px4-firmware` — not
inherited automatically since each recipe carries its own patch list.
All three recipes now produce the identical `SOURCE_DATE_EPOCH`
(`1777072624`), confirmed by inspecting each deployed `.px4`'s
`build_time` field after a rebuild.

## 6. Acceptance criteria

- **AC-1** — **Done.** `bitbake mc:pixhawk6x:px4-firmware` succeeds:
  2011/2011 tasks, zero errors (REQ-2, REQ-8). Re-verified with
  `BB_NO_NETWORK=1` explicitly set after a `cleansstate` (forcing
  `do_fetch`/`do_unpack`/`do_compile` to genuinely re-run against only
  cached sources): all tasks succeeded, confirming the microcdr mirror
  redirect and every other source are truly network-independent once
  fetched. REQ-2 fully closed.
- **AC-2** — **Done.** `px4-firmware_1.17.0.bb` now `inherit`s `deploy`
  with a `do_deploy` task (`addtask deploy after do_compile before
  do_build`) that installs
  `px4-firmware-${PV}-${MACHINE}.{elf,px4}` into `DEPLOY_DIR_IMAGE`.
  Verified for real, not just by reading the recipe: after enabling
  `INHERIT += "rm_work"` (to reclaim disk space) and rebuilding from a
  wiped `TMPDIR`, `tmp/deploy/images/pixhawk-6x/px4-firmware-1.17.0-pixhawk-6x.elf`
  exists (47,702,264 bytes, matching the earlier build byte-for-byte
  in size) while `tmp/work/.../px4-firmware/1.17.0/` was reduced to
  just `temp/` by `rm_work` — proving the deploy task, not luck, is
  what preserved the artifact. Re-ran `arm-none-eabi-readelf -A` on
  the deployed copy: `Tag_CPU_name: "7E-M"`, `Tag_FP_arch: FPv5/FP-D16`
  — still the correct target chip attributes.
- **AC-3** — **Done.** Two clean builds produce byte-identical
  artifacts (REQ-7). Three independent `cleansstate` rebuilds showed
  the `.elf` was already byte-identical every time
  (`c359018bcbeb958f06c345a5d30e5a53`), but the `.px4` wrapper wasn't
  — traced to `Tools/px_mkfw.py` hardcoding
  `build_time = int(time.time())` with no override. Fixed with patch
  0001 (§7): honor `SOURCE_DATE_EPOCH` (which OE already computes per
  recipe and exports to all tasks), falling back to `time.time()`
  unchanged when unset. Re-verified with two more independent
  `cleansstate` rebuilds after the patch: both `.elf` and `.px4`
  hashes matched exactly across runs.
- **AC-4** — **Done.** §2's CDRSTREAM question is answered with cited
  Kconfig evidence: OFF for `px4_fmu-v6x_default`
  (`MODULES_ZENOH` is the only selector and it's unset).
- **AC-5** — **Done.** `px4-io-firmware` (new `pixhawk6x-io`
  multiconfig, Cortex-M3, §5.4 option (a)) and `px4-bootloader` (reuses
  the `pixhawk6x` multiconfig) both build and deploy (REQ-4, REQ-5).
  Verified for real: `readelf -A` on the deployed `px4-io-firmware`
  `.elf` shows `Tag_CPU_name: "7-M"` with no FPU tags (correct for the
  FPU-less STM32F100C8); the deployed `.px4` shows `board_id: 10`,
  `magic: "PX4FWv2"` (the known PX4IOv2 identifiers) and
  `image_maxsize: 61440` (60K, matching `script.ld`'s flash length).
  `px4-bootloader`'s `.elf` shows the same Cortex-M7/FPv5-D16
  attributes as `px4-firmware`'s. Both also carry patch 0001
  (`SOURCE_DATE_EPOCH`) for `.px4` reproducibility parity.
- **AC-6** — **Done.** `bitbake px4-autopilot` (posix) succeeds
  unaffected by all of this milestone's new recipes/machines/
  multiconfigs: 2947/2947 tasks, zero errors. `bitbake
  mc:pixhawk6x:baremetal-helloworld` fails with "Nothing PROVIDES
  'baremetal-helloworld' ... incompatible with machine pixhawk-6x" —
  this is the **pre-existing, already-documented** limitation from
  spec 001 REQ-3's revision (oe-core's `baremetal-helloworld` only
  maps addresses for QEMU `COMPATIBLE_MACHINE` targets, which is
  exactly why M2's `px4-firmware` exists as the real bare-metal
  validation path), not a regression from any of this session's new
  work — none of it touches `baremetal-helloworld` or its
  `COMPATIBLE_MACHINE` list.

## 7. Implementation record (fill during/after implementation)

**`px4-firmware` builds successfully as of 2026-07-25**, after three
real bugs found and fixed by actually running the build (not by
static review):

1. **`HOSTCC`** — NuttX compiles several host-side build tools
   (`incdir`, `mkdeps`, ...) with a *native* compiler, entirely
   separate from `arm-none-eabi`. `tools/Config.mk` defaults
   `HOSTCC ?= cc`, but bare `cc` (unlike `gcc`) isn't in oe-core's
   `HOSTTOOLS` allowlist. Fixed with `export HOSTCC = "gcc"` in
   `px4-firmware.inc` (`?=` lets the inherited environment variable
   win).
2. **`pkg_resources`** — PX4's libuavcan DSDL compiler still imports
   it directly; setuptools 82.0.0 (Feb 2026) removed the module
   entirely upstream (confirmed via web search, not assumed:
   [pypa/setuptools#5174](https://github.com/pypa/setuptools/issues/5174)).
   Added `python3-pkg-resources_81.0.0.bb` (last pre-removal release,
   version/checksum verified via PyPI's JSON API), providing just the
   `pkg_resources/` module alongside oe-core's own (82.0.1) setuptools.
3. **Micro-CDR nested fetch** — `Micro-XRCE-DDS-Client`'s own
   `SuperBuild.cmake` does its own `ExternalProject_Add` git clone of
   Micro-CDR at compile time (the exact tradeoff flagged when
   `UXRCE_DDS_CLIENT_USE_SYSTEM_LIBS` was scoped out below). Fixed by
   fetching the same pinned ref (`tag v2.0.1`, matching
   `microcdr_2.0.1.bb`'s own pin) as a second named `SRC_URI` and
   redirecting that exact `GIT_REPOSITORY` URL to the local copy via
   a task-scoped `GIT_CONFIG_GLOBAL` in `do_compile:prepend` —
   deliberately not `git config --global`, since `HOME` in this
   sandboxed task is the real build user's home directory, not
   isolated.

**Follow-up work landed 2026-07-26**, prompted by enabling
`INHERIT += "rm_work"` locally to reclaim disk space (which would
have silently discarded every finished firmware image, since PX4's
build has no install step):

4. **Deploy wiring (REQ-6)** — added `inherit deploy` and a
   `do_deploy` task to `px4-firmware_1.17.0.bb`, ordered `after
   do_compile before do_build` (same convention `kernel.bbclass` uses
   for `zImage`) so it runs ahead of `rm_work`'s cleanup. Verified
   against a real `rm_work`-enabled rebuild: the deployed `.elf`
   survived in `DEPLOY_DIR_IMAGE` (identical size/`readelf -A`
   attributes) while `${WORKDIR}` was reduced to just `temp/`.
5. **Offline build (REQ-2/REQ-8)** — after a `cleansstate`, rebuilt
   with `BB_NO_NETWORK=1` explicitly set. All 2717 tasks succeeded,
   confirming every source (including the microcdr mirror redirect)
   is genuinely cached and network-independent.
6. **Reproducibility (REQ-7)** — three independent `cleansstate`
   rebuilds showed the `.elf` was already byte-identical every time,
   but the `.px4` wrapper wasn't. Root-caused (without touching any
   unrelated project checkout) by inspecting `Tools/px_mkfw.py` in the
   already-fetched git mirror under the downloads cache:
   `desc['build_time'] = int(time.time())`, with no override
   mechanism. Added patch
   `0001-px_mkfw-honor-SOURCE_DATE_EPOCH-for-build_time.patch`
   (generated with `git format-patch` against the pinned SRCREV, not
   hand-edited) so it uses `SOURCE_DATE_EPOCH` when set — which OE
   already computes per recipe via `do_deploy_source_date_epoch` and
   exports to all tasks — falling back to `time.time()` unchanged
   otherwise. Two more independent `cleansstate` rebuilds after the
   patch produced identical `.elf` and `.px4` hashes both times.

| Item | Decision / evidence |
|---|---|
| CDRSTREAM status for fmu-v6x (§2) | **OFF** — only `MODULES_ZENOH` selects `LIB_CDRSTREAM`, and it's unset for `px4_fmu-v6x_default`. `PX4_BUILD_IDLC=OFF` not needed. Verified via kconfiglib `defconfig.py` run against the real `Kconfig` tree with `cmake/kconfig.cmake`'s exact env vars, cross-checked against known-true `CONFIG_MODULES_UXRCE_DDS_CLIENT=y`. |
| UXRCE_DDS_CLIENT_USE_SYSTEM_LIBS | **Deliberately not set** (maintainer decision, §5.2) — PX4 builds `microcdr`/`microxrceddsclient` itself via its own nested build; the resulting network fetch is redirected to a local mirror (see numbered list above) rather than solved via prebuilt system libs. |
| Which of px4-autopilot's patches 0001-0003 apply to px4-firmware | **0001: omitted** (confirmed necessary by reading the patch + `cmake.bbclass` source, §5.2). **0002/0003: not carried** (UXRCE system-libs scoped out, row above). The build succeeded without any of the three, so none are currently needed — revisit only if a real failure demands one. Note: px4-firmware has since gained its *own*, unrelated "0001" patch (`0001-px_mkfw-honor-SOURCE_DATE_EPOCH-for-build_time.patch`, row below) — the numbering is per-recipe and coincidental. |
| px4io multiconfig vs. nested-build decision (§5.4) | **Resolved: option (a)** — new `pixhawk-6x-io` MACHINE (Cortex-M3, stock `tune-cortexm3.inc`) + `pixhawk6x-io` multiconfig. `bitbake mc:pixhawk6x-io:px4-io-firmware` succeeded on the first real attempt, 2717/2717 tasks. |
| Byte-reproducibility confirmed | **Done (REQ-7)** — `.elf` was always reproducible; `.px4` needed `0001-px_mkfw-honor-SOURCE_DATE_EPOCH-for-build_time.patch` (see implementation record above). Confirmed with two independent `cleansstate` rebuilds post-patch: identical `.elf`/`.px4` hashes both times. |
| Toolchain gap vs. M1 findings (§5.3) | **None found** — `gcc-arm-none-eabi-native` (spec 001 §6) plus the unpatched kconfig force-override was sufficient; no additional toolchain work was needed beyond the three bugs above. |
| REQ-6 deploy wiring | **Done** — `px4-firmware_1.17.0.bb` inherits `deploy`; `do_deploy` (ordered `after do_compile before do_build`) installs `px4-firmware-${PV}-${MACHINE}.{elf,px4}` into `DEPLOY_DIR_IMAGE`. Verified against a real `rm_work`-enabled rebuild: the deployed `.elf` survived (47,702,264 bytes, same target attributes via `readelf -A`) while `rm_work` reduced `${WORKDIR}` to just `temp/` — the deploy task is what preserved it, not incidental leftover state. |

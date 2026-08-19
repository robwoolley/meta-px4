# Spec 009 (M9): ROS 2 SDK for host-side colcon cross-builds

- **Status:** In progress. REQ-0 (dynamic-layer `.bbappend` glob) and
  REQ-4 (jazzy forward-port of `skip_shell_path.patch`) are done — see
  §6; REQ-4's patch is verified to apply cleanly but its runtime effect
  (AC-3) cannot be checked until the SDK exists. Everything else is not
  started; REQ-1's `populate_sdk` baseline against upstream's
  `ros2-image-sdktest` is next.
- **Created:** 2026-08-18
- **Depends on:** [000-architecture.md](000-architecture.md) (M9 row),
  [007-sitl-gazebo-ros2.md](007-sitl-gazebo-ros2.md) (the
  `meta-ros2-jazzy` layer set and dynamic-layer wiring),
  [008-ontarget-colcon.md](008-ontarget-colcon.md) (shares the
  `ros2/examples` acceptance workload, for a direct comparison).
- **Delivers:** A `populate_sdk`-generated, relocatable SDK that a
  developer installs on their workstation and, after sourcing its
  environment script, uses to **cross-compile** ROS 2 packages with
  `colcon` for the target — validated by building the `jazzy` branch of
  [`ros2/examples`](https://github.com/ros2/examples) on the host and
  running the resulting binaries on the target.

## 1. Rationale

[008](008-ontarget-colcon.md) makes the target self-hosting, which is
simple but slow: an emulated (or embedded) target compiling C++ is the
worst machine in the room for the job. The standard OE answer is
`populate_sdk` — cross-toolchain plus a target sysroot, installed on
the developer's workstation. For ROS 2 that has historically not worked
out of the box, because `colcon` and the `ament`/`rosidl` build
machinery expect to run *host-native* Python tooling that generates
code against *target* headers, and a plain OE SDK ships neither the
nativesdk ROS tooling nor the environment variables that tell it which
side of the sysroot split each thing lives on.

Upstream meta-ros has been closing that gap, and the user pointed at
the two pieces: [PR #1215](https://github.com/ros/meta-ros/pull/1215)
("ROS2 support in standard SDK") and
[PR #1261](https://github.com/ros/meta-ros/pull/1261) ("Add required
ROS2 SDK environment variables"). §2 records what those two actually
are relative to the `wrynose` branch this project builds against —
which is not what their PR pages suggest, and materially changes the
size of this milestone.

## 2. Findings (checked against the real layers and the GitHub API, not assumed)

Layer paths are relative to the `meta-ros` checkout at
`oe-px4/bitbake-builds/oe-px4-sitl/layers/meta-ros` (branch `wrynose`,
HEAD `f4a5e4800a`).

- **PR #1215's substance is in `wrynose`, but none of its literal files
  are.** The PR targets `kirkstone-next` (GitHub reports it `closed`
  with `merged: false`, merge commit `5761716f`) and adds
  `meta-ros2/conf/distro/include/ros2-sdk.inc` plus a `require` line in
  `meta-ros2/conf/layer.conf`. Neither exists on `wrynose` — checked
  directly. What landed instead is a **per-ROS-distro** restructuring:
  `meta-ros2-jazzy/conf/ros-distro/include/jazzy/ros-sdk.inc` (and the
  same file for kilted, and others), `require`d from
  `meta-ros2-jazzy/conf/layer.conf:27`.
- **The restructured version defines variables; it does not apply
  them.** Where the PR did `TOOLCHAIN_HOST_TASK:append = "..."`
  globally from a layer-wide include, `jazzy/ros-sdk.inc` only sets
  `ROS_SDK_HOST_PACKAGES` and `ROS_SDK_TARGET_PACKAGES`. The *only*
  consumer anywhere in the tree is
  `meta-ros2/recipes-core/images/ros2-image-sdktest.bb`, which does
  `TOOLCHAIN_HOST_TASK:append = "${ROS_SDK_HOST_PACKAGES}"` and the
  target equivalent. **Consequence:** running `-c populate_sdk` on an
  arbitrary image (including M7's `px4-sitl-qemu-image`) gets *none* of
  the ROS SDK content. An image must opt in explicitly. This is the
  single most important structural finding for this milestone.
- **jazzy's package lists are substantially richer than the PR's.**
  `ROS_SDK_HOST_PACKAGES` adds `nativesdk-rosidl-default-generators`
  over the PR's list. `ROS_SDK_TARGET_PACKAGES` goes well beyond the
  PR's ament-cmake-only set, adding `rclcpp`, `rclcpp-lifecycle`,
  `rclcpp-action`, `rclcpp-components`, `builtin-interfaces`,
  `common-interfaces`, `fastrtps-cmake-module`,
  `rosidl-default-generators`, `rosidl-core-generators`,
  `rosidl-cmake`, `ros-environment`, `ros-workspace`, `pluginlib`,
  `ament-package`, and `ament-lint`. So the "which packages go in the
  SDK" problem is already solved upstream for jazzy — this milestone
  should consume `ROS_SDK_*_PACKAGES`, not re-derive a list.
- **PR #1215's `nativesdk` lark-parser half *is* present — in the
  recipe, not the include.** `meta-ros-common/recipes-devtools/python/
  python-lark-parser.inc` still ends with `BBCLASSEXTEND = "native"`,
  but that is not the operative line:
  `python3-lark-parser_0.7.0.bb` does `require python-lark-parser.inc`
  and then **overrides** it with `BBCLASSEXTEND = "native nativesdk"`.
  So `nativesdk-python3-lark-parser` builds, and the
  `nativesdk-rosidl-parser` in jazzy's `ROS_SDK_HOST_PACKAGES` (which
  needs it — `rosidl-parser_*.bb` has `ROS_EXEC_DEPENDS =
  "python3-lark-parser"`) resolves. Upstream is collapsing the `.inc`
  into the recipe outright, now that Python 2 is end-of-life and there
  is no longer a second recipe sharing it; this checkout predates that
  merge but already has the behavior. **No local delta is needed here**
  — an earlier draft of this spec predicted a `populate_sdk` failure
  from reading only the `.inc`, which was wrong.
- **PR #1261 is still open and unmerged.** Base `master-next`, state
  `open` — checked via the API, not inferred from the PR page. Its
  single file, `meta-ros2/recipes-devtools/ros-sdk-env/
  ros-sdk-env_1.0.bb`, is absent from `wrynose` (searched the whole
  tree). So its content has to be carried locally by this project for
  now, with a clear path to dropping it once upstream merges.
- **What #1261 actually does.** It is a `nativesdk` recipe that writes
  `ros-sdk-env.sh` into `${SDKPATHNATIVE}/post-relocate-setup.d/`,
  exporting `PYTHON_SOABI` (computed from `TUNE_ARCH`/`TARGET_OS` with
  the same arch-suffix special cases as
  `meta-ros2/classes/ros_ament_cmake.bbclass`, including the `arm` and
  `i686` exceptions), `PYTHON3_NUMPY_INCLUDE_DIR`, `PYTHONWARNINGS`,
  `AMENT_SKIP_SHELL_PATH`, and a `PYTHONPATH` prepend of the target
  sysroot's `site-packages`. It also ships a `ros-sdk-setup.sh` that
  copies the env script into `environment-setup.d/` at relocation time,
  and — if `ROS_SDK_UNIFY` is set to `sh`/`bash`/`zsh` — sources the
  target's `/opt/ros/${ROS_DISTRO}/setup.<ext>`. The value of this
  recipe is precisely that it removes the hand-exported variables from
  PR #1215's README instructions (which hardcoded
  `cpython-310-aarch64-linux-gnu`) and computes them from the actual
  machine configuration.
- **`AMENT_SKIP_SHELL_PATH` is inert on jazzy as things stand.** The
  variable is only honored if `ament_package` carries
  `skip_shell_path.patch`, which adds an
  `os.environ.get('AMENT_SKIP_SHELL_PATH')` early-return. That patch
  exists **only** under
  `meta-ros2-kilted/recipes-bbappends/ament-package/` (applied to
  `ament-package_0.17.3-1`). jazzy's `ament-package` is
  `0.16.5-1` and `meta-ros2-jazzy/recipes-bbappends/ament-package/`
  does not exist at all. So adopting #1261 on jazzy without also
  backporting that patch gives a variable nothing reads, and ament will
  still prepend `$OECORE_TARGET_SYSROOT/opt/ros/jazzy/bin` to the host
  `PATH` — the exact breakage #1261 exists to prevent.
- **`example_interfaces` need not be cloned into the workspace.** PR
  #1215's instructions clone `ros2/example_interfaces` alongside the
  examples. Here it is already packaged
  (`example-interfaces_0.12.1-1` for jazzy) and appears in the SDK's
  target sysroot once installed, so the workspace stays to just
  `ros2/examples`.
- **`ros2/examples` has a `jazzy` branch; meta-ros pins the same code
  at 0.19.7-1.** As in [008](008-ontarget-colcon.md) §2 — which is what
  makes the two milestones' results comparable, and lets an
  SDK-cross-built artifact be diffed against a bitbake-cross-built one
  produced by the very same toolchain.
- **meta-px4's dynamic-layer glob did not pick up `.bbappend` files.**
  `meta-px4/conf/layer.conf`'s `BBFILES_DYNAMIC` was a single
  `.../recipes-*/*/*.bb` pattern — `.bb` only. Every meta-px4
  dynamic-layer file so far has been a `.bb` (spec 007's three
  recipes), so this never mattered; but this milestone's REQ-2 and
  REQ-4 deliverables are bbappends, and they would have been silently
  ignored — no error, just an append that never happens. meta-ros2's
  own `layer.conf` shows the intended shape, listing `*.bb` and
  `*.bbappend` as separate entries for each of its qt5/qt6/zenoh
  dynamic layers. Fixed as REQ-0 (§6).

## 3. Requirements

- **REQ-0** — Extend `meta-px4/conf/layer.conf`'s `BBFILES_DYNAMIC`
  line to cover `*.bbappend` as well as `*.bb` (§2), following the
  two-entry shape meta-ros2's own `layer.conf` uses. Without this,
  REQ-1 and REQ-4 are no-ops that fail silently. Confirm with
  `bitbake-layers show-appends` that the new bbappends are actually
  seen.
- **REQ-1** — Establish the baseline: `bitbake ros2-image-sdktest -c
  populate_sdk` on this project's layer set. Upstream's own
  `ros2-image-sdktest` is the SDK vehicle for this milestone (it is
  already the sole consumer of `ROS_SDK_*_PACKAGES` — §2 — so using it
  means consuming upstream's wiring rather than reproducing it), and
  proving it builds unmodified here is the first task.
- **REQ-2** — Extend that SDK with this project's own ROS packages —
  `px4-msgs`, `px4-ros2-cpp`, `micro-xrce-dds-agent` — via a
  `ros2-image-sdktest_%.bbappend` (or a thin meta-px4 image deriving
  from it) adding them to `TOOLCHAIN_TARGET_TASK`, so the SDK can build
  an application against the PX4 interface libraries and not merely
  against stock ROS 2. Keep this additive: REQ-1's unmodified baseline
  must stay independently reproducible.
- **REQ-3** — Carry PR #1261's `ros-sdk-env` recipe in meta-px4 (same
  dynamic layer), so the generated SDK exports `PYTHON_SOABI`,
  `PYTHON3_NUMPY_INCLUDE_DIR`, `PYTHONWARNINGS`,
  `AMENT_SKIP_SHELL_PATH`, and the target `PYTHONPATH` automatically on
  `source environment-setup-*`. Attribute it to the upstream PR and
  record the condition for dropping it (PR #1261 merging and reaching
  `wrynose`).
- **REQ-4** — *(Implemented ahead of the rest — see §6.)* Forward-port
  `skip_shell_path.patch` to jazzy's `ament-package` as a bbappend, so
  REQ-3's `AMENT_SKIP_SHELL_PATH` actually does something (§2). Verify
  the effect directly — the host `PATH` must not gain
  `$OECORE_TARGET_SYSROOT/opt/ros/jazzy/bin` after sourcing the SDK
  environment.
- **REQ-5** — `bitbake ros2-image-sdktest -c populate_sdk` produces an
  installable SDK; installing and sourcing it yields an environment
  where `colcon` is on `PATH` and `OE_CMAKE_TOOLCHAIN_FILE` is set.
- **REQ-6** — In that environment, a `colcon build` of the `jazzy`
  branch of `ros2/examples` cross-compiles successfully, using
  `-DCMAKE_TOOLCHAIN_FILE=${OE_CMAKE_TOOLCHAIN_FILE}` and
  `-DBUILD_TESTING=OFF`, with `PYTHON_SOABI` coming from REQ-3's
  environment rather than being hand-passed on the command line.
- **REQ-7** — The cross-built artifacts are confirmed to be *target*
  binaries (correct ELF machine type, not the host's) and at least one
  of them runs on the target, interoperating with a packaged
  counterpart as in [008](008-ontarget-colcon.md) REQ-4.
- **REQ-8** — Document the full workflow — SDK build, install, source,
  `colcon` invocation, and the two carried upstream deltas (REQ-1,
  REQ-3/REQ-4) with their drop conditions.

## 4. Non-goals

- **Upstreaming the carried deltas.** Worth doing eventually, and
  REQ-1/REQ-3/REQ-4 are written so the deltas stay identifiable, but
  landing patches in meta-ros is not this milestone's gate.
- **The extensible SDK (eSDK, `populate_sdk_ext`).** M6 already owns
  eSDK in the milestone table; this milestone is the standard SDK only.
- **Cross-building PX4 itself with colcon.** `px4-autopilot-gz` remains
  a bitbake recipe (spec 007 §6 documents why its build is not a
  well-behaved CMake project). The SDK targets ROS 2 *application*
  development against `px4-msgs`/`px4-ros2-cpp`.
- **Windows or macOS SDK hosts.** Linux `x86_64` only, matching every
  other tool in this project.
- **On-target building** — that is [008](008-ontarget-colcon.md).

## 5. Design sketch

### 5.1 Layer layout

```
meta-px4/
└── dynamic-layers/
    └── meta-ros2-jazzy/
        ├── recipes-px4/                  # existing (M7)
        ├── recipes-bbappends/
        │   ├── ament-package/
        │   │   ├── ament-package_%.bbappend        # REQ-4  (done)
        │   │   └── ament-package/skip_shell_path.patch
        │   └── ros2-image-sdktest_%.bbappend       # REQ-2
        └── recipes-devtools/ros-sdk-env/
            └── ros-sdk-env_1.0.bb                  # REQ-3, upstream PR #1261
```

Everything sits inside the existing `meta-ros2-jazzy` dynamic layer, so
a meta-ros-less build (the Renode/STM32H7 track) is unaffected — the
same property M7 established and relies on. The `.bbappend` entries
depend on REQ-0's `BBFILES_DYNAMIC` fix (done); `ros-sdk-env_1.0.bb`
works under the glob as it stood already.

No image recipe of this project's own is needed: upstream's
`ros2-image-sdktest` is the SDK vehicle (REQ-1), extended additively
for the PX4 packages (REQ-2).

### 5.2 Sourcing model: two sysroots, one build

The thing that makes ROS 2 SDK builds subtle is that `ament`/`rosidl`
code generators run as **host** Python (from `OECORE_NATIVE_SYSROOT`)
while emitting and compiling code against **target** headers (from
`OECORE_TARGET_SYSROOT`). PR #1215's approach was to have the developer
export `AMENT_PREFIX_PATH` spanning both sysroots and a `PYTHONPATH`
spanning both `site-packages` trees, by hand, with a hardcoded
`PYTHON_SOABI`. REQ-3 replaces the hand-exporting with a generated
script, but the model is the same and should be stated plainly in the
REQ-8 documentation, because every failure mode in this milestone is
some variation of "the wrong sysroot's copy won".

### 5.3 Version pinning

The examples workspace is pinned to `ros2/examples` branch `jazzy`,
matching both this project's ROS distro and the 0.19.7-1 recipes
meta-ros already generates (§2). Pin the branch explicitly in the
documentation rather than cloning the default branch, which tracks
`rolling` and will not build against jazzy.

## 6. Implementation record

### REQ-0 — complete

`conf/layer.conf`'s `BBFILES_DYNAMIC` line gained a second entry for
`*.bbappend` alongside the existing `*.bb`, matching both the layer's
own (non-dynamic) `BBFILES` and the shape meta-ros2 uses for its
qt5/qt6/zenoh dynamic layers. This is a prerequisite for REQ-4, not
cosmetic: bitbake ignores a bbappend that no `BBFILES` pattern matches
*silently* — no warning — so the ament-package append below would
otherwise never have run.

Confirmed with the real build (AC-0): `bitbake-layers show-appends`
against the `oe-px4-sitl` environment reports

```
ament-package_0.16.5-1.bb:
  .../meta-px4/dynamic-layers/meta-ros2-jazzy/recipes-bbappends/ament-package/ament-package_%.bbappend
```

(`MACHINE` has to be given — `MACHINE=qemux86-64 bitbake-layers ...` —
because this build directory's `local.conf` never sets one; bitbake
fails with an unexpanded `${MACHINE}` in its cooker log path otherwise.
Worth knowing before running any bitbake command in this environment.)

### REQ-4 — complete (patch carried; runtime effect still to verify)

`dynamic-layers/meta-ros2-jazzy/recipes-bbappends/ament-package/`
carries `ament-package_%.bbappend` and a jazzy-regenerated
`skip_shell_path.patch`.

The patch was taken from
`meta-ros2-kilted/recipes-bbappends/ament-package/ament-package/`
(written against ament-package 0.17.3-1) and re-cut against jazzy's
actual pinned source — `ros2-gbp/ament_package-release` at SRCREV
`2117de89eab1d924d9e6a72ec58f31c221dd49c5`, i.e. ament-package
0.16.5-1, the revision `meta-ros2-jazzy/generated-recipes/
ament-package/ament-package_0.16.5-1.bb` names. The kilted copy applies
to that source with a −2 line offset; regenerating removes the offset
so neither `patch` nor the stricter `git apply --check` reports fuzz
(both verified against the pristine file fetched at that SRCREV). The
change itself is unmodified in substance: a `_skip_shell_path()` helper
reading `AMENT_SKIP_SHELL_PATH`, and a `break` in
`handle_dsv_types_except_source` that skips `PATH`/`LD_LIBRARY_PATH`
when it is set.

The bbappend is deliberately `%`-versioned where kilted's is pinned to
its exact version. A pinned append stops matching after a version bump
and silently restores the very bug it fixes (an inert
`AMENT_SKIP_SHELL_PATH`); a `%` append against a patch cut for one
SRCREV will instead fail the build loudly if upstream moves the patched
code, which is the better failure for something whose symptom is
otherwise invisible.

Verified in the real build, not just against a fetched copy of the
file: `MACHINE=qemux86-64 bitbake ament-package -c patch -f` succeeds,
and the unpacked tree at
`tmp/work/x86-64-v3-oe-linux/ament-package/0.16.5-1/sources/
ament-package-0.16.5-1/ament_package/template/prefix_level/
_local_setup_util.py` contains both hunks — `_skip_shell_path()` at
line 195 and the `PATH`/`LD_LIBRARY_PATH` guard at line 328. (A green
`do_patch` alone would not have shown this; the file was read back.)

Still outstanding: this establishes that the patch *applies*, not that
it *works*. AC-3 — host `PATH` free of
`$OECORE_TARGET_SYSROOT/opt/ros/jazzy/bin` after sourcing a real SDK
environment — needs REQ-3 and REQ-5 first, since nothing sets
`AMENT_SKIP_SHELL_PATH` until `ros-sdk-env` is in the SDK.

### REQ-2 / REQ-3 — recipes written; PR #1261 needed a wrynose fix

`recipes-bbappends/images/ros2-image-sdktest.bbappend` (REQ-2) and
`recipes-devtools/ros-sdk-env/ros-sdk-env_1.0.bb` (REQ-3) are in place.

Two things worth recording about the bbappend:

- It is named `ros2-image-sdktest.bbappend`, **not**
  `ros2-image-sdktest_%.bbappend`. Upstream's recipe filename carries no
  version (`ros2-image-sdktest.bb`), and a `%` append matches only
  versioned recipes — the `%` form was silently attached to nothing, and
  `bitbake-layers show-appends` was what caught it.
- Its additions are gated on `PX4_ROS_SDK_EXTRAS` so REQ-1's unmodified
  upstream baseline stays reproducible without deleting the file.

**PR #1261 does not apply to wrynose as written.** The recipe sets

```
S = "${WORKDIR}/sources"
UNPACKDIR = "${S}"
```

and this OE version's `do_unpack` rejects that outright:

```
ERROR: nativesdk-ros-sdk-env-1.0-r0 do_unpack: S should be set relative
to UNPACKDIR, e.g. replace WORKDIR with UNPACKDIR in
"S = ${WORKDIR}/sources"
```

Since `bitbake.conf` already has `UNPACKDIR ??= "${WORKDIR}/sources"`,
`S = "${UNPACKDIR}"` is the same path and satisfies the check. Also
added a `mkdir -p ${S}` at the top of `do_install`, because this recipe
has no `SRC_URI` at all and so nothing otherwise guarantees the
directory exists by then. Both deviations are commented in the recipe
against the upstream original — this is a genuine
master-next-vs-wrynose incompatibility, worth reporting upstream when
the carried delta is revisited.

### Disk: `populate_sdk` needs far more room than an image build, and
### `rm_work_all` does not clean up after it

Worth knowing before running this milestone. The first `populate_sdk`
attempt grew `tmp/` from 23 GB to 71 GB and filled the disk, dying on
its *last* task (`do_populate_sdk`, 11179 of 11180) for want of space
rather than on any build error.

Two compounding reasons, both specific to `-c populate_sdk`:

- **`rm_work` never fires during the build.** `rm_work.bbclass` hangs
  `do_rm_work` off `do_build` (via `do_rm_work_all[recrdeptask]`), and
  `-c populate_sdk` never runs `do_build` — so nothing in the
  dependency tree is pruned as it goes, unlike a plain `bitbake
  <image>`.
- **`rm_work_all` afterwards does not reach the SDK host packages
  either.** Running `bitbake ros2-image-sdktest -c rm_work_all` on the
  aftermath freed only ~1 GB of 42 GB: its `recrdeptask` follows
  `do_build`'s dependency chain, but the `nativesdk-*` recipes are
  pulled in by `TOOLCHAIN_HOST_TASK` for `populate_sdk` specifically and
  are not in that chain. They have to be named directly:

  ```sh
  MACHINE=qemux86-64 bitbake -c rm_work \
      nativesdk-qemu nativesdk-llvm nativesdk-glibc-locale \
      nativesdk-linux-libc-headers nativesdk-mesa \
      gcc-cross-canadian-x86-64 binutils-cross-canadian-x86-64 \
      qemu-helper-native px4-ros2-cpp px4-msgs
  ```

  That reclaimed 16 GB (`tmp/work` 40 GB → 21 GB). `nativesdk-qemu`
  alone was 8.1 GB. `linux-yocto` stays regardless — `inject_rm_work`
  adds anything inheriting `kernel` to `RM_WORK_EXCLUDE` by design.

Use `bitbake -c rm_work` rather than deleting work directories by hand:
`do_rm_work` rewrites `tmp/stamps`, promoting each completed task's
stamp to a *setscene* stamp so a later run restores from sstate. A
manual `rm -rf` leaves normal stamps claiming the output is still in
`WORKDIR`, which is precisely the desync
[007](007-sitl-gazebo-ros2.md) §6 recorded (`zlib`, `gcc-runtime`
failing on missing `packages-split/`). If a work directory must be
removed by hand, remove that recipe's `tmp/stamps` entry with it.

### REQ-1, REQ-5-REQ-8 — in progress

The first `populate_sdk` run got as far as
`nativesdk-ros-sdk-env:do_unpack` before failing on the above, having
built 7768+ of 11180 tasks with no other error — so the lark-parser
blocker an earlier draft predicted indeed does not exist (§2), and
nothing else in the upstream baseline has failed. A retry with the
fixed recipe is queued.

## 7. Acceptance criteria

- **AC-0** — MET for REQ-4's bbappend. `bitbake-layers show-appends`
  lists it against `ament-package_0.16.5-1.bb` (§6), proving REQ-0's
  glob fix works — it is invisible without it. Re-check when REQ-2's
  `ros2-image-sdktest` bbappend is added.
- **AC-1** — The REQ-1 baseline is answered in §6 with real build
  output: `bitbake ros2-image-sdktest -c populate_sdk` either succeeds
  unmodified, or any fix needed is carried as an identifiable,
  attributed delta.
- **AC-2** — `bitbake ros2-image-sdktest -c populate_sdk` produces an
  SDK installer; installing it and sourcing `environment-setup-*`
  gives `colcon` on `PATH`, a set `OE_CMAKE_TOOLCHAIN_FILE`, and a
  `PYTHON_SOABI` matching the target machine's actual tuple (REQ-3,
  REQ-5).
- **AC-3** — After sourcing the SDK environment, the host `PATH` does
  **not** contain `$OECORE_TARGET_SYSROOT/opt/ros/jazzy/bin` —
  demonstrating REQ-4's patch is applied and effective, not merely
  present.
- **AC-4** — `colcon build` of `ros2/examples` (branch `jazzy`)
  completes in the SDK environment with every package succeeding,
  verified from colcon's summary *and* the expected files under
  `install/`, without hand-exporting `PYTHON_SOABI`,
  `AMENT_PREFIX_PATH`, or `PYTHONPATH` on the command line (REQ-6).
- **AC-5** — `file`/`readelf` on a built executable reports the
  target's ELF machine type, and that executable runs on the target and
  exchanges messages with a packaged counterpart (REQ-7).
- **AC-6** — A package depending on this project's own `px4-msgs`
  builds in the SDK environment, proving REQ-2's target-task additions
  are reachable from a colcon workspace (not just stock ROS 2).

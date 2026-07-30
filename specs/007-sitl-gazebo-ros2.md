# Spec 007 (M7): `px4-autopilot` SITL + Gazebo + ROS 2 + QGroundControl

- **Status:** In progress. REQ-1/REQ-2 (AC-1) complete and verified —
  see §6. REQ-3 through REQ-7 not started.
- **Created:** 2026-07-27
- **Depends on:** [000-architecture.md](000-architecture.md) (M7 row),
  [002-px4-firmware.md](002-px4-firmware.md)'s sibling `px4-autopilot`
  recipe (already delivers a working `px4_sitl_default` posix build —
  see README.md's own recipe table).
- **Delivers:** `px4-autopilot` built with real (not stub) Gazebo
  simulation support, plus new `px4-msgs`/`px4-ros2-cpp`/
  `micro-xrce-dds-agent` recipes, so the full architecture below is
  reproducible from this layer:

  ```
  ROS 2 nodes (px4_ros2, offboard) <-> Micro XRCE-DDS Agent <-> px4-autopilot (uxrce_dds_client)
                                                                        |
                                                                  Gazebo Sim (x500, sensors)
                                                                        |
                                                                  QGroundControl (MAVLink UDP)
  ```

## 1. Rationale

No real Pixhawk 6X hardware is available, so M5 (hardware validation,
spec 000's table) can't proceed. `px4-autopilot`'s posix/SITL build,
combined with Gazebo, ROS 2, and QGroundControl, is PX4's own
standard, officially-documented development/validation architecture —
distinct from and complementary to the Renode-based STM32H7 firmware
track (M1-M4): that track validates the real bare-metal firmware
against a real CPU model; this track validates flight logic, ROS 2
integration, and sensor/perception pipelines against full physics
simulation, independent of what hardware exists. Pursuing this now
gives a real validation path while hardware remains unavailable, using
infrastructure (`meta-ros`) already available to this project.

## 2. Findings (checked against real source/recipes, not assumed)

- **MAVLink and the uXRCE-DDS client need zero PX4-side changes.**
  `px4-autopilot`'s pinned PX4 source
  (`boards/px4/sitl/default.px4board`) already has
  `CONFIG_MODULES_MAVLINK=y` and `CONFIG_MODULES_UXRCE_DDS_CLIENT=y`.
  QGroundControl (MAVLink UDP, default port 14550) and the DDS-agent
  path both work against the existing `px4-autopilot` build as-is.
- **Gazebo support is Kconfig-enabled but builds as stub targets.**
  Same `default.px4board`: `CONFIG_MODULES_SIMULATION_GZ_BRIDGE=y`,
  `GZ_MSGS=y`, `GZ_PLUGINS=y`. But PX4's own
  `src/modules/simulation/gz_bridge/CMakeLists.txt` and
  `gz_plugins/CMakeLists.txt` do `find_package(gz-transport NAMES
  gz-transport gz-transport14 gz-transport13)` (and similarly for
  `gz-sim`/`gz-sensors`/`gz-plugin`) at CMake-configure time; when
  `$ENV{GZ_DISTRO}` is `harmonic` they pin to the exact versioned names
  `gz-sim8`/`gz-sensors8`/`gz-plugin2`/`gz-transport13`. `find_package`
  fails today because `px4-autopilot.inc`'s `DEPENDS` has no gz-*
  entries at all — confirmed directly, not assumed — so these modules
  silently build as stubs that just print `"ERROR: Gazebo simulation
  dependencies not found!"` at runtime. There is no separate
  `*_gz*.px4board`; `make px4_sitl gz_x500` uses this same
  `px4_sitl_default` CONFIG, varying only the runtime
  `PX4_SIM_MODEL=gz_x500` selection.
- **This layer's own README warning doesn't apply once real `DEPENDS`
  are added.** The existing "Known limitations" note ("the nested `gz`
  project forwards no toolchain settings") is about PX4's internal
  `ExternalProject_Add(gz ...)` *fallback* path, which only runs when
  `find_package` fails. Supplying properly cross-built gz-* libraries
  via `DEPENDS` makes `find_package` succeed, so that fallback path
  never triggers.
- **`meta-ros` already has the hard part packaged.** Checked the copy
  already fetched at `oe-px4-sitl/layers/meta-ros`:
  `meta-ros-common/recipes-devtools/gazebo/` has real, complete
  recipes — `gz-sim8_8.11.0.bb`, `gz-sensors8_8.2.2.bb`,
  `gz-plugin2_2.0.4.bb`, `gz-transport13_13.5.0.bb`, `sdformat_*.bb` —
  with correct transitive `DEPENDS` chains (`gz-cmake3`, `gz-common5`,
  `gz-fuel-tools9`, `gz-gui8`, `gz-math7`, `gz-msgs10`, `gz-physics7`,
  `gz-rendering8`, `gz-tools2`, `gz-utils2`, `protobuf`,
  `pybind11-vendor`). `meta-ros-common`'s `LAYERSERIES_COMPAT` is
  `wrynose` (matches this project exactly) and it depends only on
  `core`/`meta-python`/`openembedded-layer` — all already in this
  build's `bblayers.conf`.
- **`meta-ros2-jazzy` requires `meta-ros2` (base) as a hard
  dependency.** Confirmed via `layer.conf`:
  `LAYERDEPENDS_ros2-jazzy-layer` lists `ros2-layer` (meta-ros2's own
  collection name) alongside `ros-common-layer`. Jazzy is the ROS 2
  distro officially paired with Gazebo Harmonic, matching the
  `gz-sim8` line above.
- **The dynamic-layer pattern is already proven in this exact stack.**
  `meta-ros2`'s own `layer.conf` does exactly what's needed here for
  Qt6: `BBFILES_DYNAMIC += "qt6-layer:${LAYERDIR}/dynamic-layers/
  meta-qt6/recipes-*/*/*.bb ..."`, with a real file at
  `meta-ros2/dynamic-layers/meta-qt6/recipes-python/pyqt6/
  python3-pyqt6_%.bbappend`. The same mechanism, keyed to the
  confirmed real collection name `ros2-jazzy-layer`, lets `px4-msgs`/
  `px4-ros2-cpp` recipes live in meta-px4 and only become visible to
  bitbake when meta-ros is actually present — no hard `LAYERDEPENDS`
  from meta-px4 on meta-ros, so the Renode/STM32H7 track keeps working
  standalone exactly as it does today.
- **`px4_msgs` is real (rosdistro-indexed) but needs a hand-pinned
  SRCREV, not a generic one.** Checked `index.ros.org` directly: all
  ROS distros (Humble/Jazzy/Kilted/Rolling/Lyrical) point at
  `https://github.com/PX4/px4_msgs.git` branch `main` — but the
  package's own docs say to instead "pick the branch that matches the
  PX4 version you fly," and a `release/1.17` branch exists, matching
  this layer's own PX4 pin exactly. A hypothetical future
  superflore-generated meta-ros recipe would most likely track the
  generic `main` pointer, not `release/1.17` — a real version-mismatch
  risk for message compatibility. Hand-pinning in meta-px4 avoids this
  regardless of whether meta-ros ever packages it.
- **`px4_ros2` (the "user nodes/modes" library) is `Auterion/
  px4-ros2-interface-lib`, not `PX4/px4_ros_com`.** Checked both repos
  directly via the GitHub API. `PX4/px4_ros_com` ("ROS2/ROS interface
  with PX4 through a Fast-RTPS bridge") is a different, older package
  from a pre-uXRCE-DDS era. `Auterion/px4-ros2-interface-lib`
  ("Library to interface with PX4 from a companion computer using
  ROS 2") is the one matching the diagram's "User Nodes / Modes
  (px4_ros2 C++ library, Offboard, Custom)" box — for writing external
  flight modes that register dynamically with PX4. Its actual
  ROS 2/colcon package name is `px4_ros2_cpp`. It has `release/
  <version>` branches (confirmed `release/1.16` exists; `release/1.17`
  not yet checked) and explicitly requires a matching `px4_msgs` — it
  ships its own `scripts/check-message-compatibility.py`.
- **No Micro XRCE-DDS Agent recipe exists anywhere in this stack**
  (checked meta-px4 and meta-ros directly). This layer already carries
  the client-side counterpart (`microxrceddsclient`/`microcdr`, used
  by the real firmware recipes) from the same upstream org
  (eProsima) — the Agent is `eProsima/Micro-XRCE-DDS-Agent`, a sibling
  repo, and is new, unpackaged work.

## 3. Requirements

- **REQ-1** — Add `meta-ros-common`, `meta-ros2`, and `meta-ros2-jazzy`
  to the `sitl` bitbake-setup configuration's `bblayers.conf`, without
  changing behavior for anyone who doesn't add them (meta-px4 itself
  must not gain a hard `LAYERDEPENDS` on meta-ros).
- **REQ-2** — `px4-autopilot` (or a variant) gains `DEPENDS` on
  `gz-transport13`, `gz-sim8`, `gz-sensors8`, `gz-plugin2`, `sdformat`,
  and `export GZ_DISTRO = "harmonic"`; its `gz_bridge`/`gz_plugins`
  modules build for real — verified by inspecting the actual build
  output/binary (checking for the real module, not the stub's "ERROR:
  Gazebo simulation dependencies not found!" string), not by trusting
  Kconfig alone.
- **REQ-3** — `px4-msgs` recipe in
  `meta-px4/dynamic-layers/meta-ros2-jazzy/`, pinned to
  `PX4/px4_msgs.git` at `release/1.17`, builds successfully and is
  only visible to bitbake when `meta-ros2-jazzy` is in `bblayers.conf`.
- **REQ-4** — `px4-ros2-cpp` recipe in the same dynamic-layer location,
  pinned to `Auterion/px4-ros2-interface-lib` at whichever
  release branch matches this layer's PX4 pin (verify `release/1.17`
  exists; fall back to `release/1.16` with the mismatch documented if
  not), builds successfully, and its own
  `check-message-compatibility.py` passes against this layer's
  `px4-msgs` + pinned PX4-Autopilot checkout.
- **REQ-5** — A new `micro-xrce-dds-agent` recipe (plain meta-px4
  recipe, not dynamic-layer-gated, since it has no ROS 2 build-time
  dependency of its own) builds and runs, bridging PX4's
  `uxrce_dds_client` to ROS 2 DDS topics over UDP (port 8888 per the
  diagram).
- **REQ-6** — End-to-end verification: `px4-autopilot` (Gazebo-enabled)
  boots, Gazebo Sim simulates an `x500` quadcopter, QGroundControl
  observes a live MAVLink heartbeat and can arm/fly it, and ROS 2
  topics (`/fmu/in/*`, `/fmu/out/*`) are visible via the Agent.
- **REQ-7** — Document the full build/run workflow for reproducibility
  (new doc or an addition to existing docs).

## 4. Non-goals

- Packaging QGroundControl itself as an OE recipe — run the AppImage
  natively on the operator/dev machine, matching how Renode was
  already scoped as a host prerequisite (spec 000 §4.6).
- Changing anything about the Renode/STM32H7 firmware track (M1-M4) —
  this is an additional, parallel track, not a replacement.
- ROS 2 application-level nodes beyond the bridge/interface libraries
  themselves (the diagram's ArUco tracker, `robot_state_publisher`
  usage, actual custom offboard control code) — out of scope for this
  milestone's gate; `px4-ros2-cpp` building and passing its own
  compatibility check is the bar, not a full perception pipeline.
- Real hardware (M5) — this is pursued instead of/alongside it
  specifically because hardware isn't available, not a replacement for
  it if hardware becomes available later.

## 5. Design sketch

### 5.1 Layer layout

```
meta-px4/
├── dynamic-layers/
│   └── meta-ros2-jazzy/          # only active when meta-ros2-jazzy is in bblayers.conf
│       └── recipes-px4/
│           ├── px4-msgs/
│           └── px4-ros2-cpp/
└── recipes-px4/
    └── micro-xrce-dds-agent/     # plain recipe, no dynamic-layer gating needed
```

### 5.2 `GZ_DISTRO=harmonic` pinning

PX4's `gz_bridge`/`gz_plugins` CMakeLists special-case
`$ENV{GZ_DISTRO} == "harmonic"` to search for the exact versioned
package names (`gz-sim8`, `gz-sensors8`, `gz-plugin2`,
`gz-transport13`) that `meta-ros-common` actually provides, instead of
the generic unversioned `find_package(gz-sim)` (which would resolve
against whatever the newest installed major version is — `gz-sim9`/
`gz-sim10` also exist in meta-ros-common, for Ionic/newer). Exporting
this in the recipe removes the guesswork.

## 6. Implementation record

### REQ-1 / REQ-2 (AC-1) — complete

`bblayers.conf` gained `meta-ros-common`, `meta-ros2`, `meta-ros2-jazzy`
(REQ-1), plus `meta-openembedded/meta-multimedia` (for `ffmpeg`, a
`gz-common5` dependency) and `meta-qt5` (for `gz-gui8`/`gz-sim8`'s hard,
unconditional Qt5 dependency — checked directly against upstream
`gz-sim`'s `CMakeLists.txt`; there is no headless-only build flag).
`local.conf` gained `LICENSE_FLAGS_ACCEPTED = "commercial"` (`ffmpeg`'s
patent-encumbered codecs) and removed the `ptest` `DISTRO_FEATURE` and
`create-spdx` `INHERIT` class — both unrelated, OE-core-default QA/SBOM
features that failed on `bluez5`/`openscenegraph` respectively and
aren't needed for a local SITL build. None of this is tracked in git
(build-directory config).

A new `px4-autopilot-gz_1.17.0.bb` recipe (in
`recipes-px4/px4-autopilot/`, alongside the base `px4-autopilot`
recipe it `require`s `.inc` from) adds `DEPENDS` on `gz-transport13`,
`gz-sim8`, `gz-sensors8`, `gz-plugin2`, `sdformat`, `protobuf-native`,
`opencv`, plus `export GZ_DISTRO = "harmonic"` and `inherit cmake_qt5`.
Verified for real (not just a successful `bitbake` exit code) by
extracting the built `.ipk` and confirming `./opt/px4/bin/px4-gz_bridge`
and real plugin `.so` files (`libOpticalFlow.so`,
`libOpticalFlowSystem.so`, `libBuoyancySystemPlugin.so`, plus
`moving_platform_controller`/`generic_motor`/`spacecraft_thruster`
plugin dirs and Gazebo world/model `.sdf` files) are present — not the
stub "ERROR: Gazebo simulation dependencies not found!" targets.

Getting a clean build required five additional patches
(`recipes-px4/px4-autopilot/px4-autopilot-gz/000{6,7,8,9}-*.patch` plus
a `CMAKE_PROJECT_INCLUDE` workaround file), each a narrow, targeted fix
for a real, reproducible build failure — not guessed:

- **Shadowed recipes.** `meta-ros-common` carries its own
  `pymavlink_2.4.15.bb` (identical `PN`+`PV`) at a higher
  `BBFILE_PRIORITY` than meta-px4, silently winning the provider race
  and making meta-px4's own patched copy (fixing a
  `setup_requires=['future']` live-`pip`-fetch failure) dead code that
  never actually applied. Fixed by converting meta-px4's recipe into a
  `pymavlink_%.bbappend` targeting the winning recipe instead (same
  fix shape as an unrelated, pre-existing `gts` issue found the same
  way — its `SRC_URI` pointed at Debian's long-retired Alioth git
  hosting, moved to `salsa.debian.org`). Checked meta-px4's other
  same-name recipes (`python3-empy`, `python3-lark-parser`) against
  meta-ros-common's copies too — both turned out to be identical,
  harmless dead duplicates, not shadowed bugs.
- **`gz-sim8`'s own generated CMake config bug.** Its
  `gz-sim8-config.cmake` calls `find_package(gz-gui8)` *before*
  `find_package(Qt5 COMPONENTS Core;Quick;QuickControls2)`, even though
  `gz-gui8`'s exported targets link against `Qt5::Core` — a real
  ordering bug in gz-cmake's generated output, confirmed by reading the
  installed `.cmake` file directly. Worked around via
  `-DCMAKE_PROJECT_INCLUDE=<file>` injecting an early
  `find_package(Qt5 COMPONENTS Core Quick QuickControls2 REQUIRED)`
  right after PX4's own top-level `project()` call, rather than
  patching generated gz-cmake output.
- **Qt5 CMake integration.** Separately, meta-qt5's own
  `Qt5Config.cmake` silently no-ops (skips defining any `Qt5::*`
  imported targets) unless `OE_QMAKE_PATH_EXTERNAL_HOST_BINS` and
  friends are set — these only get passed via `cmake_qt5.bbclass`
  (`inherit`ed, not just `DEPENDS`-ed).
- **Live network fetch during `do_compile`.**
  `gz_plugins/optical_flow.cmake` does its own `ExternalProject_Add`
  git clone of `PX4/PX4-OpticalFlow` (plus that repo's own
  `klt_feature_tracker` submodule) at build time — fails outright in a
  network-isolated bitbake sandbox, same class of problem as the
  `uxrce_dds_client`/CycloneDDS `idlc` live-fetches patches 0002/0003
  already solve for the base `px4-autopilot` recipe. Fixed by fetching
  `PX4-OpticalFlow` via this recipe's own `SRC_URI` (`gitsm://`, so
  `do_fetch` — which has real network access — also pulls the
  submodule), then patching `optical_flow.cmake` to use `SOURCE_DIR
  <local-checkout>` + `DOWNLOAD_COMMAND ""` when
  `-DPX4_OPTICALFLOW_SOURCE_DIR` is set, skipping
  `ExternalProject_Add`'s git/submodule machinery entirely rather than
  trying to make it succeed against a local mirror (its `.gitmodules`
  URL is unaffected by cloning the parent repo from a local path, so a
  naive "just clone locally" fix doesn't reach the submodule problem).
  Also needed `-DCMAKE_TOOLCHAIN_FILE` passed through to
  `ExternalProject_Add`'s `CMAKE_ARGS`, since it runs a fully separate
  `cmake` invocation that doesn't inherit the parent build's
  cross-toolchain/sysroot settings on its own (surfaced as "OpenCV not
  found" even though it's a real `DEPENDS`).
- **C++ standard mismatch.** Newer `protobuf` ships headers that
  include abseil, which hard-requires C++17
  (`absl/base/policy_checks.h`); PX4's project-wide
  `CMAKE_CXX_STANDARD` is 14. Overrode just the `px4_gz_msgs` target
  (`target_compile_features(... PUBLIC cxx_std_17)`) rather than
  bumping the whole project's standard.
- **Third-party-header warnings as errors.** `gz-math7`'s own headers
  (`Quaternion.hh`) do implicit float→double promotions, and OpenCV's
  (`matx.inl.hpp`) compare floats with `==`; PX4 builds with
  `-Werror=double-promotion`/`-Werror=float-equal` project-wide, so
  both became hard errors the first time any `gz_plugins` or
  `gz_bridge` code touched those headers (hit across
  `MovingPlatformController.cpp`, `OpticalFlowSensor.cpp`,
  `OpticalFlowSystem.cpp`, and `GZBridge.cpp` — not specific to any one
  file). Silenced via directory-scoped `add_compile_options` in
  `gz_plugins/CMakeLists.txt` and `gz_bridge/CMakeLists.txt`, covering
  every current and future plugin/bridge target in those directories
  rather than patching each file individually.

Also hit and fixed, unrelated to any specific patch: `rm_work` only
actually prunes a recipe's `tmp/work/<recipe>` once that recipe's own
`do_build` task runs, which never happens for dependency-only recipes
(`llvm`, `openssl`, `mesa`, `boost`, etc.) in a single-recipe (not
image) build — `tmp/work` grew to 48G+ across several iterations before
this was understood; recovered via a full `tmp/` wipe (safe: `sstate-cache`
and `DL_DIR` live outside `tmp/` and are untouched, so nothing already
built was lost) rather than the partial `tmp/work`-only wipe tried
first, which desynced `tmp/stamps` from the (now-empty) `tmp/work` and
caused a second round of unrelated failures (`zlib`, `gcc-runtime`
`do_package_write_ipk` failing on missing `packages-split/` paths).

## 7. Acceptance criteria

- **AC-1** — MET. `bitbake px4-autopilot-gz` succeeds with
  `meta-ros-common`/`meta-ros2`/`meta-ros2-jazzy` in `bblayers.conf`,
  and the resulting binary's `gz_bridge`/`gz_plugins` modules are real,
  not stubs — verified against the actual installed `.ipk` contents,
  see §6 (REQ-1, REQ-2).
- **AC-2** — `px4-msgs` and `px4-ros2-cpp` build successfully as
  meta-ros2-jazzy-gated dynamic-layer recipes in meta-px4, and are
  invisible to a build that doesn't include meta-ros (REQ-3, REQ-4).
- **AC-3** — `micro-xrce-dds-agent` builds and runs, observed bridging
  real DDS traffic between PX4 and a ROS 2 node (REQ-5).
- **AC-4** — A real, host-side QGroundControl instance observes a live
  heartbeat and arms/flies the Gazebo-simulated `x500`, with ROS 2
  topics visible via the Agent (REQ-6).

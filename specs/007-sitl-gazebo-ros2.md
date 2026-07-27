# Spec 007 (M7): `px4-autopilot` SITL + Gazebo + ROS 2 + QGroundControl

- **Status:** In progress. Scoping done against real evidence (§2); no
  code changes yet.
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

_To be filled in as work proceeds._

## 7. Acceptance criteria

- **AC-1** — `bitbake mc:...:px4-autopilot` (or equivalent) succeeds
  with `meta-ros-common`/`meta-ros2`/`meta-ros2-jazzy` in
  `bblayers.conf`, and the resulting binary's `gz_bridge`/`gz_plugins`
  modules are real, not stubs (REQ-1, REQ-2).
- **AC-2** — `px4-msgs` and `px4-ros2-cpp` build successfully as
  meta-ros2-jazzy-gated dynamic-layer recipes in meta-px4, and are
  invisible to a build that doesn't include meta-ros (REQ-3, REQ-4).
- **AC-3** — `micro-xrce-dds-agent` builds and runs, observed bridging
  real DDS traffic between PX4 and a ROS 2 node (REQ-5).
- **AC-4** — A real, host-side QGroundControl instance observes a live
  heartbeat and arms/flies the Gazebo-simulated `x500`, with ROS 2
  topics visible via the Agent (REQ-6).

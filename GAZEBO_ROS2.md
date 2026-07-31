# Building the SITL + Gazebo + ROS 2 track

This is the practical how-to for building PX4's posix/SITL flight
stack with real Gazebo Harmonic simulation support, plus the ROS 2
message/interface libraries and the host-side DDS-XRCE agent needed to
bridge it to ROS 2. For the full technical narrative (why each piece
is needed, every build failure hit and how it was actually fixed, real
evidence) see [specs/007-sitl-gazebo-ros2.md](specs/007-sitl-gazebo-ros2.md).

This does **not** cover the Renode/STM32H7 firmware track — that's a
separate, parallel track; see [SIMULATION.md](SIMULATION.md).

## 1. Overview

Five recipes, building on top of the existing `px4-autopilot` posix/SITL
recipe:

| Recipe | Location | Purpose |
|---|---|---|
| `px4-autopilot-gz` | `recipes-px4/px4-autopilot/` | Same PX4 source as `px4-autopilot`, but with `gz_bridge`/`gz_msgs`/`gz_plugins` built for real against Gazebo Harmonic, instead of `px4-autopilot`'s stub targets. |
| `px4-msgs` | `dynamic-layers/meta-ros2-jazzy/recipes-px4/` | ROS 2 message definitions matching PX4's uORB topics. |
| `px4-ros2-cpp` | `dynamic-layers/meta-ros2-jazzy/recipes-px4/` | C++ library for writing ROS 2 nodes (offboard control, custom modes) that talk to PX4. |
| `micro-xrce-dds-agent` | `dynamic-layers/meta-ros2-jazzy/recipes-px4/` | Host/companion-side counterpart to PX4's `uxrce_dds_client` — bridges the DDS-XRCE wire protocol to real DDS (ROS 2's `rmw_fastrtps`). |

The three dynamic-layer recipes only exist when `meta-ros2-jazzy` is in
`bblayers.conf` — they're invisible otherwise, and meta-px4 itself
gains no hard dependency on meta-ros.

## 2. Prerequisites

### 2.1 meta-ros layers

You need [meta-ros](https://github.com/ros/meta-ros)'s `wrynose`
branch, with `meta-ros-common`, `meta-ros2`, and `meta-ros2-jazzy`
added to `bblayers.conf` (in that order, alongside the usual
`openembedded-core`/`meta-openembedded`/`meta-px4` layers):

```
BBLAYERS += " \
    /path/to/meta-ros/meta-ros-common \
    /path/to/meta-ros/meta-ros2 \
    /path/to/meta-ros/meta-ros2-jazzy \
    "
```

### 2.2 meta-multimedia and meta-qt5

Gazebo Harmonic (via `meta-ros-common`'s `gz-*` recipes) needs
`ffmpeg` (from `meta-openembedded/meta-multimedia`) and Qt5 (`gz-gui8`
is a hard, unconditional dependency of `gz-sim8` — there is no
headless-only build option, checked directly against upstream
`gz-sim`'s `CMakeLists.txt`):

```
BBLAYERS += " \
    /path/to/meta-openembedded/meta-multimedia \
    /path/to/meta-qt5 \
    "
```

`meta-qt5` is a separate layer, not part of meta-openembedded:
`https://github.com/meta-qt5/meta-qt5.git` (its `LAYERSERIES_COMPAT`
includes `wrynose`).

### 2.3 local.conf additions

```
# ffmpeg's patent-encumbered codecs are gated behind an explicit accept.
# This is a local SITL simulation build, never distributed, so accepting
# it here is fine.
LICENSE_FLAGS_ACCEPTED = "commercial"

# Both are unrelated distro-level QA/compliance features (OE-core's own
# defaults) that fail on specific packages in this dependency graph and
# aren't needed for a local SITL build -- see spec 007 REQ-2's
# implementation record for exactly what broke and why.
DISTRO_FEATURES:remove = "ptest"
INHERIT:remove = "create-spdx"
```

### 2.4 QGroundControl (host prerequisite, not an OE recipe)

Like Renode (see `specs/000-architecture.md` §4.6), QGroundControl is
deliberately **not** built or packaged by this layer — run the AppImage
natively on the operator/dev machine:
<https://qgroundcontrol.com/downloads/>. PX4's SITL build already
speaks MAVLink over UDP with no changes needed on the PX4 side.

## 3. Building

```sh
bitbake px4-autopilot-gz
bitbake px4-msgs
bitbake px4-ros2-cpp
bitbake micro-xrce-dds-agent
```

Each has been verified to build successfully and produce real (not
stub/empty) artifacts — confirmed by extracting the built `.ipk`
packages and checking their contents directly, not just a successful
`bitbake` exit code. See spec 007 §6 for exactly what was checked in
each case:

- `px4-autopilot-gz`: `/opt/px4/bin/px4-gz_bridge`, real plugin `.so`
  files (`libOpticalFlow.so`, `libOpticalFlowSystem.so`,
  `libBuoyancySystemPlugin.so`, etc.), Gazebo world/model `.sdf` files.
- `px4-msgs`: all `rosidl`-generated `.so` files land in the main
  package.
- `px4-ros2-cpp`: `libpx4_ros2_cpp.so`, plus its own
  `check-message-compatibility.py` passes against this project's exact
  PX4-Autopilot pin.
- `micro-xrce-dds-agent`: a real, correctly linked `MicroXRCEAgent`
  executable and versioned `libmicroxrcedds_agent.so`.

A first build of `px4-autopilot-gz` compiles the entire Gazebo Harmonic
stack (gz-sim, gz-rendering, gz-physics, dartsim, ogre, Qt5, etc.) from
source and takes a long time. Watch disk space closely on the first
attempt — see the note below.

### 3.1 Disk space note

`rm_work` (if enabled) only prunes a recipe's `tmp/work/<recipe>`
directory once that recipe's own `do_build` task actually runs, which
never happens for dependency-only recipes (`llvm`, `openssl`, `mesa`,
`boost`, everything Gazebo/Qt5 pulls in) when building a single recipe
rather than an image. `tmp/work` can grow to 40-50GB+ across a few
build iterations before this becomes a problem. If disk runs low, a
full `rm -rf tmp/` is safe (not just `tmp/work/`, which desyncs
`tmp/stamps` and causes a different class of failure) — `sstate-cache`
and `DL_DIR` live outside `tmp/` by default and preserve all
already-built output, so nothing is lost.

## 4. Running it (REQ-6 — not yet done)

Actually exercising these recipes at runtime — booting PX4 SITL with
Gazebo, starting `MicroXRCEAgent`, connecting a host QGroundControl,
and observing ROS 2 topics — needs a real bootable target image
(this project's own established pattern for anything beyond
`bitbake <recipe>`; see the main [README.md](README.md)'s own
`core-image-minimal` + kas quick start). No such image has been
assembled for this SITL+Gazebo+ROS2 combination yet: it would need a
new (or extended) image recipe with `px4-autopilot-gz` and
`micro-xrce-dds-agent` in `IMAGE_INSTALL`, `runqemu` (or equivalent)
to boot it, and a plan for how Gazebo Sim itself gets run (either
inside that same image alongside `px4-autopilot-gz`, communicating
over `gz-transport`, or on the host with the image reaching it over
the network) and how QGroundControl on the host reaches the image's
forwarded MAVLink UDP port.

This is real, scoped remaining work, not something that can be
documented as "already working" — see spec 007 REQ-6 and AC-3/AC-4.

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

## 4. Running it (REQ-6)

Two independent ways to actually exercise PX4 SITL + Gazebo + ROS 2 at
runtime, both built from the recipes in section 3 plus two new image
recipes and a `qgroundcontrol-appimage` recipe. Pick whichever fits —
they don't depend on each other.

| | Scenario 1: all-in-one container | Scenario 2: QEMU + host tools |
|---|---|---|
| PX4 SITL + Gazebo + MicroXRCEAgent + QGroundControl | all inside one container image | PX4 SITL + MicroXRCEAgent inside a QEMU guest; Gazebo and QGroundControl run on the host |
| Image recipe | `px4-sitl-gazebo-qgc-image` | `px4-sitl-qemu-image` |
| GUI transport | X11 forwarding from container to host | native (Gazebo/QGroundControl run directly on the host) |
| Networking | `--network=host` | tap device bridging the guest onto the host |

Both scenarios need the prerequisites from section 2 already in place
(`bblayers.conf`/`local.conf`).

### 4.1 Scenario 1: all-in-one container (SITL + Gazebo + QGroundControl)

Modeled on the container structure used by the [ROSCon 2025 PX4
workshop](https://github.com/Dronecode/roscon-25-workshop/blob/main/docs/setup.md):
one container holding the flight stack, the simulator, and the ground
control station together, with GUI apps reaching the operator's screen
via X11 forwarding rather than VNC/noVNC (that workshop's own primary
method too).

New recipes this pulls in beyond section 3:

| Recipe | Location | Purpose |
|---|---|---|
| `qgroundcontrol-appimage` | `recipes-graphics/qgroundcontrol/` | Fetches and repackages the official upstream QGroundControl AppImage (v5.0.8) so it can ship *inside* a target image — the only place in this layer QGroundControl is actually built/packaged rather than run as a host prerequisite (see section 2.4's reasoning, which still applies to every other case). |
| `px4-sitl-launch-scripts` | `recipes-px4/px4-sitl-launch-scripts/` | One script (`start-sitl-gazebo-qgc.sh`) that sets the `PX4_GZ_*`/`GZ_SIM_*` environment variables px4-autopilot-gz's *installed* layout needs (PX4 itself only generates these into its build tree, never the installed package — see the script's own comments) and starts `MicroXRCEAgent`, QGroundControl, and PX4 SITL in order. |
| `px4-sitl-gazebo-qgc-image` | `recipes-core/images/` | The image itself: `IMAGE_FSTYPES = "container"` (oe-core's built-in `image-container.bbclass` — just tars the rootfs, no kernel/bootloader), `sysvinit`, Mesa + `libgallium` for software GL rendering. |

#### 4.1.1 Building

```sh
bitbake px4-sitl-gazebo-qgc-image
```

Produces `px4-sitl-gazebo-qgc-image-qemux86-64.rootfs.tar.bz2` under
`tmp/deploy/images/qemux86-64/`.

#### 4.1.2 Running

```sh
./scripts/run-container.sh tmp/deploy/images/qemux86-64/px4-sitl-gazebo-qgc-image-qemux86-64.rootfs.tar.bz2
```

This script (see its own header comments for the full reasoning):
loads the tarball with `docker import`/`podman import`, runs `xhost
+local:` so the container's X11 clients (Gazebo's GUI, QGroundControl)
can reach your X server, and starts the container with
`--network=host`, the X11 socket bind-mounted in, and
`LIBGL_ALWAYS_SOFTWARE=1` (software rendering — see the script for the
hardware-acceleration trade-off).

`xhost +local:` is a local security trade-off, same category as
`LICENSE_FLAGS_ACCEPTED = "commercial"` in section 2.3: fine for local
SITL development, not something to leave on for a shared machine.

#### 4.1.3 What to expect

Inside the container, `start-sitl-gazebo-qgc.sh` (run automatically as
the container's entrypoint) starts, in order: `MicroXRCEAgent` (UDP
port 8888), QGroundControl, then PX4 SITL itself — which in turn
launches Gazebo (server + GUI) automatically via its own
`ROMFS/px4fmu_common/init.d-posix/px4-rc.gzsim` startup script, exactly
as it would for a native `make px4_sitl gz_x500` build. You should see
two windows appear on your desktop (Gazebo's GUI with an `x500`
quadrotor on the default world, and QGroundControl), and QGroundControl
should show a live heartbeat/vehicle once PX4 finishes booting.

### 4.2 Scenario 2: PX4 SITL in QEMU, Gazebo + QGroundControl on the host

Here only PX4 SITL (+ MicroXRCEAgent, + the ROS 2 message libraries)
runs inside a target image; Gazebo and QGroundControl are ordinary
host-side applications — the **official** Gazebo Harmonic packages or
container image (not anything built by this layer), and the
QGroundControl AppImage (section 2.4), exactly as if PX4 were running
on real hardware on your LAN rather than in a VM.

#### 4.2.1 Building

```sh
bitbake px4-sitl-qemu-image
```

`px4-sitl-qemu-image` (`recipes-core/images/px4-sitl-qemu-image.bb`) is
a normal bootable image (`IMAGE_FSTYPES = "ext4"`, boots via
`runqemu`), carrying `px4-autopilot-gz`, `micro-xrce-dds-agent`,
`px4-msgs`, and `px4-ros2-cpp` — but deliberately *not* Gazebo or
QGroundControl, which stay host-side in this scenario.

#### 4.2.2 Installing Gazebo Harmonic on the host

Either:

- **Official packages** (Ubuntu/Debian):
  ```sh
  sudo apt install curl lsb-release gnupg
  curl https://packages.osrfoundation.org/gazebo.gpg -o /usr/share/keyrings/pkgs-osrf-archive-keyring.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/pkgs-osrf-archive-keyring.gpg] http://packages.osrfoundation.org/gazebo/ubuntu-stable $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/gazebo-stable.list
  sudo apt update && sudo apt install gz-harmonic
  ```
  (see <https://gazebosim.org/docs/harmonic/install_ubuntu> for the
  authoritative, up-to-date instructions).

- **Official container image**: `docker run --network=host
  -e DISPLAY -v /tmp/.X11-unix:/tmp/.X11-unix:ro
  gzsim/harmonic:latest` (or equivalent) — must also use
  `--network=host` for the same gz-transport-multicast reason as
  Scenario 1's container.

#### 4.2.3 Networking setup

This is the part that actually needs care, and is specific to this
scenario:

**Why not the default QEMU networking:** `runqemu`'s default
"slirp" user-mode networking only forwards TCP/UDP connections the
guest itself initiates (plus explicit `hostfwd` port mappings) — it
cannot carry the UDP **multicast** that Gazebo Transport
(`gz-transport`) uses for host↔guest discovery between the host's
Gazebo and PX4's `gz_bridge` inside the guest. This needs a **tap**
device instead, bridging the guest directly onto a real host network
interface.

**One-time host setup:**
```sh
./scripts/qemu-tap-setup.sh          # sudo runqemu-gen-tapdevs $(id -g) 1
```
This creates `tap0`, owned by your primary group, with the host end at
`192.168.7.1`. `runqemu`'s tap mode passes a matching `ip=` kernel
command-line argument, so the guest kernel self-configures
`192.168.7.2` at boot via IP auto-configuration — no networking setup
needed *inside* the image itself.

**Boot the guest:**
```sh
./scripts/run-qemu-sitl.sh
```

**Inside the guest**, start PX4 with Gazebo left external (do **not**
let PX4 launch its own local Gazebo — the whole point here is
connecting to the host's):
```sh
export PX4_GZ_STANDALONE=1
export PX4_SIM_MODEL=gz_x500
export GZ_IP=192.168.7.2
# Must match whatever GZ_PARTITION the host-side Gazebo uses (or its
# own default) -- gz-transport only discovers peers within the same
# partition, and the default partition name is derived from
# hostname+username, which differs between the guest and the host.
# Set it explicitly on BOTH sides to the same value.
export GZ_PARTITION=px4-qemu-sitl
export PATH=/opt/px4/bin:$PATH
mkdir -p /root/px4_home && cd /root/px4_home
px4 -w /root/px4_home /opt/px4
```
On the host, whatever started Gazebo (section 4.2.2) needs the same
`export GZ_PARTITION=px4-qemu-sitl` before it's launched, plus
`PX4_GZ_WORLDS`/`GZ_SIM_RESOURCE_PATH` pointed at wherever the official
package/container puts PX4's world/model SDF files (not needed if
using PX4's own `Tools/simulation/gz` checkout on the host directly).

Verify gz-transport reachability from the host before worrying about
PX4 itself:
```sh
GZ_PARTITION=px4-qemu-sitl gz topic -l
```
should list a `/world/.../clock` topic once both sides are up.

**MAVLink (QGroundControl):** PX4's GCS MAVLink link binds UDP port
`18570` inside the guest (`ROMFS/px4fmu_common/init.d-posix/px4-rc.mavlink`
— not the older `14550` convention some PX4 docs still reference).
Since QGroundControl's automatic connection scan only probes localhost
and the local broadcast address, it will generally **not** find a
guest across a tap link on its own — add a manual link instead: in
QGroundControl, **Application Settings → Comm Links → Add**, type
UDP, and target `192.168.7.2:18570`.

#### 4.2.4 What to expect

Once both sides are up and `GZ_PARTITION` matches, `gz topic -l` run
on the host should show PX4's simulation topics, the Gazebo GUI window
(running natively on the host) should show the `x500` vehicle, and
QGroundControl (after adding the manual UDP link above) should show a
live heartbeat. ROS 2 topics via `MicroXRCEAgent` are reachable from
*inside* the guest (verify with `uxrce_dds_client status` at the PX4
shell) — bridging those out to a host-side ROS 2 workstation across
the tap network is further networking this document doesn't cover,
since it wasn't part of what was asked for here (Gazebo/QGroundControl
reachability specifically).

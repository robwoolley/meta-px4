# Building ROS 2 packages with colcon

Two ways to build ROS 2 application code against this layer without writing
a bitbake recipe for it:

- **On the target** (M8, [specs/008-ontarget-colcon.md](specs/008-ontarget-colcon.md)) —
  the target compiles its own code. No cross-compilation, no toolchain file,
  no sysroot split. Simple, and slow.
- **In an SDK on your workstation** (M9, [specs/009-sdk-colcon.md](specs/009-sdk-colcon.md)) —
  cross-compile with the host's CPU. Fast, and has more moving parts.

Both build the same acceptance workload — the `jazzy` branch of
[`ros2/examples`](https://github.com/ros2/examples), pinned to commit
`07008852`, which is the `0.19.7` release that meta-ros's own
`examples-*_0.19.7-1` recipes are generated from. That is deliberate: the same
source built three ways (bitbake, on-target colcon, SDK colcon) is what makes
the results comparable.

Prerequisites for both are the meta-ros layer set from
[GAZEBO_ROS2.md §2](GAZEBO_ROS2.md) — this document assumes that build
environment already works.

> **`MACHINE` is not set in the sitl build directory's `local.conf`.** Every
> bitbake command below needs it on the command line, or bitbake fails with an
> unexpanded `${MACHINE}` in its cooker log path:
> `MACHINE=qemux86-64 bitbake ...`

---

## 1. On-target colcon (M8)

### 1.1 What gets built

`packagegroup-px4-ros-dev` aggregates the on-target build tooling: the colcon
extension set, CMake, git, pkg-config, the Python build machinery, and the
ament/rosidl CMake packages a ROS 2 package needs at configure time.

It deliberately does **not** contain the C/C++ compiler. That comes from the
image, via two `IMAGE_FEATURES`:

- `tools-sdk` — gcc, g++, make, binutils on the target.
- `dev-pkgs` — every installed package's `-dev` half. This is the one people
  miss: `find_package(rclcpp)` fails without it even though `librclcpp` is
  installed, because the exported `rclcpp-config.cmake` lives in `rclcpp-dev`.

`px4-ros-dev-image` sets both, installs the packagegroup, and stages the
`ros2/examples` sources into `/home/root/examples_ws/src/examples` (via
`ros2-examples-src.bb`) so the guest needs no network access to build them.

`example_interfaces` is installed as an ordinary binary package rather than
cloned into the workspace — meta-ros2-jazzy already carries
`example-interfaces_0.12.1-1`, and cloning it as source would only make colcon
regenerate message code on the target for no benefit.

### 1.2 Building the image

```sh
MACHINE=qemux86-64 bitbake px4-ros-dev-image
```

This image is much larger than the M7 ones — `tools-sdk` plus `dev-pkgs`
roughly doubles it, and the rootfs is sized at 20 GB to leave room for
colcon's `build/`, `install/` and `log/` trees. It is a development
convenience image, not something to ship.

### 1.3 Running the acceptance workload

```sh
./scripts/run-m8-ontarget-colcon-test.sh
```

The script boots the image under `runqemu` with **slirp** networking (not the
`tap` setup [scripts/run-qemu-sitl.sh](scripts/run-qemu-sitl.sh) needs —
that one carries UDP multicast between host Gazebo and the guest; here the only
host→guest traffic is an ssh session), waits for ssh, then runs:

1. a toolchain sanity check (`cmake`, `g++`, `colcon`, `AMENT_PREFIX_PATH`);
2. `colcon build` over the staged workspace;
3. a check that the expected executables actually exist under `install/` —
   colcon's exit code alone is not evidence;
4. a colcon-built publisher against the **bitbake-packaged** subscriber from
   `/opt/ros/jazzy`, which is where an ABI mismatch between the workspace
   overlay and the packaged ROS 2 would show up.

Memory and core count matter here in a way they don't for the M7 images: this
guest is compiling C++, not just running it. The defaults are 8 GB and 4 cores
with `--parallel-workers 2`; the failure mode when parallelism is too high is
an OOM kill partway through a long build.

With KVM this is much less painful than it sounds — measured on an 8-core
host, 8 GB / 4 cores to the guest:

| Step | Time |
|---|---|
| boot to usable ssh | 15 s |
| `colcon build`, all 22 packages | 2 min 11 s |

The script falls back to TCG automatically if `/dev/kvm` is not accessible,
and *that* is the slow path — check your access before blaming the guest.
Note that access can come from a file ACL on `/dev/kvm` rather than
membership in the `kvm` group, so `id` is not the last word: try opening it.

```sh
QEMU_MEM=12288 QEMU_SMP=6 COLCON_WORKERS=3 ./scripts/run-m8-ontarget-colcon-test.sh
KEEP_RUNNING=1 ./scripts/run-m8-ontarget-colcon-test.sh   # leave the guest up
```

### 1.4 Doing it by hand

```sh
# in the guest
. /opt/ros/jazzy/setup.sh
cd /home/root/examples_ws
colcon build --parallel-workers 2 --cmake-args -DBUILD_TESTING=OFF
. install/setup.sh
ros2 run examples_rclcpp_minimal_publisher publisher_member_function
```

---

## 2. SDK colcon (M9)

### 2.1 Which image

Upstream's own `ros2-image-sdktest` is the SDK vehicle, not an image of this
layer's. That is not laziness: `meta-ros2-jazzy` defines
`ROS_SDK_HOST_PACKAGES` and `ROS_SDK_TARGET_PACKAGES` in
`conf/ros-distro/include/jazzy/ros-sdk.inc`, and `ros2-image-sdktest` is the
**only** consumer of them anywhere in the tree. Building the SDK from any
other image gets none of the ROS content — the package sets are variables that
something has to opt into.

This layer extends that image through
`dynamic-layers/meta-ros2-jazzy/recipes-bbappends/images/ros2-image-sdktest.bbappend`,
which adds:

- `nativesdk-ros-sdk-env` — the SDK environment variables (§2.3);
- `px4-msgs`, `px4-ros2-cpp`, `micro-xrce-dds-agent` to the target sysroot, so
  the SDK can cross-build against the PX4 interface libraries and not just
  stock ROS 2.

Both are gated on `PX4_ROS_SDK_EXTRAS`, so upstream's unmodified baseline stays
reproducible:

```sh
PX4_ROS_SDK_EXTRAS=0 MACHINE=qemux86-64 bitbake ros2-image-sdktest -c populate_sdk
```

### 2.2 Building and installing the SDK

```sh
MACHINE=qemux86-64 bitbake ros2-image-sdktest -c populate_sdk
```

The installer lands in `tmp/deploy/sdk/`. Install and use it the usual way:

```sh
sh tmp/deploy/sdk/*ros2-image-sdktest*.sh -y -d ~/px4-ros-sdk
. ~/px4-ros-sdk/environment-setup-*
```

### 2.3 Two deltas this layer carries from upstream

Neither of these is in the meta-ros branch this project builds against, and
both should be dropped when they land upstream.

**`ros-sdk-env`** — carried from
[meta-ros PR #1261](https://github.com/ros/meta-ros/pull/1261), which is still
open. It generates a script that the SDK sources at setup time, exporting
`PYTHON_SOABI`, `PYTHON3_NUMPY_INCLUDE_DIR`, `PYTHONWARNINGS`,
`AMENT_SKIP_SHELL_PATH` and a target `PYTHONPATH`. Without it, every colcon
invocation needs those hand-exported — which is exactly what
[PR #1215](https://github.com/ros/meta-ros/pull/1215)'s instructions do, with a
hardcoded `cpython-310-aarch64-linux-gnu` that is wrong for any other target.
The recipe computes the value from the machine configuration instead.

**`skip_shell_path.patch` for jazzy's `ament-package`** — meta-ros carries this
patch only under `meta-ros2-kilted`; jazzy has no `ament-package` bbappend at
all. It is what makes `AMENT_SKIP_SHELL_PATH` mean anything: without it, ament
prepends `$OECORE_TARGET_SYSROOT/opt/ros/jazzy/bin` to your **host** `PATH`
when the SDK environment is sourced, pointing host shells at target binaries.
The patch was re-cut against jazzy's pinned `ament-package` 0.16.5-1 so it
applies without offset.

The bbappend is `%`-versioned where kilted's is pinned. A pinned append stops
matching after a version bump and silently restores the bug; a `%` append
against a patch cut for one SRCREV fails the build loudly instead, which is the
better outcome for something whose symptom is otherwise invisible.

> Both live in `dynamic-layers/meta-ros2-jazzy/`, which means meta-px4 still
> has no hard `LAYERDEPENDS` on meta-ros — the Renode/STM32H7 track builds
> exactly as before. That gating also required extending meta-px4's
> `BBFILES_DYNAMIC` to match `*.bbappend`, not just `*.bb`: bitbake ignores an
> unmatched bbappend **silently**, with no warning.

### 2.4 Running the acceptance workload

```sh
./scripts/run-m9-sdk-colcon-test.sh
```

It installs the SDK, then checks:

- **the environment** — `colcon` on `PATH`, `OE_CMAKE_TOOLCHAIN_FILE` set, and
  `PYTHON_SOABI` populated from `ros-sdk-env` rather than by hand;
- **the `PATH`** — that it did *not* gain the target sysroot's ROS bin
  directory. This is the check that proves `skip_shell_path.patch` does
  something, as opposed to merely applying cleanly;
- **the cross-build** — `colcon build` with the SDK's toolchain file and no
  hand-exported variables;
- **the artifacts** — that `file` reports target ELFs, not host binaries.

### 2.5 Doing it by hand

```sh
. ~/px4-ros-sdk/environment-setup-*
git clone https://github.com/ros2/examples.git -b jazzy ws/src/examples
cd ws
colcon build --cmake-args \
    -DCMAKE_TOOLCHAIN_FILE=${OE_CMAKE_TOOLCHAIN_FILE} \
    -DBUILD_TESTING=OFF
```

Note there is no `-DPYTHON_SOABI=...` here, and no `example_interfaces` clone.
Both are handled — the first by `ros-sdk-env`, the second by
`example-interfaces` being installed into the SDK's target sysroot by this
layer's `ros2-image-sdktest.bbappend`. It is **not** in upstream's
`ROS_SDK_TARGET_PACKAGES`, so without that addition every action, service and
client example fails to configure and you get 0 of 22 packages built.

`ros-sdk-env` also supplies three things a stock OE SDK does not, each of
which was a real failure before it did (see spec 009 §6):

| variable | why it matters |
|---|---|
| `OE_CMAKE_TOOLCHAIN_FILE` | the SDK ships a toolchain file but names it in no variable, so the command above would pass an empty path |
| `PYTHON_SOABI` | derived from the target sysroot; the parse-time computation yields an empty architecture field under `class-nativesdk` |
| `AMENT_PREFIX_PATH` | comes from sourcing the target's `setup.bash`, enabled by `ROS_SDK_UNIFY` (defaulted to `bash` here) |

### 2.6 The thing that makes SDK builds subtle

The `ament`/`rosidl` code generators run as **host** Python out of
`OECORE_NATIVE_SYSROOT`, while emitting and compiling code against **target**
headers from `OECORE_TARGET_SYSROOT`. Essentially every failure mode in this
milestone is some variation of *the wrong sysroot's copy won* — a host Python
importing target site-packages, a generator finding host headers, `PATH`
picking up target binaries. When something breaks here, that is the first
thing to check.

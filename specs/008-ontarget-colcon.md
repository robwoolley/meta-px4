# Spec 008 (M8): on-target colcon build tooling packagegroup

- **Status: Done.** All five requirements complete and all four
  acceptance criteria met against real runs, not inferred — see §6.
  `colcon build` of the `jazzy` branch of `ros2/examples` finishes all
  22 packages in the guest in **2 min 11 s**, and a colcon-built
  publisher exchanges live messages with the bitbake-packaged
  subscriber. Three of the four problems found along the way were only
  discoverable by building on the target (§6), which is exactly the
  argument §1 makes for having this milestone at all.
- **Created:** 2026-08-18
- **Depends on:** [000-architecture.md](000-architecture.md) (M8 row),
  [007-sitl-gazebo-ros2.md](007-sitl-gazebo-ros2.md) (delivers the
  `meta-ros2-jazzy` dynamic-layer wiring, the `bblayers.conf` layer set,
  and `px4-sitl-qemu-image` — the image this milestone extends).
- **Delivers:** A `packagegroup-px4-ros-dev` recipe (plus an image that
  installs it) giving a *target* rootfs everything needed to build ROS 2
  packages with `colcon` **on the target itself** — native compiler,
  CMake, the colcon extension set, and the ament/rosidl CMake machinery
  — validated by building the `jazzy` branch of
  [`ros2/examples`](https://github.com/ros2/examples) in the running
  guest and executing a resulting node.

## 1. Rationale

Everything M7 delivered is cross-built by bitbake: recipes are written,
`bitbake` runs, artifacts land in an image. That is the right model for
shipping, but it is a poor fit for the inner loop of *application*
development — writing an offboard controller against `px4-ros2-cpp`
means editing C++ and wanting to see it run, not authoring a `.bb` file
and waiting on a cross-build. Two escape hatches exist for that loop:
build on the target, or build on the host against an SDK. This
milestone is the first; [009](009-sdk-colcon.md) is the second, and
they share an acceptance workload (`ros2/examples` under `colcon`) so
their results are directly comparable.

On-target is the simpler of the two and worth having first: there is no
cross-compilation, no toolchain file, no sysroot separation, and no
`PYTHON_SOABI` guesswork — `colcon build` on the target is the same
invocation an upstream ROS 2 developer runs on a workstation. It is
also the honest way to find out whether this layer's ROS 2 packaging is
actually *complete*: a cross-build only needs whatever the recipe's
`DEPENDS` names, whereas an on-target build fails loudly the moment a
`-dev` package, a CMake config file, or an `ament_index` entry is
missing from the image.

## 2. Findings (checked against the real layers, not assumed)

Paths below are relative to the `meta-ros` checkout materialized at
`oe-px4/bitbake-builds/oe-px4-sitl/layers/meta-ros` (branch `wrynose`,
HEAD `f4a5e4800a`).

- **colcon is already fully packaged for the target — no new recipes
  needed.** `meta-ros-common/recipes-devtools/colcon/` carries 19
  recipes (`python3-colcon-core_0.18.3`, `-ros_0.5.0`, `-cmake_0.2.28`,
  `-bash_0.5.0`, `-cd`, `-defaults`, `-devtools`, `-library-path`,
  `-metadata`, `-notification`, `-output`, `-package-information`,
  `-package-selection`, `-parallel-executor`, `-pkg-config`,
  `-python-setup-py`, `-recursive-crawl`, `-test-result`, and the
  `python3-colcon-common-extensions_0.3.0` aggregator). Every one of
  them is a **target** recipe: they say `BBCLASSEXTEND += "nativesdk"`
  (`python3-colcon-core` says `BBCLASSEXTEND = "native nativesdk"`),
  which *extends* the default target variant rather than replacing it.
  So `IMAGE_INSTALL += "python3-colcon-common-extensions"` already
  works today; this milestone's packagegroup aggregates, it does not
  package colcon from scratch.
- **`python3-colcon-common-extensions` does not pull in everything.**
  Its `RDEPENDS` lists 15 of the 19 — it omits `-notification`,
  `-pkg-config`, and `-python-setup-py`. `-python-setup-py` in
  particular is what builds `ament_python` packages, which is exactly
  half of `ros2/examples` (the `rclpy` side), so the packagegroup must
  name it explicitly rather than relying on the aggregator.
- **`python3-colcon-notification` is broken, and its omission upstream
  is load-bearing.** The in-recipe `TODO` comment reads as though the
  aggregator's package list is merely incomplete. It is not:
  `colcon-notification` 0.3.0's `setup.py` does `from pkg_resources
  import parse_version`, and `pkg_resources` is not present in the
  setuptools this build uses, so `do_compile` fails with
  `ModuleNotFoundError`. Found by adding it to the packagegroup and
  watching the image build fail on it (§6) — not by reading the recipe.
  Excluded deliberately: fixing it belongs upstream, and all it
  provides is desktop notification popups on build completion, which
  is meaningless on a headless target.
- **No existing packagegroup covers build tooling.** meta-ros's
  packagegroups (`packagegroup-ros-world`,
  `packagegroup-ros-turtlebot3-core`/`-extended`, and the
  `packagegroup-spaceros-jazzy-*` set) are all *runtime* package
  aggregations. Nothing in meta-ros assembles a
  build-ROS-on-the-target set, so this is genuinely new work rather
  than a re-export of something upstream already has.
- **`ROS_SDK_TARGET_PACKAGES` is the right content list, and it is
  already written.** `meta-ros2-jazzy/conf/ros-distro/include/jazzy/
  ros-sdk.inc` defines it, and that layer's `layer.conf` `require`s the
  file globally — so the variable is set for *any* recipe, not just for
  the SDK image that is upstream's only other consumer of it. It names
  ~20 interdependent `ament-cmake-*` packages plus `rclcpp` and
  friends, the interface packages, and the rosidl generators. A ROS 2
  build needs that whole set whether it runs on the target or in an
  SDK, so this packagegroup consumes the variable rather than
  hand-listing (see §6 for what hand-listing actually cost).
- **`ros2-image-sdktest.bb` is the closest existing reference, and it
  is aimed elsewhere.** `meta-ros2/recipes-core/images/
  ros2-image-sdktest.bb` defines `ROS_SDK_EXTRA_INSTALL` (boost,
  bullet, `eigen3-cmake-module`, `libeigen`, `libstdc++-staticdev`,
  `opencv-staticdev`, `orocos-kdl`, `pcl-dev`, `pybind11-vendor`,
  `python-cmake-module`, `python3-numpy-staticdev`, `python3-opencv`,
  `python3-pykdl`, `qhull-staticdev`, `rttest`, `tlsf-staticdev`,
  `tlsf-cpp`, `tinyxml-vendor`, `yaml-cpp-vendor`). That list is a
  useful inventory of what ROS 2 builds actually reach for, but the
  image exists to populate an **SDK** sysroot (see
  [009](009-sdk-colcon.md)), so it installs no compiler, no CMake, and
  no colcon on the target itself. It is a starting point for the
  packagegroup's contents, not a substitute for it.
- **ROS 2 installs under `/opt/ros/${ROS_DISTRO}` on target.**
  `meta-ros-common/classes/ros_opt_prefix.bbclass` sets
  `ros_base_prefix ?= "/opt/ros/${ROS_DISTRO}"`. On-target colcon must
  therefore source `/opt/ros/jazzy/setup.bash` before building, and the
  resulting workspace overlays that prefix rather than replacing it —
  it is not the plain `/usr` layout an unprefixed OE image would
  suggest.
- **`ros2/examples` has a real `jazzy` branch, matching this project's
  ROS distro.** Confirmed against the GitHub API: branches include
  `humble`, `iron`, `jazzy`, `kilted`, `lyrical`, `rolling`. The
  `jazzy` tree contains `rclcpp/`, `rclpy/`, and `launch_testing/`
  package directories.
- **`example_interfaces` is already packaged for jazzy, so no second
  clone is needed.** PR 1215's build instructions clone
  `ros2/example_interfaces` separately into the examples workspace —
  necessary in its humble-era context, but not here:
  `meta-ros2-jazzy/generated-recipes/example-interfaces/
  example-interfaces_0.12.1-1.bb` exists (with a matching bbappend), so
  installing it into the image satisfies the action/service examples'
  message dependency from the target sysroot. Cloning it as workspace
  source would instead force colcon to rebuild message code on target
  for no benefit.
- **meta-ros already cross-builds the very same examples, which gives a
  free cross-check.** `meta-ros2-jazzy/generated-recipes/examples/`
  carries 22 superflore-generated recipes at version **0.19.7-1**
  (`examples-rclcpp-minimal-publisher`, `-minimal-subscriber`,
  `-wait-set`, `-cbg-executor`, the `rclpy` counterparts, and
  `launch-testing-examples`). This pins which upstream tag the `jazzy`
  branch corresponds to, and means the acceptance criteria can compare
  a `colcon`-built package against a `bitbake`-built one rather than
  only checking that colcon exited zero.
- **Native compilation on a target rootfs is not on by default.** A
  stock OE image ships runtime libraries only: no `gcc`/`g++`, no
  `make`, no headers. OE-core's `tools-sdk` `IMAGE_FEATURES` entry adds
  the on-target toolchain and `dev-pkgs` adds every installed package's
  `-dev` headers. Both are needed here, and `dev-pkgs` in particular is
  what makes the ROS 2 CMake config files (`ament_cmake`'s
  `*-config.cmake`, `rclcpp`'s exported targets) present on target at
  all — without it `find_package(rclcpp)` fails even though `librclcpp`
  is installed.

## 3. Requirements

- **REQ-1** — A `packagegroup-px4-ros-dev` recipe, placed in
  `meta-px4/dynamic-layers/meta-ros2-jazzy/recipes-core/packagegroups/`
  so it is only visible when `meta-ros2-jazzy` is in `bblayers.conf`
  (same `BBFILES_DYNAMIC` gating already used for `px4-msgs` /
  `px4-ros2-cpp` / `micro-xrce-dds-agent` — meta-px4 must not gain a
  hard `LAYERDEPENDS` on meta-ros). It aggregates, at minimum: the full
  colcon set including the three the aggregator omits (§2), the
  on-target build tools (`cmake`, `make`, `binutils`, `gcc`, `g++`,
  `git`, `pkgconfig`, `python3-dev`, `python3-setuptools`), and the
  ament/rosidl CMake machinery needed to configure a ROS 2 package
  (`ament-cmake`, `ament-cmake-ros`, `ament-cmake-python`,
  `ament-package`, `ros-workspace`, `ros-environment`,
  `rosidl-default-generators`).
- **REQ-2** — An image (`px4-ros-dev-image`, or an
  `IMAGE_FEATURES`-extended variant of `px4-sitl-qemu-image`) that
  installs REQ-1's packagegroup with `IMAGE_FEATURES += "tools-sdk
  dev-pkgs"`, sized so an on-target colcon build of the acceptance
  workload actually fits (§5.3).
- **REQ-3** — Booting that image and running `colcon build` over a
  `jazzy`-branch checkout of `ros2/examples` succeeds, having sourced
  `/opt/ros/jazzy/setup.bash` first, with `example_interfaces` coming
  from the installed target package rather than from workspace source.
- **REQ-4** — At least one built node runs: a `colcon`-built
  `examples_rclcpp_minimal_publisher` and the *packaged*
  `examples-rclcpp-minimal-subscriber` (or vice versa) exchange
  messages, proving the on-target build produced a genuinely
  ABI-compatible artifact rather than merely compiling.
- **REQ-5** — Document the workflow (how to get sources onto the
  target, the `setup.bash` sourcing, the `colcon build` invocation, and
  the disk/RAM constraints from §5.3), as an addition to
  [GAZEBO_ROS2.md](../GAZEBO_ROS2.md) or a sibling doc.

## 4. Non-goals

- **Cross-compiling from the host** — that is entirely
  [009](009-sdk-colcon.md)'s milestone; the two deliberately share the
  `ros2/examples` workload and nothing else.
- **Shipping a development image as a product.** `tools-sdk` +
  `dev-pkgs` roughly doubles image size; this is a development
  convenience target, and the M7 images stay lean.
- **Building PX4 itself (or `px4-autopilot-gz`) on target.** The
  acceptance workload is `ros2/examples`. PX4's own build pulls nested
  CMake `ExternalProject`s and live network fetches that M7 spent five
  patches taming for the *cross* build (spec 007 §6); re-litigating
  that on-target is out of scope.
- **Custom `.msg`/`.srv` generation on target.** The examples get their
  interfaces from the prebuilt `example-interfaces` package (§2). Full
  on-target rosidl codegen may work as a side effect but is not a gate.
- **Any change to the Renode/STM32H7 track (M1-M4).**

## 5. Design sketch

### 5.1 Layer layout

```
meta-px4/
└── dynamic-layers/
    └── meta-ros2-jazzy/          # gated on meta-ros2-jazzy being present
        ├── recipes-px4/          # existing (M7): px4-msgs, px4-ros2-cpp, ...
        └── recipes-core/
            └── packagegroups/
                └── packagegroup-px4-ros-dev.bb
```

`conf/layer.conf:21` reads `BBFILES_DYNAMIC += "ros2-jazzy-layer:
${LAYERDIR}/dynamic-layers/meta-ros2-jazzy/recipes-*/*/*.bb"` —
checked, not assumed. `recipes-core/packagegroups/
packagegroup-px4-ros-dev.bb` sits at exactly the two levels below
`recipes-*` that the glob expects, so this milestone needs no
layer.conf change. (Note for [009](009-sdk-colcon.md): that same glob
covers `*.bb` **only**, so any `.bbappend` in the dynamic layer would
be silently ignored — see 009 §2.)

### 5.2 Why a packagegroup rather than an image-only `IMAGE_INSTALL`

Splitting the list into a packagegroup keeps it reusable: the M7 QEMU
image, a future hardware image, and an ad-hoc `IMAGE_INSTALL:append`
can all pull the same set, and `RDEPENDS`-based aggregation means the
list is resolved at package level (so `dev-pkgs` picks up the matching
`-dev` variants) rather than being a flat image manifest.

### 5.3 Size and memory

An on-target native ROS 2 build is the resource-hungry part of this
milestone, and the M7 QEMU image's `IMAGE_ROOTFS_SIZE ?= "8192"` was
sized for running PX4, not for compiling C++ with a full `-dev`
sysroot. Expect to need a substantially larger rootfs plus enough guest
RAM that `colcon`'s default parallelism doesn't OOM — `colcon build
--parallel-workers 1` and `--executor sequential` are the obvious
mitigations, and the real numbers belong in the implementation record
once measured rather than guessed at here.

## 6. Implementation record

### REQ-1 / REQ-2 — recipes complete, image builds

`dynamic-layers/meta-ros2-jazzy/recipes-core/packagegroups/
packagegroup-px4-ros-dev.bb` (REQ-1),
`.../recipes-core/images/px4-ros-dev-image.bb` (REQ-2), and
`.../recipes-px4/ros2-examples-src/ros2-examples-src_0.19.7.bb`
(workspace staging for REQ-3). `MACHINE=qemux86-64 bitbake
px4-ros-dev-image` succeeds; the deployed rootfs is 914 MB on disk
(21 GB apparent — the ext4 is sparse, so §5.3's disk worry was
unfounded).

`ros2-examples-src` pins `ros2/examples` to
`07008852303f2a35a91c65d78046b274a35477ea`. That is the `jazzy` branch
HEAD *and* the `0.19.7` release commit meta-ros generates its
`examples-*_0.19.7-1` recipes from — checked via the GitHub API, which
is what makes the three build paths (bitbake, on-target colcon, SDK
colcon) genuinely comparable. It needs no `S` override: this OE
version's `bitbake.conf` sets `S = "${UNPACKDIR}/${BP}"` and the git
fetcher unpacks there, the same way the sibling `px4-msgs` recipe
relies on.

Four real problems, each found by building rather than by reading:

- **`python3-colcon-notification` does not build** — §2. Naming it in
  the packagegroup (on the theory that the aggregator's omission was
  just an unfinished `TODO`) failed the image build on
  `ModuleNotFoundError: No module named 'pkg_resources'`. Dropped, with
  the reasoning recorded in the recipe so nobody adds it back.
- **`debug-tweaks` is not a valid `IMAGE_FEATURES` entry** — it is
  `EXTRA_IMAGE_FEATURES` shorthand, and `IMAGE_FEATURES` rejects it by
  name. Replaced with the three features it stands for here:
  `allow-empty-password`, `allow-root-login`, `empty-root-password`.
  Needed at all because this build directory's `local.conf` sets no
  `EXTRA_IMAGE_FEATURES`, so root's password would otherwise be locked
  and the ssh-driven test harness could not log in.
- **The image deploys under a different name than the recipe.**
  `meta-ros-common/classes/ros_image.bbclass` does `IMAGE_BASENAME:append
  = "-${ROS_DISTRO}"`, so `px4-ros-dev-image` is deployed as
  `px4-ros-dev-image-jazzy-qemux86-64`. Anything referring to the image
  by name — `runqemu` in particular — needs the deployed basename, not
  the recipe name. (The M7 images are unaffected: they inherit only
  `core-image`, not `ros_image`.)
- **`runqemu` shells out to `bitbake -e` when given a bare image
  name**, which blocks silently on bitbake's lock if any build is
  running. Since M8's guest test and M9's `populate_sdk` were run in
  parallel, this deadlocked with no output at all. Fixed by passing the
  deployed `qemuboot.conf` path directly, which skips the bitbake call;
  the fstype then has to be given explicitly too (`ext4`), because
  qemux86-64's machine config sets `qb_default_fstype = ext4.zst` while
  this image builds plain uncompressed ext4.

### REQ-3 / REQ-4 — harness working, AC-2 met, build fix in flight

Driven by `scripts/run-m8-ontarget-colcon-test.sh`, which boots the
image under `runqemu` with slirp networking and drives it over ssh.
KVM is used when `/dev/kvm` is accessible (it is here, via a file ACL
rather than kvm group membership) — it matters more for this guest than
any other in the repo, since this one compiles C++ rather than just
running it. With KVM the guest reaches a usable ssh prompt in **15
seconds**, so the emulation overhead §5.3 worried about is a non-issue.

**AC-2 met** on the first real run, in the guest:

```
cmake:  cmake version 4.3.1
g++:    g++ (GCC) 15.3.0
colcon: colcon-bash 0.5.0: up-to-date  colcon-cd 0.1.1: up-to-date ...
ROS:    /opt/ros/jazzy
```

**AC-3 initially failed, and the failure was the useful part.** Every
C++ package in the workspace died at configure time:

```
CMake Error at /opt/ros/jazzy/share/ament_cmake/cmake/
ament_cmake_export_dependencies-extras.cmake:5 (find_package):
  Could not find a package configuration file provided by
  "ament_cmake_libraries"
```

The hand-curated ament list in the packagegroup (`ament-cmake`,
`ament-cmake-auto`, `ament-cmake-ros`, `ament-cmake-python`,
`ament-package`, `ament-lint-auto`, `ros-workspace`, `ros-environment`,
`rosidl-default-generators`, `rosidl-cmake`) looked complete and was
not: `ament_cmake`'s own extras file does `find_package` on sibling
packages that nothing in that list pulls in, and installing
`ament-cmake` does not drag them along. This is exactly the failure
mode §1 predicted an on-target build would expose and a cross-build
would not — bitbake only ever needs what a recipe's `DEPENDS` names.

Fixed by replacing the hand-curated list with
`${ROS_SDK_TARGET_PACKAGES}` — meta-ros2-jazzy's own curated set of
~20 interdependent `ament-cmake-*` packages plus `rclcpp`, the
interface packages and the rosidl generators, defined in
`conf/ros-distro/include/jazzy/ros-sdk.inc` and set globally by that
layer's `layer.conf`. Upstream maintains it for the SDK
([009](009-sdk-colcon.md) §2); an on-target build needs the same
content for the same reason, so consuming the variable is both correct
and self-maintaining. Worth noting as a cross-milestone result: the one
variable serves both M8 and M9.

The rebuilt image carries 916 packages, 44 of them `ament-cmake-*`
(including the missing `ament-cmake-libraries`) — checked against the
image manifest, not assumed from the recipe change.

**AC-3 and AC-4 then both passed on the next run.** All 22 workspace
packages built:

```
Summary: 22 packages finished [2min 11s]
```

with the expected executables present under `install/` (verified by
listing them, not from colcon's exit code — `publisher_member_function`
and nine subscriber variants among them). For AC-4, the colcon-built
publisher and the **bitbake-packaged** subscriber from `/opt/ros/jazzy`
exchanged live messages:

```
publisher (colcon-built):    [1787111055.714505072] Publishing: 'Hello, world! 0'
subscriber (bitbake-packaged): [1787111055.714670266] I heard: 'Hello, world! 0'
```

165 microseconds apart — real IPC between an artifact built on the
target and one cross-built by bitbake, which is what makes this
evidence of ABI compatibility rather than merely of successful
compilation.

One last wrinkle, harmless but worth knowing: the image has no
`procps`, so `pkill`/`killall` do not exist in the guest. The test
harness captures the subscriber's PID instead of matching it by name.

### REQ-5 — complete

[COLCON.md](../COLCON.md) covers both this milestone and
[009](009-sdk-colcon.md): what the packagegroup contains and why the
compiler is *not* in it, how to build and run the image, the by-hand
commands, and the `MACHINE` caveat this build directory needs.

## 7. Acceptance criteria

- **AC-1** — **MET.** `packagegroup-px4-ros-dev` builds, and
  `bitbake-layers show-recipes` reports it from `meta-px4` only with
  `meta-ros2-jazzy` present (REQ-1).
- **AC-2** — **MET.** The image builds and boots under `runqemu` — 15
  seconds to a usable ssh prompt with KVM — and in the guest `cmake`
  (4.3.1), `g++` (GCC 15.3.0) and `colcon version-check` all succeed
  with `AMENT_PREFIX_PATH=/opt/ros/jazzy` (§6).
- **AC-3** — **MET.** `colcon build` over the `jazzy` branch of
  `ros2/examples` reports `22 packages finished [2min 11s]` in the
  guest, and the expected executables are present under `install/` —
  checked by listing them, not from the exit code (§6, REQ-3).
- **AC-4** — **MET.** The `colcon`-built publisher and the
  `bitbake`-packaged subscriber exchanged messages in the guest, seen
  in the subscriber's own output 165 µs after the publisher's (§6,
  REQ-4).

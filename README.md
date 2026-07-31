# PX4 Autopilot

An OpenEmbedded/Yocto layer providing recipes for building
[PX4/PX4-Autopilot](https://github.com/PX4/PX4-Autopilot), covering
two distinct configurations:

- **PX4 posix/SITL** — the flight stack cross-compiled for a Linux
  target (`px4-autopilot`), the normal companion-computer/simulation
  build.
- **Pixhawk 6X NuttX firmware** — the real bare-metal flight
  controller firmware (`px4-firmware`, plus `px4-firmware-renode`,
  `px4-bootloader`, `px4-io-firmware`) cross-compiled for the
  STM32H753 (Cortex-M7) FMU and its STM32F100 (Cortex-M3) PX4IO
  coprocessor — no Linux involved on the target at all.

## Layout

| Recipe | Purpose |
|---|---|
| `px4-autopilot` | The posix/SITL flight stack, built with `cmake.bbclass` against PX4's top-level CMakeLists (`-DCONFIG=${PX4_CONFIG}`). |
| `px4-autopilot-gz` | Same posix/SITL build, with real (not stub) Gazebo Harmonic simulation support — see [GAZEBO_ROS2.md](GAZEBO_ROS2.md). |
| `px4-msgs`, `px4-ros2-cpp`, `micro-xrce-dds-agent` | ROS 2 message/interface libraries and the host-side DDS-XRCE agent, gated on `meta-ros2-jazzy` being present (`dynamic-layers/meta-ros2-jazzy/`) — see [GAZEBO_ROS2.md](GAZEBO_ROS2.md). |
| `qgroundcontrol-appimage` | Repackages the official QGroundControl AppImage so it can ship *inside* a target image (only used by `px4-sitl-gazebo-qgc-image`) — everywhere else QGroundControl stays a host prerequisite, see GAZEBO_ROS2.md section 2.4. |
| `px4-sitl-launch-scripts` | Orchestrates `MicroXRCEAgent`/QGroundControl/PX4 SITL startup order inside `px4-sitl-gazebo-qgc-image`. |
| `px4-sitl-gazebo-qgc-image`, `px4-sitl-qemu-image` | Two runnable images for the SITL+Gazebo+ROS2 track — an all-in-one OCI container, and a `runqemu`-bootable image pairing with host-native Gazebo/QGroundControl — see [GAZEBO_ROS2.md](GAZEBO_ROS2.md) section 4. |
| `microcdr` | eProsima Micro CDR, normally cloned from GitHub *at compile time* by the Micro-XRCE-DDS-Client SuperBuild. |
| `microxrceddsclient` | eProsima Micro XRCE-DDS Client (PX4 fork), normally built by PX4 as a nested `ExternalProject_Add`. |
| `cyclonedds-px4-native` | Host `idlc` with the `cdrstream-desc` feature, normally bootstrapped by PX4 at configure time with a hardcoded `/usr/bin/gcc`. |
| `px4-firmware` | The real-hardware Pixhawk 6X FMU firmware (`px4_fmu-v6x_default`), NuttX/bare-metal, Cortex-M7. |
| `px4-firmware-renode` | Same firmware, Renode-only variant (console/TELEM1 DMA disabled to work around Renode timer-model gaps; optionally SIH-enabled) — see [SIMULATION.md](SIMULATION.md). |
| `px4-bootloader` | The Pixhawk 6X FMU's bootloader (`px4_fmu-v6x_bootloader`), same Cortex-M7 chip/tune as `px4-firmware`. |
| `px4-io-firmware` | Firmware for the Pixhawk 6X's PX4IO coprocessor (`px4_io-v2_default`), a separate Cortex-M3/no-FPU chip. |
| `gcc-arm-none-eabi` | The prebuilt `arm-none-eabi-*` bare-metal GCC toolchain the four recipes above depend on — see "Baremetal ARM toolchain" below. |

## Quick start

### PX4 posix/SITL build (Linux, with kas on ELISA Space Grade Linux)

Clone this layer:

```sh
git clone https://github.com/robwoolley/meta-px4 layers/meta-px4
```

Then build **one** of the following kas configurations, depending on the
framework you want. Each builds `core-image-minimal` for `qemuarm64` on
top of the SGL scarthgap configuration:
```sh
kas build layers/meta-px4/kas/px4-sgl-qemuarm64.yml
```

This builds `core-image-minimal` for `qemuarm64` with `px4-autopilot`
installed (default board config `px4_sitl_default`, see below).

### Pixhawk 6X NuttX firmware build (bare-metal, STM32H7/STM32F1)

This builds real flight-controller firmware, not a Linux image. Add
this layer plus `openembedded-core/meta` to `bblayers.conf`, then
enable the two bare-metal multiconfigs meta-px4 provides (`pixhawk6x`
for the Cortex-M7 FMU + bootloader, `pixhawk6x-io` for the Cortex-M3
PX4IO coprocessor — each sets its own `MACHINE`/`TCLIBC`, so your
primary build's own `MACHINE`/`DISTRO` are untouched):

```
# conf/local.conf
BBMULTICONFIG = "pixhawk6x pixhawk6x-io"
```

```sh
bitbake mc:pixhawk6x:px4-firmware       # real hardware
bitbake mc:pixhawk6x:px4-bootloader
bitbake mc:pixhawk6x-io:px4-io-firmware
```

No separate host toolchain install is needed — see "Baremetal ARM
toolchain" below. For the Renode-only variant, running it against
Renode's STM32H743 model (plain NSH boot or a SIH simulated flight),
and MAVLink connectivity, see [SIMULATION.md](SIMULATION.md).

## Baremetal ARM toolchain

Everything needed to cross-compile the Pixhawk 6X firmware is carried
directly in this layer — no external layer or host-installed
toolchain is required:

- **OE-level machine/tune configuration** (bare-metal `TCLIBC`,
  Cortex-M7+FPU / Cortex-M3 tunes, flash/RAM layout):
  `conf/machine/pixhawk-6x.conf` and `conf/machine/pixhawk-6x-io.conf`,
  plus their shared `.inc` files under `conf/machine/include/` and
  `conf/machine/include/arm/armv7m/`. `TCLIBC = "baremetal"` itself is
  set in `conf/multiconfig/pixhawk6x.conf`/`pixhawk6x-io.conf`, not at
  the machine level, since it depends on which recipe is building.
- **The actual `arm-none-eabi-*` compiler binaries**:
  `recipes-devtools/external-arm-toolchain/gcc-arm-none-eabi_15.3.rel1.bb`
  (vendored from `git.yoctoproject.org/meta-arm`'s
  `meta-arm-toolchain` layer rather than depending on that whole
  layer, matching this layer's existing convention of carrying its own
  devtools recipes). Produces `gcc-arm-none-eabi-native`, a `DEPENDS`
  of all four NuttX/bare-metal recipes above — PX4's own
  `Toolchain-arm-none-eabi.cmake` drives the actual compiler flags,
  this recipe just puts the prebuilt binaries on `PATH`.

## Carried patches (px4-autopilot)

1. **kconfig toolchain guard** — PX4's `cmake/kconfig.cmake` force-overrides
   `CMAKE_TOOLCHAIN_FILE` from the board config (`CONFIG_BOARD_TOOLCHAIN`),
   clobbering the toolchain file bitbake passes and mis-directing any
   subproject that forwards it. The patch makes the board toolchain a
   default only.
2. **`UXRCE_DDS_CLIENT_USE_SYSTEM_LIBS`** — links
   `libmicroxrcedds_client.a`/`libmicrocdr.a` + headers from the target
   sysroot instead of running the nested client build (which fetches
   Micro-CDR from the network).
3. **`PX4_BUILD_IDLC=OFF`** — skips the configure-time `git submodule` calls
   and host-gcc CycloneDDS bootstrap; `idlc` is taken from the native
   sysroot via PATH (cyclonedds' own `Generate.cmake` does
   `find_program(idlc)` when cross-compiling). Only relevant when the board
   config enables `CONFIG_LIB_CDRSTREAM`.

## Version coupling — read before bumping SRCREV

When you bump `px4-autopilot`'s SRCREV you **must** re-sync the subproject
recipes to PX4's submodule pins (`git submodule status` in the PX4 tree):

- `microxrceddsclient` SRCREV ← `src/modules/uxrce_dds_client/Micro-XRCE-DDS-Client`
  (or `…-v3` when the config sets `CONFIG_UXRCE_DDS_CLIENT_USE_DDS_V3`;
  then also switch `microcdr` to 2.0.2 — the client does
  `find_package(microcdr <ver> EXACT)`).
- `cyclonedds-px4-native` SRCREV ← `src/lib/cdrstream/cyclonedds`
  (host idlc and the target-side cdr serializer compiled into PX4 must
  come from the same sources).
- The `UCLIENT_PROFILE_*` options in `microxrceddsclient` must continue to
  match `src/modules/uxrce_dds_client/CMakeLists.txt` — they change the
  client's config header and ABI.

## Selecting the board (posix/SITL)

`PX4_CONFIG ?= "px4_sitl_default"` — override in a bbappend or your distro
config with any **posix**-platform config (e.g. `emlid_navio2_default`).
The Pixhawk 6X NuttX firmware build has no equivalent variable — its
board is fixed per recipe (`px4_fmu-v6x_default`, `px4_io-v2_default`,
etc., set in each recipe's own `EXTRA_OECMAKE`), since each is a
distinct, non-interchangeable piece of hardware.

## Known limitations / out of scope

- **Simulators**: the gazebo-classic / gz / jsbsim / flightgear
  ExternalProjects are guarded by `find_package` of the simulator dev libs
  and stay disabled as long as those are not in `DEPENDS`. The nested `gz`
  project forwards no toolchain settings, so do not enable it without
  packaging it separately.
- **Board extras**: `beaglebone/blue` fetches librobotcontrol from GitHub,
  `modalai/voxl2` builds libfc-sensor-api at configure time — package these
  separately if you target those boards.
- **Debug builds**: `src/drivers/uavcan/libdronecan` downloads googletest at
  configure time when `CMAKE_BUILD_TYPE=Debug` and the DroneCAN driver is
  enabled. Keep the default Release build type or patch it out.
- **Big-endian targets**: `microcdr` is built with default (little-endian)
  endianness config; pass `-DCONFIG_BIG_ENDIANNESS=ON` for BE machines.
- The python `-native` dependencies come from oe-core and
  meta-openembedded/meta-python (`kconfiglib`, `jsonschema`, `matplotlib`,
  …); the ones neither provides (`empy`, `lark-parser`, `pyros-genmsg`,
  `pymavlink`, `pyulog`, `nunavut`) are carried in this layer under
  `recipes-devtools/`, along with a `cerberus` newer than meta-python's.
  PX4 requires empy < 4 (the layer's 3.3.2 recipe satisfies this).

## Dependencies

  URI: https://github.com/openembedded/openembedded-core.git
  branch: wrynose 

  URI: https://github.com/openembedded/meta-openembedded.git (meta-python)
  branch: wrynose

The layer is compatible with Yocto scarthgap (5.0) and wrynose (6.0).
PX4 additionally uses python modules from the wider meta-openembedded
collection; the kas configurations above pull in everything required via SGL.

## Patches

Please submit any patches against the meta-px4 layer to the
maintainer:

Maintainer: Rob Woolley <rob.woolley@windriver.com>

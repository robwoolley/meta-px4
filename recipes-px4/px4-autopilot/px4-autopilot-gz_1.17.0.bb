require px4-autopilot.inc

# meta-qt5's own Qt5Config.cmake no-ops (silently skips defining Qt5::*
# imported targets) unless OE_QMAKE_PATH_* cmake cache variables are set --
# these only get passed via cmake_qt5.bbclass, which px4-autopilot.inc's
# plain `inherit cmake` doesn't provide. Needed once gz-gui8 (a transitive
# dependency here) pulls Qt5 into the CMake configure. Also brings in
# qtbase/qtbase-native DEPENDS. specs/007-sitl-gazebo-ros2.md REQ-2.
inherit cmake_qt5

# Same patch files as px4-autopilot_1.17.0.bb -- shared, not duplicated.
FILESEXTRAPATHS:prepend := "${THISDIR}/px4-autopilot:"

# Same PX4 source/SRCREV/patches as px4-autopilot_1.17.0.bb -- see that
# recipe for the base posix/SITL build. This variant additionally links
# real Gazebo simulation support (gz_bridge/gz_msgs/gz_plugins), which
# px4-autopilot's own build already Kconfig-enables
# (CONFIG_MODULES_SIMULATION_GZ_BRIDGE/GZ_MSGS/GZ_PLUGINS=y in
# boards/px4/sitl/default.px4board) but which silently builds as stub
# "ERROR: Gazebo simulation dependencies not found!" targets there,
# since that recipe carries no gz-* DEPENDS at all -- checked directly
# in src/modules/simulation/{gz_bridge,gz_plugins}/CMakeLists.txt,
# which do find_package(gz-transport/gz-sim/gz-sensors/gz-plugin) at
# configure time. specs/007-sitl-gazebo-ros2.md REQ-2.
#
# GZ_DISTRO=harmonic makes those CMakeLists search for the exact
# versioned package names (gz-sim8, gz-sensors8, gz-plugin2,
# gz-transport13) that meta-ros-common actually provides, instead of
# an unversioned find_package(gz-sim) that could just as easily
# resolve against a newer major version meta-ros-common also carries
# (gz-sim9/10, for Ionic/newer).
#
# gz_plugins' optical_flow.cmake does its own live `git clone` of
# PX4/PX4-OpticalFlow via CMake's ExternalProject_Add at do_compile time,
# which fails outright in this network-isolated build (same class of
# problem as the uxrce_dds_client/CycloneDDS idlc fetches that patches
# 0002/0003 already solve). Fetching it here via SRC_URI (do_fetch has
# real network access) and pointing optical_flow.cmake at the local
# checkout via patch 0006 + -DPX4_OPTICALFLOW_SOURCE_DIR (below) avoids
# the live clone entirely. specs/007-sitl-gazebo-ros2.md REQ-2.
SRC_URI = "gitsm://github.com/PX4/PX4-Autopilot.git;protocol=https;branch=release/1.17 \
           file://0001-cmake-kconfig-respect-an-externally-provided-CMAKE_T.patch \
           file://0002-uxrce_dds_client-support-linking-pre-built-Micro-XRC.patch \
           file://0003-msg-support-using-a-pre-built-CycloneDDS-idlc.patch \
           file://0004-build-add-px4-sitl-deb-packages.patch \
           file://0005-replace-dpkg-with-option.patch \
           file://0006-gz_plugins-support-linking-a-pre-fetched-PX4-Optical.patch \
           file://0007-gz_msgs-build-px4_gz_msgs-as-C-17.patch \
           file://0008-gz_plugins-silence-Werror-double-promotion-from-gz-.patch \
           file://0009-gz_bridge-silence-Werror-double-promotion-from-gz-m.patch \
           file://gz-qt5-find-package-order-workaround.cmake \
           gitsm://github.com/PX4/PX4-OpticalFlow.git;protocol=https;branch=master;destsuffix=px4-opticalflow-src;name=opticalflow"

SRCREV = "d6f12ad1c4f70ad3230afd7d86e971421e02fef4"
SRCREV_opticalflow = "a4d4cfde0c023be6b1ff8c8e796862413bc99272"
SRCREV_FORMAT = "default_opticalflow"

DEPENDS += " \
    gz-transport13 \
    gz-sim8 \
    gz-sensors8 \
    gz-plugin2 \
    sdformat \
    protobuf-native \
    opencv \
"

export GZ_DISTRO = "harmonic"

EXTRA_OECMAKE = " \
    -DCMAKE_INSTALL_PREFIX=/opt/px4 \
    -DCONFIG=px4_sitl_default \
    -DPX4_PACKAGE=ON \
    -DPX4_BINARY_DIR=/opt/px4 \
    -DUXRCE_DDS_CLIENT_USE_SYSTEM_LIBS=ON \
    -DPROTOBUF_PROTOC_EXECUTABLE=${STAGING_BINDIR_NATIVE}/protoc \
    -DCMAKE_PROJECT_INCLUDE=${UNPACKDIR}/gz-qt5-find-package-order-workaround.cmake \
    -DPX4_OPTICALFLOW_SOURCE_DIR=${UNPACKDIR}/px4-opticalflow-src \
"

FILES:${PN} += "/opt/px4"
FILES:${PN} += " \
    /opt/px4/etc \
    /opt/px4/share \
"

# ERROR: px4-autopilot-gz-1.17.0-r0 do_package_qa: QA Issue: File /opt/px4/bin/px4 in package px4-autopilot-gz contains reference to TMPDIR [buildpaths]
INSANE_SKIP:${PN}:append = " buildpaths"

# Ignore all the buildpaths in the comments of the generated source files
INSANE_SKIP:${PN}-src:append = " buildpaths"

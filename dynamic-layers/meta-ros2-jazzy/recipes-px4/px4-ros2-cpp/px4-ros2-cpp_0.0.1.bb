DESCRIPTION = "Library to interface with PX4 from ROS2"
HOMEPAGE = "https://github.com/Auterion/px4-ros2-interface-lib"
LICENSE = "BSD-3-Clause"
# LICENSE lives at the repo root, one level above ${S} (this repo is a
# monorepo; the actual package is under px4_ros2_cpp/).
LIC_FILES_CHKSUM = "file://../LICENSE;md5=44654f576b1c4642587c090c5cec2ce0"

inherit ros_distro_jazzy
inherit ros_component

ROS_CN = "px4_ros2_cpp"
ROS_BPN = "px4_ros2_cpp"

# release/1.17 (confirmed to exist, matching this project's PX4-Autopilot
# pin exactly -- px4-autopilot_1.17.0.bb / px4-autopilot-gz_1.17.0.bb).
# This repo is a monorepo; the actual package lives under px4_ros2_cpp/.
# specs/007-sitl-gazebo-ros2.md REQ-4.
SRC_URI = "git://github.com/Auterion/px4-ros2-interface-lib.git;protocol=https;branch=release/1.17"
SRCREV = "4a3370f084ac6f1ef001a4afa2b007845ffd0837"
S = "${UNPACKDIR}/${BP}/px4_ros2_cpp"

ROS_BUILD_DEPENDS = " \
    ament-index-cpp \
    rclcpp \
    px4-msgs \
    eigen3-cmake-module \
    libeigen \
"

ROS_BUILDTOOL_DEPENDS = " \
    ament-cmake-native \
    eigen3-cmake-module \
    python3-empy-native \
"

ROS_EXPORT_DEPENDS = " \
    ament-index-cpp \
    libeigen \
"

ROS_BUILDTOOL_EXPORT_DEPENDS = " \
    eigen3-cmake-module \
"

ROS_EXEC_DEPENDS = ""

# Currently informational only -- see http://www.ros.org/reps/rep-0149.html#dependency-tags.
ROS_TEST_DEPENDS = " \
    ament-lint-auto \
    ament-lint-common \
    ament-cmake-gtest \
"

DEPENDS = "${ROS_BUILD_DEPENDS} ${ROS_BUILDTOOL_DEPENDS}"
# Bitbake doesn't support the "export" concept, so build them as if we needed them to build this package (even though we actually
# don't) so that they're guaranteed to have been staged should this package appear in another's DEPENDS.
DEPENDS += "${ROS_EXPORT_DEPENDS} ${ROS_BUILDTOOL_EXPORT_DEPENDS}"

RDEPENDS:${PN} += "${ROS_EXEC_DEPENDS}"

ROS_BUILD_TYPE = "ament_cmake"

inherit ros_${ROS_BUILD_TYPE}

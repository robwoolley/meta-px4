SUMMARY = "Build tooling for compiling ROS 2 packages with colcon on the target"
DESCRIPTION = "specs/008-ontarget-colcon.md REQ-1. Everything needed to run \
'colcon build' over a ROS 2 workspace on the target itself: the full colcon \
extension set, CMake and the supporting host tools, and the ament/rosidl \
CMake machinery a ROS 2 package needs at configure time. \
\
This deliberately does NOT pull in the C/C++ toolchain itself -- gcc, g++, \
binutils, make and friends come from the 'tools-sdk' IMAGE_FEATURE, and the \
headers every find_package() needs come from 'dev-pkgs'. An image using this \
packagegroup must set both; see px4-ros-dev-image.bb."

LICENSE = "MIT"

inherit packagegroup

# colcon-common-extensions RDEPENDs on 15 of meta-ros-common's 19 colcon
# recipes. Two of the four it omits are named explicitly here because they
# matter: -python-setup-py builds ament_python packages (the whole rclpy half
# of ros2/examples), and -pkg-config resolves pkg-config-based dependencies.
#
# python3-colcon-notification is deliberately NOT included. Its omission from
# the aggregator upstream is load-bearing, not the stale TODO the in-recipe
# comment suggests: colcon-notification 0.3.0's setup.py does
# 'from pkg_resources import parse_version', and pkg_resources is gone from
# the setuptools this build uses, so do_compile fails outright
# (ModuleNotFoundError). Adding it back would need a recipe fix upstream --
# and it would buy nothing here, since all it provides is desktop
# notification popups when a build finishes, on a headless target.
RDEPENDS:${PN} = " \
    python3-colcon-common-extensions \
    python3-colcon-python-setup-py \
    python3-colcon-pkg-config \
    \
    cmake \
    git \
    pkgconfig \
    python3-dev \
    python3-setuptools \
    python3-numpy \
    \
    ${ROS_SDK_TARGET_PACKAGES} \
"

# ROS_SDK_TARGET_PACKAGES comes from meta-ros2-jazzy's
# conf/ros-distro/include/jazzy/ros-sdk.inc, which its layer.conf requires
# globally -- so the variable is set for any recipe, not just the SDK image
# that is upstream's only other consumer of it (see specs/009 section 2).
#
# Reusing it here rather than hand-listing ament packages is deliberate, and
# was learned the hard way: a hand-curated list naming ament-cmake,
# ament-cmake-auto, ament-cmake-ros and friends looked complete but omitted
# ament-cmake-libraries, so every C++ package in ros2/examples died at
# configure time in ament_cmake_export_dependencies-extras.cmake with
# "Could not find a package configuration file provided by
# ament_cmake_libraries". The full set has ~20 interdependent ament-cmake-*
# packages plus rclcpp, the interface packages and the rosidl generators;
# upstream already curates exactly that list for the SDK, and an on-target
# build needs the same content for the same reason. See specs/008 section 6.

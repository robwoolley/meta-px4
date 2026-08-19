SUMMARY = "Self-hosting ROS 2 development image: build ROS packages with colcon on target"
DESCRIPTION = "specs/008-ontarget-colcon.md REQ-2. A runqemu-bootable image \
carrying the on-target build tooling (packagegroup-px4-ros-dev), the ROS 2 \
runtime the built packages link against, and the ros2/examples sources \
staged as a ready-to-build colcon workspace. \
\
This is a development convenience image, deliberately not lean: 'tools-sdk' \
and 'dev-pkgs' together roughly double its size. The M7 images \
(px4-sitl-qemu-image, px4-sitl-gazebo-qgc-image) stay as they are."

LICENSE = "MIT"

inherit core-image
inherit ros_distro_${ROS_DISTRO}
inherit ${ROS_DISTRO_TYPE}_image

# tools-sdk  -- the on-target C/C++ toolchain (gcc, g++, make, binutils).
# dev-pkgs   -- every installed package's -dev headers and CMake config
#               files. Without this find_package(rclcpp) fails even though
#               librclcpp is installed, because the exported *-config.cmake
#               lives in the -dev package. See specs/008 section 2.
# The three login features spell out what 'debug-tweaks' would have meant.
# 'debug-tweaks' itself is only valid in EXTRA_IMAGE_FEATURES, not here --
# IMAGE_FEATURES rejects it as an invalid feature name. This build directory's
# local.conf sets no EXTRA_IMAGE_FEATURES, so without these root's password is
# locked and the dropbear login is refused, which is how
# scripts/run-m8-ontarget-colcon-test.sh drives the guest.
# Appropriate for a development image; do not copy into anything shippable.
IMAGE_FEATURES += " \
    tools-sdk \
    dev-pkgs \
    ssh-server-dropbear \
    allow-empty-password \
    allow-root-login \
    empty-root-password \
"

IMAGE_INSTALL = " \
    packagegroup-core-boot \
    packagegroup-px4-ros-dev \
    ros2-examples-src \
    \
    rclcpp \
    rclpy \
    std-msgs \
    example-interfaces \
    \
    ${CORE_IMAGE_EXTRA_INSTALL} \
"

# example-interfaces is installed as a binary package rather than cloned into
# the workspace: the action/service examples depend on it, and meta-ros2-jazzy
# already carries example-interfaces_0.12.1-1. Cloning it as workspace source
# would force colcon to regenerate message code on target for no benefit.

# The bitbake-built counterparts of the workspace packages, so AC-4 can pair a
# colcon-built node with a packaged one.
IMAGE_INSTALL += " \
    examples-rclcpp-minimal-publisher \
    examples-rclcpp-minimal-subscriber \
"

IMAGE_LINGUAS = " "

IMAGE_FSTYPES = "ext4"

# An on-target native build with a full -dev sysroot needs far more room than
# the M7 images' 8G: sources, object files, and colcon's build/ + install/ +
# log/ trees all land in the rootfs. Revisit with real numbers once measured
# (specs/008 section 5.3).
IMAGE_ROOTFS_SIZE ?= "20971520"
IMAGE_ROOTFS_EXTRA_SPACE:append = "${@bb.utils.contains("DISTRO_FEATURES", "systemd", " + 4096", "", d)}"

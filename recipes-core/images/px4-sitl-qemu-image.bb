SUMMARY = "Bootable qemux86-64 image with PX4 SITL, ROS 2 message libs, and MicroXRCEAgent"
DESCRIPTION = "Scenario 2 of specs/007-sitl-gazebo-ros2.md REQ-6 -- a normal, \
runqemu-bootable image carrying only the PX4 side of the stack \
(px4-autopilot-gz + micro-xrce-dds-agent + px4-msgs/px4-ros2-cpp). Gazebo \
itself and QGroundControl are NOT installed here: this scenario deliberately \
runs both of those as ordinary host-side applications (the official Gazebo \
Harmonic packages/container image, and the QGroundControl AppImage) talking \
to PX4 running inside this QEMU guest over the network -- see GAZEBO_ROS2.md \
section 4.2 for the required tap-networking setup and exactly how each side \
reaches the other."
LICENSE = "MIT"

IMAGE_INSTALL = " \
    packagegroup-core-boot \
    px4-autopilot-gz \
    px4-msgs \
    px4-ros2-cpp \
    micro-xrce-dds-agent \
    ${CORE_IMAGE_EXTRA_INSTALL} \
"

IMAGE_LINGUAS = " "

inherit core-image

# ext4: the simplest format `runqemu qemux86-64 <this-image>.ext4` boots
# directly, no wic partition layout of our own to maintain.
IMAGE_FSTYPES = "ext4"

IMAGE_ROOTFS_SIZE ?= "8192"
IMAGE_ROOTFS_EXTRA_SPACE:append = "${@bb.utils.contains("DISTRO_FEATURES", "systemd", " + 4096", "", d)}"

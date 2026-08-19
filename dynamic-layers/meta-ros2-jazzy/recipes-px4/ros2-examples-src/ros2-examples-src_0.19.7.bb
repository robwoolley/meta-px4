SUMMARY = "ros2/examples source tree, staged on target as a colcon workspace"
DESCRIPTION = "specs/008-ontarget-colcon.md REQ-3. Ships the ros2/examples \
sources into the image at ${ROS2_EXAMPLES_WS}/src/examples so an on-target \
'colcon build' needs no network access in the guest -- the acceptance test \
should exercise colcon and the target toolchain, not QEMU's networking. \
\
This is source only: nothing here is compiled at build time. meta-ros \
separately cross-builds the very same code as examples-rclcpp-* / \
examples-rclpy-* binary packages, which is what makes the AC-4 comparison \
(colcon-built publisher against bitbake-built subscriber) meaningful."

HOMEPAGE = "https://github.com/ros2/examples"
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://LICENSE;md5=3b83ef96387f14655fc854ddc3c6bd57"

# Branch 'jazzy' matches this project's ROS distro. Its HEAD is the 0.19.7
# release commit, i.e. exactly the source meta-ros2-jazzy's own
# examples-*_0.19.7-1 recipes are generated from -- checked via the GitHub
# API, not assumed, so the two build paths really are comparing like with
# like.
SRC_URI = "git://github.com/ros2/examples;protocol=https;branch=jazzy"
SRCREV = "07008852303f2a35a91c65d78046b274a35477ea"

ROS2_EXAMPLES_WS ?= "/home/root/examples_ws"

inherit allarch

do_configure[noexec] = "1"
do_compile[noexec] = "1"

do_install() {
    install -d ${D}${ROS2_EXAMPLES_WS}/src/examples
    cp -R --no-dereference --preserve=mode,links ${S}/. ${D}${ROS2_EXAMPLES_WS}/src/examples/
    rm -rf ${D}${ROS2_EXAMPLES_WS}/src/examples/.git
}

FILES:${PN} = "${ROS2_EXAMPLES_WS}"

# Pure source: no ELF objects to strip or check, and the .py files are
# workspace input rather than installed executables.
INHIBIT_PACKAGE_STRIP = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT = "1"
INSANE_SKIP:${PN} += "file-rdeps"

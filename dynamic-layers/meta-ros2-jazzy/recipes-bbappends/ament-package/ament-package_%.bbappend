# Forward-port of meta-ros2-kilted's skip_shell_path.patch to jazzy.
#
# meta-ros carries this patch only under meta-ros2-kilted (against
# ament-package 0.17.3-1); meta-ros2-jazzy has no ament-package bbappend
# at all. Without it, AMENT_SKIP_SHELL_PATH is a variable nothing reads,
# so an SDK environment that sets it (see specs/009-sdk-colcon.md REQ-3,
# from meta-ros PR #1261) still gets the target's
# /opt/ros/${ROS_DISTRO}/bin prepended to the host PATH.
#
# Deliberately version-agnostic (%) rather than pinned to 0.16.5-1 the
# way kilted's copy is pinned to its own version: the patch is
# regenerated against a specific SRCREV, so a future ament-package bump
# that moves the patched code should fail the build loudly rather than
# silently stop applying and quietly restore the bug this fixes.
#
# Drop this once the patch reaches jazzy upstream.

FILESEXTRAPATHS:prepend := "${THISDIR}/${BPN}:"

SRC_URI:append = " file://skip_shell_path.patch"

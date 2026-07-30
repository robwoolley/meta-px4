# meta-ros-common carries its own pymavlink_2.4.15.bb (same PN+PV) at a
# higher BBFILE_PRIORITY, which silently shadows meta-px4's own recipe of
# the same name -- our setup_requires=['future'] fix (see the patch below)
# was never actually being applied because of this. bbappend onto the
# winning recipe instead of trying to out-prioritize it.
# specs/007-sitl-gazebo-ros2.md REQ-2.
#
# The patch file lives under meta-px4's own pymavlink/ dir, not
# meta-ros-common's -- FILESPATH stays anchored to the winning (base)
# recipe's location, so it needs to be added explicitly here.
FILESEXTRAPATHS:prepend := "${THISDIR}/pymavlink:"
#
# setup.py's setup_requires=['future'] tries to `pip wheel` it from PyPI
# at build time regardless of what DEPENDS already staged, which fails
# outright in a network-isolated build.
SRC_URI += "file://0001-setup.py-drop-setup_requires-future.patch"

DEPENDS += "python3-future-native"

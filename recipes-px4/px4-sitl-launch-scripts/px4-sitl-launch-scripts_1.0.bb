SUMMARY = "Launcher script wiring together PX4 SITL, Gazebo, MicroXRCEAgent, and QGroundControl inside px4-sitl-gazebo-qgc-image.bb"
DESCRIPTION = "Not a general-purpose tool -- a single project-specific script \
(start-sitl-gazebo-qgc.sh) that sets the PX4_GZ_*/GZ_SIM_* environment \
variables px4-autopilot-gz's installed layout needs (since the gz_env.sh PX4 \
itself generates only lands in its *build* tree, never the installed \
package -- see the script's own comments) and starts MicroXRCEAgent, \
QGroundControl, and PX4 SITL in order. specs/007-sitl-gazebo-ros2.md REQ-6."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://start-sitl-gazebo-qgc.sh"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${UNPACKDIR}/start-sitl-gazebo-qgc.sh ${D}${bindir}/start-sitl-gazebo-qgc.sh
}

FILES:${PN} = "${bindir}/start-sitl-gazebo-qgc.sh"

# Pure shell, glued to a specific target image's installed package layout --
# nothing to cross-compile, but it does need to run on the same target the
# packages it references (px4-autopilot-gz, micro-xrce-dds-agent,
# qgroundcontrol-appimage) are built for, so this is deliberately not
# `inherit allarch`.
RDEPENDS:${PN} = "px4-autopilot-gz micro-xrce-dds-agent qgroundcontrol-appimage"

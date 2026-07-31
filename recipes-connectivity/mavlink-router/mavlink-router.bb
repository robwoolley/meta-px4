# Recipe created by recipetool
# This is the basis of a recipe and may need further editing in order to be fully functional.
# (Feel free to remove these comments when editing.)

# WARNING: the following LICENSE and LIC_FILES_CHKSUM values are best guesses - it is
# your responsibility to verify that the values are complete and correct.
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://LICENSE;md5=93888867ace35ffec2c845ea90b2e16b"

SRC_URI = "gitsm://github.com/mavlink-router/mavlink-router.git;protocol=https;branch=master"

# Modify these as desired
PV = "1.0+git"
SRCREV = "2362c620f483cef1edd574fb962a373a288e4b9e"

DEPENDS = "systemd"

inherit meson pkgconfig systemd

EXTRA_OEMESON = "-Dsystemdsystemunitdir=${systemd_system_unitdir}"

FILES:${PN} += "${systemd_system_unitdir}/mavlink-router.service"
 
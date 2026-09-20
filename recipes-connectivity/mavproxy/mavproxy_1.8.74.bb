# Recipe created by recipetool
# This is the basis of a recipe and may need further editing in order to be fully functional.
# (Feel free to remove these comments when editing.)

SUMMARY = "MAVProxy MAVLink ground station"
HOMEPAGE = "https://github.com/ArduPilot/MAVProxy"
# NOTE: License in setup.py/PKGINFO is: GPLv3
# WARNING: the following LICENSE and LIC_FILES_CHKSUM values are best guesses - it is
# your responsibility to verify that the values are complete and correct.
#
# The following license files were not able to be identified and are
# represented as "Unknown" below, you will need to check them yourself:
#   COPYING.txt
#   MAVProxy/modules/mavproxy_cesium/LICENSE.md
LICENSE = "GPL-3.0-only"
LIC_FILES_CHKSUM = "file://COPYING.txt;md5=3c34afdc3adf82d2448f12715a255122 \
                    file://MAVProxy/modules/mavproxy_cesium/LICENSE.md;md5=84dcc94da3adb52b53ae4fa38fe49e5d"

SRC_URI = "gitsm://github.com/ArduPilot/MAVProxy;protocol=https;branch=master"

# Modify these as desired
PV = "1.8.74+git"
SRCREV = "v1.8.74"

inherit setuptools3

# The following configs & dependencies are from setuptools extras_require.
# These dependencies are optional, hence can be controlled via PACKAGECONFIG.
# The upstream names may not correspond exactly to bitbake package names.
# The configs are might not correct, since PACKAGECONFIG does not support expressions as may used in requires.txt - they are just replaced by text.
#
# Uncomment this line to enable all the optional features.
#PACKAGECONFIG ?= "cesium server recommended"
PACKAGECONFIG[cesium] = ",,,python3-tornado"
PACKAGECONFIG[server] = ",,,python3-flask"
PACKAGECONFIG[recommended] = ",,,python3-pygame python3-flask python3-openai python3-paho-mqtt python3-piexif python3-pymonocypher python3-pynmea2 python3-wxpython"

# WARNING: the following rdepends are determined through basic analysis of the
# python sources, and might not be 100% accurate.
RDEPENDS:${PN} += " python3-core \
    python3-pyserial \
    python3-future \
    python3-lxml \
    python3-numpy \
    python3-pyyaml \
    python3-pillow \
"

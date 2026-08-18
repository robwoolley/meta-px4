# Recipe created by recipetool
# This is the basis of a recipe and may need further editing in order to be fully functional.
# (Feel free to remove these comments when editing.)

# WARNING: the following LICENSE and LIC_FILES_CHKSUM values are best guesses - it is
# your responsibility to verify that the values are complete and correct.
#
# The following license files were not able to be identified and are
# represented as "Unknown" below, you will need to check them yourself:
#   COPYING
SUMMARY = "This is a python implementation of the MAVLink protocol."
HOMEPAGE = "https://github.com/ArduPilot/pymavlink/"

LICENSE = "LGPL-3.0-only"
LIC_FILES_CHKSUM = "file://COPYING;md5=6ea13ec5f0f3dd35ac5b53afdc3ed9ff"

SRC_URI[sha256sum] = "ed3225ce985d06103aab251afc883d04a98a1d6db5c73995e7de4caacb1f66cd"

inherit pypi python_setuptools_build_meta

DEPENDS = "python3-future-native python3-lxml-native"
RDEPENDS:${PN} = "python3-future python3-lxml"

# WARNING: We were unable to map the following python package/module
# dependencies to the bitbake packages which include them:
#    cython

PYPI_PACKAGE = "pymavlink"

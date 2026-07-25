SUMMARY = "This is a python implementation of the MAVLink protocol."
HOMEPAGE = "https://github.com/ArduPilot/pymavlink/"

LICENSE = "LGPL-3.0-only"
LIC_FILES_CHKSUM = "file://README.md;md5=203be5c2bd6638cbb07938f9cb7ec45e"

SRC_URI[sha256sum] = "7bf45ad4a250e5e9928c33b5ff56afed4dbc6f99c8f58d05051e2a308150db80"

# setup.py's setup_requires=['future'] tries to `pip wheel` it from PyPI
# at build time regardless of what DEPENDS already staged, which fails
# outright in a network-isolated build.
SRC_URI += "file://0001-setup.py-drop-setup_requires-future.patch"

inherit setuptools3 pypi

BBCLASSEXTEND = "native nativesdk"

DEPENDS = "python3-pip-native python3-wheel-native python3-future-native"
RDEPENDS:${PN} = "python3-future python3-lxml"

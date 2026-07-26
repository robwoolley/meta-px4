SUMMARY = "PX4 Autopilot NuttX firmware (fmu-v6x), Renode-only variant"
DESCRIPTION = "Same as px4-firmware, but with USART3 (console) DMA disabled for Renode testing -- see specs/003-renode-boot.md."
HOMEPAGE = "https://px4.io/"

LICENSE = "Apache-2.0 & BSD-3-Clause & ISC & MIT & GPL-3.0-or-later"
LIC_FILES_CHKSUM = "file://LICENSE;md5=70b583e99896377e3306b74b7f33e5de"

# Same DEPENDS as px4-firmware.inc -- see that file's own comments for
# the full story on each entry.
DEPENDS += "\
    gcc-arm-none-eabi-native \
    nunavut-native \
    pymavlink-native \
    pyros-genmsg-native \
    python3-argcomplete-native \
    python3-cerberus-native \
    python3-coverage-native \
    python3-empy-native \
    python3-future-native \
    python3-jinja2-native \
    python3-jsonschema-native \
    python3-pkg-resources-native \
    python3-kconfiglib-native \
    python3-lark-parser-native \
    python3-lxml-native \
    python3-matplotlib-native \
    python3-numpy-native \
    python3-packaging-native \
    python3-pkgconfig-native \
    python3-psutil-native \
    python3-pycryptodome-native \
    python3-pygments-native \
    python3-pyserial-native \
    python3-pyyaml-native \
    python3-requests-native \
    python3-setuptools-native \
    python3-six-native \
    python3-sympy-native \
    python3-toml-native \
    python3-wheel-native \
    pyulog-native \
"

INHIBIT_DEFAULT_DEPS = "1"

inherit cmake python3native pkgconfig

export GIT_SUBMODULES_ARE_EVIL = "1"

# See px4-firmware.inc for the full story: NuttX's own host-tool
# compiles need HOSTCC on oe-core's HOSTTOOLS allowlist.
export HOSTCC = "gcc"

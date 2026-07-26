SUMMARY = "PX4 Autopilot NuttX firmware (fmu-v6x)"
DESCRIPTION = "PX4 is an open-source autopilot stack for drones and unmanned vehicles."
HOMEPAGE = "https://px4.io/"

LICENSE = "Apache-2.0 & BSD-3-Clause & ISC & MIT & GPL-3.0-or-later"
LIC_FILES_CHKSUM = "file://LICENSE;md5=70b583e99896377e3306b74b7f33e5de"

# Same code-generation tooling as px4-autopilot (empy/jinja2/kconfiglib/
# etc. run on the host regardless of target architecture). Deliberately
# NOT carrying microcdr/microxrceddsclient/cyclonedds-px4-native here:
# CONFIG_LIB_CDRSTREAM is off for px4_fmu-v6x_default (specs/002 section 2)
# so the idlc bootstrap is moot, and UXRCE_DDS_CLIENT_USE_SYSTEM_LIBS is
# deliberately not set (specs/002 section 5.2) -- PX4 builds
# microcdr/microxrceddsclient itself via its own nested build for this
# milestone.
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

# Avoid bitbake pulling in / building its own OE cross-gcc chain for
# this MACHINE (observed happening automatically for TCLIBC="baremetal"
# during M1's baremetal-helloworld build -- binutils-cross-arm,
# gcc-cross-arm, etc.). px4-firmware uses only the vendored
# gcc-arm-none-eabi-native toolchain (specs/000 section 4.1 Option B),
# not an OE-built one.
INHIBIT_DEFAULT_DEPS = "1"

inherit cmake python3native pkgconfig

export GIT_SUBMODULES_ARE_EVIL = "1"

# NuttX's own build compiles several host-side build-time tools (incdir,
# mkdeps, cmpconfig, ...) from platforms/nuttx/NuttX/nuttx/tools/*.c using
# $(HOSTCC) -- a NATIVE compiler, entirely separate from the arm-none-eabi
# cross-compiler used for target code. tools/Config.mk defaults
# `HOSTCC ?= cc`, but bare "cc" (unlike "gcc") is not in oe-core's
# HOSTTOOLS allowlist, so it's absent from PATH in this sandboxed build --
# confirmed by reproducing: `make[1]: *** [Makefile.host:203: incdir]
# Error 127`. `?=` means an inherited environment variable wins, so
# exporting HOSTCC here (to something HOSTTOOLS does provide) fixes it.
export HOSTCC = "gcc"

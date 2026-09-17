# Vendored from git.yoctoproject.org/meta-arm
# (meta-arm-toolchain/recipes-devtools/external-arm-toolchain/) --
# see arm-binary-toolchain.inc for why this is carried directly in
# meta-px4 rather than depending on that layer.
#
# specs/000-architecture.md section 4.1, Option B: PX4's own
# Toolchain-arm-none-eabi.cmake drives compiler flags; this recipe
# only provides the prebuilt arm-none-eabi-* binaries on PATH and
# handles pinning/fetch/packaging.

require arm-binary-toolchain.inc

COMPATIBLE_HOST = "(x86_64|aarch64).*-linux"

SUMMARY = "Arm GNU Toolchain - AArch32 bare-metal target (arm-none-eabi)"
# The upstream meta-arm-toolchain recipe (git.yoctoproject.org/meta-arm)
# expresses this as the SPDX-style "GPL-3.0-only AND GPL-3.0-or-later
# WITH GCC-exception-3.1", but this layer's oe-core (wrynose) doesn't
# support "AND"/"WITH" as LICENSE operators -- oe/license.py's
# license_operator_chars is only '&|() '. Rewritten in the traditional
# OE syntax using the GPL-3.0-with-GCC-exception common-license token
# that already exists in meta/files/common-licenses/ for exactly this
# combination (GCC's own binaries vs. its runtime libraries).
LICENSE = "GPL-3.0-only & GPL-3.0-with-GCC-exception"

LIC_FILES_CHKSUM:aarch64 = "file://share/doc/gcc/Copying.html;md5=90014a59d1783b37a10240d4d0002c6e"
LIC_FILES_CHKSUM:x86-64 = "file://share/doc/gcc/Copying.html;md5=90014a59d1783b37a10240d4d0002c6e"

SRC_URI = "https://gitlab.arm.com/api/v4/projects/tooling%2Fgnu-toolchains-for-arm/packages/generic/gnu-toolchain/${PV}/arm-gnu-toolchain-${PV}-${HOST_ARCH}-${BINNAME}.tar.xz;name=gcc-${HOST_ARCH}"
SRC_URI[gcc-aarch64.sha256sum] = "06979e0c8171de58e5dc2a2b2019330a290f30930f27728af98a83e1a7369b3a"
SRC_URI[gcc-x86_64.sha256sum] = "563bebb2b97d53382b956d6ee1fe61e2cae26699901417234a37df505ef9b5fa"

S = "${WORKDIR}/arm-gnu-toolchain-${PV}-${HOST_ARCH}-${BINNAME}"

UPSTREAM_CHECK_URI = "https://gitlab.arm.com/tooling/gnu-toolchains-for-arm/-/branches"
UPSTREAM_CHECK_REGEX = "releases/(?P<pver>.+)\?"

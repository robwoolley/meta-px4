require px4-firmware.inc

# Same PX4 source/SRCREV as px4-autopilot_1.17.0.bb. Patch 0001 (kconfig
# toolchain guard) is deliberately NOT carried here -- it makes PX4's
# board-driven CMAKE_TOOLCHAIN_FILE a default only, which is backwards
# for firmware: we want PX4's own Toolchain-arm-none-eabi.cmake (driven
# by CONFIG_BOARD_TOOLCHAIN="arm-none-eabi") to win over cmake.bbclass's
# unconditionally-injected one, so the *unpatched* force-override
# behavior is what we want here (specs/002 section 5.2).
#
# Micro-XRCE-DDS-Client's own SuperBuild.cmake (src/modules/uxrce_dds_client/
# Micro-XRCE-DDS-Client/cmake/SuperBuild.cmake) does its own ExternalProject_Add
# git clone of Micro-CDR at *compile* time -- confirmed by a real build failure:
#   fatal: unable to access 'https://github.com/eProsima/Micro-CDR.git/':
#   Could not resolve host: github.com
# (This is the network-fetch question specs/002 section 5.2 flagged as the
# tradeoff of not carrying UXRCE_DDS_CLIENT_USE_SYSTEM_LIBS/patches 0002-0003.)
# Fixed by fetching the same pinned ref meta-px4's own microcdr_2.0.1.bb already
# uses (tag v2.0.1, matching SuperBuild.cmake's `_microcdr_tag` exactly) as a
# second named SRC_URI entry, then redirecting that exact GIT_REPOSITORY URL to
# the local copy via a WORKDIR-scoped git config (GIT_CONFIG_GLOBAL) in
# do_compile:prepend -- NOT `git config --global`, which would write to the
# real build user's ~/.gitconfig (HOME is not sandboxed for this task).
#
# Patch 0001 (SOURCE_DATE_EPOCH): confirmed via three independent clean
# rebuilds (REQ-7) that the .elf is already byte-for-byte reproducible, but
# the .px4 wrapper was not -- Tools/px_mkfw.py hardcodes
# `build_time = int(time.time())` with no override. Patched it to honor
# SOURCE_DATE_EPOCH (which OE already computes per recipe via
# do_deploy_source_date_epoch and exports to all tasks), falling back to
# time.time() unchanged when unset.
SRC_URI = "gitsm://github.com/PX4/PX4-Autopilot.git;protocol=https;branch=release/1.17 \
           git://github.com/eProsima/Micro-CDR.git;protocol=https;nobranch=1;destsuffix=git/microcdr-mirror;name=microcdr \
           file://0001-px_mkfw-honor-SOURCE_DATE_EPOCH-for-build_time.patch \
"

SRCREV = "d6f12ad1c4f70ad3230afd7d86e971421e02fef4"
SRCREV_microcdr = "3d1b17703c7cf4f22def2910bc845bdb5152d7b5"
SRCREV_FORMAT = "px4_microcdr"

EXTRA_OECMAKE = " \
    -DCONFIG=px4_fmu-v6x_default \
"

export GIT_CONFIG_GLOBAL = "${WORKDIR}/git-mirrors.gitconfig"

do_compile:prepend() {
    git config --file "${GIT_CONFIG_GLOBAL}" \
        url."file://${UNPACKDIR}/git/microcdr-mirror".insteadOf \
        "https://github.com/eProsima/Micro-CDR.git"
}

# PX4's own build has no "make install" target for firmware images -- the
# .elf/.px4 just land in the out-of-tree cmake build dir (${B}, confirmed
# at .../px4-firmware/1.17.0/build/px4_fmu-v6x_default.{elf,px4}). rm_work
# deletes ${WORKDIR} (including ${B}) right after do_build, so without a
# deploy task the finished firmware would vanish along with the rest of
# the work directory. addtask ... before do_build runs this ahead of that
# cleanup, same ordering kernel.bbclass uses for zImage.
inherit deploy

PX4_IMAGE_BASENAME = "px4-firmware-${PV}-${MACHINE}"

do_deploy() {
    install -d ${DEPLOYDIR}
    install -m 0644 ${B}/px4_fmu-v6x_default.elf ${DEPLOYDIR}/${PX4_IMAGE_BASENAME}.elf
    install -m 0644 ${B}/px4_fmu-v6x_default.px4 ${DEPLOYDIR}/${PX4_IMAGE_BASENAME}.px4
}
addtask deploy after do_compile before do_build

require px4-firmware-renode.inc

# Same PX4 source/SRCREV/microcdr-mirror setup as px4-firmware_1.17.0.bb
# -- see that recipe's own comments for the full story on patch 0001
# omission, the microcdr nested-fetch redirect, and the
# SOURCE_DATE_EPOCH patch.
#
# Patch 0002 (this recipe only): specs/003-renode-boot.md gap #5 --
# paused a live Renode boot repeatedly via its interactive telnet
# monitor and resolved `cpu PC`/`cpu LR` against the real ELF with
# arm-none-eabi-addr2line. The CPU was actually stuck in
# stm32_serial.c's DMA-based UART transmit path (up_dma_send /
# up_dma_txavailable) and its completion interrupt handler, not
# anywhere in px4_mtd.cpp -- Renode's DMA.STM32DMA model for `dma2`
# never signals the transfer-complete condition NuttX's serial driver
# waits on, so the console's DMA-based TX loops forever re-sending the
# same buffered output. Disabling CONFIG_USART3_TXDMA/RXDMA forces
# plain interrupt-driven console I/O, sidestepping the incomplete DMA
# model. This patch is deliberately carried only here, not in
# px4-firmware: the real hardware build keeps DMA-based console I/O
# unmodified.
#
# Patch 0003 (this recipe only): specs/004-sih-renode-mavlink.md REQ-1/
# REQ-2. Enables CONFIG_MODULES_SIMULATION_SIMULATOR_SIH=y (PX4's onboard
# flight-dynamics simulation, checked directly in PX4's own Kconfig --
# default n, not enabled for fmu-v6x upstream) and forces
# SYS_AUTOSTART=1100 (the existing 1100_rc_quad_x_sih.hil quadcopter
# airframe) via rc.board_defaults, since there is no persistent
# parameter storage in this environment for a runtime `param set` to
# survive a reboot.
SRC_URI = "gitsm://github.com/PX4/PX4-Autopilot.git;protocol=https;branch=release/1.17 \
           git://github.com/eProsima/Micro-CDR.git;protocol=https;nobranch=1;destsuffix=git/microcdr-mirror;name=microcdr \
           file://0001-px_mkfw-honor-SOURCE_DATE_EPOCH-for-build_time.patch \
           file://0002-boards-px4-fmu-v6x-disable-USART3-DMA-for-Renode.patch \
           file://0003-boards-px4-fmu-v6x-enable-SIH-and-force-quad-X-SIH.patch \
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

inherit deploy

PX4_IMAGE_BASENAME = "px4-firmware-renode-${PV}-${MACHINE}"

do_deploy() {
    install -d ${DEPLOYDIR}
    install -m 0644 ${B}/px4_fmu-v6x_default.elf ${DEPLOYDIR}/${PX4_IMAGE_BASENAME}.elf
    install -m 0644 ${B}/px4_fmu-v6x_default.px4 ${DEPLOYDIR}/${PX4_IMAGE_BASENAME}.px4
}
addtask deploy after do_compile before do_build

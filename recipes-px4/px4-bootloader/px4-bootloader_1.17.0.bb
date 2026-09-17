require px4-bootloader.inc

# Same PX4 source/SRCREV as px4-firmware_1.17.0.bb (specs/002 REQ-5).
# CONFIG_BOARD_ARCHITECTURE="cortex-m7" in
# boards/px4/fmu-v6x/bootloader.px4board -- same chip/tune as
# px4-firmware, so this reuses the "pixhawk6x" multiconfig rather than
# needing one of its own.
SRC_URI = "gitsm://github.com/PX4/PX4-Autopilot.git;protocol=https;branch=release/1.17 \
           file://0001-px_mkfw-honor-SOURCE_DATE_EPOCH-for-build_time.patch \
"

SRCREV = "d6f12ad1c4f70ad3230afd7d86e971421e02fef4"

S = "${WORKDIR}/git"

EXTRA_OECMAKE = " \
    -DCONFIG=px4_fmu-v6x_bootloader \
"

# PX4's own platforms/nuttx/CMakeLists.txt names bootloader output
# ${PX4_BOARD_VENDOR}_${PX4_BOARD_MODEL}_${PX4_BOARD_LABEL}.{elf,bin,px4}
# ("px4_fmu-v6x_bootloader.{elf,bin,px4}"), landing in ${B} same as
# px4-firmware's px4_fmu-v6x_default.{elf,px4} -- confirmed a .px4
# genuinely gets built here too (px_mkfw.py invocation seen in a real
# do_compile log), not assumed absent. See px4-firmware_1.17.0.bb for
# why a deploy task is needed at all (rm_work + no install target),
# and for why patch 0001 (SOURCE_DATE_EPOCH) is needed for
# reproducibility.
inherit deploy

PX4_IMAGE_BASENAME = "px4-bootloader-${PV}-${MACHINE}"

do_deploy() {
    install -d ${DEPLOYDIR}
    install -m 0644 ${B}/px4_fmu-v6x_bootloader.elf ${DEPLOYDIR}/${PX4_IMAGE_BASENAME}.elf
    install -m 0644 ${B}/px4_fmu-v6x_bootloader.bin ${DEPLOYDIR}/${PX4_IMAGE_BASENAME}.bin
    install -m 0644 ${B}/px4_fmu-v6x_bootloader.px4 ${DEPLOYDIR}/${PX4_IMAGE_BASENAME}.px4
}
addtask deploy after do_compile before do_build

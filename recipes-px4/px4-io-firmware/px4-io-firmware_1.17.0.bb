require px4-io-firmware.inc

# Same PX4 source/SRCREV as px4-firmware_1.17.0.bb (specs/002 REQ-4).
# boards/px4/io-v2/default.px4board sets CONFIG_BOARD_ARCHITECTURE=
# "cortex-m3" -- a different chip/tune than px4-firmware's Cortex-M7,
# so this needs its own multiconfig (pixhawk6x-io, MACHINE=
# "pixhawk-6x-io"), per specs/002 section 5.4 option (a).
SRC_URI = "gitsm://github.com/PX4/PX4-Autopilot.git;protocol=https;branch=release/1.17 \
           file://0001-px_mkfw-honor-SOURCE_DATE_EPOCH-for-build_time.patch \
"

SRCREV = "d6f12ad1c4f70ad3230afd7d86e971421e02fef4"

S = "${WORKDIR}/git"

EXTRA_OECMAKE = " \
    -DCONFIG=px4_io-v2_default \
"

# PX4's own build (platforms/nuttx/CMakeLists.txt) names output
# px4_io-v2_default.{elf,bin,px4}, landing in ${B} -- same convention
# as px4-firmware's px4_fmu-v6x_default.{elf,px4}. See
# px4-firmware_1.17.0.bb for why a deploy task is needed at all
# (rm_work + no install target).
#
# Note (specs/002 section 2, M0 finding): PX4's own
# src/drivers/px4io/CMakeLists.txt has a *separate* mechanism
# (CONFIG_BOARD_IO, an ExternalProject_Add nested build) that some
# other boards (e.g. cubepilot) use to embed a freshly-built io-v2
# image into their own fmu ROMFS automatically. fmu-v6x does not set
# CONFIG_BOARD_IO -- boards/px4/fmu-v6x/extras/px4_io-v2_default.bin
# is a pre-built binary checked into the PX4-Autopilot source tree
# instead, and that's what px4-firmware's ROMFS actually embeds. This
# recipe produces a genuinely from-source-built io-v2 image as its own
# deploy artifact; wiring px4-firmware to consume it instead of the
# vendored blob is a separate, not-yet-decided follow-up.
inherit deploy

PX4_IMAGE_BASENAME = "px4-io-firmware-${PV}-${MACHINE}"

do_deploy() {
    install -d ${DEPLOYDIR}
    install -m 0644 ${B}/px4_io-v2_default.elf ${DEPLOYDIR}/${PX4_IMAGE_BASENAME}.elf
    install -m 0644 ${B}/px4_io-v2_default.px4 ${DEPLOYDIR}/${PX4_IMAGE_BASENAME}.px4
}
addtask deploy after do_compile before do_build

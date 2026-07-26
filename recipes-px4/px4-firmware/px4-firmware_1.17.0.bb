require px4-firmware.inc

# Same PX4 source/SRCREV as px4-autopilot_1.17.0.bb. Patch 0001 (kconfig
# toolchain guard) is deliberately NOT carried here -- it makes PX4's
# board-driven CMAKE_TOOLCHAIN_FILE a default only, which is backwards
# for firmware: we want PX4's own Toolchain-arm-none-eabi.cmake (driven
# by CONFIG_BOARD_TOOLCHAIN="arm-none-eabi") to win over cmake.bbclass's
# unconditionally-injected one, so the *unpatched* force-override
# behavior is what we want here (specs/002 section 5.2).
SRC_URI = "gitsm://github.com/PX4/PX4-Autopilot.git;protocol=https;branch=release/1.17"

SRCREV = "d6f12ad1c4f70ad3230afd7d86e971421e02fef4"

EXTRA_OECMAKE = " \
    -DCONFIG=px4_fmu-v6x_default \
"

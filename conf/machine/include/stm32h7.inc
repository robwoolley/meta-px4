#
# Shared settings for STM32H7-family (Cortex-M7) bare-metal machines.
#
# Values below are verified against the real PX4 v1.17.0 source tree
# (boards/px4/fmu-v6x/nuttx-config/{nsh/defconfig,scripts/script.ld}),
# not assumed:
#   - FLASH: origin 0x08020000, length 1920K (script.ld) -- the first
#     128K of the chip's 2M flash is reserved for the bootloader.
#   - RAM: CONFIG_RAM_START=0x20010000, CONFIG_RAM_SIZE=245760 (240K)
#     (nsh/defconfig) -- main SRAM used by the application.
#   - ITCM_RAM: origin 0x00000000, length 64K (script.ld).
# These are fmu-v6x-specific instances of the STM32H753 memory map;
# revisit if this .inc is ever shared with a board whose flash/RAM
# reservations differ.

require conf/machine/include/arm/armv7m/tune-cortexm7-fpv5d16.inc

STM32H7_FLASH_BASE ?= "0x08020000"
STM32H7_FLASH_SIZE ?= "1920K"
STM32H7_RAM_BASE   ?= "0x20010000"
STM32H7_RAM_SIZE   ?= "240K"
STM32H7_ITCM_BASE  ?= "0x00000000"
STM32H7_ITCM_SIZE  ?= "64K"

MACHINE_FEATURES = ""

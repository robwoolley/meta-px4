#
# Shared settings for the PX4IO coprocessor (STM32F100C8, Cortex-M3,
# no FPU).
#
# Values below are verified against the real PX4 v1.17.0 source tree
# (boards/px4/io-v2/nuttx-config/{nsh/defconfig,scripts/script.ld}),
# not assumed:
#   - FLASH: origin 0x08001000, length 60K (script.ld) -- the first 4K
#     of the chip's 64K flash is reserved for the bootloader.
#   - RAM: CONFIG_RAM_SIZE=8192 (nsh/defconfig), origin 0x20000000
#     (script.ld) -- the whole 8K SRAM.
# STM32F100 is a value-line part with no hardware FPU, unlike the
# fmu-v6x's STM32H753 -- tune-cortexm3.inc (stock oe-core, no
# layer-local tune needed here).

require conf/machine/include/arm/armv7m/tune-cortexm3.inc

STM32F1_FLASH_BASE ?= "0x08001000"
STM32F1_FLASH_SIZE ?= "60K"
STM32F1_RAM_BASE   ?= "0x20000000"
STM32F1_RAM_SIZE   ?= "8K"

MACHINE_FEATURES = ""

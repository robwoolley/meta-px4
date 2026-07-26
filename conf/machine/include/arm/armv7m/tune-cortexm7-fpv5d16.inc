#
# Cortex-M7 + double-precision FPU (fpv5-d16), for STM32H7-class parts.
#
# oe-core's tune-cortexm7.inc provides only the FPU-less cortexm7
# tune; feature-arm-neon.inc defines the single-precision vfpv5spd16
# feature (-mfpu=fpv5-sp-d16) but wires it only into the ARMv8-M
# chain. Neither covers -mfpu=fpv5-d16 on ARMv7E-M, so this layer
# defines it here, following feature-arm-neon.inc's vfpv5spd16 entry
# as the pattern. The -mfpu=/-mfloat-abi= flags themselves are still
# composed generically by oe-core's feature-arm-vfp.inc, which is
# already pulled in transitively for this chain via
# arch-armv6m.inc -> arch-armv5.inc -> feature-arm-vfp.inc -- this
# file only needs to add the TUNE_CCARGS_MFPU contribution, not
# reimplement the flag composition.
#
# Confirmed from NuttX source (not assumed) that PX4/fmu-v6x needs
# this exact flag: platforms/nuttx/NuttX/nuttx/arch/arm/src/armv7-m/
# Toolchain.defs sets -mfpu=fpv5-d16 when CONFIG_ARCH_CORTEXM7=y and
# CONFIG_ARCH_DPFPU=y, and arch/arm/src/stm32h7/Kconfig selects
# ARCH_HAVE_DPFPU for the STM32H7 chip variants fmu-v6x uses.

require conf/machine/include/arm/armv7m/tune-cortexm7.inc

TUNEVALID[fpv5d16] = "Enable Vector Floating Point Version 5, Double Precision, with 16 registers (fpv5-d16) unit."
TUNE_CCARGS_MFPU .= "${@bb.utils.contains('TUNE_FEATURES', 'fpv5d16', 'fpv5-d16', '', d)}"

# Hard-float naming embeds "hf" directly in the tune name (matching
# every other hard-float ARM tune in oe-core, e.g. cortexa5hf) because
# TUNE_PKGARCH is auto-computed as ARMPKGARCH + ARMPKGSFX_EABI (="hf")
# + ARMPKGSFX_FPU (="-fpv5d16") = "cortexm7hf-fpv5d16" -- PACKAGE_EXTRA_ARCHS
# must contain that exact string or oe-core's sanity checker rejects
# the config ("PACKAGE_ARCHS ... does not contain TUNE_PKGARCH").
AVAILTUNES                                  += "cortexm7hf-fpv5d16"
ARMPKGARCH:tune-cortexm7hf-fpv5d16           = "cortexm7"
TUNE_FEATURES:tune-cortexm7hf-fpv5d16        = "${TUNE_FEATURES:tune-cortexm7} fpv5d16 callconvention-hard"
PACKAGE_EXTRA_ARCHS:tune-cortexm7hf-fpv5d16  = "${PACKAGE_EXTRA_ARCHS:tune-cortexm7} cortexm7hf-fpv5d16"

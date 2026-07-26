*** Variables ***
${UART}                     sysbus.usart3
# Override at invocation time, e.g.:
#   renode-test pixhawk6x-boot.robot --variable ELF:/path/to/px4-firmware-renode-1.17.0-pixhawk-6x.elf
${ELF}                      @px4-firmware-renode-1.17.0-pixhawk-6x.elf

*** Test Cases ***
PX4 Boots To NSH On Pixhawk 6X FMU
    Execute Command             set bin ${ELF}
    Execute Command             set repl @${CURDIR}/pixhawk6x.repl
    Execute Command             include @${CURDIR}/pixhawk6x-boot.resc

    Create Terminal Tester       ${UART}
    Start Emulation

    Wait For Line On Uart        NuttShell (NSH)             timeout=20
    Wait For Prompt On Uart      nsh>                        timeout=20

    Write Line To Uart           ver all
    Wait For Line On Uart        HW arch                     timeout=10
    Wait For Prompt On Uart      nsh>                         timeout=10

    Write Line To Uart           uorb status
    Wait For Line On Uart        TOPIC NAME    timeout=10
    Wait For Prompt On Uart      nsh>                         timeout=10

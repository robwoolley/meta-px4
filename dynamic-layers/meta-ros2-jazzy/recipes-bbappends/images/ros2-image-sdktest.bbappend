# specs/009-sdk-colcon.md REQ-2 / REQ-3.
#
# Upstream's ros2-image-sdktest is this milestone's SDK vehicle: it is already
# the only consumer of ROS_SDK_HOST_PACKAGES / ROS_SDK_TARGET_PACKAGES
# anywhere in the tree, so extending it means consuming upstream's wiring
# rather than reproducing it in an image of our own.
#
# Two additions:
#   - nativesdk-ros-sdk-env  (REQ-3) puts PYTHON_SOABI, PYTHONPATH,
#     AMENT_SKIP_SHELL_PATH etc. into the SDK environment automatically.
#   - the PX4 interface libraries (REQ-2) so the SDK can cross-build an
#     application against px4-msgs/px4-ros2-cpp, not just stock ROS 2.
#
# Both are gated on PX4_ROS_SDK_EXTRAS so REQ-1's unmodified upstream baseline
# stays independently reproducible:
#     PX4_ROS_SDK_EXTRAS=0 bitbake ros2-image-sdktest -c populate_sdk

PX4_ROS_SDK_EXTRAS ??= "1"

TOOLCHAIN_HOST_TASK:append = "${@' nativesdk-ros-sdk-env' if d.getVar('PX4_ROS_SDK_EXTRAS') == '1' else ''}"

# example-interfaces is not a PX4 package, but ros2/examples' action, service
# and client packages all find_package() it and ROS_SDK_TARGET_PACKAGES does
# not include it -- so without this, every one of them fails to configure with
# "Could not find a package configuration file provided by example_interfaces"
# (observed: 0 of 22 packages built). meta-ros2-jazzy already carries
# example-interfaces_0.12.1-1; putting it in the SDK sysroot mirrors what
# px4-ros-dev-image does for the on-target build (specs/008), so both
# milestones build the same source against a packaged copy rather than
# regenerating the message code. Cloning ros2/example_interfaces into the
# colcon workspace, as PR #1215's instructions do, is the alternative.
TOOLCHAIN_TARGET_TASK:append = "${@' px4-msgs px4-ros2-cpp micro-xrce-dds-agent example-interfaces' if d.getVar('PX4_ROS_SDK_EXTRAS') == '1' else ''}"

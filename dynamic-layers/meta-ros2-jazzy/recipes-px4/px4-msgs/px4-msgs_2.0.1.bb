DESCRIPTION = "Package with the ROS-equivalent of PX4 uORB msgs"
HOMEPAGE = "https://github.com/PX4/px4_msgs"
LICENSE = "BSD-3-Clause"
LIC_FILES_CHKSUM = "file://LICENSE;md5=6f63d3ce5fdb6ff9577d66fa59838f30"

inherit ros_distro_jazzy

# ros_component (which every superflore-generated recipe pulls in via
# ros_superflore_generated, but a hand-written recipe like this one
# doesn't get automatically) is where `export ROS_DISTRO` and the fix
# for rosidl's unversioned .so files actually live:
#   - rosidl_generator_rs reads the real process environment variable
#     ROS_DISTRO directly (os.environ['ROS_DISTRO']); without exporting
#     it, do_compile fails with "KeyError: 'ROS_DISTRO'".
#   - rosidl-generated typesupport/generator .so files have no SONAME
#     versioning at all (standard ROS2 behavior, not px4_msgs-specific).
#     ros_component's own `inherit ros_faulty_solibs` blanks
#     FILES_SOLIBSDEV (bitbake.conf's own default -dev glob source) and
#     re-adds the same pattern to the main package's FILES instead --
#     this actually works, unlike just adding the pattern to FILES:${PN}
#     directly (tried first): PACKAGES lists ${PN}-dev before ${PN}, so
#     -dev's identical default pattern claims the files first regardless
#     of what the main package's own FILES also lists.
# specs/007-sitl-gazebo-ros2.md REQ-3.
inherit ros_component

ROS_CN = "px4_msgs"
ROS_BPN = "px4_msgs"

# px4_msgs isn't rosdistro-indexed to a version-matched release -- its own
# docs say each PX4 release has its own branch, and rosdistro's generic
# entry just points at `main` (checked against index.ros.org directly).
# Pin to release/1.17 to match this project's own PX4-Autopilot pin
# (px4-autopilot_1.17.0.bb / px4-autopilot-gz_1.17.0.bb), per px4_msgs'
# own message-compatibility model. specs/007-sitl-gazebo-ros2.md REQ-3.
SRC_URI = "git://github.com/PX4/px4_msgs.git;protocol=https;branch=release/1.17"
SRCREV = "86d8239e962f6939e05c3737784f60c02fa884db"

ROS_BUILD_DEPENDS = " \
    builtin-interfaces \
    ros-environment \
    service-msgs \
    rosidl-typesupport-c \
    rosidl-typesupport-cpp \
    rosidl-default-runtime \
"

ROS_BUILDTOOL_DEPENDS = " \
    ament-cmake-native \
    rosidl-default-generators-native \
    rosidl-parser-native \
    rosidl-adapter-native \
    rosidl-typesupport-fastrtps-cpp-native \
    rosidl-typesupport-fastrtps-c-native \
    python3-numpy-native \
    python3-lark-parser-native \
"

ROS_EXPORT_DEPENDS = " \
    builtin-interfaces \
    ros-environment \
    service-msgs \
"

ROS_BUILDTOOL_EXPORT_DEPENDS = ""

ROS_EXEC_DEPENDS = " \
    rosidl-default-runtime \
    service-msgs \
"

# Currently informational only -- see http://www.ros.org/reps/rep-0149.html#dependency-tags.
ROS_TEST_DEPENDS = " \
    ament-lint-common \
"

DEPENDS = "${ROS_BUILD_DEPENDS} ${ROS_BUILDTOOL_DEPENDS}"
# Bitbake doesn't support the "export" concept, so build them as if we needed them to build this package (even though we actually
# don't) so that they're guaranteed to have been staged should this package appear in another's DEPENDS.
DEPENDS += "${ROS_EXPORT_DEPENDS} ${ROS_BUILDTOOL_EXPORT_DEPENDS}"

RDEPENDS:${PN} += "${ROS_EXEC_DEPENDS}"

ROS_BUILD_TYPE = "ament_cmake"

inherit ros_${ROS_BUILD_TYPE}

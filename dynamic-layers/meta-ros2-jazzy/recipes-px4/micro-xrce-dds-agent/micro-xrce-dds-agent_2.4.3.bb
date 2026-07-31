SUMMARY = "eProsima Micro XRCE-DDS Agent"
DESCRIPTION = "Host/companion-side counterpart to PX4's uxrce_dds_client \
(microxrceddsclient/microcdr, recipes-connectivity/) -- bridges the \
DDS-XRCE protocol PX4 speaks over UDP/serial to real DDS participants \
(ROS 2's rmw_fastrtps), so ROS 2 nodes see PX4's uORB topics directly."
HOMEPAGE = "https://github.com/eProsima/Micro-XRCE-DDS-Agent"
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://LICENSE;md5=3b83ef96387f14655fc854ddc3c6bd57"

# v2.4.3 (the last v2.x release, before eProsima's Fast-RTPS->Fast-DDS
# rename landed as a major version 3 API/CMake-package-name break)
# deliberately chosen over master/v3.x: its CMakeLists calls
# find_package(fastrtps 2.14 REQUIRED), an exact match for
# meta-ros2-jazzy's own fastrtps_2.14.6-1.bb. master/v3.x calls
# find_package(fastdds 3 REQUIRED) instead, which meta-ros2-jazzy doesn't
# provide (only meta-ros2-rolling/-lyrical do, and mixing ROS distro
# layers isn't supported). specs/007-sitl-gazebo-ros2.md REQ-5.
SRC_URI = "git://github.com/eProsima/Micro-XRCE-DDS-Agent.git;protocol=https;nobranch=1"
SRCREV = "73622810d984349b80bbac0ef55fc0b694d62222"

DEPENDS = " \
    fastcdr \
    fastrtps \
"

inherit cmake

# UAGENT_SUPERBUILD is ON by default and does its own ExternalProject_Add
# live git fetch of fastcdr/fastrtps/spdlog (plus foonathan_memory_vendor)
# at build time -- same live-network-fetch-during-build antipattern hit
# repeatedly elsewhere in this project (PX4-OpticalFlow, uxrce_dds_client,
# CycloneDDS idlc). Use the versions already staged from DEPENDS instead.
# UAGENT_P2P_PROFILE is off because it would otherwise need
# find_package(microxrcedds_client) -- PX4's own fork
# (recipes-connectivity/microxrceddsclient) is pinned to PX4's exact
# uxrce_dds_client submodule revision and isn't a fit for the Agent's own
# (unrelated) P2P-discovery use of that library; P2P multi-agent discovery
# isn't needed for a single-agent PX4<->ROS2 bridge anyway.
# fastcdr/fastrtps install under ROS's own /opt/ros/jazzy prefix, not the
# plain OE ${prefix} (/usr) this recipe's own `inherit cmake` uses -- this
# is a plain, non-ROS CMake application (no `inherit ros_*`), so it
# doesn't get ros_ament_cmake.bbclass's usual CMAKE_PREFIX_PATH addition
# for that prefix. Add it explicitly.
# UAGENT_LOGGER_PROFILE is off: meta-oe's spdlog_1.17.0.bb pulls in a very
# recent fmt (v12), whose stricter type-checking rejects Micro-XRCE-DDS-
# Agent v2.4.3's own code (fmt::formatter specializations missing for
# eprosima::uxr::*EndPoint types -- code written against an older fmt
# API). Logging output is optional, not core DDS-XRCE<->DDS bridging
# functionality, so dropping it avoids the whole dependency rather than
# patching multiple call sites for a fmt version this Agent release
# predates. specs/007-sitl-gazebo-ros2.md REQ-5.
EXTRA_OECMAKE = " \
    -DCMAKE_PREFIX_PATH=${STAGING_DIR_HOST}/opt/ros/jazzy \
    -DUAGENT_SUPERBUILD=OFF \
    -DUAGENT_USE_SYSTEM_FASTCDR=ON \
    -DUAGENT_USE_SYSTEM_FASTDDS=ON \
    -DUAGENT_P2P_PROFILE=OFF \
    -DUAGENT_LOGGER_PROFILE=OFF \
    -DUAGENT_BUILD_TESTS=OFF \
"

# Installed under microxrcedds_agent (upstream's own project name), not
# ${BPN} (micro-xrce-dds-agent) -- the default -dev FILES glob doesn't
# match it. cmake package config files only.
FILES:${PN}-dev += "${datadir}/microxrcedds_agent"

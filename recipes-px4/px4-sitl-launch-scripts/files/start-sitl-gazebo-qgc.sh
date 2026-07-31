#!/bin/sh
# Starts, in order: MicroXRCEAgent, QGroundControl, and PX4 SITL -- for the
# all-in-one px4-sitl-gazebo-qgc-image.bb container (specs/007-sitl-gazebo-ros2.md
# REQ-6). Gazebo itself is not started here: PX4's own
# ROMFS/px4fmu_common/init.d-posix/px4-rc.gzsim launches `gz sim -r -s
# <world>.sdf` (headless server) plus `gz sim -g` (GUI) automatically as part
# of its normal SITL startup, as long as PX4_GZ_STANDALONE is unset and `gz`
# resolves on PATH -- see that script and the px4-autopilot-gz_1.17.0.bb
# EXTRA_OECMAKE for the real GZ_DISTRO=harmonic/gz-sim8 pin this relies on.

# px4-autopilot-gz_1.17.0.bb / patch 0004 installs these under
# ${CMAKE_INSTALL_PREFIX} (/opt/px4) -- see that patch's install(DIRECTORY
# .../Tools/simulation/gz/models DESTINATION share/gz) rules. PX4 itself
# would normally get these paths from a gz_env.sh generated into its own
# *build* tree (src/modules/simulation/gz_bridge/gz_env.sh.in), which is
# never installed/packaged -- set them here instead, pointing at the real
# installed locations.
export PX4_GZ_MODELS=/opt/px4/share/gz/models
export PX4_GZ_WORLDS=/opt/px4/share/gz/worlds
export PX4_GZ_PLUGINS=/opt/px4/lib/gz/plugins
export PX4_GZ_SERVER_CONFIG=/opt/px4/share/gz/server.config
export GZ_SIM_RESOURCE_PATH="${PX4_GZ_MODELS}:${PX4_GZ_WORLDS}"
export GZ_SIM_SYSTEM_PLUGIN_PATH="${PX4_GZ_PLUGINS}"
export GZ_SIM_SERVER_CONFIG_PATH="${PX4_GZ_SERVER_CONFIG}"

export PATH="/opt/px4/bin:${PATH}"

# Selects which ROMFS/px4fmu_common/init.d-posix/airframes/NNNN_gz_<model>
# startup file to run -- gz_x500 (a plain quadrotor) matches the ROSCon 2025
# workshop's own default and is the simplest airframe to verify a working
# pipeline against. Override by exporting PX4_SIM_MODEL before running this
# script for a different vehicle.
export PX4_SIM_MODEL="${PX4_SIM_MODEL:-gz_x500}"

PX4_HOME="${PX4_HOME:-/root/px4_home}"
mkdir -p "${PX4_HOME}"

echo "[start-sitl-gazebo-qgc] starting MicroXRCEAgent (udp4, port 8888) ..."
MicroXRCEAgent udp4 -p 8888 > "${PX4_HOME}/micro-xrce-dds-agent.log" 2>&1 &

echo "[start-sitl-gazebo-qgc] starting QGroundControl ..."
qgroundcontrol > "${PX4_HOME}/qgroundcontrol.log" 2>&1 &

echo "[start-sitl-gazebo-qgc] starting PX4 SITL (model=${PX4_SIM_MODEL}); this also launches Gazebo (server + GUI) via px4-rc.gzsim"
cd "${PX4_HOME}"
exec px4 -w "${PX4_HOME}" /opt/px4

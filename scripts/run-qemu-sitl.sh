#!/bin/sh
# Boots px4-sitl-qemu-image.bb with tap networking (specs/007-sitl-gazebo-ros2.md
# REQ-6, Scenario 2 -- see GAZEBO_ROS2.md section 4.2). Run
# scripts/qemu-tap-setup.sh once first, from the same build environment.
#
# "tap", not the runqemu default "slirp": see qemu-tap-setup.sh's own
# comments -- slirp's NAT can't carry the UDP multicast that host-side
# Gazebo and the guest's PX4 gz_bridge need to discover each other.
#
# "nographic serial": this image has no GUI of its own (Gazebo and
# QGroundControl are host-side apps in this scenario) -- a serial console is
# all that's needed to watch PX4 boot and log in for troubleshooting.
#
# Usage:
#   ./run-qemu-sitl.sh [image-name]     # default: px4-sitl-qemu-image

set -eu

MACHINE=qemux86-64
IMAGE="${1:-px4-sitl-qemu-image}"

if ! command -v runqemu >/dev/null 2>&1; then
    echo "error: runqemu not on PATH -- source your build environment first" >&2
    exit 1
fi

exec runqemu "${MACHINE}" "${IMAGE}" tap nographic serial

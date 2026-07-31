#!/bin/sh
# One-time host setup for Scenario 2's QEMU networking (specs/007-sitl-gazebo-ros2.md
# REQ-6 / GAZEBO_ROS2.md section 4.2.3).
#
# Why tap, not the runqemu default "slirp" (user-mode NAT): slirp only
# forwards TCP/UDP connections the guest itself initiates, plus whatever you
# explicitly hostfwd -- it does not pass through UDP multicast, which is how
# host-side Gazebo (or the official Gazebo container image, per
# GAZEBO_ROS2.md section 4.2.2) and PX4's gz_bridge inside the guest would
# need to find each other via gz-transport. A tap device bridges the guest
# directly onto a real Linux interface on the host, so host processes reach
# the guest (and vice versa) exactly like a second machine on the same LAN --
# multicast included.
#
# Must be run once (as root, hence sudo) before the first `runqemu ... tap`;
# the tap devices persist across reboots of the *host* until explicitly torn
# down (pass 0 as the count to runqemu-gen-tapdevs to remove them).
#
# Usage:
#   ./qemu-tap-setup.sh          # create 1 tap device (tap0)
#   ./qemu-tap-setup.sh 0        # remove all tap devices this user owns

set -eu

COUNT="${1:-1}"

if ! command -v runqemu-gen-tapdevs >/dev/null 2>&1; then
    echo "error: runqemu-gen-tapdevs not on PATH -- source your build environment" \
         "(e.g. openembedded-core/oe-init-build-env, or the equivalent" \
         "bitbake-setup environment script) first, and make sure" \
         "'bitbake qemu-helper-native' has been run at least once." >&2
    exit 1
fi

echo "Creating ${COUNT} tap device(s) owned by group $(id -g) ..."
sudo runqemu-gen-tapdevs "$(id -g)" "${COUNT}"

echo
echo "Done. Yocto's tap networking puts the first guest at 192.168.7.2" \
     "(the host end of tap0 is 192.168.7.1) -- see" \
     "GAZEBO_ROS2.md section 4.2.3 for how QGroundControl and Gazebo on the" \
     "host reach that address."

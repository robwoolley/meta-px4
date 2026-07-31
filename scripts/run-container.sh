#!/bin/sh
# Loads and runs the px4-sitl-gazebo-qgc-image.bb container tarball (Scenario 1
# of specs/007-sitl-gazebo-ros2.md REQ-6 / GAZEBO_ROS2.md section 4.1) with
# X11 forwarding and host networking.
#
# Usage:
#   ./run-container.sh <path-to-px4-sitl-gazebo-qgc-image-*.rootfs.tar.bz2>
#
# What this does, and why:
#
#  - `xhost +local:` grants any *local* process permission to connect to the
#    host's X server. This is the same trade-off this project already
#    documents elsewhere for local-only builds (LICENSE_FLAGS_ACCEPTED =
#    "commercial" in GAZEBO_ROS2.md section 2.3): fine for a local SITL
#    development container, not something to do on a shared/multi-user
#    machine or leave enabled long-term.
#
#  - `--network=host`: gz-transport (between px4-autopilot-gz's gz_bridge and
#    the Gazebo server, both *inside* this same container) and PX4's MAVLink
#    UDP heartbeat (out to a host-native QGroundControl, if you'd rather run
#    that separately instead of the one baked into this image) both need
#    real UDP multicast/broadcast reachability that a container's default
#    bridge network + NAT does not reliably provide (this is exactly the
#    concern that shaped Scenario 2's tap networking too -- see
#    GAZEBO_ROS2.md section 4.2.3). Host networking sidesteps the whole
#    problem: no port mapping needed.
#
#  - `-v /tmp/.X11-unix:/tmp/.X11-unix:ro -e DISPLAY`: X11 forwarding for
#    Gazebo's GUI client and QGroundControl, matching the ROSCon 2025 PX4
#    workshop's own primary approach (their docker_run.sh does the same).
#
#  - `-e LIBGL_ALWAYS_SOFTWARE=1`: forces Mesa's llvmpipe software
#    rasterizer inside the container instead of trying (and generally
#    failing, or silently falling back anyway) to match the host's real GPU
#    driver version over indirect GLX. Slower, but version-independent. Drop
#    this and add `--device=/dev/dri` instead if you want to try hardware
#    acceleration and know your host/container Mesa versions are close
#    enough to interoperate -- not verified either way by this script.

set -eu

TARBALL="${1:?Usage: $0 <path-to-px4-sitl-gazebo-qgc-image-*.rootfs.tar.bz2>}"
IMAGE_TAG="px4-sitl-gazebo-qgc:local"

if command -v docker >/dev/null 2>&1; then
    RUNTIME=docker
elif command -v podman >/dev/null 2>&1; then
    RUNTIME=podman
else
    echo "error: neither docker nor podman found on PATH" >&2
    exit 1
fi
echo "Using container runtime: ${RUNTIME}"

echo "Loading ${TARBALL} as ${IMAGE_TAG} ..."
"${RUNTIME}" import "${TARBALL}" "${IMAGE_TAG}"

if command -v xhost >/dev/null 2>&1; then
    echo "Granting local X11 clients access (xhost +local:) ..."
    xhost +local: >/dev/null
else
    echo "warning: xhost not found -- Gazebo/QGroundControl's GUI will likely fail to connect to your X server" >&2
fi

echo "Starting container ..."
exec "${RUNTIME}" run --rm -it \
    --network=host \
    -e DISPLAY="${DISPLAY:-:0}" \
    -e LIBGL_ALWAYS_SOFTWARE=1 \
    -v /tmp/.X11-unix:/tmp/.X11-unix:ro \
    --name px4-sitl-gazebo-qgc \
    "${IMAGE_TAG}" \
    /usr/bin/start-sitl-gazebo-qgc.sh

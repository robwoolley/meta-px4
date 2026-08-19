#!/bin/sh
# Boots px4-ros-dev-image and runs specs/008-ontarget-colcon.md's acceptance
# workload inside the guest: 'colcon build' over the staged ros2/examples
# workspace (AC-3), then a colcon-built publisher against a bitbake-packaged
# subscriber (AC-4).
#
# "slirp", not the "tap" that scripts/run-qemu-sitl.sh needs: that script
# carries UDP multicast between host-side Gazebo and the guest, which slirp's
# NAT cannot do. Here the only host->guest traffic is an ssh session, which
# slirp forwards fine (2222->22) and which needs no root setup on the host.
#
# The guest never reaches the network: ros2/examples is staged into the image
# by ros2-examples-src.bb, so this tests colcon and the on-target toolchain
# rather than QEMU's networking.
#
# Memory and cores matter here in a way they don't for the M7 images -- this
# guest is compiling C++, not just running it. Defaults below are deliberately
# generous; colcon's parallelism is kept low because the failure mode when it
# is too high is an OOM kill partway through a long build.
#
# Usage:
#   ./run-m8-ontarget-colcon-test.sh [image-name]
# Env:
#   QEMU_MEM=8192  QEMU_SMP=4  COLCON_WORKERS=2  SSH_PORT=2222
#   KEEP_RUNNING=1   leave the guest up after the test (for poking around)

set -eu

MACHINE=qemux86-64
# meta-ros-common's ros_image.bbclass does IMAGE_BASENAME:append =
# "-${ROS_DISTRO}", so this image is DEPLOYED as px4-ros-dev-image-jazzy even
# though the recipe is px4-ros-dev-image. runqemu wants the deployed basename.
IMAGE="${1:-px4-ros-dev-image-jazzy}"
QEMU_MEM="${QEMU_MEM:-8192}"
QEMU_SMP="${QEMU_SMP:-4}"
COLCON_WORKERS="${COLCON_WORKERS:-2}"
SSH_PORT="${SSH_PORT:-2222}"
WS=/home/root/examples_ws
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"

LOGDIR="${LOGDIR:-$(pwd)/m8-test-logs}"
mkdir -p "${LOGDIR}"

if ! command -v runqemu >/dev/null 2>&1; then
    echo "error: runqemu not on PATH -- source your build environment first" >&2
    exit 1
fi

# Boot from the deployed qemuboot.conf rather than by image name.
#
# Given a bare image name, runqemu shells out to 'bitbake -e <image>' to
# discover the machine configuration -- which blocks on bitbake's lock if any
# other build is running, and then simply hangs here with no output. Handing it
# the qemuboot.conf directly skips that entirely, so this test can run
# alongside a build (which is exactly how M8 and M9 were run in parallel).
QB_CONF="${QB_CONF:-}"
if [ -z "${QB_CONF}" ]; then
    DEPLOY="${DEPLOY_DIR_IMAGE:-tmp/deploy/images/${MACHINE}}"
    QB_CONF="${DEPLOY}/${IMAGE}-${MACHINE}.rootfs.qemuboot.conf"
fi
if [ ! -f "${QB_CONF}" ]; then
    echo "error: no qemuboot.conf at ${QB_CONF}" >&2
    echo "       pass one explicitly with QB_CONF=/path/to/....qemuboot.conf" >&2
    exit 1
fi
echo "=== qemuboot.conf: ${QB_CONF} ==="

SSH="ssh -p ${SSH_PORT} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
     -o LogLevel=ERROR -o ConnectTimeout=5 root@127.0.0.1"

cleanup() {
    if [ "${KEEP_RUNNING:-0}" = "1" ]; then
        echo "--- guest left running (ssh -p ${SSH_PORT} root@127.0.0.1) ---"
        return
    fi
    echo "--- shutting guest down ---"
    ${SSH} "poweroff" >/dev/null 2>&1 || true
    sleep 5
    [ -n "${QEMU_PID:-}" ] && kill "${QEMU_PID}" 2>/dev/null || true
}
trap cleanup EXIT

# "kvm" matters more here than in any other script in this directory: this
# guest compiles C++, and TCG emulation makes that roughly an order of
# magnitude slower. Falls back automatically if /dev/kvm is not usable.
KVM_OPT=kvm
if ! { [ -r /dev/kvm ] && [ -w /dev/kvm ]; }; then
    echo "warning: /dev/kvm not accessible -- falling back to TCG (much slower)" >&2
    KVM_OPT=
fi

echo "=== booting ${IMAGE} (${QEMU_MEM}M, ${QEMU_SMP} cores, ${KVM_OPT:-tcg}) ==="
# "ext4" must be explicit. The qemux86-64 machine config sets
# qb_default_fstype = ext4.zst, but this image deliberately builds plain
# uncompressed ext4 (IMAGE_FSTYPES), so without naming the type runqemu looks
# for a .ext4.zst that was never produced and fails with "Failed to find
# rootfs".
runqemu "${QB_CONF}" ext4 slirp nographic ${KVM_OPT} \
        qemuparams="-m ${QEMU_MEM} -smp ${QEMU_SMP}" \
        > "${LOGDIR}/qemu-console.log" 2>&1 &
QEMU_PID=$!

echo "=== waiting for ssh (timeout ${BOOT_TIMEOUT}s) ==="
waited=0
until ${SSH} "true" >/dev/null 2>&1; do
    sleep 5
    waited=$((waited + 5))
    if [ "${waited}" -ge "${BOOT_TIMEOUT}" ]; then
        echo "error: guest did not come up within ${BOOT_TIMEOUT}s" >&2
        echo "--- last 40 lines of console ---" >&2
        tail -40 "${LOGDIR}/qemu-console.log" >&2 || true
        exit 1
    fi
    if ! kill -0 "${QEMU_PID}" 2>/dev/null; then
        echo "error: qemu exited during boot" >&2
        tail -40 "${LOGDIR}/qemu-console.log" >&2 || true
        exit 1
    fi
done
echo "guest up after ${waited}s"

echo "=== AC-2: toolchain present ==="
${SSH} ". /opt/ros/jazzy/setup.sh; \
        echo \"cmake:  \$(cmake --version | head -1)\"; \
        echo \"g++:    \$(g++ --version | head -1)\"; \
        echo \"colcon: \$(colcon version-check 2>&1 | head -3 | tr '\n' ' ')\"; \
        echo \"ROS:    \${AMENT_PREFIX_PATH:-UNSET}\"" \
    2>&1 | tee "${LOGDIR}/ac2-toolchain.log"

echo "=== AC-3: colcon build of ros2/examples in the guest ==="
echo "(this is the long one -- compiling C++ under emulation)"
${SSH} "set -e; . /opt/ros/jazzy/setup.sh; cd ${WS}; \
        colcon build --parallel-workers ${COLCON_WORKERS} \
                     --cmake-args -DBUILD_TESTING=OFF" \
    2>&1 | tee "${LOGDIR}/ac3-colcon-build.log"

echo "=== AC-3: verifying artifacts exist (not just a zero exit) ==="
${SSH} "ls -l ${WS}/install/examples_rclcpp_minimal_publisher/lib/examples_rclcpp_minimal_publisher/ \
        ${WS}/install/examples_rclcpp_minimal_subscriber/lib/examples_rclcpp_minimal_subscriber/ 2>&1; \
        echo '--- built package count ---'; ls ${WS}/install | wc -l" \
    2>&1 | tee "${LOGDIR}/ac3-artifacts.log"

echo "=== AC-4: colcon-built publisher <-> packaged subscriber ==="
# The subscriber is the bitbake-built one from /opt/ros/jazzy; the publisher is
# the one colcon just built in the workspace. If the workspace overlay were
# ABI-incompatible with the packaged ROS 2, this is where it shows up.
# The subscriber's PID is captured rather than matched by name on the way out:
# this image has no procps, so pkill/killall do not exist in the guest.
${SSH} "set -e; . /opt/ros/jazzy/setup.sh; . ${WS}/install/setup.sh; \
        /opt/ros/jazzy/lib/examples_rclcpp_minimal_subscriber/subscriber_member_function \
            > /tmp/sub.log 2>&1 & \
        SUBPID=\$!; \
        sleep 3; \
        timeout 15 ${WS}/install/examples_rclcpp_minimal_publisher/lib/examples_rclcpp_minimal_publisher/publisher_member_function \
            > /tmp/pub.log 2>&1 || true; \
        sleep 2; kill \${SUBPID} 2>/dev/null || true; \
        echo '--- publisher (colcon-built) ---'; head -5 /tmp/pub.log; \
        echo '--- subscriber (bitbake-packaged) ---'; head -5 /tmp/sub.log" \
    2>&1 | tee "${LOGDIR}/ac4-pubsub.log"

echo
echo "=== done -- logs in ${LOGDIR} ==="

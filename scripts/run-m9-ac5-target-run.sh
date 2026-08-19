#!/bin/sh
# Finishes specs/009-sdk-colcon.md AC-5: takes an executable cross-built on the
# host by the SDK (scripts/run-m9-sdk-colcon-test.sh) and runs it on a target,
# paired with the bitbake-packaged subscriber already in the image.
#
# 'file' reporting a target ELF is necessary but not sufficient -- on a
# same-architecture SDK it cannot even distinguish host from target by machine
# type (see spec 009 AC-5). Actually running the thing is the proof.
#
# Reuses M8's px4-ros-dev-image as the target: it already carries the ROS 2
# runtime the cross-built binary links against, and the packaged
# examples-rclcpp-minimal-subscriber to pair with. Only the executable is
# copied in; nothing is built in the guest.
#
# Usage (from the build directory, with the SDK workspace already built):
#   ./run-m9-ac5-target-run.sh [path-to-cross-built-publisher]
# Env:
#   WS_DIR=...  colcon workspace (default: ./m9-examples-ws)
#   QEMU_MEM=2048  QEMU_SMP=2  SSH_PORT=2222  KEEP_RUNNING=1

set -eu

MACHINE=qemux86-64
IMAGE="${IMAGE:-px4-ros-dev-image-jazzy}"
WS_DIR="${WS_DIR:-$(pwd)/m9-examples-ws}"
QEMU_MEM="${QEMU_MEM:-2048}"
QEMU_SMP="${QEMU_SMP:-2}"
SSH_PORT="${SSH_PORT:-2222}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
LOGDIR="${LOGDIR:-$(pwd)/m9-ac5-logs}"
mkdir -p "${LOGDIR}"

PUB="${1:-${WS_DIR}/install/examples_rclcpp_minimal_publisher/lib/examples_rclcpp_minimal_publisher/publisher_member_function}"
if [ ! -f "${PUB}" ]; then
    echo "error: no cross-built publisher at ${PUB}" >&2
    echo "       run scripts/run-m9-sdk-colcon-test.sh first" >&2
    exit 1
fi

echo "=== host-side view of the artifact ==="
file "${PUB}" | tee "${LOGDIR}/ac5-host-file.log"

if ! command -v runqemu >/dev/null 2>&1; then
    echo "error: runqemu not on PATH -- source your build environment first" >&2
    exit 1
fi

# Same two gotchas as the M8 harness: boot from the deployed qemuboot.conf so
# runqemu does not shell out to 'bitbake -e' (which blocks on the bitbake lock
# if any build is running), and name the fstype explicitly because the machine
# default is ext4.zst while this image builds plain ext4.
QB_CONF="${QB_CONF:-tmp/deploy/images/${MACHINE}/${IMAGE}-${MACHINE}.rootfs.qemuboot.conf}"
if [ ! -f "${QB_CONF}" ]; then
    echo "error: no qemuboot.conf at ${QB_CONF}" >&2
    exit 1
fi

KVM_OPT=kvm
{ [ -r /dev/kvm ] && [ -w /dev/kvm ]; } || KVM_OPT=

SSH_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=5"
SSH="ssh -p ${SSH_PORT} ${SSH_OPTS} root@127.0.0.1"
SCP="scp -P ${SSH_PORT} ${SSH_OPTS}"

cleanup() {
    if [ "${KEEP_RUNNING:-0}" = "1" ]; then
        echo "--- guest left running (ssh -p ${SSH_PORT} root@127.0.0.1) ---"
        return
    fi
    ${SSH} "poweroff" >/dev/null 2>&1 || true
    sleep 5
    [ -n "${QEMU_PID:-}" ] && kill "${QEMU_PID}" 2>/dev/null || true
}
trap cleanup EXIT

echo "=== booting ${IMAGE} ==="
runqemu "${QB_CONF}" ext4 slirp nographic ${KVM_OPT} \
        qemuparams="-m ${QEMU_MEM} -smp ${QEMU_SMP}" \
        > "${LOGDIR}/qemu-console.log" 2>&1 &
QEMU_PID=$!

waited=0
until ${SSH} "true" >/dev/null 2>&1; do
    sleep 5
    waited=$((waited + 5))
    if [ "${waited}" -ge "${BOOT_TIMEOUT}" ]; then
        echo "error: guest did not come up within ${BOOT_TIMEOUT}s" >&2
        tail -40 "${LOGDIR}/qemu-console.log" >&2 || true
        exit 1
    fi
    kill -0 "${QEMU_PID}" 2>/dev/null || { echo "error: qemu exited" >&2; exit 1; }
done
echo "guest up after ${waited}s"

echo "=== copying the SDK-cross-built publisher into the guest ==="
${SCP} "${PUB}" root@127.0.0.1:/tmp/sdk_publisher 2>&1 | tail -2
${SSH} "chmod +x /tmp/sdk_publisher; echo '--- as the target sees it ---'; \
        file /tmp/sdk_publisher 2>/dev/null || true; \
        echo '--- dynamic dependencies resolve? ---'; \
        . /opt/ros/jazzy/setup.sh; ldd /tmp/sdk_publisher | grep -cE 'not found' \
            && echo 'UNRESOLVED SYMBOLS ABOVE' || echo 'all libraries resolved'" \
    2>&1 | tee "${LOGDIR}/ac5-target-file.log"

echo "=== AC-5 runtime: SDK-cross-built publisher -> packaged subscriber ==="
# Subscriber is the bitbake-built one from /opt/ros/jazzy; publisher is the one
# the SDK cross-compiled on the host. PID captured rather than matched by name:
# this image has no procps, so pkill/killall do not exist.
${SSH} "set -e; . /opt/ros/jazzy/setup.sh; \
        /opt/ros/jazzy/lib/examples_rclcpp_minimal_subscriber/subscriber_member_function \
            > /tmp/sub.log 2>&1 & \
        SUBPID=\$!; \
        sleep 3; \
        timeout 15 /tmp/sdk_publisher > /tmp/pub.log 2>&1 || true; \
        sleep 2; kill \${SUBPID} 2>/dev/null || true; \
        echo '--- publisher (SDK cross-built on host) ---'; head -5 /tmp/pub.log; \
        echo '--- subscriber (bitbake-packaged, in image) ---'; head -5 /tmp/sub.log" \
    2>&1 | tee "${LOGDIR}/ac5-runtime.log"

echo
echo "=== done -- logs in ${LOGDIR} ==="

#!/bin/bash
# bash, not sh: the SDK's own environment-setup script is written for bash,
# and the PATH diff below uses process substitution.
#
# Installs the ros2-image-sdktest SDK and runs specs/009-sdk-colcon.md's
# acceptance workload on the HOST: cross-compile ros2/examples with colcon
# against the SDK's toolchain file (AC-4), and confirm the results are target
# binaries (AC-5).
#
# Also checks the two things this milestone had to carry from upstream:
#   AC-2  ros-sdk-env (meta-ros PR #1261, still open) exports PYTHON_SOABI and
#         friends automatically, so no hand-exported variables are needed.
#   AC-3  the jazzy forward-port of skip_shell_path.patch actually works --
#         the host PATH must NOT gain the target sysroot's ROS bin directory.
#         This is the one check that proves the patch does something, as
#         opposed to merely applying.
#
# Usage:
#   ./run-m9-sdk-colcon-test.sh [path-to-sdk-installer.sh]
# Env:
#   SDK_DIR=...        where to install (default: ./m9-sdk)
#   WS_DIR=...         colcon workspace (default: ./m9-examples-ws)

set -eu

# Same commit the on-target milestone stages (ros2-examples-src_0.19.7.bb) and
# the same one meta-ros generates its examples-*_0.19.7-1 recipes from, so the
# three build paths are comparing like with like.
EXAMPLES_URL=https://github.com/ros2/examples
EXAMPLES_REV=07008852303f2a35a91c65d78046b274a35477ea

SDK_DIR="${SDK_DIR:-$(pwd)/m9-sdk}"
WS_DIR="${WS_DIR:-$(pwd)/m9-examples-ws}"
LOGDIR="${LOGDIR:-$(pwd)/m9-test-logs}"
mkdir -p "${LOGDIR}"

INSTALLER="${1:-}"
if [ -z "${INSTALLER}" ]; then
    INSTALLER=$(ls -1t tmp/deploy/sdk/*ros2-image-sdktest*.sh 2>/dev/null | head -1 || true)
fi
if [ -z "${INSTALLER}" ] || [ ! -f "${INSTALLER}" ]; then
    echo "error: no SDK installer found -- pass one, or run this from the build" >&2
    echo "       directory after: bitbake ros2-image-sdktest -c populate_sdk" >&2
    exit 1
fi
echo "=== SDK installer: ${INSTALLER} ==="

if [ ! -d "${SDK_DIR}" ]; then
    echo "=== installing SDK to ${SDK_DIR} ==="
    # -y accept defaults, -d target dir. The installer refuses a non-empty dir.
    sh "${INSTALLER}" -y -d "${SDK_DIR}" 2>&1 | tail -20
else
    echo "=== reusing existing SDK at ${SDK_DIR} ==="
fi

ENV_SETUP=$(ls -1 "${SDK_DIR}"/environment-setup-* 2>/dev/null | head -1 || true)
if [ -z "${ENV_SETUP}" ]; then
    echo "error: no environment-setup-* script in ${SDK_DIR}" >&2
    exit 1
fi
echo "=== env script: ${ENV_SETUP} ==="

# Capture the pre-source PATH so AC-3 can be judged against it.
PATH_BEFORE="${PATH}"

# Everything from here runs in a subshell that has sourced the SDK env.
# shellcheck disable=SC1090
(
    set +u
    . "${ENV_SETUP}"
    set -u

    {
        echo "=== AC-2: SDK environment ==="
        echo "colcon:                 $(command -v colcon || echo 'NOT FOUND')"
        echo "OE_CMAKE_TOOLCHAIN_FILE: ${OE_CMAKE_TOOLCHAIN_FILE:-UNSET}"
        echo "PYTHON_SOABI:            ${PYTHON_SOABI:-UNSET}"
        echo "PYTHON3_NUMPY_INCLUDE_DIR: ${PYTHON3_NUMPY_INCLUDE_DIR:-UNSET}"
        echo "AMENT_SKIP_SHELL_PATH:   ${AMENT_SKIP_SHELL_PATH:-UNSET}"
        echo "AMENT_PREFIX_PATH:       ${AMENT_PREFIX_PATH:-UNSET}"
        echo "OECORE_TARGET_SYSROOT:   ${OECORE_TARGET_SYSROOT:-UNSET}"
    } 2>&1 | tee "${LOGDIR}/ac2-sdk-env.log"

    # Bail clearly rather than dying on 'unbound variable' under set -u: every
    # check below is meaningless without this, so say so in as many words.
    if [ -z "${OECORE_TARGET_SYSROOT:-}" ]; then
        echo "error: OECORE_TARGET_SYSROOT unset after sourcing ${ENV_SETUP}" >&2
        echo "       -- this is not a normal OE SDK environment; aborting" >&2
        exit 1
    fi

    echo "=== AC-3: host PATH must not contain the target sysroot's ROS bin ==="
    ac3_status=PASS
    {
        if echo "${PATH}" | tr ':' '\n' | grep -qF "${OECORE_TARGET_SYSROOT}/opt/ros/"; then
            echo "FAIL: PATH contains ${OECORE_TARGET_SYSROOT}/opt/ros/... entries:"
            echo "${PATH}" | tr ':' '\n' | grep -F "${OECORE_TARGET_SYSROOT}/opt/ros/"
            echo "=> skip_shell_path.patch is not taking effect"
            ac3_status=FAIL
        else
            echo "PASS: no ${OECORE_TARGET_SYSROOT}/opt/ros/* entries on PATH"
        fi
        echo "--- PATH entries added by sourcing the SDK env ---"
        echo "${PATH}" | tr ':' '\n' | grep -vxF -f <(echo "${PATH_BEFORE}" | tr ':' '\n') || true
    } 2>&1 | tee "${LOGDIR}/ac3-path.log"
    # ac3_status is set inside the { } above, which runs in this shell (brace
    # group, not a subshell), so it survives -- but the pipe to tee does put
    # the group in a subshell, so re-derive it from the log instead.
    grep -q "^FAIL:" "${LOGDIR}/ac3-path.log" && ac3_status=FAIL || ac3_status=PASS

    if [ ! -d "${WS_DIR}/src/examples" ]; then
        echo "=== fetching ros2/examples @ ${EXAMPLES_REV} ==="
        mkdir -p "${WS_DIR}/src"
        git clone -q "${EXAMPLES_URL}" "${WS_DIR}/src/examples"
        git -C "${WS_DIR}/src/examples" checkout -q "${EXAMPLES_REV}"
    fi

    echo "=== AC-4: colcon cross-build (no hand-exported variables) ==="
    cd "${WS_DIR}"
    # PIPESTATUS, not $?: piping into tee would otherwise report tee's exit
    # status and a failed cross-build would silently look like a pass.
    set +e
    colcon build \
        --cmake-args \
            "-DCMAKE_TOOLCHAIN_FILE=${OE_CMAKE_TOOLCHAIN_FILE}" \
            -DBUILD_TESTING=OFF \
        2>&1 | tee "${LOGDIR}/ac4-colcon-build.log"
    ac4_rc=${PIPESTATUS[0]}
    set -e
    echo "colcon build exit status: ${ac4_rc}"

    # AC-5 still runs even if AC-4 failed -- whatever did get built is worth
    # inspecting, and a partial result is more informative than none.
    echo "=== AC-5: are the artifacts target binaries? ==="
    {
        # The -o branches are parenthesised so the implicit -print applies to
        # both, not just the last one.
        find "${WS_DIR}/install" -type f \
             \( -name 'publisher_member_function' \
                -o -name 'subscriber_member_function' \) | while read -r f; do
            echo "--- ${f}"
            file "${f}"
        done
        echo "--- for comparison, a host binary ---"
        file "$(command -v git)"
    } 2>&1 | tee "${LOGDIR}/ac5-elf-type.log"

    echo
    echo "=== summary ==="
    echo "AC-3 (host PATH clean):   ${ac3_status}"
    echo "AC-4 (colcon cross-build): $([ "${ac4_rc}" -eq 0 ] && echo PASS || echo FAIL)"
    [ "${ac3_status}" = PASS ] && [ "${ac4_rc}" -eq 0 ]
)
rc=$?

echo
echo "=== done -- logs in ${LOGDIR} ==="
echo "Copy an executable to a running target to finish AC-5's runtime half."
exit "${rc}"

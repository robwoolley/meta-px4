# Carried from meta-ros PR #1261 ("{ros2} Add required ROS2 SDK environment
# variables") by Stephen Street <stephen@redrocketcomputing.com>, which is
# still OPEN against master-next and has not reached wrynose.
# specs/009-sdk-colcon.md REQ-3. Drop this recipe once that PR merges and
# lands in the meta-ros branch this project builds against.
#
# Without it, every ROS 2 colcon cross-build in the SDK needs the developer to
# hand-export PYTHON_SOABI (with the right target tuple), PYTHONPATH, and
# friends -- exactly the hardcoded 'cpython-310-aarch64-linux-gnu' incantation
# PR #1215's instructions carry. Computing them from the machine config is the
# whole point.

SUMMARY = "Add ROS2 SDK environment variables"
HOMEPAGE = "https://github.com/ros/meta-ros"
SECTION = "devel"

LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://${COREBASE}/meta/files/common-licenses/Apache-2.0;md5=89aea4e17d99a7cacdbeed46a0096b10"

inherit python3native ros_opt_prefix

FILES:${PN} = "${SDKPATHNATIVE}/post-relocate-setup.d/ros-sdk-env.sh ${SDKPATHNATIVE}/post-relocate-setup.d/ros-sdk-setup.sh"

# Deviation from PR #1261, required by this OE version. The PR has:
#     S = "${WORKDIR}/sources"
#     UNPACKDIR = "${S}"
# which wrynose's do_unpack rejects outright:
#     ERROR: S should be set relative to UNPACKDIR, e.g. replace WORKDIR with
#     UNPACKDIR in "S = ${WORKDIR}/sources"
# UNPACKDIR already defaults to ${WORKDIR}/sources (bitbake.conf), so pointing
# S at UNPACKDIR gives the identical path while satisfying the check. This
# recipe has no SRC_URI at all -- S is only ever a scratch directory for the
# two generated shell fragments below.
S = "${UNPACKDIR}"

# PR #1261 computes PYTHON_SOABI here at parse time, copying the approach in
# meta-ros/meta-ros2/classes/ros_ament_cmake.bbclass:
#     PYTHON_SOABI_ARCH = "${TUNE_ARCH}-${TARGET_OS}"
#     PYTHON_SOABI = "cpython-...-${PYTHON_SOABI_ARCH}-gnu"
# That works in a target recipe but NOT here: this is a class-nativesdk
# recipe, where TUNE_ARCH is empty. 'bitbake -e nativesdk-ros-sdk-env' shows
# TUNE_ARCH="" and, as a direct result, PYTHON_SOABI="cpython-314--linux-gnu"
# -- note the empty field where the architecture should be. The SDK's real
# target SOABI is cpython-314-x86_64-linux-gnu.
#
# There is no parse-time variable to substitute, either. In nativesdk context
# TARGET_ARCH and TUNE_PKGARCH both describe the SDK *host*; they happen to
# read x86_64 for an x86_64-host/x86_64-target SDK, so they would paper over
# the bug here and produce a wrong answer for any cross-architecture SDK --
# exactly the case PR #1215's hardcoded 'aarch64' was about. DEFAULTTUNE holds
# the tune name (x86-64-v3), not the architecture (x86_64).
#
# So both values below are derived at SDK setup time from the installed target
# sysroot instead, which is authoritative whatever the target turns out to be.

# Sourcing the target's ROS setup is what puts AMENT_PREFIX_PATH in the
# environment, without which ament cannot find the target's ROS packages and
# no cross-build works. It is also what would otherwise pollute the host PATH,
# which is precisely what AMENT_SKIP_SHELL_PATH (and jazzy's forward-ported
# skip_shell_path.patch) exists to prevent. Defaulted on, weakly, so it can
# still be turned off in local.conf.
ROS_SDK_UNIFY ??= "bash"

do_install:append:class-nativesdk () {
    # No SRC_URI means nothing guarantees S exists by do_install time.
    mkdir -p ${S}

    # Quoted heredoc: this block is runtime shell for the SDK's setup, not
    # something bitbake should expand. It contains no ${...}, so bitbake
    # passes it through untouched.
    cat > ${S}/ros-sdk-env.sh <<'ROSSDKEOF'
# Derive the target Python SOABI from a real extension module in the target
# sysroot, e.g. array.cpython-314-x86_64-linux-gnu.so -> the middle field.
_ros_sdk_so=$(ls $OECORE_TARGET_SYSROOT/usr/lib/python*/lib-dynload/*.cpython-*.so 2>/dev/null | head -1)
if [ -n "$_ros_sdk_so" ]; then
    PYTHON_SOABI=$(basename "$_ros_sdk_so" | sed -e 's/^[^.]*\.//' -e 's/\.so$//')
    export PYTHON_SOABI
fi
unset _ros_sdk_so

# The SDK ships a CMake toolchain file but names it in no environment
# variable, so PR #1215's documented
# '-DCMAKE_TOOLCHAIN_FILE=${OE_CMAKE_TOOLCHAIN_FILE}' expands to nothing.
# Point it at the real file.
_ros_sdk_tc=$(ls $OECORE_NATIVE_SYSROOT/usr/share/cmake/*-toolchain.cmake 2>/dev/null | head -1)
if [ -n "$_ros_sdk_tc" ]; then
    OE_CMAKE_TOOLCHAIN_FILE=$_ros_sdk_tc
    export OE_CMAKE_TOOLCHAIN_FILE
fi
unset _ros_sdk_tc
ROSSDKEOF

    echo "export PYTHON3_NUMPY_INCLUDE_DIR="'$OECORE_TARGET_SYSROOT'"/usr/lib/python${PYTHON_BASEVERSION}/site-packages/numpy/core/include" >> ${S}/ros-sdk-env.sh
    echo "export PYTHONWARNINGS=ignore" >> ${S}/ros-sdk-env.sh
    echo "export AMENT_SKIP_SHELL_PATH=1" >> ${S}/ros-sdk-env.sh

    if [ -n "${ROS_SDK_UNIFY}" ]; then
        echo '. $OECORE_TARGET_SYSROOT'"${ros_base_prefix}/setup.${ROS_SDK_UNIFY}" >> ${S}/ros-sdk-env.sh
    fi
    echo "export PYTHONPATH="'$OECORE_TARGET_SYSROOT'"/usr/lib/python${PYTHON_BASEVERSION}/site-packages:"'$PYTHONPATH' >> ${S}/ros-sdk-env.sh

    mkdir -p ${D}${SDKPATHNATIVE}/post-relocate-setup.d
    install -m 644 ${UNPACKDIR}/ros-sdk-env.sh ${D}${SDKPATHNATIVE}/post-relocate-setup.d/ros-sdk-env.sh

    echo "#! /usr/bin/env sh" > ${S}/ros-sdk-setup.sh
    echo 'mkdir -p $OECORE_NATIVE_SYSROOT/environment-setup.d' >> ${S}/ros-sdk-setup.sh
    echo 'install -m 755 $OECORE_NATIVE_SYSROOT/post-relocate-setup.d/ros-sdk-env.sh $OECORE_NATIVE_SYSROOT/environment-setup.d/ros-sdk-env.sh'  >> ${S}/ros-sdk-setup.sh

    install -m 755 ${UNPACKDIR}/ros-sdk-setup.sh ${D}${SDKPATHNATIVE}/post-relocate-setup.d/ros-sdk-setup.sh
}

BBCLASSEXTEND = " nativesdk"

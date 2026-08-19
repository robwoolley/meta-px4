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

# This was pulled from meta-ros/meta-ros2/classes/ros_ament_cmake.bbclass
PYTHON_SOABI_ARCH = "${TUNE_ARCH}-${TARGET_OS}"
PYTHON_SOABI_ARCH_SUFFIX = "-gnu"

# The suffix is already included in TARGET_OS
PYTHON_SOABI_ARCH_SUFFIX:arm = ""

# Another exception is i686 TUNE_ARCH in dunfell and newer with this change:
# https://git.openembedded.org/openembedded-core/commit/?h=dunfell&id=6beab388e73b3ac6157650855a6c1fb1d71e8015
PYTHON_SOABI_ARCH:i686 = "i386-${TARGET_OS}"

PYTHON_SOABI = "cpython-${@d.getVar('PYTHON_BASEVERSION').replace('.', '')}${PYTHON_ABI}-${PYTHON_SOABI_ARCH}${PYTHON_SOABI_ARCH_SUFFIX}"

do_install:append:class-nativesdk () {
    # No SRC_URI means nothing guarantees S exists by do_install time.
    mkdir -p ${S}

    echo "export PYTHON_SOABI=${PYTHON_SOABI}" > ${S}/ros-sdk-env.sh
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

SUMMARY = "pkg_resources module for tools still using the legacy setuptools API"
DESCRIPTION = "setuptools 82.0.0 (Feb 2026) removed the bundled pkg_resources \
module entirely; there is no standalone PyPI replacement, and the \
community-recommended fix for anything still importing it is to pin an \
older setuptools (https://github.com/pypa/setuptools/issues/5174). PX4's \
libuavcan DSDL compiler (src/drivers/uavcan/libdronecan's libuavcan \
submodule) still imports pkg_resources directly, and oe-core's own \
python3-setuptools-native is pinned at 82.0.1 (no pkg_resources) -- so \
this recipe fetches the last pre-removal release (81.0.0) purely to \
extract that one module, installed alongside (not replacing) the main \
setuptools-native package."

HOMEPAGE = "https://pypi.org/project/setuptools"
SECTION = "devel/python"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://LICENSE;md5=141643e11c48898150daa83802dbc65f"

PYPI_PACKAGE = "setuptools"

inherit pypi python3native

SRC_URI[sha256sum] = "487b53915f52501f0a79ccfd0c02c165ffe06631443a886740b91af4b7a5845a"

S = "${UNPACKDIR}/${PYPI_PACKAGE}-${PV}"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

do_install() {
    install -d ${D}${PYTHON_SITEPACKAGES_DIR}/pkg_resources
    cp -r ${S}/pkg_resources/. ${D}${PYTHON_SITEPACKAGES_DIR}/pkg_resources/
    rm -rf ${D}${PYTHON_SITEPACKAGES_DIR}/pkg_resources/tests
}

FILES:${PN} = "${PYTHON_SITEPACKAGES_DIR}/pkg_resources"

BBCLASSEXTEND = "native"

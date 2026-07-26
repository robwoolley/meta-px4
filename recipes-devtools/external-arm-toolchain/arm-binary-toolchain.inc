# Copyright (C) 2019 Garmin Ltd. or its subsidiaries
# Released under the MIT license (see COPYING.MIT for the terms)
#
# Vendored from git.yoctoproject.org/meta-arm
# (meta-arm-toolchain/recipes-devtools/external-arm-toolchain/), which
# carries this same MIT-licensed .inc unmodified across its
# gcc-arm-none-eabi and gcc-aarch64-none-elf recipes. meta-px4 needs
# only the arm-none-eabi target (specs/000 section 4.1, Option B:
# package the prebuilt toolchain rather than build one via oe-core's
# own cross machinery) and vendors this rather than adding a
# dependency on the whole meta-arm-toolchain layer, matching this
# layer's existing convention of carrying its own devtools recipes
# (python3-empy, pyros-genmsg, etc.) instead of pulling in more
# external layers.

INHIBIT_DEFAULT_DEPS = "1"

FILES:${PN} = "${libexecdir} ${bindir}"

BINNAME = "${@d.getVar("BPN").strip("gcc-")}"

do_install() {
    install -d ${D}${bindir} ${D}${libexecdir}/${BP}/
    cp -r ${S}/. ${D}${libexecdir}/${BP}

    # Symlink all executables into bindir
    for f in ${D}${libexecdir}/${BP}/bin/*; do
        ln -rs $f ${D}${bindir}/$(basename $f)
    done
}

INSANE_SKIP:${PN} = "already-stripped libdir staticdev file-rdeps arch dev-so"

INHIBIT_SYSROOT_STRIP = "1"
INHIBIT_PACKAGE_STRIP = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT = "1"

# Need to mark these as private until do_package's soname-finder only looks in $libdir
PRIVATE_LIBS = "libgcc_s.so.1 libstdc++.so.6"

BBCLASSEXTEND = "native nativesdk"

# Skipping file deps - we don't control the dependencies for prebuilt libraries, resulting in
# nothing provides libcrypt.so.1()(64bit) needed by nativesdk-gcc-arm-none-eabi
# when packaged as RPM for SDK.
SKIP_FILEDEPS = "1"

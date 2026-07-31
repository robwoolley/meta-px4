SUMMARY = "QGroundControl, packaged as its official upstream AppImage"
DESCRIPTION = "QGroundControl is normally run as a host-native AppImage \
against PX4 SITL (see GAZEBO_ROS2.md section 2.4 and specs/000-architecture.md \
section 4.6 -- same reasoning as this layer deliberately not building Renode). \
This recipe exists only for the all-in-one container scenario \
(px4-sitl-gazebo-qgc-image.bb), where QGroundControl has to actually ship \
*inside* the image rather than run on the operator's own machine. It does not \
build QGroundControl from source -- it just fetches and repackages the same \
AppImage a host user would otherwise download by hand."
HOMEPAGE = "https://qgroundcontrol.com/"

# QGroundControl is dual-licensed (Apache-2.0 OR GPL-3.0-only); the AppImage
# itself is a build of the Apache-licensed tree. Only LICENSE-APACHE is
# fetched here (fetching the actual source tree just for its license file
# would be wasteful for a binary-repackaging recipe).
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://LICENSE-APACHE;md5=86d3f3a95c324c9479bd8986968f4327"

# v5.0.8 (2025-10-09), the latest tagged *stable* release at the time this
# recipe was written -- v5.1.0 is a same-day release-candidate tag and
# deliberately not used, matching this layer's general preference for a
# known, already-shaken-out version over whatever happens to be newest
# (e.g. micro-xrce-dds-agent's own v2.4.3 pin, specs/007 REQ-5).
SRC_URI = "https://github.com/mavlink/qgroundcontrol/releases/download/v${PV}/QGroundControl-x86_64.AppImage;downloadfilename=QGroundControl-x86_64.AppImage;name=appimage \
           https://raw.githubusercontent.com/mavlink/qgroundcontrol/v${PV}/LICENSE-APACHE;downloadfilename=LICENSE-APACHE;name=license"

SRC_URI[appimage.sha256sum] = "06969c67ef58ea063def0a8271447a1cc385438c4a7df36813315b4475146737"
SRC_URI[license.sha256sum] = "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"

# Both fetched files land directly in UNPACKDIR (downloadfilename, no
# archive to extract) -- default S (${UNPACKDIR}/${BP}) is a versioned
# subdirectory that's never created for a plain file download.
S = "${UNPACKDIR}"

# Fetched, not built: this is a prebuilt upstream binary.
do_configure[noexec] = "1"
do_compile[noexec] = "1"

# Only ever meaningful on the same x86_64 target this layer's SITL work
# already targets -- upstream doesn't publish an aarch64 QGroundControl
# AppImage, and PX4 SITL itself is x86_64-only here (qemux86-64/genericx86-64).
# Deliberately NOT `inherit allarch`: this is an x86_64-only binary blob, not
# arch-independent content.
COMPATIBLE_HOST = "x86_64.*-linux"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${UNPACKDIR}/QGroundControl-x86_64.AppImage ${D}${bindir}/qgroundcontrol.AppImage

    # --appimage-extract-and-run sidesteps needing FUSE (libfuse2 + squashfuse,
    # plus /dev/fuse + CAP_SYS_ADMIN) inside a plain `docker run` container --
    # it self-extracts to a temp dir on every launch and execs from there
    # instead of mounting a SquashFS via FUSE. Costs a couple of extra seconds
    # of startup, not a problem for a SITL ground control station.
    cat > ${D}${bindir}/qgroundcontrol <<'EOF'
#!/bin/sh
exec ${bindir}/qgroundcontrol.AppImage --appimage-extract-and-run "$@"
EOF
    sed -i "s|\${bindir}|${bindir}|" ${D}${bindir}/qgroundcontrol
    chmod 0755 ${D}${bindir}/qgroundcontrol
}

FILES:${PN} += "${bindir}/qgroundcontrol.AppImage ${bindir}/qgroundcontrol"

# The AppImage bundles almost all of Qt itself; what it does *not* bundle
# (base X11/XCB client libs, Mesa GL, fontconfig, ALSA) has not been
# empirically enumerated against this target's exact glibc/Mesa versions --
# not claimed here as RDEPENDS. px4-sitl-gazebo-qgc-image.bb's own package
# set (needed for Gazebo's GUI anyway) is what actually provides them.
INSANE_SKIP:${PN} += "already-stripped"

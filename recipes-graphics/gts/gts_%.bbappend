# meta-ros-common's gts_0.7.6.bb points SRC_URI at
# git://anonscm.debian.org/git/debian-science/packages/gts.git -- Debian's
# Alioth git hosting was retired years ago and the domain no longer serves
# git traffic (do_fetch: exit code 128). Debian migrated this repo to Salsa;
# same content, same SRCREV (7cfcef0d9fc44f4fe424455027e78b73864590ec)
# confirmed present there. gts is a transitive DEPENDS of gz-common5
# (Gazebo Harmonic). specs/007-sitl-gazebo-ros2.md REQ-2.
SRC_URI = "git://salsa.debian.org/science-team/gts.git;protocol=https;branch=master \
           file://fix-includes.patch \
           file://fix-predicates_init.patch \
"

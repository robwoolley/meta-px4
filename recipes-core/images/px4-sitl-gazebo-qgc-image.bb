SUMMARY = "All-in-one container: PX4 SITL, Gazebo Harmonic, MicroXRCEAgent, and QGroundControl"
DESCRIPTION = "Scenario 1 of specs/007-sitl-gazebo-ros2.md REQ-6 -- everything \
needed to fly PX4 SITL against real Gazebo simulation packed into a single \
OCI-runnable container image, modeled on the container structure used by the \
ROSCon 2025 PX4 workshop (github.com/Dronecode/roscon-25-workshop docs/setup.md): \
one container holding the flight stack, the simulator, and the ground \
control station together, with GUI apps (Gazebo's client, QGroundControl) \
reaching the operator's screen via X11 forwarding rather than VNC. See \
GAZEBO_ROS2.md section 4.1 for how to build, load, and run this image, \
including the X11/host-networking setup on the host side."
LICENSE = "MIT"

IMAGE_INSTALL = " \
    packagegroup-core-boot \
    px4-autopilot-gz \
    px4-msgs \
    px4-ros2-cpp \
    micro-xrce-dds-agent \
    qgroundcontrol-appimage \
    px4-sitl-launch-scripts \
    gz-sim8 \
    gz-tools2 \
    mesa \
    libgallium \
    fontconfig \
"

# packagegroup-core-boot pulls in ssh-pregen-hostkeys (dropbear host keys),
# which needs a fully-populated /etc and a real init system to be meaningful
# -- neither applies to a container whose PID 1 is
# start-sitl-gazebo-qgc.sh. Same removal the oe-core "container" IMAGE_FSTYPE
# selftest itself makes (meta/lib/oeqa/selftest/cases/containerimage.py).
IMAGE_INSTALL:remove = "ssh-pregen-hostkeys"

IMAGE_LINGUAS = " "

inherit core-image

# "container" (image-container.bbclass, built into oe-core) just tars up the
# rootfs -- no kernel needed in the *image itself*, no bootloader, no
# partition table -- for `docker load`/`podman load` or any other OCI-ish
# runtime that can import a plain tarball as a root filesystem layer.
#
# PREFERRED_PROVIDER_virtual/kernel = "linux-dummy" is oe-core's own
# documented way to avoid building a real kernel for exactly this case
# (see meta/lib/oeqa/selftest/cases/containerimage.py) -- but that only
# actually takes effect set in a *configuration* file (local.conf,
# distro/machine .conf), not inside a recipe's own metadata; verified by
# building this image with it set right here, which still built a full
# linux-yocto for qemux86-64 (BUILD_MACHINE already needs one for the
# *other* image in this layer, px4-sitl-qemu-image, so nothing is
# actually broken -- this build just also produces a kernel this specific
# image itself never uses). Left set here as accurate documentation of
# intent; not moved to local.conf since that would also affect
# px4-sitl-qemu-image, which genuinely needs a real kernel.
PREFERRED_PROVIDER_virtual/kernel = "linux-dummy"
IMAGE_FSTYPES = "container"

# sysvinit rather than systemd: this container's only actual process
# hierarchy is start-sitl-gazebo-qgc.sh and the three things it execs/backgrounds
# -- no services, timers, or unit files of our own to manage, so systemd's
# extra weight (and cgroup/D-Bus expectations that don't always hold up
# cleanly one level inside an already-namespaced container) buys nothing here.
INIT_MANAGER = "sysvinit"

# GL rendering happens against the *container's own* Mesa talking indirect
# GLX to whatever X server the host bind-mounts in (see GAZEBO_ROS2.md
# section 4.1.2's `docker run` flags) -- forcing the llvmpipe software
# rasterizer (LIBGL_ALWAYS_SOFTWARE=1, set by run-container.sh, not baked in
# here) sidesteps needing the container's Mesa/DRI version to exactly match
# whatever GPU driver the host kernel loaded for real hardware-accelerated
# GLX, at the cost of 3D performance -- acceptable for verifying the
# pipeline works, not a claim about being fast.

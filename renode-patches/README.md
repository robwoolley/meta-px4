# Renode patches

Renode itself is a pinned host prerequisite for this layer, not an OE
recipe (`specs/000-architecture.md` §4.6) — see
[SIMULATION.md](../SIMULATION.md) for the normal portable-release
install. The patches here apply on top of a specific Renode **source**
checkout, for cases the plain portable release can't handle correctly.

## `0001-STM32_Timer-fix-capture-compare-arming-for-wrapping-free-running-counters.patch`

**Target:** Renode source at tag `v1.16.1`
(`github.com/renode/renode`, `src/Infrastructure` submodule).

**What it fixes:** `STM32_Timer`'s capture/compare channel arming
didn't handle a free-running counter wrapping around — a channel's
compare target is only "in the future" in raw numeric terms about
half the time; the rest of the time the target is numerically *behind*
the current counter value because the counter itself wrapped, which is
completely normal for any periodic software timer that reschedules by
writing `deadline & 0xffff` (or equivalent) to the compare register —
exactly what NuttX/PX4's STM32 HRT driver does
(`hrt_call_reschedule()` in `platforms/nuttx/src/px4/stm/stm32_common/hrt/hrt.c`).
The old model treated a numerically-behind target as invalid and left
the channel permanently disarmed; a second, related bug treated a
compare value of exactly 0 as "channel disabled" (a real, valid target
on real hardware — it just means "match when the counter wraps to
0").

**Why it matters here:** every PX4 module that runs on a NuttX work
queue (`ScheduleOnInterval`, which is most flight-control code
including SIH) is scheduled via `hrt_call_every()`. Without this fix,
every one of those work queues gets exactly one callback and then
never runs again — confirmed via NuttX's own `top` (0ms CPU / `w:sem`
forever) and by reading the STM32 timer's registers directly while
paused (`CCR3` stuck at `0`). This was the real root cause behind
`specs/004-sih-renode-mavlink.md`'s "SIH publishes once and stops"
finding, not an arming-check bug or a SIH-specific gap. With this
patch, `commander check` reports `Preflight check: OK` and the vehicle
arms.

**Building a patched Renode:**

```sh
git clone --recurse-submodules https://github.com/renode/renode.git renode-src
cd renode-src
git checkout v1.16.1
git submodule update --init --recursive

git -C src/Infrastructure am /path/to/meta-px4/renode-patches/0001-STM32_Timer-fix-capture-compare-arming-for-wrapping-free-running-counters.patch

# Needs the .NET 8 SDK on PATH (a portable dotnet-install.sh setup works
# fine, no root needed) plus the normal native build prerequisites
# (cmake, gcc/g++, make, python3) for tlib.
./build.sh --net -t -p
```

The `-t -p` packaging step needs `fpm` to produce a redistributable
tarball; without it, the build still succeeds and
`output/bin/Release/publish/` contains a working, framework-dependent
build you can run directly with `dotnet output/bin/Release/publish/Renode.dll`
(or `./output/bin/Release/publish/Renode` if a matching `dotnet`
runtime is on `PATH`) from the `renode-src` checkout — Renode resolves
its bundled `platforms/`/`scripts/` directories relative to its
working directory when run this way, so invoke it from `renode-src/`.

Only `src/Infrastructure`'s `STM32_Timer.cs` changes; nothing else
about the normal Renode/SIMULATION.md workflow (the `.repl`/`.resc`
files, the `px4-firmware-renode` recipe, the UART socket-bridging
technique) needs to change to use a patched build — just point at the
patched `Renode` binary instead of the portable release's.

**Not yet done:** this hasn't been proposed upstream
(`github.com/renode/renode`) or submitted as a PR — it's a real,
verified fix, but sending it upstream is a separate decision.

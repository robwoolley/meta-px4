# Spec 004 (M4): SIH-in-Renode simulation + MAVLink bridge

- **Status:** In progress — REQ-1/REQ-2 implemented and largely
  verified (§6): SIH is enabled and genuinely running in Renode with
  correct physics and correctly-flagged simulated sensor data. One
  real, unresolved discrepancy blocks arming: `commander check`
  reports "Preflight check: FAILED" and `health_and_arming_checks`
  reports "No valid data from Accel/Gyro/Baro/Compass" even though a
  direct `listener sensor_accel` query moments earlier showed fresh,
  physically-correct data (`z: -9.81024`, i.e. exactly gravity,
  `device_id` tagged `SIMULATION:1`). REQ-3 through REQ-6 not yet
  started.
- **Created:** 2026-07-26
- **Depends on:** [000-architecture.md](000-architecture.md) (G4, R2),
  [003-renode-boot.md](003-renode-boot.md) (Done: PX4 boots to a live
  NSH prompt in Renode via the `px4-firmware-renode` variant).
- **Delivers:** A SIH-enabled firmware variant running PX4's onboard
  flight-dynamics simulation inside Renode, reachable over MAVLink
  from the host, with a scripted MAVSDK arm→takeoff→land test that
  runs headlessly and reflects pass/fail in its exit code.

## 1. Rationale

Spec 000 G4 ("Automated simulated flight (SIH-in-Renode + MAVSDK) in
CI") and R2 ("timer fidelity under virtual time may affect SIH — M4
carries the research risk, not M3") both point here. M3 already found
that Renode's fidelity gaps are real and take genuine investigation
to resolve (seven of them, including one that looked exactly like a
timer bug and turned out to be a stuck DMA controller instead) — but
M3's bar (reach `nsh>`, run a couple of commands) only needed
*eventually-correct* discrete progress. SIH is different: PX4's
onboard physics integration needs a *continuously* well-paced time
base to produce believable flight dynamics, not just forward
progress. Whether that holds up under Renode's virtual-time model is
a genuinely open question — R2 explicitly flags this as unresolved
and assigns the research risk to this milestone, not M3.

## 2. Real findings (checked against source, not assumed)

- **SIH is a Kconfig option layered onto an existing board, not a
  separate board.** `src/modules/simulation/simulator_sih/Kconfig`:
  `menuconfig MODULES_SIMULATION_SIMULATOR_SIH ... default n`,
  `select`ing `MODULES_SIMULATION_PWM_OUT_SIM`/`SENSOR_BARO_SIM`/
  `SENSOR_GPS_SIM`/`SENSOR_MAG_SIM` (software-simulated sensor
  backends that replace the real SPI/I2C drivers entirely). Not
  enabled in `boards/px4/fmu-v6x/default.px4board`. Confirmed there is
  no `boards/px4/sih/` directory — every PX4 board (`fmu-v2` through
  `fmu-v6xrt`, `sitl`, etc.) is a real hardware target; SIH is meant
  to be compiled into one of them, matching M3's own precedent of a
  distinct `px4-firmware-renode`-style variant rather than a new
  `CONFIG=` board name.
- **Ready-made SIH airframes already exist in the real source tree** —
  `ROMFS/px4fmu_common/init.d/airframes/1100_rc_quad_x_sih.hil`
  through `1104_standard_ackermann_sih.hil` (quad, plane, tailsitter,
  VTOL, rover). `1100` (quadcopter) is the simplest: sets
  `param set SYS_HITL 2` ("start the SIH and avoid sensors startup" —
  the *runtime* activation switch, separate from the Kconfig compile-
  time gate above), disables battery/UAVCAN preflight checks, and
  configures a standard quad-X mixer. Selected via `SYS_AUTOSTART` at
  boot, the normal PX4 airframe-selection mechanism — no new airframe
  file needs authoring for a first attempt.
- **A MAVLink bridge path already exists in stock boot, before any
  M4-specific work.** M3's own real boot log (from the unmodified
  `px4-firmware-renode` build, no SIH involved) already showed:
  `Starting MAVLink on /dev/ttyS6` and `Starting MAVLink on ethernet`.
  Whether either is actually *reachable from the host* over Renode
  has not yet been verified — that's this milestone's first real
  question, not an assumption to build on.
- **Renode's base `stm32h743.repl` already models Ethernet**
  (`ethernet: Network.SynopsysDWCEthernetQualityOfService @ ...`) —
  the likely path for MAVSDK to reach MAVLink over UDP without new
  UART-to-socket plumbing, pending real verification of whether
  Renode's network peripheral model can actually bridge to a host
  socket (Renode supports this via its network "tap"/switch
  mechanism in general; whether it works out of the box for this
  specific Ethernet MAC model is unverified).
- **No `mavlink-router` or MAVSDK recipe/tooling exists anywhere in
  this layer yet** (checked directly, not assumed) — this is real,
  new scope, not something to wire into existing infrastructure.

## 3. Requirements

- **REQ-1** — **Done.** A SIH-enabled firmware variant exists
  (`CONFIG_MODULES_SIMULATION_SIMULATOR_SIH=y` plus its Kconfig
  dependencies), extending `px4-firmware-renode` per §5.1's simpler
  path. Verified genuinely running with correct physics and correctly-
  flagged simulated sensor data (§6), not just "compiles."
- **REQ-2** — **Done.** `SYS_AUTOSTART` selects the existing
  `1100_rc_quad_x_sih` airframe (simplest vehicle type) rather than
  authoring a new one — confirmed via real boot log
  (`Loading airframe: /etc/init.d/airframes/1100_rc_quad_x_sih.hil`).
- **REQ-3** — MAVLink is verified reachable from the *host* (not just
  visible in Renode's own console log) via a real socket-level check
  — actually connect and observe a MAVLink heartbeat, not infer
  reachability from boot-log text alone. Resolve the Ethernet-vs-UART
  question (§2) with evidence: attempt Ethernet first since it avoids
  new UART/socket plumbing, fall back to bridging the UART MAVLink
  instance (`/dev/ttyS6`) via Renode's UART-to-socket mechanism if
  Ethernet proves impractical.
- **REQ-4** — Host-side MAVLink tooling (`mavlink-router` and/or
  MAVSDK) is real, runnable tooling — either an OE recipe (companion-
  side packaging, per spec 000 §4.4's note that posix-side packaging
  lives there) or a documented host prerequisite (matching the Renode
  precedent from M3, spec 000 §4.6). Decide with evidence which is
  actually needed: MAVSDK can often speak MAVLink directly without
  `mavlink-router` as an intermediate hop, so don't add the hop
  unless a real need for it surfaces.
- **REQ-5** — A scripted MAVSDK test (arm → takeoff → land) runs
  against the Renode SIH instance, exit code reflects pass/fail,
  runs headlessly (matching spec 003 REQ-5's `renode-test`-style CI
  precedent, even if the actual CI pipeline itself is M6's job).
- **REQ-6** — Document real timer/virtual-time fidelity findings for
  SIH specifically (R2's flagged risk): does the physics integration
  hold up under Renode's virtual-time model well enough for a
  believable arm→takeoff→land, or does it drift/break down? Fixed
  forward from actually-observed behavior — attempt first, don't
  pre-guess a workaround for a problem that may not occur (mirroring
  how M3's `CONFIG_STM32H7_PWR_IGNORE_ACTVOSRDY` guess turned out not
  to exist/be needed at all).

## 4. Non-goals

- Modeling real sensor hardware — still unnecessary; SIH's Kconfig
  `select` list (§2) replaces every relevant sensor driver with a
  software backend.
- Multi-vehicle or swarm simulation.
- Physical actuator/ESC modeling beyond what SIH's own physics model
  (`aero.hpp`/`sih.cpp`) already provides.
- Airframes other than the basic quadcopter (`1100`) — the other four
  ready-made `_sih.hil` airframes are available for later, not this
  milestone's gate.
- Hardware validation (M5).
- The CI pipeline itself (M6) — this milestone documents a working
  headless invocation, not the infrastructure that runs it on every
  commit.

## 5. Design sketch

### 5.1 Open question: does REQ-1's Kconfig fragment belong on
`px4-firmware-renode` or a new variant?

Not yet decided — resolve with evidence. `px4-firmware-renode`
already exists solely to make Renode testing work (DMA workaround);
adding a second, unrelated purpose (SIH) to the same recipe risks
conflating "boots at all under Renode" with "runs a simulated flight
under Renode." Try extending the existing variant first (simpler,
one fewer recipe to maintain) and only split it out if the two
purposes turn out to interfere with each other in practice.

### 5.2 Open question: Ethernet or UART for the MAVLink bridge?

Not yet decided — resolve with evidence per REQ-3. Ethernet avoids
new UART-to-host-socket plumbing if Renode's `SynopsysDWCEthernetQoS`
model actually bridges cleanly to a host-reachable interface; if it
doesn't (or fmu-v6x's real Ethernet wiring turns out to need board-
specific PHY details not modeled), fall back to exposing the UART
MAVLink instance the way spec 000 §4.6 originally anticipated ("a
MAVLink UART exposed as a socket for host-side tools").

### 5.3 Open question: is a physics-integration timing problem real?

R2's flagged risk. Attempt a real SIH boot + arm sequence first; only
if the vehicle state visibly diverges/misbehaves (e.g. attitude
integration blowing up, altitude never stabilizing) should this be
treated as confirmed rather than speculative — M3's own experience
was that a plausible-sounding timing concern (`CONFIG_STM32H7_
PWR_IGNORE_ACTVOSRDY`) turned out not to exist/apply at all, so this
should be verified, not assumed to be a blocker going in.

## 6. Implementation record

**REQ-1/REQ-2 (SIH enabled, quad-X airframe forced): done.** Extended
`px4-firmware-renode` (§5.1's simpler-first option, confirmed workable)
with a third patch
(`0003-boards-px4-fmu-v6x-enable-SIH-and-force-quad-X-SIH.patch`):
adds `CONFIG_MODULES_SIMULATION_SIMULATOR_SIH=y` to
`boards/px4/fmu-v6x/default.px4board`, and `param set SYS_AUTOSTART
1100` to `boards/px4/fmu-v6x/init/rc.board_defaults` (runs before
rcS's airframe-selection step, so it takes effect every boot despite
no persistent parameter storage).

**Real build failure, fixed with evidence, not guessed:** the first
attempt overflowed fmu-v6x's FLASH region by 47,052 bytes — SIH's
physics/EKF-adjacent code doesn't fit alongside the full real-hardware
driver set. Fixed by removing, in the same patch, real-hardware
drivers/modules made genuinely redundant by SIH's own simulated
backends and by this variant only ever running the quad-X airframe:
all real IMU/barometer/magnetometer chip drivers (9 IMU variants, 3
barometer variants), `UAVCAN`, camera/gimbal/OSD peripherals,
differential pressure (airspeed — irrelevant to a multirotor), and
the fixed-wing/VTOL flight-control modules (`FW_*`,
`VTOL_ATT_CONTROL`, `MODE_NAVIGATOR_VTOL_TAKEOFF`). None of the
removed drivers did anything useful in this environment anyway — M3's
own boot log already showed every one of them reporting "no device on
bus". Rebuild succeeded. One expected, harmless side effect: the
`1100` airframe's own `param set UAVCAN_ENABLE 0` now logs
`ERROR [param] Parameter UAVCAN_ENABLE not found` (the parameter no
longer exists since `UAVCAN` was removed) — cosmetic, not a failure.

**Verified SIH is genuinely running, with real physics and real
simulated sensor data** — via the interactive Renode session
technique proven in spec 003 (raw `usart3 WriteChar` monitor commands
turned out not to inject RX data the way assumed; switched to
reusing the already-proven `renode-test`/`Write Line To Uart`
mechanism instead, forcing a deliberate assertion failure to capture
the full console dump, the same technique that recovered the real
`uorb status` output in spec 003):
- `simulator_sih status` printed live physics state: vehicle type
  "Quadcopter", landed state, local position/velocity (NED),
  attitude (roll/pitch/yaw), angular acceleration, actuator signals,
  aerodynamic forces/moments — all populated with physically sensible
  resting-on-ground values, not zeros-because-uninitialized or stale
  data.
- `listener sensor_accel -n 1` showed a fresh sample (`timestamp: ...
  1.334135 seconds ago`) with `device_id` explicitly flagged
  `SIMULATION:1`, and `z: -9.81024` — exactly Earth gravity for a
  stationary vehicle. This is real, correct, actively-updating
  simulated sensor data, not a stub.

**Open discrepancy, not yet resolved:** despite the above, `commander
check` reports `Preflight check: FAILED`, and
`health_and_arming_checks` repeatedly logs `Preflight Fail: No valid
data from Accel/Baro/Gyro/Compass 0` — both immediately after the
`listener` query above and several seconds later, so this isn't
simply "hadn't started yet." Traced the exact check condition in
`src/modules/commander/HealthAndArmingChecks/checks/
accelerometerCheck.cpp`: `is_valid = _sensor_accel_sub[instance]
.copy(&accel_data) && (accel_data.device_id != 0) &&
(accel_data.timestamp != 0)`, a `SubscriptionMultiArray` on the same
raw `ORB_ID::sensor_accel` topic the `listener` command reads — not a
calibration issue (that's a separate `is_calibration_valid` check,
which would produce a different log message). Root cause not yet
found: possibly the check's own subscription only samples
periodically and happens to catch a gap between SIH publish cycles,
or something more specific to `advertised()` state — needs further,
more targeted tracing (e.g. watching the check's own periodic
evaluation across several cycles) rather than a single point-in-time
`listener` snapshot.

| Item | Decision / evidence |
|---|---|
| SIH variant: extends `px4-firmware-renode` or new recipe? | **Extends it** — confirmed workable, one additional patch, no need for a separate recipe. |
| MAVLink bridge transport: Ethernet or UART? | _tbd — not yet attempted_ |
| Host MAVLink tooling: OE recipe or host prerequisite? | _tbd — not yet attempted_ |
| Timer/virtual-time fidelity under SIH | _tbd — SIH itself runs and produces correct physics; whether it holds up through a full arm→takeoff→land sequence is not yet tested_ |
| FLASH budget for the SIH variant | **Real constraint hit and fixed** — see above. Removing real-hardware drivers redundant with SIH's simulated backends was necessary, not optional. |
| Arming blocked by a real, unresolved sensor-validity discrepancy | **Yes** — see "Open discrepancy" above. This is the current blocker for REQ-5 (scripted arm→takeoff→land). |

## 7. Acceptance criteria

- **AC-1** — SIH-enabled firmware builds and boots in Renode to the
  point of accepting `commander` mode-switch/arm commands (REQ-1,
  REQ-2).
- **AC-2** — A real, host-side socket connection observes a MAVLink
  heartbeat from the Renode instance (REQ-3, REQ-4).
- **AC-3** — Scripted MAVSDK arm→takeoff→land completes successfully
  against the Renode SIH instance, runnable headlessly with a
  pass/fail exit code (REQ-5).
- **AC-4** — Real timer/virtual-time fidelity findings for SIH are
  documented in §6, whether or not a problem was actually found
  (REQ-6).

# Spec 004 (M4): SIH-in-Renode simulation + MAVLink bridge

- **Status: Done.** All six requirements complete. The work-queue/HRT
  timer gap flagged as a severe, unresolved blocker earlier is
  **fixed** — not in PX4/NuttX, but in Renode itself (§6,
  [renode-patches/](../renode-patches/)): `Timers.STM32_Timer`'s
  capture/compare arming didn't handle a free-running counter wrapping
  around, and separately treated a compare value of exactly 0 as
  "channel disabled" (a valid target on real hardware). Since PX4's
  work-queue scheduling depends on this timer (fmu-v6x's `HRT_TIMER`
  is TIM8) to reschedule itself, every work queue got exactly one
  callback and then silently stopped forever — confirmed via NuttX's
  `top` (0ms CPU / `w:sem` for the entire uptime) and by reading TIM8's
  registers directly while paused (`CCR3` stuck at exactly `0`). With
  both bugs fixed and a Renode rebuilt from source, all previously-
  starved work queues (`wq:INS0`/ekf2, `wq:rate_ctrl`,
  `wq:nav_and_controllers`, `sih`, `wq:hp_default`, `wq:lp_default`)
  run continuously, `sensor_accel` keeps updating indefinitely,
  `commander check` reports `Preflight check: OK`, and `commander arm`
  genuinely arms the vehicle. REQ-3/REQ-4: a real `pymavlink` client
  observed a genuine heartbeat over a UART-bridged socket (§6), no
  `mavlink-router` needed. **REQ-5 done**: a scripted MAVSDK
  arm→takeoff→land test
  (`recipes-renode/pixhawk6x/sih_flight_test.py`) passes reliably
  (verified twice) against the patched Renode — see §6 for the two
  more real bugs (both in the test harness, not PX4/Renode) found and
  fixed getting there. REQ-6's original answer (§6) predates the timer
  fix and should be read alongside it: the "severe" framing was
  accurate for the unpatched Renode, resolved by patching Renode
  rather than by finding a PX4/NuttX-side workaround.
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

- **REQ-1** — **Done, for real, as of the Renode timer fix (§6).** A
  SIH-enabled firmware variant exists
  (`CONFIG_MODULES_SIMULATION_SIMULATOR_SIH=y` plus its Kconfig
  dependencies), extending `px4-firmware-renode` per §5.1's simpler
  path. An earlier "genuinely running" claim based on a single
  point-in-time snapshot was wrong — re-querying the same topic later
  proved PX4's work-queue scheduling (which SIH runs on) never
  advanced past its first tick under the plain portable Renode
  release, a real Renode timer-model bug (§6), not a PX4/NuttX issue.
  Fixed by patching Renode itself
  ([renode-patches/](../renode-patches/)) and rebuilding it from
  source; with that build, SIH runs continuously, `sensor_accel`
  updates indefinitely, and the vehicle arms.
- **REQ-2** — **Done.** `SYS_AUTOSTART` selects the existing
  `1100_rc_quad_x_sih` airframe (simplest vehicle type) rather than
  authoring a new one — confirmed via real boot log
  (`Loading airframe: /etc/init.d/airframes/1100_rc_quad_x_sih.hil`).
- **REQ-3** — **Done.** MAVLink is verified reachable from the *host*
  via a real socket-level check, not inferred from boot-log text.
  Resolved Ethernet-vs-UART (§2) with evidence: Ethernet was assessed
  impractical (§6 — Renode's `emulation CreateTap` needs a host TAP
  interface, which needs `CAP_NET_ADMIN`/root to bring up, a
  system-networking change out of scope to make without explicit
  sign-off), so bridged the UART MAVLink instance (`/dev/ttyS6`,
  UART7/TELEM1) via the same `CreateServerSocketTerminal` mechanism
  already proven for the console (`SIMULATION.md`). A real `pymavlink`
  client connected over TCP and received a correctly-parsed heartbeat
  (§6) — genuine socket-level reachability, not text-log inference.
- **REQ-4** — Host-side MAVLink tooling (`mavlink-router` and/or
  MAVSDK) is real, runnable tooling — either an OE recipe (companion-
  side packaging, per spec 000 §4.4's note that posix-side packaging
  lives there) or a documented host prerequisite (matching the Renode
  precedent from M3, spec 000 §4.6). Decide with evidence which is
  actually needed: MAVSDK can often speak MAVLink directly without
  `mavlink-router` as an intermediate hop, so don't add the hop
  unless a real need for it surfaces.
- **REQ-5** — **Done.** A scripted MAVSDK test
  (`recipes-renode/pixhawk6x/sih_flight_test.py`) runs arm → takeoff →
  land against the Renode SIH instance, exit code reflects pass/fail
  (0/1), runs headlessly (matching spec 003 REQ-5's `renode-test`-style
  CI precedent, even if the actual CI pipeline itself is M6's job).
  Verified passing twice in a row against the timer-patched Renode.
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

**Initial (incomplete) verification — superseded below.** A first pass
via the interactive Renode session technique proven in spec 003 (raw
`usart3 WriteChar` monitor commands turned out not to inject RX data
the way assumed; switched to reusing the already-proven
`renode-test`/`Write Line To Uart` mechanism instead) showed
`simulator_sih status` printing plausible physics state and `listener
sensor_accel -n 1` showing a sample tagged `SIMULATION:1` with `z:
-9.81024` (exactly Earth gravity). This was read as "genuinely
running, real, actively-updating simulated sensor data" — but that
conclusion was wrong: it was a single point-in-time snapshot, and
nothing at the time re-queried the same topic later to check whether
the value was actually still updating.

**Corrected finding, root-caused with hard evidence:** SIH's sensor
data is **not** actively updating. Querying `listener sensor_accel -n
1` four times over ~8 real seconds (via the `SIMULATION.md`
`CreateServerSocketTerminal`/`nc` workflow) returned the exact same
`timestamp: 7077681` every time, while the reported age climbed in
lockstep with real time (9.415s → 10.307s → 13.824s → 17.519s ago).
`sensor_accel` was published **exactly once**, near boot, and never
again — not a subscription/instance mismatch and not a calibration
issue. (This also means the original `accelerometerCheck.cpp`
condition quoted above was incomplete: the real check is `is_valid =
... .copy(&accel_data) && (accel_data.device_id != 0) &&
(accel_data.timestamp != 0) && (hrt_elapsed_time(&accel_data.timestamp)
< 1_s)` — a genuinely stale, once-published sample fails this
honestly, exactly as designed.)

Since the growing "seconds ago" figure is itself computed from
`hrt_absolute_time()`, that free-running clock is clearly still
advancing correctly — ruling out a fully frozen HRT peripheral. The
break is specifically in **periodic callback delivery**. Confirmed via
NuttX's `top once`: every PX4 work-queue thread —
`wq:manager`/`wq:lp_default`/`wq:hp_default`/`wq:nav_and_controllers`/
`wq:rate_ctrl`/`wq:INS0` — plus the `sih` task itself, showed **`w:sem`
state and exactly 0ms of accumulated CPU time across the full 16.8s
of uptime measured**. None of them has run even once since initial
registration. This is not SIH-specific: it is PX4's entire work-queue
scheduling mechanism (`ScheduleOnInterval`/`hrt_call_every`, backed by
fmu-v6x's `HRT_TIMER` = **TIM8**, per `board_config.h`) failing to
redeliver its periodic capture-compare interrupt under Renode's
`Timers.STM32_Timer` model, after presumably firing once during each
module's initial setup. (A handful of standalone tasks not built on
the work-queue mechanism — `ekf2`, `commander`, the `mavlink_*` tasks —
showed nonzero but still very small CPU time, consistent with
one-time startup work rather than genuine ongoing cycles.)

This reframes the M4 blocker entirely: it isn't an arming-check quirk
or a SIH-specific gap, it's a Renode hardware-timer fidelity gap in
the peripheral class used for *all* PX4 periodic module scheduling —
precisely the risk spec 000 R2 flagged for M4, just more severe than
"SIH's physics integration might drift": nothing scheduled through a
work queue advances past its first tick at all. Fixing this for real
would mean either patching Renode's own `Timers.STM32_Timer` C# model
(a change to Renode itself, well outside this OE layer's scope as
built so far) or empirically finding a different timer peripheral
Renode models more completely and repointing fmu-v6x's `HRT_TIMER` at
it for the Renode-only variant (unverified whether any other modeled
STM32H7 timer instance behaves differently, since `Timers.STM32_Timer`
is a single shared model class). Not yet attempted either way — this
is being surfaced as a real, evidenced blocker rather than a guessed
fix, matching this project's own established practice of not
patching around a problem before its root cause is understood.

**Follow-up: patched Renode's own timer model, root-caused down to
two exact bugs, and verified the fix directly.** Read
`Timers.STM32_Timer`'s actual C# source (cloned
`github.com/renode/renode` at tag `v1.16.1`, matching the installed
portable release exactly) rather than guessing further. The channel-
arming logic (`UpdateCaptureCompareTimer`) only armed a compare-match
sub-timer when the *current* counter value was numerically less than
the newly-written compare target:
```
ccTimers[i].Enabled = Enabled && IsInterruptOrOutputEnabled(i) && Value < ccTimers[i].Limit;
```
Cross-checked against the actual PX4/NuttX HRT driver source
(`platforms/nuttx/src/px4/stm/stm32_common/hrt/hrt.c`,
`hrt_call_reschedule()`): it deliberately reschedules by writing
`deadline & 0xffff` to the compare register — i.e. the low 16 bits of
a free-running, wrapping counter. This means the new compare target is
very often numerically *behind* the current counter value, which is
completely normal (the match just occurs on the next lap) — but the
model's `Value < Limit` check treats that as invalid and leaves the
channel disarmed. Confirmed this really was happening rather than
merely theorized: after fixing it (below) and re-testing, `sih`'s
`sensor_accel` published continuously for several real seconds before
freezing *again* at the exact same virtual timestamp across two
independent runs — too precise to be a random race, and definitively
not fully fixed by the wraparound correction alone.

Paused the second, still-reproducible freeze and read TIM8's actual
registers directly via the monitor (`sysbus ReadDoubleWord`, the same
peripheral-inspection technique already proven in spec 003): `CR1`
showed the timer still enabled (`CEN=1`), `DIER` showed both PPM and
HRT interrupts still enabled, `SR` showed no stuck pending flags — but
`CCR3` (channel 3, fmu-v6x's `HRT_TIMER_CHANNEL`) read back as exactly
`0`. The model has a second bug: a compare write of `0` is treated as
"channel disabled" — a leftover special case from the old, broken
comparison above. On real hardware `0` is simply another valid compare
target (it matches whenever the counter wraps around to `0`, which is
a normal, fairly common value for a computed deadline to land on
exactly) — the "disabled" special case doesn't correspond to anything
real, and is exactly what silently and permanently stopped HRT's
channel the first time a reschedule happened to compute a target of 0.

Fixed both together, computing the actual number of ticks until the
next match directly (with wraparound) instead of a raw numeric
comparison, and removing the "compare value 0 means disabled" special
case entirely — see
[`renode-patches/0001-STM32_Timer-fix-capture-compare-arming-for-wrapping-free-running-counters.patch`](../renode-patches/0001-STM32_Timer-fix-capture-compare-arming-for-wrapping-free-running-counters.patch)
for the full patch and rationale. Built a patched Renode from source
(.NET 8 SDK, portable-installed with no root needed) and verified
directly against the same real firmware, no `.repl`/recipe changes
needed:
- `listener sensor_accel -n 1`, queried 8 times over 30+ seconds
  (crossing well past both previous freeze points), returned a
  genuinely fresh sample (sub-millisecond age) every single time.
- `top once`: every previously-starved work queue now shows real,
  accumulating CPU time —
  `wq:INS0` (ekf2's estimation cycle) 0ms→135ms, `wq:rate_ctrl`
  0ms→123ms, `sih` 0ms→79ms, `wq:nav_and_controllers` 0ms→55ms,
  `wq:hp_default`/`wq:lp_default` 0ms→3-4ms; total task CPU usage
  8.47%→41.76%.
- `commander check` reports `INFO [commander] Preflight check: OK`
  (the only remaining warning is the already-documented, unrelated
  "Missing FMU SD Card", since Renode doesn't model fmu-v6x's SD
  card).
- `commander arm` genuinely arms: `INFO [commander] Armed by internal
  command`.

This resolves REQ-1 for real (§3) and directly unblocks REQ-5
(scripted arm→takeoff→land is no longer blocked on a Renode-side
limitation, only on writing the actual test). Not yet done: proposing
this upstream to `github.com/renode/renode` — a real, verified fix,
but sending it upstream is a separate decision from fixing it locally.

**Second real Renode DMA-model gap found and fixed: UART7 (TELEM1),
the same class of bug as spec 003's console DMA hang.** Discovered
interactively: connecting to the console over a Renode
`CreateServerSocketTerminal` socket (the documented workaround in
`SIMULATION.md` for `showAnalyzer`'s GUI window not accepting keyboard
input in headless/SSH setups) worked for typing commands right up
until the console printed `Starting MAVLink on /dev/ttyS6` followed by
`INFO [mavlink] mode: Normal, data rate: 1200 B/s on /dev/ttyS6 @
57600B` — at which point the entire system stopped responding: no
further `nsh>` prompts, no response to typed input, nothing.

Root-caused from source, not by further live probing:
- `/dev/ttyS6` is **UART7**, not the console — confirmed two
  independent ways: (a) NuttX's `stm32_serial.c` `g_uart_devs[]` table
  registers `/dev/ttySN` in fixed peripheral-index order (`[0]=USART1
  ... [2]=USART3(console) ... [6]=UART7`, with
  `CONFIG_STM32H7_SERIAL_DISABLE_REORDERING=y` set and all 8 ports
  populated with no gaps, so index order is the actual order); (b)
  independently corroborated via `boards/px4/fmu-v6x/src/board_config.h`
  (`PX4IO_SERIAL_DEVICE "/dev/ttyS5"` tied explicitly to `USART6`,
  validating the same index arithmetic) and
  `boards/px4/fmu-v6x/default.px4board`
  (`CONFIG_BOARD_SERIAL_TEL1="/dev/ttyS6"`, confirming ttyS6 is
  labeled TELEM1).
- fmu-v6x's `nuttx-config/nsh/defconfig` sets `CONFIG_UART7_RXDMA=y`/
  `CONFIG_UART7_TXDMA=y`.
- fmu-v6x's own `nuttx-config/include/board_dma_map.h` routes UART7's
  RX/TX DMA onto **DMA2** (`DMAMAP_UART7_RX/TX = DMAMAP_DMA12_UART7RX/
  TX_1`, in the file's own "DMAMUX2 Using ... DMA2" section, the same
  group as `DMAMAP_USART3_RX/TX`) — the exact same DMA2 controller
  instance spec 003 already proved Renode's `DMA.STM32DMA` model never
  signals transfer-complete on.

So once MAVLink actually transmits on TELEM1 (which happens slightly
later in boot than the UDP/ethernet MAVLink instance, hence the delay
before the freeze), it hits the identical infinite-retransmit hang
already root-caused for the console in spec 003 — and because the
hang is a tight, never-yielding busy-wait, it starves task scheduling
for the whole system, not just that one UART, which is why NSH itself
appeared frozen too.

**Fixed** the same way as the console: a fourth patch
(`0004-boards-px4-fmu-v6x-disable-UART7-TELEM1-DMA-for-Renode.patch`)
disables `CONFIG_UART7_RXDMA`/`TXDMA`, forcing interrupt-driven I/O
for TELEM1. Carried only by `px4-firmware-renode`; the real hardware
`px4-firmware` recipe is unaffected. **Verified directly**: rebuilt,
booted the same way as before, and confirmed the console now keeps
producing output and responding to typed commands well past the
`mode: Normal, data rate: 1200 B/s on /dev/ttyS6 @ 57600B` line — sent
`ver all` interactively after that point and got the full expected
response followed by a fresh `nsh>` prompt, where the unpatched build
would have hung forever.

**REQ-3 done: MAVLink verified reachable from the host over a real
socket, resolving Ethernet vs. UART with evidence.** Checked whether
Renode's Ethernet path (`emulation CreateTap`/`CreateSwitch`, bridging
the base repl's modeled `SynopsysDWCEthernetQualityOfService` MAC to a
host-reachable interface) was practical: `CreateTap` requires a host
TAP network interface, and while `/dev/net/tun` itself is
world-writable on this host (so the raw fd can be opened
unprivileged), actually bringing the resulting interface up and
assigning it an address still needs `CAP_NET_ADMIN` — a host-level
networking change out of scope to make without explicit sign-off, so
Ethernet was assessed impractical rather than attempted. Fell back to
the documented alternative (spec 000 §4.6): bridged the UART MAVLink
instance instead, using the exact same `emulation
CreateServerSocketTerminal`/`connector Connect` mechanism already
proven for the console in `SIMULATION.md` — `connector Connect
sysbus.uart7 telem1` — with zero new host privileges needed, since
it's the same mechanism already working.

Verified with a real MAVLink client, not a raw byte/text check: installed
`pymavlink` into the existing `renode-test-venv` and connected via
`mavutil.mavlink_connection('tcp:127.0.0.1:<port>')`. Received a
correctly-parsed heartbeat within 0.1 real seconds of connecting:
`HEARTBEAT {type: 2 (MAV_TYPE_QUADROTOR), autopilot: 12
(MAV_AUTOPILOT_PX4), base_mode: 61, system_status: 0, mavlink_version:
3}`, system ID 1 — genuine protocol-level reachability, not inferred
from the boot log's `Starting MAVLink on /dev/ttyS6` text.

This also answers REQ-4's open question: a plain `pymavlink` client
was sufficient with no `mavlink-router` intermediate hop needed,
confirming the REQ-4 hypothesis that MAVSDK-family tooling can speak
MAVLink directly.

**REQ-5 done: scripted MAVSDK arm→takeoff→land, passing reliably.**
With the timer fix in place, wrote
`recipes-renode/pixhawk6x/sih_flight_test.py`: boots
`px4-firmware-renode` under the patched Renode
(`pixhawk6x-sih-flight.resc`, bridging both TELEM1 and the console to
TCP sockets), drives a real `mavsdk`-python client through arm →
set-takeoff-altitude → takeoff → wait-for-altitude → hover → land →
wait-for-landed, and exits 0/1 for pass/fail. Getting a genuinely
reliable pass required finding and fixing three more real bugs — two
in the test harness, one a real MAVLink-link configuration gap, none
of them Renode/PX4 bugs:

1. **MAVSDK's client-side health flags never agree with PX4's own
   state on this link.** The first attempt gated on
   `telemetry.health()`'s `is_global_position_ok`/`is_home_position_ok`
   before arming, matching normal MAVSDK usage — and it timed out
   every time. Checked PX4's own internal state directly over the
   console (`listener vehicle_global_position`, `listener
   home_position`): both were genuinely valid
   (`lat_lon_valid`/`valid_hpos`/`valid_lpos` all `True`) the whole
   time. The gap is specifically MAVSDK's own derived flags lagging on
   TELEM1's low-bandwidth link (below), not a real readiness problem.
   Fixed by not gating on them at all — attempt `arm()` directly (with
   retries) and let PX4's own `COMMAND_ACK` be the authority, matching
   how a plain NSH `commander arm` already works against this same
   firmware.
2. **TELEM1 defaults to `MAV_0_RATE` 1200 B/s** (a real-radio-
   appropriate default baked into the airframe/board config) — fine
   for a bare heartbeat (REQ-3/REQ-4's own check), but far too slow for
   MAVSDK's own normal client machinery (parameter sync, telemetry
   streams): the console log showed `ERROR [parameters] get: param
   65535 invalid` repeating continuously, consistent with a full
   parameter sync that never completes. Fixed by raising `MAV_0_RATE`
   (to 50000 in the test) over the console before connecting MAVSDK —
   a plain runtime `param set`, no firmware rebuild needed.
3. **`mavsdk`-python's `System` leaks its spawned `mavsdk_server`
   subprocess.** `System.__del__` is supposed to stop it, but `__del__`
   is not reliably called before interpreter exit — confirmed directly
   by finding three stray `mavsdk_server` processes left behind after
   three earlier test runs, all competing for the same default gRPC
   port 50051, breaking subsequent runs in confusing ways (a `tcp://`
   connection to Renode's socket appearing to reset immediately, an
   empty exception message) that had nothing to do with Renode or PX4
   at all. Fixed by explicitly calling the private
   `drone._stop_mavsdk_server()` in a `finally` block.

Also added a hard overall `asyncio.wait_for` watchdog around the whole
flight sequence: a single hung MAVSDK call (observed once, on `arm()`)
can block forever waiting for an ACK that never arrives rather than
raising, which would make a per-stage timeout check placed *after* an
`await` never actually run.

**Verified passing twice in a row**, including a first-attempt `arm()`
timeout that the retry loop recovered from (`arm attempt failed
(TIMEOUT...), retrying...` → succeeds), takeoff reaching ~4.7-5.4 m
against a 5 m target, and landing confirmed via `in_air` going `False`.

| Item | Decision / evidence |
|---|---|
| SIH variant: extends `px4-firmware-renode` or new recipe? | **Extends it** — confirmed workable, one additional patch, no need for a separate recipe. |
| UART7 (TELEM1) DMA hang | **Real gap found and fixed** — see above. Same Renode DMA2 model bug as spec 003's console hang, recurring on a second DMA2-routed UART; fixed the same way (patch 0004 disables `CONFIG_UART7_RXDMA`/`TXDMA`). |
| MAVLink bridge transport: Ethernet or UART? | **UART** — Ethernet needs host `CAP_NET_ADMIN` for `CreateTap` (assessed impractical, not attempted); the UART bridge needed zero new host privileges since it reuses the console's already-proven socket mechanism. Verified with a real `pymavlink` heartbeat. |
| Host MAVLink tooling: OE recipe or host prerequisite? | **Host prerequisite, not an OE recipe** — plain `pip install pymavlink` into a venv was sufficient; no `mavlink-router` intermediate needed. |
| Timer/virtual-time fidelity under SIH | **Real, severe gap found *and fixed*.** Not "drift" — PX4's work-queue scheduling (SIH included) never advanced past its first tick under the plain portable Renode release. Two bugs in `Timers.STM32_Timer`'s capture/compare arming (wraparound-unaware comparison; compare-value-0 wrongly meaning "disabled"); fixed with a Renode source patch ([renode-patches/](../renode-patches/)), verified directly. |
| FLASH budget for the SIH variant | **Real constraint hit and fixed** — see above. Removing real-hardware drivers redundant with SIH's simulated backends was necessary, not optional. |
| Arming blocked by a real sensor-validity discrepancy | **Root-caused and fixed** — not an arming-check bug: `sensor_accel` genuinely stopped updating after one publish because the work-queue thread that would republish it never ran again, due to the Renode timer bugs above. With a patched Renode, `commander check` reports `OK` and `commander arm` genuinely arms. No longer a blocker for REQ-5. |
| Scripted MAVSDK arm→takeoff→land | **Done, verified twice.** `recipes-renode/pixhawk6x/sih_flight_test.py`, exit 0/1. Needed three more fixes to be reliable, none of them Renode/PX4 bugs: don't gate arming on MAVSDK's own health flags (they lag behind PX4's genuinely-valid internal state on this link); raise `MAV_0_RATE` from its 1200 B/s default before connecting MAVSDK; explicitly stop the `mavsdk_server` subprocess mavsdk-python leaks (`System.__del__` isn't reliably called). |

## 7. Acceptance criteria

- **AC-1** — **Done, using a Renode built from
  [renode-patches/](../renode-patches/).** SIH-enabled firmware builds
  and boots in Renode (REQ-1, REQ-2) and stays in a state that accepts
  `commander` mode-switch/arm commands: `commander check` reports
  `Preflight check: OK` and `commander arm` genuinely arms (§6). Not
  met with the plain portable Renode release from §2.2 — needs the
  patched build.
- **AC-2** — **Done.** A real, host-side socket connection observes a
  MAVLink heartbeat from the Renode instance (REQ-3, REQ-4) — see §6.
- **AC-3** — **Done.** Scripted MAVSDK arm→takeoff→land
  (`recipes-renode/pixhawk6x/sih_flight_test.py`) completes
  successfully against the Renode SIH instance, runnable headlessly
  with a pass/fail exit code (REQ-5). Verified passing twice in a row.
- **AC-4** — **Done.** Real timer/virtual-time fidelity findings for
  SIH are documented in §6: a severe Renode work-queue/HRT timer gap
  (not the milder "drift" originally anticipated), root-caused to two
  specific bugs in `Timers.STM32_Timer` and fixed with a Renode source
  patch, verified directly (REQ-6).

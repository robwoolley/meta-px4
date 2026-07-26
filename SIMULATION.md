# Building and running the Pixhawk 6X simulation (Renode)

This is the practical how-to for building the Renode-only PX4 firmware
variant and running it — either as a plain boot-to-NSH check or as a
SIH (Software-In-the-Hardware) simulated flight — against Renode's
STM32H743 CPU model. For the full technical narrative (fidelity gaps
found and fixed, evidence, open issues) see
[specs/003-renode-boot.md](specs/003-renode-boot.md) and
[specs/004-sih-renode-mavlink.md](specs/004-sih-renode-mavlink.md).

This does **not** cover the real-hardware `px4-firmware` recipe
(flashed to an actual Pixhawk 6X) — that's a separate, unmodified
build; see the main [README.md](README.md).

## 1. Overview

Two related things live under `recipes-px4/px4-firmware-renode/` and
`recipes-renode/pixhawk6x/`:

- **`px4-firmware-renode`** — an OE recipe that builds the same PX4
  `px4_fmu-v6x_default` NuttX firmware as the real hardware
  `px4-firmware` recipe, but with console I/O forced to
  interrupt-driven instead of DMA-based (Renode's DMA2 model can't
  complete DMA-based UART transfers) and, on top of that,
  [PX4's SIH simulator](https://docs.px4.io/main/en/simulation/sih.html)
  compiled in and forced to boot into the quad-X airframe. Neither
  change touches the real-hardware `px4-firmware` recipe.
- **`recipes-renode/pixhawk6x/`** — a Renode board overlay
  (`pixhawk6x.repl`), boot script (`pixhawk6x-boot.resc`), and Robot
  Framework test (`pixhawk6x-boot.robot`) that boot that firmware
  against Renode's `stm32h743.repl` CPU model plus the peripheral
  fixes fmu-v6x actually needs (extra SPI buses, SDMMC2, some PWR
  register busy-waits).

## 2. Prerequisites

### 2.1 An OE build with this layer

You need a working OpenEmbedded/Yocto build (`bitbake`,
`openembedded-core`) with this layer (`meta-px4`) added to
`bblayers.conf`. Any scarthgap or wrynose based setup works; see the
main [README.md](README.md) for layer dependencies. This layer does
not provide its own `bitbake`/`openembedded-core` checkout.

### 2.2 Renode (host prerequisite, not an OE recipe)

Renode is a host tool, pinned and installed manually — it is
deliberately **not** built or packaged by this layer (see
`specs/000-architecture.md` §4.6). Install the portable release used
during development:

```sh
curl -LO https://github.com/renode/renode/releases/download/v1.16.1/renode-1.16.1.linux-portable-dotnet.tar.gz
# verify the SHA-256 against the digest published on the GitHub release page
tar xf renode-1.16.1.linux-portable-dotnet.tar.gz -C /path/to/tools/
```

This gives you `/path/to/tools/renode-1.16.1.../renode` (interactive/
GUI launcher) and `.../renode-test` (headless Robot Framework runner),
self-contained with a bundled .NET runtime — no root or system
package required.

`renode-test` needs a few Python packages that aren't part of the
Renode tarball. Create a small venv for them:

```sh
python3 -m venv tools/renode-test-venv
tools/renode-test-venv/bin/pip install -r /path/to/tools/renode-1.16.1.../tests/requirements.txt
```

(`robotframework`, `psutil`, `pyyaml`, `telnetlib3` as of this
writing.) Activate it before running `renode-test`, since `renode-test`
just invokes whatever `python3` is first on `PATH`:

```sh
source tools/renode-test-venv/bin/activate
```

## 3. Building the firmware

The Renode variant lives in its own multiconfig (`pixhawk6x`, defined
in `conf/multiconfig/pixhawk6x.conf` — sets `MACHINE = "pixhawk-6x"`
and `TCLIBC = "baremetal"`) so it never touches your primary build's
`MACHINE`/`DISTRO`. Enable it in your build's `conf/local.conf`:

```
BBMULTICONFIG = "pixhawk6x"
```

Then build:

```sh
bitbake mc:pixhawk6x:px4-firmware-renode
```

The deployed artifacts land at:

```
tmp/deploy/images/pixhawk-6x/px4-firmware-renode-1.17.0-pixhawk-6x.elf
tmp/deploy/images/pixhawk-6x/px4-firmware-renode-1.17.0-pixhawk-6x.px4
```

(under whichever build directory's `TMPDIR` your multiconfig build
uses). The `.elf` is what Renode loads; the `.px4` is the same
firmware in PX4's flashable container format (not useful for Renode,
only kept for parity with the real hardware recipe's outputs).

A first build takes a while (PX4's full NuttX + flight-stack compile).
Subsequent builds after a source-only patch change are much faster.

## 4. Running it in Renode

All commands below assume your working directory is this layer's root
(`meta-px4/`), since the `.robot`/`.resc` files use paths relative to
`recipes-renode/pixhawk6x/`.

### 4.1 Headless (recommended first check)

```sh
source /path/to/tools/renode-test-venv/bin/activate
/path/to/tools/renode-1.16.1.../renode-test \
    recipes-renode/pixhawk6x/pixhawk6x-boot.robot \
    --variable ELF:@/absolute/path/to/px4-firmware-renode-1.17.0-pixhawk-6x.elf
```

This boots the firmware, waits for the `nsh>` prompt, and runs `ver
all` and `uorb status`, asserting on real console output. A clean run
takes roughly 20 seconds and ends with `status OK`. On failure, Renode
writes a full console log under `logs/` and a snapshot under
`snapshots/` (both gitignored) — read the log to see exactly what the
firmware printed.

### 4.2 Interactive (for exploring / typing your own NSH commands)

```sh
/path/to/tools/renode-1.16.1.../renode
```

At the `(monitor)` prompt:

```
set bin @/absolute/path/to/px4-firmware-renode-1.17.0-pixhawk-6x.elf
set repl @recipes-renode/pixhawk6x/pixhawk6x.repl
include @recipes-renode/pixhawk6x/pixhawk6x-boot.resc
emulation CreateServerSocketTerminal 3456 "term"
connector Connect sysbus.usart3 term
start
```

Then, in a **separate** terminal:

```sh
nc localhost 3456
```

That `nc` session is the actual NSH console — type commands into it
once you see `nsh>`. Use `nc`, not `telnet`: `CreateServerSocketTerminal`
is a plain raw TCP pass-through, not a real telnet server, but a
`telnet` client still sends its own protocol negotiation bytes and
translates Enter into `CR LF`/`CR NUL` on connect — Renode forwards all
of that straight to the UART as literal bytes, which NSH's input
parser doesn't understand, so keystrokes appear to do nothing even
though the connection and output are fine. `nc` does no negotiation or
translation at all, so it just works.

`pixhawk6x-boot.resc` also runs `showAnalyzer usart3`, which pops up a
GUI window mirroring the same UART output. That window is only useful
for watching the log: it's a separate window from the one running the
`(monitor)` prompt, and typing does nothing unless it both has a real
display to render into *and* has actual keyboard focus (click into it
first) — in practice, over SSH, in a tiling window manager, or in any
setup without a full desktop session, it either doesn't grab focus or
doesn't appear at all, even though the text keeps rendering. The
`CreateServerSocketTerminal`/`nc` combination above works regardless
of GUI/focus state and is the reliable way to actually type into the
shell.

Useful things to try once you have a shell:

```
nsh> ver all
nsh> uorb status
```

## 5. Running the SIH simulated flight

The same `px4-firmware-renode` artifact also has PX4's SIH simulator
compiled in and forced to boot the `1100_rc_quad_x_sih` quadcopter
airframe (`SYS_AUTOSTART=1100`, set every boot via
`rc.board_defaults` since this environment has no persistent parameter
storage). No separate build or recipe is needed — boot it exactly as
in §4 and use these NSH commands once at `nsh>`:

```
nsh> simulator_sih status      # live physics: position, velocity, attitude, actuators
nsh> listener sensor_accel -n 1  # one simulated accel sample (should read ~9.81 m/s^2 on Z at rest)
nsh> commander check           # preflight/arming check summary
```

**Known open issue** (see `specs/004-sih-renode-mavlink.md` §6): SIH
publishes one valid, correctly-flagged simulated sample at startup,
but does **not** keep running — re-querying `listener sensor_accel -n
1` later returns the exact same timestamp every time. This traces to a
real, severe Renode gap: PX4's work-queue scheduling (which SIH and
most flight-control modules run on) never advances past its first
tick under Renode, a hardware-timer fidelity gap in Renode's
`Timers.STM32_Timer` model (fmu-v6x's `HRT_TIMER` is TIM8), not
something fixable via PX4/NuttX config. As a result `commander check`
always reports `Preflight check: FAILED` and arming does not work —
this is currently unresolved (fixing it for real means either patching
Renode's own timer model or finding a differently-modeled timer
peripheral to repoint HRT at; neither has been attempted).

## 6. Connecting MAVLink from the host

MAVLink starts automatically at boot on two transports — UDP (visible
in the console log as `Starting MAVLink on ethernet`, port 14550) and
the TELEM1 UART (`Starting MAVLink on /dev/ttyS6`). Bridging out over
Renode's modeled Ethernet MAC needs a host TAP interface
(`emulation CreateTap`), which needs `CAP_NET_ADMIN` to bring up even
though `/dev/net/tun` itself may be world-writable — treat that as a
real host-networking change and don't do it without deciding you want
it. The UART path needs no extra host privileges at all: it's the
exact same `CreateServerSocketTerminal` mechanism from §4.2, just
connected to `uart7` instead of `usart3`.

At the `(monitor)` prompt, in addition to the console bridge from §4.2:

```
emulation CreateServerSocketTerminal 3457 "telem1"
connector Connect sysbus.uart7 telem1
```

Then from the host, any real MAVLink client can connect over that
socket as if it were a TCP-attached telemetry radio — for example with
`pymavlink`:

```python
from pymavlink import mavutil
m = mavutil.mavlink_connection('tcp:127.0.0.1:3457')
print(m.wait_heartbeat(timeout=30))
```

This receives a genuine, correctly-parsed `HEARTBEAT` message
(`type: 2` = quadrotor, `autopilot: 12` = PX4) — real protocol-level
reachability, not just the boot log's `Starting MAVLink...` text. No
`mavlink-router` or other intermediate hop is needed; MAVSDK-family
tooling can connect the same way.

## 7. Troubleshooting

- **`renode-test` fails with `No module named 'psutil'` (or similar)**
  — the venv from §2.2 isn't activated, or wasn't created against the
  same Renode release's `tests/requirements.txt`.
- **`renode-test` reports a crash/broken connection around the same
  time as an external `timeout` you wrapped it in** — `renode-test`
  runs can legitimately take several minutes; don't wrap it in an
  aggressive external timeout, or let it run in the background instead.
- **Relative-path "File does not exist" errors under `renode-test`
  but not under a direct interactive `renode` run** — Renode's working
  directory differs between the two invocation styles. Always run
  `renode-test` from `meta-px4/`'s root and pass the ELF path via
  `--variable ELF:@<absolute-path>` as shown above, rather than editing
  paths inside the `.resc`/`.robot` files.

# Spec 003 (M3): PX4 boots in Renode

- **Status:** Draft
- **Created:** 2026-07-26
- **Depends on:** [000-architecture.md](000-architecture.md),
  [001-machine-pixhawk-6x.md](001-machine-pixhawk-6x.md) (REQ-4/AC-2
  deferred here — see below),
  [002-px4-firmware.md](002-px4-firmware.md) (Done: `px4-firmware`
  produces a verified, correctly-attributed `px4_fmu-v6x_default.elf`
  in `DEPLOY_DIR_IMAGE`, which this milestone boots for the first
  time).
- **Delivers:** A layer-provided Renode harness
  (`recipes-renode/pixhawk6x/*.repl`, `*.resc`, robot test) that boots
  the OE-built `px4-firmware` ELF against Renode's STM32H743 CPU
  model, reaching an NSH shell prompt and passing basic uORB/version
  smoke commands, runnable both interactively and headless
  (`renode-test`) for CI.

## 1. Rationale

M1 set up the machine/toolchain triad but explicitly could not
validate it against real hardware with a trivial recipe (spec 001
REQ-3's revision: `baremetal-helloworld` only supports QEMU machine
models, not `pixhawk-6x`). Its Renode boot requirement (REQ-4/AC-2)
was deferred to "wherever a real bootable ELF for `pixhawk-6x` first
exists" — that's now, since M2 delivered a verified
`px4_fmu-v6x_default.elf` (spec 002 §7: correct `Tag_CPU_name: "7E-M"`
/ `Tag_FP_arch: FPv5/FP-D16` attributes, byte-reproducible,
`BB_NO_NETWORK=1`-clean). This milestone is where the machine, the
toolchain, and the firmware all get validated together for the first
time, against something closer to real hardware behavior than a
compile-only check.

Per spec 000 §4.6, Renode itself is a **host prerequisite**, not an
OE recipe — it isn't installed on this development host yet (checked:
neither `renode` nor `renode-test` is on `PATH`), so bringing it up is
part of this milestone's real work, not assumed available.

## 2. M0-style findings (done against the real tree/host, before design)

- **Console UART**: `boards/px4/fmu-v6x/nuttx-config/nsh/defconfig`
  sets `CONFIG_USART3_SERIAL_CONSOLE=y` (already cited in spec 002
  §2) — the robot test's UART analyzer must attach to whichever
  Renode UART peripheral corresponds to STM32H753's USART3, not UART1
  or a default guess.
- **Renode's STM32H743 model**: Renode ships
  `platforms/cpus/stm32h743.repl` (per spec 000 §4.6, H753 is the same
  die plus crypto peripherals Renode's model doesn't need to
  distinguish for boot purposes). Confirm the exact peripheral names
  this `.repl` exposes (UART numbering, memory map) directly from the
  installed Renode's copy before writing the board overlay, not from
  memory of Renode's docs.
- **Renode install state**: not installed on this host. Options are a
  portable/self-contained release (tarball or AppImage from Renode's
  GitHub releases) versus a system package — prefer the portable
  route to avoid requiring root and to keep the pinned version
  reproducible/documented, consistent with treating it as a pinned
  host prerequisite rather than "whatever the system happens to
  have."
- **Boot gate definition** (spec 000 §5, M3 row): "Robot test: boot
  ELF → `nsh>` → `uorb status`, `ver all` pass" — the two concrete NSH
  commands this milestone's robot test must actually issue and check
  output from, not just "something prints."

## 3. Requirements

- **REQ-1** — Renode is brought up on the development host via a
  pinned, documented version (portable release preferred over a
  system package), with the exact version recorded in §6 once
  installed — not left as "whatever was latest."
- **REQ-2** — `recipes-renode/pixhawk6x/pixhawk6x.repl` uses Renode's
  own `platforms/cpus/stm32h743.repl` as its base (`using
  "platforms/cpus/stm32h743.repl"`) plus only the board-level deltas
  actually needed (memory aliasing consistent with
  `boards/px4/fmu-v6x/nuttx-config/scripts/script.ld`'s flash/RAM
  layout already recorded in `conf/machine/include/stm32h7.inc`; UART
  wiring for the real console, USART3 per §2).
- **REQ-3** — `recipes-renode/pixhawk6x/pixhawk6x-boot.resc` creates
  the machine from that `.repl`, loads
  `px4-firmware-1.17.0-pixhawk-6x.elf` from `DEPLOY_DIR_IMAGE` (not a
  hardcoded build-tree path), and starts emulation headless-compatible
  (no interactive-only Renode monitor commands).
- **REQ-4** — A Robot Framework test (`recipes-renode/pixhawk6x/
  pixhawk6x-boot.robot` or similar) drives the `.resc`, attaches a
  UART analyzer to the console UART, and asserts: NSH prompt
  (`nsh>`) appears within a bounded virtual-time window; `uorb status`
  returns without error; `ver all` returns without error and echoes
  recognizable PX4 version/build info. This directly satisfies spec
  001's deferred REQ-4/AC-2 as well as this milestone's own gate.
- **REQ-5** — The robot test runs headless via `renode-test`, exit
  code reflects pass/fail, runnable both interactively (for
  development) and in a CI-equivalent invocation (documented command,
  even if the actual CI pipeline itself is M6's job).
- **REQ-6** — Document (§6) any real fidelity gaps hit during
  bring-up (e.g. spec 000 R2's flagged PWR/RCC simplification or
  `CONFIG_STM32H7_PWR_IGNORE_ACTVOSRDY`), with the actual NuttX board
  config change needed to work around them if one is required —
  fixed forward from a real boot failure, not pre-emptively guessed.

## 4. Non-goals

- SIH-in-Renode simulation and the MAVLink bridge (M4).
- Real hardware flashing/validation (M5).
- A pinned/containerized Renode for hermetic CI, or the CI pipeline
  itself (M6) — this milestone documents a working headless
  invocation; wiring it into actual CI infrastructure is out of scope.
- `px4-io-firmware`/`px4-bootloader` booting in Renode — this
  milestone is about the main FMU firmware reaching an NSH prompt;
  the IO coprocessor and bootloader images built in M2 are not
  exercised here.

## 5. Design sketch

### 5.1 Layer layout

```
recipes-renode/
└── pixhawk6x/
    ├── pixhawk6x.repl        # board overlay over stm32h743.repl
    ├── pixhawk6x-boot.resc   # machine setup + ELF load + start
    └── pixhawk6x-boot.robot  # UART analyzer assertions, headless-runnable
```

Not an actual bitbake recipe (`.bb`) — Renode assets are host-side
test infrastructure, not something that gets cross-compiled or
deployed to target. `recipes-renode/` is a directory convention for
where these live in the layer, matching spec 000/001's naming, not a
signal that bitbake processes them.

### 5.2 Renode version pin

Record the exact installed version (e.g. via `renode --version`) once
chosen. Prefer whatever release NuttX's own Renode STM32H7 porting
notes reference as tested, if such notes exist and are checked
directly rather than assumed; otherwise a recent stable release,
pinned and recorded, not "latest" left unpinned.

### 5.3 Open question: does `px4_fmu-v6x_default` boot cleanly on Renode's H743 model as-is?

Not yet known — this is exactly what M3's real bring-up will answer.
Spec 000 R2 already flags simplified PWR/RCC modeling as a known
upstream Renode gap that *might* surface as a hang during NuttX's
clock/power init sequence. Resolve with evidence: attempt the boot
first with an unmodified NuttX board config; only reach for the
`CONFIG_STM32H7_PWR_IGNORE_ACTVOSRDY`-style workaround (or any other
board-config change) if a real failure demonstrates it's needed, and
document exactly which failure justified it in §6.

## 6. Implementation record (fill during/after implementation)

_Not yet started._

| Item | Decision / evidence |
|---|---|
| Renode version installed | _tbd_ |
| stm32h743.repl peripheral names (UART numbering) | _tbd_ |
| Boot result on first attempt (unmodified NuttX config) | _tbd_ |
| PWR/RCC fidelity gap encountered? | _tbd_ |

## 7. Acceptance criteria

- **AC-1** — Renode installed, version recorded (REQ-1).
- **AC-2** — `pixhawk6x.repl` + `pixhawk6x-boot.resc` load the real
  `DEPLOY_DIR_IMAGE` ELF and start emulation without Renode errors
  (REQ-2, REQ-3).
- **AC-3** — `renode-test` run of the robot test passes: `nsh>` prompt
  seen, `uorb status` and `ver all` both return successfully (REQ-4,
  REQ-5). This also closes spec 001's deferred REQ-4/AC-2.
- **AC-4** — Any real fidelity gaps hit are documented with the actual
  fix applied, in §6 (REQ-6).

# Spec 003 (M3): PX4 boots in Renode

- **Status: Done.** PX4 boots all the way to a live NSH prompt in
  Renode and passes `ver all`/`uorb status`, verified headlessly via
  `renode-test` (`recipes-renode/pixhawk6x/pixhawk6x-boot.robot`,
  ~20 seconds, `status OK`). Seven distinct real fidelity gaps were
  found and fixed along the way (§6): two PWR busy-waits, one USB OTG
  busy-wait, `spi1`/`spi2`/`spi3`/`spi5`/`spi6` unmodeled, `SDMMC2`
  unmodeled, and — the deepest one — a DMA-based console UART
  retransmission bug, root-caused via `cpu PC`/`LR` sampling in
  Renode's interactive telnet monitor to a Renode DMA2 model gap (not
  a NuttX bug), fixed via a new Renode-only `px4-firmware-renode`
  recipe that disables `CONFIG_USART3_TXDMA`/`RXDMA`. This closes
  REQ-4/AC-3 and spec 001's deferred REQ-4/AC-2.
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

- **REQ-1** — **Done.** Renode is brought up on the development host
  via a pinned, documented version (portable release preferred over a
  system package), with the exact version recorded in §6 once
  installed — not left as "whatever was latest." (1.16.1, §6.)
- **REQ-2** — **Done.** `recipes-renode/pixhawk6x/pixhawk6x.repl` uses
  Renode's own `platforms/cpus/stm32h743.repl` as its base (`using
  "platforms/cpus/stm32h743.repl"`) plus only the board-level deltas
  actually needed: no memory-region deltas (verified byte-for-byte
  match, §6), but real fidelity gaps did require deltas beyond the
  original UART-only expectation — PWR register tags,
  `spi1`/`spi2`/`spi3`/`spi5`/`spi6`, and `SDMMC2` all needed modeling
  (§5.3/§6).
- **REQ-3** — **Done, with a deliberate deviation from the original
  wording.** `recipes-renode/pixhawk6x/pixhawk6x-boot.resc` creates
  the machine from that `.repl` and starts emulation headless-
  compatible. It loads `px4-firmware-renode-1.17.0-pixhawk-6x.elf`
  from `DEPLOY_DIR_IMAGE` — the Renode-only variant (§5.3 gap #5),
  not `px4-firmware-1.17.0-pixhawk-6x.elf` (the real hardware image)
  as originally assumed here, since the real image's DMA-based
  console I/O cannot complete under Renode's DMA2 model. Both are
  still not hardcoded build-tree paths; the ELF path is a `$bin`
  monitor variable set by the caller.
- **REQ-4** — **Done.** A Robot Framework test
  (`recipes-renode/pixhawk6x/pixhawk6x-boot.robot`) drives the
  `.resc`, attaches a UART analyzer to the console UART, and asserts:
  NSH prompt (`nsh>`) appears within a bounded virtual-time window;
  `uorb status` returns without error; `ver all` returns without
  error and echoes recognizable PX4 version/build info. Verified
  against real console output, not guessed — see §6. This directly
  satisfies spec 001's deferred REQ-4/AC-2 as well as this milestone's
  own gate.
- **REQ-5** — **Done.** The robot test runs headless via
  `renode-test` (`renode-test recipes-renode/pixhawk6x/
  pixhawk6x-boot.robot --variable ELF:@<path-to-elf>`), exit code
  reflects pass/fail (`status OK`, ~20 seconds), runnable both
  interactively (for development) and in a CI-equivalent invocation
  (documented command, even if the actual CI pipeline itself is M6's
  job).
- **REQ-6** — **Done.** Document (§6) any real fidelity gaps hit
  during bring-up (e.g. spec 000 R2's flagged PWR/RCC simplification
  or `CONFIG_STM32H7_PWR_IGNORE_ACTVOSRDY`), with the actual NuttX
  board config change needed to work around them if one is required —
  fixed forward from a real boot failure, not pre-emptively guessed.
  Seven gaps found this way, documented in §5.3/§6; only one
  (the DMA-based console retransmission) needed a NuttX config change
  (`CONFIG_USART3_TXDMA`/`RXDMA`, carried only by the new
  `px4-firmware-renode` recipe) — `CONFIG_STM32H7_PWR_IGNORE_ACTVOSRDY`
  itself turned out not to exist at this SRCREV (checked directly),
  so all other fixes are Renode-side `.repl` additions.

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

### 5.3 Open question: does `px4_fmu-v6x_default` boot cleanly on Renode's H743 model as-is? — **Answered: no, five real gaps found so far**

Resolved with evidence exactly as planned: attempted the boot with an
unmodified NuttX board config first, fixed forward from each real
failure in turn (not pre-guessed), documented below in order
encountered. `CONFIG_STM32H7_PWR_IGNORE_ACTVOSRDY` (the workaround
named as a possibility when this spec was drafted) turned out not to
exist at all in PX4/NuttX@`fb2fadf6` — checked directly, not assumed —
so all fixes below are Renode-side (`.repl` overlay), not NuttX source
patches, which is the *less* invasive of the two paths spec 000 §4.6
anticipated.

**Gaps found and fixed, in the order a real boot hits them:**

1. **`PWR_CSR1.ACTVOSRDY` (bit 13, `0x58024804`)** —
   `stm32h7x3xx_rcc.c`'s clock config has an unconditional
   `while ((getreg32(STM32_PWR_CSR1) & PWR_CSR1_ACTVOSRDY) == 0) {}`.
   The base `stm32h743.repl` only tags the *adjacent* `D3CR` register
   for the analogous VOSRDY loop, not this one. Fixed with a `Tag`
   returning bit 13 set.
2. **`PWR_D3CR.VOSRDY` (bit 13, `0x58024818`) — regression from fix
   #1, not a new gap.** Renode's `.repl` format replaces rather than
   appends a derived file's `sysbus: init:` block against the base's
   own — confirmed empirically: adding *only* the CSR1 tag silently
   dropped the base repl's own D3CR tag, reproducing the *next* loop's
   hang one register later. Fixed by repeating the base's D3CR tag
   alongside the new one in the same block.
3. **`PWR_CR3.USB33RDY` (bit 26, `0x5802480C`)** — `stm32_otgdev.c`'s
   USB OTG-FS device power-up busy-waits on this bit before the
   console is even reached. Fixed with a `Tag`.
4. **`spi5` entirely unmodeled** — the base repl only models `spi4` as
   a real `SPI.STM32H7_SPI` peripheral; `spi5` (which fmu-v6x's
   sensors use) is left an inert `Tag` stub returning 0 forever.
   `stm32_spi.c`'s SPI driver has multiple unconditional,
   *unbounded* `while (...) ;` waits on `SPI_SR` status bits (TXP/RXP/
   EOT/SUSP) with no timeout at all — confirmed by reading the driver,
   not assumed — so any real transfer on an unmodeled SPI bus hangs
   forever. Fixed by modeling `spi5` the same way the base repl
   already models `spi4` (a real peripheral object, not a workaround).

After fix #4, the boot progresses **past all of NuttX's clock/power/
USB/SPI-bus bring-up and reaches genuine PX4 application code** —
confirmed by real `usart3` console output (PX4's own `PX4_ERR`-tagged
log lines), not just continued silence. This is a substantial result:
it validates the machine, toolchain, linker layout, and RCC/clock/USB/
SPI-bus bring-up all work together for the first time.

**Gap #5 — current blocker, not yet fixed: task-delay/tick fidelity.**
Console output shows `ERROR [PX4_MTD] failed to initialize mtd driver`
(from `platforms/nuttx/src/px4/common/px4_mtd.cpp`'s `ramtron_attach`,
expected — no FRAM/RAMTRON chip is modeled behind `spi5`) repeating
219,231 times in a 40-second real-time run. Read the source: this is
normally a *bounded* loop (30 attempts, `spi_speed_hz` stepping down
1MHz each try, `px4_usleep(10000)` between attempts — i.e. normally
~300ms to give up once). The log's own virtual-time counter proves
`usleep(10000)` (10ms requested) is actually costing only ~3.78
*microseconds* of virtual time — a ~2600x speedup, not a hang or a
reboot loop (virtual time is monotonically increasing throughout, at
~3.78µs/iteration, definitively ruling out a reset loop, which
would cost whole seconds of virtual time per cycle). NuttX's
scheduler tick/task-delay mechanism is not advancing correctly under
this Renode configuration, which turns what would be a normal,
bounded "give up after ~300ms and move on" retry into an effective
infinite tight loop that never reaches NSH. This is the same class of
risk spec 000 R2 flagged for M4's SIH timing, but it turns out to
already block M3's plain boot too.

**Follow-up investigation, several hypotheses tested and ruled out
with real evidence (not yet root-caused):**

- Verified directly in the real, built NuttX `.config`:
  `CONFIG_SCHED_TICKLESS is not set` — plain 1kHz `SysTick`-driven
  scheduling, `CONFIG_USEC_PER_TICK=1000`. Not a tickless-mode
  question.
- Verified `px4_usleep` maps to plain `system_usleep` (standard NuttX
  `usleep()`) for this hardware target — the
  `ENABLE_LOCKSTEP_SCHEDULER` path (SITL-only) is not compiled in, so
  this isn't a lockstep-simulation-specific code path.
- **Hypothesis: `SysTick` clock-rate mismatch.** The real, built
  `board.h` resolves `STM32_CPUCLK_FREQUENCY` = `STM32_PLL1P_FREQUENCY`
  = 480MHz (HSE 16MHz × PLL1N(60) / PLL1P(2)), while the base
  `stm32h743.repl` sets `nvic: systickFrequency: 96_000_000` — a real,
  concrete 5x mismatch against what `stm32_timerisr.c`'s
  `SYSTICK_RELOAD = (STM32_CPUCLK_FREQUENCY / CLK_TCK) - 1` assumes.
  **Tested and ruled out**: overriding `systickFrequency` to
  `480000000` in the board `.repl` produced **zero change** in the
  observed timing pattern (identical ~3.78µs-per-iteration behavior).
- **Confirmed via `cpu LogFunctionNames true`**: the `SysTick`
  interrupt handler (`stm32_timerisr`) genuinely *does* fire during
  boot — not simply absent/undelivered.
- **Confirmed via a clean (untraced) run**: `host`/`virt` time track
  each other almost exactly 1:1 up until the MTD retry loop begins
  (`host: 5.01s | virt: 4.97s`), then `virt` time essentially stalls
  while `host` time keeps advancing normally (`host: 9.06s | virt:
  5.11s` four seconds of host time later). This rules out a simple
  "sleep returns proportionally too fast" scaling bug — instead, the
  calling task appears to be rescheduled and resumed almost
  immediately without ever genuinely blocking for the requested
  duration, each time through the retry.

**Where this stands**: the `SysTick` timer interrupt fires correctly,
but whatever wait NuttX's `usleep()` → `clock_nanosleep()` →
`nxsig_nanosleep()` → `nxsig_timedwait()` chain uses to actually
suspend the calling task until enough ticks have elapsed does not
appear to be blocking it at all in this configuration. Traced the
call chain by reading `sched/signal/sig_nanosleep.c` directly: it
ultimately depends on a signal-wait-with-timeout primitive, not yet
traced further.

Attempted a direct memory-based diagnostic (read NuttX's tick counter,
`g_system_timer` at `0x24008258` — found via `arm-none-eabi-nm` on the
real ELF — before/after a deterministic `emulation RunFor "N"` virtual-
time advance, to measure the real tick rate independent of console
log noise) but hit two genuine Renode tooling limitations rather than
new evidence:
- A batch of several sequential `RunFor` calls in one `-e` invocation
  crashed Renode itself with an internal `.NET`
  `ObjectDisposedException` in `LimitTimer`/`BaseClockSource` — a real
  Renode-side issue with that usage pattern, not a NuttX bug.
- A single `RunFor` followed by `sysbus ReadDoubleWord` completed
  without error, but the read command's *result* never appeared
  anywhere in the log — Renode's headless `-e` batch mode does not
  appear to surface monitor query-command output the way an
  interactive/telnet session would.

**Conclusion for this round**: root cause not yet found. The
remaining honest options are (a) a genuinely interactive debugging
session (Renode's telnet monitor and/or GDB attached to it) to read
`g_system_timer` and step through the wait/wake path directly, which
is a materially bigger setup than the log-based experiments run so
far, or (b) documenting this as a known, real M3 blocker and revisiting
with fresh eyes/tools later rather than continuing to guess at
register-level fixes with diminishing returns.

**Follow-up: got the interactive session working, with a major
correction to the diagnosis above.** Renode's `-P <port>` flag runs
the monitor on a plain TCP socket instead of `-e` batch mode; a small
Python client (raw `socket`, not `telnetlib`) against it *does*
surface query-command output, unlike headless `-e` batch mode.

- Read `g_system_timer` (NuttX's tick counter) directly via `sysbus
  ReadDoubleWord 0x24008258` and compared it against Renode's own
  `machine ElapsedVirtualTime`: **6670 ticks vs. 6.672162230s
  elapsed — an exact match** (`CONFIG_USEC_PER_TICK=1000`, so 6670
  ticks *should* be 6.670s; matches to within 2ms). **This proves the
  SysTick/scheduler tick mechanism is genuinely correct** — the
  global tick counter advances in exact lockstep with Renode's own
  notion of virtual time. The earlier "2600x speedup" framing was a
  misreading of the evidence: the *global* clock is not broken.
- Tested the obvious follow-up hypothesis directly: maybe the boot
  isn't stuck at all, just slow, because of the sheer log volume from
  unrelated unmodeled DMA/NVIC sub-registers (each `Unhandled write`
  warning costs real wall-clock time to log, and there are many per
  virtual millisecond). Let a live instance run for several real
  minutes via the interactive session and watched both the `usart3`
  console and `ElapsedVirtualTime`. **Refuted**: over the full
  observation window the "failed to initialize mtd driver" message
  count grew linearly to over 1.12 million, virtual time crept from
  ~6.3s to only ~13s (getting *slower* in real-time terms as the log
  grew, not faster), and **every single occurrence showed the exact
  same `+3.78µs` virtual-time delta from the previous one, unchanged
  throughout** — this is not converging on eventually completing a
  bounded ~300ms give-up sequence at any real-time timescale worth
  waiting for.
- **Refined conclusion**: the global scheduler tick is correct, but
  whatever specific code path prints "failed to initialize mtd
  driver" is not experiencing genuine 10ms `usleep()` delays between
  attempts — each iteration costs only ~3.78µs of *virtual* time, far
  less than even one 1ms tick. This means the earlier hypothesis
  (something in this specific call chain isn't blocking on ticks
  properly) still stands; what's newly ruled out is any explanation
  resting on the *global* clock/scheduler being broken, or on the
  simulation merely being "slow but working."

**Root cause found: it was never `px4_mtd.cpp`'s retry loop at
all.** Paused the live instance repeatedly via the interactive
session and read `cpu PC`/`cpu LR` each time, resolving the addresses
against the real ELF with `arm-none-eabi-addr2line`. Five samples
across several real seconds: four landed squarely inside
`up_dma_send`/`up_dma_txavailable` (`arch/arm/src/chip/stm32_serial.c`
lines 3386-3441) and one inside `stm32_sdma_interrupt`
(`arch/arm/src/chip/stm32_dma.c:1230`, reached via `irq_dispatch`) —
the STM32 serial driver's **DMA-based UART transmit path and its
completion interrupt handler**, not anywhere in `px4_mtd.cpp`.

This means the actual MTD failure almost certainly happened *once*,
as a normal bounded event — but the resulting `PX4_ERR` console
message got stuck being **re-transmitted forever** by the serial
driver's DMA logic, because Renode's `DMA.STM32DMA` model for `dma2`
never signals the transfer-complete condition `up_dma_send` is
waiting for (matching the constant stream of `dma2: Unhandled write`
warnings seen throughout — `TEIE`, `CFEIF0`/`CDMEIF0`/`CTEIF0`/
`CHTIF0`, `TRBUFF`, register bits/features the model doesn't
implement). Confirmed the console is configured for exactly this
path: `boards/px4/fmu-v6x/nuttx-config/nsh/defconfig` sets
`CONFIG_USART3_TXDMA=y` (and `CONFIG_USART3_RXDMA=y`) for the console
UART.

This reframes gap #5 entirely: it is likely **not** a NuttX task-
delay/scheduling bug at all (the tick mechanism is proven correct),
but an incomplete Renode DMA controller model interacting badly with
DMA-based console TX specifically. The MTD retry itself may already
be working exactly as designed underneath the runaway retransmission.
Two realistic fixes, not yet decided (see below): (a) disable
`CONFIG_USART3_TXDMA`/`RXDMA` for a Renode-specific build variant, or
(b) find a Renode-side DMA2 configuration/model fix. This changes the
relationship between M2 and M3: M3 was assumed to boot the *exact*
M2-built `px4-firmware` artifact unmodified; option (a) would instead
need a distinct, Renode-only build configuration.

**Decided and implemented: option (a).** Added a new recipe,
`px4-firmware-renode_1.17.0.bb` (`recipes-px4/px4-firmware-renode/`),
identical to `px4-firmware` except it carries one additional patch
(`0002-boards-px4-fmu-v6x-disable-USART3-DMA-for-Renode.patch`,
generated with `git format-patch` against the pinned SRCREV) that
flips `CONFIG_USART3_RXDMA`/`TXDMA` to `# ... is not set` in
`boards/px4/fmu-v6x/nuttx-config/nsh/defconfig`, forcing
interrupt-driven console I/O instead of DMA. The real hardware
`px4-firmware` recipe is completely unaffected — this patch is
carried only by the new recipe. Built successfully
(`bitbake mc:pixhawk6x:px4-firmware-renode`, deploying
`px4-firmware-renode-1.17.0-pixhawk-6x.{elf,px4}`).

**Verified the fix directly against a real boot**: the "failed to
initialize mtd driver" message that previously repeated over
1.12 million times now appears **exactly once**, immediately followed
by genuine subsequent boot output that was never reached before —
`ERROR [PX4_MTD] mtd failure: -5 bus 2 address 0 class 1` (the
expected, gracefully-handled failure, not a hang), `[boot] Rev 0x0 :
Ver 0x0 V6X000`, `reset done, 10 ms`, `[boot] Fault Log info File No 4
Length 3177 flags:0x01 state:1`, `[boot] Fault Log is Armed`. This
conclusively confirms the root-cause diagnosis: the earlier "infinite
loop" was entirely the DMA UART retransmission bug, not a problem
with `px4_mtd.cpp`'s own retry logic or the scheduler.

Boot then progressed to a new, different, and far less severe
peripheral gap: repeated `ReadDoubleWord` from `0x48022434` (`SDMMC2`
range, per the base repl's own `Tag <0x48022400, 0x480227FF>
"SDMMC2"` — an inert stub, no real `SD.STM32HSDMMC` object modeled
for it, unlike `SDMMC1` at `0x52007000` which the base repl does
model). Only ~3,157 occurrences in a 90-second real-time run (versus
1.12 million+ for the DMA bug) — a normal, bounded-looking
peripheral-probe gap of the same general kind already fixed for
`spi5`, not evidence of another deep timing issue. **Fixed** by
modeling `sdmmc2` as `SD.STM32HSDMMC` (IRQ 124, per
`STM32_IRQ_SDMMC2 = STM32_IRQ_FIRST + 124` for the stm32h7x3xx family
— checked directly in NuttX's own IRQ header, not assumed).

That fix unblocked the boot far enough to hit the same class of gap
on the remaining SPI buses fmu-v6x enables
(`CONFIG_STM32H7_SPI1/2/3/6=y`, in addition to SPI4/5 already
modeled/fixed) — `spi1`/`spi2`/`spi3`/`spi6` were all still inert
`Tag` stubs in the base repl. **Fixed** proactively (all four at
once, rather than one real-hang-at-a-time) by modeling them the same
way as `spi4`/`spi5`. Note the base repl's own tag labels for
`0x40003800`/`0x40003C00` match the *real* STM32H7 memory map
(SPI2/SPI3 respectively) — the more common assumption that SPI2 is
at `0x40003C00` is backwards; verified directly against the repl, not
assumed.

**After all seven gaps were fixed, PX4 reaches a genuine, live NSH
prompt** — confirmed with real console output: full boot banner
(`HW arch: PX4_FMU_V6X`, PX4/NuttX versions, git hashes), sensor
probes correctly reporting "no device on bus" (expected — no sensor
chips are modeled), MAVLink/logger/uavcan startup, and finally
`NuttShell (NSH) NuttX-11.0.0` followed by a working `nsh>` prompt.

**Automated via `renode-test`** (`recipes-renode/pixhawk6x/
pixhawk6x-boot.robot`), after fixing two harness-specific issues
unrelated to the firmware itself:
- Relative paths in `.resc`/`.repl` `include`/`LoadPlatformDescription`
  calls depend on Renode's own working directory, which differs
  between a direct `renode -e` invocation (the shell's cwd) and
  `renode-test` (its own internal cwd). Fixed by passing both the ELF
  and `.repl` paths as monitor variables (`$bin`, `$repl`) built from
  Robot Framework's `${CURDIR}`, rather than hardcoding relative paths
  inside the `.resc`.
- Robot Framework's space-separated test format treats runs of 2+
  spaces as cell separators — an assertion string built from the
  padded console header line (`TOPIC NAME               INST #SUB
  #Q SIZE PATH`) was silently split into multiple arguments,
  producing a confusing unrelated .NET exception rather than a clear
  parse error. Fixed by asserting on a short, space-safe substring
  (`TOPIC NAME`) instead of the full padded line.
- The initial guess for the `uorb status` assertion text
  (`uorb total subscribers`) was wrong, exactly as expected for an
  unverified guess — found the real output (`TOPIC NAME` header,
  followed by the topic table) by reading the full saved Renode log
  from a failed test run, then corrected the assertion.

Final result: `renode-test recipes-renode/pixhawk6x/
pixhawk6x-boot.robot` passes in **~20 seconds**, `status OK` —
`ver all` and `uorb status` both verified against real, not guessed,
output.

## 6. Implementation record

| Item | Decision / evidence |
|---|---|
| Renode version installed | **1.16.1** (`renode-1.16.1.linux-portable-dotnet.tar.gz`, self-contained with bundled .NET runtime — no root/system dependency). Downloaded from the official GitHub release; SHA-256 verified against GitHub's own published digest before extracting. Installed under `oe-px4/tools/renode/` (host tooling, gitignored, same convention as `bitbake`/`bitbake-builds`). A separate Python venv (`oe-px4/tools/renode-test-venv/`, also gitignored) provides `renode-test`'s own dependencies (`robotframework`, `psutil`, etc., per its `tests/requirements.txt`). |
| stm32h743.repl peripheral names (UART numbering) | Console is `usart3` (`UART.STM32F7_USART @ sysbus 0x40004800`), matching `CONFIG_USART3_SERIAL_CONSOLE=y`. Checked directly in the installed Renode's own copy of the platform file, not from memory of docs. |
| Memory-region deltas needed | **None.** Every `MEMORY` region in the real `script.ld` (ITCM/FLASH/DTCM1+2/AXI_SRAM/SRAM1-4/BKPRAM) matches an existing `stm32h743.repl` object exactly, byte for byte — verified by direct comparison before writing the overlay, not assumed from the M1 flash/RAM constants alone. |
| Boot result on first attempt (unmodified NuttX config, base repl) | **Hung immediately** on `PWR_CSR1.ACTVOSRDY` busy-wait (gap #1). |
| PWR/RCC fidelity gaps | **Three separate busy-waits** (gaps #1-3), all fixed via `.repl` `Tag` overrides, no NuttX source patch (confirmed no existing NuttX config option covers any of them at this SRCREV). |
| DMA-based console UART retransmission (gap #5) | Root-caused via `cpu PC`/`LR` sampling over Renode's interactive telnet monitor (`-P <port>` + a raw Python `socket` client — headless `-e` batch mode doesn't surface query-command output) resolved against the real ELF with `arm-none-eabi-addr2line`: stuck in `up_dma_send`/`stm32_sdma_interrupt` (serial driver DMA path), not `px4_mtd.cpp`. Renode's `DMA.STM32DMA` model for `dma2` never signals transfer-complete, so the console's DMA-based TX loops forever re-sending one buffered message. Fixed via a new `px4-firmware-renode` recipe (patch disabling `CONFIG_USART3_TXDMA`/`RXDMA`) — the real hardware `px4-firmware` recipe is unaffected. Verified directly: took the repeated error message from 1.12 million+ occurrences down to exactly one. |
| `spi1`/`spi2`/`spi3`/`spi5`/`spi6` fidelity gaps | **Yes** (gaps #4, #7) — all fixed by modeling them as `SPI.STM32H7_SPI`, matching the base repl's own treatment of `spi4`. `stm32_spi.c`'s SPI driver has unconditional, unbounded `while` waits on `SPI_SR` with no timeout at all, so any unmodeled SPI bus hangs forever on first real transfer. |
| `SDMMC2` fidelity gap | **Yes** (gap #6) — fixed by modeling it as `SD.STM32HSDMMC` (IRQ 124), matching the base repl's own treatment of `SDMMC1`. |
| Reached PX4 application code? | **Yes**, and further: reached a fully live NSH prompt with `ver all`/`uorb status` both verified. |
| NSH prompt reached? | **Yes** — confirmed via a real `renode-test` run, `status OK`, ~20 seconds. |

## 7. Acceptance criteria

- **AC-1** — **Done.** Renode installed (1.16.1), version recorded
  (REQ-1).
- **AC-2** — **Done.** `pixhawk6x.repl` + a direct `renode -e` boot
  (the `.resc`'s own content, run inline while iterating) load the
  real `DEPLOY_DIR_IMAGE` ELF and start emulation without Renode
  errors, reaching genuine PX4 application code on the console
  (REQ-2, REQ-3).
- **AC-3** — **Done.** `renode-test recipes-renode/pixhawk6x/
  pixhawk6x-boot.robot` passes (`status OK`, ~20 seconds): `nsh>`
  prompt reached, `ver all` and `uorb status` both verified against
  real (not guessed) console output (REQ-4, REQ-5). This closes spec
  001's deferred REQ-4/AC-2.
- **AC-4** — **Done.** All seven real fidelity gaps found along the
  way are documented with their actual fixes in §5.3/§6 (REQ-6).

#!/usr/bin/env python3
"""
Headless SIH arm->takeoff->land test against Renode.

Per specs/004-sih-renode-mavlink.md REQ-5: boots px4-firmware-renode
(SIH-enabled) under Renode, bridges the TELEM1 MAVLink UART to a TCP
socket the same way SIMULATION.md documents for manual use, and drives
a real MAVSDK client through arm -> takeoff -> land. Exit code
reflects pass/fail, so this is runnable in CI the same way spec 003's
renode-test-based boot check is.

Requires a Renode built from renode-patches/ -- PX4's work-queue
scheduling (which SIH depends on) does not run under the plain
portable Renode release; see renode-patches/README.md.
"""
import argparse
import asyncio
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

from mavsdk import System
from mavsdk.action import ActionError

HERE = Path(__file__).resolve().parent


def wait_for_port(host, port, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with socket.create_connection((host, port), timeout=1):
                return True
        except OSError:
            time.sleep(0.5)
    return False


def start_renode(renode_bin, repl, elf, mavlink_port, console_port, log_path):
    resc = HERE / "pixhawk6x-sih-flight.resc"
    monitor_cmds = (
        f"set repl @{repl}; "
        f"set bin @{elf}; "
        f"set mavlink_port {mavlink_port}; "
        f"set console_port {console_port}; "
        f"include @{resc}"
    )
    cmd = [renode_bin, "--disable-xwt", "-e", monitor_cmds]
    log = open(log_path, "wb")
    proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
    return proc, log


def send_console_command(host, port, command, timeout=5):
    with socket.create_connection((host, port), timeout=timeout) as s:
        s.settimeout(timeout)
        try:
            s.recv(65536)
        except socket.timeout:
            pass
        s.sendall((command + "\n").encode())
        time.sleep(1.5)
        try:
            return s.recv(65536).decode(errors="replace")
        except socket.timeout:
            return ""


def capture_console(host, port, log_path, stop_event, timeout=90):
    if not wait_for_port(host, port, timeout):
        return
    try:
        with open(log_path, "wb") as f, socket.create_connection((host, port), timeout=2) as s:
            s.settimeout(0.5)
            while not stop_event.is_set():
                try:
                    data = s.recv(65536)
                    if not data:
                        break
                    f.write(data)
                    f.flush()
                except socket.timeout:
                    continue
    except OSError:
        pass


async def run_flight(port, takeoff_alt, hover_time, connect_timeout):
    drone = System()
    try:
        await drone.connect(system_address=f"tcp://127.0.0.1:{port}")

        print("Waiting for MAVLink connection...")
        async for state in drone.core.connection_state():
            if state.is_connected:
                print("Connected.")
                break

        # Deliberately not gating on drone.telemetry.health()'s
        # is_global_position_ok/is_home_position_ok here: PX4's own
        # internal state (vehicle_global_position, home_position) is
        # genuinely valid from shortly after boot (confirmed directly
        # via NSH `listener`), but MAVSDK's client-side health flags lag
        # far behind on the TELEM1 link's low-bandwidth "Normal" MAVLink
        # stream -- they may never catch up within any reasonable
        # timeout even though PX4 itself is ready to arm. Attempt arm()
        # directly instead and let PX4's own COMMAND_ACK be the real
        # authority, matching how a plain NSH `commander arm` already
        # works against this same firmware.
        print("Arming...")
        start = time.monotonic()
        while True:
            try:
                await asyncio.wait_for(drone.action.arm(), timeout=15)
                break
            except (ActionError, asyncio.TimeoutError) as e:
                if time.monotonic() - start > connect_timeout:
                    raise RuntimeError(f"Could not arm within timeout: {e}")
                print(f"  arm attempt failed ({e}), retrying...")
                await asyncio.sleep(2)

        print(f"Setting takeoff altitude to {takeoff_alt} m...")
        await drone.action.set_takeoff_altitude(takeoff_alt)

        print("Taking off...")
        await drone.action.takeoff()

        print("Waiting to reach target altitude...")
        reached = False
        start = time.monotonic()
        async for pos in drone.telemetry.position():
            alt = pos.relative_altitude_m
            print(f"  altitude: {alt:.2f} m")
            if alt >= takeoff_alt * 0.8:
                reached = True
                break
            if time.monotonic() - start > connect_timeout:
                break
        if not reached:
            raise RuntimeError("Did not reach target altitude within timeout")

        print(f"Hovering for {hover_time}s...")
        await asyncio.sleep(hover_time)

        print("Landing...")
        await drone.action.land()

        print("Waiting to land...")
        start = time.monotonic()
        async for in_air in drone.telemetry.in_air():
            print(f"  in_air: {in_air}")
            if not in_air:
                break
            if time.monotonic() - start > connect_timeout:
                raise RuntimeError("Did not land within timeout")
    finally:
        # System.__del__ is supposed to stop the mavsdk_server subprocess
        # it spawns, but __del__ is not reliably called before interpreter
        # exit (confirmed: repeat runs of this script left stray
        # mavsdk_server processes behind, all bound to the same default
        # gRPC port 50051, breaking subsequent runs). Stop it explicitly.
        drone._stop_mavsdk_server()

    print("Landed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--renode", required=True, help="Path to the timer-patched Renode binary/wrapper")
    parser.add_argument("--elf", required=True, help="Path to px4-firmware-renode-*.elf")
    parser.add_argument("--repl", default=str(HERE / "pixhawk6x.repl"))
    parser.add_argument("--port", type=int, default=5762, help="TCP port to bridge TELEM1 to")
    parser.add_argument("--console-port", type=int, default=5763, help="TCP port to bridge the NSH console to")
    parser.add_argument("--takeoff-alt", type=float, default=5.0, help="Takeoff altitude in meters")
    parser.add_argument("--hover-time", type=float, default=10.0, help="Seconds to hover before landing")
    parser.add_argument("--stage-timeout", type=float, default=90.0, help="Per-stage timeout in seconds")
    parser.add_argument("--log", default="sih_flight_test.renode.log", help="Where to write Renode's own log")
    parser.add_argument("--console-log", default="sih_flight_test.console.log", help="Where to write the NSH console log")
    parser.add_argument("--mav-rate", type=int, default=50000, help="MAV_0_RATE (B/s) to set on TELEM1 before connecting MAVSDK")
    args = parser.parse_args()

    sys.stdout.reconfigure(line_buffering=True)
    print(f"Starting Renode ({args.renode})...")
    proc, log = start_renode(args.renode, args.repl, args.elf, args.port, args.console_port, args.log)
    stop_event = threading.Event()
    console_thread = None
    try:
        print(f"Waiting for TELEM1 socket on port {args.port}...")
        if not wait_for_port("127.0.0.1", args.port, args.stage_timeout):
            print(f"FAIL: TELEM1 socket never came up (see {args.log})", file=sys.stderr)
            return 1

        # TELEM1 defaults to MAV_0_RATE 1200 (a real-radio-appropriate
        # rate baked into the airframe/board defaults) -- far too slow
        # for MAVSDK's own parameter-sync/health machinery to keep up,
        # even though PX4's own internal state is fine (see
        # specs/004-sih-renode-mavlink.md REQ-5 implementation notes).
        # Raise it over the console before connecting MAVSDK. Done as a
        # one-shot connection *before* starting the persistent console
        # capture thread below, since Renode's CreateServerSocketTerminal
        # is not known to support multiple simultaneous clients cleanly.
        print(f"Setting MAV_0_RATE to {args.mav_rate} over the console...")
        if not wait_for_port("127.0.0.1", args.console_port, args.stage_timeout):
            print(f"FAIL: console socket never came up (see {args.log})", file=sys.stderr)
            return 1
        send_console_command("127.0.0.1", args.console_port, f"param set MAV_0_RATE {args.mav_rate}")

        console_thread = threading.Thread(
            target=capture_console,
            args=("127.0.0.1", args.console_port, args.console_log, stop_event, args.stage_timeout),
            daemon=True,
        )
        console_thread.start()

        # A hard overall watchdog on top of run_flight()'s own per-stage
        # timeouts: any single MAVSDK call (e.g. arm()) can in principle
        # hang forever waiting for an ACK that never arrives rather than
        # raising, which would make a timeout check placed *after* an
        # await never actually run. This guarantees the test terminates
        # either way.
        overall_timeout = args.stage_timeout * 5 + args.hover_time
        try:
            asyncio.run(asyncio.wait_for(
                run_flight(args.port, args.takeoff_alt, args.hover_time, args.stage_timeout),
                timeout=overall_timeout,
            ))
        except Exception as e:
            print(f"FAIL: {e} (see {args.log} and {args.console_log})", file=sys.stderr)
            return 1

        print("PASS")
        return 0
    finally:
        stop_event.set()
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
        log.close()
        if console_thread is not None:
            console_thread.join(timeout=5)


if __name__ == "__main__":
    sys.exit(main())

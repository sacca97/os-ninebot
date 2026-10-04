"""f2 — research CLI for Ninebot / Segway F2 Pro BLE.

Safety tiers:
  no Bluetooth at all : decode, keys
  passive / read-only : scan, gatt          (no GATT writes)
  init                : connects, sends only 0x5B INIT (no credential), reports whether a password is stored
  authenticated reads : status, read        (0x5B INIT + 0x5D login with the stored key, then READ frames)
  power               : power on|off        the two captured power frames (what the app calls unlock / lock)
  pairing             : pair --force        REPLACES the scooter's password (breaks the official app until re-paired)
No other writes exist.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import secrets
import sys
from pathlib import Path

from . import decode as decode_mod
from .protocol import NINEBOT_MANUFACTURER_ID, NUS_SERVICE_UUID, Cmd, Dev
from .registers import BY_KEY, REGISTERS, STATUS_KEYS

CONFIG_DIR = Path(os.environ.get("F2PROBE_HOME", Path.home() / ".config" / "f2probe"))
APP_KEY_FILE = CONFIG_DIR / "app_key.hex"


# -- app key storage ------------------------------------------------------


def load_app_key() -> bytes | None:
    try:
        key = bytes.fromhex(APP_KEY_FILE.read_text().strip())
    except FileNotFoundError:
        return None
    if len(key) != 16:
        sys.exit(f"{APP_KEY_FILE}: expected 16 bytes of hex")
    return key


def parse_key_text(text: str) -> bytes:
    """The credential file / string format shared with the Android app: 32 hex chars (whitespace, ':' and a 0x prefix tolerated)."""
    clean = "".join(c for c in text if not c.isspace() and c != ":")
    if clean[:2] in ("0x", "0X"):
        clean = clean[2:]
    try:
        key = bytes.fromhex(clean)
    except ValueError:
        key = b""
    if len(key) != 16 or len(clean) != 32:
        raise ValueError("expected exactly 32 hex characters (16 bytes)")
    return key


def save_app_key(key: bytes, source: str) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    APP_KEY_FILE.write_text(key.hex().upper() + "\n")
    APP_KEY_FILE.chmod(0o600)
    print(f"Saved app key ({source}) to {APP_KEY_FILE}")


# -- commands -------------------------------------------------------------


def cmd_decode(args) -> None:
    app_key = bytes.fromhex(args.app_key) if args.app_key else None
    trace, results = decode_mod.decode(args.capture, args.name, app_key)
    if args.gatt:
        print(f"Advertised: {dict(trace.adv_names)}\nNinebot addrs: {sorted(trace.ninebot_addrs)}")
        print(f"Connections: {dict((hex(k), v) for k, v in trace.conn_addr.items())}\n")
        print(decode_mod.format_gatt(trace) or "No ATT writes/notifications in capture.")
        return
    if trace.truncated_records:
        print(f"WARNING: {trace.truncated_records} truncated records. Set the Android snoop log to "
              "'Enabled' (full), not 'Filtered', or payloads will be missing.\n")  # fmt: skip
    if not results:
        print(f"No Ninebot (5AA5) frames found in this capture ({len(trace.att)} ATT writes/notifications).")
        print("Run again with --gatt to dump all GATT traffic (newer protocol?).")
        if trace.adv_names:
            print("Advertised names seen:", ", ".join(sorted(set(trace.adv_names.values()))))
        return
    if args.json:
        Path(args.json).write_text(decode_mod.to_json(results))
        print(f"Wrote {args.json}")
    for r in results:
        rows = r.rows if args.all else [x for x in r.rows if not (x.ok and x.cmd in (Cmd.READ, Cmd.READ_ACK))]
        ok = sum(x.ok for x in r.rows)
        print(f"=== connection 0x{r.conn:03X}  name={r.name!r}  serial={r.serial}  "
              f"frames={len(r.rows)} decrypted={ok}")  # fmt: skip
        print(f"    ble_data={r.ble_data}\n    app_data={r.app_data}")
        if r.ble_data and not r.app_data and ok < len(r.rows):
            print("    NOTE: no SET_PWD (0x5C) in this capture: the app reused a password it stored when it first\n"
                  "    paired, so frames after INIT cannot be decrypted. Either supply it with --app-key HEX, or\n"
                  "    record a capture that includes pairing (see README 'Capturing a pairing').")
        if not args.all:
            print("    (READ / READ_ACK polling hidden; use --all to show)")
        print(decode_mod.format_rows(rows, args.raw))
        writes = [x for x in r.rows if x.ok and x.cmd in (Cmd.WRITE, Cmd.WRITE_NO_REPLY)]
        if writes:
            print(f"\n--- {len(writes)} WRITE frame(s) sent by the official app ---")
            print(decode_mod.format_rows(writes, show_raw=True))
        print()
    if args.save_app_key:
        keys = {r.app_data for r in results if r.app_data}
        if len(keys) != 1:
            sys.exit(f"cannot save app key: found {len(keys)} distinct app keys in capture")
        save_app_key(bytes.fromhex(keys.pop()), f"official app, from {args.capture}")


def cmd_keys(args) -> None:
    try:
        if args.set:
            save_app_key(parse_key_text(args.set), "manual")
        if args.import_file:
            src = Path(args.import_file)
            save_app_key(parse_key_text(src.read_text()[:1024]), f"file {src}")
    except (ValueError, OSError) as e:
        sys.exit(f"Import failed: {e}")
    if args.export_file:
        key = load_app_key()
        if key is None:
            sys.exit("No app key stored; nothing to export.")
        dest = Path(args.export_file)
        if dest.exists() and not args.force:
            sys.exit(f"{dest} exists; use --force to overwrite it.")
        dest.touch(mode=0o600)
        dest.chmod(0o600)
        dest.write_text(key.hex().upper() + "\n")
        print(f"Exported the app key to {dest} (mode 600). Keep it private.")
        return
    key = load_app_key()
    print(f"{APP_KEY_FILE}: {key.hex().upper() if key else '(none)'}")


async def cmd_scan(args) -> None:
    from . import transport

    found = await transport.scan(args.timeout, only_ninebot=not args.all)
    if not found:
        print("Nothing found.")
    for dev, adv in found:
        mfr = adv.manufacturer_data.get(NINEBOT_MANUFACTURER_ID)
        print(f"{adv.rssi:>4} dBm  {dev.address}  name={adv.local_name or dev.name!r}")
        if mfr is not None:
            print(f"          ninebot mfr data: {mfr.hex().upper()}")
        if adv.service_uuids:
            print(f"          services: {', '.join(adv.service_uuids)}")


async def cmd_gatt(args) -> None:
    from bleak import BleakClient

    from . import transport

    dev, adv = await transport.find(args.address)
    print(f"Connecting to {adv.local_name or dev.name} [{dev.address}] (service discovery only, no writes)")
    async with BleakClient(dev) as c:
        for s in c.services:
            mark = "   <- Nordic UART" if s.uuid == NUS_SERVICE_UUID else ""
            print(f"service {s.uuid}  {s.description}{mark}")
            for ch in s.characteristics:
                print(f"   char {ch.uuid}  handle=0x{ch.handle:04X}  [{', '.join(ch.properties)}]  {ch.description}")


async def _find(args):
    from . import transport

    dev, adv = await transport.find(args.address)
    name = args.name or adv.local_name or dev.name
    if not name:
        sys.exit("scooter name unknown (needed for crypto); pass --name")
    print(f"Found: {name} [{dev.address}]")
    return dev, name


async def _with_client(args, body, **client_kwargs) -> None:
    from . import transport

    app_key = load_app_key()
    if app_key is None:
        sys.exit(
            "No app key stored. Reuse the official app's credential from a pairing capture:\n"
            "  f2 decode <capture> --app-key HEX --save-app-key\n"
            "(`f2 pair --force` would instead replace the scooter's password with a new one.)"
        )
    dev, name = await _find(args)
    client = transport.Client(dev, name, app_key, debug=args.debug, **client_kwargs)
    try:
        await client.open()
        await client.authenticate()
        print("Auth: OK\n")
        await body(client)
    except transport.AuthError as e:
        sys.exit(f"Login failed: {e}\nNot retrying. Check that the official app is disconnected and the key is current.")
    finally:
        await client.disconnect()


async def cmd_init(args) -> None:
    """Connect and send only 0x5B INIT, no credential involved."""
    from . import transport

    dev, name = await _find(args)
    client = transport.Client(dev, name, None, debug=args.debug)
    try:
        reply = await client.open()
        print(f"Serial:           {reply.data[16:].decode(errors='replace')}")
        print(f"Auth challenge:   {reply.data[:16].hex().upper()}  (changes every connection)")
        print(f"Password stored:  {'yes' if reply.idx else 'no'}  (INIT reply index {reply.idx})")
    finally:
        await client.disconnect()


async def cmd_pair(args) -> None:
    """Replace the scooter's stored password with a new random one (explicit, gated)."""
    from . import transport

    if not args.force:
        sys.exit("Refusing: `f2 pair` REPLACES the scooter's password. Re-run with --force if you mean it.")
    print("WARNING: this sets a NEW password on the scooter. The official Segway app will stop working\n"
          "until it is paired again (which in turn invalidates the key saved by this tool).")  # fmt: skip
    if input("Type PAIR to continue: ").strip() != "PAIR":
        sys.exit("Aborted.")

    new_key = secrets.token_bytes(16)
    # Persist the new key BEFORE touching the scooter so it can never be lost, and keep the old one.
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    pending = CONFIG_DIR / "app_key.pending"
    pending.write_text(new_key.hex().upper() + "\n")
    pending.chmod(0o600)
    if APP_KEY_FILE.exists():
        backup = CONFIG_DIR / "app_key.hex.bak"
        backup.write_text(APP_KEY_FILE.read_text())
        backup.chmod(0o600)
        print(f"Previous key backed up to {backup}")

    dev, name = await _find(args)
    client = transport.Client(dev, name, new_key, debug=args.debug, allow_pairing=True)
    try:
        await client.open()
        await client.set_password()
        await client.authenticate(reset_counter=False)
    except transport.AuthError as e:
        sys.exit(f"Pairing failed: {e}\nThe new key stays in {pending}; the previous key is in app_key.hex.bak.")
    finally:
        await client.disconnect()
    pending.replace(APP_KEY_FILE)
    print(f"Paired. New key saved to {APP_KEY_FILE}")


async def cmd_status(args) -> None:
    async def body(client) -> None:
        # The BLE board answers even when the scooter is off; the controller and battery boards do not.
        state = await client.power_state()
        print(f"{'Power state (0x4D):':<22}{'on' if state else 'off'} (raw {state})")
        if not state:
            print("\nScooter is off: nothing else to read. Turn it on with `f2 power on`.")
            return
        regs = list(REGISTERS) if args.all else [BY_KEY[k] for k in STATUS_KEYS]
        misses = 0
        for reg in regs:
            if reg.key == "power_state":
                continue
            try:
                val = await client.read(reg)
                misses = 0
            except TimeoutError:
                val = "<no reply>"
                misses += 1
            except Exception as e:  # keep going; unknown registers on F2 are expected
                val = f"<{type(e).__name__}: {e}>"
            print(f"{reg.label + ':':<22}{val} {reg.unit}".rstrip())
            if misses >= 3:
                print("\nStopping: 3 reads in a row got no reply. The scooter may have just powered off.")
                return

    await _with_client(args, body)


async def cmd_power(args) -> None:
    on = args.state == "on"

    async def body(client) -> None:
        state = await client.power_state()
        print(f"Power state now: {'on' if state else 'off'} (0x4D = {state})")
        if state == (1 if on else 0):
            print(f"Already {args.state}; nothing sent.")
            return
        print(f"Sending POWER {args.state.upper()} (one write, no retry) ...")
        if await client.set_power(on):
            print(f"Done: power state is now {args.state} (0x4D = {1 if on else 0}).")
        else:
            sys.exit("No change seen in 0x4D after 15 s. The write is not retried; check the scooter and run `f2 status`.")

    await _with_client(args, body, allow_power=True)


async def cmd_read(args) -> None:
    target = {"ctrl": Dev.ES_CONTROL, "bms": Dev.ES_BATT, "ble": Dev.ES_BLE}[args.target]

    async def body(client) -> None:
        for i in range(args.count):
            idx = args.index + i
            data = await client.read_raw(target, idx, args.len)
            le = int.from_bytes(data[:2], "little") if len(data) >= 2 else None
            print(f"{target.name} 0x{idx:02X}: {data.hex().upper()}" + (f"  (le16={le} 0x{le:04X})" if le is not None else ""))

    await _with_client(args, body)


# -- argparse -------------------------------------------------------------


def main() -> None:
    p = argparse.ArgumentParser(prog="f2", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("decode", help="decode a btsnoop_hci.log / PacketLogger .pklg offline (no Bluetooth)")
    s.add_argument("capture")
    s.add_argument("--name", help="scooter BLE name (auto-detected from the capture if possible)")
    s.add_argument("--all", action="store_true", help="also show READ polling")
    s.add_argument("--raw", action="store_true", help="show encrypted bytes for every frame")
    s.add_argument("--json", metavar="FILE", help="write all decoded frames as JSON")
    s.add_argument("--app-key", metavar="HEX", help="16-byte app password, if already known")
    s.add_argument("--gatt", action="store_true", help="dump all raw ATT writes/notifications instead")
    s.add_argument("--save-app-key", action="store_true", help="store the official app key for live use")
    s.set_defaults(func=cmd_decode)

    s = sub.add_parser("keys", help="show / set / import / export the stored app key")
    s.add_argument("--set", metavar="HEX", help="32 hex characters")
    s.add_argument("--import", dest="import_file", metavar="FILE", help="read the key from a file (the Android app's export works)")
    s.add_argument("--export", dest="export_file", metavar="FILE", help="write the key to a file (one line, mode 600)")
    s.add_argument("--force", action="store_true", help="overwrite an existing export file")
    s.set_defaults(func=cmd_keys)

    s = sub.add_parser("scan", help="passive scan for scooters")
    s.add_argument("--timeout", type=float, default=10)
    s.add_argument("--all", action="store_true", help="show all BLE devices")
    s.set_defaults(func=cmd_scan)

    s = sub.add_parser("gatt", help="connect and list GATT services (no writes)")
    s.add_argument("--address")
    s.set_defaults(func=cmd_gatt)

    s = sub.add_parser("init", help="connect, send only 0x5B INIT, report whether a password is stored")
    s.add_argument("--address")
    s.add_argument("--name", help="override BLE name used for crypto")
    s.add_argument("--debug", action="store_true")
    s.set_defaults(func=cmd_init)

    s = sub.add_parser("power", help="power the scooter on or off (the app's unlock / lock)")
    s.add_argument("state", choices=["on", "off"])
    s.add_argument("--address")
    s.add_argument("--name", help="override BLE name used for crypto")
    s.add_argument("--debug", action="store_true")
    s.set_defaults(func=cmd_power)

    s = sub.add_parser("pair", help="REPLACE the scooter's password with a new one (needs --force)")
    s.add_argument("--address")
    s.add_argument("--name", help="override BLE name used for crypto")
    s.add_argument("--debug", action="store_true")
    s.add_argument("--force", action="store_true")
    s.set_defaults(func=cmd_pair)

    for name, func, hlp in (("status", cmd_status, "authenticate and read status registers"),
                            ("read", cmd_read, "authenticate and READ raw register(s)")):  # fmt: skip
        s = sub.add_parser(name, help=hlp)
        s.add_argument("--address")
        s.add_argument("--name", help="override BLE name used for crypto")
        s.add_argument("--debug", action="store_true", help="print plaintext + encrypted frames")
        s.set_defaults(func=func)
    s.add_argument("index", type=lambda x: int(x, 0))
    s.add_argument("--target", choices=["ctrl", "bms", "ble"], default="ctrl")
    s.add_argument("--count", type=int, default=1, help="consecutive indices to read")
    s.add_argument("--len", type=int, default=2, help="bytes per read")
    sub.choices["status"].add_argument("--all", action="store_true", help="read every known register")

    args = p.parse_args()
    logging.basicConfig(format="%(levelname)s %(message)s", level=logging.DEBUG if args.verbose else logging.INFO)
    logging.getLogger("bleak").setLevel(logging.WARNING)
    r = args.func(args)
    if asyncio.iscoroutine(r):
        asyncio.run(r)


if __name__ == "__main__":
    main()

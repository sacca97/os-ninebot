"""Offline decoding of an official-app capture (Android btsnoop or iOS PacketLogger).

Pure file processing: nothing here touches Bluetooth.
"""

from __future__ import annotations

import datetime as dt
import json
from dataclasses import asdict, dataclass

from . import hcilog
from .crypto import Session
from .protocol import MAGIC, NUS_NOTIFY_UUID, NUS_WRITE_UUID, Cmd, Dev, Packet, encrypted_frame_len
from .registers import describe_index


@dataclass
class Row:
    ts: float
    conn: int
    direction: str  # "app->scooter" / "scooter->app"
    raw: str
    ok: bool
    key: str | None = None
    counter: int | None = None
    src: int | None = None
    dst: int | None = None
    cmd: int | None = None
    idx: int | None = None
    data: str | None = None
    note: str = ""


@dataclass
class ConnResult:
    conn: int
    name: str | None
    serial: str | None
    ble_data: str | None
    app_data: str | None
    rows: list[Row]


def _frames(events: list[hcilog.AttEvent]):
    """Reassemble 20-byte (or MTU-sized) chunks into complete encrypted frames, per direction."""
    bufs: dict[bool, bytearray] = {True: bytearray(), False: bytearray()}
    first_ts: dict[bool, float] = {}
    for ev in events:
        buf = bufs[ev.from_phone]
        if ev.value[:2] == MAGIC:
            buf.clear()
            first_ts[ev.from_phone] = ev.ts
        elif not buf:
            continue
        buf += ev.value
        if len(buf) >= 3 and len(buf) >= encrypted_frame_len(buf):
            n = encrypted_frame_len(buf)
            yield first_ts[ev.from_phone], ev.from_phone, bytes(buf[:n])
            buf.clear()


def _guess_name(frames: list[tuple[float, bool, bytes]], names: list[str]) -> str | None:
    """The first app frame is 0x5B INIT, encrypted with a key derived only from the name."""
    for _, from_phone, frame in frames:
        if not from_phone:
            continue
        for n in names:
            if Session(n.encode()).decrypt(frame, learn=False):
                return n
    return None


def _annotate(p: Packet) -> str:
    reg = describe_index(p.dst if p.cmd in (Cmd.READ, Cmd.WRITE, Cmd.WRITE_NO_REPLY) else p.src, p.idx)
    if p.cmd == Cmd.INIT and p.src == Dev.ES_BLE:
        return f"ble_data={p.data[:16].hex().upper()} serial={p.data[16:].decode(errors='replace')}"
    if p.cmd == Cmd.PING and p.dst == Dev.ES_BLE:
        return f"app_data={p.data.hex().upper()}"
    if p.cmd == Cmd.PING and p.src == Dev.ES_BLE:
        return "already paired" if p.idx == 1 else "NOT paired (press power button)"
    if p.cmd in (Cmd.WRITE, Cmd.WRITE_NO_REPLY):
        return f"*** WRITE {reg or 'unknown register'} ***"
    if reg:
        return reg
    return ""


def decode(path: str, name: str | None = None, app_key: bytes | None = None) -> tuple[hcilog.Trace, list[ConnResult]]:
    trace = hcilog.parse(path)
    by_conn: dict[int, list[hcilog.AttEvent]] = {}
    for ev in trace.att:
        uuid = trace.handle_uuid.get((ev.conn, ev.handle))
        if uuid is not None and uuid not in (NUS_WRITE_UUID, NUS_NOTIFY_UUID):
            continue
        by_conn.setdefault(ev.conn, []).append(ev)

    candidate_names = [name] if name else list(dict.fromkeys(
        [trace.adv_names[a] for a in trace.ninebot_addrs if a in trace.adv_names] + list(trace.adv_names.values())
    ))  # fmt: skip

    results = []
    for conn, events in by_conn.items():
        frames = list(_frames(events))
        if not frames:
            continue
        conn_name = name or _guess_name(frames, [n for n in [trace.name_for_conn(conn)] if n] + candidate_names)
        session = Session(conn_name.encode()) if conn_name else None
        if session and app_key:
            session.app_data = app_key
        rows = []
        for ts, from_phone, frame in frames:
            row = Row(ts, conn, "app->scooter" if from_phone else "scooter->app", frame.hex().upper(), False)
            d = session.decrypt(frame) if session else None
            if d and d.packet:
                p = d.packet
                row.ok, row.key, row.counter = True, d.key, d.counter
                row.src, row.dst, row.cmd, row.idx, row.data = p.src, p.dst, p.cmd, p.idx, p.data.hex().upper()
                row.note = _annotate(p)
            else:
                row.note = "undecryptable (unknown name/key, or a different protocol)"
            rows.append(row)
        results.append(ConnResult(
            conn, conn_name, session.serial if session else None,
            session.ble_data.hex().upper() if session and session.ble_data else None,
            session.app_data.hex().upper() if session and session.app_data else None,
            rows,
        ))  # fmt: skip
    return trace, results


def format_rows(rows: list[Row], show_raw: bool = False) -> str:
    out = []
    for r in rows:
        t = dt.datetime.fromtimestamp(r.ts).strftime("%H:%M:%S.%f")[:-3]
        arrow = "APP ->" if r.direction == "app->scooter" else "  <- "
        if r.ok:
            p = Packet(r.src, r.dst, r.cmd, r.idx, bytes.fromhex(r.data or ""))
            line = f"{t} {arrow} [{r.key:<4} #{r.counter:<5}] {p}"
            if r.note:
                line += f"   ; {r.note}"
        else:
            line = f"{t} {arrow} [????       ] {r.note}"
        out.append(line)
        if show_raw or not r.ok:
            out.append(f"{'':>13}raw {r.raw}")
    return "\n".join(out)


_ATT_OPS = {0x12: "WRITE_REQ", 0x52: "WRITE_CMD", 0x1B: "NOTIFY", 0x1D: "INDICATE"}


def format_gatt(trace: hcilog.Trace) -> str:
    """Every ATT write/notification, regardless of payload. For unknown / newer protocols."""
    out = []
    for ev in trace.att:
        t = dt.datetime.fromtimestamp(ev.ts).strftime("%H:%M:%S.%f")[:-3]
        uuid = trace.handle_uuid.get((ev.conn, ev.handle), "?")
        arrow = "APP ->" if ev.from_phone else "  <- "
        out.append(f"{t} {arrow} conn=0x{ev.conn:03X} {_ATT_OPS[ev.opcode]:<9} h=0x{ev.handle:04X} "
                   f"{uuid[:8]:<8} len={len(ev.value):<3} {ev.value.hex().upper()}")  # fmt: skip
    return "\n".join(out)


def to_json(results: list[ConnResult]) -> str:
    return json.dumps([asdict(r) for r in results], indent=2)

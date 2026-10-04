"""Minimal HCI log parser: Android btsnoop and Apple PacketLogger (.pklg).

Extracts ATT writes/notifications, GATT characteristic declarations,
advertised device names and LE connection handles. No external deps.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field

BTSNOOP_MAGIC = b"btsnoop\x00"
_EPOCH_DELTA_US = 0x00DCDDB30F2F8000  # 0000-01-01 -> 1970-01-01 in microseconds

ATT_CID = 0x0004
ATT_READ_BY_TYPE_RSP = 0x09
ATT_WRITE_REQ = 0x12
ATT_WRITE_CMD = 0x52
ATT_NOTIFY = 0x1B
ATT_INDICATE = 0x1D


@dataclass
class AttEvent:
    ts: float  # unix seconds
    conn: int
    opcode: int
    handle: int
    value: bytes
    from_phone: bool  # True: phone (host) -> scooter


@dataclass
class Trace:
    att: list[AttEvent] = field(default_factory=list)
    handle_uuid: dict[tuple[int, int], str] = field(default_factory=dict)  # (conn, value handle) -> uuid
    adv_names: dict[str, str] = field(default_factory=dict)  # addr -> name
    ninebot_addrs: set[str] = field(default_factory=set)
    conn_addr: dict[int, str] = field(default_factory=dict)
    truncated_records: int = 0

    def name_for_conn(self, conn: int) -> str | None:
        addr = self.conn_addr.get(conn)
        return self.adv_names.get(addr) if addr else None


def _addr(b: bytes) -> str:
    return ":".join(f"{x:02X}" for x in reversed(b))


def _uuid128(b: bytes) -> str:
    h = b[::-1].hex()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"


def _parse_ad(data: bytes) -> tuple[str | None, bool]:
    name, is_ninebot, i = None, False, 0
    while i + 1 < len(data):
        ln = data[i]
        if ln == 0 or i + 1 + ln > len(data):
            break
        typ, val = data[i + 1], data[i + 2 : i + 1 + ln]
        if typ in (0x08, 0x09):
            name = val.decode(errors="replace")
        elif typ == 0xFF and len(val) >= 2 and struct.unpack("<H", val[:2])[0] == 16974:
            is_ninebot = True
        i += 1 + ln
    return name, is_ninebot


def _record_adv(trace: Trace, addr: str, ad: bytes) -> None:
    name, nb = _parse_ad(ad)
    if name:
        trace.adv_names[addr] = name
    if nb:
        trace.ninebot_addrs.add(addr)


def _handle_event(trace: Trace, ev: bytes) -> None:
    if len(ev) < 3 or ev[0] != 0x3E:
        return
    sub, p = ev[2], ev[3:]
    if sub in (0x01, 0x0A) and len(p) >= 11 and p[0] == 0:  # (enhanced) connection complete
        trace.conn_addr[struct.unpack("<H", p[1:3])[0] & 0x0FFF] = _addr(p[5:11])
    elif sub == 0x02 and len(p) >= 10 and p[0] == 1:  # legacy adv report (single)
        dlen = p[9]
        _record_adv(trace, _addr(p[3:9]), p[10 : 10 + dlen])
    elif sub == 0x0D and len(p) >= 25:  # extended adv report(s)
        i, n = 1, p[0]
        for _ in range(n):
            if i + 24 > len(p):
                break
            addr = _addr(p[i + 3 : i + 9])
            dlen = p[i + 23]
            _record_adv(trace, addr, p[i + 24 : i + 24 + dlen])
            i += 24 + dlen


def _handle_att(trace: Trace, ts: float, conn: int, from_phone: bool, pdu: bytes) -> None:
    if not pdu:
        return
    op = pdu[0]
    if op in (ATT_WRITE_REQ, ATT_WRITE_CMD, ATT_NOTIFY, ATT_INDICATE) and len(pdu) >= 3:
        handle = struct.unpack("<H", pdu[1:3])[0]
        trace.att.append(AttEvent(ts, conn, op, handle, bytes(pdu[3:]), from_phone))
    elif op == ATT_READ_BY_TYPE_RSP and len(pdu) >= 2:
        ln = pdu[1]
        if ln not in (7, 21):  # characteristic declarations with 16- or 128-bit UUID
            return
        for off in range(2, len(pdu) - ln + 1, ln):
            e = pdu[off : off + ln]
            value_handle = struct.unpack("<H", e[3:5])[0]
            u = e[5:]
            uuid = f"0000{struct.unpack('<H', u)[0]:04x}-0000-1000-8000-00805f9b34fb" if len(u) == 2 else _uuid128(u)
            trace.handle_uuid[(conn, value_handle)] = uuid


def _feed(trace: Trace, l2cap_buf: dict, ptype: int, body: bytes, received: bool, ts: float) -> None:
    """Process one HCI packet. ptype uses H4 numbering: 1 cmd, 2 ACL, 4 event."""
    if ptype == 0x04:
        _handle_event(trace, body)
        return
    if ptype != 0x02 or len(body) < 4:
        return
    hf, _ln = struct.unpack("<HH", body[:4])
    conn, pb = hf & 0x0FFF, (hf >> 12) & 0x3
    frag = body[4:]
    key = (conn, not received)
    if pb in (0x0, 0x2):
        l2cap_buf[key] = bytearray(frag)
    elif key in l2cap_buf:
        l2cap_buf[key] += frag
    else:
        return
    buf = l2cap_buf[key]
    if len(buf) < 4:
        return
    l2_len, cid = struct.unpack("<HH", buf[:4])
    if len(buf) < 4 + l2_len:
        return
    del l2cap_buf[key]
    if cid == ATT_CID:
        _handle_att(trace, ts, conn, not received, bytes(buf[4 : 4 + l2_len]))


def parse_btsnoop(raw: bytes) -> Trace:
    _version, datalink = struct.unpack(">II", raw[8:16])
    if datalink not in (1001, 1002):
        raise ValueError(f"unsupported btsnoop datalink {datalink}")

    trace, l2cap_buf = Trace(), {}
    off = 16
    while off + 24 <= len(raw):
        orig_len, incl_len, flags, _drops, ts_us = struct.unpack(">IIIIq", raw[off : off + 24])
        pkt = raw[off + 24 : off + 24 + incl_len]
        off += 24 + incl_len
        if incl_len < orig_len:
            trace.truncated_records += 1
        received = bool(flags & 1)
        ts = (ts_us - _EPOCH_DELTA_US) / 1e6
        if datalink == 1002:
            if not pkt:
                continue
            ptype, body = pkt[0], pkt[1:]
        else:
            ptype = (0x04 if received else 0x01) if flags & 2 else 0x02
            body = pkt
        _feed(trace, l2cap_buf, ptype, body, received, ts)
    return trace


# Apple PacketLogger (.pklg): no file header; records are
#   u32 len | u32 ts_secs | u32 ts_usecs | u8 type | payload[len - 9]
# Byte order is big endian in old captures and little endian in newer (iOS) ones.
_PKLG_HCI = {0x00: (0x01, False), 0x01: (0x04, True), 0x02: (0x02, False), 0x03: (0x02, True)}
_PKLG_KNOWN = set(_PKLG_HCI) | {0x08, 0x09, 0x0A, 0x0B} | set(range(0xF0, 0x100))


def _pklg_plausible(raw: bytes, endian: str, records: int = 50) -> bool:
    off, n = 0, 0
    while off + 13 <= len(raw) and n < records:
        ln = struct.unpack(endian + "I", raw[off : off + 4])[0]
        if ln < 9 or off + 4 + ln > len(raw) or raw[off + 12] not in _PKLG_KNOWN:
            return False
        off += 4 + ln
        n += 1
    return n > 0


def parse_pklg(raw: bytes) -> Trace:
    endian = next((e for e in ("<", ">") if _pklg_plausible(raw, e)), None)
    if endian is None:
        raise ValueError("not a PacketLogger file (or unsupported variant); try exporting it as btsnoop")
    trace, l2cap_buf = Trace(), {}
    off = 0
    while off + 13 <= len(raw):
        ln, secs, usecs = struct.unpack(endian + "III", raw[off : off + 12])
        typ, body = raw[off + 12], raw[off + 13 : off + 4 + ln]
        off += 4 + ln
        if typ in _PKLG_HCI:
            ptype, received = _PKLG_HCI[typ]
            _feed(trace, l2cap_buf, ptype, body, received, secs + usecs / 1e6)
    return trace


def parse(path: str) -> Trace:
    """Parse an Android btsnoop log or an Apple PacketLogger .pklg trace."""
    with open(path, "rb") as f:
        raw = f.read()
    if raw[:8] == BTSNOOP_MAGIC:
        return parse_btsnoop(raw)
    return parse_pklg(raw)

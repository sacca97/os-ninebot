"""Offline tests: no Bluetooth is touched.

A synthetic official-app session is generated with miauth's reference NbCrypto
(both app and scooter side, following ninebot-ble's handshake), wrapped into a
btsnoop file, then decoded with f2probe.
"""

import os
import struct
from pathlib import Path

import pytest
from miauth.nb.nbcrypto import NbCrypto

from f2probe import decode
from f2probe.crypto import Session
from f2probe.protocol import Cmd, Dev, Packet
from f2probe.transport import POWER_OFF, POWER_ON, UnsafeCommand, assert_safe

NAME = b"NBScooter1234"
BLE_DATA = bytes(range(0x10, 0x20))
SERIAL = b"N5GTEST1234567"
APP_KEY = bytes(range(0xA0, 0xB0))
NUS_TX_HANDLE, NUS_RX_HANDLE = 0x000E, 0x0010


def official_session() -> list[tuple[bool, bytes]]:
    """(from_phone, encrypted frame) for a full handshake + read + write, via reference crypto."""
    app, sc = NbCrypto(), NbCrypto()
    app.set_name(NAME)
    sc.set_name(NAME)
    out = []

    def a(p):
        out.append((True, bytes(app.encrypt(p.pack()))))
        sc.decrypt(out[-1][1])

    def s(p, it=None):
        if it is not None:
            sc.it = it
        out.append((False, bytes(sc.encrypt(p.pack()))))
        app.decrypt(out[-1][1])

    a(Packet(Dev.PC, Dev.ES_BLE, Cmd.INIT, 0))
    s(Packet(Dev.ES_BLE, Dev.PC, Cmd.INIT, 0, BLE_DATA + SERIAL))
    app.set_ble_data(BLE_DATA)
    sc.set_ble_data(BLE_DATA)
    a(Packet(Dev.PC, Dev.ES_BLE, Cmd.PING, 0, APP_KEY))
    s(Packet(Dev.ES_BLE, Dev.PC, Cmd.PING, 1), it=4)
    app.set_app_data(APP_KEY)
    sc.set_app_data(APP_KEY)
    a(Packet(Dev.PC, Dev.ES_BLE, Cmd.PAIR, 0, SERIAL))
    s(Packet(Dev.ES_BLE, Dev.PC, Cmd.PAIR, 1))
    a(Packet(Dev.PC, Dev.ES_CONTROL, Cmd.READ, 0x1D, b"\x02"))
    s(Packet(Dev.ES_CONTROL, Dev.PC, Cmd.READ_ACK, 0x1D, b"\x02\x00"))
    a(Packet(Dev.PC, Dev.ES_CONTROL, Cmd.WRITE, 0x70, b"\x01\x00"))
    s(Packet(Dev.ES_CONTROL, Dev.PC, Cmd.WRITE_ACK, 0x70, b"\x00"))
    return out


# -- btsnoop writer ----------------------------------------------------------


def _rec(h4: bytes, received: bool, ts: int) -> bytes:
    flags = (1 if received else 0) | (2 if h4[0] in (1, 4) else 0)
    return struct.pack(">IIIIq", len(h4), len(h4), flags, 0, ts) + h4


def _acl(conn: int, l2cap: bytes, split: int | None = None) -> list[bytes]:
    parts = [l2cap] if split is None else [l2cap[:split], l2cap[split:]]
    pkts = []
    for i, part in enumerate(parts):
        pb = 0x2 if i == 0 else 0x1
        pkts.append(b"\x02" + struct.pack("<HH", conn | (pb << 12), len(part)) + part)
    return pkts


def _att(pdu: bytes) -> bytes:
    return struct.pack("<HH", len(pdu), 4) + pdu


def _uuid_le(u: str) -> bytes:
    return bytes.fromhex(u.replace("-", ""))[::-1]


def hci_packets(frames, conn=0x0040):
    """[(h4 packet, received, ts_us)] for a full capture: adv, connect, GATT decls, NUS traffic."""
    addr = bytes.fromhex("112233445566")
    ad = bytes([len(NAME) + 1, 0x09]) + NAME + bytes([5, 0xFF]) + struct.pack("<H", 16974) + b"\x00\x00"
    adv = bytes([0x04, 0x3E, 0, 0x02, 1, 0x00, 0x00]) + addr + bytes([len(ad)]) + ad + b"\xc0"
    conn_evt = bytes([0x04, 0x3E, 0, 0x01, 0x00]) + struct.pack("<H", conn) + bytes([0, 0]) + addr + bytes(7)
    decls = b""
    for h, u in ((NUS_RX_HANDLE - 1, "6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
                 (NUS_TX_HANDLE - 1, "6e400002-b5a3-f393-e0a9-e50e24dcca9e")):
        decls += struct.pack("<HBH", h, 0x10, h + 1) + _uuid_le(u)
    rbt = _att(bytes([0x09, 21]) + decls)

    pkts = [(adv, True, 1), (conn_evt, True, 2)]
    pkts += [(p, True, 3) for p in _acl(conn, rbt, split=10)]  # fragmented ACL
    ts = 1_700_000_000_000_000
    for from_phone, frame in frames:
        for i in range(0, len(frame), 20):
            chunk = frame[i : i + 20]
            pdu = (bytes([0x52]) + struct.pack("<H", NUS_TX_HANDLE) if from_phone
                   else bytes([0x1B]) + struct.pack("<H", NUS_RX_HANDLE)) + chunk
            ts += 1000
            pkts += [(p, not from_phone, ts) for p in _acl(conn, _att(pdu))]
    return pkts


def write_btsnoop(path, frames):
    recs = [_rec(h4, rx, ts + 0x00DCDDB30F2F8000) for h4, rx, ts in hci_packets(frames)]
    path.write_bytes(b"btsnoop\x00" + struct.pack(">II", 1, 1002) + b"".join(recs))


# -- tests -------------------------------------------------------------------


def test_decode_official_capture(tmp_path):
    f = tmp_path / "btsnoop_hci.log"
    write_btsnoop(f, official_session())
    trace, results = decode.decode(str(f))
    assert trace.truncated_records == 0
    assert len(results) == 1
    r = results[0]
    assert r.name == NAME.decode()  # auto-detected from advertising + INIT validation
    assert all(row.ok for row in r.rows), decode.format_rows(r.rows)
    assert r.app_data == APP_KEY.hex().upper()
    assert r.ble_data == BLE_DATA.hex().upper()
    assert r.serial == SERIAL.decode()
    writes = [x for x in r.rows if x.cmd == Cmd.WRITE]
    assert len(writes) == 1 and writes[0].idx == 0x70 and writes[0].data == "0100"
    assert {x.key for x in r.rows} == {"name", "ble", "app"}


def test_session_tx_is_accepted_by_reference_scooter():
    """f2probe's live encrypt path, checked against a reference NbCrypto 'scooter'."""
    me, sc = Session(NAME), NbCrypto()
    sc.set_name(NAME)

    def tx(p):
        got = Packet.unpack(bytes(sc.decrypt(me.encrypt(p))))
        assert got == p

    def rx(p, it=None):
        if it is not None:
            sc.it = it
        d = me.decrypt(bytes(sc.encrypt(p.pack())))
        assert d is not None and d.packet == p

    tx(Packet(Dev.PC, Dev.ES_BLE, Cmd.INIT, 0))
    rx(Packet(Dev.ES_BLE, Dev.PC, Cmd.INIT, 0, BLE_DATA + SERIAL))
    sc.set_ble_data(BLE_DATA)
    tx(Packet(Dev.PC, Dev.ES_BLE, Cmd.PING, 0, APP_KEY))
    rx(Packet(Dev.ES_BLE, Dev.PC, Cmd.PING, 1), it=9)
    sc.set_app_data(APP_KEY)
    assert me.tx_key == "app"
    tx(Packet(Dev.PC, Dev.ES_BLE, Cmd.PAIR, 0, SERIAL))
    rx(Packet(Dev.ES_BLE, Dev.PC, Cmd.PAIR, 1))
    tx(Packet(Dev.PC, Dev.ES_CONTROL, Cmd.READ, 0x1D, b"\x02"))
    rx(Packet(Dev.ES_CONTROL, Dev.PC, Cmd.READ_ACK, 0x1D, b"\x02\x00"))


def test_guard():
    P = Packet
    ok = [
        P(Dev.PHONE, Dev.ES_BLE, Cmd.INIT, 0),
        P(Dev.PHONE, Dev.ES_BLE, Cmd.PAIR, 0, b"x" * 14),
        P(Dev.PHONE, Dev.ES_CONTROL, Cmd.READ, 0x1D, b"\x02"),
        P(Dev.PHONE, Dev.ES_BATT, Cmd.READ, 0x32, b"\x02"),
    ]
    for p in ok:
        assert_safe(p)
    set_pwd = P(Dev.PHONE, Dev.ES_BLE, Cmd.PING, 0, bytes(16))
    refused = [
        POWER_ON, POWER_OFF, set_pwd,  # gated: need allow_power / allow_pairing
        P(Dev.PHONE, Dev.ES_CONTROL, Cmd.WRITE, 0x79, b"\x01\x00"),
        P(Dev.PHONE, Dev.ES_CONTROL, Cmd.READ, 0x1D, b"\x02\x00"),
        P(Dev.PC, Dev.ES_CONTROL, Cmd.READ, 0x1D, b"\x02"),
    ]
    for p in refused:
        with pytest.raises(UnsafeCommand):
            assert_safe(p)
    assert_safe(POWER_ON, allow_power=True)
    assert_safe(POWER_OFF, allow_power=True)
    assert_safe(set_pwd, allow_pairing=True)
    # even when enabled, only the exact frames pass
    for p, kw in [
        (P(Dev.PHONE, Dev.ES_CONTROL, Cmd.WRITE_NO_REPLY, 0x79, b"\x00\x00"), {"allow_power": True}),
        (P(Dev.PHONE, Dev.ES_BLE, Cmd.WRITE_NO_REPLY, 0x79, b"\x01\x00"), {"allow_power": True}),
        (POWER_OFF, {"allow_pairing": True}),
    ]:
        with pytest.raises(UnsafeCommand):
            assert_safe(p, **kw)


CAPTURE = Path(__file__).resolve().parent.parent / "segway-pairing.pklg"
# The real capture and scooter name are private: set F2_SCOOTER_NAME and drop the capture next to the repo to run these.
SCOOTER_NAME = os.environ.get("F2_SCOOTER_NAME", "")
HAVE_CAPTURE = CAPTURE.exists() and bool(SCOOTER_NAME)


@pytest.mark.skipif(not HAVE_CAPTURE, reason="real capture / F2_SCOOTER_NAME not provided")
def test_replay_official_app_frames_byte_for_byte():
    """Our encrypt path must reproduce every frame the official app sent, byte for byte,
    including the reconnect login (0x5D at counter 2) we will send from the Mac."""
    _, first = decode.decode(str(CAPTURE), SCOOTER_NAME)
    key = bytes.fromhex(next(r.app_data for r in first if r.app_data))  # credential set during pairing
    _, results = decode.decode(str(CAPTURE), SCOOTER_NAME, key)
    conn = max(results, key=lambda r: len(r.rows))
    assert all(r.ok for r in conn.rows)

    s = Session(SCOOTER_NAME.encode())
    s.decrypt(bytes.fromhex(conn.rows[1].raw))  # INIT reply teaches ble_data
    s.app_data, s.tx_key = key, "app"

    # the login exactly as authenticate() builds it
    s.counter = 1
    login = Packet(Dev.PHONE, Dev.ES_BLE, Cmd.PAIR, 0, conn.serial.encode())
    assert s.encrypt(login).hex().upper() == conn.rows[2].raw

    app_rows = [r for r in conn.rows if r.direction == "app->scooter" and r.counter]
    assert len(app_rows) > 100
    for r in app_rows:
        pkt = Packet(r.src, r.dst, r.cmd, r.idx, bytes.fromhex(r.data))
        s.counter = r.counter - 1
        assert s.encrypt(pkt).hex().upper() == r.raw, f"mismatch at counter {r.counter}: {pkt}"


@pytest.mark.skipif(not HAVE_CAPTURE, reason="real capture / F2_SCOOTER_NAME not provided")
def test_power_frames_match_official_app_capture():
    _, first = decode.decode(str(CAPTURE), SCOOTER_NAME)
    key = bytes.fromhex(next(r.app_data for r in first if r.app_data))
    _, results = decode.decode(str(CAPTURE), SCOOTER_NAME, key)
    conn = max(results, key=lambda r: len(r.rows))
    writes = [Packet(r.src, r.dst, r.cmd, r.idx, bytes.fromhex(r.data)) for r in conn.rows
              if r.cmd == Cmd.WRITE_NO_REPLY]
    assert writes == [POWER_OFF, POWER_ON, POWER_OFF]  # exactly what the official app sent, in order

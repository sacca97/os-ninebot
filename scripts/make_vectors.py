"""Generate SYNTHETIC crypto test vectors for a port of the Ninebot session crypto (e.g. the Android app).

Frames are produced with miauth's reference NbCrypto, then each one is round-tripped through f2probe's Session
(already verified byte-for-byte against a real official-app capture). No real credentials are used here.

    python scripts/make_vectors.py > android/test-vectors/crypto_vectors.json
"""

import json
import sys

from miauth.nb.nbcrypto import NbCrypto

from f2probe.crypto import Session
from f2probe.protocol import Cmd, Dev, Packet

NAME = b"NBTEST0000001A"  # 14 bytes, like a real scooter name/serial
SERIAL = NAME
BLE_DATA = bytes.fromhex("00112233445566778899AABBCCDDEEFF")
APP_KEY = bytes.fromhex("F0E1D2C3B4A5968778695A4B3C2D1E0F")


def keys():
    return {
        "name": NbCrypto.calc_sha1_key(NAME, NbCrypto.FW_DATA),
        "ble": NbCrypto.calc_sha1_key(NAME, BLE_DATA),
        "app": NbCrypto.calc_sha1_key(APP_KEY, BLE_DATA),
    }


def seal(plain: bytes, key: str, counter: int) -> bytes:
    nb = NbCrypto()
    nb.sha1_key = keys()[key]
    if counter == 0:  # "non-SN" mode: static keystream + 2-byte checksum (any key)
        nb.ble_data, nb.it = None, 0
    else:  # AES-CTR + CBC-MAC. NbCrypto picks the mode BEFORE incrementing, so the first AES frame is counter 2.
        assert counter >= 2 and key != "name"
        nb.ble_data, nb.it = BLE_DATA, counter - 1
    out = bytes(nb.encrypt(plain))
    return out


def main() -> None:
    P, B, C, T = Dev.PHONE, Dev.ES_BLE, Dev.ES_CONTROL, Dev.ES_BATT
    cases = [
        ("init_request", "name", 0, Packet(P, B, Cmd.INIT, 0)),
        ("init_reply_password_stored", "name", 0, Packet(B, P, Cmd.INIT, 1, BLE_DATA + SERIAL)),
        ("init_reply_no_password", "name", 0, Packet(B, P, Cmd.INIT, 0, BLE_DATA + SERIAL)),
        ("set_pwd_request_counter0", "ble", 0, Packet(P, B, Cmd.PING, 0, APP_KEY)),  # ownbee-style, weak mode
        ("set_pwd_request_aes", "ble", 5, Packet(P, B, Cmd.PING, 0, APP_KEY)),  # as the official app (seen at #5)
        ("set_pwd_reply_pending", "ble", 6, Packet(B, P, Cmd.PING, 0)),
        ("set_pwd_reply_accepted", "ble", 7, Packet(B, P, Cmd.PING, 1)),
        ("auth_request", "app", 2, Packet(P, B, Cmd.PAIR, 0, SERIAL)),
        ("auth_reply_ok", "app", 3, Packet(B, P, Cmd.PAIR, 1)),
        ("read_ctrl_status_word", "app", 4, Packet(P, C, Cmd.READ, 0x1D, b"\x02")),
        ("read_ctrl_status_word_ack", "app", 5, Packet(C, P, Cmd.READ_ACK, 0x1D, b"\x00\x08")),
        ("read_ble_power_state", "app", 6, Packet(P, B, Cmd.READ, 0x4D, b"\x02")),
        ("read_ble_power_state_ack_on", "app", 7, Packet(B, P, Cmd.READ_ACK, 0x4D, b"\x01\x00")),
        ("read_ble_power_state_ack_off", "app", 7, Packet(B, P, Cmd.READ_ACK, 0x4D, b"\x00\x00")),
        ("read_batt_percent", "app", 8, Packet(P, T, Cmd.READ, 0x32, b"\x02")),
        ("read_batt_percent_ack_66", "app", 9, Packet(T, P, Cmd.READ_ACK, 0x32, b"\x42\x00")),
        ("power_off_write", "app", 10, Packet(P, C, Cmd.WRITE_NO_REPLY, 0x79, b"\x01\x00")),
        ("power_on_write", "app", 12, Packet(P, B, Cmd.WRITE_NO_REPLY, 0x1E, b"\x01\x00")),
        ("power_state_notification_on", "app", 13, Packet(B, P, 0x21, 0x00, b"\x02\x4D\x01\x00")),
        ("long_payload_3_blocks", "app", 300, Packet(B, P, Cmd.READ_ACK, 0x10, bytes(range(40)))),
        ("counter_high_16bit_values", "app", 0xFFFE, Packet(P, C, Cmd.READ, 0x10, b"\x02")),
    ]
    out = []
    for cid, key, counter, pkt in cases:
        plain = pkt.pack()
        frame = seal(plain, key, counter)
        # round trip with the (real-capture-verified) f2probe implementation
        s = Session(NAME)
        s.ble_data, s.app_data = BLE_DATA, APP_KEY
        d = s.decrypt(frame, learn=False)
        wire_counter = counter  # must stay < 65536: only 16 bits go on the wire and the receiver rebuilds the nonce from them
        assert d is not None and d.plain == plain and d.key == key, cid
        assert d.counter == wire_counter, (cid, d.counter, wire_counter)
        out.append({
            "id": cid, "key": key, "counter": counter, "wire_counter": wire_counter,
            "plaintext": plain.hex().upper(), "frame": frame.hex().upper(),
        })  # fmt: skip
    json.dump({
        "note": "SYNTHETIC test data. counter = value used in the nonce (32-bit); only the low 16 bits go on the wire. "
                "key: name=sha1(pad16(name)||FW_DATA)[:16] non-SN mode; ble=sha1(pad16(name)||ble_data)[:16]; "
                "app=sha1(app_key||ble_data)[:16]; the latter two use AES-CTR + CBC-MAC.",
        "name": NAME.decode(), "ble_data": BLE_DATA.hex().upper(), "app_key": APP_KEY.hex().upper(),
        "fw_data": NbCrypto.FW_DATA.hex().upper(),
        "derived_keys": {k: v.hex().upper() for k, v in keys().items()},
        "vectors": out,
    }, sys.stdout, indent=2)  # fmt: skip
    print()


if __name__ == "__main__":
    main()

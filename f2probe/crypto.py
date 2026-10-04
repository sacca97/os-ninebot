"""Ninebot "encryption 1" session with MAC-verified decryption.

Built on miauth's NbCrypto primitives (port of scooterhacking/NinebotCrypto).

Every key in this scheme is derived from values that cross the air:

  name key = sha1(device_name || FW_DATA)[:16]   used for 0x5B INIT
  ble key  = sha1(device_name || ble_data)[:16]  ble_data = first 16 bytes of the 0x5B reply
  app key  = sha1(app_data   || ble_data)[:16]   app_data = 16 bytes sent in the 0x5C request

Rather than hard-coding when each key becomes active (which upstream gets
subtly wrong on some paths), decrypt() tries every known key in both modes
and only accepts a result whose CRC/MAC verifies. That works the same for
live traffic and for offline btsnoop captures.
"""

from __future__ import annotations

from dataclasses import dataclass

from miauth.nb.nbcrypto import NbCrypto
from miauth.util import crc16

from .protocol import Cmd, Dev, Packet


@dataclass
class Decrypted:
    plain: bytes
    packet: Packet | None
    key: str  # "name", "ble" or "app"
    counter: int


def _aes_data(counter: int, ble_data: bytes) -> bytearray:
    a = bytearray(16)
    a[0] = 1
    a[1:5] = counter.to_bytes(4, "big")
    a[5:13] = ble_data[:8]
    return a


class Session:
    def __init__(self, name: bytes) -> None:
        self.name = name
        self.ble_data: bytes | None = None
        self.app_data: bytes | None = None
        self.counter = 0
        self.tx_key = "name"
        self._serial: bytes | None = None

    # -- keys -------------------------------------------------------------

    def _key(self, label: str) -> bytes | None:
        if label == "name":
            return NbCrypto.calc_sha1_key(self.name, NbCrypto.FW_DATA)
        if label == "ble" and self.ble_data is not None:
            return NbCrypto.calc_sha1_key(self.name, self.ble_data)
        if label == "app" and self.ble_data is not None and self.app_data is not None:
            return NbCrypto.calc_sha1_key(self.app_data, self.ble_data)
        return None

    def _candidates(self) -> list[tuple[str, bytes]]:
        order = [self.tx_key] + [k for k in ("app", "ble", "name") if k != self.tx_key]
        out = []
        for label in order:
            key = self._key(label)
            if key is not None:
                out.append((label, key))
        return out

    # -- decrypt ----------------------------------------------------------

    def _try(self, frame: bytes, key: bytes, aes: bool) -> bytes | None:
        pl_len = len(frame) - 9
        enc = bytes(frame[3 : 3 + pl_len])
        counter = (frame[-2] << 8) | frame[-1]
        if not aes:
            dec = NbCrypto.crypto_next(enc, key)
            if bytes(frame[pl_len + 5 : pl_len + 7]) != bytes(crc16(dec)):
                return None
        else:
            if self.ble_data is None:
                return None
            dec = NbCrypto.crypto_next(enc, key, _aes_data(counter, self.ble_data))
            mac_seed = _aes_data(counter, self.ble_data)
            mac_seed[0] = 0x59
            mac_seed[15] = pl_len
            mac = NbCrypto.crc_next(bytes(frame[:3]) + bytes(dec), key, mac_seed)
            if bytes(frame[pl_len + 3 : pl_len + 7]) != bytes(mac):
                return None
        return bytes(frame[:3]) + bytes(dec)

    def decrypt(self, frame: bytes, learn: bool = True) -> Decrypted | None:
        """Decrypt one complete on-air frame. Returns None if no known key verifies."""
        if len(frame) < 10:
            return None
        counter = (frame[-2] << 8) | frame[-1]
        for label, key in self._candidates():
            # counter == 0 frames use the non-AES mode; others normally use AES,
            # but try both since the MAC tells us which one is right.
            for aes in (counter != 0, counter == 0):
                plain = self._try(frame, key, aes)
                if plain is not None:
                    pkt = Packet.unpack(plain)
                    if learn:
                        self.counter = counter
                        if pkt is not None:
                            self.observe(pkt)
                    return Decrypted(plain, pkt, label, counter)
        return None

    def observe(self, pkt: Packet) -> None:
        """Learn key material from handshake packets (either direction)."""
        if pkt.cmd == Cmd.INIT and pkt.src == Dev.ES_BLE and len(pkt.data) >= 16:
            self.ble_data = pkt.data[:16]
            self._serial = pkt.data[16:]
            self.tx_key = "ble"
        elif pkt.cmd == Cmd.PING and pkt.dst == Dev.ES_BLE and len(pkt.data) == 16:
            self.app_data = pkt.data
        elif pkt.cmd == Cmd.PING and pkt.src == Dev.ES_BLE and pkt.idx == 1 and self.app_data:
            self.tx_key = "app"

    @property
    def serial(self) -> str | None:
        return self._serial.decode(errors="replace") if self._serial else None

    # -- encrypt ----------------------------------------------------------

    def encrypt(self, pkt: Packet) -> bytes:
        nb = NbCrypto()
        nb.sha1_key = self._key(self.tx_key)
        nb.ble_data = self.ble_data
        nb.it = self.counter
        out = bytes(nb.encrypt(pkt.pack()))
        self.counter = nb.it
        self.observe(pkt)
        return out

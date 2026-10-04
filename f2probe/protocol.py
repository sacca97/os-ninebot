"""Ninebot BLE serial protocol: plaintext frame layout and constants.

Plaintext frame:  5A A5 | len | src | dst | cmd | idx | data[len]
Encrypted frame:  5A A5 | len | enc(src..data) | mac/crc (4) | counter (2, big endian)

So an encrypted frame is always ``len + 13`` bytes on the air.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field

MAGIC = b"\x5a\xa5"

NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_WRITE_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # phone -> scooter
NUS_NOTIFY_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # scooter -> phone

NINEBOT_MANUFACTURER_ID = 16974  # 0x424E, "NB"


class Cmd(enum.IntEnum):
    READ = 0x01
    WRITE = 0x02
    WRITE_NO_REPLY = 0x03
    READ_ACK = 0x04
    WRITE_ACK = 0x05
    INIT = 0x5B
    PING = 0x5C
    PAIR = 0x5D


class Dev(enum.IntEnum):
    ES_CONTROL = 0x20
    ES_BLE = 0x21
    ES_BATT = 0x22
    PC = 0x3D
    PHONE = 0x3E


def _name(enum_cls: type[enum.IntEnum], value: int) -> str:
    try:
        return enum_cls(value).name
    except ValueError:
        return f"0x{value:02X}"


def encrypted_frame_len(header: bytes) -> int:
    """Total on-air length of an encrypted frame, given at least its first 3 bytes."""
    return header[2] + 13


@dataclass
class Packet:
    src: int
    dst: int
    cmd: int
    idx: int
    data: bytes = field(default=b"")

    def pack(self) -> bytes:
        return MAGIC + bytes([len(self.data), self.src, self.dst, self.cmd, self.idx]) + bytes(self.data)

    @staticmethod
    def unpack(frame: bytes) -> Packet | None:
        if len(frame) < 7 or frame[:2] != MAGIC or len(frame) != 7 + frame[2]:
            return None
        return Packet(frame[3], frame[4], frame[5], frame[6], bytes(frame[7:]))

    def __str__(self) -> str:
        s = f"{_name(Dev, self.src)} -> {_name(Dev, self.dst)} {_name(Cmd, self.cmd)} idx=0x{self.idx:02X}"
        if self.data:
            s += f" data={self.data.hex().upper()}"
        return s

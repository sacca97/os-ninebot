"""Controller (0x20) and BMS (0x22) register map.

Ported from ownbee/ninebot-ble (MIT), ninebot_ble/register.py at commit 1850351f5bce9627f612fd1141489ed7a575be61
(https://github.com/ownbee/ninebot-ble). Copyright (c) 2014 Alexander Ernfridsson (upstream notice).
These describe READ registers only; nothing here is ever written.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Any, Callable

from .protocol import Dev


def _le16(d: bytes) -> int:
    return d[0] | (d[1] << 8)


def _les16(d: bytes) -> int:
    return struct.unpack("<h", d[:2])[0]


def _u32(d: bytes) -> int:
    return _le16(d[:2]) | (_le16(d[2:4]) << 16)


def _ver(d: bytes) -> str:
    v = _le16(d)
    return f"{v >> 8}.{(v >> 4) & 0xF}.{v & 0xF}"


def _str(d: bytes) -> str:
    return d.decode(errors="replace").rstrip("\x00")


def _bit(pos: int) -> Callable[[bytes], bool]:
    return lambda d: bool(_le16(d) & (1 << pos))


def _cells(d: bytes) -> str:
    """Per-cell voltages in mV, one u16 per cell (BMS 0x40..0x49, 10S pack), plus their spread."""
    mv = [_le16(d[i : i + 2]) for i in range(0, len(d) - 1, 2)]
    return f"{mv} mV (spread {max(mv) - min(mv)} mV)"


def _cell_temps(d: bytes) -> str:
    """Two sensors, one byte each, stored as degrees C + 20."""
    return ", ".join(f"{b - 20} °C" for b in d[:2])


_MODES = {0: "NORMAL", 1: "ECO", 2: "SPORT"}
_KERS = {0: "weak", 1: "medium", 2: "strong"}


@dataclass(frozen=True)
class Reg:
    key: str
    label: str
    target: int
    index: int
    count: int  # number of consecutive 2-byte indices
    decode: Callable[[bytes], Any]
    unit: str = ""


C, B = Dev.ES_CONTROL, Dev.ES_BATT

REGISTERS: list[Reg] = [
    Reg("serial", "Serial", C, 0x10, 7, _str),
    Reg("bt_password", "BT pairing code", C, 0x17, 3, _str),
    Reg("fw_ctrl", "Controller FW", C, 0x1A, 1, _ver),
    Reg("error", "Error code", C, 0x1B, 1, _le16),
    Reg("alarm", "Alarm code", C, 0x1C, 1, _le16),
    Reg("status_word", "Status word (0x1D)", C, 0x1D, 1, lambda d: f"0x{_le16(d):04X}"),
    # BLE-board register 0x4D is the POWER state (1 = on, 0 = off), readable while the scooter is off because
    # the BLE board stays alive. Seen live: "unlocked" in the app -> 1, "locked" -> 0, and the controller/battery
    # boards stop answering when it is 0. Community docs (ha-ninebot issue #13) describe the same register.
    Reg("power_state", "Power state (0x4D)", Dev.ES_BLE, 0x4D, 1, lambda d: f"{'on' if _le16(d) else 'off'} (raw {_le16(d)})"),
    Reg("lock_0x1d_bit1", "Lock bit (0x1D bit 1, legacy)", C, 0x1D, 1, _bit(1)),
    Reg("speed_limited", "Speed limited", C, 0x1D, 1, _bit(0)),
    Reg("activated", "Activated", C, 0x1D, 1, _bit(11)),
    Reg("charging", "Charging (0x1D bit 8)", C, 0x1D, 1, _bit(8)),
    Reg("range_actual", "Range (actual)", C, 0x24, 1, lambda d: _le16(d) / 100, "km"),
    Reg("range_predicted", "Range (predicted)", C, 0x25, 1, lambda d: _le16(d) / 100, "km"),
    Reg("mileage", "Mileage", C, 0x29, 2, lambda d: round(_u32(d) / 1000, 1), "km"),
    Reg("body_temp", "Body temperature", C, 0x3E, 1, lambda d: _les16(d) / 10, "°C"),
    Reg("ctrl_voltage", "Controller voltage", C, 0x47, 1, lambda d: _le16(d) / 100, "V"),
    # Live-speed meaning confirmed by owner; units and sign still need verification.
    Reg("speed", "Live speed (raw)", C, 0x26, 1, _le16),
    Reg("fw_ble", "BLE FW", C, 0x68, 1, _ver),
    Reg("mode", "Mode", C, 0x75, 1, lambda d: _MODES.get(_le16(d), f"0x{_le16(d):04X}")),
    Reg("walk_mode", "Walk mode (5 km/h)", C, 0x77, 1, lambda d: bool(_le16(d))),
    Reg("kers", "KERS level", C, 0x7B, 1, lambda d: _KERS.get(_le16(d), f"unknown ({_le16(d)})")),
    Reg("cruise", "Cruise", C, 0x7C, 1, lambda d: bool(_le16(d))),
    Reg("tcs", "Traction control (TCS)", C, 0xF3, 1, lambda d: bool(_le16(d))),
    Reg("tail_light", "Tail light", C, 0x7D, 1, _le16),
    Reg("bms_fw", "BMS FW", B, 0x17, 1, lambda d: f"0x{_le16(d):04X}"),
    Reg("battery", "Battery", B, 0x32, 1, _le16, "%"),
    Reg("battery_current", "Battery current", B, 0x33, 1, lambda d: _les16(d) / 100, "A"),
    Reg("battery_voltage", "Battery voltage", B, 0x34, 1, lambda d: _le16(d) / 100, "V"),
    Reg("cell_voltages", "Cell voltages", B, 0x40, 10, _cells),
    Reg("cell_temps", "Cell temperatures", B, 0x35, 1, _cell_temps),
    Reg("battery_health", "Battery health", B, 0x3B, 1, _le16, "%"),
]

BY_KEY = {r.key: r for r in REGISTERS}

STATUS_KEYS = [
    "power_state", "serial", "fw_ctrl", "fw_ble", "bms_fw", "battery", "battery_voltage", "range_actual",
    "speed", "mileage", "mode", "status_word", "error", "alarm",
]  # fmt: skip


def describe_index(target: int, index: int) -> str | None:
    """Best-effort register name for a (target, index) pair, for annotating traces."""
    for r in REGISTERS:
        if r.target == target and r.index <= index < r.index + r.count and r.key not in ("lock_0x1d_bit1", "speed_limited", "activated", "charging"):
            return r.label
    return None

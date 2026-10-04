"""Live BLE transport (bleak). READ-ONLY unless pairing is explicitly enabled.

The only frames this module will put on the air are:
  * 0x5B INIT and 0x5D PAIR/AUTH to the BLE board (login with the stored credential)
  * 0x01 READ to the controller / BMS / BLE board with a 1-byte length payload
  * 0x5C PING (= SET_PWD, which REPLACES the scooter's stored password) ONLY when the
    Client was created with allow_pairing=True, which only `f2 pair --force` does.
  * the two captured power frames, POWER_ON / POWER_OFF, ONLY when the Client was created
    with allow_power=True, which only `f2 power on|off` does.

Anything else raises UnsafeCommand before encryption. There is deliberately no code path
for any other WRITE (0x02/0x03) or for arbitrary raw frames.

Frames are sent as PHONE (0x3E), like the official app (the ownbee library uses PC 0x3D).
"""

from __future__ import annotations

import asyncio
import logging
import time

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

from .crypto import Session
from .protocol import MAGIC, NINEBOT_MANUFACTURER_ID, NUS_NOTIFY_UUID, NUS_WRITE_UUID, Cmd, Dev, Packet, encrypted_frame_len
from .registers import Reg

log = logging.getLogger("f2probe")


class UnsafeCommand(RuntimeError):
    pass


class AuthError(RuntimeError):
    pass


# The exact frames the official app sends to power the scooter on/off (segway-pairing.pklg;
# also documented for other models in ha-ninebot issue #13). Write-no-reply, value 1.
POWER_ON = Packet(Dev.PHONE, Dev.ES_BLE, Cmd.WRITE_NO_REPLY, 0x1E, b"\x01\x00")
POWER_OFF = Packet(Dev.PHONE, Dev.ES_CONTROL, Cmd.WRITE_NO_REPLY, 0x79, b"\x01\x00")
POWER_STATE_REG = 0x4D  # BLE board, u16: 1 = on, 0 = off


def assert_safe(p: Packet, allow_pairing: bool = False, allow_power: bool = False) -> None:
    if p.src != Dev.PHONE:
        raise UnsafeCommand(f"refusing non-PHONE source: {p}")
    if p in (POWER_ON, POWER_OFF):
        if allow_power:
            return
        raise UnsafeCommand(f"power control not enabled: {p}")
    if p.dst == Dev.ES_BLE and p.cmd in (Cmd.INIT, Cmd.PAIR):
        return
    if p.dst == Dev.ES_BLE and p.cmd == Cmd.PING:
        if allow_pairing:
            return
        raise UnsafeCommand(f"PING/SET_PWD replaces the scooter's password; pairing not enabled: {p}")
    if p.cmd == Cmd.READ and p.dst in (Dev.ES_CONTROL, Dev.ES_BATT, Dev.ES_BLE) and len(p.data) == 1:
        return
    raise UnsafeCommand(f"refusing to send non-read frame: {p}")


async def scan(timeout: float = 10.0, only_ninebot: bool = True) -> list[tuple[BLEDevice, AdvertisementData]]:
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    out = []
    for dev, adv in found.values():
        if not only_ninebot or NINEBOT_MANUFACTURER_ID in adv.manufacturer_data:
            out.append((dev, adv))
    return sorted(out, key=lambda x: -(x[1].rssi or -999))


async def find(address: str | None, timeout: float = 15.0) -> tuple[BLEDevice, AdvertisementData]:
    """Scan until the first matching scooter is seen, then stop (usually 1-2 s; `timeout` is only the limit).

    With several scooters in range the first one heard wins; pass --address to pick one."""
    seen: dict[str, AdvertisementData] = {}

    def match(dev: BLEDevice, adv: AdvertisementData) -> bool:
        ok = dev.address.lower() == address.lower() if address else NINEBOT_MANUFACTURER_ID in adv.manufacturer_data
        if ok:
            seen[dev.address] = adv
        return ok

    dev = await BleakScanner.find_device_by_filter(match, timeout=timeout)
    if dev is None:
        raise RuntimeError("no Ninebot scooter found (is it on, and is the official app disconnected?)")
    return dev, seen[dev.address]


def _reply(cmd):
    return lambda r: r.src == Dev.ES_BLE and r.cmd == cmd


class Client:
    def __init__(self, device: BLEDevice, name: str, app_key: bytes | None = None,
                 debug: bool = False, allow_pairing: bool = False, allow_power: bool = False) -> None:  # fmt: skip
        self.device = device
        self.session = Session(name.encode())
        self.app_key = app_key
        self.debug = debug
        self.allow_pairing = allow_pairing
        self.allow_power = allow_power
        self.client: BleakClient | None = None
        self.init_reply: Packet | None = None
        self._rx = bytearray()
        self._queue: asyncio.Queue[Packet] = asyncio.Queue(100)

    # -- low level --------------------------------------------------------

    def _on_notify(self, _char, data: bytearray) -> None:
        if data[:2] == MAGIC:
            self._rx = bytearray()
        elif not self._rx:
            log.debug("rx stray chunk %s", bytes(data).hex().upper())
            return
        self._rx += data
        if len(self._rx) < 3 or len(self._rx) < encrypted_frame_len(self._rx):
            return
        frame = bytes(self._rx[: encrypted_frame_len(self._rx)])
        self._rx = bytearray()
        d = self.session.decrypt(frame)
        if self.debug:
            print(f"  <- enc {frame.hex().upper()}")
        if d is None or d.packet is None:
            log.warning("rx frame failed MAC check with all known keys: %s", frame.hex().upper())
            return
        if self.debug:
            print(f"  <- [{d.key} #{d.counter}] {d.packet}")
        self._queue.put_nowait(d.packet)

    async def send(self, p: Packet) -> None:
        assert self.client is not None
        assert_safe(p, self.allow_pairing, self.allow_power)
        enc = self.session.encrypt(p)
        if self.debug:
            print(f"  -> [{self.session.tx_key} #{self.session.counter}] {p}")
            print(f"  -> enc {enc.hex().upper()}")
        for i in range(0, len(enc), 20):
            await self.client.write_gatt_char(NUS_WRITE_UUID, enc[i : i + 20])

    async def receive(self, timeout: float) -> Packet:
        return await asyncio.wait_for(self._queue.get(), timeout)

    async def request(self, p: Packet, match, timeout: float = 3.0, retries: int = 2) -> Packet:
        for attempt in range(retries + 1):
            await self.send(p)
            deadline = time.monotonic() + timeout
            while (left := deadline - time.monotonic()) > 0:
                try:
                    r = await self.receive(left)
                except asyncio.TimeoutError:
                    break
                if match(r):
                    return r
            log.debug("no reply to %s (attempt %d)", p, attempt + 1)
        raise TimeoutError(f"no reply to {p}")

    # -- session ----------------------------------------------------------

    async def open(self) -> Packet:
        """Connect and send 0x5B INIT. No credential is involved; this is what the app sends first.

        The reply's index tells whether the scooter holds a stored password (1) or not (0).
        """
        self.client = BleakClient(self.device)
        await self.client.connect()
        await self.client.start_notify(NUS_NOTIFY_UUID, self._on_notify)
        self.init_reply = await self.request(Packet(Dev.PHONE, Dev.ES_BLE, Cmd.INIT, 0), _reply(Cmd.INIT))
        return self.init_reply

    async def authenticate(self, reset_counter: bool = True) -> None:
        """Log in with the stored credential, exactly like the official app on reconnect:
        INIT, then 0x5D with the 14-byte serial at counter 2. One attempt only, never retried."""
        if self.app_key is None or self.init_reply is None:
            raise AuthError("need open() and a stored app key")
        self.session.app_data = self.app_key
        self.session.tx_key = "app"
        if reset_counter:
            self.session.counter = 1  # the official app's first authenticated frame is counter 2
        try:
            r = await self.request(
                Packet(Dev.PHONE, Dev.ES_BLE, Cmd.PAIR, 0, self.init_reply.data[16:]),
                _reply(Cmd.PAIR), retries=0,
            )  # fmt: skip
        except TimeoutError:
            raise AuthError("scooter did not answer the login: the stored key is probably not (or no longer) valid") from None
        if r.idx != 1:
            raise AuthError("scooter rejected the login")
        log.info("authenticated")

    async def set_password(self, wait: float = 60.0) -> None:
        """0x5C SET_PWD with self.app_key, repeated every 2 s until the scooter confirms (power button).

        Only usable when allow_pairing=True. This REPLACES the scooter's stored password."""
        if self.app_key is None or self.init_reply is None:
            raise AuthError("need open() and a new app key")
        deadline = time.monotonic() + wait
        while time.monotonic() < deadline:
            r = await self.request(Packet(Dev.PHONE, Dev.ES_BLE, Cmd.PING, 0, self.app_key), _reply(Cmd.PING), retries=0)
            if r.idx == 1:
                return
            print("Waiting: press the scooter's power button to confirm pairing ...")
            await asyncio.sleep(2.0)
        raise AuthError("pairing was not confirmed in time")

    async def disconnect(self) -> None:
        """Best-effort cleanup; the scooter may already have dropped the link (it does so after INIT alone)."""
        if not self.client:
            return
        try:
            if self.client.is_connected:
                await self.client.stop_notify(NUS_NOTIFY_UUID)
        except Exception as e:
            log.debug("stop_notify failed (link already closed?): %s", e)
        try:
            await self.client.disconnect()
        except Exception as e:
            log.debug("disconnect failed: %s", e)

    async def read_raw(self, target: int, index: int, length: int = 2) -> bytes:
        def match(r: Packet) -> bool:
            return r.src == target and r.cmd == Cmd.READ_ACK and r.idx == index

        r = await self.request(Packet(Dev.PHONE, target, Cmd.READ, index, bytes([length])), match)
        return r.data

    async def read(self, reg: Reg):
        data = b"".join([await self.read_raw(reg.target, reg.index + i) for i in range(reg.count)])
        return reg.decode(data)

    async def power_state(self) -> int:
        """BLE-board register 0x4D: 1 = on, 0 = off. Answers even while the scooter is off."""
        data = await self.read_raw(Dev.ES_BLE, POWER_STATE_REG)
        return data[0] | (data[1] << 8)

    async def set_power(self, on: bool, timeout: float = 15.0) -> bool:
        """Send ONE power frame (never retried), then poll 0x4D until it shows the new state."""
        if not self.allow_power:
            raise UnsafeCommand("power control not enabled")
        want = 1 if on else 0
        await self.send(POWER_ON if on else POWER_OFF)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            await asyncio.sleep(1.0)
            try:
                if await self.power_state() == want:
                    return True
            except TimeoutError:
                continue
        return False

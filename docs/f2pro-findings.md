# F2 Pro findings (from segway-pairing.pklg, iPhone official app)

Scooter name / serial: `<scooter name>`. Encryption is the standard "encryption 1/2" AES-CTR+CBC-MAC
scheme already implemented in `f2probe/crypto.py`; all 337 frames of the main session verify.

## Handshake observed
- Reconnect with a stored password: `INIT(0x5B)` (name key) -> `PAIR/AUTH(0x5D)` with the 14-byte serial,
  counter starts at 2 (app key). Reply `PAIR idx=1` = success. No `PING(0x5C)` / `SET_PWD`.
- Fresh pairing: stale-password AUTH frames get no reply; app then sends `PING(0x5C)` with the new 16-byte
  password; scooter answers `idx=0` ("pending") until the power button is pressed, then the app reconnects
  and authenticates with the new password.
- The app identifies itself as `PHONE (0x3E)`, not `PC (0x3D)`.

## What the app calls "lock / unlock" is almost certainly POWER off / on
BLE-board register `0x4D` is the power state (1 = on, 0 = off). It stays readable while the scooter is off
because the BLE board stays alive, and while off the controller (0x20) and battery (0x22) boards do not answer
reads. Confirmed live: "unlocked" in the app -> `0x4D`=1 and all reads work; "locked" -> `0x4D`=0.

The community docs describe exactly these frames for power control (Max G2, write-no-reply 0x03):
- power ON : board 0x21 (BLE), register `0x1E`, value 1  (3-4 s)   <- our capture: "unlock"
- power OFF: board 0x20 (controller), register `0x79`, value 1 (3-4 s) <- our capture: "lock"
- state    : board 0x21, register `0x4D` (u16, 1 = on)
Same notes: that scooter has no Bluetooth-based lock; writes to 0x1D bit 1 / 0x70 were ignored.
Sources: https://github.com/BobMcGlobus/ha-ninebot/issues/13 , https://nootnooot.codeberg.page/segway-ninebot-ble/

Official-app writes in segway-pairing.pklg (session started "unlocked" = on, `0x4D`=1):

| Time | App write | Scooter notification | Meaning |
|---|---|---|---|
| 20:02:47 | `ES_CONTROL WRITE_NO_REPLY idx=0x79 data=01 00` | `0x21 idx=0 data=02 4D 00 00` | power OFF ("lock") |
| 20:03:00 | `ES_BLE     WRITE_NO_REPLY idx=0x1E data=01 00` | `0x21 idx=0 data=02 4D 01 00` | power ON ("unlock") |
| 20:03:02 | `ES_CONTROL WRITE_NO_REPLY idx=0x79 data=01 00` | `0x21 idx=0 data=02 4D 00 00` | power OFF ("lock") |

- Notifications `0x21` are change reports `[02, reg, value_le16]`.
- Status word `0x1D` reads `0x0800` (bytes `00 08`; bit 11 = "activated") and never changed.
- Only 3 writes for the 4 requested actions; the final "unlock" is not in the trace.
- Not yet verified whether the F2 Pro also has a real (immobilising) lock separate from power.
- Nothing in f2probe sends these frames: `assert_safe` still refuses every WRITE.

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

## Register sweep (live read, scooter on; all READ frames, 2 bytes each, indices 0x00-0xFF per board)
Unverified unless noted: each item is inferred from a single snapshot. Serial-like and key-like values are deliberately not recorded.

- Auth with the key from the official app's pairing capture still works; the scooter answers on all three boards
  (ctrl 0x20, BMS 0x22, BLE 0x21), but only while power is on (0x4D = 1).
- Status word 0x1D: 0x0800 in the app captures (power on, not charging) and 0x0900 in the first live sweeps. The "bit 8 = on" guess
  here was wrong: power was on in both; see "ECO mode, light, charger" below for the likelier meaning (charging).
- BMS 0x40-0x49: ten values of 4041-4102, summing to the pack voltage (0x34 = 4094 -> 40.94 V) -> **per-cell voltages
  in mV, 10S pack** (0x49 was 60 mV below the others). Arithmetic check passes; not cross-checked against the app.
- BMS 0x35: both bytes 0x2F; 47 - 20 = 27 matches the controller body temperature (27.0 C) -> probably temperature
  sensors stored as value+20.
- Controller 0xB1-0xBA: a mirror of the live values (range actual/predicted, battery %, temperature, mileage, status
  word), probably a block the app polls at once. Mileage = u32 at 0x29/0x2A (m), confirmed by the 1332.7 km reading.
- Controller 0x3E/0x3F/0x41/0x42/0x3A hold small values in the 20-30 and 230 range: likely further temperatures. 0x22/0x23 = 94/65.
- Controller 0x32-0x35 (two u32?), 0x73 = 20000, 0x74 = 150, 0xC8-0xCE (0xF0xx patterns), 0xDA-0xDF: unknown.
- BLE board 0x78-0xAA and 0xD9-0xE0 look like high-entropy data (key/certificate material?). Do not dump or commit it.
- BLE board 0x05-0x0B and 0x70-0x77 repeat the scooter name; 0x60-0x66 and 0xCD-0xD4 read as other ASCII ids.

## What the official app polls (decoded from segway-comms.pklg and segway-pairing.pklg; same set in both)
- Loop: BLE 0x4D (power), ctrl 0x1B, 0x1C, 0x1D, 0x22, 0x25, 0x26, 0x29, 0x75, 0x77. Once: ctrl 0x10, 0x1A, 0x3E, 0x67, 0x68,
  0x7B, 0x7D, 0xDA, 0xE4, 0xE7; BLE 0x50; BMS 0x10, 0x1B, 0x20, 0x31, 0x35, 0x40, 0x52, 0x53.
- ctrl 0x22 is the battery percent the app shows (matches BMS 0x32 on the live read: 94 = 94). ctrl 0x26, BLE 0x50, BMS 0x53 read 0 at rest (ctrl 0x77 is walk mode, see below);
  ctrl 0xE7 = 1. Meaning unknown; the likely candidates are ride/trip state, which need a read while riding.
- The app reads all ten cells in ONE 20-byte read at BMS 0x40 (our read loop does ten 2-byte reads instead; both work).
  Captured cells: 3922..3930 mV with one at 3774 mV, so the last cell has been the weak one since before the live sweep (spread
  156 mV then, ~60 mV at 94 %). (verified against the live sweep, unverified in the Android app)
- BMS 0x35 captured as 30 30 -> 28 C each with the +20 offset, consistent with the live read.
- The app does not read the 0xB1-0xBA mirror block seen in the sweep, so it is not part of the app's protocol use.
- Added to f2probe/registers.py and Registers.kt: cell_voltages (BMS 0x40 x10), cell_temps (BMS 0x35); tests use the captured bytes.

## KERS (energy recovery) setting, from a before/after sweep
- Changing KERS from medium to weak in the official app changed only controller 0x7B: 1 -> 0 (user-reported labels).
  So 0 = weak, 1 = medium; 2 = strong is a guess. The other differences in the diff were live noise (battery %, cell mV, range, one
  fluctuating value at ctrl 0x3A, and a few registers that answered in only one of the two sweeps).
- Reading matches the existing `kers` register (ctrl 0x7B). Writing it is not implemented: `assert_safe` / `FrameGuard` still refuse it.

## KERS strong, TCS on (second before/after sweep)
- KERS strong -> ctrl 0x7B = 2, so 0/1/2 = weak/medium/strong (confirmed for all three).
- Turning TCS (traction control) on changed ctrl 0xF3: 0 -> 1 (it read 0 in two earlier sweeps with TCS off). Single observation; the
  only non-noise change besides 0x7B. Added as `tcs` (read-only) in f2probe/registers.py and Registers.kt (experimental).

## Walk mode (5 km/h) on
- Turning walk mode on changed ctrl 0x77: 0 -> 1. That register is in the official app's polling loop, which fits.
  Added as `walk_mode` (read-only, experimental in the app). Only non-noise change in that diff, single observation.
- Still unmapped from the app's list: ctrl 0x26, ctrl 0xE7 (reads 1), BLE 0x50, BMS 0x53.

## Toggle-back confirmation and charging
- TCS off and walk mode off: ctrl 0xF3 and 0x77 both went 1 -> 0, so both registers are confirmed in both directions. KERS (0x7B) also went
  2 -> 1 (back to medium) in the same diff.
- Battery current (BMS 0x33) while the scooter was charging and on: -1.31, -1.21, -1.14, -1.03, -1.00 A across five reads, while battery
  went 94 -> 96 % and pack voltage 40.93 -> 41.01 V. Reads as the end-of-charge taper, so **negative = charging** (hypothesis; the
  discharge sign is not yet observed). The status word 0x1D stayed 0x0900 during charging, so no charge flag was found there.

## ECO mode, light, charger (sweep after those changes)
- Mode SPORT -> ECO: ctrl 0x75 2 -> 1 (0 NORMAL, 1 ECO, 2 SPORT, as already mapped). ctrl 0x82 and 0x84 also went 2 -> 1 with it,
  so they are probably per-mode parameters (speed/power tier?). Predicted range jumped 37.6 -> ~52 km and actual range 30.7 -> 42.2 km
  with the mode (range is mode dependent). Unverified.
- Light on: no register changed (ctrl 0x7D tail light stayed 2). The front/ambient light is either not a register on these boards,
  or sits in the BLE-board range 0x60+ that the sweep deliberately skips.
- Charger: in the same diff the battery current flipped from -1.00 A to +0.08 A and the pack fell 41.01 -> 40.85 V, i.e. charging
  stopped. At the same time status word 0x1D went 0x0900 -> 0x0800 (bit 8 cleared) and BMS 0x30 low byte 0x43 -> 0x01.
  Likely: **0x1D bit 8 = charging** and **BMS 0x33 negative = charging, positive = discharge**. Confounded with the mode/light change
  in the same step, so confirm by re-plugging the charger with nothing else changed. BMS 0x52 high byte also fell one step per sweep
  (0x37, 0x36, 0x35) during charging: unknown, possibly charge related.

## Charger re-plug (only change: charger plugged back in)
- ctrl 0x1D 0x0800 -> 0x0900 and BMS 0x33 +0.08 A -> -1.10 A, both reversed by unplugging and restored by plugging in. So
  **0x1D bit 8 = charging** and **BMS 0x33 negative = charging**, observed in both directions. Added `charging` (read-only).
- BMS 0x30 low byte also follows it (0x01 unplugged, 0x43 charging); meaning of the other bits unknown. ctrl 0x5D went 0 -> 0xFFFF once
  (unknown, single observation).
- Not charge related: BMS 0x29 (flipped between 0 and 1 independently of the charger).

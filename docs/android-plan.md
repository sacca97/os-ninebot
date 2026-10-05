# Android app plan: open-source Segway F2 Pro client (v0.1)

Scope of v0.1: **pairing / credential import, info display, power on/off** (what the official app calls
unlock / lock). Everything here is derived from the Python research tool in this repo (`f2probe/`), checked
against a real official-app capture and live reads/logins on a real F2 Pro (`<scooter name>`).

**Status legend used below:** ✅ verified on the real scooter · 🟡 matches a real capture but never run live from
our code · ❓ unverified / assumption.

---

## 0. Decisions you still need to make

| # | Decision | Recommendation |
|---|---|---|
| 1 | **Licence / provenance of the crypto.** Our Python reference imports `miauth` (AGPL-3.0, a port of NinebotCrypto). A line-by-line Kotlin port of its source would be a derivative. | Implement §4 **clean-room from this spec + the test vectors** (the algorithm is documented publicly at nootnooot.codeberg.page/segway-ninebot-ble). Then choose any licence. If you port the code directly instead, the app must be AGPL-3.0. |
| 2 | **BLE library**: Nordic Kotlin-BLE-Library vs raw `BluetoothGatt`. | Put everything behind a small `ScooterLink` interface (§3). Start with raw `BluetoothGatt` wrapped in coroutines (fewest dependencies, we need one characteristic pair); swap later if flaky. Check Nordic's current API before choosing it. |
| 3 | **Name / branding.** | Don't use Segway/Ninebot names or logos. Call it e.g. "OpenRide" and say "unofficial" in the store/readme. |
| 4 | **minSdk / targetSdk.** | minSdk 26, targetSdk = current stable. Runtime BLE permissions differ ≤ API 30 vs ≥ 31 (§8). |

## 1. Prerequisites before the app writes anything

These are open items from the Mac work. **Do not ship power control until items 1 and 2 are done.**

1. ✅ **`f2 power on` / `f2 power off` were run live and work** (owner confirmed on the F2 Pro). Original item: Run them on the bench (README → "power").
   Confirm `0x4D` flips and note what the scooter physically does. The app's power feature is gated on this.
2. ❓ **Is "lock" really power-off on the F2 Pro, or is there a separate immobilising lock?** Community docs for
   other models say Bluetooth has no real lock. Watch the scooter on the bench test above.
3. ❓ **Which register holds live speed?** Needed to refuse power-off while moving. Not identified. The official app
   polls controller registers `0x22`, `0x26`, `0x77` that we haven't decoded. Capture while spinning the wheel and
   diff them. Until known, power-off needs a typed/explicit confirmation (as in the CLI).
4. ✅ **Pairing: `f2 pair` (variant A, weak mode) works live** (owner confirmed); the app uses only that flow. Original item: has two plausible variants and `f2 pair` has never been run live. Treat in-app *pairing*
   as experimental; make **credential import** (§6.1) the primary path.

## 2. Hand-off: what to copy to the Android machine

Copy:
- `docs/android-plan.md` (this file), `docs/f2pro-findings.md`
- `android/test-vectors/crypto_vectors.json` (21 **synthetic** frames, safe to commit)
- `f2probe/` (reference implementation: `protocol.py`, `crypto.py`, `registers.py`, `transport.py`) and `tests/`
- `scripts/make_vectors.py`

**Do NOT copy or commit:** `segway-comms.pklg`, `segway-pairing.pklg` (contain your pairing password),
`~/.config/f2probe/app_key.hex`, `captures/`. The app's repo must never contain a real credential or serial.

**Kickoff prompt for the next session:** *"Implement docs/android-plan.md milestone M0 and M1 only. The Python files in
f2probe/ are the reference; crypto must pass android/test-vectors/crypto_vectors.json byte for byte."*

---

## 3. Architecture

Kotlin, Jetpack Compose (Material 3), coroutines/Flow. Two Gradle modules so the protocol is testable on the JVM
without a device:

```
:core   (pure Kotlin/JVM, no Android imports)
  protocol/  Packet, Cmd, Dev, FrameReassembler            <- f2probe/protocol.py
  crypto/    NinebotCrypto (key derivation, seal/open)     <- f2probe/crypto.py
  registers/ Register table + decoders                     <- f2probe/registers.py
  session/   ScooterSession (handshake, request/response)  <- f2probe/transport.py
  safety/    FrameGuard (allowlist)                        <- transport.assert_safe

:app    (Android)
  ble/       ScooterLink (interface) + AndroidGattLink, scanner
  data/      CredentialStore (Keystore-wrapped), settings
  ui/        screens, ViewModels
```

`ScooterLink` (the only thing `session` knows about Bluetooth):
```kotlin
interface ScooterLink {
    val incoming: Flow<ByteArray>        // raw notification chunks from 6e400003
    suspend fun write(chunk: ByteArray)  // one write-without-response to 6e400002
    suspend fun close()
}
```
A `FakeScooterLink` (in `:core` tests) runs the scooter side of the protocol with the same crypto, so the whole
handshake and every feature is unit-testable.

### 3.1 BLE facts ✅
- Advertising: manufacturer data company id **16974 (0x424E)**; **local name = scooter name** (e.g. `<scooter name>`).
  **The name is key material**, so take it from the scan record (`ScanRecord.deviceName`), not
  `BluetoothDevice.name` (can be cached/stale/null).
- Nordic UART service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`; write `6e400002-…` ; notify `6e400003-…` (same suffix).
- Official app: enables notifications (CCCD), then sends each whole frame in **one write-without-response**
  (`WRITE_NO_RESPONSE`; frames up to 27 bytes) and receives whole frames per notification. It negotiates a larger
  MTU. Our code must still **reassemble**: a frame is complete when `len(buf) == buf[2] + 13`; a new frame starts
  with `5A A5`. Request MTU (e.g. 247) after connect and chunk writes to `MTU-3` bytes anyway.
- The scooter accepts **one central at a time**: the official app must be closed. It drops the link shortly after
  `INIT` if you don't log in.
- Android: wait for `onServicesDiscovered` before anything, serialise GATT ops, retry a bounded number of times on
  status 133, always `close()` the GATT object.

---

## 4. Protocol + crypto spec (port exactly; verify with the vectors)

### 4.1 Plain frame
```
5A A5 | LEN | SRC | DST | CMD | IDX | DATA[LEN]          (LEN = data bytes only)
```
Devices: `0x20` controller, `0x21` BLE board, `0x22` battery/BMS, `0x3D` PC, **`0x3E` PHONE (use this)**.
Commands: `0x01` READ, `0x02` WRITE, `0x03` WRITE_NO_REPLY, `0x04` READ_ACK, `0x05` WRITE_ACK,
`0x5B` INIT, `0x5C` SET_PWD ("PING"), `0x5D` AUTH ("PAIR"). Unsolicited BLE-board notifications use cmd `0x21`.

### 4.2 On-air frame = `LEN + 13` bytes
```
header(3: 5A A5 LEN, plaintext) | encrypted body (LEN+4 bytes: SRC DST CMD IDX DATA) | 6-byte tail
```
Let `body` = plaintext bytes after the 3-byte header, `n = LEN + 4`.

### 4.3 Keys
```
pad16(x) = x right-padded with 0x00 to 16 bytes
key(k1, k2) = SHA1(pad16(k1) || pad16(k2))[0..16]

FW_DATA   = 97 CF B8 02 84 41 43 DE 56 00 2B 3B 34 78 0A 5D
nameKey   = key(deviceName, FW_DATA)       // INIT only
bleKey    = key(deviceName, bleData)       // bleData = first 16 bytes of the INIT reply
appKeyDrv = key(password,  bleData)        // password = the 16-byte stored credential
```

### 4.4 Counter and mode
The tail always ends with the **16-bit big-endian counter**. **Counter 0 ⇒ weak mode, counter ≥ 2 ⇒ AES mode.**
(Counter 1 never occurs: the reference sender checks the mode *before* incrementing, so the first AES frame is 2.)
- Keep one counter per session. Receiving a frame sets `counter = its counter`. Sending uses `counter + 1`.
- After `INIT` (counter 0) set `counter = 1` so the login goes out as **2** ✅.
- Frames alternate strictly in the real traffic (app N, scooter N+1…) ✅: **one request in flight at a time.**
- Only 16 bits travel, so a session must end before 65535 (reconnect).

### 4.5 Counter 0 ("weak", INIT and some pairing frames)
```
ks      = AES128_ECB(key, FW_DATA)                // same 16-byte block for every block
enc     = body XOR ks (repeated per 16-byte block)
crc     = (~sum(body)) & 0xFFFF
tail    = 00 00 | crc_lo crc_hi | 00 00           // crc little endian
```
Open: decrypt, then check `crc` against tail bytes 2-3.

### 4.6 Counter ≥ 2 (AES-CTR + CBC-MAC) ✅ byte-exact vs real capture
```
nonce(A_i) = 01 | counter(4, BE32) | bleData[0..8] | 00 | 00 | i          // 16 bytes, i = block index byte
ks_i       = AES128_ECB(key, A_i), i = 1,2,3…                              // block i of the body
enc        = body XOR ks            (last block partial)

B0   = 59 | counter(4, BE32) | bleData[0..8] | 00 | 00 | n                 // n = body length
X    = AES(key, B0)
X    = AES(key, X XOR (header3 || 00*13))                                  // header = 5A A5 LEN
for each 16-byte block of the PLAINTEXT body (zero-padded): X = AES(key, X XOR block)
tag  = X[0..4] XOR AES(key, A_0)[0..4]        // A_0 = same layout as A_i with i = 00
frame = header3 | enc | tag(4) | counter(2, BE)
```
Open: counter from the last 2 bytes → decrypt → recompute the tag → **reject on mismatch** (never accept an
unauthenticated frame). The counter in the nonce is the 16-bit wire value.

### 4.7 Which key when
| Frame | Key | Counter |
|---|---|---|
| INIT request/reply | nameKey | 0 |
| SET_PWD | bleKey | see §5.3 |
| AUTH and everything after | appKeyDrv | ≥ 2 |
Receiving side: try the keys that are currently possible and accept only a verified frame (as `Session.decrypt` does).

### 4.8 Test vectors
`android/test-vectors/crypto_vectors.json`: 21 synthetic cases (name, bleData, password, per case: key label, counter,
plaintext, frame) generated by `scripts/make_vectors.py`. Both **sealing** and **opening** must match. Include them in
`core/src/test/resources`. This is the first acceptance gate (M1).

---

## 5. Session flows

### 5.1 Connect + login with a stored credential ✅ (live-verified from Python)
```
connect → discover services → enable notify (6e400003) → (request MTU)
→ send INIT  [PHONE→BLE 0x5B idx 0, no data]               nameKey, counter 0
← INIT reply [idx = 1 if a password is stored, else 0; data = bleData(16) ‖ serial(14 ASCII)]
→ set counter = 1; send AUTH [PHONE→BLE 0x5D idx 0, data = serial(14)]   appKeyDrv, counter 2
← AUTH reply [BLE→PHONE 0x5D idx 1] = success
```
- **One attempt, no retry.** A wrong password gets *no reply* (timeout); treat as `CredentialRejected` and stop.
- If INIT reply idx = 0 ⇒ no password stored ⇒ go to pairing (§5.3).
- The BLE board also sends an unsolicited frame right after connect (counter 0): ignore frames that aren't the reply.

### 5.2 Request/response
- READ: `PHONE→dst  cmd 0x01, idx, data = [0x02]` → reply `dst→PHONE cmd 0x04, same idx, data = 2 bytes LE`.
  Multi-word registers read `idx, idx+1…` (each `[0x02]`) and concatenate.
- Timeout per request ~3 s; retry a read at most twice (reads are idempotent). **Never retry a write.**
- Notifications `BLE→PHONE cmd 0x21 idx 0 data = 02 <reg> <value LE16>` report register changes (e.g. `02 4D 00 00`
  = power state became 0). Use them to update the UI immediately, but still confirm by reading.

### 5.3 Pairing (replaces the scooter's password) ✅ variant A verified live
The app implements only variant A (`WeakModePairing`) and logs in on the same connection afterwards (counter left as the scooter reported it). Variant B below was never needed and is not implemented.
The 16-byte password is **chosen by the client** (use `SecureRandom`). Official-app trace ✅:
`INIT → SET_PWD(password) → reply idx 0 = "pending, press the scooter's power button" → (button) → idx 1`, then a new
connection logs in with `AUTH` using the new password.
Two variants, only the second is seen in our capture:
- **A (ownbee/`f2 pair`):** `SET_PWD` right after INIT, counter 0 (weak mode, bleKey), repeat every 2 s up to 60 s until
  idx 1, then AUTH on the same connection. ❓ never run live from our code.
- **B (official app):** `SET_PWD` as an AES frame with bleKey (counter ≥ 2; seen at #5 after stale AUTH frames), then
  after the button press **reconnect** and do §5.1 with the new password. 🟡
Implement a `PairingStrategy` interface; try **B first**. Persist the new password *before* sending SET_PWD (pending
slot), and promote it only after a successful login, exactly like `f2 pair`. Warn the user: pairing invalidates the
official app's credential (and the Mac tool's), so offer an **export** of the new password.

---

## 6. Features

### 6.1 Credential screen (do import first)
- **Import** a 32-hex-char password (paste or QR). This reuses the official app's credential, so nothing gets
  replaced: the Mac tool's `~/.config/f2probe/app_key.hex` can be typed in. Validate: 16 bytes.
- **Pair new** (advanced, behind a warning dialog, §5.3).
- **Forget** credential. **Export** (explicit action, biometric/PIN gate, copy to clipboard flagged sensitive).
- Storage: password encrypted with an **AndroidKeyStore AES-GCM** key, ciphertext in DataStore, keyed by scooter
  serial. `allowBackup=false`. Never log the password, bleData or full frames in release builds.

### 6.2 Scan / connect
- Scan with filter on manufacturer id 0x424E (and optionally the service UUID). Show name + RSSI. Remember the chosen
  scooter (name, address). Take `deviceName` from the scan record and keep it as given.
- Permissions: ≥ API 31 `BLUETOOTH_SCAN` (`neverForLocation`) + `BLUETOOTH_CONNECT`; ≤ API 30 `ACCESS_FINE_LOCATION` +
  legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` (`maxSdkVersion=30`). Explain each before asking.

### 6.3 Info display (read-only)
Connect → login → read `power_state` **first**. If **off: show "Scooter is off" and stop** (the controller/battery
boards don't answer while off; this mirrors `f2 status`). If on, read the rest, then refresh: power every ~2 s, the
rest every ~5 s, one request at a time. Stop polling when the screen is backgrounded and disconnect.

| Field | Board | Index | Words | Decode | Status |
|---|---|---|---|---|---|
| Power state | BLE 0x21 | 0x4D | 1 | u16, 1 = on / 0 = off | ✅ both states |
| Serial | ctrl 0x20 | 0x10 | 7 | ASCII | ✅ |
| Controller FW | ctrl | 0x1A | 1 | `v>>8 . (v>>4)&F . v&F` (0x0187 → 1.8.7) | ✅ |
| BLE FW | ctrl | 0x68 | 1 | same (→ 5.6.6) | ✅ |
| BMS FW | batt 0x22 | 0x17 | 1 | hex | ✅ |
| Battery % | batt | 0x32 | 1 | u16 | ✅ (66) |
| Battery voltage | batt | 0x34 | 1 | u16 / 100 V | ✅ (39.08) |
| Range | ctrl | 0x24 | 1 | u16 / 100 km | ✅ (21.12) |
| Mileage | ctrl | 0x29 | 2 | u32 (lo word first) / 1000 km | ✅ (1332.7) |
| Avg speed | ctrl | 0x65 | 1 | u16 / 10 km/h | ✅ |
| Mode | ctrl | 0x75 | 1 | 0 NORMAL, 1 ECO, 2 SPORT | ✅ reads 2; names from ownbee ❓ |
| Error / alarm | ctrl | 0x1B / 0x1C | 1 | u16 | ✅ |
| Status word | ctrl | 0x1D | 1 | u16 (0x0800 seen) | ✅ (no lock bit here) |
| Battery current | batt | 0x33 | 1 | s16 / 100 A; negative = charging | ✅ live from Python, both signs |
| Battery health | batt | 0x3B | 1 | u16 % | ✅ live from Python (98) |
| Cell voltages | batt | 0x40 | 10 | u16 mV each, 10S | ✅ live from Python; also in the app capture |
| Cell temperatures | batt | 0x35 | 1 | 2 bytes, °C + 20 | ✅ live from Python |
| Charging | ctrl | 0x1D | 1 | bit 8 of the status word | ✅ live from Python, both states |
| Body temp | ctrl | 0x3E | 1 | s16 / 10 °C | ✅ live from Python (27.0) |
| Range predicted | ctrl | 0x25 | 1 | u16 / 100 km | ✅ live from Python |
| KERS | ctrl | 0x7B | 1 | 0 weak, 1 medium, 2 strong | ✅ live from Python |
| TCS / walk mode | ctrl | 0xF3 / 0x77 | 1 | 0 off, 1 on | ✅ live from Python, toggled both ways |
| Controller voltage | ctrl | 0x47 | 1 | u16 / 100 V | ❓ |
| Cruise / tail light | ctrl | 0x7C / 0x7D | 1 | u16 | ❓ (meaning unknown) |

Show ❓ rows in an "Experimental" section, hidden by default. **Do not display or read the "BT pairing code"
register (0x17 ×3 on ctrl)**; it's not needed.

### 6.4 Power on / off (the app's "unlock / lock")
Exact frames (🟡 identical to the official app's, confirmed in the capture; live send from our code pending §1):

| Action | Frame (plaintext) | Board target |
|---|---|---|
| Power **on** ("unlock") | `PHONE→BLE(0x21) cmd 0x03 idx 0x1E data 01 00` | BLE board |
| Power **off** ("lock") | `PHONE→CTRL(0x20) cmd 0x03 idx 0x79 data 01 00` | controller |

Rules (mirror `f2 power`):
1. Read `0x4D` first. If already in the requested state, **send nothing**.
2. **Power off:** explicit confirmation ("only while stopped"); if/when the speed register is known (§1.3), refuse
   when speed ≠ 0.
3. Send **one** write (protocol cmd 0x03 is *no reply*; at the ATT level use write-without-response). **No retry.**
4. Poll `0x4D` for up to 15 s (takes ~3-4 s); show a spinner; success only when it reads the new value. If it
   doesn't change, say so and **don't resend automatically**.
5. `FrameGuard` allows *exactly these two packets* and nothing else (copy `tests/test_offline.py` cases: a different
   register/value/board/cmd must be refused even with power enabled).
6. Note in the UI that powering on over Bluetooth leaves the scooter ready to ride.

### 6.5 Debug screen (very useful, build early)
Frame log showing plaintext and (optionally) ciphertext, direction, counter, key label. Redact password/bleData by
default. Export to a file only on explicit action.

### 6.6 Explicitly out of scope for v0.1
Speed/mode/KERS/light settings, firmware update, ride tracking/maps, cloud account, any raw-write console, multiple
scooters, widgets/background service.

---

## 7. Milestones and acceptance criteria

| M | Deliverable | Done when |
|---|---|---|
| M0 | Gradle project, `:core` + `:app`, CI running JVM tests, lint | `./gradlew test` green on a clean checkout |
| M1 | `Packet`, `FrameReassembler`, `NinebotCrypto`, `FrameGuard` | **all 21 vectors seal and open byte-for-byte**; reassembler handles chunked/merged notifications; guard tests mirror the Python ones |
| M2 | `AndroidGattLink`, scan, permissions, **INIT only** | App shows serial + "password stored: yes" from a real scooter; no credential involved, nothing written but INIT |
| M3 | `ScooterSession` login with a **typed-in** credential + reads | `Auth OK`; reads match `f2 status` for the same state; wrong key ⇒ clean error, **no retry loop** |
| M4 | Dashboard (§6.3), off-state handling, backgrounding | Off scooter ⇒ "off" and no timeouts; on ⇒ all ✅ rows populated |
| M5 | Power control (§6.4), only after §1.1-1.2 | on/off verified on the bench by watching the scooter and `0x4D`; guard refuses everything else |
| M6 | Credential store, import/export, (experimental) pairing | Import works and survives restart; pairing only after the Python `f2 pair` equivalent is understood |
| M7 | Polish: errors, onboarding text, licence, README, privacy statement | Release checklist (§9) |

Always do **M2 → M3 before any write exists** in the codebase.

## 8. Testing strategy
- **JVM unit tests (`:core`):** vectors (crypto), reassembly (frames split across notifications, two frames in one,
  garbage before `5A A5`), counters (login at 2, wrong counter rejected), the whole handshake against
  `FakeScooterLink`, power flow including "already in state ⇒ nothing sent", "no change ⇒ no resend", and every
  forbidden-frame case.
- **Never commit** real captures. If you want regression tests from real traffic, re-encrypt with synthetic keys.
- **On-device checklist** (manual, per release): scan → INIT only → login → status (on) → status (off) → power off →
  verify → power on → verify → kill app mid-poll → reconnect. Test with the official app running (expect "can't connect")
  and then closed.
- Android quirks to test: permission denied/permanently denied, Bluetooth off, location off (≤ API 30), GATT 133,
  screen rotation during a power operation, process death with a pending pairing key.

## 9. Release checklist
- No secrets in repo or logs; `allowBackup=false`; Keystore tested on a device without lock screen.
- Licence decision (§0.1) applied; third-party notices; README states "unofficial, use at your own risk, not
  affiliated with the manufacturer".
- Writes: only the two power frames; verified by test and by grepping the code for `0x03`/write callers.

## 10. Risks / known unknowns (short)
1. Power commands unverified live from our tooling (§1.1). 2. Possibly a real lock separate from power (§1.2).
3. Speed register unknown (§1.3). 4. Pairing variant unverified (§5.3). 5. Mode names from another model. 6. The scooter
name must match exactly or *every* key is wrong (symptom: INIT reply won't verify). 7. A failed login may start to
look like a lock-out if hammered: keep to one attempt per connection and add a visible cool-down. 8. Only one F2 Pro
(firmware controller 1.8.7, BLE 5.6.6) has been tested.

## 11. Python ↔ Kotlin map
| Python | Kotlin |
|---|---|
| `f2probe/protocol.py` | `core/protocol` |
| `f2probe/crypto.py` (`Session`) | `core/crypto/NinebotCrypto`, `core/session` counter/key logic |
| `f2probe/registers.py` | `core/registers` |
| `f2probe/transport.py` (`Client`, `assert_safe`, `POWER_*`) | `core/session/ScooterSession`, `core/safety/FrameGuard` |
| `f2probe/cli.py` (`status`, `power`, `pair`) | ViewModels / use-cases |
| `tests/test_offline.py` | `core/src/test` (+ vectors) |


## Power-off interlock and latency work (status)
- **Interlock** ✅ unit-tested with a fake scooter, ❓ not tried on a moving scooter. `ScooterSession.setPower(false)` reads ctrl 0x65
  ("average speed") and ctrl 0x26 (polled by the official app, 0 at rest, suspected live speed) and only sends the write if both read 0.
  A non-zero value or an unreadable register throws `PowerRefused` and nothing is sent (fail closed). Power ON is not interlocked.
  The confirmation dialog was removed. Open question: if 0x65 is a trip average it may stay non-zero after a ride and block power-off;
  the Experimental list shows both registers so they can be watched while riding.
- **Reads:** a register of N words is now ONE request of 2N bytes (cells 20 bytes: 73 ms against 958 ms for ten reads, live from Python;
  also 14, 4 and 8 byte reads checked live). Never more than 20 bytes. Strictly one request in flight: a pipelining experiment on the
  scooter lost replies, but the scooter state was not checked during that run, so it is untested and not used.
- **Link:** connection priority HIGH, MTU wait capped at 1.5 s.
- **Flow:** the last scooter's name is remembered; at launch the app goes straight to the dashboard (a short scan for that name, no probe
  connection) when a credential is stored; a scan result with a stored credential also skips the probe screen ("Credential" button
  on each card opens the old screen). The credential decrypts while GATT connects.
- **Polling:** one cycle per second (power + FAST registers: battery, current, status, speed, mode, range); the rest every 5th cycle; static values last.

## Credential import/export and foreground-only polling (status)
- **Format** (same in the app and `f2`): the 16-byte password as 32 hex characters on one line, the same as `~/.config/f2probe/app_key.hex`.
  Input tolerates whitespace, `:` separators, a `0x` prefix and either case; output is upper case with a trailing newline.
  Shared parser: `CredentialHex` (core) and `parse_key_text` (Python), both unit-tested.
- **App:** the credential screen imports from a typed/pasted string or "From file" (reads at most 1 KB), and exports behind the device
  screen lock: copy to the clipboard (flagged sensitive) or "Save file" (clear text, keep it private). ❓ untested on a phone: the file
  pickers need a device.
- **Python:** `f2 keys --import FILE`, `f2 keys --export FILE [--force]` (mode 600, no silent overwrite), `f2 keys --set HEX`.
  A bad import leaves the stored key unchanged.
- **Polling:** only while the app is on screen. In the background polling is suspended; after 60 s the connection is dropped (releasing the
  scooter's single-app lock) and it reconnects when the app returns. ❓ untested on a phone.

## UI structure, theme, background behaviour (status)
- **Theme** ❓ not seen on a device yet: Material 3, light/dark follows the phone live (Material You colours on Android 12+), edge-to-edge,
  and a DayNight window theme so there is no white flash at launch in dark mode.
- **Home** shows only: scooter name, on/off state, battery %, range, and the power on / power off buttons. Battery opens Battery
  (charge, current, health, cells, cell temperatures); Range opens Range and ride (range, predicted, mode, speed, mileage, temperature).
  "More" has Scooter info (settings read-only, diagnostics, device, experimental), App settings and Log.
- **Background:** a bug in `MainActivity` disconnected the instant the screen locked (an old ON_STOP hook calling `stop()` that overrode the
  grace period). Removed. Now: polling pauses immediately; the link is kept 60 s so unlocking is instant, then dropped (which also frees
  the scooter for the official app); it reconnects on return. No foreground service on purpose (see the discussion in the chat: a held
  connection blocks the official app and costs battery, and nothing needs polling unattended).

## Navigation (status)
- The home screen is the root. It connects to the scooter saved last (name remembered, credential stored) after a short scan for that name
  (under a second in range), no probe connection. A connection opened from a saved ADDRESS does not work: the scooter uses a random
  address type, which `getRemoteDevice(address)` loses (logcat showed `addr_type=public`, and the connect hung), so the device object must
  come from a scan; it is then reused in-process for reconnects.
  With nothing saved, or the credential forgotten, it shows "No scooter paired" and an "Add scooter" button. Verified on an emulator.
- Scanning lives on a secondary "Add scooter" page (also under More). Picking a scooter that already has a credential collapses the stack
  to the home screen; otherwise the credential screen opens first and "Log in and open dashboard" also collapses to home. Back from home
  leaves the app. App settings are under More.

## Power on/off speed (status)
- Confirmation now waits on the scooter's own change notification (cmd 0x21, `02 4D <state>`), subscribed before the write goes out, with
  a 300 ms poll of 0x4D as fallback (was: a fixed 1 s sleep between polls). Unit-tested with a fake scooter that goes silent after the write.
- While a power command runs the poll loop stands down, and polled reads fail fast (1 s, one retry, instead of 3 x 3 s) and stop as soon
  as the scooter reports it is off. Before, the poll loop kept reading dead controller registers, each holding the single request slot
  for up to 9 s, which delayed the power-state checks. On a power-on notification the loop wakes at once.
- The scooter's own switching time (docs say 3-4 s) is a floor we cannot change. ❓ not yet timed on the real scooter.

## Bluetooth off / permission missing (status)
- The home screen checks the permission and the radio before connecting. With the radio off it shows "Bluetooth is off" and a
  "Turn on Bluetooth" button (system dialog) instead of a connection error; it connects by itself the moment the radio comes on, and
  switching the radio off while connected drops the link quietly. A missing permission shows "Bluetooth permission needed" and an Allow button.
  Verified on an emulator (off, on, and the system dialog opening); not tried against a real scooter.

## Credential import is validated (status)
- Importing (typed, pasted or from a file) no longer just stores the password: it goes to the pending slot, one real login is attempted
  (connect, INIT, AUTH), and only on success is it promoted and the app opens the home screen. A rejection discards it, keeps any
  existing credential untouched and starts the normal 30 s login cool-down; a connection failure also discards it and says why.
  Not possible during the cool-down. ❓ not tried against the scooter yet (the code path is the same one `pair` and the dashboard use).

## Device profiles (status)

- GATT, discovery, protocol selection, register mappings/decoders, polling priority and power mappings now come from `android/profiles/*.json`, validated and generated into typed Kotlin during the build. No runtime configuration parser is shipped.
- Only the existing Ninebot crypto/INIT-AUTH implementation and weak-mode pairing are supported. Additional model profiles do not imply additional crypto support.
- Model selection and saved-profile reconnects are implemented. ❓ Profile refactor not yet verified on hardware; core tests, debug build and lint cover the software changes.
- Controller 0x26 stays an unverified raw value, polled each cycle and shown below Average speed on Range and ride without the experimental switch. Official-app polling, zero at rest, and the Ninebot ES protocol's current-speed mapping (signed 16-bit / 10 km/h) support the hypothesis; meaning, scale and behavior on a moving F2 Pro remain ❓. The owner confirms 0x65 is not live speed. Existing zero checks on both registers are preserved.
- See `CONTRIBUTING.md` for the new-model contribution workflow. Real captures remain private; contributions use findings and synthetic fixtures.

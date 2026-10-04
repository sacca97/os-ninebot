# Segway F2 Pro open client (unofficial)

Unofficial, not affiliated with Segway-Ninebot; use at your own risk.

| Part | What | Where |
|---|---|---|
| **f2probe** (Python) | Research / reference tool: decode captures, scan, read, power on/off, pair. Where new protocol work is tried first. | `f2probe/`, `tests/`, `scripts/` |
| **OpenRide** (Android) | Kotlin/Compose app ported from f2probe: credential import, info display, power on/off, pairing. | `android/` (see `android/README.md`) |
| Shared contract | Crypto test vectors both sides must pass byte for byte. | `android/test-vectors/crypto_vectors.json` |
| Docs | Protocol findings, Android plan, Python to Android workflow. | `docs/` |

Contributors / agents: see `AGENTS.md` (layout, commands, rules, external reference URLs).

Workflow for experimenting in Python and porting: `docs/porting.md`. Run everything with `scripts/check_all.sh`.

**Never commit** real captures (`*.pklg`, btsnoop logs), credentials (`app_key*`), or your scooter's real name/serial
(it is key material). `.gitignore` covers the usual names; tests that need a real capture read the scooter name from
`F2_SCOOTER_NAME`.

**Licence:** not chosen yet. f2probe depends on `miauth` (AGPL-3.0), so distributing the Python tool carries AGPL terms;
the Android app is a clean-room port from the written spec and vectors (`docs/android-plan.md` §0.1). Add a `LICENSE`
before publishing.

---

# f2probe

Research tool for talking to a Ninebot / Segway F2 Pro over BLE.

## Safety model

| Tier | Commands | What goes on the air |
|---|---|---|
| No Bluetooth | `f2 decode` (btsnoop or .pklg), `f2 keys` | nothing |
| Passive | `f2 scan`, `f2 gatt` | scan / connect + service discovery, **no GATT writes** |
| Init only | `f2 init` | 0x5B INIT only, no credential |
| Authenticated reads | `f2 status`, `f2 read` | 0x5B INIT + 0x5D login with the stored key, then `READ` (0x01) frames only |
| Power | `f2 power on`, `f2 power off` | the two captured power frames only (write-no-reply, value 1); what the app calls unlock / lock |
| Pairing | `f2 pair --force` | 0x5C SET_PWD: **replaces the scooter's password**, gated behind `--force` and typing PAIR |

`f2probe/transport.py:assert_safe` refuses every other frame before encryption. The only writes that exist
are the two power frames (exact match, enabled only by `f2 power`) and the gated SET_PWD of `f2 pair`.
There are no raw-frame commands.

## Setup

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'
pytest -q            # offline tests, no Bluetooth
```

## Order of operations (from plan.md)

1. **Capture the official app first** (rooted Android):
   ```bash
   scripts/android_capture.sh setup
   # Segway app: connect, wait 5s, LOCK, wait 3s, UNLOCK, wait 3s, close app
   scripts/android_capture.sh pull
   ```
   **Or with an iPhone** (no root needed): capture with Apple PacketLogger (see "iPhone capture" below), then
   pass the `.pklg` file to `f2 decode` the same way.
2. **Decode it offline.** Every key is derivable from the capture, so this recovers the plaintext,
   including the exact LOCK / UNLOCK `WRITE` frames:
   ```bash
   f2 decode captures/btsnoop_*.log            # handshake + writes, READ polling hidden
   f2 decode captures/btsnoop_*.log --all --raw --json decoded.json
   f2 decode captures/btsnoop_*.log --save-app-key
   ```
   `--save-app-key` stores the **official app's** credential in `~/.config/f2probe/app_key.hex`, so the Mac
   reuses it rather than registering a second app with the scooter.
3. **Passive checks from the Mac** (close the official app first, since the scooter takes one connection):
   ```bash
   f2 scan
   f2 gatt
   ```
4. **Authenticated read-only status:**
   ```bash
   f2 status --debug     # prints plaintext + encrypted frames
   f2 read 0x1d          # status word, bit 1 = locked
   ```
   These need a stored app key (`f2 decode <pairing capture> --app-key HEX --save-app-key`) and make one
   login attempt, never a retry loop.

## iPhone capture (PacketLogger)

1. iPhone: open https://developer.apple.com/bug-reporting/profiles-and-logs/, pick **Bluetooth** for iOS,
   install the profile (Settings → Profile Downloaded → Install), then **reboot**. The profile expires
   after a few days.
2. Mac: download **Additional Tools for Xcode** from https://developer.apple.com/download/all/ (any
   free Apple ID works). Open `Hardware/PacketLogger.app`.
3. Plug the iPhone in over USB, unlock it, and tap **Trust**.
4. **Make sure the scooter is disconnected:** force-quit Segway Mobility and turn Bluetooth off in
   *Settings* (not Control Center). Also keep `f2` from connecting from the Mac.
5. PacketLogger → **File → New iOS Trace** → select the iPhone. Then turn Bluetooth back on.
6. Segway Mobility: connect → wait 3 s → LOCK → 5 s → UNLOCK → 5 s → LOCK → 5 s → UNLOCK → close app.
7. Stop the trace and **File → Save** as `captures/iphone_lock.pklg`.
8. `f2 decode captures/iphone_lock.pklg`. If no Ninebot frames show up, run `f2 decode captures/iphone_lock.pklg --gatt`.

The trace **must start before the app connects**. Decryption needs the handshake at the start of
the connection.

## Capturing a pairing

After the first handshake the official app stores a session password and later sessions skip
`SET_PWD (0x5C)`, so a normal trace can't be decrypted (`f2 decode` prints a NOTE when it sees this).
To get a decryptable trace, record one that *includes* the pairing: make the app forget the scooter
(remove it in the app, or reinstall the app), start the trace, then connect again. The app then sends
`SET_PWD` (you may need to press the scooter's power button) and the password appears in the decode
as `app_data`. Official-app pairing is simply redone, so nothing is lost.

If `decode` reports frames as *undecryptable* even though the name was detected, the F2 Pro probably
uses the newer protocol ("encryption 2"). The next step then is Frida on the official app, as plan.md
describes.

The register map in `f2probe/registers.py` is ported from ownbee/ninebot-ble (MIT,
https://github.com/ownbee/ninebot-ble, commit 1850351f5bce9627f612fd1141489ed7a575be61); it is not vendored here.

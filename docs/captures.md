# Capturing and decoding BLE traffic

Keep captures, decoded dumps and credentials private. The advertised scooter
name and serial are key material. Never attach real traffic to issues or PRs.

## Capture and connect

1. **Capture the official app first** (rooted Android):
   ```bash
   scripts/android_capture.sh setup
   # Segway app: connect, wait 5s, LOCK, wait 3s, UNLOCK, wait 3s, close app
   scripts/android_capture.sh pull
   ```
   **Or with an iPhone** (no root needed): capture with Apple PacketLogger (see "iPhone capture" below), then
   pass the `.pklg` file to `f2 decode` the same way.
2. **Decode it offline.** A trace containing the pairing password can recover plaintext,
   including the power-control frames. Later sessions need the stored credential:
   ```bash
   f2 decode captures/btsnoop_*.log            # handshake + writes, READ polling hidden
   f2 decode captures/btsnoop_*.log --all --raw --json decoded.json
   f2 decode captures/btsnoop_*.log --save-app-key
   ```
   `--save-app-key` stores the **official app's** credential in `~/.config/f2probe/app_key.hex`
   for later CLI connections.
3. **Scan and inspect GATT** (close the official app first, since the scooter takes one connection):
   ```bash
   f2 scan
   f2 gatt
   ```
4. **Authenticated read-only status:**
   ```bash
   f2 status --debug     # prints plaintext + encrypted frames
   f2 read 0x1d          # status word; bit 8 = charging on the F2 Pro
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

After pairing, the official app stores a password and later sessions skip
`SET_PWD (0x5C)`. Decoding those sessions requires that password:

```bash
f2 decode captures/session.pklg --app-key HEX --save-app-key
```

To capture a new pairing, start the trace before reconnecting after removing the
scooter from the official app or reinstalling it. Pairing may require pressing
the scooter's power button. It replaces the stored password and can invalidate
another client's credential. Keep the capture and decoded `app_data` private.

Undecryptable frames can indicate a missing handshake, a missing credential, or
an unsupported protocol. They do not by themselves identify an encryption version.


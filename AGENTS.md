# AGENTS.md

Guidance for AI coding agents and new contributors. Unofficial open-source client for the Segway F2 Pro
(Ninebot BLE protocol). Not affiliated with the manufacturer.

## Layout
- `f2probe/`, `tests/`, `scripts/`: Python research/reference tool. New protocol work is tried here first.
- `android/`: Kotlin app ("OpenRide"). `:core` is pure Kotlin/JVM (protocol, crypto, session), `:app` is Compose + BLE.
- `android/test-vectors/crypto_vectors.json`: synthetic vectors both sides must pass byte for byte.
- `docs/`: `f2pro-findings.md` (protocol facts + status legend), `porting.md` (workflow).

## Commands
- Everything: `scripts/check_all.sh` (pytest, regenerate vectors, Android `:core` tests). Needs JDK 21 (`JAVA_HOME`).
- Python: `python3 -m venv .venv && . .venv/bin/activate && pip install -e '.[dev]' && pytest -q`
- Android: `cd android && ./gradlew :core:test :app:assembleDebug :app:lintDebug`

## Rules
- Writes to the scooter stay behind `transport.assert_safe` (Python) and `FrameGuard` (Kotlin); change both together, with tests.
- Never commit real captures (`*.pklg`, btsnoop), credentials (`app_key*`), or the real scooter name/serial (it is key
  material). Vectors and tests use synthetic values; real-capture tests read `F2_SCOOTER_NAME`.
- Mark anything not verified on a real scooter as such (legend in `docs/f2pro-findings.md`).
- The Android crypto is a clean-room implementation from the spec; do not copy `miauth` (AGPL-3.0) source into it.

## External references (not vendored; open when needed)
| What | URL | Used for |
|---|---|---|
| ownbee/ninebot-ble (MIT) | https://github.com/ownbee/ninebot-ble (pinned: `1850351f5bce9627f612fd1141489ed7a575be61`) | Register map (`ninebot_ble/register.py`) ported to `f2probe/registers.py`; the weak-mode pairing flow |
| Segway/Ninebot BLE protocol write-up | https://nootnooot.codeberg.page/segway-ninebot-ble/ | Public description of the frame/crypto algorithm (basis for the clean-room Kotlin port) |
| ha-ninebot issue #13 | https://github.com/BobMcGlobus/ha-ninebot/issues/13 | Power on/off register frames for other models |
| miauth (AGPL-3.0) | https://pypi.org/project/miauth/ | `NbCrypto` reference used by the Python tool (pinned 0.9.7) |
| NinebotCrypto | `scooterhacking/NinebotCrypto` (named in `f2probe/crypto.py`; miauth is a port of it) | Original crypto implementation |

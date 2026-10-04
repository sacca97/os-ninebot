# Python to Android porting workflow

Python (`f2probe/`) is where protocol ideas are tried against the real scooter; the Android app (`android/`) is the
product. Keep them in step like this.

## Where things live
| Concern | Python | Kotlin |
|---|---|---|
| Frames, constants | `f2probe/protocol.py` | `core/.../protocol/Packet.kt`, `FrameReassembler.kt` |
| Crypto + counters | `f2probe/crypto.py` (uses `miauth`) | `core/.../crypto/NinebotCrypto.kt`, `SessionCrypto.kt` (clean-room) |
| Registers | `f2probe/registers.py` | `core/.../registers/Registers.kt` |
| Session / flows | `f2probe/transport.py` | `core/.../session/ScooterSession.kt`, `PairingStrategy.kt` |
| Safety allowlist | `transport.assert_safe` | `core/.../safety/FrameGuard.kt` |
| Tests | `tests/test_offline.py` | `core/src/test/kotlin` |

## Loop
1. Try the idea in Python against the scooter (`f2 ...`). Add an offline test in `tests/` if it can be tested without hardware.
2. If it touches crypto/framing, add a case to `scripts/make_vectors.py`; `scripts/check_all.sh` regenerates
   `android/test-vectors/crypto_vectors.json` and copies it into `:core`'s test resources.
3. Port to the matching Kotlin file; the vectors (and `SessionTest` with `FakeScooterLink`) are the acceptance gate.
4. `scripts/check_all.sh` must pass before pushing (CI runs both sides too).

## Rules
- Writes stay behind `assert_safe` / `FrameGuard`; update both when a new frame is allowed, with matching tests.
- Mark anything not verified live as such in `docs/android-plan.md` (legend: verified / matches capture / unverified).
- Vectors are synthetic. Never put real names, serials, passwords or captures in the repo.

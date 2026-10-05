# Python to Android porting workflow

Research protocol changes in Python (`f2probe/`), then implement them in Kotlin (`android/`).

## Source files

| Concern | Python | Kotlin |
|---|---|---|
| Frames, constants | `f2probe/protocol.py` | `core/.../protocol/Packet.kt`, `FrameReassembler.kt` |
| Crypto + counters | `f2probe/crypto.py` (uses `miauth`) | `core/.../crypto/NinebotCrypto.kt`, `SessionCrypto.kt` (clean-room) |
| Registers | `f2probe/registers.py` | `core/.../registers/Registers.kt` |
| Session / flows | `f2probe/transport.py` | `core/.../session/ScooterSession.kt`, `PairingStrategy.kt` |
| Safety allowlist | `transport.assert_safe` | `core/.../safety/FrameGuard.kt` |
| Tests | `tests/test_offline.py` | `core/src/test/kotlin` |

## Workflow

1. Try the idea in Python against the scooter (`f2 ...`). Add an offline test in `tests/` if it can be tested without hardware.
2. If it touches crypto/framing, add a case to `scripts/make_vectors.py`; `scripts/check_all.sh` regenerates
   `android/test-vectors/crypto_vectors.json` and copies it into `:core`'s test resources.
3. Port to the matching Kotlin file; the vectors (and `SessionTest` with `FakeScooterLink`) verify the port.
4. `scripts/check_all.sh` must pass before pushing (CI runs both sides too).

## Rules

- Writes stay behind `assert_safe` / `FrameGuard`; update both when a new frame is allowed, with matching tests.
- Mark anything not verified live as such in `docs/f2pro-findings.md` (legend: verified / matches capture / unverified).
- Vectors are synthetic. Never put real names, serials, passwords or captures in the repo.

## Adding a model

Use a [device profile](../android/profiles/README.md) for models that reuse the
implemented protocol. Research new protocols in Python first. Capture instructions
are in [captures.md](captures.md); Python live tools use F2 defaults and do not
load Android profiles.

For each mapping, record board, register, length, byte order, sign, scale and units.
Compare with the official app while changing one state at a time. Keep unknown
readings raw and experimental. Do not dump credential registers or test power-off
while moving. Profiles cannot authorize new writes.

In a PR, include the model, region and firmware tested, supported features,
mapping evidence, unresolved assumptions and check results. Use synthetic fixtures;
never publish real traffic or credentials. Android crypto must be implemented from
the specification, without copying AGPL `miauth` source.

With the Python environment active and JDK 21 selected:

```sh
scripts/check_all.sh
cd android
./gradlew :app:assembleDebug :app:lintDebug
```

For profile changes, check selection, login, readings, reconnect and restart on
hardware, and report the firmware tested. Software checks do not verify a model.

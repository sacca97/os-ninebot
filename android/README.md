# OpenRide

Open-source Android client for the Segway F2 Pro: credential import, info display, power on/off.
Not affiliated with Segway-Ninebot; use at your own risk. See `../docs/android-plan.md`.

    JAVA_HOME=<jdk21> ./gradlew :core:test :app:assembleDebug

`make help` lists the shortcuts. Debug and release are two separate apps on the phone:

| | id | label | commands |
|---|---|---|---|
| debug | `com.sacca.openride.debug` | OpenRide Debug | `make run` |
| release (signed, minified) | `com.sacca.openride` | OpenRide | `make keystore` once, then `make run-release` |

`make keystore` creates `release.jks` and `keystore.properties` (both git-ignored). Back them up: an installed release app can only be
updated with APKs signed by the same key. Pick a phone with `SERIAL=<adb serial>`.

- `:core` pure Kotlin protocol/crypto/session (JVM-testable, `crypto_vectors.json` is the gate)
- `:app` Compose UI, BLE (`AndroidGattLink`), Keystore credential store

Power control is on by default (verified on a real F2 Pro) and can be disabled in Settings. Never commit real credentials, serials or captures.

Stack: Kotlin 2.2, Compose + Material 3, Hilt (KSP), DataStore, version catalog (`gradle/libs.versions.toml`),
per-screen `@HiltViewModel`s, `collectAsStateWithLifecycle`, back stack as state (`Navigator`).

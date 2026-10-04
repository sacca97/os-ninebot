# OpenRide (unofficial)

Open-source Android client for the Segway F2 Pro: credential import, info display, power on/off.
Not affiliated with Segway-Ninebot; use at your own risk. See `../docs/android-plan.md`.

    JAVA_HOME=<jdk21> ./gradlew :core:test :app:assembleDebug

- `:core` pure Kotlin protocol/crypto/session (JVM-testable, `crypto_vectors.json` is the gate)
- `:app` Compose UI, BLE (`AndroidGattLink`), Keystore credential store

Power control is on by default (verified on a real F2 Pro) and can be disabled in Settings. Never commit real credentials, serials or captures.

Stack: Kotlin 2.2, Compose + Material 3, Hilt (KSP), DataStore, version catalog (`gradle/libs.versions.toml`),
per-screen `@HiltViewModel`s, `collectAsStateWithLifecycle`, back stack as state (`Navigator`).

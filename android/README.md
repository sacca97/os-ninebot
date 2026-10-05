# OpenRide

Android client for the Segway F2 Pro: telemetry, credential import, pairing and
power control. Not affiliated with Segway-Ninebot.

Build from this directory with JDK 21 and Python 3:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :core:test :app:assembleDebug :app:lintDebug
make run
```

Use `SERIAL=<adb serial>` to choose a phone. `make help` lists other commands.
Debug installs as OpenRide Debug (`com.sacca.openride.debug`). For the separate
release app (`com.sacca.openride`), run `make keystore` once, then `make run-release`.
Back up the ignored `release.jks` and `keystore.properties`; updates need the same key.

`make release VERSION=0.0.1` updates the app version, increments `versionCode`,
commits, tags and pushes. GitHub Actions builds and publishes the signed APK.
Use a clean working tree. `make build-release` builds locally.

Set GitHub Actions secrets `KEYSTORE_BASE64` (base64 of `release.jks`),
`KEYSTORE_PASSWORD` and `KEY_PASSWORD` from your existing signing key.
Set `KEY_ALIAS` if its alias is not `openride`; check with `keytool -list -keystore release.jks`.
Keep the same key for updates. Versions use `MAJOR.MINOR.PATCH`.

`:core` contains JVM protocol/session code; `:app` contains Compose UI, BLE and
Keystore storage. [Device profiles](profiles/README.md) are compiled at build time.
Power control has worked on a real F2 Pro; the profile refactor and motion
interlock remain unverified on hardware. See [protocol findings](../docs/f2pro-findings.md)
and [development workflow](../docs/porting.md).

# Contributing support for a scooter

OpenRide currently supports the F2 Pro. You can submit support for another model
as a pull request. Start with a read-only profile and add verified features as
you establish their behavior. A scooter using the same protocol may only need
new mappings; a different protocol also needs an implementation.

## What is implemented today?

The app has one Ninebot frame/crypto implementation: weak frames at counter zero
and AES frames using the existing key derivation, counter handling and MAC.
It supports the F2 Pro INIT/AUTH flow and weak-mode pairing. These modes are
parts of one implementation, not a catalogue of independently supported crypto
protocols. Support on other models or firmware is unverified until tested.

Profiles select these compiled implementations by identifier. Unknown identifiers
fail the build. A profile cannot describe a new cipher, key derivation or auth
state machine. For one of those, research it in Python first, implement Kotlin
from the specification, and add synthetic vectors that both implementations pass.
Do not copy AGPL `miauth` source into the clean-room Android crypto implementation.

## 1. Establish the model and collect evidence

Record the commercial model, region, controller/BLE/BMS firmware versions,
official-app version and phone OS. Exclude your device name, serial, Bluetooth
address, passwords, account details and identifying screenshots from public
reports. Record which behavior you observed on hardware and which is inferred.

Useful evidence includes:

- Advertisement manufacturer ID and GATT service/characteristic UUIDs, properties,
  notification versus indication, and write mode.
- Connection, initialization, authentication, reconnection and, if needed,
  pairing messages.
- Repeated telemetry reads, decoded bytes, corresponding official-app readings,
  and controlled changes in state.

Packet captures are useful when the mapping or protocol is unknown. Existing
specifications and verified read-only observations may suffice for a model that
reuses the implemented protocol; do not assume compatibility from shared UUIDs.

Capture the official app starting **before** it connects, including the handshake
and service discovery. Keep each experiment small and note action timestamps.
Observe one change at a time: for example mode A → B → A, charger disconnected →
connected → disconnected, or a known displayed speed returning to zero. A value
changing during motion alone does not establish its units or whether it is live
speed, average speed or a trip counter. Do not send power-off commands while
moving to test a speed candidate.

The existing [capture instructions](README.md#iphone-capture-packetlogger) describe
Apple PacketLogger. For a rooted Android phone the repository includes:

```sh
# Run from the repository root. This toggles the phone's Bluetooth.
scripts/android_capture.sh setup
# Record the official-app connection and your timestamped observations.
scripts/android_capture.sh pull
```

Analyze a specific local file, for example:

```sh
f2 decode /private/path/session.pklg --gatt
f2 decode /private/path/session.pklg --all
```

These commands implement the current Ninebot formats. No decoded frames can mean
missing handshake/credential, different framing, or an unsupported protocol; it
does not by itself prove a new encryption version. The current Python live tools
also use F2 defaults and do not load the Android profiles: adapt them before
using them against another model with different GATT or protocol details.

Some sessions require the credential from the original pairing. Capturing a new
pairing can replace it and invalidate another client's credential; perform that
only deliberately on your own scooter. Consult the existing
[pairing-capture notes](README.md#capturing-a-pairing) and keep all output private.

**Never commit or attach real `.pklg`/btsnoop captures, decoded session dumps,
passwords, names or serials to GitHub issues or PRs.** Captures can expose key
material. Removing visible identifiers does not make encrypted traffic safe to
publish. Submit written findings and constructed synthetic fixtures instead;
use synthetic keys and identities when creating crypto vectors.

## 2. Research in Python, then add a profile

Set up the reference tool from the repository root:

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -e '.[dev]'
```

Try new protocol work in `f2probe/` first and add offline regression coverage in
`tests/`. Document the board, register, length, byte order, sign, scale, units and
observations behind each new mapping. Track model-specific findings in `docs/`
and mark the hardware verification status in `docs/android-plan.md` using its
legend. Start with known read targets; do not dump unknown credential registers.

Copy `android/profiles/ninebot-f2-pro.json` to a new file. Choose a unique `id`
and model `name`, then replace discovery, GATT and register mappings with the
ones you established. See the [profile format](android/profiles/README.md).
Keep stable application keys for equivalent readings, such as `batt_pct`, `range`
and `speed`. Do not reuse a key for data with a different meaning.

Remove unsupported readings. Set `pairing` or `power` to `null` when unsupported
or not established. Put unknown readings behind `experimental: true`, preserve
raw values until their encoding is known, and include `verification` and
`evidence`. Transferring an F2 mapping to another model requires verification on
that model. The app offers model selection when multiple profiles are compiled
and remembers the selected profile for reconnects.

New special decoders go in Kotlin with matching Python behavior and tests. New
framing/crypto/auth implementations must add supported identifiers to the profile
compiler and route those selections to the actual implementation. Follow the
[porting workflow](docs/porting.md); changes to crypto/framing also need synthetic
vectors in `scripts/make_vectors.py` and both Android vector copies.

## 3. Keep writes and power control behind the safety guards

A JSON profile does not authorize writes. Every Android packet passes through
`FrameGuard`, whose allowlist is maintained independently. New allowed writes
must have matching changes and tests in Python `transport.assert_safe` and Kotlin
`FrameGuard`. Research and verify their exact target, command, register, payload
and behavior before enabling them. Do not submit a general raw-write bypass.

A profile with power support must define its state mapping, notifications, exact
on/off packets and explicit zero checks. An unreadable, malformed or nonzero
check must prevent power-off; writes are never automatically repeated to obtain
a state change. Report whether the speed meaning and interlock behavior have
actually been verified. The F2 Pro's `0x26` is still unknown: repeated official-app
polling and zero at rest are the entire basis for considering it a speed candidate.

## 4. Check the change and open a pull request

With the Python environment active and JDK 21 selected:

```sh
export JAVA_HOME=/path/to/jdk21
scripts/check_all.sh
cd android
./gradlew :app:assembleDebug :app:lintDebug
```

The core tests include profile compiler checks and shared crypto vectors. Add
coverage for meaningful new behavior and rejected inputs, using synthetic data.
For the Android profile path, manually check model selection, connect, one login,
readings, disconnect/reconnect and a restart with the saved model. Report results
for the specific firmware you tested; do not claim support for an entire family
from one device.

In your PR description include:

- Model, region, firmware and official-app versions tested.
- Whether it reuses the existing crypto/auth flow or adds an implementation.
- Supported features and deliberate omissions.
- A mapping/evidence table: application key, board/register/length, decoding,
  observed values and comparison with the official app.
- Hardware verification and unresolved assumptions, including speed semantics.
- Automated check results and manual connection/reconnection results.

The PR should contain the profile, any required implementations, synthetic tests
or vectors, and findings/status documentation. It must contain no real captures
or secrets. See [AGENTS.md](AGENTS.md) for the repository rules.

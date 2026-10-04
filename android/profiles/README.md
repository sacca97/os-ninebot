# Device profiles

Each `*.json` here is validated by `scripts/generate_profiles.py` and compiled into
`:core` as typed Kotlin. Generation is a dependency of `compileKotlin`; Python 3 is
required at build time. No profile parser or downloadable configuration runs on
the phone. `:core:test` also checks the compiler's validation paths.

To add a model using the existing Ninebot protocol, copy `ninebot-f2-pro.json`,
choose a unique `id` and display `name`, and replace its model-specific mappings.
Remove unsupported readings. Set `pairing` and/or `power` to `null` if unsupported.
Do not assume another model uses the F2 Pro addresses or power commands.

The scan screen offers model selection when more than one profile is compiled.
Manufacturer IDs and UART UUIDs identify a family, not necessarily a model. The
selected profile ID is saved alongside the last scooter; older saved devices use
the F2 Pro profile. A removed profile produces an error instead of silently
reconnecting with another model's mapping.

## Readings

`key` is the stable application identity (`batt_pct`, `range`, `speed`, etc.).
`device` is the target board address; `register` is its index; `bytes` is the
requested response length (1–20 for the currently implemented protocol).
`static` means once per connection; `fast` means each polling cycle; other
readings run every fifth cycle. `experimental` controls visibility and polling
through the existing Settings switch.

`decode` supports:

- `number`: signed/unsigned scalar, `byte_order` (`little_endian` default or
  `big_endian`), `scale`, `offset`, `precision`, and `unit`.
- `enum`: integer-to-label `values`; unknown values remain visible.
- `boolean`: optional bit `mask`, with `true` and `false` labels.
- `hex` and `raw`: optional `prefix` and hexadecimal `width`.
- `ascii`, `firmware`, `cells`, `temperatures`, and `current`: named Kotlin
  decoders for the existing formats. A new format needs a decoder implementation.

Scalar values are limited to four bytes; cell arrays use 16-bit elements.
Response lengths are checked before decoding. Unknown fields, decoder names,
protocol identifiers, invalid UUIDs, duplicate IDs/reading keys, and missing
power check references fail the build. `verification` and optional `evidence`
record the provenance; transferring a mapping to another model requires its own
verification. The stable keys drive common UI sections; extra experimental
readings appear on More → Scooter info with experimental readings enabled.

## Protocol and safety

`protocol`, `encryption`, `authentication`, and `pairing` select known Kotlin
implementations. Currently only the existing Ninebot framing, crypto and
INIT/AUTH flow, plus weak-mode pairing, are implemented. Different algorithms or
handshake flows require Kotlin code and synthetic test vectors, not arbitrary
expressions in JSON. No real names, serials, keys or captures belong in profiles.

`power` defines the state register, notification format, exact on/off packets,
and explicit raw zero checks by reading key. The current notification decoder
supports `[marker, register, state little-endian]`. The configured checks must
all return complete zero values before power-off. These checks are independent
of experimental UI visibility.

Every outgoing packet still passes through `FrameGuard`. Its independent
allowlist is deliberately not generated from profiles: JSON cannot authorize a
new write. New write support must be researched in Python first, with matching
changes and tests in Python `transport.assert_safe` and Kotlin `FrameGuard`.

## F2 Pro speed uncertainty

Controller `0x26` remains `speed_26`, an experimental **raw unknown value**.
The local evidence is `docs/f2pro-findings.md`, “What the official app polls”:
the official app repeatedly reads it, and it returned zero at rest. The findings
still describe its meaning as unknown; those observations alone do not identify
live speed or its scale. It is a candidate to observe while moving, not an
established speed reading. Controller `0x65` keeps the reference label
“Average speed”; live versus trip-average semantics are also unresolved.

The existing power-off interlock continues checking both registers. Neither its
behavior while moving nor this profile refactor has been verified on hardware.
Enable experimental readings to watch `0x26` on More → Scooter info. A confirmed
mapping can then receive the stable `speed` key, encoding and scale.

For the complete research-to-PR workflow, see
[CONTRIBUTING.md](../../CONTRIBUTING.md).

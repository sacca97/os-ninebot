# Device profiles

`scripts/generate_profiles.py` validates these JSON files and generates Kotlin
for `:core` during compilation. Python 3 is required; profiles are fixed at build time.

To add a model, copy `ninebot-f2-pro.json`, choose a unique `id` and `name`, and
replace its discovery, GATT and register mappings with verified values. Remove
unsupported readings; set unsupported `pairing` or `power` to `null`. Record
`verification` and `evidence`. Shared UUIDs do not establish model compatibility.

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

Scalars support up to four bytes; cells use 16-bit elements. Invalid fields,
UUIDs, decoder/protocol identifiers, duplicate IDs/keys and missing power-check
references fail the build. Response lengths are checked before decoding.

## Protocol and power

Profiles select the existing Ninebot framing, crypto, INIT/AUTH and weak-mode
pairing implementations. New protocols or decoders need Kotlin code and matching
Python tests/vectors; JSON cannot define them.

`power` defines state reads, notifications (`[marker, register, state_le16]`),
exact on/off packets and readings that must return complete zero values before
power-off. `FrameGuard` independently authorizes writes. Update it and Python
`transport.assert_safe` together, with tests, for any new allowed write.

❓ The F2 Pro profile refactor and motion interlock are unverified on hardware.
`speed` is live speed, confirmed by the owner on hardware. It remains raw until
scale and sign are confirmed against the dashboard. Power-off checks this register;
average speed (`0x65`) has been removed.
See [speed evidence](../../docs/f2pro-findings.md#live-speed-investigation-2026-10-04)
and the [development workflow](../../docs/porting.md).

# OpenRide / f2probe

Unofficial Android client and Python BLE tool for the Segway F2 Pro.
Reads telemetry, imports credentials, pairs, and controls power.
Not affiliated with Segway-Ninebot.

## Android

See [build and install instructions](android/README.md).

## Python CLI

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -e '.[dev]'
f2 --help
```

```text
f2 decode CAPTURE   Decode BLE traffic offline
f2 keys            Show, set, import or export the stored app key
f2 scan            Scan for scooters
f2 gatt            List GATT services (no writes)
f2 init            Send INIT and check whether a password is stored
f2 status          Authenticate and read status registers
f2 read INDEX      Authenticate and read raw registers
f2 power on|off    Power the scooter on or off
f2 pair --force    Replace the scooter's password
```

Use `f2 COMMAND --help` for options.

## Reference

- [Protocol findings](docs/f2pro-findings.md)
- [Device profiles](android/profiles/README.md)
- [Development workflow](docs/porting.md)

Never commit real captures, credentials, scooter names or serials.
No project licence has been chosen. Python depends on `miauth` (AGPL-3.0);
Android crypto is implemented from the specification and synthetic vectors.
The register map comes from [ownbee/ninebot-ble](https://github.com/ownbee/ninebot-ble)
(MIT, commit `1850351f5bce9627f612fd1141489ed7a575be61`).

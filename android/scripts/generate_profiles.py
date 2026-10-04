#!/usr/bin/env python3
"""Validate device JSON and emit Kotlin. Uses only Python's standard library."""
import json
import math
from pathlib import Path
import re
import sys
import uuid


def integer(value, low=0, high=255):
    result = int(value, 0) if isinstance(value, str) else value
    if type(result) is not int or not low <= result <= high:
        raise ValueError(f"integer outside {low}..{high}: {value!r}")
    return result


def quoted(value):
    if not isinstance(value, str):
        raise ValueError(f"expected string: {value!r}")
    return json.dumps(value, ensure_ascii=False).replace('$', '\\$')


def boolean(value):
    if type(value) is not bool:
        raise ValueError(f"expected boolean: {value!r}")
    return str(value).lower()


def enum(value, allowed, kotlin):
    if value not in allowed:
        raise ValueError(f"unknown {kotlin}: {value!r}")
    return f"{kotlin}.{value.upper().replace('-', '_')}"


def number(value):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError(f"invalid number: {value!r}")
    return repr(float(value))


def fields(obj, allowed):
    unknown = obj.keys() - set(allowed.split())
    if unknown:
        raise ValueError(f"unknown fields: {sorted(unknown)}")


def decoder(d, size):
    fields(d, 'kind signed scale offset precision unit prefix width mask true false values byte_order')
    kind = d['kind']
    if kind not in {'ascii', 'cells', 'temperatures', 'number', 'current', 'firmware', 'hex', 'enum', 'boolean', 'raw'}:
        raise ValueError(f"unknown decoder: {kind}")
    if kind not in {'ascii', 'cells', 'temperatures'} and size > 4:
        raise ValueError('scalar readings must be at most 4 bytes')
    if kind == 'cells' and size % 2:
        raise ValueError('cell readings require complete 16-bit values')
    if kind == 'firmware' and size != 2:
        raise ValueError('firmware decoder requires 2 bytes')
    order = d.get('byte_order', 'little_endian')
    if order not in {'little_endian', 'big_endian'}:
        raise ValueError(f"unknown byte order: {order}")
    args = [f'kind = {quoted(kind)}', f'byteOrder = {quoted(order)}']
    for key in ('signed',):
        if key in d: args.append(f'{key} = {boolean(d[key])}')
    for key in ('scale', 'offset'):
        if key in d: args.append(f'{key} = {number(d[key])}')
    for key, high in [('precision', 8), ('width', 16)]:
        if key in d: args.append(f'{key} = {integer(d[key], high=high)}')
    if 'mask' in d: args.append(f'mask = {integer(d["mask"], high=0xFFFFFFFF)}L')
    for key, kotlin in [('unit', 'unit'), ('prefix', 'prefix'), ('true', 'trueLabel'), ('false', 'falseLabel')]:
        if key in d: args.append(f'{kotlin} = {quoted(d[key])}')
    if 'values' in d:
        entries = ', '.join(f'{integer(k, low=-0x80000000, high=0xFFFFFFFF)}L to {quoted(v)}' for k, v in d['values'].items())
        args.append(f'values = mapOf({entries})')
    return 'ReadingDecoder(' + ', '.join(args) + ')'


def packet(p):
    fields(p, 'source device command register data')
    args = [str(integer(p[k])) for k in ('source', 'device', 'command', 'register')]
    args.append('byteArrayOf(' + ', '.join(f'{integer(b)}.toByte()' for b in p['data']) + ')')
    return 'Packet(' + ', '.join(args) + ')'


def profile(p):
    fields(p, 'id name protocol encryption authentication pairing discovery gatt readings power')
    if not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', p['id']):
        raise ValueError('invalid profile id')
    discovery = p['discovery']
    fields(discovery, 'manufacturer_id')
    g = p['gatt']
    fields(g, 'service write notify write_mode receive_mode mtu')
    for key in ('service', 'write', 'notify'): uuid.UUID(g[key])
    readings, keys = [], set()
    for r in p['readings']:
        fields(r, 'key label device register bytes decode static fast experimental verification evidence')
        key = r['key']
        if not re.fullmatch(r'[a-z][a-z0-9_]*', key) or key in keys:
            raise ValueError(f'invalid or duplicate reading key: {key}')
        keys.add(key)
        size = integer(r['bytes'], 1, 20)
        args = [quoted(key), quoted(r['label']), str(integer(r['device'])), str(integer(r['register'])), str((size + 1) // 2)]
        for flag in ('static', 'fast', 'experimental'):
            args.append(f'{flag} = {boolean(r.get(flag, False))}')
        args += [f'byteCount = {size}', f'verification = {quoted(r["verification"])}', f'evidence = {quoted(r.get("evidence", ""))}']
        args.append(f'decode = {{ raw -> require(raw.size == {size}) {{ "invalid reading length: {key}" }}; {decoder(r["decode"], size)}.decode(raw) }}')
        readings.append('Reg(' + ', '.join(args) + ')')
    power = 'null'
    if p.get('power'):
        pw = p['power']
        fields(pw, 'state notification on off zero_checks verification')
        st, nt = pw['state'], pw['notification']
        fields(st, 'device register bytes on_value')
        fields(nt, 'command marker')
        if not pw['zero_checks'] or len(set(pw['zero_checks'])) != len(pw['zero_checks']) or not set(pw['zero_checks']) <= keys:
            raise ValueError('power requires unique, existing zero-check readings')
        for r in p['readings']:
            if r['key'] in pw['zero_checks'] and (r['bytes'] > 4 or r['decode'].get('byte_order', 'little_endian') != 'little_endian'):
                raise ValueError('power zero checks require little-endian scalar readings')
        size = integer(st['bytes'], 1, 4)
        args = [str(integer(st['device'])), str(integer(st['register'])), str(size), f'{integer(st["on_value"], high=(1 << (size * 8)) - 1)}L', str(integer(nt['command'])), str(integer(nt['marker'])), packet(pw['on']), packet(pw['off']), 'listOf(' + ', '.join(quoted(k) for k in pw['zero_checks']) + ')', quoted(pw['verification'])]
        power = 'PowerProfile(' + ', '.join(args) + ')'
    args = [quoted(p['id']), quoted(p['name']), str(integer(discovery['manufacturer_id'], high=65535)), enum(p['protocol'], {'ninebot'}, 'Protocol'), enum(p['encryption'], {'ninebot-current'}, 'Encryption'), enum(p['authentication'], {'ninebot-init-auth'}, 'Authentication'), enum(p['pairing'], {'weak-mode'}, 'Pairing') if p.get('pairing') else 'null']
    gatt = [quoted(str(uuid.UUID(g[k]))) for k in ('service', 'write', 'notify')]
    gatt += [enum(g['write_mode'], {'with-response', 'without-response', 'with_response', 'without_response'}, 'WriteMode'), enum(g['receive_mode'], {'notification', 'indication'}, 'ReceiveMode'), str(integer(g['mtu'], 23, 517))]
    args += ['GattProfile(' + ', '.join(gatt) + ')', 'listOf(\n            ' + ',\n            '.join(readings) + '\n        )', power]
    return 'DeviceProfile(\n        ' + ',\n        '.join(args) + '\n    )'


def generate(source, target):
    profiles = []
    ids = set()
    for path in sorted(source.glob('*.json')):
        try:
            data = json.loads(path.read_text())
            if data['id'] in ids: raise ValueError(f'duplicate profile id: {data["id"]}')
            ids.add(data['id'])
            profiles.append(profile(data))
        except (ValueError, KeyError, TypeError) as error:
            raise ValueError(f'{path.name}: {error}') from error
    if 'ninebot-f2-pro' not in ids:
        raise ValueError('default ninebot-f2-pro profile is missing')
    code = '''// Generated from profiles/*.json. Do not edit.
package com.sacca.openride.core.profile

import com.sacca.openride.core.protocol.Packet
import com.sacca.openride.core.registers.Reg

object DeviceProfiles {
    val all: List<DeviceProfile> = listOf(
    ''' + ',\n    '.join(profiles) + '''
    )
    val default: DeviceProfile get() = byId("ninebot-f2-pro")
    fun byId(id: String): DeviceProfile = all.firstOrNull { it.id == id }
        ?: error("Unknown device profile: $id")
}
'''
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(code)


if __name__ == '__main__':
    try:
        generate(Path(sys.argv[1]), Path(sys.argv[2]))
    except ValueError as error:
        sys.exit(str(error))

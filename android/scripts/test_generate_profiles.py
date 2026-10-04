"""Checks the profile compiler's rejection paths using synthetic edits."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from generate_profiles import generate, profile


class ProfileCompilerTest(unittest.TestCase):
    def setUp(self):
        self.base = json.loads((Path(__file__).resolve().parents[1] / 'profiles/ninebot-f2-pro.json').read_text())

    def test_model_specific_address_and_scale_are_emitted(self):
        model = copy.deepcopy(self.base)
        model['readings'][4]['register'] = '0x42'
        model['readings'][4]['decode']['scale'] = 0.5
        code = profile(model)
        self.assertIn('"batt_pct", "Battery", 34, 66', code)
        self.assertIn('scale = 0.5', code)

    def test_invalid_profiles_fail(self):
        for section, key, value in [
            ('gatt', 'service', 'invalid-uuid'),
            ('gatt', 'write_mode', 'anything'),
            ('power', 'zero_checks', ['missing_reading']),
        ]:
            with self.subTest(key=key):
                model = copy.deepcopy(self.base)
                model[section][key] = value
                with self.assertRaises(ValueError): profile(model)
        for field in ['protocol', 'encryption', 'authentication', 'pairing']:
            with self.subTest(field=field):
                model = copy.deepcopy(self.base)
                model[field] = 'unknown'
                with self.assertRaises(ValueError): profile(model)
        for edit in [
            lambda r: r.update(bytes=21),
            lambda r: r.update(key='batt_pct'),
            lambda r: r['decode'].update(kind='unknown'),
            lambda r: r['decode'].update(scale=float('nan')),
            lambda r: r['decode'].update(sacle=0.1),
        ]:
            model = copy.deepcopy(self.base)
            edit(model['readings'][0])
            with self.assertRaises(ValueError): profile(model)

    def test_duplicate_ids_fail_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ['a', 'b']:
                (root / f'{name}.json').write_text(json.dumps(self.base))
            with self.assertRaisesRegex(ValueError, 'duplicate profile id'):
                generate(root, root / 'generated.kt')


if __name__ == '__main__':
    unittest.main()

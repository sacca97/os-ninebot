import pytest

from f2probe.identity import model_from_serial


@pytest.mark.parametrize('prefixes, model', [
    ('NAGR NAGV NAGU NAGT NAGS', 'Ninebot F2 Pro'),
    ('NAGF NAGK NAGJ NAGH NAGG', 'Ninebot F2 Plus'),
    ('NAGA NAGE NAGD NAGC NAGB', 'Ninebot F2'),
])
def test_model_prefixes(prefixes, model):
    for prefix in prefixes.split():
        serial = prefix + 'A0000A0000'  # Synthetic serial; no real device identity.
        assert model_from_serial(serial) == model
        assert model_from_serial(serial.lower()) == model


@pytest.mark.parametrize('serial', ['', 'NAGU', 'NAGU notserial', 'NAGUA0000A0000!', 'XXXXA0000A0000'])
def test_unknown_and_malformed_serials_are_not_guessed(serial):
    assert model_from_serial(serial) is None

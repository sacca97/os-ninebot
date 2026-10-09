"""Display-only model lookup; never substitute the result for crypto identity.

F2 prefix groups from firmware research by VooDooShamane (2024-05-21):
https://rollerplausch.com/threads/f2-series-informationen-firmware-hardware-tuning.11004/
See docs/f2pro-findings.md for provenance and verification status.
"""


def model_from_serial(serial: str) -> str | None:
    value = serial.upper()
    if len(value) != 14 or not value.isascii() or not value.isalnum():
        return None
    prefix = value[:4]
    if prefix in ("NAGR", "NAGV", "NAGU", "NAGT", "NAGS"):
        return "Ninebot F2 Pro"
    if prefix in ("NAGF", "NAGK", "NAGJ", "NAGH", "NAGG"):
        return "Ninebot F2 Plus"
    if prefix in ("NAGA", "NAGE", "NAGD", "NAGC", "NAGB"):
        return "Ninebot F2"
    return None

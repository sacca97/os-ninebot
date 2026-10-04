"""Regenerates the launcher icon layers from branding/icon-openride-source.png (a transparent symbol).

    pip install pillow && python branding/make_icons.py     (run from android/)

Adaptive icon: the 108 dp canvas is masked by the launcher (circle, squircle, ...) and only the centre 66 dp circle is guaranteed
visible, so the symbol is scaled until its bounding-box diagonal fits inside that circle.
"""
from pathlib import Path

from PIL import Image

HERE = Path(__file__).parent
RES = HERE.parent / "app" / "src" / "main" / "res"
BG = (0x0F, 0x76, 0x6E)  # teal; also written to ic_launcher_background.xml
CANVAS = 512  # 108 dp layer
SAFE = CANVAS * 66 / 108

src = Image.open(HERE / "icon-openride-source.png").convert("RGBA")
sym = src.crop(src.split()[3].point(lambda v: 255 if v > 10 else 0).getbbox())
k = SAFE / (sym.width**2 + sym.height**2) ** 0.5
sym = sym.resize((round(sym.width * k), round(sym.height * k)), Image.LANCZOS)

fg = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
fg.alpha_composite(sym, ((CANVAS - sym.width) // 2, (CANVAS - sym.height) // 2))

mono = Image.new("RGBA", fg.size, (0, 0, 0, 0))  # themed icons: any opaque shape, the system tints it
mono.putalpha(fg.split()[3])

(RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
fg.save(RES / "drawable-nodpi" / "ic_launcher_foreground.png")
mono.save(RES / "drawable-nodpi" / "ic_launcher_monochrome.png")

# Full-bleed 512 px icon for a store listing, and a round-masked preview.
full = Image.new("RGBA", fg.size, BG + (255,))
full.alpha_composite(fg)
full.convert("RGB").save(HERE / "icon-512.png")
mask = Image.new("L", fg.size, 0)
from PIL import ImageDraw  # noqa: E402

ImageDraw.Draw(mask).ellipse((0, 0, CANVAS - 1, CANVAS - 1), fill=255)
prev = Image.new("RGBA", fg.size, (255, 255, 255, 255))
prev.paste(full, mask=mask)
prev.save(HERE / "icon-preview-round.png")
print("symbol", sym.size, "on", CANVAS, "- done")

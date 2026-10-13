"""Validate installed textures and make a nearest-neighbor resource contact sheet."""
from pathlib import Path
import textwrap
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
TEXTURES = ROOT / 'src/main/resources/assets/futa_gtnh/textures'
files = sorted(TEXTURES.rglob('*.png'))
columns, cell_w, cell_h = 7, 160, 164
sheet = Image.new('RGB', (columns * cell_w, ((len(files) + columns - 1) // columns) * cell_h), '#20242b')
draw = ImageDraw.Draw(sheet)
for index, path in enumerate(files):
    with Image.open(path) as image:
        assert image.size == (32, 32), (path, image.size)
        assert image.mode == 'RGBA', (path, image.mode)
        assert image.getbbox() is not None, path
        if path.parent.name == 'items':
            assert set(image.getchannel('A').getdata()) <= {0, 255}, path
        x, y = index % columns * cell_w + 16, index // columns * cell_h + 4
        for row in range(16):
            for col in range(16):
                color = '#464b54' if (row + col) % 2 else '#353b44'
                draw.rectangle((x + col * 8, y + row * 8, x + col * 8 + 7, y + row * 8 + 7), fill=color)
        enlarged = image.resize((128, 128), Image.Resampling.NEAREST)
        sheet.paste(enlarged, (x, y), enlarged)
        label = '\n'.join(textwrap.wrap(path.stem, 24))
        draw.multiline_text((x - 6, y + 130), label, fill='#eef2f8', spacing=1)
output = ROOT / 'docs/images/1.29.1-textures.png'
output.parent.mkdir(parents=True, exist_ok=True)
sheet.save(output)
print(f'Texture validation: {len(files)} RGBA 32x32 resources passed')
print(output)

"""Pack ImageGen atlases into Minecraft resources without changing their artwork.

Usage: python tools/texture/import_gt_ae_atlas.py BLOCK_ATLAS ITEM_ATLAS DISASSEMBLER_ATLAS [SWIFT_STEP_PISTOL]
Only grid extraction, nearest-neighbor resizing and alpha thresholding are applied.
UI nine-slices are deterministic layout assets, independent of generated artwork.
"""
from pathlib import Path
import sys
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
TEXTURES = ROOT / 'src/main/resources/assets/futa_gtnh/textures'
BLOCKS = [
    'shared_terminal_side', 'shared_terminal_top',
    'shared_terminal_face_down', 'shared_terminal_face_up',
    'shared_terminal_face_north', 'shared_terminal_face_south',
    'shared_terminal_face_west', 'shared_terminal_face_east',
    'loot_machine_front', 'loot_machine_side', 'loot_machine_top', 'io_node_core',
    'io_node_arm', 'io_node_cap', 'ae_signal_transmitter', 'me_super_dock',
]
ITEMS = ['swift_step', 'solar_descaler', 'locator_wand', 'minigame_helper',
         'shared_cell_item', 'shared_cell_fluid', 'ae_signal_receiver_cover', 'settings_button']


def split(path, names, columns, rows, folder):
    atlas = Image.open(path).convert('RGBA')
    for i, name in enumerate(names):
        # Swift Step has a separate pistol sprite; the old atlas contains an obsolete amulet.
        if folder == 'items' and name == 'swift_step':
            continue
        column, row = i % columns, i // columns
        box = (round(column * atlas.width / columns), round(row * atlas.height / rows),
               round((column + 1) * atlas.width / columns), round((row + 1) * atlas.height / rows))
        tile = atlas.crop(box).resize((32, 32), Image.Resampling.NEAREST)
        if folder == 'items':
            tile.putalpha(tile.getchannel('A').point(lambda value: 255 if value >= 128 else 0))
        destination = TEXTURES / folder / (name + '.png')
        if name == 'settings_button':
            destination = TEXTURES / 'gui/settings_button.png'
        destination.parent.mkdir(parents=True, exist_ok=True)
        tile.save(destination)


def install_swift_step(path):
    tile = Image.open(path).convert('RGBA').resize((32, 32), Image.Resampling.NEAREST)
    tile.putalpha(tile.getchannel('A').point(lambda value: 255 if value >= 128 else 0))
    tile.save(TEXTURES / 'items/swift_step.png')


def ui():
    folder = TEXTURES / 'gui/mui2'
    folder.mkdir(parents=True, exist_ok=True)
    for name, center, accent in [
        ('panel', '#555c68', '#9280b7'), ('button', '#555d69', '#949eac'),
        ('button_hover', '#65617f', '#b894df'), ('button_disabled', '#383d46', '#646975'),
        ('slot', '#2c3038', '#707886'),
    ]:
        tile = Image.new('RGBA', (32, 32), '#181c23')
        draw = ImageDraw.Draw(tile)
        draw.rectangle((1, 1, 30, 30), fill=accent)
        draw.rectangle((2, 2, 29, 29), fill=center)
        draw.line((2, 29, 29, 29), fill='#303640')
        draw.line((29, 2, 29, 29), fill='#303640')
        tile.save(folder / (name + '.png'))


if __name__ == '__main__':
    if len(sys.argv) not in (4, 5):
        raise SystemExit(__doc__)
    if len(sys.argv) == 5:
        install_swift_step(sys.argv[4])
    elif not (TEXTURES / 'items/swift_step.png').exists():
        raise SystemExit('SWIFT_STEP_PISTOL is required when no installed pistol sprite exists')
    split(sys.argv[1], BLOCKS, 4, 4, 'blocks')
    split(sys.argv[2], ITEMS, 4, 2, 'items')
    split(sys.argv[3], ['disassembler', 'disassembler_active'], 2, 1, 'blocks')
    (TEXTURES / 'blocks/ae_signal_receiver_cover.png').write_bytes(
        (TEXTURES / 'items/ae_signal_receiver_cover.png').read_bytes())
    ui()

"""Draws the mod icon: a structure's bounding box (a wireframe cube seen from above at an angle, the way the boxes look
in game) around a small swamp hut, on dark ground, as 32x32 pixel art scaled to 128x128.

Run from the repo root:  python -I tools/make-icon.py
Writes src/main/resources/assets/bounding-box-viewer/icon.png. No dependencies beyond the standard library.
"""
import struct
import sys
import zlib
from pathlib import Path

SIZE = 32
SCALE = 4
OUT = Path("src/main/resources/assets/bounding-box-viewer/icon.png")

GROUND = [(0x1E, 0x2A, 0x1C), (0x22, 0x30, 0x1F), (0x1A, 0x25, 0x18)]
WOOD = (0x6B, 0x4A, 0x2E)
WOOD_DARK = (0x4A, 0x32, 0x1E)
ROOF = (0x3E, 0x2B, 0x1B)
DOOR = (0x2A, 0x1C, 0x10)
BOX = (0xC0, 0x66, 0xFF)
BOX_BACK = (0x7A, 0x3D, 0xA8)
SHADE = (0x4A, 0x2D, 0x63)

img = [[None] * SIZE for _ in range(SIZE)]


def px(x, y, c):
    if 0 <= x < SIZE and 0 <= y < SIZE:
        img[y][x] = c


def line(x0, y0, x1, y1, c):
    """Bresenham, so the edges stay one pixel wide."""
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        px(x0, y0, c)
        if x0 == x1 and y0 == y1:
            return
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


# Speckled dark ground.
for y in range(SIZE):
    for x in range(SIZE):
        px(x, y, GROUND[(x * 7 + y * 13 + x * y) % len(GROUND)])

# The cube: front face 18x16 at (4, 12), back face shifted up-right by (8, -7).
FX0, FY0, FX1, FY1 = 4, 12, 21, 27
OX, OY = 7, -7
BX0, BY0, BX1, BY1 = FX0 + OX, FY0 + OY, FX1 + OX, FY1 + OY

# Shade inside the front face, where the hut's mobs spawn.
for y in range(FY0 + 1, FY1):
    for x in range(FX0 + 1, FX1):
        if (x + y) % 2 == 0:
            px(x, y, SHADE)

# The hut on stilts, inside the box.
for y in range(19, 24):
    for x in range(8, 18):
        px(x, y, WOOD if (x + y) % 3 else WOOD_DARK)
for x in range(7, 19):
    px(x, 18, ROOF)
for x in range(8, 18):
    px(x, 17, ROOF)
for y in range(20, 24):
    px(12, y, DOOR)
    px(13, y, DOOR)
for x in (8, 17):
    for y in range(24, 27):
        px(x, y, WOOD_DARK)

# Back edges first (dimmer), then the front ones over them.
line(BX0, BY0, BX1, BY0, BOX_BACK)
line(BX1, BY0, BX1, BY1, BOX_BACK)
line(BX0, BY0, BX0, BY1, BOX_BACK)
line(BX0, BY1, BX1, BY1, BOX_BACK)
line(FX0, FY0, BX0, BY0, BOX)
line(FX1, FY0, BX1, BY0, BOX)
line(FX1, FY1, BX1, BY1, BOX)
line(FX0, FY1, BX0, BY1, BOX_BACK)
for x0, y0, x1, y1 in ((FX0, FY0, FX1, FY0), (FX1, FY0, FX1, FY1), (FX0, FY1, FX1, FY1), (FX0, FY0, FX0, FY1)):
    line(x0, y0, x1, y1, BOX)


def write_png(path, pixels, scale):
    rows = []
    for row in pixels:
        out = bytearray()
        for c in row:
            rgba = (0, 0, 0, 0) if c is None else (*c, 255)
            out += bytes(rgba) * scale
        rows += [b"\x00" + bytes(out)] * scale
    width = len(pixels[0]) * scale
    height = len(pixels) * scale

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
    png += chunk(b"IEND", b"")
    path.write_bytes(png)


if __name__ == "__main__":
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else OUT
    out.parent.mkdir(parents=True, exist_ok=True)
    write_png(out, img, SCALE)
    print(f"wrote {out} ({SIZE * SCALE}x{SIZE * SCALE})")

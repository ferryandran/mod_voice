"""
generate_3d_mesh.py
Menyiapkan texture render untuk logo Kopdes 3D (KopdesLogoRenderer).

Mesh 3D (sisi extrude) TIDAK lagi di-bake ke Java. KopdesLogoMesh membangunnya
saat runtime langsung dari kopdes_logo_3d.png, jadi muka depan dan dinding sisi
selalu memakai mask alpha yang sama persis (tidak ada celah / sisi melayang).

Script ini:
  1. crop margin transparan kopdes_logo.png, pad jadi persegi
  2. resize ke GRID x GRID (premultiplied alpha, tanpa fringe gelap)
  3. alpha dibuat biner (0 / 255) supaya cutout shader == mask mesh

Jalankan dari voicedoor_mod/ setiap kali kopdes_logo.png diganti:
    python generate_3d_mesh.py
"""
import numpy as np
from PIL import Image

TEXTURE_IN  = r"src\main\resources\assets\voicedoor\textures\block\kopdes_logo.png"
TEXTURE_OUT = r"src\main\resources\assets\voicedoor\textures\block\kopdes_logo_3d.png"

GRID         = 128   # resolusi texture/mesh untuk panel 5x5 blok (~25 px per blok)
ALPHA_THRESH = 128   # alpha >= ini dianggap padat

img = Image.open(TEXTURE_IN).convert("RGBA")

# 1. Crop margin transparan, lalu pad ke persegi (logo tetap di tengah)
bbox = img.getchannel("A").point(lambda a: 255 if a >= ALPHA_THRESH else 0).getbbox()
if bbox:
    img = img.crop(bbox)
side = max(img.size)
square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
square.paste(img, ((side - img.width) // 2, (side - img.height) // 2))

# 2. Resize dengan premultiplied alpha
small = square.convert("RGBa").resize((GRID, GRID), Image.LANCZOS).convert("RGBA")

# 3. Alpha biner
arr = np.array(small)
solid = arr[:, :, 3] >= ALPHA_THRESH
arr[:, :, 3] = np.where(solid, 255, 0)
arr[~solid, :3] = 0

Image.fromarray(arr).save(TEXTURE_OUT)
print(f"Saved {TEXTURE_OUT} ({GRID}x{GRID}, {solid.sum()} pixel padat)")

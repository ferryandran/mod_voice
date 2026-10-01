"""
Cara menjalankan: python generate_textures.py
Script ini membuat texture PNG sederhana untuk VoiceDoor block dan item.
Membutuhkan Pillow: pip install Pillow
"""

from PIL import Image, ImageDraw, ImageFont
import os

SIZE = 16  # Minecraft texture size

def make_door_panel(filename, base_color, accent_color, is_top=True):
    """Buat texture panel pintu dengan desain metalik dan simbol mikrofon."""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Background panel
    draw.rectangle([0, 0, SIZE-1, SIZE-1], fill=base_color)

    # Frame/border
    frame_color = tuple(max(0, c - 30) for c in base_color[:3]) + (255,)
    draw.rectangle([0, 0, SIZE-1, 1], fill=frame_color)
    draw.rectangle([0, SIZE-2, SIZE-1, SIZE-1], fill=frame_color)
    draw.rectangle([0, 0, 1, SIZE-1], fill=frame_color)
    draw.rectangle([SIZE-2, 0, SIZE-1, SIZE-1], fill=frame_color)

    # Garis dekoratif horizontal
    line_color = tuple(min(255, c + 20) for c in base_color[:3]) + (255,)
    if is_top:
        draw.line([(2, 4), (SIZE-3, 4)], fill=line_color)
        draw.line([(2, 6), (SIZE-3, 6)], fill=line_color)
        # Simbol mikrofon kecil di tengah (circle)
        draw.ellipse([(5, 8), (10, 13)], outline=accent_color, width=1)
        draw.line([(7, 13), (8, 15)], fill=accent_color)  # stem
        draw.line([(5, 15), (10, 15)], fill=accent_color)  # base
    else:
        draw.line([(2, 9), (SIZE-3, 9)], fill=line_color)
        draw.line([(2, 11), (SIZE-3, 11)], fill=line_color)
        # Simbol kunci kecil
        draw.ellipse([(5, 2), (10, 7)], outline=accent_color, width=1)
        draw.rectangle([(6, 6), (9, 10)], fill=accent_color)

    img.save(filename)
    print(f"Created: {filename}")


def make_item_texture(filename):
    """Buat texture item pintu (tampak depan)."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Warna pintu metalik
    metal = (100, 120, 140, 255)
    metal_dark = (70, 90, 110, 255)
    metal_light = (130, 150, 170, 255)
    accent = (0, 200, 255, 255)

    # Gambar pintu kecil dalam 16x16
    # Badan pintu
    draw.rectangle([3, 0, 12, 15], fill=metal)
    # Panel atas
    draw.rectangle([4, 1, 11, 6], fill=metal_dark)
    draw.rectangle([5, 2, 10, 5], fill=metal_light)
    # Panel bawah
    draw.rectangle([4, 8, 11, 14], fill=metal_dark)
    draw.rectangle([5, 9, 10, 13], fill=metal_light)
    # Handle
    draw.ellipse([(10, 7), (12, 9)], fill=accent)
    # Simbol mikrofon kecil di panel atas
    draw.ellipse([(6, 2), (9, 4)], outline=accent, width=1)

    img.save(filename)
    print(f"Created: {filename}")


# Paths, relatif terhadap lokasi script ini.
#
# Sebelumnya di-hardcode ke c:\Users\Lenovo\Documents\... sehingga script hanya jalan di
# satu komputer.
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
base_path = os.path.join(SCRIPT_DIR, "src", "main", "resources", "assets", "voicedoor", "textures")
block_path = os.path.join(base_path, "block")
item_path = os.path.join(base_path, "item")
os.makedirs(block_path, exist_ok=True)
os.makedirs(item_path, exist_ok=True)

# Warna metalik dengan aksen cyan (menunjukkan teknologi/suara)
METAL_BASE = (90, 110, 130, 255)
ACCENT_CYAN = (0, 200, 255, 255)

# Buat textures
make_door_panel(os.path.join(block_path, "voice_door_top.png"), METAL_BASE, ACCENT_CYAN, is_top=True)
make_door_panel(os.path.join(block_path, "voice_door_bottom.png"), METAL_BASE, ACCENT_CYAN, is_top=False)
make_item_texture(os.path.join(item_path, "voice_door.png"))

print("\nSelesai! Texture berhasil dibuat.")
print("Jalankan script ini lagi setiap kali ingin memperbarui texture.")

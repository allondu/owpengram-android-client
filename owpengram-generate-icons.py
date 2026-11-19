from pathlib import Path
from PIL import Image, ImageDraw
import sys

ROOT = Path(__file__).resolve().parent
LIGHT_SRC, TRIAD_LIGHT_SRC, TRIAD_DARK_SRC = sys.argv[1:4]

def load(path):
    return Image.open(path).convert("RGB")

def fill(im, seed, color):
    ImageDraw.floodfill(im, seed, color, thresh=30)

light = load(LIGHT_SRC)
triad_light = load(TRIAD_LIGHT_SRC)
triad_dark = load(TRIAD_DARK_SRC)

# The dark ribbon variant is the light artwork with its outer and inner white
# areas turned black. The white outline symbols stay white.
dark = light.copy()
fill(dark, (0, 0), (0, 0, 0))
fill(dark, (512, 380), (0, 0, 0))

fill(light, (0, 0), (255, 255, 255))
fill(triad_light, (0, 0), (255, 255, 255))
fill(triad_dark, (0, 0), (0, 0, 0))

variants = {
    "light": light,
    "dark": dark,
    "triad_light": triad_light,
    "triad_dark": triad_dark,
}
app_densities = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
legacy_densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
legacy_names = {
    "light": "ic_launcher_sa",
    "dark": "icon_2_launcher_sa",
    "triad_light": "icon_3_launcher_sa",
    "triad_dark": "icon_4_launcher_sa",
}

base = ROOT / "TMessagesProj/src/main/res"
standalone = ROOT / "TMessagesProj_AppStandalone/src/main/res"
for variant, im in variants.items():
    for density, size in app_densities.items():
        out = base / f"mipmap-{density}" / f"app_icon_{variant}.png"
        im.resize((size, size), Image.LANCZOS).save(out, optimize=True)
    for density, size in legacy_densities.items():
        out = standalone / f"mipmap-{density}" / f"{legacy_names[variant]}.png"
        im.resize((size, size), Image.LANCZOS).save(out, optimize=True)

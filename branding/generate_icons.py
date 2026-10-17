"""Render launcher icons and Android TV banners from the shared brand mark.

Requires Pillow. The Android adaptive foreground is kept as a vector in the
phone app; this script supplies the legacy icons and TV raster assets.
"""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
INK = (20, 47, 67)
WHITE = (243, 251, 252)
MINT = (99, 223, 202)
SCALE = 6


def mark(draw, x, y, size):
    """Draw the same 108-unit screen/play mark used by the adaptive icon."""
    def box(a, b, c, d):
        return (x + a * size / 108, y + b * size / 108,
                x + c * size / 108, y + d * size / 108)

    draw.rounded_rectangle(box(27, 28, 81, 74), radius=10 * size / 108,
                           outline=WHITE, width=max(2, round(5 * size / 108)))
    draw.polygon([(x + 48 * size / 108, y + 40 * size / 108),
                  (x + 48 * size / 108, y + 62 * size / 108),
                  (x + 65 * size / 108, y + 51 * size / 108)], fill=MINT)
    draw.rounded_rectangle(box(44, 79, 64, 83), radius=2 * size / 108,
                           fill=WHITE)


def icon(size, round_background=False):
    canvas = Image.new("RGBA", (size * SCALE, size * SCALE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    edge = size * SCALE - 1
    if round_background:
        draw.ellipse((0, 0, edge, edge), fill=INK)
    else:
        draw.rounded_rectangle((0, 0, edge, edge), radius=size * SCALE * .22,
                               fill=INK)
    mark(draw, 0, 0, size * SCALE)
    return canvas.resize((size, size), Image.Resampling.LANCZOS)


def font(size, bold=False):
    candidates = [
        Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"),
        Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc" if bold else
             "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
    ]
    for path in candidates:
        if path.exists():
            return ImageFont.truetype(str(path), size)
    raise RuntimeError("A Chinese font is required to render the TV banners")


def banner(size, endpoint):
    width, height = size
    image = Image.new("RGBA", (width * SCALE, height * SCALE), INK + (255,))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 6 * SCALE, height * SCALE), fill=MINT)
    mark(draw, 10 * SCALE, 36 * SCALE, 100 * SCALE)
    draw.text((124 * SCALE, 57 * SCALE), "禁止熬夜投屏专用",
              fill=WHITE, font=font(19 * SCALE, bold=True))
    draw.text((124 * SCALE, 91 * SCALE), endpoint,
              fill=MINT, font=font(17 * SCALE, bold=True))
    draw.text((124 * SCALE, 119 * SCALE), "本地播放 · 轻松投屏",
              fill=(180, 205, 216), font=font(11 * SCALE))
    return image.resize(size, Image.Resampling.LANCZOS)


def main():
    density = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2,
               "xxhdpi": 3, "xxxhdpi": 4}
    for module, endpoint in (("app", "手机端"), ("tv_component", "电视端")):
        res = ROOT / module / "src/main/res"
        for qualifier, factor in density.items():
            folder = res / f"mipmap-{qualifier}"
            size = round(48 * factor)
            icon(size).save(folder / "ic_launcher.png")
            if module == "app":
                icon(size, round_background=True).save(folder / "ic_launcher_round.png")
            banner((round(320 * factor), round(180 * factor)), endpoint).save(
                folder / "ic_leanback_banner.png")
        if module == "app":
            banner((240, 135), endpoint).save(
                res / "mipmap-ldpi/ic_leanback_banner.png")

    preview = Image.new("RGB", (760, 310), "#EAF2F6")
    preview.paste(icon(160), (34, 34), icon(160))
    preview.paste(icon(160, round_background=True), (212, 34),
                  icon(160, round_background=True))
    preview.paste(banner((320, 180), "电视端"), (402, 24))
    preview.save(ROOT / "branding/brand-preview.png")


if __name__ == "__main__":
    main()

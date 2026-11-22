#!/usr/bin/env python3
"""Generate all Android launcher icon sizes from design/icon_source.png.

Run from the repository root:
    python design/generate_icons.py

Outputs (WebP, lossless):
  res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.webp            48..192
  res/mipmap-*/ic_launcher_round.webp                                     48..192
  res/mipmap-*/ic_launcher_foreground.webp                                108..432
  res/mipmap-*/ic_launcher_background.webp                                108..432
  res/mipmap-*/ic_launcher_monochrome.webp                                108..432
Plus app/src/main/ic_launcher-playstore.png (512x512).
"""

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SRC_PATH = ROOT / "design" / "icon_source.png"
RES = ROOT / "app" / "src" / "main" / "res"

# Android launcher icon sizes per density: (legacy/round, adaptive layer)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

TRIM = 8  # border trim in source pixels (removes edge artifacts)

# Background gradient sampled from the source artwork (top -> middle -> bottom).
BG_TOP = np.array([38, 38, 37], dtype=np.float32)
BG_MID = np.array([26, 25, 22], dtype=np.float32)
BG_BOTTOM = np.array([14, 11, 7], dtype=np.float32)

# The glyph in the source artwork sits at the edge of a 72dp adaptive-icon mask.
# Map the artwork onto a slightly smaller circle so no tip is clipped:
#   adaptive: visible area = 70dp of the 108dp layer
#   round:    visible area = 95% of the icon canvas
ADAPTIVE_VISIBLE = 70 / 108
ROUND_VISIBLE = 0.95


def load_source() -> Image.Image:
    img = Image.open(SRC_PATH).convert("RGBA")
    w, h = img.size
    # The source has a few light pixels baked into its outer border; trim them off.
    return img.crop((TRIM, TRIM, w - TRIM, h - TRIM))


def resize_premultiplied(img: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Resize RGBA without dark halos around the anti-aliased edges."""
    arr = np.asarray(img).astype(np.float32) / 255.0
    alpha = arr[..., 3:4]
    pm = np.concatenate([arr[..., :3] * alpha, alpha], axis=-1)
    resized = Image.fromarray((pm * 255.0).round().astype(np.uint8), "RGBA")
    resized = resized.resize(size, Image.LANCZOS)
    out = np.asarray(resized).astype(np.float32) / 255.0
    a = out[..., 3:4]
    rgb = np.zeros_like(out[..., :3])
    np.divide(out[..., :3], a, out=rgb, where=a > 1e-6)
    return Image.fromarray(
        (np.concatenate([np.clip(rgb, 0.0, 1.0), a], axis=-1) * 255.0).round().astype(np.uint8),
        "RGBA",
    )


def extract_glyph(src: Image.Image) -> tuple[Image.Image, np.ndarray, tuple[float, float]]:
    """Cut the play glyph (plus its glow) out of the artwork.

    The backdrop is neutral grey while the glyph is saturated blue/cyan, so
    chroma cleanly separates them without picking up background texture.
    Returns the cropped RGBA glyph, its strong-opacity bounding box (in glyph
    coordinates) and that box's centre in source coordinates.
    """
    arr = np.asarray(src).astype(np.float32)
    r, g, b = arr[..., 0], arr[..., 1], arr[..., 2]
    chroma = np.maximum(b - r, g - r)
    alpha = np.clip((chroma - 10.0) / 130.0, 0.0, 1.0) * (arr[..., 3] / 255.0)

    # Per-row background colour from the untouched left margin.
    bg_row = np.median(arr[:, 40:250, :3], axis=1)
    bg = np.repeat(bg_row[:, None, :], arr.shape[1], axis=1)
    safe = np.maximum(alpha, 1e-3)[..., None]
    rgb = np.clip(bg + (arr[..., :3] - bg) / safe, 0.0, 255.0)

    glyph = Image.fromarray(
        np.dstack([rgb, alpha * 255.0]).round().astype(np.uint8), "RGBA"
    )

    ys, xs = np.where(alpha > 0.5)
    strong = np.array([xs.min(), ys.min(), xs.max() + 1, ys.max() + 1], dtype=np.float64)
    strongly_centred = (
        (strong[0] + strong[2]) / 2.0,
        (strong[1] + strong[3]) / 2.0,
    )

    # Crop with a margin so the glow is not cut off.
    margin = int(round(src.width * 0.03))
    box = (
        max(0, int(xs.min()) - margin),
        max(0, int(ys.min()) - margin),
        min(src.width, int(xs.max()) + 1 + margin),
        min(src.height, int(ys.max()) + 1 + margin),
    )
    strong -= np.array([box[0], box[1], box[0], box[1]])
    return glyph.crop(box), strong, strongly_centred


def background(size: int) -> Image.Image:
    """Vertical dark gradient matching the source artwork's backdrop."""
    t = np.linspace(0.0, 1.0, size, dtype=np.float32)[:, None, None]
    upper = BG_TOP + (BG_MID - BG_TOP) * (t / 0.5)
    lower = BG_MID + (BG_BOTTOM - BG_MID) * ((t - 0.5) / 0.5)
    col = np.where(t < 0.5, upper, lower)
    col = np.repeat(col, size, axis=1)
    return Image.fromarray(np.clip(col, 0, 255).round().astype(np.uint8), "RGB")


def composed_icon(
    canvas: Image.Image,
    glyph: Image.Image,
    strong: np.ndarray,
    glyph_centre: tuple[float, float],
    source_size: int,
    visible: float,
) -> Image.Image:
    """Scale/place the glyph so it keeps the source artwork's composition."""
    size = canvas.width
    scale = size * visible / source_size
    cx = (strong[0] + strong[2]) / 2.0
    cy = (strong[1] + strong[3]) / 2.0

    glyph_w = round(glyph.width * scale)
    glyph_h = round(glyph.height * scale)
    scaled = resize_premultiplied(glyph, (glyph_w, glyph_h))

    target_x = size / 2.0 + (glyph_centre[0] - source_size / 2.0) * scale
    target_y = size / 2.0 + (glyph_centre[1] - source_size / 2.0) * scale
    paste_x = round(target_x - cx * scale)
    paste_y = round(target_y - cy * scale)

    out = canvas.convert("RGBA").copy()
    out.alpha_composite(scaled, (paste_x, paste_y))
    return out


def circle_mask(size: int) -> Image.Image:
    """Anti-aliased circular alpha mask."""
    ss = 4
    big = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(big).ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    return big.resize((size, size), Image.LANCZOS)


def monochrome(glyph: Image.Image) -> Image.Image:
    """Crisp white silhouette of the glyph for Android 13 themed icons."""
    arr = np.asarray(glyph).astype(np.float32) / 255.0
    alpha = np.clip((arr[..., 3] - 0.40) / 0.30, 0.0, 1.0)
    out = np.zeros((*alpha.shape, 4), dtype=np.uint8)
    out[..., :3] = 255
    out[..., 3] = (alpha * 255.0).round().astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def save_webp(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "WEBP", lossless=True, quality=100, method=6)


def main() -> None:
    src = load_source()
    glyph, strong, glyph_centre = extract_glyph(src)
    source_size = src.width
    print(f"source: {SRC_PATH.name} trimmed to {source_size}x{source_size}")
    print(f"glyph: {glyph.size[0]}x{glyph.size[1]} (strong {strong[2]-strong[0]:.0f}x{strong[3]-strong[1]:.0f})")

    for density, (legacy, adaptive) in DENSITIES.items():
        out_dir = RES / f"mipmap-{density}"

        # Legacy square icon: the artwork exactly as designed.
        save_webp(resize_premultiplied(src, (legacy, legacy)), out_dir / "ic_launcher.webp")

        # Round icon: gradient backdrop + glyph, clipped to a circle.
        round_icon = composed_icon(
            background(legacy), glyph, strong, glyph_centre, source_size, ROUND_VISIBLE
        )
        round_icon.putalpha(circle_mask(legacy))
        save_webp(round_icon, out_dir / "ic_launcher_round.webp")

        # Adaptive layers: gradient background + glyph inside the safe zone.
        save_webp(background(adaptive).convert("RGBA"), out_dir / "ic_launcher_background.webp")

        foreground = composed_icon(
            Image.new("RGBA", (adaptive, adaptive), (0, 0, 0, 0)),
            glyph, strong, glyph_centre, source_size, ADAPTIVE_VISIBLE,
        )
        save_webp(foreground, out_dir / "ic_launcher_foreground.webp")

        # Themed (monochrome) icon: white glyph, positioned like the foreground.
        save_webp(
            composed_icon(
                Image.new("RGBA", (adaptive, adaptive), (0, 0, 0, 0)),
                monochrome(glyph), strong, glyph_centre, source_size, ADAPTIVE_VISIBLE,
            ),
            out_dir / "ic_launcher_monochrome.webp",
        )
        print(f"{density}: legacy {legacy}px, adaptive {adaptive}px")

    store_path = ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png"
    resize_premultiplied(src, (512, 512)).save(store_path, "PNG", optimize=True)
    print(f"play store icon: {store_path.relative_to(ROOT)} (512x512)")


if __name__ == "__main__":
    main()

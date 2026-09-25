"""
Shiny Hunter icon generator
===========================

Draws the Shiny Hunter icon from plain geometry: a rounded square, a circle, and four-point
"sparkle" stars built from quadratic Bezier curves. There is no image model, no randomness, and no
input image; running it twice gives identical files.

Outputs (written next to this script, or to the folder you pass as the first argument):
    ShinyHunter icon.svg   vector version
    ShinyHunter icon.png   512x512, for Modrinth / GitHub
    ShinyHunter icon.ico   Windows icon, 16-256 px

Run:
    python make_shinyhunter_icon.py
Needs Pillow for the PNG/ICO:  python -m pip install pillow

Everything you might want to change is in the DESIGN section below.
"""
import os
import sys

from PIL import Image, ImageDraw

# ============================================================================ DESIGN
# The canvas is 128 x 128 units; the numbers below are in those units.

CANVAS = 128
CORNER_RADIUS = 26              # rounding of the background square

BACKGROUND = "#0F1B2D"           # dark navy
BLUE = "#5CC8FF"                 # main sparkle
CORE = "#EAF9FF"                 # bright centre of the main sparkle
GOLD = "#F4C75A"                 # small stars
GLOW = "#4FC3F7"                 # soft disc behind the sparkle
GLOW_OPACITY = 0.20

# A sparkle is (centre x, centre y, half-width, half-height, pinch).
# "pinch" is how far the curve's control point sits from the centre: small = thin, sharp
# points; larger = fatter star.
MAIN_SPARKLE = (62, 63, 40, 50, 6)
CORE_SPARKLE = (62, 63, 15, 19, 2.5)
GOLD_STARS = [
    (99, 32, 8, 10, 1.6),        # top right
    (30, 98, 6, 7.5, 1.2),       # bottom left
]
GLOW_CIRCLE = (62, 63, 36)       # centre x, centre y, radius

# Drawn back to front.
LAYERS = (
    [("circle", GLOW_CIRCLE, GLOW, GLOW_OPACITY),
     ("sparkle", MAIN_SPARKLE, BLUE, 1.0),
     ("sparkle", CORE_SPARKLE, CORE, 1.0)]
    + [("sparkle", star, GOLD, 1.0) for star in GOLD_STARS]
)

PNG_SIZE = 512
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]


# ============================================================================ GEOMETRY

def sparkle_segments(cx, cy, half_w, half_h, pinch):
    """The four curved sides of a four-point star.

    The star's tips are straight up, right, down and left of the centre. Each side is a quadratic
    Bezier curve from one tip to the next, bent inwards by a control point that sits `pinch` units
    from the centre, diagonally between the two tips. That's what makes the sides concave.
    """
    tips = [(cx, cy - half_h), (cx + half_w, cy), (cx, cy + half_h), (cx - half_w, cy)]
    controls = [(cx + pinch, cy - pinch), (cx + pinch, cy + pinch),
                (cx - pinch, cy + pinch), (cx - pinch, cy - pinch)]
    return [(tips[i], controls[i], tips[(i + 1) % 4]) for i in range(4)]


def bezier_point(start, control, end, t):
    """A point on a quadratic Bezier curve: B(t) = (1-t)^2 P0 + 2(1-t)t P1 + t^2 P2."""
    u = 1 - t
    return (u * u * start[0] + 2 * u * t * control[0] + t * t * end[0],
            u * u * start[1] + 2 * u * t * control[1] + t * t * end[1])


def sparkle_polygon(sparkle, steps_per_side=64):
    """The star's outline as points, by walking along each curve."""
    points = []
    for start, control, end in sparkle_segments(*sparkle):
        for i in range(steps_per_side):
            points.append(bezier_point(start, control, end, i / steps_per_side))
    return points


# ============================================================================ SVG

def make_svg():
    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CANVAS} {CANVAS}" '
        f'width="{CANVAS}" height="{CANVAS}">',
        '  <title>Shiny Hunter</title>',
        f'  <rect width="{CANVAS}" height="{CANVAS}" rx="{CORNER_RADIUS}" fill="{BACKGROUND}"/>',
    ]
    for kind, shape, colour, opacity in LAYERS:
        alpha = "" if opacity == 1.0 else f' fill-opacity="{opacity:g}"'
        if kind == "circle":
            cx, cy, r = shape
            lines.append(f'  <circle cx="{cx:g}" cy="{cy:g}" r="{r:g}" fill="{colour}"{alpha}/>')
        else:
            segments = sparkle_segments(*shape)
            d = "M{:g} {:g}".format(*segments[0][0])
            for _, control, end in segments:
                d += " Q{:g} {:g} {:g} {:g}".format(*control, *end)
            lines.append(f'  <path d="{d}Z" fill="{colour}"{alpha}/>')
    lines.append("</svg>")
    return "\n".join(lines) + "\n"


# ============================================================================ RASTER

def rgba(hex_colour, opacity=1.0):
    h = hex_colour.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), round(255 * opacity)


def render(size, supersample=8):
    """Draws the icon at `size` pixels. It's drawn `supersample` times larger and then scaled
    down, which smooths the edges (anti-aliasing)."""
    big = size * supersample
    scale = big / CANVAS
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(image).rounded_rectangle(
        [0, 0, big - 1, big - 1], radius=CORNER_RADIUS * scale, fill=rgba(BACKGROUND))

    for kind, shape, colour, opacity in LAYERS:
        # Each shape gets its own layer so see-through colours blend properly.
        layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        if kind == "circle":
            cx, cy, r = shape
            draw.ellipse([(cx - r) * scale, (cy - r) * scale, (cx + r) * scale, (cy + r) * scale],
                         fill=rgba(colour, opacity))
        else:
            draw.polygon([(x * scale, y * scale) for x, y in sparkle_polygon(shape)],
                         fill=rgba(colour, opacity))
        image = Image.alpha_composite(image, layer)

    return image.resize((size, size), Image.LANCZOS)


# ============================================================================ MAIN

def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    os.makedirs(out, exist_ok=True)

    with open(os.path.join(out, "ShinyHunter icon.svg"), "w", encoding="utf-8") as f:
        f.write(make_svg())

    render(PNG_SIZE).save(os.path.join(out, "ShinyHunter icon.png"))

    frames = [render(n) for n in ICO_SIZES]
    frames[-1].save(os.path.join(out, "ShinyHunter icon.ico"), format="ICO",
                    sizes=[(n, n) for n in ICO_SIZES], append_images=frames[:-1])

    print("Wrote ShinyHunter icon.svg / .png / .ico to", out)


if __name__ == "__main__":
    main()

import math, os, sys
W, H = 120, 130

def circle(cx, cy, r): return f"M{cx-r},{cy} a{r},{r} 0 1,0 {2*r},0 a{r},{r} 0 1,0 {-2*r},0 Z"
def ellipse(cx, cy, rx, ry): return f"M{cx-rx},{cy} a{rx},{ry} 0 1,0 {2*rx},0 a{rx},{ry} 0 1,0 {-2*rx},0 Z"
def rrect(x, y, w, h, r):
    return (f"M{x+r},{y} H{x+w-r} A{r},{r} 0 0 1 {x+w},{y+r} V{y+h-r} A{r},{r} 0 0 1 {x+w-r},{y+h} "
            f"H{x+r} A{r},{r} 0 0 1 {x},{y+h-r} V{y+r} A{r},{r} 0 0 1 {x+r},{y} Z")
def star4(cx, cy, r):  # sparkle
    k = r * 0.28
    return f"M{cx},{cy-r} Q{cx+k},{cy-k} {cx+r},{cy} Q{cx+k},{cy+k} {cx},{cy+r} Q{cx-k},{cy+k} {cx-r},{cy} Q{cx-k},{cy-k} {cx},{cy-r} Z"
def note(x, y, s=1.0):  # small eighth note, head at (x,y)
    return (ellipse(x, y, 5*s, 4*s) + f" M{x+3.6*s},{y-1*s} H{x+5.4*s} V{y-18*s} H{x+3.6*s} Z"
            f" M{x+5.4*s},{y-18*s} C{x+10*s},{y-16*s} {x+13*s},{y-12*s} {x+11*s},{y-7*s} C{x+10*s},{y-10*s} {x+8*s},{y-11*s} {x+5.4*s},{y-11*s} Z")

def P(d, fill=None, stroke=None, sw=0, alpha=1.0, cap="round"):
    return dict(d=d, fill=fill, stroke=stroke, sw=sw, alpha=alpha, cap=cap)
def G(rot, px, py, children): return dict(group=True, rot=rot, px=px, py=py, children=children)

YELLOW, OUTLINE, DARK = "#FFD34E", "#E0A300", "#3A2210"
CORAL, GRAPE, GOLD, GOLD_D, INK, PINK = "#FF5A36", "#7B5CFF", "#FFC93C", "#D99A00", "#1E1A2E", "#FF8FA3"

def avatar(level):
    L = []
    # ----- background effects -----
    if level >= 7:  # golden rays
        cx, cy = 60, 70
        for i in range(16):
            a = math.radians(i * 22.5)
            a1, a2 = a - 0.09, a + 0.09
            r1, r2 = 38, (62 if i % 2 == 0 else 54)
            pts = [(cx + r1 * math.cos(a), cy + r1 * math.sin(a)),
                   (cx + r2 * math.cos(a1), cy + r2 * math.sin(a1)),
                   (cx + r2 * math.cos(a2), cy + r2 * math.sin(a2))]
            d = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts) + " Z"
            L.append(P(d, fill=GOLD if i % 2 == 0 else CORAL, alpha=0.55))
    if level >= 6:
        if level == 6:
            L.append(P(circle(60, 70, 50), fill=GRAPE, alpha=0.18))
    # ----- neck chain (behind face bottom) -----
    if level >= 4:
        L.append(P("M36,96 Q60,124 84,96", stroke=GOLD, sw=3.5))
        L.append(P(note(57, 118, 0.55), fill=GOLD))
    # ----- face -----
    L.append(P(circle(60, 70, 34), fill=YELLOW, stroke=OUTLINE, sw=2))
    if level >= 1:
        L.append(P(ellipse(41, 80, 6, 4), fill=PINK, alpha=0.7))
        L.append(P(ellipse(79, 80, 6, 4), fill=PINK, alpha=0.7))
    # eyes
    if level < 2:
        L.append(P(ellipse(48, 64, 3.6, 4.6), fill=DARK))
        L.append(P(ellipse(72, 64, 3.6, 4.6), fill=DARK))
    # mouth
    if level == 0:
        L.append(P("M45,79 Q60,92 75,79", stroke=DARK, sw=4))
    else:
        L.append(P("M42,77 Q60,103 78,77 Q60,82 42,77 Z", fill=DARK))
        L.append(P("M52,90 Q60,96 68,90 Q60,86 52,90 Z", fill="#FF6B6B"))
    # sunglasses: smooth shades from Air Guitarist, epic pixel shades for the Legend
    if 2 <= level < 7:
        L.append(P("M34,57 H56 V63 Q56,74 45,74 Q34,74 34,63 Z", fill=INK))
        L.append(P("M64,57 H86 V63 Q86,74 75,74 Q64,74 64,63 Z", fill=INK))
        L.append(P("M55,59 H65 V62 H55 Z", fill=INK))
        L.append(P("M38,61 L44,61", stroke="#FFFFFF", sw=2, alpha=0.8))
        L.append(P("M68,61 L74,61", stroke="#FFFFFF", sw=2, alpha=0.8))
    # ----- hats (under the headphone band) -----
    if level in (3, 4):  # backwards cap
        cap = CORAL
        L.append(P("M27,52 C26,20 94,20 93,52 Q60,44 27,52 Z", fill=cap, stroke="#C93C1C", sw=1.5))
        L.append(P("M87,46 Q108,40 113,50 Q100,53 88,52 Z", fill="#C93C1C"))  # visor peeking out the side
        L.append(P("M50,47 H70 V51 H50 Z", fill="#C93C1C"))  # strap
        L.append(P(circle(60, 25, 3.2), fill="#C93C1C"))
        L.append(P("M36,40 Q60,30 84,40", stroke="#FFFFFF", sw=2, alpha=0.35))
    # ----- headphones -----
    hp = {0: "#8A8A99", 1: CORAL, 2: CORAL, 3: GRAPE, 4: GRAPE, 5: INK, 6: GRAPE, 7: "#E53946"}[level]
    trim = {5: GOLD, 7: GOLD}.get(level, "#00000033")
    L.append(P("M24,72 C20,18 100,18 96,72", stroke=hp, sw=7))
    for x in (14, 92):
        L.append(P(rrect(x, 56, 14, 32, 6), fill=hp, stroke=trim if level in (5, 7) else None, sw=2))
        L.append(P(rrect(x + (8 if x < 60 else -2), 60, 8, 24, 4), fill="#000000", alpha=0.18))
    if level == 4:  # radio host boom mic
        L.append(P("M100,84 C100,98 90,100 80,96", stroke=INK, sw=3))
        L.append(P(ellipse(77, 95, 5, 4), fill="#44445A"))
        L.append(P("M74,94 L80,94", stroke="#FFFFFF", sw=1.2, alpha=0.5))
    if level >= 6:  # glowing ring on the cups
        for x in (21, 99):
            L.append(P(circle(x, 72, 4), fill="#FFFFFF", alpha=0.85))
    if level >= 7:  # pixel shades go on top of everything on the face, arm and all
        L.extend(pixel_shades(x0=27, y0=56, block=2.75))
    # ----- bigger hats (over the band) -----
    if level == 5:  # tilted top hat with a vinyl record
        L.append(G(-10, 60, 40, [
            P(ellipse(60, 40, 34, 7), fill=INK),
            P("M40,40 L38,4 Q60,0 82,4 L80,40 Z", fill=INK),
            P("M39,28 H81 V36 H39 Z", fill=CORAL),
            P(circle(72, 32, 6.5), fill="#2B2B3A", stroke="#55556A", sw=1),
            P(circle(72, 32, 2.2), fill=GOLD),
            P("M44,8 L46,26", stroke="#FFFFFF", sw=2, alpha=0.25),
        ]))
    if level == 6:  # wizard hat with stars
        L.append(P(ellipse(60, 42, 36, 8), fill="#4B32C3"))
        L.append(P("M30,42 Q60,50 90,42 L70,6 Q66,-4 54,0 Q62,2 62,8 Z", fill="#5B3FD9"))
        L.append(P(star4(56, 26, 5), fill=GOLD))
        L.append(P(star4(70, 34, 3.5), fill=GOLD))
        L.append(P(star4(46, 38, 3), fill="#FFFFFF"))
    if level == 7:  # crown
        L.append(P("M30,46 L31,14 L45,28 L60,4 L75,28 L89,14 L90,46 Z", fill=GOLD, stroke=GOLD_D, sw=2))
        L.append(P("M30,40 H90 V46 H30 Z", fill=GOLD_D))
        L.append(P(circle(60, 30, 4.5), fill="#E53946"))
        L.append(P(circle(44, 36, 3), fill="#3AA7FF"))
        L.append(P(circle(76, 36, 3), fill="#2EC4B6"))
        for (x, y) in ((31, 14), (60, 4), (89, 14)):
            L.append(P(circle(x, y, 3), fill="#FFFFFF"))
    # ----- floating extras -----
    if level in (1, 2):
        L.append(P(note(100, 30, 0.8), fill=CORAL))
    if level >= 3:
        L.append(P(note(102, 28, 0.8), fill=CORAL if level < 7 else GOLD))
        L.append(P(note(12, 30, 0.6), fill=GRAPE if level < 7 else GOLD))
    if level >= 5:
        L.append(P(star4(18, 112, 5), fill=GOLD))
        L.append(P(star4(104, 108, 4), fill=GOLD))
    if level >= 7:
        L.append(P(star4(10, 58, 4), fill="#FFFFFF"))
        L.append(P(star4(112, 70, 5), fill="#FFFFFF"))
    return L

def pixel_shades(x0, y0, block):
    """Chunky 8-bit 'deal with it' style shades, built from square pixels."""
    lens = ["###########",
            "##w##w#####",
            "###w##w####",
            ".#########.",
            "..#######..",
            "....###...."]
    bridge = ["##", "##", "..", "..", "..", ".."]
    rows = [lens[r] + bridge[r] + lens[r] for r in range(len(lens))]
    black, white = [], []
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            x, y = x0 + c * block, y0 + r * block
            sq = f"M{x},{y} h{block} v{block} h{-block} Z"
            if ch == "#": black.append(sq)
            elif ch == "w": white.append(sq)
    arm = f"M{x0 - 20},{y0} h20 v{block} h-20 Z"  # the arm sticking out to the side
    return [P(" ".join(black) + " " + arm, fill="#000000"), P(" ".join(white), fill="#FFFFFF")]

# ---------- writers ----------
def svg_el(e):
    if e.get("group"):
        inner = "".join(svg_el(c) for c in e["children"])
        return f'<g transform="rotate({e["rot"]} {e["px"]} {e["py"]})">{inner}</g>'
    a = [f'd="{e["d"]}"', f'fill="{e["fill"] or "none"}"']
    if e["stroke"]: a += [f'stroke="{e["stroke"]}"', f'stroke-width="{e["sw"]}"', f'stroke-linecap="{e["cap"]}"', 'stroke-linejoin="round"']
    if e["alpha"] < 1: a.append(f'opacity="{e["alpha"]}"')
    return f'<path {" ".join(a)}/>'

def to_svg(level, size=240, bg=None):
    body = "".join(svg_el(e) for e in avatar(level))
    bgr = f'<rect width="{W}" height="{H}" fill="{bg}"/>' if bg else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{size}" height="{size*H/W:.0f}">{bgr}{body}</svg>'

def argb(c, alpha=1.0):
    c = c.lstrip("#")
    if len(c) == 8:  # rrggbbaa -> aarrggbb
        return "#" + c[6:] + c[:6]
    return "#" + c

def vec_el(e, ind="    "):
    if e.get("group"):
        inner = "".join(vec_el(c, ind + "    ") for c in e["children"])
        return f'{ind}<group android:rotation="{e["rot"]}" android:pivotX="{e["px"]}" android:pivotY="{e["py"]}">\n{inner}{ind}</group>\n'
    a = [f'android:pathData="{e["d"]}"']
    if e["fill"]:
        a.append(f'android:fillColor="{argb(e["fill"])}"')
        if e["alpha"] < 1: a.append(f'android:fillAlpha="{e["alpha"]}"')
    if e["stroke"]:
        a += [f'android:strokeColor="{argb(e["stroke"])}"', f'android:strokeWidth="{e["sw"]}"',
              'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
        if e["alpha"] < 1: a.append(f'android:strokeAlpha="{e["alpha"]}"')
    return f'{ind}<path ' + f"\n{ind}    ".join(a) + " />\n"

def to_vector(level):
    body = "".join(vec_el(e) for e in avatar(level))
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Humdinger avatar, tier {level}. Generated — edit the generator, not by hand. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{W}dp"\n    android:height="{H}dp"\n'
            f'    android:viewportWidth="{W}"\n    android:viewportHeight="{H}">\n{body}</vector>\n')

if __name__ == "__main__":
    out = sys.argv[1]
    import cairosvg
    names = ["Fresh Ears","Toe Tapper","Air Guitarist","Karaoke Hero","Radio Buff","Record Collector","Music Boffin","Humdinger Legend"]
    cells = ""
    for i in range(8):
        inner = "".join(svg_el(e) for e in avatar(i))
        x, y = (i % 4) * 150, (i // 4) * 185
        cells += f'<g transform="translate({x+15},{y+10})">{inner}</g>'
        cells += f'<text x="{x+75}" y="{y+165}" text-anchor="middle" font-family="Fredoka SemiBold" font-size="14" fill="#2A1B3D">{i}. {names[i]}</text>'
    grid = f'<svg xmlns="http://www.w3.org/2000/svg" width="600" height="370" viewBox="0 0 600 370"><rect width="600" height="370" fill="#FFF8EE"/>{cells}</svg>'
    cairosvg.svg2png(bytestring=grid.encode(), write_to=os.path.join(out, "grid.png"), output_width=1200)
    for i in range(8):
        open(os.path.join(out, f"avatar_tier_{i}.xml"), "w").write(to_vector(i))
    print("ok")

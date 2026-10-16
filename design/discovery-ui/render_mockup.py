"""Render the proposed ClearTune discovery screen without changing app code."""

from pathlib import Path
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = HERE / "discovery-mockup.png"
W, H, SCALE = 1080, 2400, 2

BG = "#F5F3FF"
SURFACE = "#FFFBFF"
INK = "#1C1B1F"
MUTED = "#49454F"
PRIMARY = "#4965A1"
PRIMARY_PALE = "#DCE5FF"
OUTLINE = "#E4E3EE"
FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"


def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT, size * SCALE)


def scaled_box(box):
    return tuple(round(value * SCALE) for value in box)


canvas = Image.new("RGB", (W * SCALE, H * SCALE), BG)
draw = ImageDraw.Draw(canvas)


def rect(box, fill, radius=0, outline=None, width=1):
    draw.rounded_rectangle(scaled_box(box), radius=radius * SCALE, fill=fill,
                          outline=outline, width=width * SCALE)


def line(points, fill, width=2):
    draw.line([(round(x * SCALE), round(y * SCALE)) for x, y in points],
              fill=fill, width=width * SCALE, joint="curve")


def circle(center, radius, fill, outline=None, width=1):
    x, y = center
    draw.ellipse(scaled_box((x - radius, y - radius, x + radius, y + radius)),
                 fill=fill, outline=outline, width=width * SCALE)


def label(x, y, value, size, color=INK, bold=False):
    draw.text((round(x * SCALE), round(y * SCALE)), value, font=font(size, bold),
              fill=color, anchor="lt")


def card(box):
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle(scaled_box((box[0], box[1] + 8, box[2], box[3] + 8)),
                         radius=38 * SCALE, fill=(37, 35, 71, 17))
    canvas.paste(Image.alpha_composite(canvas.convert("RGBA"),
                                      shadow.filter(ImageFilter.GaussianBlur(10 * SCALE))).convert("RGB"))
    rect(box, SURFACE, radius=38, outline=OUTLINE, width=2)


def cover_art_from_home(box):
    home = Image.open(ROOT / "screenshots" / "ui-home-v1.3.3.png").convert("RGB")
    return home.crop(box)


def illustrative_art(kind):
    art = Image.new("RGB", (512, 512))
    d = ImageDraw.Draw(art)
    if kind == "dawn":
        d.rectangle((0, 0, 512, 512), fill="#F4D4BE")
        d.ellipse((274, 72, 432, 230), fill="#FFF1D2")
        d.polygon([(0, 333), (121, 249), (259, 327), (380, 257), (512, 317), (512, 512), (0, 512)], fill="#AF9699")
        d.polygon([(0, 399), (144, 346), (314, 393), (512, 337), (512, 512), (0, 512)], fill="#77808B")
    elif kind == "sea":
        d.rectangle((0, 0, 512, 280), fill="#D8E9E8")
        d.rectangle((0, 280, 512, 512), fill="#7DB6B8")
        d.ellipse((328, 83, 416, 171), fill="#FFF2D3")
        d.polygon([(240, 316), (365, 316), (343, 341), (267, 341)], fill="#33586B")
        d.polygon([(303, 168), (303, 316), (367, 316)], fill="#FFF7DB")
        d.line((303, 169, 303, 321), fill="#657C81", width=6)
        d.line((27, 390, 213, 400), fill="#D2DFCE", width=7)
        d.line((291, 419, 476, 431), fill="#D2DFCE", width=7)
    elif kind == "forest":
        d.rectangle((0, 0, 512, 512), fill="#DDE6DA")
        d.ellipse((326, 62, 432, 168), fill="#F6ECC8")
        d.polygon([(0, 368), (115, 207), (240, 367)], fill="#76948B")
        d.polygon([(131, 401), (292, 159), (483, 401)], fill="#63847B")
        d.polygon([(331, 385), (448, 230), (512, 319), (512, 512), (331, 512)], fill="#426E68")
        d.rectangle((0, 390, 512, 512), fill="#365C5A")
    elif kind == "mountain":
        d.rectangle((0, 0, 512, 512), fill="#DCE4E4")
        d.polygon([(0, 403), (200, 123), (410, 403)], fill="#8EA5AD")
        d.polygon([(145, 202), (200, 123), (257, 201), (202, 180)], fill="#FFF9E7")
        d.polygon([(167, 512), (386, 236), (512, 389), (512, 512)], fill="#516F7A")
        d.polygon([(0, 445), (119, 361), (288, 512), (0, 512)], fill="#375C69")
    else:  # warm city
        d.rectangle((0, 0, 512, 512), fill="#D8D4D7")
        d.rectangle((0, 190, 129, 489), fill="#788A9A")
        d.rectangle((127, 115, 251, 487), fill="#4C6277")
        d.rectangle((259, 241, 382, 484), fill="#8497A0")
        d.rectangle((388, 174, 512, 484), fill="#556B7D")
        for x, y in [(158, 168), (158, 220), (158, 272), (287, 301), (413, 229)]:
            d.rectangle((x, y, x + 21, y + 31), fill="#FFE1A7")
        d.polygon([(0, 466), (512, 418), (512, 512), (0, 512)], fill="#D3B4A8")
    return art


home_art = [
    cover_art_from_home((53, 1520, 305, 1772)),
    cover_art_from_home((342, 1520, 594, 1772)),
    cover_art_from_home((631, 1520, 883, 1772)),
    illustrative_art("mountain"),
]
abstract_art = [Image.open(ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / name).convert("RGB")
                for name in ("cleartune_cover_orb.jpg", "cleartune_cover_ribbon.jpg",
                             "cleartune_cover_horizon.jpg", "cleartune_cover_portal.jpg")]
memory_art = [illustrative_art(name) for name in ("dawn", "sea", "forest", "city")]


def cover(art, x, y, size=176):
    px = size * SCALE
    resized = art.resize((px, px), Image.Resampling.LANCZOS)
    mask = Image.new("L", (px, px), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, px - 1, px - 1),
                                           radius=20 * SCALE, fill=255)
    canvas.paste(resized, (x * SCALE, y * SCALE), mask)


def chevron(x, y):
    line([(x - 5, y - 11), (x + 7, y), (x - 5, y + 11)], PRIMARY, 5)


def recommendation_card(y, title, reason, arts):
    left, right, height = 54, 1026, 472
    card((left, y, right, y + height))
    label(95, y + 47, title, 55, INK, True)
    label(824, y + 62, "查看全部", 30, PRIMARY, True)
    chevron(991, y + 79)
    label(95, y + 133, reason, 31, MUTED)
    for index, art in enumerate(arts):
        cover(art, 95 + index * 193, y + 247)
    circle((932, y + 335), 55, PRIMARY_PALE)
    draw.polygon([(914 * SCALE, (y + 307) * SCALE),
                  (914 * SCALE, (y + 363) * SCALE),
                  (959 * SCALE, (y + 335) * SCALE)], fill=PRIMARY)


def render_editorial():
    """An alternative with one lead recommendation and visible song choices."""
    def play(x, y, radius=26, fill=PRIMARY, triangle=SURFACE):
        circle((x, y), radius, fill)
        draw.polygon([((x - radius * .22) * SCALE, (y - radius * .42) * SCALE),
                      ((x - radius * .22) * SCALE, (y + radius * .42) * SCALE),
                      ((x + radius * .45) * SCALE, y * SCALE)], fill=triangle)

    def more(y):
        label(855, y + 12, "查看全部", 29, PRIMARY)
        chevron(1009, y + 27)

    def song_row(y, art, title, subtitle, hero=False):
        x = 96 if hero else 54
        cover(art, x, y, 94)
        label(x + 123, y + 4, title, 36, INK, True)
        label(x + 123, y + 59, subtitle, 27, MUTED)
        play(953 if hero else 977, y + 47, 28, "#E8E7F5", PRIMARY)

    label(73, 42, "3:30", 42)
    label(809, 42, "5G", 38, INK, True)
    for i, height in enumerate((11, 20, 29, 38)):
        rect((870 + i * 14, 79 - height, 879 + i * 14, 79), INK, 4)
    rect((951, 48, 1015, 77), "#14C54D", 8)
    rect((1015, 57, 1021, 68), INK, 3)
    line([(87, 164), (61, 190), (87, 216)], INK, 7)
    line([(65, 190), (111, 190)], INK, 7)
    label(145, 155, "发现音乐", 58, INK, True)
    draw.arc(scaled_box((837, 171, 875, 209)), 65, 320, fill=PRIMARY, width=5 * SCALE)
    draw.polygon([(875 * SCALE, 171 * SCALE), (877 * SCALE, 187 * SCALE),
                  (863 * SCALE, 181 * SCALE)], fill=PRIMARY)
    label(891, 174, "换一批", 31, PRIMARY, True)
    label(54, 283, "沿着喜欢，听见更多", 34, MUTED)

    # Only the leading recommendation has a container.
    panel = Image.new("RGB", (972 * SCALE, 773 * SCALE))
    pd = ImageDraw.Draw(panel)
    for y in range(panel.height):
        t = y / panel.height
        rgb = tuple(round(a + (b - a) * t) for a, b in zip((221, 229, 255), (248, 246, 255)))
        pd.line((0, y, panel.width, y), fill=rgb)
    mask = Image.new("L", panel.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, panel.width - 1, panel.height - 1), radius=42 * SCALE, fill=255)
    canvas.paste(panel, (54 * SCALE, 365 * SCALE), mask)
    label(96, 414, "从喜欢出发", 52, INK, True)
    label(96, 506, "因为你喜欢", 31, MUTED)
    label(96, 554, "林间来信", 40, PRIMARY, True)
    label(96, 619, "再听听相似的声音", 29, MUTED)

    rear = home_art[3].resize((238 * SCALE, 238 * SCALE), Image.Resampling.LANCZOS).convert("RGBA")
    rear_mask = Image.new("L", rear.size, 0)
    ImageDraw.Draw(rear_mask).rounded_rectangle((0, 0, rear.width - 1, rear.height - 1), radius=25 * SCALE, fill=255)
    rear.putalpha(rear_mask)
    rear = rear.rotate(-9, Image.Resampling.BICUBIC, expand=True)
    canvas.paste(rear, (714 * SCALE, 432 * SCALE), rear)
    cover(home_art[0], 619, 470, 287)
    rect((96, 706, 365, 792), PRIMARY, 43)
    play(139, 749, 26, PRIMARY, "#FFFFFF")
    label(181, 731, "播放这组", 32, "#FFFFFF", True)
    label(388, 733, "20 首歌曲", 28, MUTED)
    line([(96, 844), (984, 844)], "#DADFED", 2)
    song_row(881, home_art[0], "风经过的午后", "林间来信 · 午后片刻", True)
    song_row(1006, home_art[3], "慢慢靠近", "远山乐队 · 山与回声", True)

    label(54, 1200, "换个口味", 52, INK, True)
    more(1200)
    label(54, 1276, "这些少听的歌，也许正合今天的心情", 30, MUTED)
    tracks = [("微光之海", "蓝色房间"), ("夜航", "无声引力"),
              ("日落之后", "岛屿电台"), ("回声", "回声计划")]
    for i, ((title, artist), art) in enumerate(zip(tracks, abstract_art)):
        x = 54 + i * 309
        cover(art, x, 1342, 278)
        play(x + 230, 1573, 32, "#F5F3FF", PRIMARY)
        label(x, 1650, title, 36, INK, True)
        label(x, 1709, artist, 28, MUTED)

    label(54, 1813, "好久不见", 52, INK, True)
    more(1813)
    label(54, 1889, "有些旋律，一响起就想起了", 30, MUTED)
    song_row(1958, memory_art[0], "晨光来信", "晴日来信 · 上次听是 36 天前")
    song_row(2084, memory_art[1], "等风靠岸", "海边的人 · 上次听是 48 天前")
    song_row(2210, home_art[2], "夜色温柔", "晚间电台 · 上次听是 62 天前")
    rect((398, 2365, 682, 2375), "#5D5E64", 5)
    output = HERE / "discovery-editorial-mockup.png"
    canvas.resize((W, H), Image.Resampling.LANCZOS).save(output, optimize=True)
    print(output)


if "--editorial" in sys.argv:
    render_editorial()
    raise SystemExit(0)


# Android status bar and top app bar, matching the existing light screenshot.
label(73, 42, "3:30", 42, INK)
label(809, 42, "5G", 38, INK, True)
for index, height in enumerate((11, 20, 29, 38)):
    rect((870 + index * 14, 79 - height, 879 + index * 14, 79), INK, radius=4)
rect((951, 48, 1015, 77), "#14C54D", radius=8)
rect((1015, 57, 1021, 68), INK, radius=3)
line([(87, 171), (61, 197), (87, 223)], INK, 7)
line([(65, 197), (111, 197)], INK, 7)
label(145, 161, "发现音乐", 58, INK, True)
rect((824, 154, 1026, 240), PRIMARY_PALE, radius=43)
draw.arc(scaled_box((845, 171, 885, 215)), 65, 320, fill=PRIMARY, width=5 * SCALE)
draw.polygon([(884 * SCALE, 174 * SCALE), (886 * SCALE, 190 * SCALE),
              (872 * SCALE, 185 * SCALE)], fill=PRIMARY)
label(893, 181, "换一批", 31, PRIMARY, True)

label(54, 319, "在你的曲库里，发现下一首想听的歌", 34, MUTED)

# Every filter is visible at once on a phone-width screen.
chips = [(54, 128, "全部", True), (199, 281, "从喜欢出发", False),
         (497, 226, "换个口味", False), (740, 286, "好久不见", False)]
for x, width, text, selected in chips:
    rect((x, 420, x + width, 504), PRIMARY_PALE if selected else SURFACE,
         radius=42, outline=None if selected else OUTLINE, width=2)
    text_width = draw.textlength(text, font=font(31, selected)) / SCALE
    label(x + (width - text_width) / 2, 445, text, 31, PRIMARY if selected else MUTED, selected)

recommendation_card(563, "从喜欢出发", "你喜欢的艺术家，还有这些歌值得一听", home_art)
recommendation_card(1073, "换个口味", "听得不多的歌，也许正合今天的心情", abstract_art)
recommendation_card(1583, "好久不见", "30 天没播放，重新遇见熟悉的旋律", memory_art)

# Discovery is a secondary page: the app's bottom navigation is hidden here.
rect((398, 2365, 682, 2375), "#5D5E64", radius=5)

OUT.parent.mkdir(parents=True, exist_ok=True)
canvas.resize((W, H), Image.Resampling.LANCZOS).save(OUT, optimize=True)
print(OUT)

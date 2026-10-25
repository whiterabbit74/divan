# Apple-style (SF Symbols-like) glyphs drawn from scratch as SVG: filled forms, round caps,
# one colour per app with a lighter secondary layer. Rendered by rsvg-convert, banner by PIL.
import subprocess, sys, os
from PIL import Image, ImageDraw, ImageFont
OUT = sys.argv[1]
LANG = sys.argv[2] if len(sys.argv) > 2 else "ru"
TITLES = {
    "en": {"watch.kino": "Movies", "tv.own.owntv": "Live TV", "io.github.jqssun.airplay": "iPhone Screen",
           "app.freescreen": "Cast", "com.esaba.downloader": "Downloads", "com.phlox.tvwebbrowser": "Browser",
           "miada.tv.webbrowser": "Browser"},
    "zh": {"watch.kino": "电影", "tv.own.owntv": "电视直播", "io.github.jqssun.airplay": "iPhone 投屏",
           "app.freescreen": "无线投屏", "com.esaba.downloader": "下载", "com.phlox.tvwebbrowser": "浏览器",
           "miada.tv.webbrowser": "浏览器"},
}
# SF Pro has no Chinese: Hiragino Sans GB W6 (PingFang is not installed on this Mac)
FONT_FILE, FONT_INDEX = ("/System/Library/Fonts/Hiragino Sans GB.ttc", 2) if LANG == "zh" else ("/System/Library/Fonts/SFNS.ttf", 0)
def make_font(size):
    f = ImageFont.truetype(FONT_FILE, size, index=FONT_INDEX)
    if FONT_FILE.endswith("SFNS.ttf"): f.set_variation_by_name("Semibold")
    return f
def svg(body, c):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100">
<g fill="none" stroke-linecap="round" stroke-linejoin="round">{body.format(c=c)}</g></svg>'''

GLYPHS = {
 # film.fill: a filled strip, perforations punched out, play mark in white
 "film": '''<mask id="m"><rect width="100" height="100" fill="white"/>
   <g fill="black">{holes}</g></mask>
   <rect x="16" y="14" width="68" height="72" rx="10" fill="{c}" mask="url(#m)"/>
   <path d="M43 38 L62 50 L43 62 Z" fill="white" stroke="white" stroke-width="3"/>''',
 # tv.fill with antenna: body, screen as the lighter layer, stand
 "tv": '''<path d="M38 18 L50 30 L62 18" stroke="{c}" stroke-width="6"/>
   <rect x="12" y="30" width="76" height="52" rx="11" fill="{c}"/>
   <rect x="20" y="37" width="60" height="38" rx="6" fill="white" fill-opacity="0.85"/>
   <path d="M44 48 L58 56 L44 64 Z" fill="{c}" stroke="{c}" stroke-width="3"/>''',
 # airplayvideo: screen open at the bottom, solid triangle rising into it
 "airplay": '''<path d="M34 72 H24 Q14 72 14 62 V30 Q14 20 24 20 H76 Q86 20 86 30 V62 Q86 72 76 72 H66" stroke="{c}" stroke-width="7"/>
   <path d="M50 56 L68 82 H32 Z" fill="{c}" stroke="{c}" stroke-width="4"/>''',
 # cast: screen frame with waves and a dot from its lower-left corner
 "cast": '''<path d="M14 44 V28 Q14 18 24 18 H76 Q86 18 86 28 V64 Q86 74 76 74 H50" stroke="{c}" stroke-width="7"/>
   <path d="M14 56 A20 20 0 0 1 32 74" stroke="{c}" stroke-width="7"/>
   <path d="M14 40 A36 36 0 0 1 48 74" stroke="{c}" stroke-width="7" stroke-opacity="0.45"/>
   <circle cx="17" cy="71" r="5.5" fill="{c}"/>''',
 # arrow.down.circle.fill
 "download": '''<circle cx="50" cy="50" r="38" fill="{c}"/>
   <path d="M50 30 V68 M35 54 L50 69 L65 54" stroke="white" stroke-width="8"/>''',
 # globe
 "globe": '''<circle cx="50" cy="50" r="36" stroke="{c}" stroke-width="6.5"/>
   <ellipse cx="50" cy="50" rx="15" ry="36" stroke="{c}" stroke-width="6.5"/>
   <path d="M16 50 H84 M22 32 H78 M22 68 H78" stroke="{c}" stroke-width="5" stroke-opacity="0.55"/>''',
}
holes = "".join(f'<rect x="{x}" y="{y}" width="7" height="8" rx="2"/>' for x in (21, 72) for y in (22, 36, 50, 64, 76 - 1))
GLYPHS["film"] = GLYPHS["film"].replace("{holes}", holes)

APPS = [  # package, title, glyph, Apple system colour
 ("watch.kino", "Кино", "film", "#FF3B30"),
 ("tv.own.owntv", "Телеканалы", "tv", "#34C759"),
 ("io.github.jqssun.airplay", "Экран iPhone", "airplay", "#5856D6"),
 ("app.freescreen", "Трансляция", "cast", "#30B0C7"),
 ("com.esaba.downloader", "Загрузки", "download", "#FF9500"),
 ("com.phlox.tvwebbrowser", "Браузер", "globe", "#007AFF"),
 ("miada.tv.webbrowser", "Браузер", "globe", "#007AFF"),
]
S = 4; W, H = 640 * S, 360 * S
font = make_font(64 * S)
os.makedirs(OUT, exist_ok=True)
for pkg, title, g, c in APPS:
    title = TITLES.get(LANG, {}).get(pkg, title)
    path = f"{OUT}/{g}_{c[1:]}.svg"
    open(path, "w").write(svg(GLYPHS[g], c))
    png = path[:-4] + ".png"
    subprocess.run(["rsvg-convert", "-w", str(150 * S), "-h", str(150 * S), path, "-o", png], check=True)
    img = Image.new("RGB", (W, H), (0xF5, 0xF5, 0xF5))
    mark = Image.open(png).convert("RGBA")
    img.paste(mark, (70 * S, (H - mark.height) // 2), mark)
    d = ImageDraw.Draw(img)
    size = 64
    while d.textlength(title, font=font) > 340 * S and size > 40:
        size -= 2; font = make_font(size * S)
    d.text((240 * S, H // 2), title, font=font, fill=(0x1C, 0x1C, 0x1E), anchor="lm")
    font = make_font(64 * S)
    suffix = "" if LANG == "ru" else f".{LANG}"
    img.resize((640, 360), Image.LANCZOS).save(f"{OUT}/{pkg}{suffix}.png")
print("ok")

#!/usr/bin/env python3
import json, math, os
from PIL import Image, ImageDraw, ImageFont

SCALE = 2
PADDING = 50
FONT = "/System/Library/Fonts/SFNS.ttf"

def rgb(hex_color):
    if not hex_color or hex_color in ('transparent', 'none'): return None
    h = hex_color.lstrip('#')
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def font(size):
    try: return ImageFont.truetype(FONT, int(size * SCALE))
    except: return ImageFont.load_default()

def dashed_line(draw, p1, p2, color, w, dash=14, gap=8):
    dx, dy = p2[0]-p1[0], p2[1]-p1[1]
    length = math.hypot(dx, dy)
    if not length: return
    ux, uy = dx/length, dy/length
    pos, on = 0, True
    while pos < length:
        if on:
            ep = min(pos+dash, length)
            draw.line([(p1[0]+ux*pos, p1[1]+uy*pos), (p1[0]+ux*ep, p1[1]+uy*ep)], fill=color, width=w)
        pos += dash if on else gap
        on = not on

def arrowhead(draw, end, prev, color):
    size = 9 * SCALE
    angle = math.atan2(end[1]-prev[1], end[0]-prev[0])
    p2 = (end[0] - size*math.cos(angle-math.pi/6), end[1] - size*math.sin(angle-math.pi/6))
    p3 = (end[0] - size*math.cos(angle+math.pi/6), end[1] - size*math.sin(angle+math.pi/6))
    draw.polygon([end, p2, p3], fill=color)

def render(src, dst):
    data = json.load(open(src))
    els = [e for e in data['elements'] if not e.get('isDeleted')]

    # bounding box
    xs, ys = [], []
    for e in els:
        x, y, w, h = e.get('x',0), e.get('y',0), e.get('width',0), e.get('height',0)
        xs += [x, x+w]
        ys += [y, y+h]
        for p in e.get('points', []):
            xs.append(x + p[0]); ys.append(y + p[1])
    mn_x, mn_y = min(xs), min(ys)
    mx_x, mx_y = max(xs), max(ys)

    W = int((mx_x - mn_x + 2*PADDING) * SCALE)
    H = int((mx_y - mn_y + 2*PADDING) * SCALE)
    img = Image.new('RGB', (W, H), 'white')
    d = ImageDraw.Draw(img)

    ox = (-mn_x + PADDING) * SCALE
    oy = (-mn_y + PADDING) * SCALE
    sx = lambda v: int(v * SCALE + ox)
    sy = lambda v: int(v * SCALE + oy)
    sw = lambda v: max(1, int(v * SCALE))

    for layer in ('rectangle', 'line', 'arrow', 'text'):
        for e in els:
            if e['type'] != layer: continue

            if layer == 'rectangle':
                x1, y1 = sx(e['x']), sy(e['y'])
                x2, y2 = sx(e['x']+e['width']), sy(e['y']+e['height'])
                fill   = rgb(e.get('backgroundColor'))
                stroke = rgb(e.get('strokeColor','#343a40')) or (52,58,64)
                lw     = max(1, sw(e.get('strokeWidth',1)))
                dashed = e.get('strokeStyle') == 'dashed'
                if e.get('roundness'):
                    r = sw(10)
                    if fill: d.rounded_rectangle([x1,y1,x2,y2], radius=r, fill=fill)
                    if dashed:
                        d.rounded_rectangle([x1,y1,x2,y2], radius=r, outline=(180,180,180), width=lw)
                    else:
                        d.rounded_rectangle([x1,y1,x2,y2], radius=r, outline=stroke, width=lw)
                else:
                    if fill: d.rectangle([x1,y1,x2,y2], fill=fill)
                    d.rectangle([x1,y1,x2,y2], outline=stroke, width=lw)

            elif layer == 'line':
                x0, y0 = sx(e['x']), sy(e['y'])
                pts = [(x0+int(p[0]*SCALE), y0+int(p[1]*SCALE)) for p in e.get('points',[])]
                stroke = rgb(e.get('strokeColor','#cccccc')) or (200,200,200)
                lw = max(1, sw(e.get('strokeWidth',1)))
                if len(pts) >= 2:
                    if e.get('strokeStyle') == 'dashed':
                        dashed_line(d, pts[0], pts[1], stroke, lw)
                    else:
                        d.line(pts, fill=stroke, width=lw)

            elif layer == 'arrow':
                x0, y0 = sx(e['x']), sy(e['y'])
                pts = [(x0+int(p[0]*SCALE), y0+int(p[1]*SCALE)) for p in e.get('points',[])]
                stroke = rgb(e.get('strokeColor','#343a40')) or (52,58,64)
                lw = max(1, sw(e.get('strokeWidth',1)))
                if len(pts) >= 2:
                    d.line(pts, fill=stroke, width=lw)
                    if e.get('endArrowhead') == 'arrow':
                        arrowhead(d, pts[-1], pts[-2], stroke)

            elif layer == 'text':
                text = e.get('text','')
                if not text: continue
                fnt   = font(e.get('fontSize',16))
                color = rgb(e.get('strokeColor','#1e1e1e')) or (30,30,30)
                align = e.get('textAlign','left')
                valign = e.get('verticalAlign','top')
                ex, ey = sx(e['x']), sy(e['y'])
                ew, eh = sw(e.get('width',100)), sw(e.get('height',20))

                lines_t = text.split('\n')
                sizes = []
                for lt in lines_t:
                    bb = d.textbbox((0,0), lt, font=fnt)
                    sizes.append((bb[2]-bb[0], bb[3]-bb[1]))

                gap = int(e.get('fontSize',16) * 0.22 * SCALE)
                total_h = sum(s[1] for s in sizes) + gap*(len(lines_t)-1)

                if valign == 'middle': cy = ey + (eh - total_h)//2
                elif valign == 'bottom': cy = ey + eh - total_h
                else: cy = ey

                for lt, (lw_t, lh_t) in zip(lines_t, sizes):
                    if align == 'center': lx = ex + (ew - lw_t)//2
                    elif align == 'right': lx = ex + ew - lw_t
                    else: lx = ex
                    d.text((lx, cy), lt, font=fnt, fill=color)
                    cy += lh_t + gap

    img.save(dst, 'PNG')
    print(f"  {os.path.basename(dst)}")

base = "/Users/dt/Desktop/wallet/docs"
pairs = [
    ("architecture-layers", ),
    ("deposit-flow", ),
    ("withdrawal-payout-flow", ),
    ("transfer-flow", ),
    ("fx-quote-exchange-flow", ),
]

print("Rendering diagrams...")
for (name,) in pairs:
    render(f"{base}/diagrams/{name}.excalidraw", f"{base}/images/{name}.png")
print("Done.")

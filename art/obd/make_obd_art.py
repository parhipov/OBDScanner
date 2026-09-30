"""
Illustrations for the «Где разъём OBD» block of the guide tab: one picture per socket LOCATION (the car
database maps many cars onto one of these). Left-hand drive, automatic (2 pedals), neutral dark interior;
the OBD socket is lit up and an ELM327 adapter shows where it goes.

    python art/obd/make_obd_art.py                    # all locations
    python art/obd/make_obd_art.py console passenger  # some

Locations:
    left_door        under the dash, left of the steering column, towards the door / hood release
    under_column     right under the steering column
    right_of_column  under the dash between the column and the centre console
    left_cover       left of/under the column behind a fuse-box or trim cover (shown removed)
    console          in the centre console / under the ashtray / behind a tunnel trim (wide view)
    passenger        passenger side footwell / under the glovebox (wide view)

Writes art/obd/obd_loc_<id>.svg (source) and app/src/main/res/drawable-nodpi/obd_loc_<id>.webp, rendered
with headless Edge (needs Pillow for the WebP step). No text inside the pictures: captions are in
CarPicker.kt (translatable); which car uses which — `obd` in app/src/main/assets/cars/*.json.

The scene is one "world" drawn in 1080x660 driver-footwell units (column at x=600, dash lip ~y 300,
floor ~y 450); the centre console sits at x~1030..1400 and the passenger footwell to the right of it.
Each location picks a viewBox into that world: the driver-side ones show 0..1080 as before, the wide
ones pull the camera back (same output size) and draw the socket/adapter bigger so they stay readable.
"""
import math
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

W, H = 1080, 660
COLUMN = 600

# World extents (everything outside any viewBox is still painted so nothing shows through).
WX0, WY0, WX1, WY1 = -200, -500, 2500, 1300

# Bottom edge of the driver's knee panel: quadratic from P0 (right) through control P1 to P2 (left).
P0, P1, P2 = (1080, 262), (560, 350), (100, 300)
# Bottom edge of the passenger dash (glovebox side): from Q0 (right) through Q1 to Q2 (left, meets P0).
Q0, Q1, Q2 = (2500, 250), (1760, 352), (1080, 262)

# Centre console: stack face x range at the top and where it meets the tunnel.
STACK_L, STACK_R, STACK_BOTTOM = 1058, 1372, 470

DASH, DASH_HI, CARPET = "#303238", "#42454c", "#1f2023"


def quad_y(p0, p1, p2, x):
    """y of a quadratic Bezier (x decreasing from p0 to p2) at x, by bisection on t."""
    lo, hi = 0.0, 1.0
    for _ in range(60):
        t = (lo + hi) / 2
        bx = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
        if bx > x:
            lo = t
        else:
            hi = t
    t = (lo + hi) / 2
    return (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]


def lip_y(x):
    """Lower edge of the dash at x: driver knee panel left of the console, glovebox side right of it."""
    return quad_y(P0, P1, P2, x) if x <= P0[0] else quad_y(Q0, Q1, Q2, x)


def floor_y(x):
    """Where the carpet meets the firewall."""
    return 470 - 40 * x / 1080


DRIVER_VIEW = (0, 0, W, H)
WIDE_VIEW = (250, -40, 1640, 1640 * H / W)

LOCS = {
    # Door side of the column, above the footrest, next to the hood release.
    "left_door": dict(socket=(330, None), adapter=(0, 215), hood=166),
    # Straight under the column, above the brake pedal.
    "under_column": dict(socket=(600, None), adapter=(-235, 190)),
    # Between the column and the console, above the gas pedal; view nudged right to show the console.
    "right_of_column": dict(socket=(930, None), adapter=(75, 230), view=(120, 0, W, H)),
    # Left under the wheel behind a fuse-box cover, shown removed.
    "left_cover": dict(socket=(285, 232), covered=(175, 170, 220, 125), adapter=(20, 310)),
    # Front of the centre console, under the ashtray.
    "console": dict(socket=(1215, 345), adapter=(0, 270), view=WIDE_VIEW),
    # Passenger side, under the glovebox.
    "passenger": dict(socket=(1640, None), adapter=(-60, 300), view=WIDE_VIEW),
}


def shade(hex_color, k):
    """Lighter (k > 0) or darker (k < 0) version of a colour."""
    c = [int(hex_color[i:i + 2], 16) for i in (1, 3, 5)]
    c = [min(255, max(0, int(v + (255 - v) * k if k > 0 else v * (1 + k)))) for v in c]
    return "#%02x%02x%02x" % tuple(c)


def pedal(x, y, w, h, arm_top, arm_x=None):
    """A hanging pedal: metal arm from under the dash, rubber pad with ribs."""
    ax = arm_x if arm_x is not None else x
    ribs = "".join(
        f'<line x1="{x - w / 2 + 10}" y1="{y + 12 + i * (h - 24) / 5:.1f}" x2="{x + w / 2 - 10}" y2="{y + 12 + i * (h - 24) / 5:.1f}" '
        f'stroke="#0b0c0e" stroke-width="3" stroke-linecap="round" opacity=".55"/>'
        for i in range(6)
    )
    return f"""
    <path d="M{ax - 9},{arm_top} L{ax + 9},{arm_top} L{x + 8},{y + 6} L{x - 8},{y + 6} Z" fill="url(#metal)" mask="url(#armFade)"/>
    <g filter="url(#softShadow)">
      <rect x="{x - w / 2}" y="{y}" width="{w}" height="{h}" rx="14" fill="url(#rubber)"/>
    </g>
    {ribs}
    <rect x="{x - w / 2}" y="{y}" width="{w}" height="{h}" rx="14" fill="none" stroke="#ffffff" stroke-opacity=".06" stroke-width="2"/>"""


def socket(cx, cy, scale=1.0):
    """The 16-pin OBD-II socket: black trapezoid bezel, recess, two rows of pins."""
    w, h = 118 * scale, 54 * scale
    inset = 12 * scale
    pins = []
    for row in range(2):
        for i in range(8):
            px = cx - w / 2 + 22 * scale + i * (w - 44 * scale) / 7
            py = cy - 8 * scale + row * 16 * scale
            pins.append(f'<rect x="{px - 3.2 * scale:.1f}" y="{py - 4 * scale:.1f}" width="{6.4 * scale:.1f}" height="{8 * scale:.1f}" rx="1.5" fill="url(#pin)"/>')
    return f"""
    <g filter="url(#softShadow)">
      <path d="M{cx - w / 2 - 10 * scale},{cy - h / 2 - 8 * scale} L{cx + w / 2 + 10 * scale},{cy - h / 2 - 8 * scale}
               L{cx + w / 2 - 2 * scale},{cy + h / 2 + 8 * scale} L{cx - w / 2 + 2 * scale},{cy + h / 2 + 8 * scale} Z" fill="#141518"/>
    </g>
    <path d="M{cx - w / 2},{cy - h / 2} L{cx + w / 2},{cy - h / 2} L{cx + w / 2 - inset},{cy + h / 2} L{cx - w / 2 + inset},{cy + h / 2} Z"
          fill="#060607" stroke="#2a2c31" stroke-width="{2 * scale}"/>
    {''.join(pins)}
    <rect x="{cx - 9 * scale}" y="{cy - h / 2 - 8 * scale}" width="{18 * scale}" height="{6 * scale}" fill="#0a0b0c"/>"""


def adapter(x, y, angle, scale=1.0):
    """A cheap ELM327 dongle (translucent blue body, plug on top, green LED), turned by angle (clockwise)."""
    return f"""
    <g transform="translate({x:.1f},{y:.1f}) rotate({angle:.1f}) scale({.85 * scale:.3f})" filter="url(#softShadow)">
      <path d="M-50,-58 L50,-58 L42,-30 L-42,-30 Z" fill="#101114"/>
      <rect x="-70" y="-34" width="140" height="92" rx="16" fill="url(#dongle)"/>
      <rect x="-70" y="-34" width="140" height="92" rx="16" fill="none" stroke="#9fd0ff" stroke-opacity=".35" stroke-width="2"/>
      <rect x="-54" y="-20" width="108" height="10" rx="5" fill="#ffffff" opacity=".18"/>
      <circle cx="44" cy="36" r="6" fill="#5dff8a"/>
      <circle cx="44" cy="36" r="14" fill="#5dff8a" opacity=".25" filter="url(#glow)"/>
    </g>"""


def curve_points(fn, x_from, x_to, dy=0.0, step=20):
    """'L x,y' segments along fn(x)+dy from x_from to x_to (either direction)."""
    n = max(2, int(abs(x_to - x_from) / step))
    return " ".join(f"L{x_from + (x_to - x_from) * i / n:.1f},{fn(x_from + (x_to - x_from) * i / n) + dy:.1f}" for i in range(n + 1))


def console_art():
    """Centre stack (HVAC knobs, ashtray) and the tunnel with the selector, running down to the viewer."""
    L, R, B = STACK_L, STACK_R, STACK_BOTTOM
    mid = (L + R) / 2
    knobs = "".join(f"""
      <circle cx="{x}" cy="-8" r="34" fill="#141518" filter="url(#softShadow)"/>
      <circle cx="{x}" cy="-8" r="27" fill="url(#knob)"/>
      <circle cx="{x}" cy="-8" r="27" fill="none" stroke="#fff" stroke-opacity=".10" stroke-width="2"/>
      <rect x="{x - 3}" y="-32" width="6" height="14" rx="3" fill="#c9ced6" opacity=".55"/>""" for x in (mid - 95, mid, mid + 95))
    buttons = "".join(
        f'<rect x="{mid - 128 + i * 52}" y="56" width="44" height="22" rx="6" fill="#1a1b1f" stroke="#000" stroke-opacity=".5" stroke-width="2"/>'
        for i in range(5))
    return f"""
  <!-- Centre stack side (seen from the driver's seat), face, and the tunnel -->
  <path d="M{L - 30},{WY0} L{L},{WY0} L{L - 6},{B} L{L - 70},{WY1} L{L - 150},{WY1} L{L - 36},{B + 20} Z" fill="url(#stackSide)"/>
  <path d="M{R},{WY0} L{R + 26},{WY0} L{R + 34},{B + 20} L{R + 180},{WY1} L{R + 90},{WY1} L{R + 6},{B} Z" fill="{shade(DASH, -.6)}"/>
  <path d="M{L},{WY0} L{R},{WY0} L{R + 6},{B} L{L - 6},{B} Z" fill="url(#stackFace)"/>
  <path d="M{L},{WY0} L{R},{WY0} L{R + 6},{B} L{L - 6},{B} Z" fill="#fff" filter="url(#grain)"/>
  <path d="M{L + 3},{WY0} L{L - 3},{B}" stroke="#fff" stroke-opacity=".08" stroke-width="3"/>
  <!-- HVAC controls -->
  <rect x="{mid - 150}" y="-58" width="300" height="150" rx="18" fill="#000" opacity=".22"/>
  {knobs}
  {buttons}
  <!-- Ashtray / cubby -->
  <rect x="{mid - 110}" y="112" width="220" height="80" rx="14" fill="#0b0c0e"/>
  <rect x="{mid - 110}" y="112" width="220" height="80" rx="14" fill="url(#cavity)"/>
  <rect x="{mid - 110}" y="112" width="220" height="80" rx="14" fill="none" stroke="#000" stroke-width="4"/>
  <rect x="{mid - 110}" y="112" width="220" height="14" rx="8" fill="{shade(DASH, .1)}" opacity=".8"/>
  <!-- Tunnel top: rounded front edge, then the surface widening towards the viewer, selector -->
  <path d="M{L - 6},{B} L{R + 6},{B} L{R + 90},{WY1} L{L - 70},{WY1} Z" fill="url(#tunnel)"/>
  <path d="M{L - 6},{B} L{R + 6},{B} L{R + 90},{WY1} L{L - 70},{WY1} Z" fill="#fff" filter="url(#grain)"/>
  <path d="M{L - 6},{B - 4} L{R + 6},{B - 4} L{R + 9},{B + 16} L{L - 9},{B + 16} Z" fill="{shade(DASH, -.55)}"/>
  <path d="M{L - 6},{B - 5} L{R + 6},{B - 5}" stroke="#fff" stroke-opacity=".12" stroke-width="3"/>
  <g filter="url(#softShadow)">
    <rect x="{mid - 80}" y="710" width="160" height="230" rx="26" fill="#141518"/>
  </g>
  <rect x="{mid - 14}" y="742" width="28" height="170" rx="14" fill="#060607"/>
  <g filter="url(#softShadow)">
    <path d="M{mid - 20},830 L{mid + 20},830 L{mid + 16},770 L{mid - 16},770 Z" fill="url(#metal)"/>
    <ellipse cx="{mid}" cy="760" rx="46" ry="38" fill="url(#rubber)"/>
  </g>
  <ellipse cx="{mid - 10}" cy="746" rx="24" ry="10" fill="#fff" opacity=".10"/>"""


def passenger_art():
    """Glovebox on the passenger dash, a vent above it, a floor mat."""
    gx0, gx1, gy = 1470, 1800, 78
    bottom = curve_points(lambda x: lip_y(x) - 26, gx1, gx0)
    box = f"M{gx0 + 22},{gy} L{gx1 - 22},{gy} Q{gx1},{gy} {gx1},{gy + 22} {bottom} L{gx0},{gy + 22} Q{gx0},{gy} {gx0 + 22},{gy} Z"
    return f"""
  <!-- Glovebox -->
  <path d="{box}" fill="url(#glovebox)" stroke="#000" stroke-opacity=".6" stroke-width="4"/>
  <path d="{box}" fill="none" stroke="#fff" stroke-opacity=".05" stroke-width="2" transform="translate(0,3)"/>
  <rect x="{(gx0 + gx1) / 2 - 50}" y="{gy + 26}" width="100" height="26" rx="10" fill="#0d0e10"/>
  <rect x="{(gx0 + gx1) / 2 - 40}" y="{gy + 33}" width="80" height="12" rx="6" fill="{shade(DASH, .12)}"/>
  <!-- Vent above -->
  <rect x="{gx0 + 40}" y="-40" width="250" height="70" rx="16" fill="#0c0d0f" stroke="#000" stroke-opacity=".5" stroke-width="3"/>
  {''.join(f'<line x1="{gx0 + 56}" y1="{-26 + i * 14}" x2="{gx0 + 274}" y2="{-26 + i * 14}" stroke="#3a3c42" stroke-width="5" stroke-linecap="round"/>' for i in range(4))}"""


def svg(loc):
    c = LOCS[loc]
    vx, vy, vw, vh = c.get("view", DRIVER_VIEW)
    zoom = vw / W                       # >1 for the wide views: world units per output pixel
    k = 1.0 if zoom <= 1 else zoom * .82  # socket/adapter drawn bigger in wide views
    sx, sy = c["socket"]
    covered = c.get("covered")
    if sy is None:
        sy = lip_y(sx) + 34 * k
    dash, dash_hi, carpet = DASH, DASH_HI, CARPET

    # Automatic: footrest on the left, wide brake, gas.
    pedals = [pedal(640, 470, 150, 82, 338), pedal(860, 440, 64, 170, 318)]

    # Hood release lever on the end of the dash, near the door.
    hood = ""
    if c.get("hood"):
        hx = c["hood"]
        hy = lip_y(hx) + 6
        hood = f"""
        <g filter="url(#softShadow)">
          <rect x="{hx - 48}" y="{hy - 20}" width="96" height="40" rx="12" fill="#111215"/>
          <rect x="{hx - 38}" y="{hy + 4}" width="76" height="30" rx="10" fill="url(#rubber)"/>
        </g>
        <rect x="{hx - 30}" y="{hy + 8}" width="60" height="5" rx="2.5" fill="#fff" opacity=".12"/>
        <g transform="translate({hx - 16},{hy - 14})" fill="none" stroke="#c9ced6" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" opacity=".85">
          <path d="M2,20 L2,15 L8,9 L24,9 L30,15 L30,20 Z"/><path d="M8,9 L14,1"/>
          <circle cx="8" cy="21" r="2.5"/><circle cx="24" cy="21" r="2.5"/>
        </g>"""

    cover = removed = ""
    if covered:
        cx0, cy0, cw, ch = covered
        # The opening with the socket inside, and the removed cover lying next to it.
        cover = f"""
        <rect x="{cx0}" y="{cy0}" width="{cw}" height="{ch}" rx="16" fill="#08090b"/>
        <rect x="{cx0}" y="{cy0}" width="{cw}" height="{ch}" rx="16" fill="url(#cavity)"/>
        <rect x="{cx0}" y="{cy0}" width="{cw}" height="{ch}" rx="16" fill="none" stroke="#000" stroke-width="4"/>"""
        removed = f"""
        <g transform="translate({cx0 + cw + 50},{cy0 + 70}) rotate(16) scale(.8)" filter="url(#softShadow)" opacity=".92">
          <rect x="0" y="0" width="{cw}" height="{ch}" rx="16" fill="{shade(dash, .08)}"/>
          <rect x="2" y="2" width="{cw - 4}" height="{ch - 4}" rx="14" fill="none" stroke="#fff" stroke-opacity=".08" stroke-width="2"/>
          <rect x="{cw / 2 - 26}" y="{ch - 22}" width="52" height="10" rx="5" fill="#000" opacity=".45"/>
        </g>
        <path d="M{cx0 + cw + 8},{cy0 + ch / 2} q24,6 44,34" fill="none" stroke="#FFC857" stroke-width="5" stroke-dasharray="2 12" stroke-linecap="round" opacity=".9"/>"""

    # Adapter: at its own offset from the socket, plug turned to it, with a dashed "plug it in" path.
    dx, dy = c["adapter"]
    ax, ay = sx + dx, sy + dy
    angle = math.degrees(math.atan2(-dx, dy))
    ux, uy = -dx / math.hypot(dx, dy), -dy / math.hypot(dx, dy)
    x0, y0 = ax + ux * 62 * k, ay + uy * 62 * k
    ring = (86 if covered else 96) * k
    x1, y1 = sx - ux * (ring + 8 * zoom), sy - uy * (ring + 8 * zoom)
    bend = 30 * k if dx else 0
    z = max(zoom, 1.0)
    arrow = f"""
        <path d="M{x0:.0f},{y0:.0f} Q{(x0 + x1) / 2 + bend:.0f},{(y0 + y1) / 2:.0f} {x1:.0f},{y1:.0f}"
              fill="none" stroke="#FFC857" stroke-width="{6 * z:.1f}" stroke-dasharray="{2 * z:.1f} {14 * z:.1f}" stroke-linecap="round"/>
        <g transform="translate({x1:.0f},{y1:.0f}) rotate({angle:.1f}) scale({z:.2f})"><path d="M0,-4 l-14,22 l28,0 z" fill="#FFC857"/></g>"""

    # Dash panel outline: driver knee panel, then the passenger side, closed over the top.
    panel = (f"M100,{WY0} L{WX1},{WY0} L{WX1},{Q0[1]} Q{Q1[0]},{Q1[1]} {Q2[0]},{Q2[1]} "
             f"Q{P1[0]},{P1[1]} {P2[0]},{P2[1]} Z")
    lip_band = (f"M{WX1},{Q0[1]} Q{Q1[0]},{Q1[1]} {Q2[0]},{Q2[1]} Q{P1[0]},{P1[1]} {P2[0]},{P2[1]} "
                f"L{P2[0]},{P2[1] + 16} Q{P1[0]},{P1[1] + 18} {P0[0]},{P0[1] + 14} Q{Q1[0]},{Q1[1] + 18} {WX1},{Q0[1] + 14} Z")
    lip_hi = f"M{WX1},{Q0[1] - 3} Q{Q1[0]},{Q1[1] - 3} {Q2[0]},{Q2[1] - 3} Q{P1[0]},{P1[1] - 3} {P2[0]},{P2[1] - 3}"
    under = (f"M100,{P2[1] - 10} Q{P1[0]},{P1[1] - 10} {P0[0]},{P0[1] - 10} Q{Q1[0]},{Q1[1] - 10} {WX1},{Q0[1] - 10} "
             f"L{WX1},{Q0[1] + 150} Q{Q1[0]},{Q1[1] + 140} {P0[0]},{P0[1] + 150} Q{P1[0]},{P1[1] + 130} 100,{P2[1] + 120} Z")
    fl = lambda x: floor_y(x)  # noqa: E731
    carpet_poly = f"M{WX0},{fl(WX0):.0f} L{WX1},{fl(WX1):.0f} L{WX1},{WY1} L{WX0},{WY1} Z"
    carpet_dark = f"M{WX0},{fl(WX0):.0f} L{WX1},{fl(WX1):.0f} L{WX1},{fl(WX1) + 90:.0f} L{WX0},{fl(WX0) + 90:.0f} Z"

    wide = zoom > 1
    return f"""
<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="{vx:.0f} {vy:.0f} {vw:.0f} {vh:.0f}">
  <defs>
    <linearGradient id="well" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="{H}">
      <stop offset="0" stop-color="#07080a"/><stop offset=".55" stop-color="#101216"/><stop offset="1" stop-color="#15171b"/>
    </linearGradient>
    <linearGradient id="panel" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="350">
      <stop offset="0" stop-color="{dash_hi}"/><stop offset=".75" stop-color="{dash}"/><stop offset="1" stop-color="{shade(dash, -.35)}"/>
    </linearGradient>
    <linearGradient id="stackFace" gradientUnits="userSpaceOnUse" x1="0" y1="-100" x2="0" y2="{STACK_BOTTOM}">
      <stop offset="0" stop-color="{shade(dash_hi, .06)}"/><stop offset=".7" stop-color="{shade(dash, .02)}"/><stop offset="1" stop-color="{shade(dash, -.3)}"/>
    </linearGradient>
    <linearGradient id="stackSide" gradientUnits="userSpaceOnUse" x1="{STACK_L - 150}" y1="0" x2="{STACK_L}" y2="0">
      <stop offset="0" stop-color="{shade(dash, -.7)}"/><stop offset="1" stop-color="{shade(dash, -.35)}"/>
    </linearGradient>
    <linearGradient id="tunnel" gradientUnits="userSpaceOnUse" x1="0" y1="{STACK_BOTTOM}" x2="0" y2="1000">
      <stop offset="0" stop-color="{shade(dash, -.25)}"/><stop offset=".5" stop-color="{shade(dash_hi, .02)}"/><stop offset="1" stop-color="{shade(dash, -.1)}"/>
    </linearGradient>
    <linearGradient id="glovebox" gradientUnits="userSpaceOnUse" x1="0" y1="70" x2="0" y2="320">
      <stop offset="0" stop-color="{shade(dash_hi, .05)}"/><stop offset="1" stop-color="{shade(dash, -.2)}"/>
    </linearGradient>
    <radialGradient id="knob" cx=".4" cy=".35" r=".7">
      <stop offset="0" stop-color="#4a4d55"/><stop offset="1" stop-color="#1c1d21"/>
    </radialGradient>
    <linearGradient id="side" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="{shade(dash, -.45)}"/><stop offset="1" stop-color="{shade(dash, -.15)}"/>
    </linearGradient>
    <linearGradient id="column" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="{shade(dash, -.5)}"/><stop offset=".45" stop-color="{shade(dash_hi, .08)}"/><stop offset="1" stop-color="{shade(dash, -.55)}"/>
    </linearGradient>
    <linearGradient id="rim" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#1a1b1f"/><stop offset=".6" stop-color="#2d2f35"/><stop offset="1" stop-color="#101114"/>
    </linearGradient>
    <linearGradient id="metal" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#2a2c30"/><stop offset=".5" stop-color="#6b7079"/><stop offset="1" stop-color="#26282c"/>
    </linearGradient>
    <linearGradient id="rubber" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#34363b"/><stop offset="1" stop-color="#1b1c1f"/>
    </linearGradient>
    <linearGradient id="pin" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#f3e3b0"/><stop offset=".5" stop-color="#b89a52"/><stop offset="1" stop-color="#5e4c24"/>
    </linearGradient>
    <linearGradient id="dongle" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#3a8be0" stop-opacity=".95"/><stop offset="1" stop-color="#123a72" stop-opacity=".95"/>
    </linearGradient>
    <linearGradient id="sill" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#3b3e44"/><stop offset=".6" stop-color="#8a8f98"/><stop offset="1" stop-color="#2b2d31"/>
    </linearGradient>
    <linearGradient id="underDash" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#030304" stop-opacity=".97"/><stop offset=".55" stop-color="#030304" stop-opacity=".6"/>
      <stop offset="1" stop-color="#030304" stop-opacity="0"/>
    </linearGradient>
    <linearGradient id="fadeDown" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#fff" stop-opacity="0"/><stop offset=".7" stop-color="#fff" stop-opacity="1"/>
    </linearGradient>
    <mask id="armFade" maskContentUnits="objectBoundingBox"><rect width="1" height="1" fill="url(#fadeDown)"/></mask>
    <radialGradient id="cavity" cx=".5" cy=".3" r=".8">
      <stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".8"/>
    </radialGradient>
    <radialGradient id="torch" gradientUnits="userSpaceOnUse" cx="{sx}" cy="{sy:.0f}" r="{.42 * math.hypot(vw, vh) / math.sqrt(2):.0f}">
      <stop offset="0" stop-color="#fff6dc" stop-opacity=".38"/><stop offset=".35" stop-color="#fff6dc" stop-opacity=".15"/>
      <stop offset="1" stop-color="#fff6dc" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="vignette" cx=".5" cy=".45" r=".75">
      <stop offset=".55" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".55"/>
    </radialGradient>
    <filter id="softShadow" x="-30%" y="-30%" width="160%" height="170%">
      <feDropShadow dx="0" dy="10" stdDeviation="10" flood-color="#000" flood-opacity=".55"/>
    </filter>
    <filter id="glow" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="8"/></filter>
    <filter id="carpetNoise" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency="1.4" numOctaves="2" seed="3"/>
      <feColorMatrix values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 .55 0"/>
      <feComposite in2="SourceGraphic" operator="in"/>
    </filter>
    <filter id="grain" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="1" seed="8"/>
      <feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 .05 0"/>
      <feComposite in2="SourceGraphic" operator="in"/>
    </filter>
  </defs>

  <!-- Footwells, carpet, door sill -->
  <rect x="{WX0}" y="{WY0}" width="{WX1 - WX0}" height="{WY1 - WY0}" fill="url(#well)"/>
  <path d="{carpet_poly}" fill="{carpet}"/>
  <path d="{carpet_poly}" fill="#fff" filter="url(#carpetNoise)"/>
  <path d="{carpet_dark}" fill="#000" opacity=".35"/>
  <path d="M150,560 L330,540 L350,640 L165,660 Z" fill="#1a1b1e" filter="url(#softShadow)"/>
  {''.join(f'<line x1="{165 + i * 30}" y1="{556 - i * 3}" x2="{182 + i * 30}" y2="{650 - i * 3}" stroke="#000" stroke-opacity=".5" stroke-width="5"/>' for i in range(6))}
  <path d="M1500,560 L1900,548 L1960,{WY1} L1470,{WY1} Z" fill="#18191c" filter="url(#softShadow)"/>
  <path d="M1500,560 L1900,548 L1960,{WY1} L1470,{WY1} Z" fill="none" stroke="#000" stroke-opacity=".4" stroke-width="4"/>
  <path d="M0,380 L100,392 L78,{WY1} L0,{WY1} Z" fill="url(#sill)"/>
  <path d="M0,380 L100,392 L96,410 L0,400 Z" fill="#000" opacity=".4"/>

  {''.join(pedals)}
  <path d="{under}" fill="url(#underDash)"/>

  <!-- Dash end cap by the door, knee panel and passenger dash with their lip -->
  <path d="M0,{WY0} L118,{WY0} L118,0 L104,392 L0,378 Z" fill="url(#side)"/>
  <path d="{panel}" fill="url(#panel)"/>
  <path d="{lip_band}" fill="{shade(dash, -.55)}"/>
  <path d="{lip_hi}" fill="none" stroke="#fff" stroke-opacity=".10" stroke-width="3"/>
  <path d="{panel}" fill="#fff" filter="url(#grain)"/>
  <path d="M140,{lip_y(140) - 40:.0f} Q560,{lip_y(560) - 36:.0f} 1040,{lip_y(1040) - 40:.0f}" fill="none" stroke="#000" stroke-opacity=".35" stroke-width="2"/>
  {passenger_art()}
  {console_art()}
  {cover}
  {hood}

  <!-- Steering column and the lower part of the wheel -->
  <path d="M{COLUMN - 80},{WY0} L{COLUMN + 80},{WY0} L{COLUMN + 110},215 Q{COLUMN},250 {COLUMN - 110},215 Z" fill="url(#column)" filter="url(#softShadow)"/>
  <path d="M{COLUMN - 96},120 Q{COLUMN},134 {COLUMN + 96},120" fill="none" stroke="#000" stroke-opacity=".4" stroke-width="2"/>
  <path d="M{COLUMN - 40},{WY0} L{COLUMN + 40},{WY0} L{COLUMN + 70},118 L{COLUMN - 70},118 Z" fill="#1c1d21" opacity=".9"/>
  {f'<ellipse cx="{COLUMN}" cy="-200" rx="170" ry="120" fill="#1a1b1f" filter="url(#softShadow)"/>' if wide else ''}
  <ellipse cx="{COLUMN}" cy="-190" rx="400" ry="330" fill="none" stroke="#000" stroke-opacity=".45" stroke-width="72" filter="url(#glow)"/>
  <ellipse cx="{COLUMN}" cy="-196" rx="400" ry="330" fill="none" stroke="url(#rim)" stroke-width="60"/>
  <ellipse cx="{COLUMN}" cy="-196" rx="400" ry="330" fill="none" stroke="#ffffff" stroke-opacity=".08" stroke-width="3" stroke-dasharray="6 7"/>

  {removed}
  <!-- Torch light on the socket, the socket, highlight ring, adapter and its path -->
  <rect x="{vx:.0f}" y="{vy:.0f}" width="{vw:.0f}" height="{vh:.0f}" fill="url(#torch)"/>
  {socket(sx, sy, (.9 if covered else 1.0) * k)}
  <circle cx="{sx}" cy="{sy:.1f}" r="{ring:.0f}" fill="none" stroke="#FFC857" stroke-width="{14 * z:.0f}" opacity=".35" filter="url(#glow)"/>
  <circle cx="{sx}" cy="{sy:.1f}" r="{ring:.0f}" fill="none" stroke="#FFC857" stroke-width="{5 * z:.1f}"/>
  {arrow}
  {adapter(ax, ay, angle, k)}
  <rect x="{vx:.0f}" y="{vy:.0f}" width="{vw:.0f}" height="{vh:.0f}" fill="url(#vignette)"/>
</svg>"""


def render(loc):
    os.makedirs(RES, exist_ok=True)
    name = f"obd_loc_{loc}"
    src = os.path.join(HERE, f"{name}.svg")
    with open(src, "w", encoding="utf-8") as f:
        f.write(svg(loc).strip() + "\n")
    html = os.path.join(HERE, f".render_{name}.html")
    with open(html, "w", encoding="utf-8") as f:
        f.write(f'<html><body style="margin:0;background:#000"><img src="{name}.svg" width="{W}" height="{H}"></body></html>')
    png = os.path.join(HERE, f".render_{name}.png")
    subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--window-size={W},{H}",
                    f"--screenshot={png}", "file:///" + html.replace("\\", "/")], check=True, capture_output=True)
    os.remove(html)
    from PIL import Image
    out = os.path.join(RES, f"{name}.webp")
    Image.open(png).convert("RGB").save(out, "WEBP", quality=86, method=6)
    os.remove(png)
    print(out, os.path.getsize(out) // 1024, "KB")


if __name__ == "__main__":
    for name in sys.argv[1:] or LOCS:
        if name not in LOCS:
            sys.exit(f"unknown location {name!r}; known: {', '.join(LOCS)}")
        render(name)

#!/usr/bin/env python3
"""Reproducible static checks for the AndroMeld panel frontend.

Verifies the panel asset set, the extension layout/manifest, that the
local-adapter is wired into both entries, that no MV3-CSP violation exists
(inline script / inline handler / eval / new Function), that the adapter only
advertises backend-honest capabilities, and that the packaged extension is in
sync with app/src/main/assets/web.

Run:  python3 scripts/check-web.py
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "app/src/main/assets/web"
EXT = ROOT / "chrome-extension"

problems = []


def need(cond, msg):
    if not cond:
        problems.append(msg)


# 1. required panel assets ---------------------------------------------------
REQUIRED = [
    "index.html", "css/panel.css", "js/shell.js", "js/window-app.js",
    "js/local-adapter.js", "window/index.html", "h264-player.js",
    "manifest.webmanifest", "sw.js", "img/wallpaper.svg", "img/icebox.webp",
    "img/filterbox.webp", "icon-192.png", "icon-512.png", "icon-maskable-512.png",
    "favicon-16.png", "favicon-32.png", "apple-touch-icon.png",
]
for rel in REQUIRED:
    need((WEB / rel).exists(), f"missing panel asset {rel}")

# 2. adapter wired into both entries (before the module) ---------------------
idx = (WEB / "index.html").read_text(encoding="utf-8")
need(re.search(r'<script src="h264-player\.js(?:\?[^\"]+)?">', idx) is not None,
     "index.html missing h264-player.js")
need(re.search(r'<script src="js/local-adapter\.js(?:\?[^\"]+)?">', idx) is not None,
     "index.html missing local-adapter.js")
need(idx.index("js/local-adapter.js") < idx.index('type="module"'), "adapter must load before the shell module")
win = (WEB / "window/index.html").read_text(encoding="utf-8")
need(re.search(r'<script src="\.\./js/local-adapter\.js(?:\?[^\"]+)?">', win) is not None,
     "window/index.html missing local-adapter.js")

# 3. no MV3-CSP violations ---------------------------------------------------
for rel in ["index.html", "window/index.html"]:
    t = (WEB / rel).read_text(encoding="utf-8")
    need(not re.search(r"<script(?![^>]*\bsrc=)[^>]*>", t), f"{rel} has an inline <script>")
    need(not re.search(r"\son[a-z]+\s*=", t), f"{rel} has an inline event handler")
for rel in ["js/shell.js", "js/window-app.js", "js/local-adapter.js", "h264-player.js"]:
    t = (WEB / rel).read_text(encoding="utf-8")
    need("eval(" not in t, f"{rel} uses eval(")
    need("new Function" not in t, f"{rel} uses new Function")

# 4. patch-shell applied -----------------------------------------------------
rc = subprocess.run([sys.executable, str(ROOT / "scripts/patch-shell.py"), "--check"],
                    capture_output=True, text=True)
need(rc.returncode == 0, "patch-shell not applied: " + (rc.stderr or rc.stdout).strip())

# 5. adapter advertises only backend-honest caps -----------------------------
# The set mirrors the routes the backend actually registers (BlindCastServer):
#   video/control/app-list/multi-session/desk-widget + clipboard (/api/clipboard)
#   + file/fs (/api/fs/*) + terminal (/ws/terminal) + notification
#   (/api/notifications). audio/device-audio (AAC not wired to a decoder),
#   multi-touch (/ws/control has no pointer slot) and phone-screen
#   (mode:"mirror" is not an independent session) must NOT be advertised.
SUPPORTED_CAPS = {"video", "control", "multi-session", "app-list", "file", "fs",
                  "clipboard", "desk-widget", "terminal", "notification"}
UNSUPPORTED_CAPS = {"multi-touch", "phone-screen", "audio", "device-audio", "camera"}
ad = (WEB / "js/local-adapter.js").read_text(encoding="utf-8")
m = re.search(r"var CAPS\s*=\s*\[([^\]]*)\]", ad)
need(bool(m), "adapter CAPS not found")
if m:
    caps = set(re.findall(r'"([^"]+)"', m.group(1)))
    for bad in sorted(UNSUPPORTED_CAPS):
        need(bad not in caps, f"adapter advertises unsupported cap {bad}")
    for good in sorted(SUPPORTED_CAPS):
        need(good in caps, f"adapter missing supported cap {good}")
    need(caps == SUPPORTED_CAPS,
         f"adapter caps {sorted(caps)} != backend-honest {sorted(SUPPORTED_CAPS)}")
# advertised caps must be actually bridged, not merely declared
for route in ["api/clipboard", "api/fs/roots", "api/fs/list", "api/fs/mkdir",
              "api/fs/download", "api/fs/upload", "api/apps", "api/apps/icon",
              "api/desktop/windows", "ws/terminal", "api/notifications",
              "api/notifications/icon"]:
    need(route in ad, f"adapter advertises caps but does not bridge {route}")

# 6. extension layout --------------------------------------------------------
need((EXT / "console.html").exists(), "extension console.html missing")
need((EXT / "window/index.html").exists(), "extension window/index.html missing")
need(not (EXT / "panel").exists(), "stale extension panel/ directory present")
for gone in ["app.js", "sidepanel.html", "sidepanel.js", "background.js"]:
    need(not (EXT / gone).exists(), f"old BlindCast console file still present: {gone}")
mf = json.loads((EXT / "manifest.json").read_text(encoding="utf-8"))
need(mf.get("manifest_version") == 3, "manifest is not MV3")
need("BlindCast" not in json.dumps(mf), "manifest still references BlindCast")
need(mf.get("action", {}).get("default_popup") == "popup.html", "manifest popup is not popup.html")
ch = (EXT / "console.html").read_text(encoding="utf-8")
need(re.search(r'src="js/local-adapter\.js(?:\?[^\"]+)?"', ch) is not None,
     "extension console.html missing local-adapter.js")

# 7. extension in sync with the web panel ------------------------------------
rc = subprocess.run([sys.executable, str(ROOT / "scripts/sync-chrome-console.py"), "--check"],
                    capture_output=True, text=True)
need(rc.returncode == 0, "extension out of sync: " + (rc.stderr or rc.stdout).strip())

if problems:
    print("STATIC CHECK FAILED:")
    for p in problems:
        print("  - " + p)
    sys.exit(1)
print("Static checks passed")

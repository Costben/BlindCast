#!/usr/bin/env python3
"""Apply the minimal, documented patches that let the original AndroMeld panel
(v130 shell.js / window-app.js) run against the BlindCast local backend.

Every patch is anchored on an exact original substring and is idempotent.
Run:  python3 scripts/patch-shell.py [--check]

Patch rationale (see outputs/plans/frontend-contract-20261009-e0eac67.md §4):

  P0 release version   shell.js derives its release number from the URL path
                       (`/130/js/shell.js` -> 130). Served from `/js/shell.js`
                       it resolves to NaN, which breaks the welcome handshake
                       gate (`web !== us` -> upgrade page) and SW caching.
  P1 capability gate   `Bw()` returns "ok" only for secure contexts with
                       VideoDecoder. Over plain LAN HTTP it forces the
                       unsupported view, so the adapted page can never load.
  P2 session factory   `wP()` builds the RTC session. In local mode we inject
                       the adapter session instead (socketFactory seam).
  P3 version redirect  `OP()` navigates to `../<version>/` upstream. Must not
                       fire locally or it bounces the page off-device.
  P4 service worker    `v0()` registers the upstream SW; skip it locally so a
                       cached upstream shell can never shadow the adapted one.
  P5 telemetry         `rr()` reports errors to the upstream Firebase endpoint;
                       disable it locally so no device session data leaves.
  P6 firebase import   Skip the gstatic Firebase dynamic imports locally.
  P7 MSE decoder       `R0()` decodes with WebCodecs only. In non-secure
                       contexts fall back to the adapter's MSE decoder.
"""
import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "app/src/main/assets/web"

GUARD = "window.__blindcastLocal"

# (id, file, old, new)
PATCHES = [
    (
        "P12-http-pcm-player",
        "js/shell.js",
        'function yO(){let e=null,t=null,n=0;',
        f'function yO(){{if({GUARD})return {GUARD}.createPcmPlayer();let e=null,t=null,n=0;',
    ),
    (
        "P0-release-version",
        "js/shell.js",
        'us=Number(og[og.length-3]),_l=2',
        'us=Number(og[og.length-3])||130,_l=2',
    ),
    (
        "P1-capability-gate",
        "js/shell.js",
        'function Bw(e=window,t=navigator){let n=t?.userAgent||"";',
        f'function Bw(e=window,t=navigator){{if({GUARD})return ku;let n=t?.userAgent||"";',
    ),
    (
        "P1b-local-fusion-in-tab",
        "js/shell.js",
        'function Hi(){return S0()!=="browser"}',
        f'function Hi(){{return !!{GUARD}||S0()!=="browser"}}',
    ),
    (
        "P2-session-factory",
        "js/shell.js",
        'let t=new Bo({phase:e,codeKey:_E,signalKey:_r,turnUrl:Ws}),n=Us;',
        f'let t=({GUARD}?{GUARD}.createSession(e):new Bo({{phase:e,codeKey:_E,signalKey:_r,turnUrl:Ws}})),n=Us;',
    ),
    (
        "P3-version-redirect",
        "js/shell.js",
        'function OP(e,t=null,n=()=>!0){return!n()||',
        f'function OP(e,t=null,n=()=>!0){{if({GUARD})return!1;return!n()||',
    ),
    (
        "P4-service-worker",
        "js/shell.js",
        'function v0(){"serviceWorker"in navigator&&',
        f'function v0(){{if({GUARD})return;"serviceWorker"in navigator&&',
    ),
    (
        "P5-telemetry",
        "js/shell.js",
        'function rr(e,t={}){try{',
        f'function rr(e,t={{}}){{if({GUARD})return;try{{',
    ),
    (
        "P6-firebase-import",
        "js/shell.js",
        'import("https://www.gstatic.com/firebasejs/12.16.0/firebase-app.js"),'
        'import("https://www.gstatic.com/firebasejs/12.16.0/firebase-analytics.js")',
        'Promise.resolve(null),Promise.resolve(null)',
    ),
    (
        "P7-mse-decoder",
        "js/shell.js",
        'function R0(e,t,n=()=>{}){let i=e.getContext("2d"),',
        f'function R0(e,t,n=()=>{{}}){{if({GUARD}){{let _d={GUARD}.createDecoder(e,t,n);if(_d)return _d}}let i=e.getContext("2d"),',
    ),
    (
        # The RTC session's socket factory reaches into ctrlChannel()/
        # acquireMediaChannel(); the local Session only offers socketFactory(url).
        # WE() hands `e.socketFactory` to Hl() as the factory, so delegate there.
        "P8-socket-factory",
        "js/shell.js",
        'function eE(e){return t=>{let i=new URL(t).pathname;',
        f'function eE(e){{return t=>{{if({GUARD}&&e&&typeof e.socketFactory=="function")return e.socketFactory(t);let i=new URL(t).pathname;',
    ),
    (
        # Local boot: the connect flow otherwise waits for a pairing code and
        # never uses the ?token= the adapter holds. When a token is present,
        # verify it against the backend and go straight to a resume session.
        "P9-local-boot",
        "js/shell.js",
        'function qE(){let e=m0();if(e.route==="unsupported"){',
        f'function qE(){{if({GUARD}&&{GUARD}.auth&&{GUARD}.auth.token){{var _lt={GUARD}.auth.token;{GUARD}.auth.verify(_lt).then(function(_ok){{_ok?(Dd=!0,Ve.toConnecting(),UE({{auth:{{t:"resume",token:_lt}}}})):Ve.toConnectPage()}});return}}let e=m0();if(e.route==="unsupported"){{',
    ),
    (
        # The shell chooses the workspace mode at module top level with
        # `var tl=Hi()?qu()||await Xl():"desktop"`. qu() reads
        # localStorage["andromeld-workspace-mode"]; Xl() opens the workspace-mode
        # dialog and awaits a click. In local mode that await never settles, so
        # the module (and therefore the whole panel) hangs before ze.render()/
        # qE() ever run -- the page just sits on the default connect view.
        # Prefer the adapter's stored-or-default mode so boot proceeds; the
        # settings button still switches mode via the original dialog.
        "P10-workspace-mode",
        "js/shell.js",
        'var tl=Hi()?(qu()||(window.__blindcastLocal&&window.__blindcastLocal.workspaceMode&&window.__blindcastLocal.workspaceMode())||await Xl()):"desktop"',
        f'var tl=Hi()?(window.__blindcastLocal&&window.__blindcastLocal.workspaceMode&&window.__blindcastLocal.workspaceMode()||qu()||await Xl()):"desktop"',
    ),
    (
        # Keep workspace changes made by the original settings dialog in the
        # local adapter's own preference namespace. This lets a fresh browser
        # open Fusion while preserving an explicit later Desktop selection.
        "P11-local-workspace-preference",
        "js/shell.js",
        'function yb(e){ht("andromeld-workspace-mode",e)}',
        f'function yb(e){{ht("andromeld-workspace-mode",e);{GUARD}&&{GUARD}.setWorkspaceMode&&{GUARD}.setWorkspaceMode(e)}}',
    ),
    (
        # 面板「关于」里那张「更多 App」卡片列表整块删掉了（Mac / IceBox / FilterBox 是
        # 上游自己的产品），`#macDownload` 随之不存在；这条只在守卫它的读取口加一个判空，
        # 免得将来有人再调用 `macDownloadUrl()` 时直接对 null 取属性。
        "P13-about-mac-null",
        "js/shell.js",
        'macDownloadUrl:()=>ce.macDownload.getAttribute("href")||""',
        'macDownloadUrl:()=>ce.macDownload?ce.macDownload.getAttribute("href")||"":""',
    ),
    (
        # 关闭搜索面板时先给祖先写 aria-hidden、再恢复焦点，Chrome 会判定为把仍持有焦点的
        # 元素藏进 aria-hidden 子树并报「Blocked aria-hidden ... descendant retained focus」。
        # 同一行已经设了 inert，inert 子树本就不进辅助技术树、也不可聚焦，aria-hidden 属
        # 冗余写入；且全库无任何代码读取该属性，删掉这两处写入即可消掉告警。
        "P15-search-close-aria-hidden",
        "js/shell.js",
        'he=null,t.inert=!0,t.setAttribute?.("aria-hidden","true"),n.inert=!0,n.setAttribute?.("aria-hidden","true"),i.setAttribute("aria-expanded","false")',
        'he=null,t.inert=!0,n.inert=!0,i.setAttribute("aria-expanded","false")',
    ),
    (
        # 同上：关闭动画结束回调里也对 #searchSurface 重复写了一次 aria-hidden。
        "P15b-search-pe-aria-hidden",
        "js/shell.js",
        't.classList?.remove?.("is-closing"),e.classList?.remove?.("is-closing"),t.inert=!0,t.setAttribute?.("aria-hidden","true"),t.hidden=!0',
        't.classList?.remove?.("is-closing"),e.classList?.remove?.("is-closing"),t.inert=!0,t.hidden=!0',
    ),
]

# window-app.js is the Fusion per-app window runtime. It does not build its own
# session (it inherits the connection from window.opener.andromeldHub) and has no
# capability gate or SW registration, so only the decoder / telemetry / analytics
# exits need patching.
WINDOW_PATCHES = [
    (
        "P5-telemetry",
        "js/window-app.js",
        'var cs={gate:null,sender:null}',
        'var cs={gate:()=>!1,sender:null}',
    ),
    (
        "P6-firebase-import",
        "js/window-app.js",
        'import("https://www.gstatic.com/firebasejs/12.16.0/firebase-app.js"),'
        'import("https://www.gstatic.com/firebasejs/12.16.0/firebase-analytics.js")',
        'Promise.resolve(null),Promise.resolve(null)',
    ),
    (
        "P6b-analytics-send",
        "js/window-app.js",
        'async function Wh(e,t){try{return!await im()||!ur||!as()?!1:(ur.sdk.logEvent(ur.analytics,e,t),!0)}catch{return!1}}',
        'async function Wh(e,t){return!1}',
    ),
    (
        "P7-mse-decoder",
        "js/window-app.js",
        'function Vh(e,t,n=()=>{}){let i=e.getContext("2d"),',
        f'function Vh(e,t,n=()=>{{}}){{if({GUARD}){{let _d={GUARD}.createDecoder(e,t,n);if(_d)return _d}}let i=e.getContext("2d"),',
    ),
    (
        # 窗口标题栏（38px）盖在画布上：原版让设备端在虚拟屏顶部预留同样高度的内缩
        # （`decor-insets` → 原版 `FusionDecorInset` 线程「reserved top=…px on display …」），
        # 本适配层只把这条消息回显给面板，设备端没有预留，于是应用顶部被标题栏吃掉。
        # 这里改由面板自己让位：上报给设备的窗口高度减去标题栏，设备分辨率与画布区域
        # 重新同比例，`Jp(s)` 就是回显给设备的那同一个值，两边不会再漂。
        "P14-canvas-below-bar",
        "js/window-app.js",
        'function w($){if(t.kind!=="device")return;let{pkg:_,userId:S}=c.session,',
        'function w($){if(t.kind!=="device")return;let _bt=Jp(s);_bt>0&&($={w:$.w,h:Math.max(160,Math.round($.h-_bt))});let{pkg:_,userId:S}=c.session,',
    ),
]


def apply(text, patch_id, old, new, problems):
    if new in text and old not in text:
        return text, "already"
    if text.count(old) != 1:
        problems.append(f"{patch_id}: anchor count={text.count(old)} (expected 1)")
        return text, "skip"
    return text.replace(old, new, 1), "applied"


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true", help="report without writing")
    args = ap.parse_args()

    problems = []
    summary = []
    for patch_id, rel, old, new in PATCHES + WINDOW_PATCHES:
        path = WEB / rel
        if not path.exists():
            problems.append(f"{rel}: missing")
            continue
        text = path.read_text(encoding="utf-8")
        updated, state = apply(text, patch_id, old, new, problems)
        summary.append(f"{rel:20} {patch_id:22} {state}")
        if state == "applied" and not args.check:
            path.write_text(updated, encoding="utf-8")

    for line in summary:
        print(line)
    if problems:
        print("\nPROBLEMS:", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        return 1
    print(f"\nAll {len(PATCHES) + len(WINDOW_PATCHES)} patches OK"
          + (" (check only)" if args.check else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

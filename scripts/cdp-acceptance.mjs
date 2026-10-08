#!/usr/bin/env node
/* Real headed-Chrome acceptance probe over CDP (no browser-automation host).
 *
 * Chrome must already be running with --remote-debugging-port (headed, not
 * headless, GPU on). Each scenario opens a fresh tab, navigates, waits for the
 * panel handshake to settle, then evaluates a DOM/runtime snapshot and reports
 * any page error.
 *
 * Usage:
 *   node scripts/cdp-acceptance.mjs --port 9222 "label::url" ["label::url" ...]
 *
 * A literal __TOKEN__ in a url is replaced from $BC_TOKEN so the token never
 * appears on the command line or in this script's output.
 */
import { setTimeout as sleep } from "node:timers/promises";

const argv = process.argv.slice(2);
function opt(name, def) {
  const i = argv.indexOf("--" + name);
  return i >= 0 ? argv[i + 1] : def;
}
const port = opt("port", "9222");
const scenarios = argv
  .filter((a) => a.includes("::") && !a.startsWith("--"))
  .map((a) => {
    const at = a.indexOf("::");
    return { label: a.slice(0, at), url: a.slice(at + 2) };
  });
const TOKEN = process.env.BC_TOKEN || "";

class CDP {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl);
    this.id = 0;
    this.pending = new Map();
    this.handlers = [];
    this.ready = new Promise((res, rej) => {
      this.ws.onopen = () => res();
      this.ws.onerror = () => rej(new Error("CDP socket error"));
    });
    this.ws.onmessage = (m) => {
      const d = JSON.parse(m.data);
      if (d.id != null) {
        const p = this.pending.get(d.id);
        if (p) {
          this.pending.delete(d.id);
          d.error ? p.rej(new Error(JSON.stringify(d.error))) : p.res(d.result);
        }
      } else {
        for (const h of this.handlers) { try { h(d); } catch {} }
      }
    };
  }
  send(method, params, sessionId) {
    const id = ++this.id;
    const msg = { id, method, params: params || {} };
    if (sessionId) msg.sessionId = sessionId;
    this.ws.send(JSON.stringify(msg));
    return new Promise((res, rej) => this.pending.set(id, { res, rej }));
  }
  close() { try { this.ws.close(); } catch {} }
}

const PROBE = `(function(){
  var cs = function(el){ if(!el) return null; var c=getComputedStyle(el), r=el.getBoundingClientRect();
    return { display:c.display, visibility:c.visibility, w:Math.round(r.width), h:Math.round(r.height) }; };
  var views = [].slice.call(document.querySelectorAll('[id^="view-"]')).map(function(e){
    return { id:e.id, display:getComputedStyle(e).display, hidden:!!e.hidden,
             w:Math.round(e.getBoundingClientRect().width), h:Math.round(e.getBoundingClientRect().height) };
  });
  var A = window.__blindcastLocal || null;
  var sess = document.getElementById('view-session');
  return {
    path: location.pathname,
    secure: !!window.isSecureContext,
    videoDecoder: (typeof VideoDecoder!=='undefined'),
    h264Player: !!(window.H264Player),
    adapter: !!A,
    caps: (A && A.caps) ? A.caps : null,
    hasToken: A ? !!(A.auth && A.auth.hasToken()) : false,
    views: views,
    visibleViews: views.filter(function(v){ return v.display!=='none' && v.h>0; }).map(function(v){ return v.id; }),
    sessionState: sess ? (sess.dataset.controlState || sess.getAttribute('data-control-state') || '') : null,
    taskbar: cs(document.getElementById('taskbar')),
    stage: cs(document.getElementById('stage')),
    hub: cs(document.getElementById('hub')),
    wallpaper: cs(document.getElementById('desktopWallpaper')),
    deskLayer: cs(document.getElementById('deskLayer')),
    bodyData: Object.assign({}, document.body.dataset),
    bodyClass: document.body.className
  };
})()`;

const version = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
const browser = new CDP(version.webSocketDebuggerUrl);
await browser.ready;

const out = [];
for (const sc of scenarios) {
  const url = sc.url.replace("__TOKEN__", encodeURIComponent(TOKEN));
  const t = await browser.send("Target.createTarget", { url: "about:blank" });
  const att = await browser.send("Target.attachToTarget", { targetId: t.targetId, flatten: true });
  const sid = att.sessionId;
  const errors = [];
  browser.handlers.push((d) => {
    if (d.sessionId !== sid) return;
    if (d.method === "Runtime.exceptionThrown") {
      const ex = d.params.exceptionDetails.exception || {};
      errors.push("exception: " + (ex.description || d.params.exceptionDetails.text));
    } else if (d.method === "Runtime.consoleAPICalled" && d.params.type === "error") {
      errors.push("console.error: " + d.params.args.map((a) => a.value ?? a.description ?? "").join(" "));
    } else if (d.method === "Log.entryAdded" && d.params.entry.level === "error") {
      errors.push("log: " + d.params.entry.text);
    }
  });
  await browser.send("Page.enable", {}, sid);
  await browser.send("Runtime.enable", {}, sid);
  await browser.send("Log.enable", {}, sid);
  await browser.send("Page.navigate", { url }, sid);
  await sleep(4000);
  const r = await browser.send("Runtime.evaluate",
    { expression: PROBE, returnByValue: true, awaitPromise: true }, sid);
  out.push(Object.assign({ label: sc.label, host: new URL(url).host, pageErrors: errors },
    r.result && r.result.value ? r.result.value : { probeError: r } ));
  await browser.send("Target.closeTarget", { targetId: t.targetId });
}
browser.close();
console.log(JSON.stringify(out, null, 2));

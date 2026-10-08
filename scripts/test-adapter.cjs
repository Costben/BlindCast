#!/usr/bin/env node
/* Reproducible logic test for js/local-adapter.js (no browser needed).
 * Covers: secure-context shims (randomUUID / clipboard / OPFS), the real
 * auth handshake (resume -> /api/auth/status + /api/status -> welcome/deny),
 * the media attach handshake + per-session frame routing, the honest caps set,
 * clipboard (chan3) and fs (chan4) bridging, pixel->normalized input, the
 * device density used for the media config, safe window reconcile on a failed
 * poll, and clipboard/typing separation.
 * Run:  node scripts/test-adapter.cjs
 */
const fs = require("fs"), vm = require("vm");
const code = fs.readFileSync("app/src/main/assets/web/js/local-adapter.js", "utf8");

// --- fakes ---
class FakeWS {
  constructor(url){ this.url=url; this.readyState=0; this.sent=[]; this._h={}; FakeWS.all.push(this); setTimeout(()=>{this.readyState=1; this._fire("open",{});},0);}
  addEventListener(t,fn){(this._h[t]=this._h[t]||[]).push(fn);} removeEventListener(){}
  send(d){this.sent.push(d);} close(){this.readyState=3;} _fire(t,e){(this._h[t]||[]).forEach(f=>f(e));}
  set onopen(fn){ this._h.open=[fn]; } set onmessage(fn){ this._h.message=[fn]; }
  set onclose(fn){ this._h.close=[fn]; } set onerror(fn){ this._h.error=[fn]; }
}
FakeWS.all = [];

// --- backend mock (dispatched by path, so the real auth flow actually runs) --
let statusOk = true;                          // /api/status answer (token check)
let windowsPayload = { ok:true, windows:[] }; // /api/desktop/windows GET answer
let openWindowPayload = null;                 // /api/desktop/windows POST answer
let lastClipPost = null;
let lastNotifPost = null;
let lastNightPost = null;
let screenBlackedOut = false;                 // /api/screen answer (device screen off?)
function resp(status, body){
  return { ok: status>=200&&status<300, status,
    json: ()=>Promise.resolve(body), text: ()=>Promise.resolve(JSON.stringify(body)),
    arrayBuffer: ()=>Promise.resolve(new ArrayBuffer(0)) };
}
function respBuf(status, buf){
  return { ok: status>=200&&status<300, status,
    json: ()=>Promise.reject(new Error("not json")), arrayBuffer: ()=>Promise.resolve(buf),
    text: ()=>Promise.resolve("") };
}
function routeFetch(url, init){
  const u = String(url), method = (init && init.method) || "GET";
  const p = u.replace(/^https?:\/\/[^/]+/, "").split("?")[0];
  if (p === "/api/auth/status") return resp(200, { authRequired:true });
  if (p === "/api/status") return statusOk ? resp(200,{ok:true,batteryLevel:69,charging:false}) : resp(401,{ok:false,error:"invalid_token"});
  if (p === "/api/pair") return resp(200, { ok:true, token:"tok" });
  if (p === "/api/desktop") return resp(200, { ok:true, densityDpi:320, width:1080, height:2400 });
  if (p === "/api/display/night-mode") { lastNightPost = JSON.parse(init.body||"{}"); return resp(200,{ok:true}); }
  if (p === "/api/screen") return resp(200, { ok:true, blackedOut: screenBlackedOut });
  if (p === "/api/desktop/windows") {
    if (method === "GET") return resp(200, windowsPayload);
    return openWindowPayload ? resp(200, openWindowPayload) : resp(200, {ok:true});
  }
  if (p === "/api/apps") return resp(200, { ok:true, apps:[{package:"com.x",label:"X"}] });
  if (p === "/api/apps/icon") return respBuf(200, new Uint8Array([137,80,78,71]).buffer);
  if (p === "/api/clipboard") {
    if (method === "POST") { lastClipPost = JSON.parse(init.body||"{}"); return resp(200,{ok:true}); }
    return resp(200, { ok:true, text:"DEVCLIP", available:true, via:"direct" });
  }
  if (p === "/api/notifications") {
    if (method === "POST") { lastNotifPost = JSON.parse(init.body||"{}"); return resp(200,{ok:true}); }
    return resp(200, { ok:true, connected:true, count:1,
      active:[{ key:"k1", package:"com.a", title:"标题", text:"内容", time:123456, ongoing:false }], recent:[] });
  }
  if (p === "/api/notifications/icon") return respBuf(200, new Uint8Array([137,80,78,71]).buffer);
  if (p === "/api/fs/list") return resp(200, { ok:true, path:"/sdcard", entries:[{name:"a",dir:false,size:1,mtime:0,hidden:false,symlink:false}] });
  if (p === "/api/fs/upload") return /name=fail/.test(u) ? resp(500, { ok:false, error:"eacces" }) : resp(200, { ok:true });
  if (p.indexOf("/api/fs/") === 0) return resp(200, { ok:true });
  return resp(404, { ok:false, error:"not_found" });
}

const sandbox = {
  console, setTimeout, clearTimeout, setInterval, clearInterval, Promise, JSON, Math, Number, String, Object, Array, Error, Date,
  Uint8Array, DataView, BigInt, TextEncoder, TextDecoder, URLSearchParams, URL, Symbol, btoa, DOMException,
  File: class { constructor(parts,name){this.parts=parts;this.name=name;} },
  Blob: class { constructor(parts){this.parts=parts;} },
  location: { search:"?host=192.168.31.216:8888", origin:"http://192.168.31.216:8888", href:"http://192.168.31.216:8888/" },
  localStorage: { _d:{}, getItem(k){return this._d[k]||null;}, setItem(k,v){this._d[k]=v;}, removeItem(k){delete this._d[k];} },
  crypto: { getRandomValues(a){ for(let i=0;i<a.length;i++) a[i]=(i*7+3)&0xff; return a; } },   // no randomUUID
  navigator: { storage: {} },   // no clipboard, no getDirectory
  document: {
    _paste:[], addEventListener(t,fn){ if(t==="paste") this._paste.push(fn); },
    createElement(tag){ return { tag, style:{}, setAttribute(){}, select(){}, click(){}, value:"", remove(){} }; },
    body:{ appendChild(){}, removeChild(){} },
    execCommand(){ return true; }
  },
  requestAnimationFrame(){ return 0; }, cancelAnimationFrame(){},
  WebSocket: FakeWS,
  fetch: (url, init)=>Promise.resolve(routeFetch(url, init)),
};
sandbox.window = sandbox; sandbox.globalThis = sandbox; sandbox.self = sandbox;
sandbox.window.isSecureContext = false;
sandbox.window.devicePixelRatio = 3;   // deliberately != densityDpi/160 (2) so D10 is observable
sandbox.URL.createObjectURL = ()=> "blob:stub"; sandbox.URL.revokeObjectURL = ()=>{};

vm.createContext(sandbox);
vm.runInContext(code, sandbox);
const L = sandbox.window.__blindcastLocal;

const results = [];
function check(name, cond){ results.push((cond?"PASS":"FAIL")+"  "+name); if(!cond) process.exitCode=1; }
const tick = (n=1)=>new Promise(r=>{ let i=0; const s=()=>{ if(++i>=n) return r(); setTimeout(s,0); }; setTimeout(s,0); });
const decodeJson = (d)=>{ try{ return JSON.parse(d); }catch{ return null; } };

(async () => {
  check("adapter exposed", !!L && L.enabled);
  // shims
  check("crypto.randomUUID shim", typeof sandbox.crypto.randomUUID === "function" && /^[0-9a-f-]{36}$/.test(sandbox.crypto.randomUUID()));
  check("navigator.clipboard shim", !!sandbox.navigator.clipboard && typeof sandbox.navigator.clipboard.writeText==="function");
  await sandbox.navigator.clipboard.writeText("hello");
  check("clipboard writeText resolves + readText", (await sandbox.navigator.clipboard.readText())==="hello");
  check("storage.getDirectory shim", typeof sandbox.navigator.storage.getDirectory === "function");
  const dir = await sandbox.navigator.storage.getDirectory();
  const fh = await dir.getFileHandle(".andromeld-x.part", { create:true });
  const w = await fh.createWritable(); await w.write("abc"); await w.write("def"); await w.close();
  const file = await fh.getFile();
  check("opfs shim getFile returns File with chunks", file && file.parts.join("")==="abcdef");
  const names=[]; for await (const k of dir.keys()) names.push(k);
  check("opfs shim keys()", names.includes(".andromeld-x.part"));

  // session + sockets
  const s = L.createSession("bootstrap");
  const ws = s.socketFactory("/ws");
  const media1 = s.socketFactory("/media");
  const media2 = s.socketFactory("/media");
  check("ws socket is separate from media", ws !== media1);
  check("each /media call is a new socket", media1 !== media2);
  await tick(2);

  // real auth handshake: resume -> authStatus(true) -> /api/status(ok) -> welcome
  const wsMsgs=[]; ws.addEventListener("message", e=>wsMsgs.push(e.data));
  ws.send(JSON.stringify({ t:"resume", v:2, client:"x", token:"good" }));
  await tick(4);
  const welcome = wsMsgs.map(decodeJson).find(m=>m&&m.t==="welcome");
  check("resume with valid token -> welcome", !!welcome);
  const EXPECT_CAPS = ["video","control","multi-session","app-list","file","fs","clipboard","desk-widget","terminal","notification"];
  check("welcome caps = backend-honest set", welcome && JSON.stringify(welcome.caps)===JSON.stringify(EXPECT_CAPS));
  check("no unbacked caps advertised", welcome && !["multi-touch","phone-screen","audio","device-audio","camera"].some(c=>welcome.caps.includes(c)));

  // token rejection must deny (never a fake welcome)
  statusOk = false;
  const sBad = L.createSession("bootstrap");
  const wsBad = sBad.socketFactory("/ws"); await tick(2);
  const badMsgs=[]; wsBad.addEventListener("message", e=>badMsgs.push(e.data));
  wsBad.send(JSON.stringify({ t:"resume", token:"bad" })); await tick(4);
  const deny = badMsgs.map(decodeJson).find(m=>m&&m.t==="deny");
  check("resume with rejected token -> deny (no fake welcome)", !!deny && !badMsgs.map(decodeJson).some(m=>m&&m.t==="welcome"));
  statusOk = true;

  // attach handshake on media socket + D10 density in the media config
  const mMsgs=[]; media1.addEventListener("message", e=>mMsgs.push(e.data));
  media1.send(JSON.stringify({ t:"attach", proto:2, token:"local", sessionId:"7" }));
  await tick(4);
  const attached = mMsgs.map(decodeJson).find(m=>m&&m.t==="attached");
  check("media attach -> attached(sessionId)", attached && attached.sessionId==="7");
  check("session.mediaFor(7) bound", s.mediaFor(7)===media1);
  const cfg = mMsgs.map(b=>new Uint8Array(b)).find(u=>u[0]===1 && u[1]===1);
  check("media config envelope (chan1 type1)", !!cfg);
  check("D10 config dp = device densityDpi (320), not browser dpr*160",
    !!cfg && decodeJson(new TextDecoder().decode(cfg.subarray(2))).dp === 320);

  // frame routing through Bridge
  const bridge = ws._bridge;
  check("bridge created on welcome", !!bridge);
  mMsgs.length=0;
  bridge.link.emit("windowFrame", 7, { pts: 100, data: new Uint8Array([0,0,0,1,0x65,9,9]) });
  await tick(1);
  const frameEnv = mMsgs.map(b=>new Uint8Array(b)).find(u=>u[0]===1 && u[1]===2);
  check("window frame routed to attached media socket", !!frameEnv);
  if (frameEnv) {
    const dv = new DataView(frameEnv.buffer, frameEnv.byteOffset+2);
    check("frame pts at offset 8", Number(dv.getBigUint64(8))===100);
    check("frame key flag set (IDR)", frameEnv[2+16]===1);
    check("frame annexb start code at offset 17", frameEnv[2+17]===0 && frameEnv[2+20]===1 && frameEnv[2+21]===0x65);
  }

  // D3: failed poll must not close windows
  bridge.windows = { 1:{ windowId:1, packageName:"a", confirmed:true } };
  windowsPayload = { ok:false, error:"boom" };
  bridge.refreshWindows(); await tick(2);
  check("D3 failed poll keeps windows", !!bridge.windows[1]);
  // a window appears -> announced as "opening" (builds the .win), then promoted to "live"
  const dec=new TextDecoder(), ctrlStates=[];
  ws.addEventListener("message", e=>{ try{const u=new Uint8Array(e.data); const m=JSON.parse(dec.decode(u.subarray(2))); if(m.c==="window-state") ctrlStates.push(m);}catch{} });
  windowsPayload = { ok:true, windows:[{ windowId:2, state:"running", packageName:"p", width:10, height:20 }] };
  bridge.refreshWindows(); await tick(2);
  const w2open = ctrlStates.find(m=>String(m.sessionId)==="2" && m.state==="opening");
  check("new window announced as opening", !!w2open && w2open.pkg==="p" && w2open.w===10 && w2open.h===20);
  check("D3 success closes stale window 1", ctrlStates.some(m=>String(m.sessionId)==="1" && m.state==="closed"));
  bridge.refreshWindows(); await tick(2);
  check("window promoted to live on next poll", ctrlStates.some(m=>String(m.sessionId)==="2" && m.state==="live"));
  check("D3 success adds window 2", !!bridge.windows[2] && !bridge.windows[1]);

  // open-window command: POST open, then answer with an "opening" window-state
  // (the panel sends control over the SESSION socket, where the Bridge lives)
  openWindowPayload = { ok:true, window:{ windowId:9, displayId:111, packageName:"com.z", width:540, height:1200, state:"running" } };
  wsMsgs.length=0;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"open-window", pkg:"com.z", w:540, h:1200, userId:0 })));
  await tick(3);
  const opened = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4 && u[1]===1)
    .map(u=>decodeJson(dec.decode(u.subarray(2)))).find(m=>m&&m.c==="window-state"&&String(m.sessionId)==="9");
  check("open-window -> opening window-state", !!opened && opened.state==="opening" && opened.pkg==="com.z");

  // D9: canvas pixels -> normalized 0..1 for the target window's source size
  const ctlSends=[];
  bridge.link.control = { readyState:1, send(d){ ctlSends.push(d); } };
  s.setSize(7, 540, 1200);
  media1._onInput({ k:"pointer", a:"down", x:270, y:600 });
  media1._onInput({ k:"pointer", a:"up",   x:540, y:1200 });
  const ctlJson = ctlSends.map(decodeJson);
  const down = ctlJson.find(o=>o&&o.type==="down");
  check("D9 pointer down normalized to 0.5/0.5", down && down.x===0.5 && down.y===0.5 && down.wid===7);
  const upEv = ctlJson.find(o=>o&&o.type==="up");
  check("D9 pointer up clamps at 1/1", upEv && upEv.x===1 && upEv.y===1);
  // scroll emulation: down/move/up all normalized
  ctlSends.length=0;
  media1._onInput({ k:"scroll", x:270, y:600, dy:120 });
  const scrolls = ctlSends.map(decodeJson);
  check("D9 scroll emits normalized down/move/up",
    scrolls.length===3 && scrolls.every(o=>o.x>=0&&o.x<=1&&o.y>=0&&o.y<=1) && scrolls[0].type==="down");
  // key is a single Down+Up upstream; panel "up" must be swallowed
  ctlSends.length=0;
  media1._onInput({ k:"key", a:"down", code:29 });
  media1._onInput({ k:"key", a:"up", code:29 });
  const keys = ctlSends.map(decodeJson).filter(o=>o&&o.type==="key");
  check("D9 key down emits once, key up swallowed", keys.length===1 && keys[0].keycode===29);

  // D7/D8: clipboard (chan3) -> /api/clipboard, never keyboard text
  ctlSends.length=0; lastClipPost=null;
  media1._onEnvelope(3, 2, new TextEncoder().encode(JSON.stringify({ text:"TYPED" })));
  await tick(2);
  check("D8 chan3 type2 posts to /api/clipboard", !!lastClipPost && lastClipPost.text==="TYPED");
  check("D7 clipboard does not send keyboard text", !ctlSends.some(d=>String(d).includes("TYPED")));
  mMsgs.length=0;
  media1._onEnvelope(3, 1, new Uint8Array(0));
  await tick(2);
  const clipIn = mMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===3 && u[1]===1)
    .map(u=>decodeJson(dec.decode(u.subarray(2)))).find(o=>o&&o.text);
  check("D8 chan3 type1 pulls device clipboard", !!clipIn && clipIn.text==="DEVCLIP");

  // D8: fs bridge over chan4 -> /api/fs/*
  mMsgs.length=0;
  media1._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"fs-list", reqId:"r1", path:"/sdcard" })));
  await tick(2);
  const fsList = mMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4 && u[1]===1)
    .map(u=>decodeJson(dec.decode(u.subarray(2)))).find(o=>o&&o.c==="fs-list-result");
  check("D8 fs-list bridged to /api/fs/list", fsList && fsList.ok===true && fsList.entries && fsList.entries[0].name==="a");

  // D19: app-list entries must use the original wire field names -- displayName
  // (not label), plus userId/isSystemApp/appCategory/game (device model
  // p203x0/C2891d.java m5584m). The panel reads e.displayName; sending `label`
  // leaves every app nameless ("Ap" glyph, all bucketed into "other").
  wsMsgs.length=0;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"app-list" })));
  await tick(3);
  const alMsg = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1)
    .map(u=>decodeJson(dec.decode(u.subarray(2)))).find(o=>o&&o.c==="app-list");
  const a0 = alMsg && alMsg.apps && alMsg.apps[0];
  check("D19 app-list uses displayName + original fields (no `label`)",
    !!a0 && a0.packageName==="com.x" && a0.displayName==="X" && a0.userId===0 &&
    a0.isSystemApp===false && a0.appCategory===-1 && a0.game===false && a0.label===undefined);

  // D20: the app-icons REQUEST uses `items`, but the panel consumes the reply
  // as `{c:"app-icons", icons:[...]}` (shell: i.c===ou?fP(i.icons||[])).
  wsMsgs.length=0;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"app-icons", items:[{packageName:"com.x",userId:0}] })));
  await tick(3);
  const icMsg = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1)
    .map(u=>decodeJson(dec.decode(u.subarray(2)))).find(o=>o&&o.c==="app-icons");
  check("D20 app-icons reply uses `icons` (panel field), not `items`",
    !!icMsg && Array.isArray(icMsg.icons) && icMsg.items===undefined &&
    icMsg.icons[0] && icMsg.icons[0].packageName==="com.x" && typeof icMsg.icons[0].pngBase64==="string");

  // D11/D18: upload completion matches the original wire protocol
  // (RunnableC2905r.java) -- success -> {c:"upload-done",id,name,imported};
  // failure -> {c:"upload-failed",id,reason}; NEVER the success shape on failure.
  mMsgs.length=0;
  media1._onChan5(1, new TextEncoder().encode(JSON.stringify({ id:7, name:"a.bin" })));
  media1._onChan5(2, new Uint8Array([0,0,0,7, 1,2,3]));
  media1._onChan5(3, new TextEncoder().encode(JSON.stringify({ id:7 })));
  await tick(3);
  const upDone = mMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1)
    .map(u=>decodeJson(dec.decode(u.subarray(2)))).find(m=>m&&m.c==="upload-done");
  check("D11 upload success -> upload-done{id,name,imported} (no ok flag)",
    upDone && upDone.id===7 && upDone.name==="a.bin" && upDone.imported===false && upDone.ok===undefined);

  // D18: a device-side upload failure must NOT reuse the success回执
  mMsgs.length=0;
  media1._onChan5(1, new TextEncoder().encode(JSON.stringify({ id:8, name:"fail.bin" })));
  media1._onChan5(2, new Uint8Array([0,0,0,8, 9,9]));
  media1._onChan5(3, new TextEncoder().encode(JSON.stringify({ id:8 })));
  await tick(3);
  const upMsgs = mMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1)
    .map(u=>decodeJson(dec.decode(u.subarray(2)))).filter(m=>m);
  const upFail = upMsgs.find(m=>m.c==="upload-failed");
  check("D18 upload failure -> upload-failed{id,reason}", upFail && upFail.id===8 && upFail.reason==="eacces");
  check("D18 upload failure emits NO upload-done for that id", !upMsgs.some(m=>m.c==="upload-done"&&m.id===8));

  // terminal (chan4 JSON + chan5 type5) <-> /ws/terminal
  wsMsgs.length=0;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"term-open", reqId:"T1", cols:80, rows:24 })));
  await tick(2);
  const termWs = ws._termSocket;
  check("term-open opens /ws/terminal", !!termWs && /ws\/terminal/.test(termWs.url));
  check("term-open sent {type:open}", !!termWs && termWs.sent.some(s=>String(s).includes('"type":"open"')));
  if(termWs) termWs._fire("message", { data: JSON.stringify({ type:"opened", id:5 }) });
  await tick(1);
  const termOpened = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1)
    .map(u=>decodeJson(dec.decode(u.subarray(2)))).find(m=>m&&m.c==="term-opened");
  check("term-opened carries id + reqId", termOpened && termOpened.id===5 && termOpened.reqId==="T1");
  // D14: two concurrent opens, replies out of order -> reqId association (not FIFO)
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"term-open", reqId:"TA", cols:80, rows:24 })));
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"term-open", reqId:"TB", cols:80, rows:24 })));
  await tick(2);
  wsMsgs.length=0;
  if(termWs) termWs._fire("message", { data: JSON.stringify({ type:"opened", id:9, reqId:"TB" }) });
  await tick(1);
  const termB = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1)
    .map(u=>decodeJson(dec.decode(u.subarray(2)))).find(m=>m&&m.c==="term-opened");
  check("D14 term-opened pairs by reqId, not FIFO", termB && termB.id===9 && termB.reqId==="TB");
  wsMsgs.length=0;
  if(termWs) termWs._fire("message", { data: new Uint8Array([0,0,0,5, 104,105]).buffer });
  await tick(1);
  const termOut = wsMsgs.map(b=>new Uint8Array(b)).find(u=>u[0]===5 && u[1]===5);
  check("terminal output passthrough chan5 type5", !!termOut && termOut[2]===0 && termOut[5]===5);
  ws._onChan5(5, new Uint8Array([0,0,0,5, 104,105]));
  await tick(1);
  check("terminal input -> {type:input,id,data:base64}", !!termWs && termWs.sent.some(s=>String(s).includes('"type":"input"')&&String(s).includes("aGk=")));

  // notifications (chan4 JSON) <-> /api/notifications
  wsMsgs.length=0;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"notif-subscribe", on:true, reqId:"N1" })));
  await tick(3);
  const nMsgs = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1).map(u=>decodeJson(dec.decode(u.subarray(2))));
  const nState = nMsgs.find(m=>m&&m.c==="notif-state");
  check("notif-subscribe -> notif-state{on:true}", !!nState && nState.on===true && nState.reqId==="N1");
  const nSnap = nMsgs.find(m=>m&&m.c==="notif-snapshot");
  // D15: sections are bare arrays (panel ingestSnapshot -> wf expects arrays)
  check("notif snapshot sections are arrays", !!nSnap && Array.isArray(nSnap.active) &&
    Array.isArray(nSnap.hidden) && Array.isArray(nSnap.recent));
  check("notif snapshot maps active records", !!nSnap && nSnap.active.length===1 &&
    nSnap.active[0].packageName==="com.a" && !!nSnap.active[0].key && nSnap.recent.length===0);
  // D12: record fields must satisfy panel mM() validators
  const r0 = nSnap && nSnap.active[0];
  check("notif record D12 fields valid", !!r0 &&
    typeof r0.title==="string" && typeof r0.text==="string" && typeof r0.subText==="string" &&
    Number.isSafeInteger(r0.postTime) && Number.isSafeInteger(r0.updateTime) &&
    Number.isSafeInteger(r0.removedAt) && Number.isSafeInteger(r0.removalReason) &&
    Number.isSafeInteger(r0.userId) && typeof r0.ongoing==="boolean" &&
    r0.contentIntentTargetKind==="unknown" && Array.isArray(r0.actions));
  wsMsgs.length=0;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"notif-icon", reqId:"N2", keys:["k1"] })));
  await tick(3);
  const nIcon = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1).map(u=>decodeJson(dec.decode(u.subarray(2)))).find(m=>m&&m.c==="notif-icon-result");
  check("notif-icon -> notif-icon-result{id=key,base64}", !!nIcon && nIcon.icons[0].id==="k1" && typeof nIcon.icons[0].base64==="string" && nIcon.icons[0].base64.length>0);
  wsMsgs.length=0; lastNotifPost=null;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"notif-cmd", reqId:"N3", command:2, key:"k1" })));
  await tick(3);
  const nCmd = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1).map(u=>decodeJson(dec.decode(u.subarray(2)))).find(m=>m&&m.c==="notif-cmd-result");
  check("notif-cmd dismiss -> POST /api/notifications", !!nCmd && nCmd.ok===true && !!lastNotifPost && lastNotifPost.action==="dismiss" && lastNotifPost.key==="k1");
  // D13: command 7 (action-click) is not backed -> unsupported, and no dismiss POST
  wsMsgs.length=0; lastNotifPost=null;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"notif-cmd", reqId:"N5", command:7, key:"k1" })));
  await tick(3);
  const nUnsup = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1).map(u=>decodeJson(dec.decode(u.subarray(2)))).find(m=>m&&m.c==="notif-cmd-result");
  check("notif-cmd unsupported command -> ok:false, no POST", !!nUnsup && nUnsup.ok===false && nUnsup.command===7 && !lastNotifPost);
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"notif-subscribe", on:false, reqId:"N4" })));

  // device-info / display-night-mode / video-ack / audio control
  wsMsgs.length=0;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"device-info" })));
  await tick(2);
  const devInfo = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1).map(u=>decodeJson(dec.decode(u.subarray(2)))).find(m=>m&&m.c==="device-info");
  check("device-info -> {c:device-info, device.battery}",
    !!devInfo && devInfo.device && devInfo.device.battery && devInfo.device.battery.level===69 && devInfo.device.battery.charging===false);
  wsMsgs.length=0; lastNightPost=null;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"display-night-mode", on:true })));
  await tick(2);
  check("display-night-mode -> POST /api/display/night-mode", !!lastNightPost && lastNightPost.on===true);

  // D21: display-power reply must use the device-push shape {c:"display-power",on}
  // (panel consumer re(Re.on===!1)), NOT display-power-state with an `on` field
  // (that command's real shape is {displayId,state,reason}; C2867H:1378).
  wsMsgs.length=0; screenBlackedOut=false;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"display-power", on:true })));
  await tick(3);
  const dpOn = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1)
    .map(u=>decodeJson(dec.decode(u.subarray(2)))).find(m=>m&&m.c==="display-power");
  check("D21 display-power echoes {on:true} when device screen is on",
    !!dpOn && dpOn.on===true && dpOn.displayId===undefined && dpOn.state===undefined);
  wsMsgs.length=0; screenBlackedOut=true;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"display-power", on:false })));
  await tick(3);
  const dpOff = wsMsgs.map(b=>new Uint8Array(b)).filter(u=>u[0]===4&&u[1]===1)
    .map(u=>decodeJson(dec.decode(u.subarray(2)))).find(m=>m&&m.c==="display-power");
  check("D21 display-power echoes {on:false} when device screen is off",
    !!dpOff && dpOff.on===false);
  wsMsgs.length=0;
  ws._onEnvelope(4, 1, new TextEncoder().encode(JSON.stringify({ c:"video-ack", sessionId:"1", bytes:1 })));
  await tick(1);
  check("video-ack is a silent no-op", wsMsgs.length===0);
  wsMsgs.length=0;
  ws._onEnvelope(2, 3, new TextEncoder().encode(JSON.stringify({ on:true, sessionId:"1" })));
  await tick(1);
  check("audio control (chan2) swallowed, no reply", wsMsgs.length===0);

  // D4: ctl queues while control socket down
  const s2 = L.createSession("bootstrap");
  const before = s2.link._ctlQueue.length;
  s2.link.ctl({ type:"requestIDR" });
  check("D4 ctl queued when socket connecting", s2.link._ctlQueue.length===before+1);

  console.log(results.join("\n"));
  console.log(process.exitCode ? "\nSOME TESTS FAILED" : "\nALL ADAPTER TESTS PASS");
  process.exit(process.exitCode||0);
})();

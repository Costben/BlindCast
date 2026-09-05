"use strict";
/* =====================================================================
 * BlindCast 侧栏中控台（sidepanel.js）
 * 复用 console.html/app.js 的流解码与反控核心逻辑（逐行一致），差异仅：
 *  1) HOST 可变：CURRENT_HOST，随设备下拉切换（?host= 仅作初始值）；
 *  2) 多设备存储：chrome.storage.local devices:[{ip,name,lastSeen}] + currentHost，
 *     并兼容迁移 popup 旧历史 hosts/host；
 *  3) 局域网扫描：沿用 popup.js 30 并发池 + 800ms 超时，发现即入下拉框；
 *  4) 假遮罩修复：连接成功（双通道建立）自动隐藏 bootOverlay + 连接代际
 *     connEpoch 丢弃过期 onclose，不再“推流中断”卡死、无需点两次进入；
 *  5) ⛶ 弹出独立大标签页（console.html?host=）。
 * 侧栏属于 chrome-extension:// 安全源，WebCodecs 硬解全通，JPEG 降级保留。
 * MV3 CSP：零内联 script（本文件外联）、零 on*=、零 eval，全 addEventListener。
 * ===================================================================== */
const $ = id => document.getElementById(id);
const canvas = $("canvas"), ctx = canvas.getContext("2d");
const stage = $("stage");

function toast(msg, ms) {
  const d = document.createElement("div");
  d.className = "toast-msg"; d.textContent = msg;
  $("toast").appendChild(d);
  setTimeout(() => d.remove(), ms || 2600);
}
const clamp01 = v => v < 0 ? 0 : v > 1 ? 1 : v;
const hex = n => (n < 16 ? "0" : "") + n.toString(16);

/* ---------------- Token / 鉴权 ---------------- */
const qsToken = new URLSearchParams(location.search).get("token") || "";
let token = "";
try { token = localStorage.getItem("bc_token") || ""; } catch (e) { token = ""; }

/* ---------------- 可变 HOST（多设备核心） ----------------
 * 初始值：?host=（支持 console.html 同款深链）→ storage currentHost → ""。
 * 端口缺省 8888，可写 host=IP:端口。 */
const qsHostRaw = new URLSearchParams(location.search).get("host") || "";
function normHostPort(raw, defPort) {
  const port = defPort || 8888;
  let host = (raw || "").trim().replace(/^https?:\/\//, "").replace(/\/.*$/, "");
  if (!host) return "";
  if (host.indexOf(":") < 0) host += ":" + port;
  return host;
}
let CURRENT_HOST = normHostPort(qsHostRaw, 8888);

function apiUrl(path) {
  const base = CURRENT_HOST ? "http://" + CURRENT_HOST + "/" : "";
  const p = base + path;
  return token ? p + (p.includes("?") ? "&" : "?") + "token=" + encodeURIComponent(token) : p;
}
async function apiStatus() {
  const r = await fetch(apiUrl("api/auth/status"), { cache: "no-store" });
  return r.json();
}
async function apiVerify(t) {
  const r = await fetch(apiUrl("api/auth/verify?token=" + encodeURIComponent(t)), { cache: "no-store" });
  if (r.status === 200) return true;
  return false;
}
function rememberToken(t) {
  token = t;
  try { localStorage.setItem("bc_token", t); } catch (e) {}
}
function showAuth(msg) {
  $("authOverlay").classList.remove("hide");
  $("bootOverlay").classList.add("hide");
  if (msg) $("authErr").textContent = msg;
  setTimeout(() => $("tokenInput").focus(), 60);
}
function hideAuth() { $("authOverlay").classList.add("hide"); }
function showBoot(msg) {
  if (msg) $("bootMsg").innerHTML = '<span class="dot"></span>' + msg;
  $("bootOverlay").classList.remove("hide");
}
function hideBoot() { $("bootOverlay").classList.add("hide"); }

/* ---------------- 多设备存储 ---------------- */
const DEV_MAX = 20;
const SCAN_CONCURRENCY = 30;
const SCAN_TIMEOUT_MS = 800;
const SCAN_PORT = 8888;

function storeGet(keys, cb) {
  try {
    if (typeof chrome !== "undefined" && chrome.storage && chrome.storage.local) {
      chrome.storage.local.get(keys, function (v) { cb(v || {}); });
      return;
    }
  } catch (e) {}
  cb({});
}
function storeSet(obj) {
  try {
    if (typeof chrome !== "undefined" && chrome.storage && chrome.storage.local) {
      chrome.storage.local.set(obj);
    }
  } catch (e) {}
}
function storeGetP(keys) {
  return new Promise(resolve => storeGet(keys, resolve));
}

let devices = []; // [{ip,name,lastSeen}]

function upsertDevice(ip, opts) {
  ip = normHostPort(ip, SCAN_PORT);
  if (!ip) return null;
  const now = new Date().toISOString();
  const keepName = opts && opts.name;
  let found = null;
  const rest = [];
  for (const d of devices) {
    if (d && d.ip === ip) found = d;
    else if (d && d.ip) rest.push(d);
  }
  const name = keepName || (found && found.name) || ip;
  const entry = { ip, name, lastSeen: now };
  devices = [entry].concat(rest).slice(0, DEV_MAX);
  storeSet({ devices });
  return entry;
}

function renderDeviceSelect() {
  const sel = $("deviceSelect");
  sel.textContent = "";
  if (!devices.length) {
    const o = document.createElement("option");
    o.value = "";
    o.textContent = "＋ 先扫描或手动添加设备";
    sel.appendChild(o);
    sel.value = "";
    return;
  }
  for (const d of devices) {
    const o = document.createElement("option");
    o.value = d.ip;
    o.textContent = (d.name && d.name !== d.ip) ? (d.name + " · " + d.ip) : d.ip;
    sel.appendChild(o);
  }
  if (CURRENT_HOST && devices.some(d => d.ip === CURRENT_HOST)) {
    sel.value = CURRENT_HOST;
  } else if (CURRENT_HOST) {
    // 当前 host 不在列表（?host= 深链）：临时展示一行，不写库，等连接成功再入库
    const o = document.createElement("option");
    o.value = CURRENT_HOST;
    o.textContent = CURRENT_HOST + " · 新";
    sel.insertBefore(o, sel.firstChild);
    sel.value = CURRENT_HOST;
  } else {
    sel.value = devices[0].ip;
    CURRENT_HOST = devices[0].ip;
  }
  updateFoot();
}

function setCurrentHost(ip, opts) {
  ip = normHostPort(ip, SCAN_PORT);
  if (!ip) return false;
  CURRENT_HOST = ip;
  if (!opts || !opts.deferSave) {
    upsertDevice(ip, {});
    storeSet({ currentHost: ip });
  }
  renderDeviceSelect();
  updateFoot();
  return true;
}

function updateFoot() {
  try {
    $("footStat").textContent = CURRENT_HOST
      ? ("SidePanel · " + CURRENT_HOST)
      : "BlindCast SidePanel · 未选设备";
  } catch (e) {}
}

/* ---------------- 全局连接态（含假遮罩修复） ---------------- */
const S = {
  streamWs: null, ctlWs: null, connected: false,
  streamOpenTs: 0, firstFrameTs: 0,
  fpsCount: 0, lastLat: -1, lastStatus: null,
  audioEnabled: true, dragging: false, lastMoveTs: 0, textBuf: "", textTimer: 0,
  videoMode: (typeof VideoDecoder === "undefined" ? "jpeg" : "h264"), jpegBusy: false,
};
// 连接代际：每次 close/connect 自增，过期 socket 的 onclose/onmessage 直接丢弃，
// 杜绝“切设备/重连后旧 onclose 复活 bootOverlay”这类假遮罩。
let connEpoch = 0;

function setConn(on) {
  S.connected = on;
  const p = $("pillConn");
  p.textContent = on ? "● 已连接" : "○ 未连接";
  p.className = "pill " + (on ? "ok" : "bad");
}
function wsBase() {
  if (CURRENT_HOST) return "ws://" + CURRENT_HOST;
  return (location.protocol === "https:" ? "wss://" : "ws://") + location.host;
}

/* ---------------- H.264 解码管线 ---------------- */
const KIND_VIDEO = 1, KIND_AUDIO = 2, KIND_JPEG = 3;
const HAS_WEBCODECS = (typeof VideoDecoder !== "undefined" && typeof EncodedVideoChunk !== "undefined");
let vdec = null, vdecKey = "", vdecHasKey = false, videoTs = 0, lastVideoTsWall = 0;
let spsCache = null, ppsCache = null;
let vdecNoCodecToastAt = 0;

function splitAnnexB(u8) {
  const sc = [];
  for (let i = 0; i + 2 < u8.length; i++) {
    if (u8[i] === 0 && u8[i + 1] === 0) {
      if (u8[i + 2] === 1) { sc.push([i, 3]); i += 2; }
      else if (i + 3 < u8.length && u8[i + 2] === 0 && u8[i + 3] === 1) { sc.push([i, 4]); i += 3; }
    }
  }
  const out = [];
  if (!sc.length) { if (u8.length) out.push({ hdr: 0, start: 0, end: u8.length }); return out; }
  for (let k = 0; k < sc.length; k++) {
    const start = sc[k][0] + sc[k][1];
    const end = k + 1 < sc.length ? sc[k + 1][0] : u8.length;
    if (end > start) out.push({ hdr: start, start, end });
  }
  return out;
}
function eqBytes(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}
function buildAvcC(sps, pps) {
  const out = new Uint8Array(11 + sps.length + pps.length);
  out[0] = 1; out[1] = sps[1]; out[2] = sps[2]; out[3] = sps[3];
  out[4] = 0xFF; out[5] = 0xE1;
  out[6] = (sps.length >> 8) & 0xFF; out[7] = sps.length & 0xFF;
  out.set(sps, 8);
  let o = 8 + sps.length;
  out[o++] = 1;
  out[o++] = (pps.length >> 8) & 0xFF; out[o++] = pps.length & 0xFF;
  out.set(pps, o);
  return out;
}
function ensureDecoder() {
  if (typeof VideoDecoder === "undefined") {
    const now = Date.now();
    if (now - vdecNoCodecToastAt > 5000) { vdecNoCodecToastAt = now; toast("浏览器不支持 WebCodecs，已自动切 JPEG (🎞)"); }
    return false;
  }
  if (!spsCache || !ppsCache || spsCache.length < 4) return false;
  const key = spsCache.length + ":" + spsCache[1] + "," + spsCache[2] + "," + spsCache[3];
  if (vdec && vdec.state !== "closed" && vdecKey === key &&
      eqBytes(spsCache, ensureDecoder._sps) && eqBytes(ppsCache, ensureDecoder._pps)) return true;
  try { if (vdec) vdec.close(); } catch (e) {}
  vdec = null; vdecHasKey = false;
  const codec = "avc1." + hex(spsCache[1]) + hex(spsCache[2]) + hex(spsCache[3]);
  try {
    const dec = new VideoDecoder({ output: onVideoFrame, error: e => console.warn("[vdec]", e) });
    dec.configure({ codec, description: buildAvcC(spsCache, ppsCache), optimizeForLatency: true });
    vdec = dec; vdecKey = key;
    ensureDecoder._sps = spsCache.slice(); ensureDecoder._pps = ppsCache.slice();
    return true;
  } catch (e) { console.warn("[vdec configure]", e); return false; }
}
function onVideoFrame(frame) {
  try {
    const w = frame.displayWidth || frame.codedWidth, h = frame.displayHeight || frame.codedHeight;
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; fitCanvas(); }
    ctx.drawImage(frame, 0, 0, w, h);
    if (!S.firstFrameTs) {
      S.firstFrameTs = performance.now();
      const ms = Math.round(S.firstFrameTs - S.streamOpenTs);
      $("footFrame").textContent = "首帧 " + ms + "ms";
      hideBoot();
    }
    S.fpsCount++;
  } finally { try { frame.close(); } catch (e) {} }
}
function feedVideo(payload) {
  const nalus = splitAnnexB(payload);
  if (!nalus.length) return;
  let hasIdr = false, dirty = false;
  for (const n of nalus) {
    const t = payload[n.hdr] & 0x1F;
    if (t === 7) { const b = payload.slice(n.hdr, n.end); if (!eqBytes(b, spsCache)) { spsCache = b; dirty = true; } }
    else if (t === 8) { const b = payload.slice(n.hdr, n.end); if (!eqBytes(b, ppsCache)) { ppsCache = b; dirty = true; } }
    else if (t === 5) hasIdr = true;
  }
  if (dirty || !vdec || vdec.state === "closed") { if (!ensureDecoder()) return; }
  if (!vdec || vdec.state !== "playing" && vdec.state !== "configured") { if (!ensureDecoder()) return; }
  if (!hasIdr && !vdecHasKey) return;
  if (vdec.decodeQueueSize > 10 && !hasIdr) return;
  let total = 0;
  for (const n of nalus) total += 4 + (n.end - n.hdr);
  const mp4 = new Uint8Array(total);
  let o = 0;
  for (const n of nalus) {
    const len = n.end - n.hdr;
    mp4[o++] = (len >>> 24) & 0xFF; mp4[o++] = (len >>> 16) & 0xFF;
    mp4[o++] = (len >>> 8) & 0xFF; mp4[o++] = len & 0xFF;
    mp4.set(payload.subarray(n.hdr, n.end), o); o += len;
  }
  const wall = performance.now() * 1000;
  videoTs = Math.max(wall, lastVideoTsWall + 1); lastVideoTsWall = videoTs;
  try {
    vdec.decode(new EncodedVideoChunk({ type: (hasIdr || !vdecHasKey) ? "key" : "delta", timestamp: Math.round(videoTs), data: mp4 }));
    if (hasIdr) vdecHasKey = true;
  } catch (e) {}
}
function fitCanvas() {
  const vw = canvas.width || 9, vh = canvas.height || 20;
  const sw = stage.clientWidth, sh = stage.clientHeight;
  // 侧栏 ~400px 宽与 9:20 竖屏天然契合：等比充满，可用宽扣 dock 约 60px。
  // 侧栏无窄窗横条回退（始终右侧垂直 dock），故不做 matchMedia 分支。
  const dockW = 60;
  const scale = Math.min((sw - dockW) / vw, sh / vh);
  const w = Math.max(1, Math.floor(vw * scale)), h = Math.max(1, Math.floor(vh * scale));
  canvas.style.width = w + "px"; canvas.style.height = h + "px";
}
window.addEventListener("resize", fitCanvas);

/* ---------------- JPEG 降级管线 ---------------- */
function drawJpegBitmap(bmp) {
  try {
    const w = bmp.width || 720, h = bmp.height || 1600;
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; fitCanvas(); }
    ctx.drawImage(bmp, 0, 0, w, h);
    if (!S.firstFrameTs) {
      S.firstFrameTs = performance.now();
      const ms = Math.round(S.firstFrameTs - S.streamOpenTs);
      $("footFrame").textContent = "首帧 " + ms + "ms (JPEG)";
      hideBoot();
    }
    S.fpsCount++;
  } finally { try { bmp.close && bmp.close(); } catch (e) {} S.jpegBusy = false; }
}
function feedJpeg(payload) {
  let jpeg = payload;
  if (payload && payload.length >= 5) {
    const len = ((payload[0] << 24) >>> 0) + (payload[1] << 16) + (payload[2] << 8) + payload[3];
    if (len > 0 && len <= payload.length - 4) {
      jpeg = payload.subarray(4, 4 + len);
    }
  }
  if (!jpeg || !jpeg.length) return;
  if (S.jpegBusy) return;
  S.jpegBusy = true;
  try {
    const blob = new Blob([jpeg], { type: "image/jpeg" });
    if (typeof createImageBitmap === "function") {
      createImageBitmap(blob).then(drawJpegBitmap).catch(() => { S.jpegBusy = false; });
    } else {
      const url = URL.createObjectURL(blob);
      const img = new Image();
      img.onload = () => { try { drawJpegBitmap(img); } finally { try { URL.revokeObjectURL(url); } catch (e) {} } };
      img.onerror = () => { S.jpegBusy = false; try { URL.revokeObjectURL(url); } catch (e) {} };
      img.src = url;
    }
  } catch (e) { S.jpegBusy = false; }
}
function setVideoModeUI() {
  const b = $("btnJpeg");
  if (!b) return;
  const jpeg = S.videoMode === "jpeg";
  b.classList.toggle("on", jpeg);
  b.title = jpeg ? "当前 JPEG 降级 (点击切回 H264)" : "当前 H264 硬解 (点击切 JPEG)";
}
function declareVideoMode() {
  setVideoModeUI();
  sendCtl({ type: "videoMode", mode: S.videoMode });
}

/* ---------------- AAC 音频管线 ---------------- */
const ASC_CANDIDATES = [
  { tag: "48k Stereo",  sr: 48000, ch: 2, asc: new Uint8Array([0x11, 0x90]) },
  { tag: "44.1k Stereo", sr: 44100, ch: 2, asc: new Uint8Array([0x12, 0x10]) },
  { tag: "48k Mono",   sr: 48000, ch: 1, asc: new Uint8Array([0x11, 0x88]) },
  { tag: "44.1k Mono", sr: 44100, ch: 1, asc: new Uint8Array([0x12, 0x08]) },
];
let adec = null, ascIdx = 0, audioTs = 0, audioErrs = 0, audioOk = 0;
let actx = null, masterGain = null;

function ensureActx() {
  if (actx) { if (actx.state === "suspended") actx.resume().catch(() => {}); return true; }
  try {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return false;
    actx = new AC({ latencyHint: "interactive" });
    masterGain = actx.createGain();
    masterGain.gain.value = S.audioEnabled ? 1 : 0;
    masterGain.connect(actx.destination);
    if (actx.state === "suspended") actx.resume().catch(() => {});
    return true;
  } catch (e) { return false; }
}
function ensureAudioDecoder() {
  if (typeof AudioDecoder === "undefined") return false;
  if (adec && adec.state !== "closed") return true;
  const c = ASC_CANDIDATES[ascIdx % ASC_CANDIDATES.length];
  try {
    const d = new AudioDecoder({ output: playAudio, error: onAudioError });
    d.configure({ codec: "mp4a.40.2", sampleRate: c.sr, numberOfChannels: c.ch, description: c.asc });
    adec = d; audioTs = 0;
    return true;
  } catch (e) { return false; }
}
function onAudioError(e) {
  audioErrs++;
  if (audioErrs >= 12) {
    audioErrs = 0;
    ascIdx++;
    try { if (adec) adec.close(); } catch (x) {}
    adec = null;
    console.warn("[adec] rotate ASC ->", ASC_CANDIDATES[ascIdx % ASC_CANDIDATES.length].tag);
  }
}
function playAudio(data) {
  try {
    audioErrs = 0; audioOk++;
    if (!S.audioEnabled || !actx || actx.state !== "running") return;
    const c = ASC_CANDIDATES[ascIdx % ASC_CANDIDATES.length];
    const buf = actx.createBuffer(data.numberOfChannels, data.numberOfFrames, data.sampleRate || c.sr);
    for (let p = 0; p < buf.numberOfChannels; p++) {
      try { data.copyTo(buf.getChannelData(p), { planeIndex: p }); }
      catch (e) { data.copyTo(buf.getChannelData(0), { planeIndex: 0 }); break; }
    }
    const src = actx.createBufferSource();
    src.buffer = buf; src.connect(masterGain); src.start();
  } finally { try { data.close(); } catch (e) {} }
}
function feedAudio(payload) {
  if (!S.audioEnabled) return;
  if (!ensureActx() || !ensureAudioDecoder()) return;
  const c = ASC_CANDIDATES[ascIdx % ASC_CANDIDATES.length];
  audioTs += (1024 * 1000000) / c.sr;
  try {
    adec.decode(new EncodedAudioChunk({ type: "key", timestamp: Math.round(audioTs), data: payload }));
  } catch (e) {}
}

/* ---------------- 双 WS 连接（代际守卫 + 自动藏遮罩） ---------------- */
function sendCtl(obj) {
  if (!S.ctlWs || S.ctlWs.readyState !== 1) return false;
  try { S.ctlWs.send(JSON.stringify(obj)); return true; }
  catch (e) { return false; }
}
function closeSockets() {
  connEpoch++;
  setConn(false);
  try { if (S.streamWs) S.streamWs.close(); } catch (e) {}
  try { if (S.ctlWs) S.ctlWs.close(); } catch (e) {}
  S.streamWs = S.ctlWs = null;
  try { if (vdec) vdec.close(); } catch (e) {}
  vdec = null; vdecHasKey = false; spsCache = ppsCache = null;
  try { if (adec) adec.close(); } catch (e) {}
  adec = null;
  S.firstFrameTs = 0; videoTs = 0; lastVideoTsWall = 0; audioTs = 0;
  S.jpegBusy = false;
}
function connect() {
  if (!CURRENT_HOST) { showBoot("先在顶部选择或添加一台设备"); return; }
  closeSockets();
  ensureActx();
  setConn(false);
  setVideoModeUI();
  S.streamOpenTs = performance.now();
  $("footFrame").textContent = "frame --";
  const myEpoch = ++connEpoch;
  const isStale = () => myEpoch !== connEpoch;
  let streamUp = false, ctlUp = false;
  // 假遮罩修复核心：双通道一建立即藏遮罩，不等首帧；过期代际直接丢弃。
  const maybeUp = () => {
    if (isStale()) return;
    if (streamUp && ctlUp) {
      setConn(true);
      hideBoot();
      toast("● 双通道已建立 (" + S.videoMode + ") · " + CURRENT_HOST);
      startPing();
      pollStatus();
    }
  };

  const sws = new WebSocket(wsBase() + "/ws/stream" + (token ? "?token=" + encodeURIComponent(token) : ""));
  sws.binaryType = "arraybuffer";
  S.streamWs = sws;
  sws.onopen = () => { if (isStale() || S.streamWs !== sws) return; streamUp = true; maybeUp(); };
  sws.onmessage = ev => {
    if (isStale() || S.streamWs !== sws) return;
    if (typeof ev.data === "string") return;
    const buf = ev.data;
    if (!buf || buf.byteLength < 2) return;
    const u8 = new Uint8Array(buf);
    const kind = u8[0];
    const payload = u8.subarray(1);
    if (kind === KIND_VIDEO) { if (S.videoMode === "jpeg") return; feedVideo(payload); }
    else if (kind === KIND_AUDIO) feedAudio(payload);
    else if (kind === KIND_JPEG) { if (S.videoMode !== "jpeg") return; feedJpeg(payload); }
  };
  sws.onclose = () => {
    if (isStale() || S.streamWs !== sws) return;
    setConn(false);
    showBoot("推流中断，点击重连");
  };
  sws.onerror = () => { try { sws.close(); } catch (e) {} };

  const cws = new WebSocket(wsBase() + "/ws/control" + (token ? "?token=" + encodeURIComponent(token) : ""));
  S.ctlWs = cws;
  cws.onopen = () => {
    if (isStale() || S.ctlWs !== cws) return;
    ctlUp = true; maybeUp(); declareVideoMode();
    if (!S.audioEnabled) sendCtl({ type: "audio", enabled: false });
  };
  cws.onmessage = ev => {
    if (isStale() || S.ctlWs !== cws) return;
    let m = null;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (m && m.type === "pong") {
      const rtt = Math.round(performance.now() - pingSentTs);
      S.lastLat = rtt;
      $("pillLat").textContent = "延迟 " + rtt + "ms";
      $("pillLat").className = "pill " + (rtt < 120 ? "ok" : "");
    }
  };
  cws.onclose = () => { stopPing(); if (!isStale() && S.ctlWs === cws) setConn(false); };
  cws.onerror = () => { try { cws.close(); } catch (e) {} };
}

/* 按设备建连：先鉴权探测，再 connect；成功入库 + 藏遮罩逻辑走 connect/maybeUp */
async function connectTo(host) {
  host = normHostPort(host, SCAN_PORT);
  if (!host) { showBoot("先在顶部选择或添加一台设备"); return; }
  CURRENT_HOST = host;
  storeSet({ currentHost: host });
  renderDeviceSelect();
  updateFoot();
  hideAuth();
  showBoot("正在连接 " + host + " …");
  let st = null;
  try { st = await apiStatus(); }
  catch (e) {
    // 保持代际一致：此次失败不污染旧连接（已在 connect 前切断，故直接提示）
    showBoot("无法连接 " + host + "，检查手机服务与 Wi-Fi");
    return;
  }
  if (!st.authRequired) {
    if (qsToken) rememberToken(qsToken);
    upsertDevice(host, {});
    renderDeviceSelect();
    connect();
    startStatusPoll();
    return;
  }
  if (qsToken && await apiVerify(qsToken)) {
    rememberToken(qsToken);
    upsertDevice(host, {});
    renderDeviceSelect();
    connect();
    startStatusPoll();
    return;
  }
  if (token && await apiVerify(token)) {
    upsertDevice(host, {});
    renderDeviceSelect();
    connect();
    startStatusPoll();
    return;
  }
  showAuth("");
}

/* ---------------- 心跳延迟 / 状态轮询 ---------------- */
let pingTimer = 0, pingSentTs = 0;
function startPing() {
  stopPing();
  const beat = () => { if (S.ctlWs && S.ctlWs.readyState === 1) { pingSentTs = performance.now(); sendCtl({ type: "ping" }); } };
  beat();
  pingTimer = setInterval(beat, 2000);
}
function stopPing() { if (pingTimer) clearInterval(pingTimer); pingTimer = 0; }

let statusTimer = 0;
async function pollStatus() {
  if (!CURRENT_HOST) return;
  try {
    const r = await fetch(apiUrl("api/status"), { cache: "no-store" });
    if (!r.ok) return;
    const j = await r.json();
    S.lastStatus = j;
    const lvl = (typeof j.batteryLevel === "number") ? j.batteryLevel : -1;
    $("pillBat").textContent = lvl >= 0 ? (j.charging ? "⚡" : "") + "电量 " + lvl + "%" : "电量 --";
    $("pillBat").className = "pill " + (lvl >= 0 && lvl <= 20 && !j.charging ? "bad" : lvl >= 0 ? "ok" : "");
    const pw = $("btnPower");
    if (j.blackedOut) { pw.classList.add("warn-on"); pw.title = "点亮屏幕"; }
    else { pw.classList.remove("warn-on"); pw.title = "息屏挂机"; }
    if (typeof j.audioEnabled === "boolean" && j.audioEnabled !== S.audioEnabled) setAudioUI(j.audioEnabled);
  } catch (e) {}
}
function startStatusPoll() { if (!statusTimer) statusTimer = setInterval(pollStatus, 5000); }

setInterval(() => {
  $("pillFps").textContent = "FPS " + S.fpsCount;
  $("pillFps").className = "pill " + (S.fpsCount > 0 ? "ok" : "");
  S.fpsCount = 0;
}, 1000);

/* ---------------- 反向操控输入 ---------------- */
function normPos(e) {
  const r = canvas.getBoundingClientRect();
  return { x: clamp01((e.clientX - r.left) / r.width), y: clamp01((e.clientY - r.top) / r.height) };
}
canvas.addEventListener("contextmenu", e => e.preventDefault());
canvas.addEventListener("mousedown", e => { if (e.button === 1) e.preventDefault(); });
canvas.addEventListener("auxclick", e => e.preventDefault());

canvas.addEventListener("pointerdown", e => {
  ensureActx();
  try { canvas.focus(); } catch (x) {}
  if (e.button === 2) { sendCtl({ type: "click", button: "right" }); return; }
  if (e.button === 1) { sendCtl({ type: "click", button: "middle" }); return; }
  if (e.button !== 0) return;
  const p = normPos(e);
  S.dragging = true;
  try { canvas.setPointerCapture(e.pointerId); } catch (x) {}
  sendCtl({ type: "down", x: +p.x.toFixed(4), y: +p.y.toFixed(4) });
});
canvas.addEventListener("pointermove", e => {
  if (!S.dragging || e.buttons === 0 && e.pointerType === "mouse") return;
  const now = performance.now();
  if (now - S.lastMoveTs < 16) return;
  S.lastMoveTs = now;
  const p = normPos(e);
  sendCtl({ type: "move", x: +p.x.toFixed(4), y: +p.y.toFixed(4) });
});
function endDrag(e) {
  if (!S.dragging) return;
  S.dragging = false;
  if (e && e.button !== undefined && e.button !== 0) return;
  const p = e ? normPos(e) : { x: 0.5, y: 0.5 };
  sendCtl({ type: "up", x: +p.x.toFixed(4), y: +p.y.toFixed(4) });
}
canvas.addEventListener("pointerup", endDrag);
canvas.addEventListener("pointercancel", () => { if (S.dragging) { S.dragging = false; sendCtl({ type: "up", x: 0.5, y: 0.5 }); } });

canvas.addEventListener("wheel", e => {
  e.preventDefault();
  const horiz = Math.abs(e.deltaX) > Math.abs(e.deltaY);
  const d = horiz ? e.deltaX : e.deltaY;
  if (!d) return;
  const r = canvas.getBoundingClientRect();
  const rangePx = Math.min(220, Math.max(80, (horiz ? r.width : r.height) * 0.25));
  const sign = d > 0 ? 1 : -1;
  const p = normPos(e);
  let x0 = p.x, y0 = p.y, x1 = p.x, y1 = p.y;
  if (horiz) { const s = rangePx / r.width / 2; x0 = clamp01(p.x + s * sign); x1 = clamp01(p.x - s * sign); }
  else { const s = rangePx / r.height / 2; y0 = clamp01(p.y + s * sign); y1 = clamp01(p.y - s * sign); }
  sendCtl({ type: "down", x: +x0.toFixed(4), y: +y0.toFixed(4) });
  const steps = 3;
  for (let i = 1; i <= steps; i++) {
    setTimeout(((xx, yy) => () => sendCtl({ type: "move", x: +xx.toFixed(4), y: +yy.toFixed(4) }))
      (x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps), i * 18);
  }
  setTimeout(() => sendCtl({ type: "up", x: +x1.toFixed(4), y: +y1.toFixed(4) }), (steps + 1) * 18);
}, { passive: false });

const KEYMAP = {
  Enter: 66, Backspace: 67, Escape: 4, Tab: 61,
  ArrowUp: 19, ArrowDown: 20, ArrowLeft: 21, ArrowRight: 22,
  Home: 3, End: 123, PageUp: 92, PageDown: 93, Delete: 112,
};
function flushText() {
  if (!S.textBuf) return;
  const t = S.textBuf; S.textBuf = "";
  sendCtl({ type: "text", text: t });
}
document.addEventListener("keydown", e => {
  if (!$("authOverlay").classList.contains("hide")) return;
  const tag = (e.target && e.target.tagName) || "";
  if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
  if (!S.connected) return;
  if (e.ctrlKey || e.metaKey || e.altKey) return;
  if (e.key.length === 1) {
    S.textBuf += e.key;
    if (S.textTimer) clearTimeout(S.textTimer);
    S.textTimer = setTimeout(flushText, 80);
    e.preventDefault();
  } else if (KEYMAP[e.key] !== undefined) {
    flushText();
    sendCtl({ type: "key", keycode: KEYMAP[e.key] });
    e.preventDefault();
  }
});
document.addEventListener("keyup", () => {});

/* ---------------- 悬浮栏 ---------------- */
function setAudioUI(on) {
  S.audioEnabled = on;
  $("btnMute").classList.toggle("on", !on);
  $("btnMute").textContent = on ? "🎵" : "🔇";
  $("btnMute").title = on ? "音频传输开 (点击静音)" : "音频传输关 (点击恢复)";
  if (masterGain && actx) masterGain.gain.value = on ? 1 : 0;
}
$("btnBack").onclick = () => sendCtl({ type: "click", button: "right" });
$("btnHome").onclick = () => sendCtl({ type: "click", button: "middle" });
$("btnRecents").onclick = () => sendCtl({ type: "key", keycode: 187 });
$("btnVolUp").onclick = () => sendCtl({ type: "key", keycode: 24 });
$("btnVolDown").onclick = () => sendCtl({ type: "key", keycode: 25 });
$("btnMute").onclick = () => { const next = !S.audioEnabled; if (sendCtl({ type: "audio", enabled: next })) setAudioUI(next); else toast("控制通道未连接"); };
$("btnPower").onclick = async () => {
  try {
    const cur = S.lastStatus ? !!S.lastStatus.blackedOut : false;
    const r = await fetch(apiUrl("api/screen"), {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action: cur ? "on" : "off" }),
    });
    const j = await r.json().catch(() => ({}));
    if (r.ok && j.ok !== false) { toast(cur ? "⏻ 已点亮屏幕" : "⏻ 已息屏挂机"); pollStatus(); }
    else toast("屏幕切换失败: " + (j.error || j.detail || r.status));
  } catch (e) { toast("屏幕切换请求失败"); }
};
$("btnFull").onclick = () => {
  try {
    if (document.fullscreenElement) document.exitFullscreen();
    else stage.requestFullscreen();
  } catch (e) {}
};
$("btnKeys").onclick = () => { try { canvas.focus(); } catch (e) {} toast("键盘已聚焦，直接打字注入"); };
$("btnJpeg").onclick = () => {
  S.videoMode = (S.videoMode === "jpeg" ? "h264" : "jpeg");
  S.jpegBusy = false;
  declareVideoMode();
  toast(S.videoMode === "jpeg" ? "🎞 已切 JPEG 降级" : "🎞 已切回 H264 硬解");
};
$("btnReconnect").onclick = () => { if (CURRENT_HOST) connectTo(CURRENT_HOST); else showBoot("先选择设备"); };
$("btnEnter").onclick = () => {
  if (!CURRENT_HOST) { showBoot("先在顶部选择或添加一台设备"); return; }
  hideBoot();
  connectTo(CURRENT_HOST);
  startStatusPoll();
};

/* ---------------- 鉴权提交 ---------------- */
async function submitAuth() {
  const t = $("tokenInput").value.trim();
  if (!t) { $("authErr").textContent = "请输入 Token"; return; }
  $("authErr").textContent = "校验中…";
  if (await apiVerify(t)) {
    rememberToken(t);
    hideAuth();
    upsertDevice(CURRENT_HOST, {});
    renderDeviceSelect();
    connect();
    startStatusPoll();
  } else {
    $("authErr").textContent = "Token 错误，请重试";
    $("authCard").classList.remove("shake");
    void $("authCard").offsetWidth;
    $("authCard").classList.add("shake");
  }
}
$("btnAuth").onclick = submitAuth;
$("tokenInput").addEventListener("keydown", e => { if (e.key === "Enter") submitAuth(); });

/* ---------------- 局域网扫描（沿用 popup 30 并发池） ---------------- */
function normPrefix(raw) {
  const p = (raw || "").trim();
  if (/^\d+\.\d+\.\d+\.?$/.test(p)) {
    return p.charAt(p.length - 1) !== "." ? p + "." : p;
  }
  return null;
}
function probeIp(url) {
  let ctl = null;
  try { ctl = new AbortController(); } catch (e) { ctl = null; }
  let timer = 0;
  let done = false;
  function finishOk(authRequired) {
    if (done) return null;
    done = true;
    if (timer) clearTimeout(timer);
    return { authRequired };
  }
  let fetchP = null;
  try {
    const opt = { cache: "no-store" };
    if (ctl) {
      opt.signal = ctl.signal;
      timer = setTimeout(function () { try { ctl.abort(); } catch (e) {} }, SCAN_TIMEOUT_MS);
    } else {
      timer = setTimeout(function () {}, SCAN_TIMEOUT_MS);
    }
    fetchP = fetch(url, opt).then(
      function (r) {
        if (r.status !== 200) { if (timer) clearTimeout(timer); done = true; return null; }
        return r.json().then(
          function (j) { return finishOk(!!(j && j.authRequired)); },
          function () { return finishOk(false); }
        );
      },
      function () { if (timer) clearTimeout(timer); done = true; return null; }
    );
  } catch (e) {
    if (timer) clearTimeout(timer);
    fetchP = Promise.resolve(null);
  }
  if (!ctl) {
    const timeoutP = new Promise(function (res) {
      setTimeout(function () { if (!done) { done = true; res(null); } }, SCAN_TIMEOUT_MS);
    });
    return Promise.race([fetchP, timeoutP]);
  }
  return fetchP;
}

let scanning = false;
function setScanProgress(done, total) {
  const pct = total ? (done / total) * 100 : 0;
  $("scanBar").style.width = pct + "%";
  $("scanMeta").textContent = total ? (done + "/" + total) : "就绪";
}

function scanLan() {
  if (scanning) return;
  const prefix = normPrefix($("scanPrefix").value);
  if (!prefix) {
    $("scanMsg").textContent = "前缀像这样写：192.168.31.（三段数字+点）";
    $("scanPrefix").focus();
    return;
  }
  scanning = true;
  $("btnScan").disabled = true;
  $("scanMsg").textContent = "正在扫描 " + prefix + "1–254…";
  const total = 254;
  let done = 0, foundCount = 0;
  setScanProgress(0, total);
  $("btnScan").textContent = "扫描中 0/254";

  let next = 1;
  function worker() {
    function step() {
      if (next > total) return Promise.resolve();
      const i = next++;
      const ip = prefix + i;
      return probeIp("http://" + ip + ":" + SCAN_PORT + "/api/auth/status").then(function (r) {
        done++;
        setScanProgress(done, total);
        $("btnScan").textContent = "扫描中 " + done + "/254";
        if (r) {
          const host = ip + ":" + SCAN_PORT;
          upsertDevice(host, {});
          renderDeviceSelect();
          foundCount++;
          $("scanMsg").textContent = "扫到 " + foundCount + " 台，已自动加入下拉框（点下拉框切换）。";
          // 首次发现且当前无连接：自动连第一台，侧栏开箱即用
          if (!CURRENT_HOST) {
            CURRENT_HOST = host;
            storeSet({ currentHost: host });
            renderDeviceSelect();
            connectTo(host);
          }
        }
        return step();
      });
    }
    return step();
  }

  const workers = [];
  for (let k = 0; k < SCAN_CONCURRENCY; k++) workers.push(worker());
  Promise.all(workers).then(function () {
    scanning = false;
    $("btnScan").disabled = false;
    $("btnScan").textContent = "🔍 扫描";
    setScanProgress(total, total);
    if (!foundCount) {
      $("scanMsg").textContent = "没扫到手机：先确认手机和电脑同一 Wi-Fi，再核对前缀（多半 192.168.31. 或 192.168.1.），手机 App 服务开着。";
    } else {
      $("scanMsg").textContent = "扫到 " + foundCount + " 台，已加入下拉框，切换即连。";
      toast("🔍 扫到 " + foundCount + " 台设备");
    }
  });
}
$("btnScan").onclick = scanLan;
$("scanPrefix").addEventListener("keydown", e => { if (e.key === "Enter") scanLan(); });

/* ---------------- 顶部：切换 / 手动添加 / 弹出 ---------------- */
$("deviceSelect").addEventListener("change", e => {
  const v = e.target.value;
  if (!v) return;
  // 平滑切断旧 WS 并连新设备（closeSockets 代际自增，旧 onclose 自动作废）
  connectTo(v);
});
function addManual() {
  const raw = $("manualIp").value;
  const host = normHostPort(raw, SCAN_PORT);
  if (!host) { $("manualIp").focus(); toast("先输入局域网 IP"); return; }
  $("manualIp").value = "";
  upsertDevice(host, {});
  connectTo(host);
}
$("btnAdd").onclick = addManual;
$("manualIp").addEventListener("keydown", e => { if (e.key === "Enter") addManual(); });

function consoleUrl(host) {
  try {
    if (typeof chrome !== "undefined" && chrome.runtime && chrome.runtime.getURL) {
      return chrome.runtime.getURL("console.html?host=" + encodeURIComponent(host) + (token ? "&token=" + encodeURIComponent(token) : ""));
    }
  } catch (e) {}
  return "console.html?host=" + encodeURIComponent(host);
}
$("btnPopout").onclick = () => {
  if (!CURRENT_HOST) { toast("先选择一台设备再弹出"); return; }
  const url = consoleUrl(CURRENT_HOST);
  try {
    if (typeof chrome !== "undefined" && chrome.tabs && chrome.tabs.create) {
      chrome.tabs.create({ url });
      return;
    }
  } catch (e) {}
  window.open(url, "_blank");
};

/* ---------------- 启动 ---------------- */
async function bootSidepanel() {
  fitCanvas();
  updateFoot();
  // 载入多设备 + 迁移 popup 旧历史
  const v = await storeGetP(["devices", "currentHost", "hosts", "host"]);
  let stored = Array.isArray(v.devices) ? v.devices.filter(d => d && d.ip) : [];
  // 迁移 popup hosts[≤5]/host
  if (!stored.length && Array.isArray(v.hosts) && v.hosts.length) {
    stored = v.hosts
      .map(h => normHostPort(h, SCAN_PORT))
      .filter(Boolean)
      .map(ip => ({ ip, name: ip, lastSeen: new Date(0).toISOString() }));
  }
  devices = stored.slice(0, DEV_MAX);
  if (!CURRENT_HOST) {
    CURRENT_HOST = normHostPort(v.currentHost || v.host || "", SCAN_PORT) ||
      (devices.length ? devices[0].ip : "");
  }
  // ?host= 深链优先：入库并置顶
  if (qsHostRaw) {
    const deep = normHostPort(qsHostRaw, SCAN_PORT);
    if (deep) {
      CURRENT_HOST = deep;
      upsertDevice(deep, {});
      storeSet({ currentHost: deep });
    }
  }
  // 猜网段前缀：从当前设备 IP 段推导，缺省 192.168.31.
  try {
    const m = (CURRENT_HOST || (devices[0] && devices[0].ip) || "").match(/^(\d+\.\d+\.\d+)\./);
    if (m) $("scanPrefix").value = m[1] + ".";
  } catch (e) {}
  renderDeviceSelect();
  if (!CURRENT_HOST) {
    showBoot("下拉框为空：点 🔍 扫描局域网，或手动输入 IP 添加");
    return;
  }
  connectTo(CURRENT_HOST);
}
bootSidepanel();

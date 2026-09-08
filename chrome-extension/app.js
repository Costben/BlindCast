"use strict";
/* =====================================================================
 * BlindCast Web 控制台（插件版 app.js）
 * 由 app/src/main/assets/web/index.html 内联脚本导出，逻辑逐行一致，
 * 仅加一处差异：?host= 参数（插件页 origin 为 chrome-extension://，
 * 相对路径不可用，故全部请求拼绝对地址；普通网页打开时 HOST 为空保持原行为）。
 * 同步纪律：改网页端先改 index.html，再把 <script> 段整体拷过来并保留本补丁。
 * 后端协议严格对齐 core/server：
 *  - GET /api/auth/status -> {authRequired}            (AuthRoute)
 *  - GET /api/auth/verify?token= -> {ok}               (AuthRoute)
 *  - GET /api/status (鉴权) -> {blackedOut,batteryLevel...(DeviceApiRoute)
 *  - POST /api/screen {action} (鉴权)                  (DeviceApiRoute)
 *  - GET /api/stream -> {streaming} / POST /api/stream {action} (鉴权，远控串流开关)
 *  - WS /ws/stream: 首条文本 hello, 后续二进制 1字节通道头+负载
 *      0x01 H.264 Annex-B NALU(首包SPS/PPS+IDR) / 0x02 AAC裸帧 /
 *      0x03 JPEG单帧[4字节大端长+JPEG](无WebCodecs降级,Universal-1) (StreamWsRoute)
 *  - WS /ws/control: 文本JSON down/move/up/key/click/text/audio/videoMode/ping (ControlWsRoute)
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

/* ---------------- 插件 host 参数（本文件独有） ----------------
 * 用法：console.html?host=192.168.31.216（端口缺省 8888，可写 host=IP:端口）。
 * 为空时即普通网页行为（location.host）。 */
const qsHost = new URLSearchParams(location.search).get("host") || "";
const HOST = qsHost.includes(":") ? qsHost.replace(/^https?:\/\//, "").replace(/\/.*$/, "")
  : (qsHost ? qsHost + ":8888" : "");

function apiUrl(path) {
  const base = HOST ? "http://" + HOST + "/" : "";
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
/* 投屏开关门禁：串流没开时不让进黑屏，遮罩提示去插件/APP 打开，可一键远控开启 */
const STREAM_OFF_MSG = "当前投屏开关没有打开,请在插件或者 APP 里面打开.";
let streamOffShown = false;
function showStreamOff() {
  streamOffShown = true;
  showBoot(STREAM_OFF_MSG);
  $("btnEnter").style.display = "none";
  $("btnStreamOn").style.display = "";
}
function hideStreamOffRestore() {
  streamOffShown = false;
  $("btnEnter").style.display = "";
  $("btnStreamOn").style.display = "none";
}
async function queryStreaming() {
  try {
    const r = await fetch(apiUrl("api/stream"), { cache: "no-store" });
    if (!r.ok) return null; // 老版本无此接口：不过问，保持原流程
    const j = await r.json().catch(() => ({}));
    return (typeof j.streaming === "boolean") ? j.streaming : null;
  } catch (e) { return null; }
}
/* 进入门禁：开着进正常页，没开亮提示（老版本查不到状态直接放行） */
async function gateEnter(msg) {
  const st = await queryStreaming();
  if (st === false) { showStreamOff(); return; }
  hideStreamOffRestore();
  showBoot(msg);
}

/* ---------------- 全局连接态 ---------------- */
const S = {
  streamWs: null, ctlWs: null, connected: false,
  streamOpenTs: 0, firstFrameTs: 0,
  fpsCount: 0, lastLat: -1, lastStatus: null,
  audioEnabled: true, dragging: false, lastMoveTs: 0, textBuf: "", textTimer: 0,
  videoMode: (typeof VideoDecoder === "undefined" ? "jpeg" : "h264"), jpegBusy: false,
};
// SidePanel-1 假遮罩修复：连接代际，过期 socket 的 onclose 直接丢弃，
// 杜绝“重连后旧 onclose 复活 bootOverlay”卡死。
let connEpoch = 0;
function setConn(on) {
  S.connected = on;
  const p = $("pillConn");
  p.textContent = on ? "● 已连接" : "○ 未连接";
  p.className = "pill " + (on ? "ok" : "bad");
}
function wsBase() {
  if (HOST) return "ws://" + HOST;
  return (location.protocol === "https:" ? "wss://" : "ws://") + location.host;
}

/* ---------------- H.264 解码管线 ---------------- */
const KIND_VIDEO = 1, KIND_AUDIO = 2, KIND_JPEG = 3;
// Universal-1: 裸浏览器经 http 局域网为非安全源，VideoDecoder undefined 即黑屏，
// 故无硬解时自动订阅 JPEG (createImageBitmap→drawImage)，有硬解走老路不动。
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
      $("bootOverlay").classList.add("hide");
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
  if (!hasIdr && !vdecHasKey) return; // 等待关键帧（I帧间隔1s内自愈）
  if (vdec.decodeQueueSize > 10 && !hasIdr) return; // 背压：丢delta保实时
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
  } catch (e) { /* 坏帧丢弃，下轮I帧自愈 */ }
}
function fitCanvas() {
  const vw = canvas.width || 9, vh = canvas.height || 20;
  const sw = stage.clientWidth, sh = stage.clientHeight;
  // #dock 默认右侧垂直侧边栏：从可用宽扣栏宽（约 64px），高度全给画面；
  // 窄窗（<=720px）侧边栏回退到底部横条，改从可用高扣 76px。坐标映射走
  // getBoundingClientRect（normPos），换边不影响。
  const narrow = window.matchMedia && window.matchMedia("(max-width: 720px)").matches;
  const dockW = 64, dockH = 76;
  const scale = narrow ? Math.min(sw / vw, (sh - dockH) / vh)
                       : Math.min((sw - dockW) / vw, sh / vh);
  const w = Math.max(1, Math.floor(vw * scale)), h = Math.max(1, Math.floor(vh * scale));
  canvas.style.width = w + "px"; canvas.style.height = h + "px";
}
window.addEventListener("resize", fitCanvas);

/* ---------------- JPEG 降级管线 (Universal-1) ----------------
 * 服务端线格式 [1字节0x03+4字节大端长+JPEG]，此处剥长后
 * createImageBitmap→drawImage，Canvas 照常；与 H264 共存，有硬解走老路不动。 */
function drawJpegBitmap(bmp) {
  try {
    const w = bmp.width || 720, h = bmp.height || 1600;
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; fitCanvas(); }
    ctx.drawImage(bmp, 0, 0, w, h);
    if (!S.firstFrameTs) {
      S.firstFrameTs = performance.now();
      const ms = Math.round(S.firstFrameTs - S.streamOpenTs);
      $("footFrame").textContent = "首帧 " + ms + "ms (JPEG)";
      $("bootOverlay").classList.add("hide");
    }
    S.fpsCount++;
  } finally { try { bmp.close && bmp.close(); } catch (e) {} S.jpegBusy = false; }
}
function feedJpeg(payload) {
  // 剥 4 字节大端长（服务端沿用既有帧格式；长度不匹配则整包当 JPEG 容错）。
  let jpeg = payload;
  if (payload && payload.length >= 5) {
    const len = ((payload[0] << 24) >>> 0) + (payload[1] << 16) + (payload[2] << 8) + payload[3];
    if (len > 0 && len <= payload.length - 4) {
      jpeg = payload.subarray(4, 4 + len);
    }
  }
  if (!jpeg || !jpeg.length) return;
  if (S.jpegBusy) return; // 背压：上一帧还在解码则丢本帧保实时（服务端约10fps）
  S.jpegBusy = true;
  try {
    const blob = new Blob([jpeg], { type: "image/jpeg" });
    if (typeof createImageBitmap === "function") {
      createImageBitmap(blob).then(drawJpegBitmap).catch(() => { S.jpegBusy = false; });
    } else {
      // 极老浏览器回退：Image + objectURL（仍零依赖）。
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

/* ---------------- AAC 音频管线 ----------------
 * 服务端只下发AAC裸帧(无ADTS/无ASC, 默认48k立体声AAC-LC)。
 * 前端按默认ASC配置AudioDecoder, 连续出错则轮换常见ASC自愈。 */
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
  if (audioErrs >= 12) { // 连续坏帧: 大概率ASC错配, 轮换候选
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

/* ---------------- 双 WS 连接 ---------------- */
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
  closeSockets();
  ensureActx();
  setConn(false);
  setVideoModeUI();
  S.streamOpenTs = performance.now();
  $("footFrame").textContent = "frame --";
  const myEpoch = ++connEpoch;
  const isStale = () => myEpoch !== connEpoch;
  let streamUp = false, ctlUp = false;
  // SidePanel-1 假遮罩修复：双通道一建立即自动隐藏 bootOverlay，无需点两次进入。
  const maybeUp = () => {
    if (isStale()) return;
    if (streamUp && ctlUp) {
      setConn(true); hideBoot();
      toast("● 双通道已建立 (" + S.videoMode + ")");
      startPing(); pollStatus();
    }
  };

  const sws = new WebSocket(wsBase() + "/ws/stream" + (token ? "?token=" + encodeURIComponent(token) : ""));
  sws.binaryType = "arraybuffer";
  S.streamWs = sws;
  sws.onopen = () => { if (isStale() || S.streamWs !== sws) return; streamUp = true; maybeUp(); };
  sws.onmessage = ev => {
    if (isStale() || S.streamWs !== sws) return;
    if (typeof ev.data === "string") return; // hello自描述, 格式固定可忽略
    const buf = ev.data;
    if (!buf || buf.byteLength < 2) return;
    const u8 = new Uint8Array(buf);
    const kind = u8[0];
    const payload = u8.subarray(1);
    if (kind === KIND_VIDEO) { if (S.videoMode === "jpeg") return; feedVideo(payload); }
    else if (kind === KIND_AUDIO) feedAudio(payload);
    else if (kind === KIND_JPEG) { if (S.videoMode !== "jpeg") return; feedJpeg(payload); }
  };
  sws.onclose = () => { if (isStale() || S.streamWs !== sws) return; setConn(false); hideStreamOffRestore(); showBoot("推流中断，点击重连"); };
  sws.onerror = () => { try { sws.close(); } catch (e) {} };

  const cws = new WebSocket(wsBase() + "/ws/control" + (token ? "?token=" + encodeURIComponent(token) : ""));
  S.ctlWs = cws;
  cws.onopen = () => { if (isStale() || S.ctlWs !== cws) return; ctlUp = true; maybeUp(); declareVideoMode(); if (!S.audioEnabled) sendCtl({ type: "audio", enabled: false }); };
  cws.onmessage = ev => {
    if (isStale() || S.ctlWs !== cws) return;
    let m = null;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (m && m.type === "pong" && typeof m._t === "undefined") { /* 服务端pong无回显字段, 用到达计时 */ }
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
    const pwLabel = pw.querySelector("span");
    if (pwLabel) pwLabel.textContent = j.blackedOut ? "点亮" : "息屏";
    if (typeof j.streaming === "boolean") {
      updateStreamUI(j.streaming);
      // 会话中途被关（插件/APP 侧手动关）：亮提示遮罩；那边再打开后自动重连续流。
      if (!j.streaming && S.connected) showStreamOff();
      else if (j.streaming && streamOffShown && S.connected) {
        hideStreamOffRestore();
        hideBoot();
        connect();
      }
    }
    if (typeof j.audioEnabled === "boolean" && j.audioEnabled !== S.audioEnabled) setAudioUI(j.audioEnabled);
  } catch (e) {}
}
function startStatusPoll() { if (!statusTimer) statusTimer = setInterval(pollStatus, 5000); }

/* FPS 统计 */
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
canvas.addEventListener("mousedown", e => { if (e.button === 1) e.preventDefault(); }); // 拦截中键自动滚动
canvas.addEventListener("auxclick", e => e.preventDefault());

canvas.addEventListener("pointerdown", e => {
  ensureActx();
  try { canvas.focus(); } catch (x) {}
  if (e.button === 2) { sendCtl({ type: "click", button: "right" }); return; } // 右键=返回
  if (e.button === 1) { sendCtl({ type: "click", button: "middle" }); return; } // 中键=Home
  if (e.button !== 0) return;
  const p = normPos(e);
  S.dragging = true;
  try { canvas.setPointerCapture(e.pointerId); } catch (x) {}
  sendCtl({ type: "down", x: +p.x.toFixed(4), y: +p.y.toFixed(4) });
});
canvas.addEventListener("pointermove", e => {
  if (!S.dragging || e.buttons === 0 && e.pointerType === "mouse") return;
  const now = performance.now();
  if (now - S.lastMoveTs < 16) return; // ~60Hz节流
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

/* 滚轮 = 定向滑动 */
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

/* 键盘: 可打印字符走text批量注入, 功能键走key */
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
  if (!$("authOverlay").classList.contains("hide")) return; // 鉴权框内不拦截
  const tag = (e.target && e.target.tagName) || "";
  if (tag === "INPUT" || tag === "TEXTAREA") return;
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
  const btn = $("btnMute");
  btn.classList.toggle("on", !on);
  const icon = btn.querySelector("i");
  if (icon) icon.textContent = on ? "🎵" : "🔇";
  btn.title = on ? "音频传输开 (点击静音)" : "音频传输关 (点击恢复)";
  if (masterGain && actx) masterGain.gain.value = on ? 1 : 0;
}
/* 停止/开启投屏（远控设备端串流采集，不关端口；画面定格，重开后自动续流） */
function updateStreamUI(streaming) {
  const btn = $("btnStream");
  if (!btn) return;
  const icon = btn.querySelector("i"), label = btn.querySelector("span");
  if (streaming) {
    if (icon) icon.textContent = "⏹";
    if (label) label.textContent = "停止投屏";
    btn.classList.add("warn-on");
    btn.title = "停止投屏采集（省电，画面定格，可再点开启）";
  } else {
    if (icon) icon.textContent = "▶";
    if (label) label.textContent = "开启投屏";
    btn.classList.remove("warn-on");
    btn.title = "开启投屏采集";
  }
}
async function toggleStream() {
  const btn = $("btnStream");
  if (btn) btn.disabled = true;
  try {
    const cur = (S.lastStatus && typeof S.lastStatus.streaming === "boolean") ? S.lastStatus.streaming : null;
    const action = cur === null ? "toggle" : (cur ? "off" : "on");
    const r = await fetch(apiUrl("api/stream"), {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action }),
    });
    const j = await r.json().catch(() => ({}));
    if (r.ok && j.ok !== false) {
      toast(cur === true ? "⏹ 已停止投屏采集（端口保持，可远程点亮）"
        : cur === false ? "🎬 已开启投屏采集" : "投屏采集已切换");
      if (cur === true) showStreamOff();
      else hideStreamOffRestore();
      pollStatus();
    } else toast("投屏切换失败: " + (j.error || r.status));
  } catch (e) { toast("投屏切换请求失败"); }
  finally { if (btn) btn.disabled = false; }
}
$("btnBack").onclick = () => sendCtl({ type: "click", button: "right" });
$("btnHome").onclick = () => sendCtl({ type: "click", button: "middle" });
$("btnRecents").onclick = () => sendCtl({ type: "key", keycode: 187 });
$("btnVolUp").onclick = () => sendCtl({ type: "key", keycode: 24 });
$("btnVolDown").onclick = () => sendCtl({ type: "key", keycode: 25 });
$("btnMute").onclick = () => { const next = !S.audioEnabled; if (sendCtl({ type: "audio", enabled: next })) setAudioUI(next); else toast("控制通道未连接"); };
$("btnPower").onclick = async () => {
  try {
    // ScreenSync-1：点击前先 GET 新鲜态（含手动电源键变更），不用 5s 轮询缓存算方向。
    let cur = S.lastStatus ? !!S.lastStatus.blackedOut : false;
    try {
      const sr = await fetch(apiUrl("api/screen"), { cache: "no-store" });
      if (sr.ok) {
        const sj = await sr.json().catch(() => ({}));
        if (sj && typeof sj.blackedOut === "boolean") {
          cur = !!sj.blackedOut;
          if (!S.lastStatus) S.lastStatus = {};
          S.lastStatus.blackedOut = cur;
        }
      }
    } catch (e) { /* 读失败回退轮询缓存 */ }
    const r = await fetch(apiUrl("api/screen"), {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action: cur ? "on" : "off" }),
    });
    const j = await r.json().catch(() => ({}));
    if (r.ok && j.ok !== false) {
      if (j && typeof j.blackedOut === "boolean") {
        if (!S.lastStatus) S.lastStatus = {};
        S.lastStatus.blackedOut = !!j.blackedOut;
      }
      toast(cur ? "⏻ 已点亮屏幕" : "⏻ 已息屏挂机"); pollStatus();
    }
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
  toast(S.videoMode === "jpeg" ? "🎞 已切 JPEG 降级 (裸浏览器)" : "🎞 已切回 H264 (硬解路)");
};
$("btnReconnect").onclick = () => { hideStreamOffRestore(); showBoot("正在重连…"); connect(); };
$("btnStream").onclick = toggleStream;
$("btnEnter").onclick = () => { hideStreamOffRestore(); $("bootOverlay").classList.add("hide"); connect(); startStatusPoll(); };
$("btnStreamOn").onclick = async () => {
  const btn = $("btnStreamOn");
  btn.disabled = true;
  try {
    const r = await fetch(apiUrl("api/stream"), {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action: "on" }),
    });
    const j = await r.json().catch(() => ({}));
    if (r.ok && j.ok !== false) {
      toast("🎬 已开启投屏采集，正在进入");
      hideStreamOffRestore();
      hideBoot();
      connect();
      startStatusPoll();
    } else toast("开启投屏失败: " + (j.error || r.status));
  } catch (e) { toast("开启投屏请求失败"); }
  finally { btn.disabled = false; }
};

/* ---------------- 鉴权提交 ---------------- */
async function submitAuth() {
  const t = $("tokenInput").value.trim();
  if (!t) { $("authErr").textContent = "请输入 Token"; return; }
  $("authErr").textContent = "校验中…";
  if (await apiVerify(t)) { rememberToken(t); hideAuth(); await gateEnter("鉴权通过，点击进入控制台"); }
  else {
    $("authErr").textContent = "Token 错误，请重试";
    $("authCard").classList.remove("shake");
    void $("authCard").offsetWidth;
    $("authCard").classList.add("shake");
  }
}
$("btnAuth").onclick = submitAuth;
$("tokenInput").addEventListener("keydown", e => { if (e.key === "Enter") submitAuth(); });

/* ---------------- 启动 ---------------- */
(async function boot() {
  fitCanvas();
  let st = null;
  try { st = await apiStatus(); }
  catch (e) { showBoot("无法连接服务，检查 http://&lt;手机IP&gt;:8888"); return; }
  if (!st.authRequired) {
    if (qsToken) rememberToken(qsToken); // 免密模式也记住URL携带的token(供WS/REST透传)
    await gateEnter("服务运行中，点击进入低延迟控制台");
    return;
  }
  // 设密模式: ?token= > localStorage > 弹窗
  if (qsToken && await apiVerify(qsToken)) { rememberToken(qsToken); await gateEnter("Token 免密通过，点击进入控制台"); return; }
  if (token && await apiVerify(token)) { await gateEnter("已记住 Token，点击进入控制台"); return; }
  showAuth("");
})();

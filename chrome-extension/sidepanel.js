"use strict";
/* =====================================================================
 * BlindCast 侧栏集群中控（sidepanel.js · Hub-1 纯中控版）
 *
 * 定位：多设备纯中控台（集群管理器），绝不投屏、不占流。
 *  - 零画面：无图像标签 / 解码管线 / 音频管线；
 *  - 零流连接：只用轻量 HTTP，不建持久通道：
 *      GET /api/auth/status（公开探活） + GET /api/status（轻量轮询）
 *      POST /api/screen（快捷熄屏/点亮） + GET/POST /api/stream（投屏采集开关）；
 *  - 设备库：chrome.storage.local devices:[{ip,port,name,token,lastSeen}]，
 *    启动恢复并各拉一次 /api/status；
 *  - 扫描候选区（ transient，不入库 ）：
 *      点条目 -> 填入顶部输入框并聚焦；右侧 [+ 添加] -> 一键入库成卡；
 *      顶部 + / 回车 -> 将输入框内容永久存库并成卡；
 *  - 卡片操作：[打开投屏] 新标签开 console.html?host=（已开则聚焦复用）、
 *    [投屏开/关] 直调 POST /api/stream（只停采集不断端口）、
 *    [熄屏/点亮] 直调 POST /api/screen、[刷新]、[移除]。
 * MV3 CSP：外联脚本、零 on*=、零 eval，全 addEventListener + textContent。
 * ===================================================================== */

const $ = id => document.getElementById(id);

function toast(msg, ms) {
  const box = $("toast");
  if (!box) return;
  const d = document.createElement("div");
  d.className = "toast-msg";
  d.textContent = msg;
  box.appendChild(d);
  setTimeout(() => d.remove(), ms || 2600);
}

/* ---------------- 常量 ---------------- */
const DEV_MAX = 20;
const SCAN_CONCURRENCY = 30;
const SCAN_TIMEOUT_MS = 800;
const SCAN_PORT = 8888;
const STATUS_TIMEOUT_MS = 3500;
const POLL_MS = 10000;

/* ---------------- 全局 Token 兜底（旧版本 localStorage 迁移） ---------------- */
let globalToken = "";
try { globalToken = localStorage.getItem("bc_token") || ""; } catch (e) { globalToken = ""; }

/* ---------------- host 归一 ---------------- */
function normHostPort(raw, defPort) {
  const port = defPort || SCAN_PORT;
  let s = (raw || "").trim().replace(/^https?:\/\//, "").replace(/\/.*$/, "").trim();
  if (!s) return "";
  const m = s.match(/^(.*?):(\d+)\s*$/);
  if (m) {
    const ipPart = m[1].trim().replace(/^\[|\]$/g, "");
    const p = parseInt(m[2], 10);
    if (!ipPart || !p || p < 1 || p > 65535) return "";
    return ipPart + ":" + p;
  }
  const ipOnly = s.replace(/^\[|\]$/g, "");
  if (!ipOnly) return "";
  if (/[^0-9A-Za-z.\-]/.test(ipOnly)) return "";
  return ipOnly + ":" + port;
}

function splitHostPort(hostPort) {
  const m = (hostPort || "").match(/^(.*?):(\d+)$/);
  if (m) return { ip: m[1], port: parseInt(m[2], 10) };
  return { ip: hostPort, port: SCAN_PORT };
}

function devKey(dev) {
  return dev.ip + ":" + dev.port;
}

/* ---------------- 存储 ---------------- */
function storeGet(keys, cb) {
  try {
    if (typeof chrome !== "undefined" && chrome.storage && chrome.storage.local) {
      chrome.storage.local.get(keys, function (v) { cb(v || {}); });
      return;
    }
  } catch (e) {}
  // 非插件环境回退：localStorage
  try {
    const out = {};
    for (const k of keys) {
      const raw = localStorage.getItem("bc_" + k);
      if (raw !== null) {
        try { out[k] = JSON.parse(raw); } catch (e2) { out[k] = raw; }
      }
    }
    cb(out);
  } catch (e) { cb({}); }
}

function storeSet(obj) {
  try {
    if (typeof chrome !== "undefined" && chrome.storage && chrome.storage.local) {
      chrome.storage.local.set(obj);
    }
  } catch (e) {}
  try {
    for (const k of Object.keys(obj)) {
      try { localStorage.setItem("bc_" + k, JSON.stringify(obj[k])); } catch (e2) {}
    }
  } catch (e) {}
}

function storeGetP(keys) {
  return new Promise(resolve => storeGet(keys, resolve));
}

function normalizeDevice(raw) {
  if (!raw) return null;
  if (typeof raw === "string") {
    const hp = normHostPort(raw, SCAN_PORT);
    if (!hp) return null;
    const sp = splitHostPort(hp);
    return { ip: sp.ip, port: sp.port, name: hp, token: "", lastSeen: new Date(0).toISOString() };
  }
  const ipRaw = raw.ip || raw.host || "";
  if (!ipRaw) return null;
  let combined = String(ipRaw).trim();
  if (combined.indexOf(":") < 0 && raw.port) combined += ":" + raw.port;
  const hp = normHostPort(combined, raw.port || SCAN_PORT);
  if (!hp) return null;
  const sp = splitHostPort(hp);
  return {
    ip: sp.ip,
    port: sp.port,
    name: raw.name || hp,
    token: raw.token || "",
    lastSeen: raw.lastSeen || new Date(0).toISOString()
  };
}

/* ---------------- 状态：devices（持久） + candidates（瞬态） ---------------- */
let devices = []; // [{ip,port,name,token,lastSeen}]
let candidates = []; // [{key,ip,port,authRequired,foundAt}]
const statusMap = new Map(); // key -> {online,checking,needToken,authRequired,data,error}

function saveDevices() {
  storeSet({ devices });
}

function findDevice(key) {
  for (const d of devices) {
    if (devKey(d) === key) return d;
  }
  return null;
}

function isSaved(key) {
  return !!findDevice(key);
}

function upsertDevice(hostPort, opts) {
  opts = opts || {};
  const hp = normHostPort(hostPort, SCAN_PORT);
  if (!hp) return null;
  const sp = splitHostPort(hp);
  const key = hp;
  const now = new Date().toISOString();
  let idx = -1;
  for (let i = 0; i < devices.length; i++) {
    if (devKey(devices[i]) === key) { idx = i; break; }
  }
  if (idx >= 0) {
    const ex = devices[idx];
    const entry = {
      ip: sp.ip,
      port: sp.port,
      name: opts.name || ex.name || key,
      token: (opts.token !== undefined) ? opts.token : (ex.token || ""),
      lastSeen: (opts.touch === false) ? (ex.lastSeen || now) : now
    };
    devices.splice(idx, 1);
    devices.unshift(entry);
    devices = devices.slice(0, DEV_MAX);
    saveDevices();
    return entry;
  }
  const entry = {
    ip: sp.ip,
    port: sp.port,
    name: opts.name || key,
    token: opts.token || "",
    lastSeen: now
  };
  devices.unshift(entry);
  devices = devices.slice(0, DEV_MAX);
  saveDevices();
  return entry;
}

function removeDevice(key) {
  devices = devices.filter(d => devKey(d) !== key);
  statusMap.delete(key);
  saveDevices();
}

function touchSeen(key) {
  const d = findDevice(key);
  if (!d) return;
  d.lastSeen = new Date().toISOString();
  saveDevices();
}

/* ---------------- HTTP（只走 REST，绝不碰流） ---------------- */
function fetchWithTimeout(url, opts, ms) {
  opts = opts || {};
  ms = ms || STATUS_TIMEOUT_MS;
  let ctl = null;
  try { ctl = new AbortController(); } catch (e) { ctl = null; }
  if (!ctl) {
    const o = { cache: "no-store" };
    for (const k of Object.keys(opts)) o[k] = opts[k];
    return fetch(url, o);
  }
  const timer = setTimeout(() => { try { ctl.abort(); } catch (e) {} }, ms);
  const o2 = { cache: "no-store", signal: ctl.signal };
  for (const k of Object.keys(opts)) o2[k] = opts[k];
  return fetch(url, o2).then(
    r => { clearTimeout(timer); return r; },
    e => { clearTimeout(timer); throw e; }
  );
}

function deviceBase(dev) {
  return "http://" + dev.ip + ":" + dev.port + "/";
}

function deviceToken(dev) {
  return (dev && dev.token) || globalToken || "";
}

/* ---------------- 扫描探针（/api/auth/status 公开接口） ---------------- */
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
    return { authRequired: !!authRequired };
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
          function (j) { return finishOk(j && j.authRequired); },
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

/* ---------------- 扫描候选区渲染 ---------------- */
function renderScanList() {
  const wrap = $("foundWrap");
  const list = $("scanList");
  if (!wrap || !list) return;
  list.textContent = "";
  if (!candidates.length) {
    wrap.classList.add("hide");
    return;
  }
  wrap.classList.remove("hide");
  const fc = $("foundCount");
  if (fc) fc.textContent = "共 " + candidates.length + " 台";
  const savedKeys = {};
  for (const d of devices) savedKeys[devKey(d)] = true;
  for (const c of candidates) {
    const row = document.createElement("div");
    row.className = "scan-item" + (savedKeys[c.key] ? " saved" : "");
    row.setAttribute("role", "button");
    row.setAttribute("tabindex", "0");
    row.setAttribute("title", "点击填入顶部输入框");
    row.dataset.key = c.key;

    const ip = document.createElement("span");
    ip.className = "ip";
    ip.textContent = c.key;
    row.appendChild(ip);

    const tag = document.createElement("span");
    tag.className = "tag";
    if (savedKeys[c.key]) {
      tag.classList.add("done");
      tag.textContent = "已保存 ✓";
    } else if (c.authRequired) {
      tag.classList.add("lock");
      tag.textContent = "需 Token";
    } else {
      tag.classList.add("free");
      tag.textContent = "免密";
    }
    row.appendChild(tag);

    const add = document.createElement("button");
    add.className = "btn-mini-add";
    add.type = "button";
    if (savedKeys[c.key]) {
      add.textContent = "已添加";
      add.disabled = true;
    } else {
      add.textContent = "＋ 添加";
      add.title = "一键入库到已保存设备";
    }
    add.dataset.key = c.key;
    add.addEventListener("click", ev => {
      ev.stopPropagation();
      addCandidate(c.key);
    });
    row.appendChild(add);

    if (!savedKeys[c.key]) {
      row.addEventListener("click", () => fillInputFromCandidate(c.key));
      row.addEventListener("keydown", ev => {
        if (ev.key === "Enter" || ev.key === " ") {
          ev.preventDefault();
          fillInputFromCandidate(c.key);
        }
      });
    }
    list.appendChild(row);
  }
}

function fillInputFromCandidate(key) {
  const input = $("manualIp");
  if (!input) return;
  input.value = key;
  input.classList.add("filled");
  try { input.focus(); } catch (e) {}
  try { input.select(); } catch (e2) {}
  toast("已填入 " + key + "，点 ＋ 入库");
}

function addCandidate(key) {
  const hp = normHostPort(key, SCAN_PORT);
  if (!hp) return;
  if (isSaved(hp)) {
    toast("已在设备库：" + hp);
    renderScanList();
    return;
  }
  const entry = upsertDevice(hp, {});
  renderScanList();
  renderDeviceList();
  updateCount();
  toast("已保存 " + hp);
  if (entry) refreshOne(entry);
}

/* ---------------- 设备卡片渲染 ---------------- */
function statusOf(key) {
  let st = statusMap.get(key);
  if (!st) {
    st = { online: false, checking: false, needToken: false, authRequired: false, data: null, error: "" };
    statusMap.set(key, st);
  }
  return st;
}

function fmtTime(iso) {
  if (!iso) return "--";
  try {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return "--";
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    const hh = String(d.getHours()).padStart(2, "0");
    const mm = String(d.getMinutes()).padStart(2, "0");
    const ss = String(d.getSeconds()).padStart(2, "0");
    if (sameDay) return hh + ":" + mm + ":" + ss;
    return (d.getMonth() + 1) + "-" + d.getDate() + " " + hh + ":" + mm;
  } catch (e) { return "--"; }
}

function renderDeviceList() {
  const list = $("deviceList");
  const empty = $("emptyState");
  if (!list) return;
  list.textContent = "";
  if (!devices.length) {
    if (empty) empty.classList.remove("hide");
    return;
  }
  if (empty) empty.classList.add("hide");
  for (const dev of devices) {
    list.appendChild(createCard(dev));
  }
}

function createCard(dev) {
  const key = devKey(dev);
  const st = statusOf(key);

  const card = document.createElement("article");
  card.className = "dev-card";
  card.dataset.key = key;

  const head = document.createElement("div");
  head.className = "dev-head";
  const dot = document.createElement("span");
  dot.className = "dot off";
  dot.dataset.role = "dot";
  head.appendChild(dot);
  const title = document.createElement("span");
  title.className = "dev-title";
  title.textContent = key;
  title.title = key;
  head.appendChild(title);
  const conn = document.createElement("span");
  conn.className = "dev-conn";
  conn.dataset.role = "conn";
  conn.textContent = "○ 离线";
  head.appendChild(conn);
  card.appendChild(head);

  const caps = document.createElement("div");
  caps.className = "capsules";
  const net = document.createElement("span");
  net.className = "capsule";
  net.dataset.role = "net";
  net.textContent = "○ 离线";
  caps.appendChild(net);
  const bat = document.createElement("span");
  bat.className = "capsule";
  bat.dataset.role = "bat";
  bat.textContent = "电量 --";
  caps.appendChild(bat);
  const scr = document.createElement("span");
  scr.className = "capsule";
  scr.dataset.role = "screen";
  scr.textContent = "屏幕 --";
  caps.appendChild(scr);
  const stm = document.createElement("span");
  stm.className = "capsule";
  stm.dataset.role = "stream";
  stm.textContent = "投屏 --";
  caps.appendChild(stm);
  card.appendChild(caps);

  const sub = document.createElement("div");
  sub.className = "dev-sub";
  sub.dataset.role = "sub";
  sub.textContent = "上次见到 " + fmtTime(dev.lastSeen);
  card.appendChild(sub);

  // Token 行（仅需 Token 时展开）
  const trow = document.createElement("div");
  trow.className = "token-row hide";
  trow.dataset.role = "tokenrow";
  const tinput = document.createElement("input");
  tinput.type = "password";
  tinput.placeholder = "访问 Token（App 设置页）";
  tinput.autocomplete = "off";
  tinput.dataset.role = "tokeninput";
  if (dev.token) tinput.value = dev.token;
  const tbtn = document.createElement("button");
  tbtn.type = "button";
  tbtn.textContent = "保存 Token";
  tbtn.addEventListener("click", () => {
    const v = tinput.value.trim();
    if (!v) { toast("先输入 Token"); return; }
    dev.token = v;
    if (!globalToken) {
      globalToken = v;
      try { localStorage.setItem("bc_token", v); } catch (e) {}
    }
    saveDevices();
    toast("Token 已保存，正在重查状态");
    refreshOne(dev);
  });
  tinput.addEventListener("keydown", ev => {
    if (ev.key === "Enter") tbtn.click();
  });
  trow.appendChild(tinput);
  trow.appendChild(tbtn);
  card.appendChild(trow);

  const open = document.createElement("button");
  open.className = "btn-open";
  open.type = "button";
  open.textContent = "🚀 打开投屏";
  open.title = "在独立大标签页打开 console.html?host=" + key;
  open.dataset.role = "open";
  open.addEventListener("click", () => openConsole(dev));
  card.appendChild(open);

  const rowStream = document.createElement("div");
  rowStream.className = "row-2";
  const stream = document.createElement("button");
  stream.className = "btn-sec";
  stream.type = "button";
  stream.textContent = "🎬 投屏开关";
  stream.title = "直调 POST /api/stream：只停采集不断端口，省电";
  stream.dataset.role = "stream";
  stream.addEventListener("click", () => toggleStream(dev, stream));
  rowStream.appendChild(stream);
  card.appendChild(rowStream);

  const row2 = document.createElement("div");
  row2.className = "row-2";
  const power = document.createElement("button");
  power.className = "btn-sec";
  power.type = "button";
  power.textContent = "⏻ 熄屏";
  power.title = "直调 POST /api/screen，不进投屏页";
  power.dataset.role = "power";
  power.addEventListener("click", () => toggleScreen(dev, power));
  row2.appendChild(power);
  const ref = document.createElement("button");
  ref.className = "btn-sec";
  ref.type = "button";
  ref.textContent = "⟳ 刷新";
  ref.title = "重新拉取 /api/status";
  ref.addEventListener("click", () => refreshOne(dev));
  row2.appendChild(ref);
  const del = document.createElement("button");
  del.className = "btn-danger";
  del.type = "button";
  del.textContent = "🗑 移除";
  del.title = "从设备库删除";
  del.addEventListener("click", () => {
    removeDevice(key);
    renderDeviceList();
    renderScanList();
    updateCount();
    toast("已移除 " + key);
  });
  row2.appendChild(del);
  card.appendChild(row2);

  updateCardStatus(dev, st);
  return card;
}

function cardEl(key) {
  const list = $("deviceList");
  if (!list) return null;
  return list.querySelector('[data-key="' + key + '"]');
}

function updateCardStatus(dev, st) {
  const key = devKey(dev);
  const el = cardEl(key);
  if (!el) return;
  const dot = el.querySelector('[data-role="dot"]');
  const conn = el.querySelector('[data-role="conn"]');
  const net = el.querySelector('[data-role="net"]');
  const bat = el.querySelector('[data-role="bat"]');
  const scr = el.querySelector('[data-role="screen"]');
  const sub = el.querySelector('[data-role="sub"]');
  const trow = el.querySelector('[data-role="tokenrow"]');
  const power = el.querySelector('[data-role="power"]');
  const open = el.querySelector('[data-role="open"]');
  const stm = el.querySelector('[data-role="stream"]');
  const streamBtn = el.querySelector('button[data-role="stream"]');

  function paintStream(state) {
    // state: true=采集中 / false=已停 / null=未知(离线·需Token·检查中)
    if (stm) {
      if (state === true) { stm.textContent = "🎬 投屏开"; stm.className = "capsule ok"; }
      else if (state === false) { stm.textContent = "⏹ 投屏关"; stm.className = "capsule"; }
      else { stm.textContent = "投屏 --"; stm.className = "capsule"; }
    }
    if (streamBtn) {
      if (state === true) { streamBtn.textContent = "⏹ 停止投屏"; streamBtn.disabled = false; }
      else if (state === false) { streamBtn.textContent = "🎬 开启投屏"; streamBtn.disabled = false; }
      else { streamBtn.textContent = "🎬 投屏开关"; streamBtn.disabled = true; }
    }
  }

  if (st.checking) {
    if (dot) dot.className = "dot check";
    if (conn) { conn.textContent = "… 检查中"; conn.className = "dev-conn"; }
    if (net) { net.textContent = "… 检查中"; net.className = "capsule"; }
    paintStream(null);
    return;
  }
  if (st.online && st.data) {
    const j = st.data;
    if (dot) dot.className = "dot on";
    if (conn) { conn.textContent = "● 在线"; conn.className = "dev-conn on"; }
    if (net) { net.textContent = "● 在线"; net.className = "capsule ok"; }
    const lvl = (typeof j.batteryLevel === "number") ? j.batteryLevel : -1;
    if (bat) {
      if (lvl >= 0) {
        bat.textContent = (j.charging ? "⚡ " : "🔋 ") + lvl + "%";
        bat.className = "capsule " + ((lvl <= 20 && !j.charging) ? "bad" : "ok");
      } else {
        bat.textContent = "电量 --";
        bat.className = "capsule";
      }
    }
    if (scr) {
      if (j.blackedOut) { scr.textContent = "⏻ 熄屏中"; scr.className = "capsule warn"; }
      else { scr.textContent = "⏻ 亮屏中"; scr.className = "capsule ok"; }
    }
    if (power) power.textContent = j.blackedOut ? "⏻ 点亮" : "⏻ 熄屏";
    if (trow) trow.classList.add("hide");
    paintStream(typeof j.streaming === "boolean" ? j.streaming : null);
    if (sub) {
      const extra = [];
      if (typeof j.streamClients === "number" || typeof j.controlClients === "number") {
        extra.push("流" + (j.streamClients || 0) + "/控" + (j.controlClients || 0));
      }
      sub.textContent = "上次见到 " + fmtTime(dev.lastSeen) + (extra.length ? " · " + extra.join(" ") : "");
    }
  } else if (st.needToken) {
    if (dot) dot.className = "dot auth";
    if (conn) { conn.textContent = "🔒 需 Token"; conn.className = "dev-conn"; }
    if (net) { net.textContent = "🔒 需 Token"; net.className = "capsule warn"; }
    if (bat) { bat.textContent = "电量 --"; bat.className = "capsule"; }
    if (scr) { scr.textContent = "屏幕 --"; scr.className = "capsule"; }
    if (power) power.textContent = "⏻ 熄屏";
    if (trow) trow.classList.remove("hide");
    paintStream(null);
    if (sub) sub.textContent = (st.error === "bad-token" ? "Token 错误，请更新" : "该设备需访问 Token") + " · 上次见到 " + fmtTime(dev.lastSeen);
  } else {
    if (dot) dot.className = "dot off";
    if (conn) { conn.textContent = "○ 离线"; conn.className = "dev-conn"; }
    if (net) { net.textContent = "○ 离线"; net.className = "capsule bad"; }
    if (bat) {
      if (st.data && typeof st.data.batteryLevel === "number" && st.data.batteryLevel >= 0) {
        bat.textContent = "🔋 " + st.data.batteryLevel + "%";
        bat.className = "capsule";
      } else {
        bat.textContent = "电量 --";
        bat.className = "capsule";
      }
    }
    if (scr) { scr.textContent = "屏幕 --"; scr.className = "capsule"; }
    if (power) power.textContent = "⏻ 熄屏";
    if (trow) trow.classList.add("hide");
    paintStream(null);
    if (sub) {
      const reason = !st.error ? "未检测到服务" : (st.error === "timeout" ? "连接超时" : st.error);
      sub.textContent = "○ 离线 · " + reason + " · 上次见到 " + fmtTime(dev.lastSeen);
    }
  }
  if (open) open.disabled = false;
}

function updateCount() {
  const el = $("devCount");
  if (el) el.textContent = devices.length + " 台";
  const foot = $("footStat");
  if (foot) {
    let on = 0;
    for (const d of devices) {
      const st = statusMap.get(devKey(d));
      if (st && st.online) on++;
    }
    foot.textContent = "BlindCast Hub · 纯中控（不占流）· " + on + "/" + devices.length + " 在线";
  }
}

/* ---------------- 轻量状态轮询（GET /api/status，不拉流） ---------------- */
async function refreshOne(dev) {
  const key = devKey(dev);
  const st = statusOf(key);
  if (st.checking) return st;
  st.checking = true;
  updateCardStatus(dev, st);
  const base = deviceBase(dev);
  const token = deviceToken(dev);
  try {
    const authRes = await fetchWithTimeout(base + "api/auth/status", {}, STATUS_TIMEOUT_MS);
    if (!authRes.ok) throw new Error("http " + authRes.status);
    const authJson = await authRes.json().catch(() => ({}));
    const needAuth = !!(authJson && authJson.authRequired);
    if (!needAuth) {
      const r = await fetchWithTimeout(base + "api/status", {}, STATUS_TIMEOUT_MS);
      if (!r.ok) throw new Error("http " + r.status);
      const j = await r.json();
      st.online = true;
      st.needToken = false;
      st.authRequired = false;
      st.data = j;
      st.error = "";
      st.checking = false;
      touchSeen(key);
    } else {
      st.authRequired = true;
      if (!token) {
        st.online = false;
        st.needToken = true;
        st.checking = false;
        st.error = "need-token";
        st.data = null;
      } else {
        const r2 = await fetchWithTimeout(base + "api/status?token=" + encodeURIComponent(token), {}, STATUS_TIMEOUT_MS);
        if (r2.status === 401) {
          st.online = false;
          st.needToken = true;
          st.checking = false;
          st.error = "bad-token";
          st.data = null;
        } else if (!r2.ok) {
          throw new Error("http " + r2.status);
        } else {
          const j2 = await r2.json();
          st.online = true;
          st.needToken = false;
          st.data = j2;
          st.error = "";
          st.checking = false;
          touchSeen(key);
        }
      }
    }
  } catch (e) {
    st.online = false;
    st.checking = false;
    st.error = (e && e.name === "AbortError") ? "timeout" : String((e && e.message) || e);
    if (st.authRequired === undefined) st.needToken = false;
  }
  statusMap.set(key, st);
  updateCardStatus(dev, st);
  updateCount();
  return st;
}

let refreshingAll = false;
async function refreshAll() {
  if (!devices.length || refreshingAll) return;
  refreshingAll = true;
  const btn = $("btnRefresh");
  if (btn) btn.disabled = true;
  try {
    await Promise.all(devices.map(d => refreshOne(d).catch(() => null)));
  } finally {
    refreshingAll = false;
    if (btn) btn.disabled = false;
    updateCount();
  }
}

/* ---------------- 打开投屏（独立大标签页，已开则聚焦复用） ---------------- */
function consoleUrl(dev) {
  const key = devKey(dev);
  const token = deviceToken(dev);
  const qs = "console.html?host=" + encodeURIComponent(key) + (token ? "&token=" + encodeURIComponent(token) : "");
  try {
    if (typeof chrome !== "undefined" && chrome.runtime && chrome.runtime.getURL) {
      return chrome.runtime.getURL(qs);
    }
  } catch (e) {}
  return qs;
}

function openConsole(dev) {
  const key = devKey(dev);
  const url = consoleUrl(dev);
  try {
    if (typeof chrome !== "undefined" && chrome.tabs && chrome.tabs.query) {
      chrome.tabs.query({}, function (tabs) {
        let hit = null;
        try {
          for (const t of (tabs || [])) {
            if (!t || !t.url || t.url.indexOf("console.html") < 0) continue;
            let h = "";
            try {
              const u = new URL(t.url);
              h = u.searchParams.get("host") || "";
            } catch (e) { h = ""; }
            if (h && normHostPort(h, SCAN_PORT) === key) { hit = t; break; }
            if (!h && t.url.indexOf(key) >= 0) { hit = t; break; }
            if (!h && t.url.indexOf(dev.ip) >= 0) { hit = t; break; }
          }
        } catch (e) { hit = null; }
        if (hit) {
          try {
            chrome.tabs.update(hit.id, { active: true }, function () {
              try {
                if (hit.windowId !== undefined && chrome.windows && chrome.windows.update) {
                  chrome.windows.update(hit.windowId, { focused: true });
                }
              } catch (e2) {}
            });
            toast("已聚焦 existing 投屏页：" + key);
          } catch (e) {
            try { chrome.tabs.create({ url }); } catch (e2) { window.open(url, "_blank"); }
          }
          return;
        }
        try {
          chrome.tabs.create({ url });
        } catch (e) {
          window.open(url, "_blank");
        }
      });
      return;
    }
  } catch (e) {}
  window.open(url, "_blank");
}

/* ---------------- 快捷熄屏 / 点亮（POST /api/screen，不进投屏页） ---------------- */
async function toggleScreen(dev, btn) {
  const key = devKey(dev);
  const st = statusOf(key);
  const cur = (st.data && typeof st.data.blackedOut === "boolean") ? !!st.data.blackedOut : null;
  const action = cur === null ? "toggle" : (cur ? "on" : "off");
  const token = deviceToken(dev);
  if (btn) btn.disabled = true;
  try {
    const url = deviceBase(dev) + "api/screen" + (token ? "?token=" + encodeURIComponent(token) : "");
    const r = await fetchWithTimeout(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action })
    }, STATUS_TIMEOUT_MS);
    if (r.status === 401) {
      st.needToken = true;
      st.online = false;
      st.error = "need-token";
      updateCardStatus(dev, st);
      toast("该设备需 Token，先在卡片填写 Token");
      return;
    }
    const j = await r.json().catch(() => ({}));
    if (r.ok && j.ok !== false) {
      toast(cur === true ? "已点亮屏幕" : (cur === false ? "已熄屏挂机" : "屏幕已切换"));
      await refreshOne(dev);
    } else {
      toast("屏幕切换失败：" + (j.error || j.detail || r.status));
    }
  } catch (e) {
    toast("屏幕切换请求失败");
  } finally {
    if (btn) btn.disabled = false;
  }
}

/* ---------------- 投屏采集开关（POST /api/stream，只停采集不断端口） ---------------- */
async function toggleStream(dev, btn) {
  const key = devKey(dev);
  const st = statusOf(key);
  const cur = (st.data && typeof st.data.streaming === "boolean") ? !!st.data.streaming : null;
  const action = cur === null ? "toggle" : (cur ? "off" : "on");
  const token = deviceToken(dev);
  if (btn) btn.disabled = true;
  try {
    const url = deviceBase(dev) + "api/stream" + (token ? "?token=" + encodeURIComponent(token) : "");
    const r = await fetchWithTimeout(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action })
    }, STATUS_TIMEOUT_MS);
    if (r.status === 401) {
      st.needToken = true;
      st.online = false;
      st.error = "need-token";
      updateCardStatus(dev, st);
      toast("该设备需 Token，先在卡片填写 Token");
      return;
    }
    const j = await r.json().catch(() => ({}));
    if (r.ok && j.ok !== false) {
      toast(cur === true ? "⏹ 已停止投屏采集（端口保持）" : (cur === false ? "🎬 已开启投屏采集" : "投屏采集已切换"));
      await refreshOne(dev);
    } else {
      toast("投屏切换失败：" + (j.error || r.status));
    }
  } catch (e) {
    toast("投屏切换请求失败");
  } finally {
    if (btn) btn.disabled = false;
  }
}

/* ---------------- 顶部：手动添加 / 扫描 ---------------- */
function addManual() {
  const input = $("manualIp");
  if (!input) return;
  const hp = normHostPort(input.value, SCAN_PORT);
  if (!hp) {
    try { input.focus(); } catch (e) {}
    toast("先输入局域网 IP，如 192.168.31.216");
    return;
  }
  const existed = isSaved(hp);
  const entry = upsertDevice(hp, {});
  input.value = "";
  input.classList.remove("filled");
  renderDeviceList();
  renderScanList();
  updateCount();
  toast(existed ? "已在设备库：" + hp : "已保存 " + hp);
  if (entry) refreshOne(entry);
}

let scanning = false;
function setScanProgress(done, total) {
  const pct = total ? (done / total) * 100 : 0;
  const bar = $("scanBar");
  if (bar) bar.style.width = pct + "%";
  const meta = $("scanMeta");
  if (meta) meta.textContent = total ? (done + "/" + total) : "就绪";
}

function scanLan() {
  if (scanning) return;
  const prefix = normPrefix($("scanPrefix").value);
  if (!prefix) {
    $("scanMsg").textContent = "前缀像这样写：192.168.31.（三段数字+点）";
    try { $("scanPrefix").focus(); } catch (e) {}
    return;
  }
  scanning = true;
  candidates = [];
  renderScanList();
  $("btnScan").disabled = true;
  const sm = $("scanMsg");
  if (sm) sm.textContent = "正在扫描 " + prefix + "1–254…点条目填入，＋ 一键入库";
  const total = 254;
  let done = 0;
  setScanProgress(0, total);
  $("btnScan").textContent = "扫描中 0/254";

  let next = 1;
  let renderTimer = 0;
  function scheduleRender() {
    if (renderTimer) return;
    renderTimer = setTimeout(() => {
      renderTimer = 0;
      renderScanList();
    }, 400);
  }

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
          const key = ip + ":" + SCAN_PORT;
          let dup = false;
          for (const c of candidates) { if (c.key === key) { dup = true; break; } }
          if (!dup) {
            candidates.push({ key, ip, port: SCAN_PORT, authRequired: !!r.authRequired, foundAt: Date.now() });
            candidates.sort((a, b) => (a.key < b.key ? -1 : 1));
            scheduleRender();
          }
          if (sm) sm.textContent = "已发现 " + candidates.length + " 台 · 点条目填入输入框，或右侧 ＋ 直接入库";
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
    if (renderTimer) { clearTimeout(renderTimer); renderTimer = 0; }
    renderScanList();
    $("btnScan").disabled = false;
    $("btnScan").textContent = "🔍 扫描局域网";
    setScanProgress(total, total);
    if (!candidates.length) {
      if (sm) sm.textContent = "没扫到：确认手机电脑同一 Wi-Fi，前缀多半 192.168.31. 或 192.168.1.，手机服务开着。";
    } else {
      if (sm) sm.textContent = "扫到 " + candidates.length + " 台 · 点条目填入＋入库，已保存的不重复入库。";
      toast("扫到 " + candidates.length + " 台，点条目填入或＋入库");
    }
  });
}

/* ---------------- 启动 ---------------- */
async function bootHub() {
  const manualIp = $("manualIp");
  const btnAdd = $("btnAdd");
  const btnScan = $("btnScan");
  const scanPrefix = $("scanPrefix");
  const btnRefresh = $("btnRefresh");
  if (btnAdd) btnAdd.addEventListener("click", addManual);
  if (manualIp) {
    manualIp.addEventListener("keydown", e => { if (e.key === "Enter") addManual(); });
    manualIp.addEventListener("input", () => manualIp.classList.remove("filled"));
  }
  if (btnScan) btnScan.addEventListener("click", scanLan);
  if (scanPrefix) scanPrefix.addEventListener("keydown", e => { if (e.key === "Enter") scanLan(); });
  if (btnRefresh) btnRefresh.addEventListener("click", refreshAll);

  const v = await storeGetP(["devices", "currentHost", "hosts", "host"]);
  let stored = [];
  if (Array.isArray(v.devices)) {
    for (const raw of v.devices) {
      const n = normalizeDevice(raw);
      if (n) {
        let dup = false;
        for (const s of stored) { if (s.ip === n.ip && s.port === n.port) { dup = true; break; } }
        if (!dup) stored.push(n);
      }
    }
  }
  // 兼容迁移：popup 旧历史 hosts/host + currentHost
  if (!stored.length && Array.isArray(v.hosts) && v.hosts.length) {
    for (const h of v.hosts) {
      const n = normalizeDevice(h);
      if (n) stored.push(n);
    }
  }
  const legacyCurrent = normHostPort(v.currentHost || v.host || "", SCAN_PORT);
  if (legacyCurrent && !stored.some(d => devKey(d) === legacyCurrent)) {
    const n = normalizeDevice(legacyCurrent);
    if (n) stored.unshift(n);
  }
  devices = stored.slice(0, DEV_MAX);
  if (devices.length) saveDevices();

  // 猜网段前缀
  try {
    const first = devices.length ? (devices[0].ip || "") : "";
    const m = first.match(/^(\d+\.\d+\.\d+)\./);
    if (m && scanPrefix) scanPrefix.value = m[1] + ".";
  } catch (e) {}

  renderDeviceList();
  renderScanList();
  updateCount();
  const foot = $("footStat");
  if (!devices.length) {
    if (foot) foot.textContent = "BlindCast Hub · 纯中控（不占流）· 先扫描或手动添加";
  } else {
    refreshAll();
  }
  setInterval(() => {
    if (!scanning && devices.length && !document.hidden) refreshAll();
  }, POLL_MS);
}

bootHub();

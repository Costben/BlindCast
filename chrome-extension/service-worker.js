"use strict";
/* AndroMeld 扩展后台：接管「设备网页」导航。
 *
 * 面板要用 WebCodecs 硬解设备的 H.264，前提是安全上下文。把
 * `http://<设备>:8888/` 粘到地址栏打开的是普通局域网网页，不是安全上下文，
 * 适配器只能退回软件 MSE 解码，桌面就卡。这里把这类导航改写成扩展页
 * （chrome-extension:// 即安全上下文），并保留 ?token=。
 *
 * 判定刻意收窄：只探内网/回环地址，且只有 /api/auth/status 的应答像
 * BlindCast 设备时才接管；已知设备（popup 历史 + 接管过的）直接跳过探测。
 */

var PROBE_PATH = "/api/auth/status";
var PROBE_TIMEOUT_MS = 900;
var NEGATIVE_TTL_MS = 5 * 60 * 1000;
var ROUTED_MAX = 20;
var RELOOP_MS = 4000;
/* `http://<设备>:8888/?blindcast=raw` 可临时按原样打开网页版。 */
var RAW_FLAG = "blindcast";
var RAW_VALUE = "raw";

var negative = Object.create(null);
var redirected = Object.create(null);

function isPrivateHostname(hostname) {
  if (!hostname) return false;
  var h = hostname.toLowerCase();
  if (h === "localhost" || h === "127.0.0.1" || h === "[::1]" || h === "::1") return true;
  if (h.slice(-10) === ".localhost" || h.slice(-6) === ".local") return true;
  var m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h);
  if (!m) return false;
  var a = Number(m[1]), b = Number(m[2]);
  if (a === 10 || a === 127) return true;
  if (a === 192 && b === 168) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  if (a === 169 && b === 254) return true;
  return false;
}

function consoleUrl(host, token) {
  var url = chrome.runtime.getURL("console.html") + "?host=" + encodeURIComponent(host);
  if (token) url += "&token=" + encodeURIComponent(token);
  return url;
}

/* 接管前确认扩展入口文件确实在。曾出现过跳转过去停在 ERR_FILE_NOT_FOUND 的死页
 * （扩展目录里那一刻没有 console.html），用户原网页反而被顶掉了。这里只在**明确
 * 404** 时放弃接管：探测本身抛错 / 拿到别的状态都按「在」处理，免得探针失效反而
 * 把接管整条链路关掉。 */
var consoleReady = null;
function consolePageAvailable() {
  if (consoleReady !== null) return Promise.resolve(consoleReady);
  return fetch(chrome.runtime.getURL("console.html"), { method: "HEAD", cache: "no-store" })
    .then(function (r) { consoleReady = !(r && r.status === 404); return consoleReady; },
      function () { return true; });
}

/* 设备应答：新版本带 product 标记；旧版本只有 authRequired 一个键。 */
function isDeviceStatus(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return false;
  if (body.product === "blindcast") return true;
  var keys = Object.keys(body);
  return keys.length === 1 && keys[0] === "authRequired" && typeof body.authRequired === "boolean";
}

function probeDevice(host) {
  var failedAt = negative[host];
  if (failedAt !== undefined && Date.now() - failedAt < NEGATIVE_TTL_MS) return Promise.resolve(false);
  var ctl = new AbortController();
  var timer = setTimeout(function () { try { ctl.abort(); } catch (e) {} }, PROBE_TIMEOUT_MS);
  return fetch("http://" + host + PROBE_PATH, { cache: "no-store", signal: ctl.signal })
    .then(function (res) {
      if (!res.ok) return false;
      return res.json().then(isDeviceStatus, function () { return false; });
    })
    .catch(function () { return false; })
    .then(function (ok) {
      if (!ok) negative[host] = Date.now();
      return ok;
    })
    .then(function (ok) { clearTimeout(timer); return ok; },
      function (e) { clearTimeout(timer); throw e; });
}

function state() {
  return chrome.storage.local.get({ autoRoute: true, routed: [], host: "", hosts: [] });
}

function remember(host) {
  return state().then(function (st) {
    var routed = Array.isArray(st.routed) ? st.routed.slice() : [];
    if (routed.indexOf(host) >= 0) return;
    routed.unshift(host);
    chrome.storage.local.set({ routed: routed.slice(0, ROUTED_MAX) });
  });
}

function recentlyRedirected(tabId) {
  var at = redirected[tabId];
  return at !== undefined && Date.now() - at < RELOOP_MS;
}

function isKnownHost(st, host) {
  if (st.host === host) return true;
  if (Array.isArray(st.hosts) && st.hosts.indexOf(host) >= 0) return true;
  return Array.isArray(st.routed) && st.routed.indexOf(host) >= 0;
}

function handle(details) {
  if (!details || details.frameId !== 0 || details.tabId < 0) return Promise.resolve();
  var url;
  try { url = new URL(details.url); } catch (e) { return Promise.resolve(); }
  if (url.protocol !== "http:" && url.protocol !== "https:") return Promise.resolve();
  if (url.searchParams.get(RAW_FLAG) === RAW_VALUE) return Promise.resolve();
  if (!isPrivateHostname(url.hostname)) return Promise.resolve();
  if (recentlyRedirected(details.tabId)) return Promise.resolve();

  var host = url.host;
  var tabId = details.tabId;
  return state().then(function (st) {
    if (st.autoRoute === false) return null;
    if (isKnownHost(st, host)) return true;
    return probeDevice(host);
  }).then(function (device) {
    if (!device) return;
    redirected[tabId] = Date.now();
    remember(host);
    return consolePageAvailable().then(function (ready) {
      if (!ready) return;
      return chrome.tabs.update(tabId, {
        url: consoleUrl(host, url.searchParams.get("token") || "")
      });
    });
  }).catch(function () {});
}

chrome.webNavigation.onBeforeNavigate.addListener(function (details) {
  handle(details);
});

chrome.tabs.onRemoved.addListener(function (tabId) {
  delete redirected[tabId];
});

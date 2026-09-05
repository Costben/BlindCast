"use strict";
/* ExtPolish-1: 历史 IP（最近 5，chrome.storage.local，try/catch 包裹保普通网页可打开）
 * + 局域网发现（并发 ~30，单点 800ms 超时 AbortController，进度条，GET /api/auth/status）。
 * CSP: 无内联脚本/无 on* 属性/无 eval，全 addEventListener。 */
var ipInput = document.getElementById("ip");
var histList = document.getElementById("histList");
var histEmpty = document.getElementById("histEmpty");
var prefixInput = document.getElementById("prefix");
var scanBtn = document.getElementById("scanBtn");
var scanBar = document.getElementById("scanBar");
var scanMsg = document.getElementById("scanMsg");
var foundList = document.getElementById("foundList");

var SCAN_CONCURRENCY = 30;
var SCAN_TIMEOUT_MS = 800;
var SCAN_PORT = 8888;
var HIST_MAX = 5;

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

function normHost(raw) {
  var host = (raw || "").trim().replace(/^https?:\/\//, "").replace(/\/.*$/, "");
  if (!host) return "";
  if (host.indexOf(":") < 0) host += ":" + SCAN_PORT;
  return host;
}

function consoleUrl(host) {
  try {
    if (typeof chrome !== "undefined" && chrome.runtime && chrome.runtime.getURL) {
      return chrome.runtime.getURL("console.html?host=" + encodeURIComponent(host));
    }
  } catch (e) {}
  return "console.html?host=" + encodeURIComponent(host);
}

function openHost(host) {
  host = normHost(host);
  if (!host) { ipInput.focus(); return; }
  saveHistory(host);
  var url = consoleUrl(host);
  try {
    if (typeof chrome !== "undefined" && chrome.tabs && chrome.tabs.create) {
      chrome.tabs.create({ url: url });
      return;
    }
  } catch (e) {}
  window.open(url, "_blank");
}

/* ---------------- 历史 ---------------- */
function loadHistory() {
  storeGet(["host", "hosts"], function (v) {
    if (v && v.host && !ipInput.value) ipInput.value = v.host;
    renderHistory(Array.isArray(v && v.hosts) ? v.hosts : []);
  });
}

function saveHistory(host) {
  storeGet(["hosts"], function (v) {
    var arr = Array.isArray(v && v.hosts) ? v.hosts.slice() : [];
    arr = [host].concat(arr.filter(function (h) { return h !== host; })).slice(0, HIST_MAX);
    storeSet({ host: host, hosts: arr });
    renderHistory(arr);
  });
}

function renderHistory(arr) {
  histList.textContent = "";
  histEmpty.style.display = arr.length ? "none" : "";
  arr.forEach(function (host) {
    var li = document.createElement("li");
    var b = document.createElement("button");
    b.type = "button";
    b.textContent = host;
    b.addEventListener("click", function () { openHost(host); });
    li.appendChild(b);
    histList.appendChild(li);
  });
}

/* ---------------- 局域网发现 ---------------- */
function normPrefix(raw) {
  var p = (raw || "").trim();
  if (/^\d+\.\d+\.\d+\.?$/.test(p)) {
    if (p.charAt(p.length - 1) !== ".") p += ".";
    return p;
  }
  return null;
}

function probeIp(url) {
  var ctl = null;
  try { ctl = new AbortController(); } catch (e) { ctl = null; }
  var timer = 0;
  var done = false;
  function finishOk(authRequired) {
    if (done) return null;
    done = true;
    if (timer) clearTimeout(timer);
    return { authRequired: authRequired };
  }
  var fetchP = null;
  try {
    var opt = { cache: "no-store" };
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
    var timeoutP = new Promise(function (res) {
      setTimeout(function () { if (!done) { done = true; res(null); } }, SCAN_TIMEOUT_MS);
    });
    return Promise.race([fetchP, timeoutP]);
  }
  return fetchP;
}

var scanning = false;
function setProgress(done, total) {
  var pct = total ? (done / total) * 100 : 0;
  scanBar.style.width = pct + "%";
}

function scan() {
  if (scanning) return;
  var prefix = normPrefix(prefixInput.value);
  if (!prefix) {
    scanMsg.textContent = "前缀像这样写：192.168.31.（三段数字+点）";
    prefixInput.focus();
    return;
  }
  scanning = true;
  scanBtn.disabled = true;
  foundList.textContent = "";
  scanMsg.textContent = "正在扫描 " + prefix + "1–254…";
  var total = 254;
  var done = 0;
  var found = [];
  setProgress(0, total);
  scanBtn.textContent = "扫描中 0/254";

  var next = 1;
  function worker() {
    function step() {
      if (next > total) return Promise.resolve();
      var i = next++;
      var ip = prefix + i;
      return probeIp("http://" + ip + ":" + SCAN_PORT + "/api/auth/status").then(function (r) {
        done++;
        setProgress(done, total);
        scanBtn.textContent = "扫描中 " + done + "/254";
        if (r) {
          var host = ip + ":" + SCAN_PORT;
          found.push({ host: host, authRequired: r.authRequired });
          addFoundItem(host, r.authRequired);
        }
        return step();
      });
    }
    return step();
  }

  var workers = [];
  for (var k = 0; k < SCAN_CONCURRENCY; k++) workers.push(worker());
  Promise.all(workers).then(function () {
    scanning = false;
    scanBtn.disabled = false;
    scanBtn.textContent = "扫描 " + prefix + "1–254";
    setProgress(total, total);
    if (!found.length) {
      scanMsg.textContent = "没扫到手机：先确认手机和电脑在同一 Wi-Fi，再核对前缀（多半是 192.168.31. 或 192.168.1.），手机 App 里服务开着。";
    } else {
      scanMsg.textContent = "扫到 " + found.length + " 台，点一行直连。";
    }
  });
}

function addFoundItem(host, authRequired) {
  var li = document.createElement("li");
  var b = document.createElement("button");
  b.type = "button";
  b.textContent = host;
  var tag = document.createElement("small");
  tag.textContent = authRequired ? "需 Token" : "免密直连";
  b.appendChild(tag);
  b.addEventListener("click", function () { openHost(host); });
  li.appendChild(b);
  foundList.appendChild(li);
  scanMsg.textContent = "";
}

/* ---------------- 接线 ---------------- */
function openConsole() { openHost(ipInput.value); }
document.getElementById("go").addEventListener("click", openConsole);
ipInput.addEventListener("keydown", function (e) { if (e.key === "Enter") openConsole(); });
scanBtn.addEventListener("click", scan);
prefixInput.addEventListener("keydown", function (e) { if (e.key === "Enter") scan(); });

loadHistory();

"use strict";
const ipInput = document.getElementById("ip");
try {
  chrome.storage.local.get("host", v => { if (v && v.host) ipInput.value = v.host; });
} catch (e) {}
function openConsole() {
  let host = (ipInput.value || "").trim().replace(/^https?:\/\//, "").replace(/\/.*$/, "");
  if (!host) { ipInput.focus(); return; }
  if (!host.includes(":")) host += ":8888";
  try { chrome.storage.local.set({ host }); } catch (e) {}
  chrome.tabs.create({ url: chrome.runtime.getURL("console.html?host=" + encodeURIComponent(host)) });
}
document.getElementById("go").addEventListener("click", openConsole);
ipInput.addEventListener("keydown", e => { if (e.key === "Enter") openConsole(); });

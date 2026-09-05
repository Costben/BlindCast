"use strict";
/* BlindCast Side Panel 中控台 · background service worker (MV3)
 * 点击工具栏图标直接在 Chrome 右侧滑出原生侧边栏（ChatGPT 同款）。
 * CSP: 无内联、无 eval，全 chrome.* 调用。 */

function enableOpenPanelOnClick() {
  try {
    if (
      typeof chrome !== "undefined" &&
      chrome.sidePanel &&
      typeof chrome.sidePanel.setPanelBehavior === "function"
    ) {
      chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(function () {});
    }
  } catch (e) {}
}

try {
  if (typeof chrome !== "undefined" && chrome.runtime) {
    if (chrome.runtime.onInstalled) {
      chrome.runtime.onInstalled.addListener(enableOpenPanelOnClick);
    }
    if (chrome.runtime.onStartup) {
      chrome.runtime.onStartup.addListener(enableOpenPanelOnClick);
    }
  }
} catch (e) {}

enableOpenPanelOnClick();

"use strict";
/* Runs chrome-extension/service-worker.js inside a Node vm with mocked
 * chrome.* / fetch, and asserts the device-page takeover decisions.
 * No browser is launched (branded Chrome ignores --load-extension and
 * Playwright's Chromium trips the macOS keychain prompt). */

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const SW_PATH = path.join(__dirname, "..", "chrome-extension", "service-worker.js");
const SRC = fs.readFileSync(SW_PATH, "utf8");

const EXT_ID = "TESTEXTENSIONID";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function makeEnv(options) {
  const opts = options || {};
  const storage = Object.assign(
    { autoRoute: true, routed: [], host: "", hosts: [] },
    opts.storage || {}
  );
  const navListeners = [];
  const removedListeners = [];
  const updates = [];
  const fetchCalls = [];
  const responses = opts.responses || {};

  const chrome = {
    runtime: { getURL: (p) => "chrome-extension://" + EXT_ID + "/" + p },
    webNavigation: { onBeforeNavigate: { addListener: (fn) => navListeners.push(fn) } },
    tabs: {
      onRemoved: { addListener: (fn) => removedListeners.push(fn) },
      update: (tabId, props) => {
        updates.push(Object.assign({ tabId: tabId }, props));
        return Promise.resolve();
      },
    },
    storage: {
      local: {
        get: (defaults) => Promise.resolve(Object.assign({}, defaults, storage)),
        set: (obj) => {
          Object.assign(storage, obj);
          return Promise.resolve();
        },
      },
    },
  };

  const CONSOLE_HEAD = "chrome-extension://" + EXT_ID + "/console.html";
  const fetchMock = (url, init) => {
    // 扩展入口存在性探测：不算设备探针，不进 fetchCalls（否则既有断言的口径会变）。
    if (url === CONSOLE_HEAD) {
      return Promise.resolve(
        opts.consoleMissing
          ? { ok: false, status: 404, json: () => Promise.resolve({}) }
          : { ok: true, status: 200, json: () => Promise.resolve({}) }
      );
    }
    fetchCalls.push(url);
    const entry = responses[url];
    if (!entry) return Promise.reject(new Error("ENOTFOUND " + url));
    if (entry === "hang") {
      return new Promise((_res, rej) => {
        init.signal.addEventListener("abort", () => rej(new Error("aborted")));
      });
    }
    return Promise.resolve({
      ok: entry.ok !== false,
      json: () => Promise.resolve(entry.body),
    });
  };

  const sandbox = {
    chrome: chrome,
    fetch: fetchMock,
    console: console,
    URL: URL,
    Promise: Promise,
    Object: Object,
    Array: Array,
    JSON: JSON,
    Number: Number,
    String: String,
    Date: Date,
    RegExp: RegExp,
    Error: Error,
    TypeError: TypeError,
    AbortController: AbortController,
    setTimeout: setTimeout,
    clearTimeout: clearTimeout,
    Math: Math,
  };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(SRC, sandbox, { filename: "service-worker.js" });

  return {
    storage: storage,
    updates: updates,
    fetchCalls: fetchCalls,
    removedListeners: removedListeners,
    navigate: (details) => {
      navListeners.forEach((fn) => fn(details));
      return sleep(40);
    },
  };
}

const results = [];
function check(name, ok, detail) {
  results.push({ name: name, ok: !!ok });
  console.log((ok ? "PASS " : "FAIL ") + name + (detail ? "  [" + detail + "]" : ""));
}

const DEVICE_STATUS = "http://192.168.31.216:8888/api/auth/status";
const DEVICE_URL = "http://192.168.31.216:8888/";
const CONSOLE = "chrome-extension://" + EXT_ID + "/console.html";

(async () => {
  // T1: pasted device link -> extension console, host + token preserved
  {
    const env = makeEnv({ responses: { [DEVICE_STATUS]: { body: { authRequired: true } } } });
    await env.navigate({ frameId: 0, tabId: 1, url: DEVICE_URL + "?token=ABC123" });
    const u = env.updates.length === 1 ? env.updates[0].url : "";
    check(
      "T1 device page taken over with token",
      env.updates.length === 1 &&
        u === CONSOLE + "?host=192.168.31.216%3A8888&token=ABC123",
      u || "no update"
    );

    // T1b: remembered host skips the probe on the next navigation
    env.updates.length = 0;
    await env.navigate({ frameId: 0, tabId: 2, url: DEVICE_URL });
    check(
      "T1b remembered host reroutes without probing",
      env.updates.length === 1 && env.fetchCalls.length === 1,
      "updates=" + env.updates.length + " probes=" + env.fetchCalls.length
    );
  }

  // T2: ?blindcast=raw escape hatch stays on the plain page
  {
    const env = makeEnv({ responses: { [DEVICE_STATUS]: { body: { authRequired: true } } } });
    await env.navigate({ frameId: 0, tabId: 1, url: DEVICE_URL + "?blindcast=raw" });
    check("T2 blindcast=raw not taken over", env.updates.length === 0 && env.fetchCalls.length === 0);
  }

  // T3: popup switch off -> no takeover, no probe
  {
    const env = makeEnv({
      storage: { autoRoute: false },
      responses: { [DEVICE_STATUS]: { body: { authRequired: true } } },
    });
    await env.navigate({ frameId: 0, tabId: 1, url: DEVICE_URL });
    check("T3 autoRoute off not taken over", env.updates.length === 0 && env.fetchCalls.length === 0);
  }

  // T4: private host that is not a device -> left alone
  {
    const env = makeEnv({
      responses: { "http://10.0.0.7/api/auth/status": { body: { status: "ok", uptime: 12 } } },
    });
    await env.navigate({ frameId: 0, tabId: 1, url: "http://10.0.0.7/" });
    check("T4 non-device private host not hijacked", env.updates.length === 0 && env.fetchCalls.length === 1);
  }

  // T4b: 404 probe answer -> left alone
  {
    const env = makeEnv({
      responses: { "http://10.0.0.8/api/auth/status": { ok: false, body: {} } },
    });
    await env.navigate({ frameId: 0, tabId: 1, url: "http://10.0.0.8/" });
    check("T4b probe 404 not hijacked", env.updates.length === 0);
  }

  // T5: public host never probed, never touched
  {
    const env = makeEnv({});
    await env.navigate({ frameId: 0, tabId: 1, url: "http://example.com/?token=x" });
    check("T5 public host untouched", env.updates.length === 0 && env.fetchCalls.length === 0);
  }

  // T6: subframe navigation ignored
  {
    const env = makeEnv({ responses: { [DEVICE_STATUS]: { body: { authRequired: true } } } });
    await env.navigate({ frameId: 3, tabId: 1, url: DEVICE_URL });
    check("T6 subframe ignored", env.updates.length === 0 && env.fetchCalls.length === 0);
  }

  // T7: non-http scheme ignored
  {
    const env = makeEnv({});
    await env.navigate({ frameId: 0, tabId: 1, url: "chrome://settings/" });
    check("T7 non-http scheme ignored", env.updates.length === 0);
  }

  // T8: https device page also taken over (probe is still plain http)
  {
    const env = makeEnv({ responses: { [DEVICE_STATUS]: { body: { product: "blindcast", authRequired: true } } } });
    await env.navigate({ frameId: 0, tabId: 1, url: "https://192.168.31.216:8888/" });
    check(
      "T8 https device page taken over",
      env.updates.length === 1 &&
        env.updates[0].url === CONSOLE + "?host=192.168.31.216%3A8888",
      env.updates.length ? env.updates[0].url : "no update"
    );
  }

  // T9: failed probe is cached, second navigation does not re-probe
  {
    const env = makeEnv({});
    await env.navigate({ frameId: 0, tabId: 1, url: "http://10.9.9.9/" });
    await env.navigate({ frameId: 0, tabId: 2, url: "http://10.9.9.9/" });
    check("T9 failed probe cached", env.fetchCalls.length === 1 && env.updates.length === 0);
  }

  // T10: known host from popup history short-circuits the probe
  {
    const env = makeEnv({ storage: { host: "192.168.31.216:8888" } });
    await env.navigate({ frameId: 0, tabId: 1, url: DEVICE_URL + "?token=ZZ" });
    check(
      "T10 popup-known host reroutes without probing",
      env.updates.length === 1 && env.fetchCalls.length === 0,
      env.updates.length ? env.updates[0].url : "no update"
    );
  }

  // T11: hanging probe times out and leaves the page alone
  {
    const env = makeEnv({ responses: { "http://10.5.5.5/api/auth/status": "hang" } });
    const started = Date.now();
    await env.navigate({ frameId: 0, tabId: 1, url: "http://10.5.5.5/" });
    await sleep(1000);
    check(
      "T11 hanging probe does not hijack",
      env.updates.length === 0 && Date.now() - started < 5000,
      "elapsed=" + (Date.now() - started) + "ms"
    );
  }

  // T12: 扩展入口文件缺失（曾经的死页事故）时不接管，原页面留给用户
  {
    const env = makeEnv({ consoleMissing: true, responses: { [DEVICE_STATUS]: { body: { authRequired: true } } } });
    await env.navigate({ frameId: 0, tabId: 1, url: DEVICE_URL });
    check(
      "T12 missing console page leaves the device page alone",
      env.updates.length === 0 && env.fetchCalls.length === 1,
      "updates=" + env.updates.length + " probes=" + env.fetchCalls.length
    );
  }

  const failed = results.filter((r) => !r.ok);
  console.log(
    "RESULT " + (failed.length === 0 ? "ALL PASS" : "FAIL " + failed.map((r) => r.name).join(", ")) +
      " (" + (results.length - failed.length) + "/" + results.length + ")"
  );
  process.exit(failed.length === 0 ? 0 : 1);
})().catch((e) => {
  console.log("ERROR " + ((e && e.stack) || e));
  process.exit(1);
});

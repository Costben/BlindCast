/* Blind Desk local transport adapter for the Blind Desk panel (v130).
 *
 * The original panel speaks its own device protocol over a WebSocket-shaped
 * socket: text handshake {t,v,client} -> welcome/attached/deny, then binary
 * envelopes [chan:1][type:1][payload] (see outputs/reference/PROTOCOL-original.md).
 * This module implements that socket on top of the Blind Desk backend
 * (outputs/plans/backend-contract-20261009.md §3 REST / §4 WS) so the original
 * bundle runs unmodified against a local device.
 *
 * Seams it plugs into (anchors in js/shell.js):
 *   - socketFactory  eE@801255   -> session.socketFactory(url)
 *   - session factory wP@847891  -> patched to return createSession()
 *   - device socket   Hl@193695  -> uses {send,close,readyState,addEventListener}
 *   - decoder         R0@228030  -> createDecoder() for non-secure contexts
 *   - media attach    Go@260893  -> /media firstFrame {t:"attach",sessionId}
 *
 * Loaded before js/shell.js (classic script, so it also installs the
 * secure-context shims the shell needs on plain LAN HTTP).
 */
(function () {
  "use strict";
  if (window.__blindcastLocal) return;

  // ---- secure-context shims (must exist before js/shell.js executes) -------
  // Over plain http:// the panel loses crypto.randomUUID, navigator.clipboard
  // and navigator.storage.getDirectory (all secure-context-only). The original
  // code calls them directly in a few places, so provide local fallbacks.
  function installSecureShims() {
    // crypto.randomUUID -- used directly by the Fusion window opener.
    try {
      if (typeof crypto !== "undefined" && !crypto.randomUUID && crypto.getRandomValues) {
        var uuid = function () {
          var b = crypto.getRandomValues(new Uint8Array(16));
          b[6] = (b[6] & 0x0f) | 0x40;
          b[8] = (b[8] & 0x3f) | 0x80;
          var h = [];
          for (var i = 0; i < 16; i++) h.push((b[i] + 0x100).toString(16).slice(1));
          return h.slice(0, 4).join("") + "-" + h.slice(4, 6).join("") + "-" +
            h.slice(6, 8).join("") + "-" + h.slice(8, 10).join("") + "-" + h.slice(10, 16).join("");
        };
        try { Object.defineProperty(crypto, "randomUUID", { value: uuid, configurable: true, writable: true }); }
        catch (e) { try { crypto.randomUUID = uuid; } catch (e2) {} }
      }
    } catch (e) {}

    // navigator.clipboard -- paste-event read + user-gesture execCommand copy.
    try {
      if (typeof navigator !== "undefined" && !navigator.clipboard) {
        var store = "";
        document.addEventListener("paste", function (ev) {
          try {
            var cd = ev.clipboardData || window.clipboardData;
            var t = cd && cd.getData("text");
            if (t) store = t;
          } catch (e) {}
        }, true);
        var clip = {
          writeText: function (t) {
            return new Promise(function (res, rej) {
              try {
                var ta = document.createElement("textarea");
                ta.value = String(t);
                ta.setAttribute("readonly", "");
                ta.style.cssText = "position:fixed;top:0;left:0;opacity:0";
                document.body.appendChild(ta);
                ta.select();
                var ok = false;
                try { ok = document.execCommand("copy"); } catch (e) {}
                document.body.removeChild(ta);
                if (!ok) { rej(new Error("copy blocked")); return; }
                store = String(t);
                res();
              } catch (e) { rej(e); }
            });
          },
          readText: function () { return Promise.resolve(store); }
        };
        try { Object.defineProperty(navigator, "clipboard", { value: clip, configurable: true }); }
        catch (e) { try { navigator.clipboard = clip; } catch (e2) {} }
      }
    } catch (e) {}

    // navigator.storage.getDirectory (OPFS) -- the fs-download path builds a
    // temp file through it. Fall back to an in-memory directory handle.
    try {
      if (typeof navigator !== "undefined" && navigator.storage && !navigator.storage.getDirectory) {
        var mem = memoryDirectory();
        var getDir = function () { return Promise.resolve(mem); };
        try { Object.defineProperty(navigator.storage, "getDirectory", { value: getDir, configurable: true }); }
        catch (e) { try { navigator.storage.getDirectory = getDir; } catch (e2) {} }
      }
    } catch (e) {}
  }

  function memoryDirectory() {
    var files = {};
    return {
      keys: function () {
        var names = Object.keys(files), i = 0;
        var it = {
          next: function () {
            return i < names.length
              ? Promise.resolve({ value: names[i++], done: false })
              : Promise.resolve({ value: undefined, done: true });
          }
        };
        if (typeof Symbol !== "undefined" && Symbol.asyncIterator) it[Symbol.asyncIterator] = function () { return it; };
        return it;
      },
      removeEntry: function (name) { delete files[name]; return Promise.resolve(); },
      getFileHandle: function (name, opts) {
        if (!files[name]) {
          if (!opts || !opts.create) return Promise.reject(new Error("NotFoundError: " + name));
          files[name] = { chunks: [] };
        }
        var entry = files[name];
        return Promise.resolve({
          createWritable: function () {
            return Promise.resolve({
              write: function (d) { entry.chunks.push(d); return Promise.resolve(); },
              close: function () { return Promise.resolve(); },
              abort: function () { entry.chunks = []; return Promise.resolve(); }
            });
          },
          getFile: function () {
            return Promise.resolve(new File(entry.chunks, name, { type: "application/octet-stream" }));
          }
        });
      }
    };
  }

  // ---- Fusion window placement --------------------------------------------
  // The shell opens every Fusion window with `popup=1,width=…,height=…` and no
  // position, so the browser drops each one on its own default spot (the
  // opener's top-left corner) and they all pile up in the same place.  Place
  // them in a cascade anchored to the window the click came from instead: each
  // new window steps down-right, stays fully inside the visible screen, and
  // never lands exactly on top of the previous one.
  var WIN_CASCADE_STEP = 28;      // px between two windows of the same batch
  var WIN_INSET = 24;             // px between the anchor window and the first
  function installWindowPlacement() {
    var nativeOpen = window.open;
    if (typeof nativeOpen !== "function") return;
    var step = 0;
    function placed(url, name, features) {
      // Only the shell's popups are positioned; link opens (window.open() with
      // no features) keep the browser's own behaviour.
      if (typeof features !== "string" || features.indexOf("popup") < 0 ||
          /(^|,)\s*(left|top|screenX|screenY)=/i.test(features)) {
        return nativeOpen.call(window, url, name, features);
      }
      var screen = window.screen || {};
      var availW = Number(screen.availWidth) || 0, availH = Number(screen.availHeight) || 0;
      var availL = Number(screen.availLeft) || 0, availT = Number(screen.availTop) || 0;
      var wm = /(^|,)\s*width=(\d+)/i.exec(features), hm = /(^|,)\s*height=(\d+)/i.exec(features);
      var w = wm ? Number(wm[2]) : 0, h = hm ? Number(hm[2]) : 0;
      var offset = (step++ % 6) * WIN_CASCADE_STEP;
      var left = (Number(window.screenX) || 0) + WIN_INSET + offset;
      var top = (Number(window.screenY) || 0) + WIN_INSET + offset;
      if (availW > 0 && w > 0) left = Math.min(left, availL + availW - w);
      if (availH > 0 && h > 0) top = Math.min(top, availT + availH - h);
      left = Math.max(left, availL);
      top = Math.max(top, availT);
      return nativeOpen.call(window, url, name,
        features + ",left=" + Math.round(left) + ",top=" + Math.round(top));
    }
    window.open = placed;
  }

  // Keep the original Desktop/Fusion layout and its taskbar unchanged.
  installSecureShims();
  installWindowPlacement();

  var params = new URLSearchParams(location.search);
  var hostParam = (params.get("host") || "").trim();
  var ORIGIN = hostParam
    ? (/^https?:\/\//i.test(hostParam) ? hostParam : "http://" + hostParam).replace(/\/+$/, "")
    : location.origin;

  var TOKEN = params.get("token") || "";
  if (!TOKEN) { try { TOKEN = localStorage.getItem("blindcast-token") || ""; } catch (e) {} }
  var authRequired = null;   // cached /api/auth/status

  function qs(p) { return TOKEN ? (p.indexOf("?") < 0 ? "?" : "&") + "token=" + encodeURIComponent(TOKEN) : ""; }
  function httpUrl(p) { return ORIGIN + "/" + String(p).replace(/^\//, "") + qs(p); }
  function wsUrl(p) { return ORIGIN.replace(/^http/, "ws") + "/" + String(p).replace(/^\//, "") + qs(p); }

  function log() {
    try { console.debug.apply(console, ["[blindcast-local]"].concat([].slice.call(arguments))); } catch (e) {}
  }

  function api(path, opts) {
    opts = opts || {};
    var init = { cache: "no-store", headers: {} };
    if (opts.body !== undefined) {
      init.method = opts.method || "POST";
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(opts.body);
    } else if (opts.method) {
      init.method = opts.method;
    }
    return fetch(httpUrl(path), init).then(function (r) {
      return r.json().catch(function () { return { ok: false, error: "HTTP " + r.status }; });
    }).catch(function (e) { return { ok: false, error: String((e && e.message) || e) }; });
  }

  // ---- local auth (backend contract §3.2) --------------------------------
  // The panel normally authenticates over the RTC ticket flow. Locally we route
  // the handshake through the backend: /api/auth/status -> optional code ->
  // POST /api/pair -> token, and validate an existing token against a real
  // authenticated endpoint (never answer "welcome" without a successful check).
  function authStatus() {
    if (authRequired !== null) return Promise.resolve(authRequired);
    return fetch(httpUrl("api/auth/status"), { cache: "no-store" })
      .then(function (r) { return r.json(); })
      .then(function (j) { authRequired = !!j && j.authRequired !== false; return authRequired; })
      .catch(function () { return true; });   // fail closed
  }
  function tokenOk(tok) {
    if (!tok) return Promise.resolve(false);
    return fetch(ORIGIN + "/api/status?token=" + encodeURIComponent(tok), { cache: "no-store" })
      .then(function (r) { return r.ok; })
      .catch(function () { return false; });
  }
  function pair(code) {
    if (!code) return Promise.resolve(null);
    return fetch(httpUrl("api/pair"), {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: String(code), name: "Blind Desk Web" })
    }).then(function (r) {
      return r.json().then(function (j) {
        if (r.ok && j && j.ok !== false) return j.token || j.clientToken || j.value || null;
        return null;
      }).catch(function () { return null; });
    }).catch(function () { return null; });
  }
  function authorize(tok) {
    return authStatus().then(function (required) {
      if (!required) return true;
      return tokenOk(tok);
    });
  }
  function adoptToken(tok) {
    if (!tok) return;
    TOKEN = tok;
    try { localStorage.setItem("blindcast-token", tok); } catch (e) {}
  }

  // ---- device geometry (for density + input normalization) ----------------
  var desktopInfo = null, desktopPromise = null;
  function deviceInfo() {
    if (desktopInfo) return Promise.resolve(desktopInfo);
    if (desktopPromise) return desktopPromise;
    desktopPromise = api("api/desktop").then(function (r) {
      desktopInfo = (r && r.ok !== false) ? r : {};
      return desktopInfo;
    }).catch(function () { desktopInfo = {}; return desktopInfo; });
    return desktopPromise;
  }
  function deviceDensity() {
    return deviceInfo().then(function (d) {
      return Number(d && d.densityDpi) || Math.round((window.devicePixelRatio || 1) * 160);
    });
  }

  var lx = new TextEncoder(), cx = new TextDecoder();

  // ---- envelope helpers (mirror of shell.js Il/Dw) ------------------------
  function enc(chan, type, payload) {
    var body = typeof payload === "string" ? lx.encode(payload)
      : payload instanceof Uint8Array ? payload
      : new Uint8Array(payload || 0);
    var out = new Uint8Array(2 + body.length);
    out[0] = chan; out[1] = type; out.set(body, 2);
    return out;
  }
  function encJson(chan, type, obj) { return enc(chan, type, JSON.stringify(obj)); }
  function dec(buf) {
    var t = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
    if (t.length < 2) return null;
    return { chan: t[0], type: t[1], payload: t.subarray(2) };
  }
  function jsonPayload(p) { try { return JSON.parse(cx.decode(p)); } catch (e) { return null; } }
  function clamp01(v) { v = Number(v); return !isFinite(v) ? 0 : v < 0 ? 0 : v > 1 ? 1 : v; }
  function bytesToBase64(u8) {
    var b = "", C = 0x8000;
    for (var i = 0; i < u8.length; i += C) b += String.fromCharCode.apply(null, u8.subarray(i, i + C));
    return btoa(b);
  }

  // channel ids (PROTOCOL-original §3)
  // Channel 5 is upload/file data; the original terminal uses channel 6/type 1.
  var CH = { KEEPALIVE: 0, VIDEO: 1, AUDIO: 2, CLIPBOARD: 3, CONTROL: 4, SESSION: 5, TERMINAL: 6, WINDOW_MEDIA: 6, CAMERA: 7, WIDGET: 8 };
  var VT = { CONFIG: 1, FRAME: 2, GAP: 3 };
  var CT = { JSON: 1, CLIP_IN: 1, CLIP_OUT: 2 };

  // Capabilities delivered through this adapter, matched to the backend routes
  // actually registered in Blind DeskServer: clipboard -> /api/clipboard,
  // file/fs -> /api/fs/*, terminal -> /ws/terminal, notification ->
  // /api/notifications; device-audio -> opt-in PCM on /ws/stream.
  // Not advertised: microphone audio, phone-screen (mode:"mirror" is not an independent session).
  var CAPS = ["video", "device-audio", "control", "multi-session", "multi-touch", "app-list", "file", "fs", "clipboard", "desk-widget", "terminal", "notification"];

  // ---- backend link (shared by all sockets of a session) ------------------
  function Link() {
    this.stream = null; this.control = null; this.widgets = null;
    this._ctlQueue = [];
    this._streamBackoff = 500; this._ctlBackoff = 500;
    this._streamTimer = null; this._ctlTimer = null;
    this.closed = false;
    this.listeners = { hello: [], frame: [], gap: [], widgetState: [], widgetFrame: [], streamOpen: [], streamClose: [] };
  }
  Link.prototype.on = function (ev, fn) { (this.listeners[ev] || (this.listeners[ev] = [])).push(fn); };
  Link.prototype.emit = function (ev, a, b) {
    var l = this.listeners[ev] || [];
    for (var i = 0; i < l.length; i++) { try { l[i](a, b); } catch (e) { log("listener", ev, e); } }
  };
  Link.prototype.openStream = function () {
    if (this.closed) return;
    if (this.stream && (this.stream.readyState === 0 || this.stream.readyState === 1)) return;
    var self = this, ws;
    try { ws = new WebSocket(wsUrl("ws/stream")); } catch (e) { this._reconnectStream(); return; }
    ws.binaryType = "arraybuffer";
    ws.onopen = function () {
      self._streamBackoff = 500;
      self.setPcmEnabled(!!self._pcmEnabled);
      self.emit("streamOpen");
    };
    ws.onmessage = function (ev) { self._onStream(ev.data); };
    ws.onclose = function () {
      if (self.stream === ws) self.stream = null;
      self.emit("streamClose");
      self._reconnectStream();
    };
    ws.onerror = function () {};
    this.stream = ws;
  };
  Link.prototype._reconnectStream = function () {
    if (this.closed || this._streamTimer) return;
    var self = this, d = this._streamBackoff;
    this._streamBackoff = Math.min(d * 2, 8000);
    this._streamTimer = setTimeout(function () { self._streamTimer = null; self.openStream(); }, d);
  };
  Link.prototype._onStream = function (data) {
    if (typeof data === "string") { try { this.emit("hello", JSON.parse(data)); } catch (e) {} return; }
    var u = new Uint8Array(data); if (!u.length) return;
    var kind = u[0];
    if (kind === 0x01) { this.emit("mainFrame", 0, u.subarray(1)); return; }
    if (kind === 0x02) { this.emit("audio", u.subarray(1)); return; }
    if (kind === 0x04 && u.length > 17) {
      this.emit("pcm", u.subarray(1)); return;
    }
    if (kind === 0x11) {
      if (u.length < 10) return;
      var wid = u[1];
      var pts = 0; for (var i = 0; i < 8; i++) pts = pts * 256 + u[2 + i];
      this.emit("windowFrame", wid, { pts: pts, data: u.subarray(10) });
      return;
    }
    if (kind === 0x03 && u.length > 5) {
      var len = (u[1] << 24 | u[2] << 16 | u[3] << 8 | u[4]) >>> 0;
      this.emit("jpeg", u.subarray(5, 5 + len));
      return;
    }
  };
  Link.prototype.openControl = function () {
    if (this.closed) return null;
    if (this.control && (this.control.readyState === 0 || this.control.readyState === 1)) return this.control;
    var self = this, ws;
    try { ws = new WebSocket(wsUrl("ws/control")); } catch (e) { this._reconnectControl(); return null; }
    ws.onopen = function () { self._ctlBackoff = 500; self._flushCtl(); };
    ws.onmessage = function () {};
    ws.onclose = function () {
      if (self.control === ws) self.control = null;
      self._reconnectControl();
    };
    ws.onerror = function () {};
    this.control = ws;
    return ws;
  };
  Link.prototype.setPcmEnabled = function (enabled) {
    this._pcmEnabled = enabled;
    if (this.stream && this.stream.readyState === 1) {
      this.stream.send(JSON.stringify({ type: "pcmAudio", enabled: enabled }));
    }
  };
  Link.prototype._reconnectControl = function () {
    if (this.closed || this._ctlTimer) return;
    var self = this, d = this._ctlBackoff;
    this._ctlBackoff = Math.min(d * 2, 8000);
    this._ctlTimer = setTimeout(function () { self._ctlTimer = null; self.openControl(); }, d);
  };
  Link.prototype.ctl = function (obj) {
    var s = JSON.stringify(obj);
    var ws = this.openControl();
    if (ws && ws.readyState === 1) { try { ws.send(s); return true; } catch (e) {} }
    if (this._ctlQueue.length > 256) this._ctlQueue.shift();
    this._ctlQueue.push(s);
    return true;
  };
  Link.prototype._flushCtl = function () {
    var q = this._ctlQueue; if (!q || !q.length) return;
    this._ctlQueue = [];
    for (var i = 0; i < q.length; i++) { try { this.control.send(q[i]); } catch (e) {} }
  };
  Link.prototype.close = function () {
    this.closed = true;
    if (this._streamTimer) clearTimeout(this._streamTimer);
    if (this._ctlTimer) clearTimeout(this._ctlTimer);
    this._streamTimer = this._ctlTimer = null;
    [this.stream, this.control, this.widgets].forEach(function (w) { try { w && w.close(); } catch (e) {} });
    this.stream = this.control = this.widgets = null;
  };

  // ---- backend event -> panel command bridge ------------------------------
  function Bridge(link, socket) {
    this.link = link; this.socket = socket; this.session = socket.session;
    this.windows = {}; this.timer = null;
    var self = this;
    link.on("windowFrame", function (wid, f) { self._video(wid, f); });
    link.on("pcm", function (frame) {
      if (!link._pcmEnabled || frame.length < 18) return;
      var view = new DataView(frame.buffer, frame.byteOffset, frame.byteLength);
      var rate = view.getUint32(0), channels = view.getUint32(4);
      if (rate < 8000 || rate > 96000 || channels < 1 || channels > 2) return;
      var key = rate + ":" + channels;
      if (self._audioConfig !== key) {
        self._audioConfig = key;
        self.socket._emitEnvelope(CH.AUDIO, 1, JSON.stringify({ sampleRate: rate, channels: channels }));
      }
      // Original PCM sink expects an eight-byte header followed by PCM16LE.
      self.socket._emitEnvelope(CH.AUDIO, 2, frame.subarray(8));
    });
    link.on("streamOpen", function () { try { link.ctl({ type: "requestIDR" }); } catch (e) {} });
    link.on("widgetState", function (st) { self.socket._emitEnvelope(CH.WIDGET, 1, JSON.stringify({ c: "widget-state", widgets: st })); });
    link.on("widgetFrame", function (buf) { self.socket._emitRaw(buf); });
  }
  Bridge.prototype._video = function (wid, f) {
    // Route the frame to the media socket attached for this window session.
    var sock = this.session && this.session.mediaFor(wid);
    if (!sock) return;
    sock._pushVideo(f);
  };
  Bridge.prototype.start = function () {
    this.link.openStream();
    deviceInfo();   // cache desktop geometry/density for normalization + config
    this.refreshWindows();
    var self = this;
    this.timer = setInterval(function () { self.refreshWindows(); }, 3000);
  };
  Bridge.prototype.stop = function () { if (this.timer) clearInterval(this.timer); this.timer = null; this._winEpoch = (this._winEpoch || 0) + 1; this._winInflight = false; this._emptyStreak = 0; };
  // window-state shape the shell's iP() consumes: a NEW sessionId must be
  // announced as state:"opening" (that is what builds the `.win`); unknown
  // sessionIds carrying state:"live" are dropped (iP: Ue.get(t) == null).
  //
  // No `kind` here.  The shell opens the window (`is()` -> hub.prepareOpen) and
  // later matches it against this message with ONE comparator (`J1`), which
  // compares kinds whenever either side carries one.  An app open carries no
  // kind, so announcing kind:"app" makes every match fail: the window the shell
  // already opened is never adopted, it stays unbound and black for good, and
  // the shell opens a second, real window beside it.  The original device sends
  // {c,sessionId,state,pkg} only, and the shell derives the app kind itself
  // (`wm`: e.kind === phone ? phone : hm(sessionId) || "app").
  Bridge.prototype._stateMsg = function (w, state) {
    return {
      c: "window-state", sessionId: String(w.windowId), pkg: w.packageName, packageName: w.packageName,
      intentUrl: String(w.intentUrl || ""),
      userId: Number(w.userId) || 0, w: w.width, h: w.height, dpr: 1, state: state
    };
  };
  Bridge.prototype._emitState = function (w, state) {
    this.socket._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify(this._stateMsg(w, state)));
  };
  Bridge.prototype.refreshWindows = function () {
    var self = this;
    // The endpoint resolves per-window and can take longer than the poll
    // interval; never let polls overlap or a late answer clobber a newer one.
    if (this._winInflight || this._mutating) return;
    this._winInflight = true;
    var epoch = this._winEpoch || 0;
    api("api/desktop/windows").then(function (r) {
      if (self.session.closed || epoch !== (self._winEpoch || 0)) return;
      self._winInflight = false;
      // Only reconcile on a real answer; a failed poll must not close windows.
      if (!r || r.ok !== true || !Array.isArray(r.windows)) return;
      var list = r.windows;
      // An empty answer is ambiguous: a transient read glitch would otherwise
      // tear every window down. Require two empty polls in a row; a genuine
      // empty repeats, and a single glitch is absorbed.
      if (!list.length) {
        if (!self._emptyStreak) { self._emptyStreak = 1; return; }
      } else {
        self._emptyStreak = 0;
      }
      var seen = {};
      list.forEach(function (w) {
        if (w.state !== "running") return;
        seen[w.windowId] = 1;
        self.session.setSize(w.windowId, w.width, w.height);
        var prev = self.windows[w.windowId];
        if (!prev) {
          prev = self.windows[w.windowId] = {
            windowId: w.windowId, packageName: w.packageName, userId: 0,
            width: w.width, height: w.height, live: false, confirmed: true
          };
          self._emitState(prev, "opening");
        } else {
          prev.packageName = w.packageName; prev.width = w.width; prev.height = w.height;
          prev.confirmed = true;
          if (!prev.live) { prev.live = true; self._emitState(prev, "live"); }
        }
      });
      Object.keys(self.windows).forEach(function (id) {
        var prev = self.windows[id];
        if (seen[id] || !prev.confirmed) return;   // keep a just-opened window during the create race
        delete self.windows[id];
        self.socket._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({
          c: "window-state", sessionId: String(prev.windowId), pkg: prev.packageName,
          packageName: prev.packageName, state: "closed"
        }));
      });
      // The original transport continuously publishes the focused package and
      // task chrome.  The local endpoint returns windows in top-task order;
      // use that stable ordering as the focused task and keep the away state
      // explicit so stale IME/task chrome is cleared after a close.
      var focused = list.filter(function (w) { return w.state === "running"; })[0];
      self.socket._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({
        c: "focused-app", pkg: focused ? String(focused.packageName || "") : "", userId: focused ? Number(focused.userId) || 0 : 0
      }));
      self.socket._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "task-state", away: !focused }));
    });
  };

  // Annex-B: a keyframe access unit is SPS(7)+PPS(8)+IDR(5); skip non-VCL NALs
  // and decide only at the first VCL slice. Handles 3- and 4-byte start codes.
  function detectKey(annexb) {
    var n = annexb.length;
    for (var i = 0; i + 3 < n; i++) {
      if (annexb[i] !== 0 || annexb[i + 1] !== 0) continue;
      var hdr;
      if (annexb[i + 2] === 1) hdr = i + 3;
      else if (annexb[i + 2] === 0 && annexb[i + 3] === 1) hdr = i + 4;
      else continue;
      if (hdr >= n) break;
      var t = annexb[hdr] & 0x1f;
      if (t === 5) return true;
      if (t === 1) return false;
      i = hdr;
    }
    return false;
  }

  // ---- LocalSocket --------------------------------------------------------
  function LocalSocket(kind, session) {
    this.kind = kind;                 // "ws" | "media"
    this.session = session || null;
    this.readyState = 0;
    this.binaryType = "arraybuffer";
    this.CONNECTING = 0; this.OPEN = 1; this.CLOSING = 2; this.CLOSED = 3;
    this._handlers = {};
    this._windowId = 0;
    this._sessionId = null;
    this._attached = false;
    this._termPending = [];    // FIFO of term-open reqIds awaiting {type:"opened"}
    this._termSocket = null;
    this._notifTimer = null;
    var self = this;
    setTimeout(function () {
      if (self.readyState === 3) return;
      self.readyState = 1;
      self._fire("open", {});
    }, 0);
  }
  LocalSocket.prototype._fire = function (type, ev) {
    var l = this._handlers[type] || [];
    for (var i = 0; i < l.length; i++) { try { l[i](ev); } catch (e) { log("handler", type, e); } }
  };
  LocalSocket.prototype.addEventListener = function (type, fn) { (this._handlers[type] || (this._handlers[type] = [])).push(fn); };
  LocalSocket.prototype.removeEventListener = function (type, fn) {
    var l = this._handlers[type] || []; var i = l.indexOf(fn); if (i >= 0) l.splice(i, 1);
  };
  LocalSocket.prototype._deliver = function (data) { this._fire("message", { data: data }); };
  LocalSocket.prototype._emitEnvelope = function (chan, type, payload) {
    this._deliver(enc(chan, type, payload).buffer);
  };
  LocalSocket.prototype._emitRaw = function (buf) { this._deliver(buf); };

  LocalSocket.prototype.send = function (data) {
    if (this.readyState !== 1) throw new DOMException("socket is not open", "InvalidStateError");
    if (typeof data === "string") { this._onText(data); return; }
    var u = data instanceof Uint8Array ? data : new Uint8Array(data);
    var e = dec(u); if (e) this._onEnvelope(e.chan, e.type, e.payload);
  };
  LocalSocket.prototype.close = function () {
    if (this.readyState === 3) return;
    this.readyState = 3;
    if (this._sessionId && this.session) this.session.unregisterMedia(this._sessionId, this);
    this._fire("close", { code: 1000, reason: "", wasClean: true });
  };

  // Video frames arrive from the backend with a per-window id; forward the
  // attached session's frames as the panel media envelope [8B len][8B pts][1B key][annexb].
  LocalSocket.prototype._pushVideo = function (f) {
    if (!this._attached || this.readyState !== 1) return;
    var total = 17 + f.data.length;
    var frame = new Uint8Array(total);
    var dv = new DataView(frame.buffer);
    dv.setBigUint64(0, BigInt(total));
    dv.setBigUint64(8, BigInt(f.pts || 0));
    frame[16] = detectKey(f.data) ? 1 : 0;
    frame.set(f.data, 17);
    this._emitEnvelope(CH.VIDEO, VT.FRAME, frame);
  };

  LocalSocket.prototype._onText = function (text) {
    var msg = null; try { msg = JSON.parse(text); } catch (e) {}
    if (!msg) return;
    if (msg.t === "attach") {
      // Per-window media socket attach (shell.js Go@260893). Bind the window id
      // carried by sessionId and acknowledge with the same sessionId.
      this._sessionId = msg.sessionId;
      this._windowId = Number(msg.sessionId) || 0;
      this._attached = true;
      if (this.session) this.session.registerMedia(msg.sessionId, this);
      this._deliver(JSON.stringify({ t: "attached", sessionId: msg.sessionId }));
      var me = this;
      // Video config: dp is the DEVICE density (the panel uses it for wheel/zoom
      // math), not the browser's devicePixelRatio.
      deviceDensity().then(function (dp) {
        me._emitEnvelope(CH.VIDEO, VT.CONFIG, JSON.stringify({ dp: dp }));
      });
      return;
    }
    if (msg.t === "resume") {
      var self = this, tok = msg.token || TOKEN;
      authorize(tok).then(function (ok) {
        if (ok) { if (msg.token) adoptToken(msg.token); self._welcome(); }
        else self._deny("AUTH");
      });
      return;
    }
    if (msg.t === "bootstrap") {
      var self2 = this, code = msg.ticket || msg.code || "";
      pair(code).then(function (tok) {
        if (tok) { adoptToken(tok); self2._welcome(); }
        else self2._deny("CODE");
      });
      return;
    }
    if (msg.t === "pair") {
      var self3 = this;
      authStatus().then(function (required) {
        if (!required) { self3._welcome(); return; }
        if (!TOKEN) { self3._deliver(JSON.stringify({ t: "pair-code", code: "", requestId: "" })); return; }
        tokenOk(TOKEN).then(function (ok) {
          if (ok) self3._welcome();
          else self3._deliver(JSON.stringify({ t: "pair-code", code: "", requestId: "" }));
        });
      });
      return;
    }
    if (msg.t === "rtc.media") { this._onMediaLease(msg); return; }
  };

  // Only reached after a successful auth check (authorize/pair).
  LocalSocket.prototype._welcome = function () {
    var self = this;
    setTimeout(function () {
      self._deliver(JSON.stringify({
        t: "welcome", v: 2, web: 130,
        // The panel is now the Blind Desk-compatible Fusion client.  Keep the
        // local device identity generic until the status route provides a
        // user-configured name; never expose the retired Blind Desk branding.
        device: { id: "android-device", name: "Android Device" },
        token: TOKEN || "local", signalKey: null, wakeId: null, turnUrl: "",
        caps: CAPS.slice()
      }));
    }, 0);
    this._bridge = new Bridge(this.session.link, this);
    this._bridge.start();
  };
  LocalSocket.prototype._deny = function (reason, detail) {
    var self = this;
    setTimeout(function () {
      self._deliver(JSON.stringify({ t: "deny", reason: reason || "PROTO", detail: detail || "" }));
    }, 0);
  };

  LocalSocket.prototype._onEnvelope = function (chan, type, payload) {
    var j = jsonPayload(payload);
    if (chan === CH.KEEPALIVE) return;
    if (chan === CH.CONTROL && type === CT.JSON && j) return this._onCommand(j);
    if (chan === CH.CLIPBOARD) {
      // chan3: type1 = device->browser (pull device clipboard),
      //        type2 = browser->device (push browser clipboard).
      if (type === CT.CLIP_IN) {
        var self = this;
        api("api/clipboard").then(function (r) {
          if (r && r.ok !== false) {
            self._emitEnvelope(CH.CLIPBOARD, CT.CLIP_IN, JSON.stringify({ text: r.text || "" }));
          }
        });
      } else if (type === CT.CLIP_OUT && j) {
        api("api/clipboard", { body: { text: j.text || "" } });
      }
      return;
    }
    if (chan === CH.SESSION) return this._onChan5(type, payload);
    if (chan === CH.TERMINAL && type === 1) return this._forwardTerminalBytes(payload);
    if (chan === CH.WIDGET) return this._onWidget(j);
    if (chan === CH.VIDEO && type === VT.GAP) { this.session.link.ctl({ type: "requestIDR", wid: this._windowId }); return; }
    if (chan === CH.AUDIO) {
      // type 3 is original device-playback intent; type 4 is microphone input,
      // which remains unadvertised and must never enable a microphone capture.
      if (type === 3 && j) {
        if (this._bridge) this._bridge._audioConfig = null;
        this.session.link.setPcmEnabled(!!j.on);
      }
      return;
    }
    log("unhandled envelope", chan, type);
  };

  LocalSocket.prototype._onCommand = function (j) {
    var self = this, link = this.session.link;
    switch (j.c) {
      case "app-list":
        api("api/apps").then(function (r) {
          // Original wire shape (app-list entry) per the device model
          // p203x0/C2891d.java m5584m: {packageName, displayName, userId,
          // isSystemApp, appCategory, game}. The panel reads e.displayName
          // (labelForApp / search filter / Di) -- emitting `label` renders
          // every app with a blank name. `game` is appCategory===0.
          var apps = (r && r.ok && r.apps ? r.apps : []).map(function (a) {
            var cat = Number.isFinite(a.appCategory) ? a.appCategory : -1;
            return {
              packageName: a.package,
              displayName: a.label == null ? "" : String(a.label),
              userId: 0,
              isSystemApp: !!a.system,
              appCategory: cat,
              game: cat === 0
            };
          });
          self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "app-list", apps: apps }));
        });
        return;
      case "app-icons":
        this._sendIcons(j.items || []);
        return;
      case "open-window":
        api("api/desktop/windows", { body: {
          action: "open", package: j.pkg || j.package || j.packageName,
          component: j.component || "",
          intentUrl: j.intentUrl || "",
          kind: j.kind || "app",
          user: Number.isFinite(Number(j.userId)) ? Number(j.userId) : 0,
          width: Number(j.w) || undefined, height: Number(j.h) || undefined
        } }).then(function (r) {
          var b = self._bridge;
          if (r && r.ok && r.window) {
            var w = r.window;
            self.session.setSize(w.windowId, w.width, w.height);
            if (b) {
              b.windows[w.windowId] = {
                windowId: w.windowId, packageName: w.packageName, userId: Number(j.userId) || 0,
                width: w.width, height: w.height, live: false, confirmed: false
              };
              b._emitState(b.windows[w.windowId], "opening");
              b.refreshWindows();
            }
            // The stock panel consumes these two notifications to keep the
            // focused package and away/IME chrome in sync even when the
            // transport is a local HTTP adapter rather than Blind Desk RTC.
            self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({
              c: "focused-app", pkg: String(w.packageName || j.pkg || j.package || ""),
              userId: Number(w.userId == null ? j.userId : w.userId) || 0
            }));
            self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "task-state", away: false }));
            return;
          }
          self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({
            c: "window-state", sessionId: "", pkg: j.pkg || j.package, packageName: j.pkg || j.package,
            state: "failed", error: (r && r.error) || "open failed"
          }));
        });
        return;
      case "close-window":
        api("api/desktop/windows", { body: { action: "close", windowId: Number(j.sessionId) || 0 } }).then(function () {
          if (self._bridge) self._bridge.refreshWindows();
        });
        return;
      case "input": return this._onInput(j);
      case "display-power":
        api("api/screen", { body: { on: !!j.on } }).then(function (r) {
          // The panel toggles its screen-off UI optimistically (ft(): re(!b) +
          // send {c:"display-power",on}); the device reconciles by pushing
          // {c:"display-power", on} (device source C2882X:1076), whose consumer
          // is `re(Re.on===!1)` (on:false -> screen-off overlay). Echo the true
          // state so the UI syncs if the device disagrees. NOTE: the device's
          // other reply `display-power-state` carries {displayId,state,reason}
          // (C2867H:1378), not `on`, so it must not be reused for this.
          var on = (r && typeof r.blackedOut === "boolean") ? !r.blackedOut : !!j.on;
          self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "display-power", on: on }));
        });
        return;
      case "resize":
        // The first packet initializes the panel's viewport after attach; the
        // existing source already has geometry. Rebuild only subsequent edits.
        if (!this._geometryInitialized) { this._geometryInitialized = true; return; }
        return this._resizeWindow(j);
      case "relaunch":
        api("api/desktop/windows", { body: {
          action: "relaunch", windowId: this._windowId
        } }).then(function (r) {
          if (r && r.ok && r.window) {
            var w = r.window;
            self.session.setSize(w.windowId, w.width, w.height);
            if (self._bridge && self._bridge.windows[w.windowId]) {
              self._bridge.windows[w.windowId].width = w.width;
              self._bridge.windows[w.windowId].height = w.height;
              self._bridge._emitState(self._bridge.windows[w.windowId], "live");
              self._bridge.refreshWindows();
            }
            self.session.link.ctl({ type: "requestIDR", wid: w.windowId });
          } else {
            self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({
              c: "toast", text: (r && r.error) || "应用重启失败"
            }));
          }
        });
        return;
      case "density": case "decor-insets":
        // The panel reports the browser chrome inset using the original `lu`
        // message. Echo it back so the shell applies the same top offset while
        // the Android display remains unaware of browser-only decoration.
        this._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "decor-insets", top: Math.max(0, Math.trunc(Number(j.top) || 0)) }));
        return;
      case "logout":
        try { localStorage.removeItem("blindcast-token"); } catch (e) {}
        this._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "logout", ok: true }));
        return;
      case "widget-sync": case "widget-size": case "widget-ack":
      case "widget-input": case "widget-hide": case "widget-unbind": case "widget-drop": case "widget-open-on":
        return this._onWidget(j);
      case "app-menu":
        this._shortcutPackage = j.packageName || "";
        api("api/apps/shortcuts?package=" + encodeURIComponent(j.packageName || "") + "&user=" + encodeURIComponent(j.userId || 0)).then(function (r) {
          self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "app-menu", packageName: j.packageName, userId: j.userId || 0, shortcuts: (r && r.ok !== false && Array.isArray(r.shortcuts)) ? r.shortcuts : [] }));
        });
        return;
      case "start-shortcut":
        var current = this._bridge && this._bridge.windows[this._windowId];
        api("api/apps/shortcut", { body: {
          package: (current && current.packageName) || j.packageName || this._shortcutPackage || "",
          shortcutId: j.id || j.shortcutId || "", user: current ? (Number(current.userId) || 0) : 0,
          sessionId: String(this._windowId)
        } }).then(function (r) {
          if (!r || r.ok === false) self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "toast", text: (r && r.error) || "快捷方式启动失败" }));
        });
        return;
      case "device-info":
        api("api/status").then(function (r) {
          r = r || {};
          self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({
            c: "device-info",
            device: {
              battery: { level: Number(r.batteryLevel), charging: !!r.charging },
              androidVersion: String(r.androidVersion || ""),
              connection: String(r.connectionType || r.connection || ""),
              storage: {
                used: Number(r.storageUsedBytes) || 0,
                total: Number(r.storageTotalBytes) || 0
              }
            }
          }));
        });
        return;
      case "display-night-mode":
        // Original panel mirrors the browser theme here; the backend applies it
        // per virtual display (fail-closed) and reports failure honestly.
        api("api/display/night-mode", { body: {
          on: !!j.on, windowId: Number(j.sessionId) || this._windowId || 0
        } }).then(function (r) {
          if (r && r.ok === false) self._emitEnvelope(CH.CONTROL, CT.JSON,
            JSON.stringify({ c: "toast", text: r.error || "夜间模式设置失败" }));
        });
        return;
      case "video-ack":
        return;   // client-side ack of a decoded frame; nothing to forward
      default:
        if (/^fs-/.test(j.c)) return this._onFs(j);
        if (/^term-/.test(j.c)) return this._onTerm(j);
        if (/^notif-/.test(j.c)) return this._onNotif(j);
        log("unhandled command", j.c);
    }
  };

  // Serialize/coalesce geometry changes: the host rebuilds the same window id.
  // Preserve that session's media socket and invalidate pre-rebuild polls.
  LocalSocket.prototype._resizeWindow = function (j) {
    var wid = this._windowId, w = Math.round(Number(j.w)), h = Math.round(Number(j.h));
    if (wid <= 0 || !Number.isFinite(w) || !Number.isFinite(h) || w < 160 || h < 160 || w > 4096 || h > 4096) return;
    this._resizePending = { w: w, h: h, force: !!j.force };
    if (this._resizeInflight) return;
    var self = this;
    function next() {
      if (self.readyState !== 1 || self.session.closed) { self._resizePending = null; return; }
      var pending = self._resizePending; self._resizePending = null;
      if (!pending) return;
      var size = self.session.sourceSize(wid);
      if (!pending.force && size && size.w === pending.w && size.h === pending.h) return;
      self._resizeInflight = true;
      var bridge = self.session._ws && self.session._ws._bridge;
      if (bridge) { bridge._winEpoch = (bridge._winEpoch || 0) + 1; bridge._winInflight = false; bridge._mutating = (bridge._mutating || 0) + 1; }
      api("api/desktop/windows", { body: { action: "resize", windowId: wid, width: pending.w, height: pending.h } }).then(function (r) {
        self._resizeInflight = false;
        if (bridge) bridge._mutating--;
        if (self.readyState !== 1 || self.session.closed) return;
        if (r && r.ok && r.window) {
          var win = r.window;
          self.session.setSize(wid, win.width, win.height);
          if (bridge && bridge.windows[wid]) {
            bridge.windows[wid].width = win.width; bridge.windows[wid].height = win.height;
            bridge._emitState(bridge.windows[wid], "live");
          }
          self._emitEnvelope(CH.VIDEO, VT.GAP, "");
          linkForSync();
        } else {
          self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "toast", text: r && r.error || "窗口缩放失败" }));
        }
        if (bridge) bridge.refreshWindows();
        next();
      });
    }
    function linkForSync() { self.session.link.ctl({ type: "requestIDR", wid: wid }); }
    next();
  };

  // ---- files (A13/A14): panel fs-* over chan4 <-> backend /api/fs/* --------
  LocalSocket.prototype._onFs = function (j) {
    var self = this;
    function reply(obj) { self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify(obj)); }
    function failed(op, r) { reply({ c: "fs-op-result", reqId: j.reqId, op: op, ok: false, err: (r && (r.err || r.error)) || "eio" }); }
    function err(c, r) { return (r && (r.err || r.error)) || "eio"; }
    switch (j.c) {
      case "fs-roots":
        api("api/fs/roots").then(function (r) {
          if (!r || r.ok === false) return reply({ c: "fs-roots-result", reqId: j.reqId, ok: false, roots: [], error: err("roots", r) });
          reply({ c: "fs-roots-result", reqId: j.reqId, ok: true, roots: r.roots || [], error: "" });
        });
        return;
      case "fs-list":
        api("api/fs/list?path=" + encodeURIComponent(j.path || "/")).then(function (r) {
          if (!r || r.ok === false) return reply({ c: "fs-list-result", reqId: j.reqId, ok: false, path: j.path, entries: [], err: err("list", r) });
          var entries = (r.entries || []).map(function (e) {
            return { name: e.name, path: e.path, isDir: !!(e.isDir != null ? e.isDir : e.dir),
              size: Number(e.size) || 0, mtime: Number(e.mtime) || 0, mime: e.mime || "" };
          });
          reply({ c: "fs-list-result", reqId: j.reqId, ok: true, path: r.path || j.path, entries: entries, err: "" });
        });
        return;
      case "fs-stat":
        api("api/fs/stat?path=" + encodeURIComponent(j.path || "/")).then(function (r) {
          if (!r || r.ok === false) return reply({ c: "fs-stat-result", reqId: j.reqId, ok: false, path: j.path, err: err("stat", r) });
          var statName = String(r.path || j.path || "").split("/").filter(Boolean).pop() || "";
          reply({ c: "fs-stat-result", reqId: j.reqId, ok: true, path: r.path,
            entry: { name: statName, isDir: !!(r.isDir != null ? r.isDir : r.dir), size: Number(r.size) || 0,
              mtime: Number(r.mtime) || 0, mime: r.mime || "" }, err: "" });
        });
        return;
      case "fs-mkdir":
        api("api/fs/mkdir", { body: { path: j.path, name: j.name } }).then(function (r) {
          if (!r || r.ok === false) return failed("mkdir", r);
          reply({ c: "fs-op-result", reqId: j.reqId, op: "mkdir", ok: true, err: "" });
        });
        return;
      case "fs-rename":
        api("api/fs/rename", { body: { path: j.path, newName: j.newName } }).then(function (r) {
          if (!r || r.ok === false) return failed("rename", r);
          reply({ c: "fs-op-result", reqId: j.reqId, op: "rename", ok: true, err: "" });
        });
        return;
      case "fs-delete":
        api("api/fs/delete", { body: { paths: j.paths || [] } }).then(function (r) {
          if (!r || r.ok === false) return failed("delete", r);
          reply({ c: "fs-op-result", reqId: j.reqId, op: "delete", ok: true, deleted: r.deleted || 0, err: "" });
        });
        return;
      case "fs-move":
        api("api/fs/move", { body: { paths: j.paths || [], destDir: j.destDir } }).then(function (r) {
          if (!r || r.ok === false) return failed("move", r);
          reply({ c: "fs-op-result", reqId: j.reqId, op: "move", ok: true, moved: r.moved || 0, err: "" });
        });
        return;
      case "fs-download":
        // Backend streams the file with Content-Disposition. Keep an abort
        // handle per request and report byte progress to newer panel builds;
        // older builds simply ignore the additional control envelope.
        this._downloads = this._downloads || {};
        var downloadId = String(j.reqId == null ? (j.id == null ? Date.now() : j.id) : j.reqId);
        var dctrl = typeof AbortController === "function" ? new AbortController() : null;
        this._downloads[downloadId] = dctrl;
        var emitDownloadProgress = function (received, total) {
          self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "fs-progress", reqId: j.reqId, id: downloadId, op: "download", received: received, total: total }));
        };
        fetch(httpUrl("api/fs/download?path=" + encodeURIComponent(j.path || "")), { signal: dctrl && dctrl.signal })
          .then(function (r) {
            if (!r.ok) throw new Error("HTTP " + r.status);
            var total = Number(r.headers.get("content-length")) || 0;
            if (!r.body || typeof r.body.getReader !== "function") return r.blob();
            var reader = r.body.getReader(), chunks = [], received = 0;
            function pump() { return reader.read().then(function (part) {
              if (part.done) return new Blob(chunks);
              chunks.push(part.value); received += part.value.byteLength; emitDownloadProgress(received, total);
              return pump();
            }); }
            return pump();
          })
          .then(function (blob) {
            var name = (j.path || "download").split("/").pop() || "download";
            var a = document.createElement("a");
            a.href = URL.createObjectURL(blob);
            a.download = name;
            document.body.appendChild(a);
            a.click();
            setTimeout(function () { try { URL.revokeObjectURL(a.href); a.remove(); } catch (e) {} }, 0);
            reply({ c: "fs-op-result", reqId: j.reqId, op: "download", ok: true, name: name, err: "" });
          })
          .catch(function (e) { reply({ c: "fs-op-result", reqId: j.reqId, op: "download", ok: false, err: dctrl && dctrl.signal.aborted ? "canceled" : String((e && e.message) || e) }); })
          .then(function () { delete self._downloads[downloadId]; });
        return;
      case "fs-cancel": case "fs-download-cancel":
        this._downloads = this._downloads || {};
        var cancelId = String(j.id == null ? (j.reqId == null ? "" : j.reqId) : j.id);
        if (this._downloads[cancelId] && this._downloads[cancelId].abort) this._downloads[cancelId].abort();
        if (j.reqId != null) reply({ c: "fs-op-result", reqId: j.reqId, op: "download", ok: false, err: "canceled" });
        return;
      case "fs-send-to-session":
        api("api/fs/send-to-session", { body: { path: j.path || "", sessionId: String(j.sessionId == null ? "" : j.sessionId) } }).then(function (r) {
          if (!r || r.ok === false) return failed("send-to-session", r);
          reply({ c: "fs-op-result", reqId: j.reqId, op: "send-to-session", ok: true, path: r.path || j.path, sessionId: r.sessionId || j.sessionId, err: "" });
        });
        return;
      default:
        reply({ c: "fs-op-result", reqId: j.reqId, op: j.c, ok: false, err: "enotsup" });
    }
  };

  // ---- terminal (term-*, chan4 JSON + chan6/type1 data) <-> /ws/terminal ----
  // Panel: term-open{reqId,cols,rows} -> term-opened{id,reqId}; term-resize;
  // term-close; term-exit{id,code}. Data both ways is chan6 type1 [4B id][bytes].
  LocalSocket.prototype._termWs = function () {
    if (this._termSocket && (this._termSocket.readyState === 0 || this._termSocket.readyState === 1)) return this._termSocket;
    var self = this, ws;
    try { ws = new WebSocket(wsUrl("ws/terminal")); } catch (e) { return null; }
    ws.binaryType = "arraybuffer";
    ws.onmessage = function (ev) { self._onTermMsg(ev.data); };
    this._termSocket = ws;
    return ws;
  };
  // D14: /ws/terminal now echoes reqId on opened/error; associate by reqId
  // instead of FIFO so concurrent opens cannot mis-pair.
  LocalSocket.prototype._termTakeReq = function (rid) {
    if (rid == null) return this._termPending.shift();
    var i = this._termPending.indexOf(rid);
    if (i >= 0) this._termPending.splice(i, 1);
    return rid;
  };
  LocalSocket.prototype._forwardTerminalBytes = function (payload) {
    var ws = this._termWs();
    if (!ws) return;
    var send = function () { if (ws.readyState === 1) { try { ws.send(payload); } catch (e) {} } };
    if (ws.readyState === 1) send(); else ws.addEventListener("open", send, { once: true });
  };
  LocalSocket.prototype._onTermMsg = function (data) {
    if (typeof data === "string") {
      var m = null; try { m = JSON.parse(data); } catch (e) {}
      if (!m) return;
      if (m.type === "opened") {
        var reqId = this._termTakeReq(m.reqId);
        this._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "term-opened", id: m.id, reqId: reqId }));
        return;
      }
      if (m.type === "exit" || m.type === "closed") {
        this._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "term-exit", id: m.id, code: (m.code == null ? -1 : m.code) }));
        return;
      }
      if (m.type === "ack") { this._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "term-ack", id: m.id })); return; }
      if (m.type === "error") {
        var rid = this._termTakeReq(m.reqId);
        this._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "term-opened", reqId: rid, id: -1, err: String(m.error || "failed") }));
      }
      return;
    }
    var u = new Uint8Array(data); if (u.length < 4) return;
    this._emitEnvelope(CH.TERMINAL, 1, u);   // [4B id][bytes] straight through
    var ack = this._termSocket;
    if (ack && ack.readyState === 1) {
      var tid = ((u[0] << 24) | (u[1] << 16) | (u[2] << 8) | u[3]) >>> 0;
      try { ack.send(JSON.stringify({ type: "ack", id: tid, received: u.length - 4 })); } catch (e) {}
    }
  };
  LocalSocket.prototype._onTerm = function (j) {
    var self = this;
    if (j.c === "term-input") {
      var raw = typeof j.data === "string" ? lx.encode(j.data) : new Uint8Array(j.data || 0);
      var frame = new Uint8Array(4 + raw.length), id = Number(j.id) >>> 0;
      frame[0] = id >>> 24; frame[1] = id >>> 16; frame[2] = id >>> 8; frame[3] = id;
      frame.set(raw, 4); this._forwardTerminalBytes(frame);
      return;
    }
    var ws = this._termWs();
    if (!ws) {
      this._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "term-opened", reqId: j.reqId, id: -1, err: "unavailable" }));
      return;
    }
    var send = function (obj) {
      if (ws.readyState === 1) { try { ws.send(JSON.stringify(obj)); } catch (e) {} }
      else ws.addEventListener("open", function () { try { ws.send(JSON.stringify(obj)); } catch (e) {} }, { once: true });
    };
    if (j.c === "term-open") { this._termPending.push(j.reqId); send({ type: "open", cols: Number(j.cols) || 80, rows: Number(j.rows) || 24, reqId: j.reqId }); return; }
    if (j.c === "term-resize") { send({ type: "resize", id: j.id, cols: Number(j.cols) || 80, rows: Number(j.rows) || 24 }); return; }
    if (j.c === "term-close") { send({ type: "close", id: j.id }); return; }
  };

  // ---- notifications (notif-*, chan4 JSON) <-> /api/notifications ----------
  LocalSocket.prototype._onNotif = function (j) {
    var self = this;
    if (j.c === "notif-subscribe") {
      this._notifOn = j.on === true;
      if (this._notifTimer) { clearInterval(this._notifTimer); this._notifTimer = null; }
      if (this._notifSocket) { try { this._notifSocket.close(); } catch (e) {} this._notifSocket = null; }
      if (!this._notifOn) {
        this._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "notif-state", reqId: j.reqId, on: false }));
        return;
      }
      function openPush() {
        var sock;
        try { sock = new WebSocket(wsUrl("ws/notifications")); } catch (e) { return false; }
        self._notifSocket = sock;
        sock.onmessage = function (ev) {
          var msg = null; try { msg = JSON.parse(ev.data); } catch (e) {}
          if (msg && (msg.type === "notif-snapshot" || msg.active)) self._notifEmit(msg);
        };
        sock.onopen = function () {
          self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "notif-state", reqId: j.reqId, on: true }));
        };
        sock.onclose = function () { if (self._notifSocket === sock) self._notifSocket = null; };
        sock.onerror = function () {};
        return true;
      }
      api("api/notifications").then(function (r) {
        var ok = !!(r && r.ok !== false);
        self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "notif-state", reqId: j.reqId, on: ok, err: ok ? undefined : "unavailable" }));
        if (!ok) return;
        self._notifEmit(r);
        if (!openPush()) self._notifTimer = setInterval(function () {
          api("api/notifications").then(function (rr) { if (rr && rr.ok !== false) self._notifEmit(rr); });
        }, 3000);
      });
      return;
    }
    if (j.c === "notif-icon") {
      var keys = Array.isArray(j.keys) ? j.keys.slice(0, 16) : [];
      Promise.all(keys.map(function (k) {
        return fetch(httpUrl("api/notifications/icon?key=" + encodeURIComponent(k)))
          .then(function (r) { return r.ok ? r.arrayBuffer() : null; })
          .then(function (b) { return b && b.byteLength ? { id: String(k), base64: bytesToBase64(new Uint8Array(b)) } : null; })
          .catch(function () { return null; });
      })).then(function (icons) {
        self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "notif-icon-result", reqId: j.reqId, icons: icons.filter(Boolean) }));
      });
      return;
    }
    if (j.c === "notif-cmd") {
      // Panel command codes (shell.js): 1=refresh 2=dismiss 3=snooze
      // 5/6=open 7=action-click 8=reply.
      var key = j.key, cmd = Number(j.command);
      function reply(ok, err) {
        self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "notif-cmd-result", reqId: j.reqId, ok: !!ok, err: err, command: j.command, key: key }));
      }
      if (cmd === 1) {
        api("api/notifications").then(function (r) {
          var ok = !!(r && r.ok !== false);
          if (ok) self._notifEmit(r);
          reply(ok, ok ? undefined : "unavailable");
        });
        return;
      }
      if (cmd === 2) {
        api("api/notifications", { body: key ? { action: "dismiss", key: key } : { action: "dismissAll" } }).then(function (r) {
          reply(!!(r && r.ok !== false));
        });
        return;
      }
      if (cmd === 5 || cmd === 6 || cmd === 7 || cmd === 8) {
        var action = (cmd === 5 || cmd === 6) ? "open" : (cmd === 7 ? "action" : "reply");
        var payload = { action: action, key: key, sessionId: j.sessionId || this._windowId || "" };
        if (cmd === 7 || cmd === 8) payload.actionIndex = Number(j.actionIndex == null ? (j.index == null ? -1 : j.index) : j.actionIndex);
        if (cmd === 8) payload.text = String(j.text == null ? (j.reply == null ? "" : j.reply) : j.text);
        api("api/notifications", { body: payload }).then(function (r) { reply(!!(r && r.ok !== false), r && r.ok === false ? (r.error || "unavailable") : undefined); });
        return;
      }
      reply(false, "unsupported");
      return;
    }
  };
  LocalSocket.prototype._notifEmit = function (r) {
    function list(x) { return Array.isArray(x) ? x : []; }
    // Panel record mapper mM() rejects a record unless title/text/subText are
    // strings and postTime/updateTime/removedAt/removalReason are safe ints.
    function map(n) {
      n = n || {};
      return {
        key: String(n.key || n.package || ("n" + Math.random().toString(16).slice(2))),
        packageName: String(n.package || n.packageName || ""), userId: Number(n.userId) || 0,
        title: String(n.title == null ? "" : n.title),
        text: String(n.text == null ? "" : n.text),
        subText: String(n.subText == null ? "" : n.subText),
        postTime: Number(n.postTime != null ? n.postTime : n.time) || 0,
        updateTime: Number(n.updateTime != null ? n.updateTime : (n.time || Date.now())),
        removedAt: Number(n.removedAt) || 0,
        removalReason: Number(n.removalReason) || 0,
        importance: n.importance == null ? null : Number(n.importance),
        ongoing: !!n.ongoing, clearable: n.clearable !== false, systemHidden: !!n.systemHidden,
        contentIntentTargetKind: n.contentIntentTargetKind || "unknown",
        actions: Array.isArray(n.actions) ? n.actions.map(function (a, i) {
          return { index: Number(a.index == null ? i : a.index), title: String(a.title || a.label || ""),
            semanticAction: String(a.semanticAction || ""), hasRemoteInput: !!a.hasRemoteInput,
            intentTargetKind: String(a.intentTargetKind || "unknown"), iconId: String(a.iconId || "") };
        }) : []
      };
    }
    // Panel ingestSnapshot() feeds each section straight into wf(), which
    // requires a bare array -> active/hidden/recent must NOT be {records:[...]}.
    this._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({
      c: "notif-snapshot", generatedAt: Number(r.generatedAt) || Date.now(), truncated: false,
      active: list(r.active).map(map),
      hidden: [],
      recent: list(r.recent).map(map)
    }));
  };

  // chan5 upload: begin(1){id,name,size,mime,destPath?} / chunk(2)[4B id][bytes]
  // / end(3){id} / cancel(4){id} -> POST /api/fs/upload?path=&name=
  LocalSocket.prototype._onChan5 = function (type, payload) {
    this._uploads = this._uploads || {};
    this._downloads = this._downloads || {};
    // Device -> browser file receiver (original protocol): begin=6,
    // chunk=7, end=8, cancel=9; the browser acknowledges each chunk with
    // type 10. This is separate from the browser -> device upload quartet
    // (1..4) handled below.
    if (type === 6) {
      var begin = jsonPayload(payload);
      if (begin && begin.id != null && Number(begin.size) >= 0) {
        this._downloads[begin.id] = { id: begin.id, name: begin.name || "download.bin", mime: begin.mime || "application/octet-stream", size: Number(begin.size), received: 0, chunks: [] };
        this._emitEnvelope(CH.SESSION, 10, lx.encode(JSON.stringify({ id: begin.id, received: 0 })));
      }
      return;
    }
    if (type === 7) {
      if (payload.length < 4) return;
      var did = ((payload[0] << 24) | (payload[1] << 16) | (payload[2] << 8) | payload[3]) >>> 0;
      var down = this._downloads[did];
      if (!down) return;
      var part = payload.subarray(4);
      down.received += part.length;
      if (down.received > down.size) { delete this._downloads[did]; return; }
      down.chunks.push(part.slice());
      this._emitEnvelope(CH.SESSION, 10, lx.encode(JSON.stringify({ id: did, received: down.received })));
      this._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "fs-progress", id: did, op: "download", received: down.received, total: down.size }));
      return;
    }
    if (type === 8) {
      var end = jsonPayload(payload), finished = end && this._downloads[end.id];
      if (!finished) return;
      delete this._downloads[end.id];
      if (finished.received !== finished.size) return;
      var blob = new Blob(finished.chunks, { type: finished.mime });
      var link = document.createElement("a"); link.href = URL.createObjectURL(blob); link.download = finished.name;
      document.body.appendChild(link); link.click();
      setTimeout(function () { try { URL.revokeObjectURL(link.href); link.remove(); } catch (e) {} }, 60000);
      this._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "fs-op-result", op: "download", id: end.id, ok: true, name: finished.name, err: "" }));
      return;
    }
    if (type === 9) {
      var abort = jsonPayload(payload); if (abort) delete this._downloads[abort.id];
      return;
    }
    if (type === 1) {
      var b = jsonPayload(payload);
      if (b && b.id != null) this._uploads[b.id] = { id: b.id, name: b.name || "upload.bin", destPath: b.destPath || null, chunks: [], controller: typeof AbortController === "function" ? new AbortController() : null };
      return;
    }
    if (type === 2) {
      if (payload.length < 4) return;
      var id = ((payload[0] << 24) | (payload[1] << 16) | (payload[2] << 8) | payload[3]) >>> 0;
      var up = this._uploads[id];
      if (up) up.chunks.push(payload.subarray(4));
      return;
    }
    if (type === 3) {
      var e = jsonPayload(payload); var cur = e && this._uploads[e.id];
      if (!cur) return;
      delete this._uploads[e.id];
      var self = this;
      var dir = cur.destPath || "/storage/emulated/0/Download";
      var blob = new Blob(cur.chunks);
      // Original wire protocol (RunnableC2905r.java:546/599/604): success ->
      // {c:"upload-done",id,name,imported}; failure -> {c:"upload-failed",id,reason}.
      // Never reuse the success回执 on failure: the panel's Bd.onUploadDone() ignores
      // any ok flag and would resolve the transfer as a success.
      function done(ok, reason) {
        if (ok) {
          self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "upload-done", id: cur.id, name: cur.name, imported: false }));
        } else {
          self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "upload-failed", id: cur.id, reason: String(reason || "io") }));
          // The stock panel ships no upload-failed handler, so also surface the
          // failure on the handled `toast` channel; otherwise it would be silent.
          self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "toast", text: "上传失败：" + cur.name }));
        }
      }
      fetch(httpUrl("api/fs/upload?path=" + encodeURIComponent(dir) + "&name=" + encodeURIComponent(cur.name)), { method: "POST", body: blob, signal: cur.controller && cur.controller.signal })
        .then(function (r) { return r.json().catch(function () { return { ok: r.ok }; }); })
        .then(function (res) { done(res && res.ok !== false, (res && res.error) || ""); })
        .catch(function (err) { done(false, String((err && err.message) || err)); });
      return;
    }
    if (type === 4) {
      var c = jsonPayload(payload); if (c) { var upCancel = this._uploads[c.id]; if (upCancel && upCancel.controller) upCancel.controller.abort(); delete this._uploads[c.id]; }
      return;
    }
    // type 5 remains reserved for the upload receiver; terminal bytes use
    // CH.TERMINAL/type 1 and are handled before this upload switch.
  };

  LocalSocket.prototype._sendIcons = function (items) {
    var self = this;
    var batch = items.slice(0, 16);
    Promise.all(batch.map(function (it) {
      return fetch(httpUrl("api/apps/icon?package=" + encodeURIComponent(it.packageName) + "&size=128"))
        .then(function (r) { return r.ok ? r.arrayBuffer() : null; })
        .then(function (buf) {
          if (!buf) return null;
          var b = ""; var u = new Uint8Array(buf);
          for (var i = 0; i < u.length; i++) b += String.fromCharCode(u[i]);
          return { packageName: it.packageName, userId: it.userId || 0, pngBase64: btoa(b) };
        }).catch(function () { return null; });
    })).then(function (icons) {
      var ok = icons.filter(Boolean);
      // The request carries `items`; the reply the panel consumes is
      // `{c:"app-icons", icons:[...]}` (shell dispatch: i.c===ou?fP(i.icons)).
      // Replying with `items` leaves every app icon blank.
      if (ok.length) self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "app-icons", icons: ok }));
    });
  };

  LocalSocket.prototype._onInput = function (j) {
    var link = this.session.link, wid = this._windowId;
    // Backend /ws/control takes normalized 0..1; the panel sends canvas pixels.
    var d = this.session.sourceSize(wid);
    function nx(v) { return d ? clamp01((Number(v) || 0) / d.w) : clamp01(Number(v) || 0); }
    function ny(v) { return d ? clamp01((Number(v) || 0) / d.h) : clamp01(Number(v) || 0); }
    if (!d) log("input before source size known for wid", wid);
    if (j.k === "pointer" || j.k === "touch") {
      var t = { down: "down", move: "move", up: "up", cancel: "cancel" }[j.a] || "move";
      link.ctl({ type: "pointer", action: t, slot: Number(j.slot) || 0, x: nx(j.x), y: ny(j.y), wid: wid });
      return;
    }
    if (j.k === "pinch") {
      var focus = j.focus || { x: Number(j.x) || 0, y: Number(j.y) || 0 };
      var fw = d ? d.w : 1, fh = d ? d.h : 1;
      link.ctl({ type: "pinch", action: j.a || "change", focus: { x: clamp01(Number(focus.x) / fw), y: clamp01(Number(focus.y) / fh) }, scale: Number(j.scale) || 1, rotation: Number(j.rotation) || 0, wid: wid });
      return;
    }
    if (j.k === "key") {
      // Preserve the original key lifecycle.  A missing action keeps the
      // legacy atomic press behavior used by older panel builds.
      link.ctl({ type: "key", action: j.a || "press", keycode: j.code, meta: Number(j.meta) || 0, wid: wid });
      return;
    }
    if (j.k === "text") { link.ctl({ type: "text", text: j.text, wid: wid }); return; }
    if (j.k === "scroll") {
      // Keep both axes.  The original wheel path uses dx/dy; collapsing dx
      // into a vertical drag made horizontal lists impossible to operate.
      var x0 = Number(j.x) || 0, y0 = Number(j.y) || 0;
      var x1 = x0 - (Number(j.dx) || 0), y1 = y0 - (Number(j.dy) || 0);
      link.ctl({ type: "pointer", action: "down", slot: 0, x: nx(x0), y: ny(y0), wid: wid });
      link.ctl({ type: "pointer", action: "move", slot: 0, x: nx(x1), y: ny(y1), wid: wid });
      link.ctl({ type: "pointer", action: "up", slot: 0, x: nx(x1), y: ny(y1), wid: wid });
      return;
    }
    log("unhandled input", j.k);
  };

  LocalSocket.prototype._onWidget = function (j) {
    if (!j || !j.c) return;
    var link = this.session.link;
    if (!link.widgets) {
      link.widgets = new WebSocket(wsUrl("ws/widgets"));
      var self = this;
      link.widgets.binaryType = "arraybuffer";
      link.widgets.onmessage = function (ev) {
        if (typeof ev.data === "string") {
          var m = null; try { m = JSON.parse(ev.data); } catch (e) {}
          if (m && m.type === "widget-state") {
            self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "widget-state", widgets: m.widgets || [] }));
          }
          if (m && m.type === "widget-open") {
            self._emitEnvelope(CH.CONTROL, CT.JSON, JSON.stringify({ c: "widget-open", token: m.token, pkg: m.pkg, userId: m.userId || 0 }));
          }
          return;
        }
        var u = new Uint8Array(ev.data); if (u.length < 4) return;
        self._emitEnvelope(CH.WIDGET, 1, u);
      };
    }
    var send = function () {
      try { link.widgets.send(JSON.stringify({ type: j.c, id: j.id, w: j.w, h: j.h, x: j.x, y: j.y, a: j.a, t: j.t, k: j.k, dx: j.dx, dy: j.dy, density: j.density, widgets: j.widgets })); } catch (e) {}
    };
    if (link.widgets.readyState === 1) send();
    else link.widgets.addEventListener("open", send, { once: true });
  };

  LocalSocket.prototype._onMediaLease = function (msg) {
    if (msg.op === "open" && msg.frame) {
      this._pendingLease = msg;
      this._deliver(JSON.stringify({ t: "rtc.media", op: "opened", label: msg.label, epoch: msg.epoch, frame: msg.frame }));
    } else if (msg.op === "close") {
      this._deliver(JSON.stringify({ t: "rtc.media", op: "closed", label: msg.label, epoch: msg.epoch }));
    }
  };

  // ---- session (plugged into shell.js wP) ---------------------------------
  function Session(phase) {
    this.phase = phase || "bootstrap";
    this.closed = false;
    this.link = new Link();
    this._sockets = [];
    this._media = {};      // sessionId(string) -> attached media LocalSocket
    this._dims = {};       // sessionId(string) -> { w, h } source size
    this._ws = null;
    this._disconnect = null;
    // shell.js WE() extracts `e.socketFactory` and Hl() calls it standalone, so
    // expose a bound instance function (a prototype method would lose `this`).
    var self = this;
    this.socketFactory = function (url) { return self._channel(url); };
  }
  Session.prototype.setDisconnectHandler = function (fn) { this._disconnect = fn; };
  Session.prototype.prepare = function () { return Promise.resolve(this); };
  Session.prototype.registerMedia = function (id, sock) { this._media[String(id)] = sock; };
  Session.prototype.unregisterMedia = function (id, sock) {
    if (this._media[String(id)] === sock) delete this._media[String(id)];
  };
  Session.prototype.mediaFor = function (wid) { return this._media[String(wid)] || null; };
  Session.prototype.setSize = function (wid, w, h) {
    if (w > 0 && h > 0) this._dims[String(wid)] = { w: w, h: h };
  };
  Session.prototype.sourceSize = function (wid) {
    var d = this._dims[String(wid)];
    if (d && d.w && d.h) return d;
    // wid 0 = whole desktop source; fall back to the desktop geometry.
    if ((Number(wid) || 0) === 0 && desktopInfo && desktopInfo.width && desktopInfo.height) {
      return { w: desktopInfo.width, h: desktopInfo.height };
    }
    return null;
  };
  Session.prototype._channel = function (url) {
    var path = new URL(url, location.href).pathname;
    if (path === "/media") {
      // One media socket per window session; it binds its window id on attach.
      var s = new LocalSocket("media", this);
      this._sockets.push(s);
      return s;
    }
    if (path !== "/ws") throw new DOMException("no local data channel for " + path, "NotSupportedError");
    if (!this._ws || this._ws.readyState === 3) { this._ws = new LocalSocket("ws", this); this._sockets.push(this._ws); }
    return this._ws;
  };
  Session.prototype.close = function () {
    this.closed = true;
    try { this.link.close(); } catch (e) {}
    this._sockets.forEach(function (s) { try { s.close(); } catch (e) {} });
    this._sockets = []; this._media = {};
  };

  function createSession(phase) { return new Session(phase); }

  // ---- MSE decoder factory (patch target for shell.js R0) -----------------
  // Mirrors R0's interface: {onConfig,onFrame,markStreamGap,reset,stats,destroy}.
  // Third argument is the per-frame presented callback (R0 calls it as n(canvas)
  // after each drawImage); stats.framesDecoded follows the player's presented count.
  function createDecoder(canvas, onNeedKeyframe, onPresented) {
    var useWc = typeof VideoDecoder !== "undefined" && window.isSecureContext !== false;
    if (useWc || !window.H264Player) return null;   // let the original R0 handle it
    var stats = { framesReceived: 0, configsReceived: 0, framesDecoded: 0, framesDropped: 0, streamGaps: 0, queue: -1 };
    var ctx = canvas.getContext("2d");
    var video = document.createElement("video");
    video.muted = true; video.playsInline = true; video.autoplay = true;
    video.style.cssText = "position:absolute;left:0;top:0;width:0;height:0;opacity:0;pointer-events:none";
    (canvas.parentNode || document.body).appendChild(video);
    var player = window.H264Player.create({
      container: video, backend: "mse",
      onNeedKeyframe: function () { try { onNeedKeyframe && onNeedKeyframe(); } catch (e) {} }
    });
    video = player.element;
    var raf = null;
    function draw() {
      raf = requestAnimationFrame(draw);
      if (video.videoWidth && (canvas.width !== video.videoWidth || canvas.height !== video.videoHeight)) {
        canvas.width = video.videoWidth; canvas.height = video.videoHeight;
      }
      if (video.readyState >= 2) {
        try {
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
          try { onPresented && onPresented(canvas); } catch (e) {}
        } catch (e) {}
      }
    }
    raf = requestAnimationFrame(draw);
    return {
      onConfig: function (cfg) { stats.configsReceived++; player.reset(); return cfg; },
      onFrame: function (frame) {
        stats.framesReceived++;
        if (frame.length < 18) return;
        var dv = new DataView(frame.buffer, frame.byteOffset, frame.byteLength);
        var pts = Number(dv.getBigUint64(8));
        var key = (frame[16] & 1) !== 0;
        player.pushAnnexB(frame.subarray(17), pts, key);
      },
      markStreamGap: function () { stats.streamGaps++; player.requestKeyframe(); },
      reset: function () { player.reset(); },
      stats: function () {
        var s = (player.stats && player.stats()) || {};
        return {
          framesReceived: stats.framesReceived,
          configsReceived: stats.configsReceived,
          framesDecoded: (s.presented != null ? s.presented : stats.framesDecoded),
          framesDropped: stats.framesDropped,
          streamGaps: stats.streamGaps,
          queue: -1
        };
      },
      destroy: function () { if (raf) cancelAnimationFrame(raf); try { player.close(); } catch (e) {} try { video.remove(); } catch (e) {} }
    };
  }

  // AudioBufferSourceNode works on LAN HTTP as well as extension origins.
  // Bound queued audio to 200ms; keep live sound rather than replaying backlog.
  function createPcmPlayer() {
    var ctx = null, config = null, next = 0, sources = new Set();
    function resume() { if (ctx && ctx.state === "suspended") ctx.resume().catch(function () {}); }
    function clear() {
      sources.forEach(function (s) { try { s.stop(); } catch (e) {} });
      sources.clear(); next = 0;
    }
    function destroy() {
      clear();
      if (ctx) { ctx.close().catch(function () {}); ctx = null; }
      config = null;
      document.removeEventListener("pointerdown", resume, true);
      document.removeEventListener("keydown", resume, true);
    }
    return {
      onConfig: function (c) {
        if (!c || c.sampleRate < 8000 || c.sampleRate > 96000 || (c.channels !== 1 && c.channels !== 2)) return;
        if (!ctx || !config || config.sampleRate !== c.sampleRate || config.channels !== c.channels) {
          destroy();
          var Context = window.AudioContext || window.webkitAudioContext;
          if (!Context) return;
          ctx = new Context({ sampleRate: c.sampleRate, latencyHint: "interactive" });
          config = c;
          document.addEventListener("pointerdown", resume, true);
          document.addEventListener("keydown", resume, true);
        }
        resume();
      },
      onPcm: function (frame) {
        if (!ctx || !config || ctx.state !== "running" || frame.length <= 8) return;
        var view = new DataView(frame.buffer, frame.byteOffset + 8, frame.byteLength - 8);
        var count = Math.floor(view.byteLength / (config.channels * 2));
        if (!count) return;
        if (next > ctx.currentTime + 0.2) clear();
        var buffer = ctx.createBuffer(config.channels, count, config.sampleRate);
        for (var ch = 0; ch < config.channels; ch++) {
          var samples = buffer.getChannelData(ch);
          for (var n = 0; n < count; n++) samples[n] = view.getInt16((n * config.channels + ch) * 2, true) / 32768;
        }
        var source = ctx.createBufferSource();
        source.buffer = buffer; source.connect(ctx.destination);
        sources.add(source); source.onended = function () { sources.delete(source); };
        next = Math.max(next, ctx.currentTime + 0.04);
        source.start(next); next += count / config.sampleRate;
      },
      destroy: destroy
    };
  }

  // ---- 设备侧虚拟桌面（VDM）会话入口 --------------------------------------
  // 面板的「桌面」形态在设备侧对应一个整屏虚拟桌面会话（`/api/desktop`）。它不跨
  // App 进程重启保留，面板里又没有别的入口能把它拉起来 —— 用户只会一直停在
  // 「桌面未运行」，在桌面上点什么都开不出来。这里补上这个入口：右下角常驻小控件
  // 可手动开关；控制台停在桌面形态时再自动拉起一次。
  // 只动 `/api/desktop`：逐窗链路（`/api/desktop/windows`）自建虚拟屏，不需要这个
  // 会话，一律不碰，避免窗口被会话起停牵连。
  var desktopRunning = false;
  var desktopBusy = false;
  var desktopPill = null;
  var desktopMountRetry = false;

  /** 逐窗页面（/window/index.html）也加载本适配器，但那里不该出现桌面控件。 */
  function isConsolePage() {
    try { return location.pathname.indexOf("/window/") < 0; } catch (e) { return false; }
  }

  /** 桌面钮挂到任务栏品牌徽标右侧。之前用 fixed 浮在右下角，正好压住托盘里的通知气泡。 */
  function desktopMount() {
    if (!desktopPill) return false;
    if (desktopPill.parentNode) return true;
    var host = null;
    try { host = document.querySelector(".tb-left"); } catch (e) { host = null; }
    if (!host || typeof host.appendChild !== "function") return false;
    host.appendChild(desktopPill);
    return true;
  }

  function desktopRender() {
    if (!isConsolePage()) return;
    try {
      var d = (typeof document === "undefined") ? null : document;
      if (!d || typeof d.getElementById !== "function" || typeof d.createElement !== "function" || !d.body) return;
      if (!desktopPill) {
        desktopPill = d.createElement("button");
        desktopPill.id = "blindcastDesktopPill";
        desktopPill.className = "tb-desktop";
        desktopPill.type = "button";
        if (typeof desktopPill.addEventListener === "function") {
          desktopPill.addEventListener("click", function () { desktopToggle(); });
        }
      }
      if (!desktopMount()) {
        // 任务栏还没解析出来。浮回右下角会重新压住通知气泡，所以先不显示，稍后再挂一次；
        // 后续每次状态刷新也会走到这里。
        if (!desktopMountRetry) {
          desktopMountRetry = true;
          setTimeout(function () { desktopMountRetry = false; desktopRender(); }, 200);
        }
        return;
      }
      var text = desktopBusy ? "桌面：切换中…"
        : (desktopRunning ? "桌面：运行中 · 点此关闭" : "桌面：未运行 · 点此启动");
      if (desktopPill.setAttribute) {
        desktopPill.setAttribute("data-state", desktopBusy ? "busy" : (desktopRunning ? "running" : "idle"));
      }
      if (desktopPill.innerHTML !== undefined) desktopPill.innerHTML = "";
      if (typeof desktopPill.appendChild === "function") {
        var dot = d.createElement("span");
        dot.className = "tb-desktop-dot";
        var label = d.createElement("span");
        label.textContent = text;
        desktopPill.appendChild(dot);
        desktopPill.appendChild(label);
      } else {
        desktopPill.textContent = text;
      }
      if (desktopPill.setAttribute) {
        desktopPill.setAttribute("title", "设备侧虚拟桌面：一块独立于物理屏的手机屏会话");
      }
    } catch (e) { log("desktop pill failed", String((e && e.message) || e)); }
  }

  function desktopRefresh() {
    return api("api/desktop").then(function (r) {
      desktopRunning = !!(r && r.ok !== false && r.running);
      desktopRender();
      return desktopRunning;
    }, function () { desktopRender(); return desktopRunning; });
  }

  function desktopSet(on) {
    if (desktopBusy) return Promise.resolve(desktopRunning);
    desktopBusy = true; desktopRender();
    var action = on ? "on" : "off";
    return api("api/desktop", { body: { action: action } }).then(function (r) {
      desktopBusy = false;
      if (r && r.ok === false) log("desktop " + action + " failed:", r.error);
      desktopRunning = !!(r && r.ok !== false && r.running);
      desktopRender();
      return desktopRunning;
    }, function (e) {
      desktopBusy = false; desktopRender();
      log("desktop " + action + " threw", String((e && e.message) || e));
      return desktopRunning;
    });
  }

  /** 先读真状态再翻面：宿主可能已自行退出，凭本地缓存翻面会翻反。 */
  function desktopToggle() {
    if (desktopBusy) return Promise.resolve(desktopRunning);
    return desktopRefresh().then(function (running) { return desktopSet(!running); });
  }

  /** 桌面形态下确保会话在跑（已在跑则只读一次状态）。 */
  function desktopEnsure() {
    if (desktopBusy) return Promise.resolve(desktopRunning);
    return desktopRefresh().then(function (running) {
      return running ? true : desktopSet(true);
    });
  }

  /** 控制台停在桌面形态才自动拉起；融合（hub）形态不动设备状态。 */
  function desktopAutostart() {
    var mode = "";
    try { mode = new URL(location.href).searchParams.get("workspace") || ""; } catch (e) {}
    if (mode !== "desktop") {
      try {
        var explicit = localStorage.getItem("blindcast-workspace-explicit");
        var stored = localStorage.getItem("blindcast-workspace-mode");
        if (explicit === "desktop" && stored === "desktop") mode = "desktop";
      } catch (e) {}
    }
    if (mode !== "desktop") return Promise.resolve(false);
    return desktopEnsure();
  }

  try {
    desktopRender();
    desktopRefresh().then(function () { desktopAutostart(); });
    if (typeof document !== "undefined" && document.addEventListener) {
      document.addEventListener("visibilitychange", function () {
        if (!document.hidden) desktopRefresh();
      });
    }
  } catch (e) { log("desktop bootstrap failed", String((e && e.message) || e)); }

  window.__blindcastLocal = {
    enabled: true,
    origin: ORIGIN,
    caps: CAPS.slice(),
    get token() { return TOKEN; },
    auth: {
      get token() { return TOKEN; },
      hasToken: function () { return !!TOKEN; },
      status: authStatus,
      verify: tokenOk,
      pair: pair
    },
    createSession: createSession,
    createDecoder: createDecoder,
    createPcmPlayer: createPcmPlayer,
    // The shell picks the workspace mode at module top level (patched line:
    // `var tl=Hi()?(qu()||__blindcastLocal.workspaceMode()||await Xl()):"desktop"`).
    // qu() reads localStorage["andromeld-workspace-mode"]; Xl() opens the blocking
    // workspace-mode dialog and awaits a click -- in local mode that click never
    // comes, so the module (and the whole panel) would hang. Return the stored
    // choice, else a `?workspace=` override, else the local default.
    // Fusion is the product's primary entry.  Keep an explicit desktop query
    // and stored choice as opt-in compatibility paths for existing users.
    workspaceMode: function () {
      try {
        var requested = new URL(location.href).searchParams.get("workspace");
        if (requested === "desktop" || requested === "fusion") return requested;
        var m = localStorage.getItem("blindcast-workspace-mode");
        var explicit = localStorage.getItem("blindcast-workspace-explicit");
        // Older builds stored Desktop by default.  Treat that stale value as
        // unset; only a choice made through the current mode picker may opt
        // back into Desktop.  This repairs existing Chrome profiles while
        // preserving the original mode switch for future choices.
        if (explicit === "desktop" && m === "desktop") return "desktop";
        if (explicit === "fusion" || m === "fusion" || localStorage.getItem("andromeld-workspace-mode") === "fusion") return "fusion";
      } catch (e) {}
      return "fusion";
    },
    setWorkspaceMode: function (mode) {
      if (mode !== "desktop" && mode !== "fusion") return;
      try {
        localStorage.setItem("blindcast-workspace-mode", mode);
        localStorage.setItem("blindcast-workspace-explicit", mode);
      } catch (e) {}
    },
    api: api,
    log: log,
    // 设备侧虚拟桌面会话（见上方 desktop* 说明）。测试与 shell 都可以直接调用。
    desktop: {
      status: desktopRefresh,
      ensure: desktopEnsure,
      toggle: desktopToggle,
      on: function () { return desktopSet(true); },
      off: function () { return desktopSet(false); }
    }
  };
  log("adapter ready", ORIGIN, TOKEN ? "(token)" : "(no token)");
})();

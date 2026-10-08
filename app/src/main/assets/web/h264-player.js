/* =============================================================================
 * h264-player.js · BlindCast 低延迟 H.264 播放器（可嵌入每窗口）
 * =============================================================================
 *
 * 目标：在**普通局域网 HTTP（非安全上下文）**里也能走 H.264 硬解，摆脱
 *      「无 WebCodecs → 只能 JPEG 2~3fps」的降级。
 *
 * 双后端，同一接口：
 *   1) webcodecs —— 安全上下文（https / localhost / 127.0.0.1）且有 VideoDecoder：
 *      VideoDecoder → VideoFrame → canvas（rAF 合并，每 vsync 一画）。
 *   2) mse       —— 非安全上下文（裸 http://192.168.x.x:8888）唯一真实等价方案：
 *      客户端 fMP4 封装 → MediaSource SourceBuffer → HTMLVideoElement。
 *      MSE 不受安全上下文限制，普通 http 可用（WebCodecs 才受限）。
 *
 * 无任何第三方联网依赖；全部自包含。
 *
 * ---------------------------------------------------------------------------
 * 接口契约（v1，已同步主实施代理）
 * ---------------------------------------------------------------------------
 *   const p = H264Player.create({
 *     container,              // HTMLElement：模块往里塞 <video>(mse) 或 <canvas>(webcodecs)
 *     backend: 'auto',        // 'auto' | 'webcodecs' | 'mse'
 *     maxLatencyMs: 300,      // 目标贴边延迟（MSE 会往 live edge 追）
 *     maxBufferFrames: 90,    // 待解队列上界（帧）——与字节双约束
 *     maxBufferBytes: 4<<20,  // 待解队列上界（字节）——与帧数双约束
 *     maxBufferSec: 6,        // MSE 已缓冲窗口上界（秒）
 *     minBufferSec: 1,        // MSE 回看保留（秒），trim 下限
 *     onFrame, onFirstFrame, onResize(w,h), onCodec(codecStr),
 *     onNeedKeyframe, onError(err), onStats(s), onBackend(name)
 *   });
 *
 *   p.pushAnnexB(payload, pts, keyframe)   // → true 已接收 / false 被丢
 *       payload  : Uint8Array，H.264 Annex-B（[SPS][PPS][IDR…] 或单个 P 帧），
 *                  与 /ws/stream 0x01 / 0x11 负载一致
 *       pts      : 数字，**单位微秒**，单调递增（同一时钟即可）
 *                  null/undefined/NaN → 模块自行用单调本地时钟 performance.now()*1000
 *       keyframe : 可选布尔提示；缺省由模块扫 NAL type 5 判定
 *
 *   p.reset()            // 换源/重建：清空全部状态，重新等 IDR（resize 后调用）
 *   p.close()            // 销毁：移除 DOM，释放 MSE / 解码器
 *   p.stats()            // {backend,codec,width,height,presented,decoded,fps,
 *                        //  latencyMs,droppedFrames,queueFrames,queueBytes,
 *                        //  bufferedMs,bufferedBytes,needKey,state}
 *   p.requestKeyframe()  // 主动向上游要 IDR（触发 onNeedKeyframe）
 *   p.element / p.backend / p.width / p.height / p.codec
 *
 * ---------------------------------------------------------------------------
 * 拥塞语义（关键，勿改坏）
 * ---------------------------------------------------------------------------
 * 待解队列**同时**受帧数与字节约束。一旦超限：
 *   **不**删旧 delta 硬解（那会毁掉预测链，画面撕裂/花屏且再也回不来），
 *   而是丢弃整个待解队列、置 needKey=true、等待并请求下一个 IDR，
 *   经 onNeedKeyframe 回调通知上游补发关键帧。
 * MSE 与 WebCodecs 两条后端同一语义。
 * MSE 已缓冲窗口另有秒数 + 字节上界（trim 旧数据，只删已播过的，安全）。
 * ========================================================================== */
(function (global) {
  "use strict";

  var TS = 90000; // fMP4 媒体时基（90kHz，视频惯例）

  // ---------------------------------------------------------------------------
  // 小工具：字节拼装 / box 构造
  // ---------------------------------------------------------------------------
  function u8(n) { return new Uint8Array([n & 0xff]); }
  function u16(n) { var b = new Uint8Array(2); b[0] = (n >>> 8) & 0xff; b[1] = n & 0xff; return b; }
  function u32(n) { var b = new Uint8Array(4); b[0] = (n >>> 24) & 0xff; b[1] = (n >>> 16) & 0xff; b[2] = (n >>> 8) & 0xff; b[3] = n & 0xff; return b; }
  function i32(n) { return u32(n < 0 ? n + 4294967296 : n); }
  function u64(n) { return cat(u32(Math.floor(n / 4294967296)), u32(n >>> 0)); }
  function fourcc(s) { return new Uint8Array([s.charCodeAt(0), s.charCodeAt(1), s.charCodeAt(2), s.charCodeAt(3)]); }
  function cat() {
    var total = 0, i;
    for (i = 0; i < arguments.length; i++) total += arguments[i].length;
    var out = new Uint8Array(total), o = 0;
    for (i = 0; i < arguments.length; i++) { out.set(arguments[i], o); o += arguments[i].length; }
    return out;
  }
  function box(type, parts) {
    parts = parts || [];
    var body = cat.apply(null, parts);
    var out = new Uint8Array(8 + body.length);
    out[0] = (out.length >>> 24) & 0xff; out[1] = (out.length >>> 16) & 0xff;
    out[2] = (out.length >>> 8) & 0xff; out[3] = out.length & 0xff;
    out[4] = type.charCodeAt(0); out[5] = type.charCodeAt(1);
    out[6] = type.charCodeAt(2); out[7] = type.charCodeAt(3);
    out.set(body, 8);
    return out;
  }
  function fullbox(type, version, flags, parts) {
    var hdr = new Uint8Array(4);
    hdr[0] = version; hdr[1] = (flags >>> 16) & 0xff; hdr[2] = (flags >>> 8) & 0xff; hdr[3] = flags & 0xff;
    return box(type, [hdr].concat(parts || []));
  }
  function zeros(n) { return new Uint8Array(n); }

  // ---------------------------------------------------------------------------
  // Annex-B 解析
  // ---------------------------------------------------------------------------
  /** 拆 NAL 单元。返回 [{hdr, start, end}]，hdr = NAL 头字节位置，start = NAL 负载起点。 */
  function parseAnnexB(u8) {
    var sc = [], i, n = u8.length;
    for (i = 0; i + 2 < n; i++) {
      if (u8[i] === 0 && u8[i + 1] === 0) {
        if (u8[i + 2] === 1) { sc.push([i, 3]); i += 2; }
        else if (i + 3 < n && u8[i + 2] === 0 && u8[i + 3] === 1) { sc.push([i, 4]); i += 3; }
      }
    }
    var out = [];
    if (!sc.length) { if (n) out.push({ sc: 0, hdr: 0, start: 0, end: n }); return out; }
    for (var k = 0; k < sc.length; k++) {
      var start = sc[k][0] + sc[k][1];
      var end = (k + 1 < sc.length) ? sc[k + 1][0] : n;
      if (end > start) out.push({ sc: sc[k][0], hdr: start, start: start, end: end });
    }
    return out;
  }
  function eqBytes(a, b) {
    if (!a || !b || a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
  }

  // ---------------------------------------------------------------------------
  // SPS 解析（取宽高/档次/等级，供 avcC 与 avc1 box 用）
  // ---------------------------------------------------------------------------
  function BitReader(d, bitOff) { this.d = d; this.p = bitOff || 0; }
  BitReader.prototype.u = function (n) {
    var v = 0;
    for (var i = 0; i < n; i++) {
      var byte = this.d[this.p >> 3];
      if (byte === undefined) { this.p++; continue; }
      var bit = (byte >> (7 - (this.p & 7))) & 1;
      v = (v << 1) | bit; this.p++;
    }
    return v;
  };
  BitReader.prototype.ue = function () {
    var lz = 0;
    while (this.u(1) === 0 && lz < 32) lz++;
    var v = lz ? this.u(lz) : 0;
    return Math.pow(2, lz) - 1 + v;
  };
  BitReader.prototype.se = function () { var k = this.ue(); return (k & 1) ? ((k + 1) >> 1) : -(k >> 1); };
  function skipScalingList(br, size) {
    var last = 8, next = 8, j;
    for (j = 0; j < size; j++) {
      if (next !== 0) { var delta = br.se(); next = (last + delta + 256) % 256; }
      last = (next === 0) ? last : next;
    }
  }
  /** 返回 {profile, constraint, level, width, height} 或 null。 */
  function parseSps(sps) {
    try {
      var br = new BitReader(sps, 8); // 跳过 1 字节 NAL 头
      var profile = sps[1], constraint = sps[2], level = sps[3];
      br.u(8); br.u(8); br.u(8); // profile/constraint/level 已在字节里
      br.ue(); // sps_id
      var chromaFormatIdc = 1, separateColourPlane = 0;
      var high = (profile === 100 || profile === 110 || profile === 122 || profile === 244 ||
                  profile === 44 || profile === 83 || profile === 86 || profile === 118 ||
                  profile === 128 || profile === 138 || profile === 139 || profile === 134 || profile === 135);
      if (high) {
        chromaFormatIdc = br.ue();
        if (chromaFormatIdc === 3) separateColourPlane = br.u(1);
        br.ue(); br.ue(); // bit_depth_luma/chroma_minus8
        br.u(1); // qpprime_y_zero_transform_bypass_flag
        if (br.u(1)) { // seq_scaling_matrix_present
          var cnt = (chromaFormatIdc !== 3) ? 8 : 12;
          for (var i = 0; i < cnt; i++) if (br.u(1)) skipScalingList(br, i < 6 ? 16 : 64);
        }
      }
      br.ue(); // log2_max_frame_num_minus4
      var pocType = br.ue();
      if (pocType === 0) br.ue();
      else if (pocType === 1) {
        br.u(1); br.se(); br.se();
        var n = br.ue();
        for (var k = 0; k < n; k++) br.se();
      }
      br.ue(); // max_num_ref_frames
      br.u(1); // gaps_in_frame_num_value_allowed_flag
      var picWidthInMbs = br.ue() + 1;
      var picHeightInMapUnits = br.ue() + 1;
      var frameMbsOnly = br.u(1);
      if (!frameMbsOnly) br.u(1);
      br.u(1); // direct_8x8_inference_flag
      var cropL = 0, cropR = 0, cropT = 0, cropB = 0;
      if (br.u(1)) { cropL = br.ue(); cropR = br.ue(); cropT = br.ue(); cropB = br.ue(); }
      var subW = (chromaFormatIdc === 0 || separateColourPlane === 1) ? 1 : (chromaFormatIdc === 3 ? 1 : 2);
      var subH = (chromaFormatIdc === 0 || separateColourPlane === 1) ? 1 : (chromaFormatIdc === 3 ? 1 : 2);
      var cropUnitX = subW;
      var cropUnitY = subH * (2 - frameMbsOnly);
      var width = picWidthInMbs * 16 - (cropL + cropR) * cropUnitX;
      var height = (2 - frameMbsOnly) * picHeightInMapUnits * 16 - (cropT + cropB) * cropUnitY;
      if (!(width > 0 && height > 0)) return null;
      return { profile: profile, constraint: constraint, level: level, width: width, height: height };
    } catch (e) { return null; }
  }
  function codecString(sps) {
    return "avc1." + hex2(sps[1]) + hex2(sps[2]) + hex2(sps[3]);
  }
  function hex2(n) { return (n < 16 ? "0" : "") + n.toString(16); }
  function buildAvcC(sps, pps) {
    var b = new Uint8Array(11 + sps.length + pps.length);
    b[0] = 1; b[1] = sps[1]; b[2] = sps[2]; b[3] = sps[3];
    b[4] = 0xff; b[5] = 0xe1;
    b[6] = (sps.length >> 8) & 0xff; b[7] = sps.length & 0xff; b.set(sps, 8);
    var o = 8 + sps.length;
    b[o++] = 1; b[o++] = (pps.length >> 8) & 0xff; b[o++] = pps.length & 0xff; b.set(pps, o);
    return b;
  }

  // ---------------------------------------------------------------------------
  // fMP4 封装器（MSE 用）：init segment + 每 AU 一个 moof+mdat 分片
  // ---------------------------------------------------------------------------
  var MATRIX = cat(u32(0x00010000), u32(0), u32(0),
                   u32(0), u32(0x00010000), u32(0),
                   u32(0), u32(0), u32(0x40000000));

  function Fmp4Muxer() {
    this.timescale = TS;
    this.seq = 0;
    this.decodeTime = 0; // 单位 = timescale
    this.sps = null; this.pps = null;
    this.width = 0; this.height = 0;
    this.codec = "";
  }
  Fmp4Muxer.prototype.configure = function (sps, pps) {
    this.sps = sps; this.pps = pps;
    var info = parseSps(sps);
    if (info) { this.width = info.width; this.height = info.height; }
    this.codec = codecString(sps);
    this.seq = 0; this.decodeTime = 0;
  };
  Fmp4Muxer.prototype.initSegment = function () {
    var w = this.width || 16, h = this.height || 16;
    var ftyp = box("ftyp", [fourcc("isom"), u32(0x200), fourcc("isom"), fourcc("iso2"), fourcc("avc1"), fourcc("mp41")]);

    var mvhd = fullbox("mvhd", 0, 0, [
      u32(0), u32(0), u32(TS), u32(0), u32(0x00010000), u16(0x0100), u16(0),
      u32(0), u32(0), MATRIX, u32(0), u32(0), u32(0), u32(0), u32(0), u32(0), u32(2)
    ]);
    var tkhd = fullbox("tkhd", 0, 0x000007, [
      u32(0), u32(0), u32(1), u32(0), u32(0), u32(0), u32(0),
      u16(0), u16(0), u16(0), u16(0), MATRIX, u32(w << 16), u32(h << 16)
    ]);
    var mdhd = fullbox("mdhd", 0, 0, [u32(0), u32(0), u32(TS), u32(0), u16(0x55c4), u16(0)]);
    var hdlr = fullbox("hdlr", 0, 0, [u32(0), fourcc("vide"), u32(0), u32(0), u32(0), cat(fourcc("VideoHandler"), u8(0))]);
    var vmhd = fullbox("vmhd", 0, 1, [u16(0), u16(0), u16(0), u16(0)]);
    var url = fullbox("url ", 0, 1, []);
    var dref = fullbox("dref", 0, 0, [u32(1), url]);
    var dinf = box("dinf", [dref]);
    var avcC = box("avcC", [buildAvcC(this.sps, this.pps)]);
    var avc1 = box("avc1", [cat(
      zeros(6), u16(1), u16(0), u16(0), zeros(12),
      u16(w), u16(h), u32(0x00480000), u32(0x00480000), u32(0), u16(1),
      zeros(32), u16(0x0018), u16(0xffff)
    ), avcC]);
    var stsd = fullbox("stsd", 0, 0, [u32(1), avc1]);
    var stts = fullbox("stts", 0, 0, [u32(0)]);
    var stsc = fullbox("stsc", 0, 0, [u32(0)]);
    var stsz = fullbox("stsz", 0, 0, [u32(0), u32(0)]);
    var stco = fullbox("stco", 0, 0, [u32(0)]);
    var stbl = box("stbl", [stsd, stts, stsc, stsz, stco]);
    var minf = box("minf", [vmhd, dinf, stbl]);
    var mdia = box("mdia", [mdhd, hdlr, minf]);
    var trak = box("trak", [tkhd, mdia]);
    var trex = fullbox("trex", 0, 0, [u32(1), u32(1), u32(0), u32(0), u32(0)]);
    var mvex = box("mvex", [trex]);
    var moov = box("moov", [mvhd, trak, mvex]);
    return cat(ftyp, moov);
  };
  /** samples: [{data:Uint8Array(AVCC), duration:units, key:bool}] → moof+mdat */
  Fmp4Muxer.prototype.fragment = function (samples) {
    this.seq++;
    var baseTime = this.decodeTime;
    var i, totalBytes = 0, totalDur = 0;
    for (i = 0; i < samples.length; i++) { totalBytes += samples[i].data.length; totalDur += samples[i].duration; }

    function buildMoof(dataOffset) {
      var mfhd = fullbox("mfhd", 0, 0, [u32(this.seq)]);
      var tfhd = fullbox("tfhd", 0, 0x020000, [u32(1)]);
      var tfdt = fullbox("tfdt", 1, 0, [u64(baseTime)]);
      var trunParts = [u32(samples.length), i32(dataOffset)];
      for (var j = 0; j < samples.length; j++) {
        var s = samples[j];
        trunParts.push(u32(s.duration), u32(s.data.length), u32(s.key ? 0x02000000 : 0x01010000));
      }
      var trun = fullbox("trun", 0, 0x000701, trunParts); // data-offset + duration + size + flags
      return box("moof", [mfhd, box("traf", [tfhd, tfdt, trun])]);
    }
    var moof = buildMoof.call(this, 0);
    var dataOffset = moof.length + 8; // mdat 头 8 字节
    moof = buildMoof.call(this, dataOffset);

    var dataParts = [];
    for (i = 0; i < samples.length; i++) dataParts.push(samples[i].data);
    var mdat = box("mdat", dataParts);

    this.decodeTime += totalDur;
    return cat(moof, mdat);
  };

  /** Annex-B payload → AVCC 样本数据（长度前缀，去掉 SPS/PPS/AUD）。 */
  function buildAvccSample(payload, nalus) {
    var keep = [], total = 0, i;
    for (i = 0; i < nalus.length; i++) {
      var t = payload[nalus[i].hdr] & 0x1f;
      if (t === 7 || t === 8 || t === 9) continue; // SPS/PPS/AUD 走带外
      var len = nalus[i].end - nalus[i].hdr;
      if (len <= 0) continue;
      keep.push({ s: nalus[i].hdr, e: nalus[i].end });
      total += 4 + len;
    }
    if (!keep.length) return null;
    var out = new Uint8Array(total), o = 0;
    for (i = 0; i < keep.length; i++) {
      var l = keep[i].e - keep[i].s;
      out[o++] = (l >>> 24) & 0xff; out[o++] = (l >>> 16) & 0xff; out[o++] = (l >>> 8) & 0xff; out[o++] = l & 0xff;
      out.set(payload.subarray(keep[i].s, keep[i].e), o); o += l;
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // 共享：时钟 / 计时 / 计数
  // ---------------------------------------------------------------------------
  function nowUs() {
    var p = (typeof performance !== "undefined" && performance.now) ? performance.now() : Date.now();
    return p * 1000;
  }
  function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }

  /** 计时器：把微秒级 pts 变成 fMP4 采样时长（单位 timescale）。 */
  function Timing() {
    this.lastPtsUs = null;
    this.minDur = Math.round(TS / 240);
    this.maxDur = Math.round(TS * 0.5);
    this.defaultDur = Math.round(TS / 30);
  }
  Timing.prototype.duration = function (ptsUs) {
    var d;
    if (this.lastPtsUs === null) d = this.defaultDur;
    else {
      var delta = ptsUs - this.lastPtsUs;
      if (!(delta > 0)) d = this.minDur;
      else d = clamp(Math.round(delta * TS / 1e6), this.minDur, this.maxDur);
    }
    this.lastPtsUs = ptsUs;
    return d;
  };
  Timing.prototype.reset = function () { this.lastPtsUs = null; };

  /** 滑动窗口 fps 统计 + 延迟。 */
  function Meter() {
    this.presented = 0;
    this.decoded = 0;
    this.dropped = 0;
    this.needKeyCount = 0;
    this.congested = 0;
    this._times = [];
    this._latSum = 0; this._latN = 0;
    this.latencyMs = -1;
  }
  Meter.prototype.frame = function (latencyMs) {
    this.presented++;
    var t = (typeof performance !== "undefined" && performance.now) ? performance.now() : Date.now();
    this._times.push(t);
    while (this._times.length && t - this._times[0] > 1000) this._times.shift();
    if (typeof latencyMs === "number" && latencyMs >= 0) { this._latSum += latencyMs; this._latN++; }
  };
  Meter.prototype.fps = function () { return this._times.length; };
  Meter.prototype.avgLatency = function () { return this._latN ? (this._latSum / this._latN) : -1; };
  Meter.prototype.reset = function () { this.presented = 0; this.decoded = 0; this.dropped = 0; this.congested = 0; this._times = []; this._latSum = 0; this._latN = 0; this.latencyMs = -1; };

  // ---------------------------------------------------------------------------
  // 后端基类：公共的 NAL 解析 / SPS-PPS 缓存 / 拥塞策略
  // ---------------------------------------------------------------------------
  function BackendBase(opts) {
    this.opts = opts;
    this.sps = null; this.pps = null;
    this.codec = ""; this.width = 0; this.height = 0;
    this.needKey = true;
    this.meter = new Meter();
    this.queueFrames = 0; this.queueBytes = 0;
    this._needKeyNotifiedAt = 0;
    this.element = null;
    this.state = "init";
  }
  BackendBase.prototype._notifyNeedKey = function (reason) {
    var t = (typeof performance !== "undefined" && performance.now) ? performance.now() : Date.now();
    if (t - this._needKeyNotifiedAt < 250) return; // 限频，别刷屏
    this._needKeyNotifiedAt = t;
    this.meter.needKeyCount++;
    if (this.opts.onNeedKeyframe) { try { this.opts.onNeedKeyframe(reason || "need-idr"); } catch (e) {} }
  };
  BackendBase.prototype._overBudget = function () {
    return this.queueFrames > this.opts.maxBufferFrames || this.queueBytes > this.opts.maxBufferBytes;
  };
  BackendBase.prototype._emitCodec = function () {
    if (this.opts.onCodec) { try { this.opts.onCodec(this.codec); } catch (e) {} }
  };
  BackendBase.prototype._emitResize = function (w, h) {
    if (w !== this.width || h !== this.height) {
      this.width = w; this.height = h;
      if (this.opts.onResize) { try { this.opts.onResize(w, h); } catch (e) {} }
    }
  };
  /** 扫描一个 AU，更新 SPS/PPS 缓存；返回 {hasIdr, configChanged}。 */
  BackendBase.prototype._scanConfig = function (payload, nalus) {
    var hasIdr = false, changed = false;
    for (var i = 0; i < nalus.length; i++) {
      var t = payload[nalus[i].hdr] & 0x1f;
      if (t === 7) {
        var s = payload.slice(nalus[i].hdr, nalus[i].end);
        if (!eqBytes(s, this.sps)) { this.sps = s; changed = true; }
      } else if (t === 8) {
        var p = payload.slice(nalus[i].hdr, nalus[i].end);
        if (!eqBytes(p, this.pps)) { this.pps = p; changed = true; }
      } else if (t === 5) hasIdr = true;
    }
    if (this.sps && this.sps.length >= 4) {
      var info = parseSps(this.sps);
      if (info && (info.width !== this.width || info.height !== this.height)) { this._emitResize(info.width, info.height); }
    }
    return { hasIdr: hasIdr, configChanged: changed };
  };

  // ---------------------------------------------------------------------------
  // MSE 后端：fMP4 → MediaSource → <video>
  // ---------------------------------------------------------------------------
  function MseBackend(opts) {
    BackendBase.call(this, opts);
    this.video = document.createElement("video");
    this.video.muted = true;
    this.video.defaultMuted = true;
    this.video.playsInline = true;
    this.video.setAttribute("playsinline", "");
    this.video.setAttribute("muted", "");
    this.video.autoplay = true;
    this.video.preload = "auto";
    this.video.style.cssText = "width:100%;height:100%;object-fit:contain;background:#000;display:block;";
    this.element = this.video;

    this.mediaSource = null;
    this.sb = null;
    this.muxer = new Fmp4Muxer();
    this.timing = new Timing();
    this.pending = [];        // [{data, bytes, key}]
    this.byteLog = [];        // [{tEnd, bytes}] MSE 已缓冲字节（近似）
    this.mediaTime = 0;       // 秒
    this._lastRemovedTo = 0;
    this._appendedBytes = 0;
    this._rvfcId = 0;
    this._lastPresentedFrames = undefined;
    this._lastPushUs = null;
    this._ready = false;       // SourceBuffer 就绪（sourceopen 已建 sb）
    this._initPending = null;  // 待追加的 init 段（必须排在所有分片之前）
    this._awaitFirstFrame = true; // 每次（重）建源后，等首帧呈现再回调 onFirstFrame
    this._mount();
    this._newSource();
  }
  MseBackend.prototype = Object.create(BackendBase.prototype);
  MseBackend.prototype.constructor = MseBackend;

  MseBackend.prototype._mount = function () {
    if (this.opts.container) {
      this.opts.container.appendChild(this.video);
    }
  };
  MseBackend.prototype._newSource = function () {
    var self = this;
    this._detachSource();
    this.pending = []; this.queueFrames = 0; this.queueBytes = 0;
    this.byteLog = []; this._appendedBytes = 0; this._lastRemovedTo = 0;
    this.mediaTime = 0;
    this._ready = false;
    this._initPending = null;
    this._awaitFirstFrame = true;
    var ms;
    try {
      ms = new MediaSource();
    } catch (e) {
      this._fatal("MediaSource 不可用（MSE 缺失）: " + e.message);
      return;
    }
    this.mediaSource = ms;
    var url = URL.createObjectURL(ms);
    this.video.src = url;
    // 闭包绑定具体的 ms：防止「连开两个 MediaSource」时旧 sourceopen 误配到新实例（竞态）
    ms.addEventListener("sourceopen", function () {
      if (self.mediaSource !== ms) return;
      self._onSourceOpen(ms);
    });
    ms.addEventListener("sourceclose", function () { if (self.mediaSource === ms) self.state = "closed"; });
    this._startRvfc();
  };
  MseBackend.prototype._detachSource = function () {
    var ms = this.mediaSource;
    this.sb = null;
    this.mediaSource = null;
    try { if (ms && ms.readyState === "open") ms.endOfStream(); } catch (e) {}
    try { if (this.video.src) URL.revokeObjectURL(this.video.src); } catch (e) {}
  };
  MseBackend.prototype._onSourceOpen = function (ms) {
    var self = this;
    if (this.mediaSource !== ms || ms.readyState !== "open") return;
    if (!this.sps || !this.pps) return; // 等配置（拿到 SPS/PPS 后 push 会重建源再回来）
    this.codec = codecString(this.sps);
    var mime = 'video/mp4; codecs="' + this.codec + '"';
    if (typeof MediaSource.isTypeSupported === "function" && !MediaSource.isTypeSupported(mime)) {
      this._fatal("MSE 不支持 " + mime);
      return;
    }
    try {
      this.sb = ms.addSourceBuffer(mime);
      this.sb.mode = "segments";
    } catch (e) { this._fatal("addSourceBuffer 失败: " + e.message); return; }
    this.sb.addEventListener("updateend", function () { self._onUpdateEnd(); });
    this.sb.addEventListener("error", function (e) { self._fatal("SourceBuffer error: " + (e && e.message)); });
    this.state = "ready";
    this._emitCodec();
    // init 段（ftyp+moov）必须排在所有分片之前：置为 _initPending，由 _pump 优先追加
    if (!this._initPending) this._initPending = this.muxer.initSegment();
    this._ready = true;
    this._pump();
  };
  MseBackend.prototype._startRvfc = function () {
    var self = this;
    if (typeof this.video.requestVideoFrameCallback !== "function") return;
    // 重建源时会再次调用：先撤掉旧回调，否则多个自续 rVFC 循环会叠加、重复计帧
    if (this._rvfcId && this.video.cancelVideoFrameCallback) {
      try { this.video.cancelVideoFrameCallback(this._rvfcId); } catch (e) {}
    }
    this._rvfcId = 0;
    var step = function (now, meta) {
      var pf = meta ? meta.presentedFrames : undefined;
      // rVFC 可能对同一 compositor tick 重入：以 presentedFrames 去重，只认真实新帧
      if (pf !== undefined && pf === self._lastPresentedFrames) {
        self._rvfcId = self.video.requestVideoFrameCallback(step);
        return;
      }
      self._lastPresentedFrames = pf;
      var lat = -1;
      var b = self._buffered();
      if (b) lat = (b.end - self.video.currentTime) * 1000;
      self.meter.frame(lat);
      self.meter.decoded = self.meter.presented;
      if (self.opts.onFrame) { try { self.opts.onFrame({ presented: self.meter.presented, presentedFrames: pf, mediaTime: meta && meta.mediaTime, width: self.video.videoWidth, height: self.video.videoHeight }); } catch (e) {} }
      if (self._awaitFirstFrame) {
        self._awaitFirstFrame = false;
        self._emitResize(self.video.videoWidth, self.video.videoHeight);
        self._firstFrame();
      }
      self._rvfcId = self.video.requestVideoFrameCallback(step);
    };
    this._rvfcId = this.video.requestVideoFrameCallback(step);
  };
  MseBackend.prototype._firstFrame = function () {
    this.state = "playing";
    if (this.opts.onFirstFrame) { try { this.opts.onFirstFrame({ backend: "mse", width: this.video.videoWidth, height: this.video.videoHeight }); } catch (e) {} }
  };
  MseBackend.prototype._buffered = function () {
    var b = this.sb ? this.sb.buffered : this.video.buffered;
    if (!b || !b.length) return null;
    return { start: b.start(0), end: b.end(b.length - 1) };
  };
  MseBackend.prototype._enqueue = function (data, tEnd) {
    this.pending.push({ data: data, bytes: data.length, tEnd: tEnd });
    this.queueFrames++;
    this.queueBytes += data.length;
  };
  MseBackend.prototype._pump = function () {
    if (!this._ready || !this.sb || this.mediaSource.readyState !== "open" || this.sb.updating) return;
    // init 段永远优先（排在所有分片之前）
    if (this._initPending) {
      var init = this._initPending; this._initPending = null;
      try { this.sb.appendBuffer(init); }
      catch (e) { this._fatal("appendBuffer(init): " + (e && e.message)); }
      return;
    }
    if (!this.pending.length) { this._trimAndCatchup(); this._ensurePlaying(); return; }
    var entry = this.pending.shift();
    this.queueFrames--; this.queueBytes -= entry.bytes;
    try {
      this.sb.appendBuffer(entry.data);
      // 只有真正进了 SourceBuffer 的字节才记进字节账（否则字节上界会被丢弃的帧污染）
      if (entry.tEnd != null) this.byteLog.push({ tEnd: entry.tEnd, bytes: entry.data.length });
    } catch (e) {
      if (e && e.name === "QuotaExceededError") { this._trimAndCatchup(); }
      else {
        // 非配额失败通常意味着媒体元素进了错误态（解码/流结构连锁）→ 整源重建自愈，等下一个 IDR
        this.pending = []; this.queueFrames = 0; this.queueBytes = 0;
        this.needKey = true;
        this.meter.dropped++;
        this._notifyNeedKey("append-error");
        if (this.video.error) this._newSource();
      }
    }
  };
  MseBackend.prototype._onUpdateEnd = function () {
    this._trimAndCatchup();
    this._pump();
    this._ensurePlaying();
  };
  MseBackend.prototype._trimAndCatchup = function () {
    if (!this.sb || this.sb.updating || this.mediaSource.readyState !== "open") return;
    var b = this.sb.buffered;
    if (!b.length) return;
    var start = b.start(0), end = b.end(b.length - 1), cur = this.video.currentTime;
    var maxSec = this.opts.maxBufferSec;
    // 1) 按秒上界 trim（只删已播过的）
    var removeTo = cur - this.opts.minBufferSec;
    if (end - start > maxSec) removeTo = Math.max(removeTo, end - maxSec);
    // 2) 按字节上界 trim（近似：回看字节账）
    var bytes = this._bufferedBytes();
    if (bytes > this.opts.maxBufferBytes) removeTo = Math.max(removeTo, start + (end - start) * 0.5);
    if (removeTo > start + 0.05) {
      try { this.sb.remove(start, Math.min(removeTo, end)); } catch (e) {}
      this._lastRemovedTo = Math.min(removeTo, end);
      this._pruneByteLog(this._lastRemovedTo);
      return;
    }
    // 3) 贴边：落后太多就跳
    var target = this.opts.maxLatencyMs / 1000;
    if (end - cur > target + 0.2) { try { this.video.currentTime = end - target; } catch (e) {} }
  };
  MseBackend.prototype._bufferedBytes = function () {
    var s = 0;
    for (var i = 0; i < this.byteLog.length; i++) s += this.byteLog[i].bytes;
    return s;
  };
  MseBackend.prototype._pruneByteLog = function (to) {
    var out = [];
    for (var i = 0; i < this.byteLog.length; i++) if (this.byteLog[i].tEnd > to) out.push(this.byteLog[i]);
    this.byteLog = out;
  };
  MseBackend.prototype._ensurePlaying = function () {
    if (this.video.paused) {
      var pr = this.video.play();
      if (pr && pr.catch) pr.catch(function () {});
    }
  };
  MseBackend.prototype._fatal = function (msg) {
    this.state = "error";
    if (this.opts.onError) { try { this.opts.onError(new Error(msg)); } catch (e) {} }
  };

  MseBackend.prototype.push = function (payload, ptsUs, keyHint) {
    var nalus = parseAnnexB(payload);
    if (!nalus.length) return false;
    var info = this._scanConfig(payload, nalus);
    var hasIdr = info.hasIdr || keyHint === true;

    // 配置变更（换分辨率/换 SPS）：重建 MediaSource，等下一个 IDR
    if (info.configChanged && this.sps && this.pps) {
      this.needKey = true;
      this.muxer.configure(this.sps, this.pps);
      this._newSource(); // 异步：sourceopen 后补 init
      this._notifyNeedKey("config-changed");
      if (!hasIdr) return false;
    }

    if (this.needKey) {
      if (!hasIdr) { this._notifyNeedKey("wait-idr"); this.meter.dropped++; return false; }
      if (!this.sps || !this.pps) { this._notifyNeedKey("no-config"); this.meter.dropped++; return false; }
      if (!this.muxer.sps || !eqBytes(this.muxer.sps, this.sps) || !eqBytes(this.muxer.pps, this.pps)) {
        this.muxer.configure(this.sps, this.pps);
      }
    }

    // 拥塞：超预算 → 丢弃整个待解队列、等 IDR（绝不删旧 delta 硬解）
    if (this._overBudget()) {
      this.pending = []; this.queueFrames = 0; this.queueBytes = 0;
      this.needKey = true;
      this.meter.dropped++; this.meter.congested++;
      this._notifyNeedKey("congested");
      return false;
    }

    var sample = buildAvccSample(payload, nalus);
    if (!sample) { this.meter.dropped++; return false; }

    var dur = this.timing.duration(ptsUs);
    var frag = this.muxer.fragment([{ data: sample, duration: dur, key: hasIdr }]);
    var tEnd = this.mediaTime + dur / TS;
    this.mediaTime = tEnd;

    this.needKey = false; // 到这里必然是「有 IDR 且配置齐」的合法起头帧
    this._enqueue(frag, tEnd);
    this._pump();
    return true;
  };
  MseBackend.prototype.reset = function () {
    this.timing.reset();
    this.needKey = true;
    this.meter.reset();
    this._newSource();
  };
  MseBackend.prototype.close = function () {
    if (this._rvfcId && this.video.cancelVideoFrameCallback) { try { this.video.cancelVideoFrameCallback(this._rvfcId); } catch (e) {} }
    this._detachSource();
    try { this.video.removeAttribute("src"); this.video.load(); } catch (e) {}
    if (this.video.parentNode) this.video.parentNode.removeChild(this.video);
    this.state = "closed";
  };
  MseBackend.prototype.stats = function () {
    var b = this._buffered();
    return {
      backend: "mse", codec: this.codec, width: this.width, height: this.height,
      presented: this.meter.presented, decoded: this.meter.decoded,
      fps: this.meter.fps(), latencyMs: b ? Math.round((b.end - this.video.currentTime) * 1000) : -1,
      droppedFrames: this.meter.dropped, needKeyCount: this.meter.needKeyCount,
      congested: this.meter.congested,
      queueFrames: this.queueFrames, queueBytes: this.queueBytes,
      bufferedMs: b ? Math.round((b.end - b.start) * 1000) : 0,
      bufferedBytes: this._bufferedBytes(),
      needKey: this.needKey, state: this.state
    };
  };

  // ---------------------------------------------------------------------------
  // WebCodecs 后端：VideoDecoder → VideoFrame → canvas（rAF 合并）
  // ---------------------------------------------------------------------------
  function WcBackend(opts) {
    BackendBase.call(this, opts);
    this.canvas = document.createElement("canvas");
    this.canvas.style.cssText = "width:100%;height:100%;object-fit:contain;background:#000;display:block;";
    this.ctx = this.canvas.getContext("2d");
    this.element = this.canvas;
    this.dec = null;
    this.timing = new Timing();
    this._pendingFrame = null; // 只留最新一帧
    this._rafScheduled = 0;
    this._queue = 0; this._queueBytes = 0;
    this._lastPushUs = null;
    this.hasKey = false;
    this._awaitFirstFrame = true;
    if (opts.container) opts.container.appendChild(this.canvas);
  }
  WcBackend.prototype = Object.create(BackendBase.prototype);
  WcBackend.prototype.constructor = WcBackend;

  WcBackend.prototype._makeDecoder = function () {
    var self = this;
    if (this.dec) { try { this.dec.close(); } catch (e) {} this.dec = null; }
    this.codec = codecString(this.sps);
    try {
      var dec = new VideoDecoder({
        output: function (frame) { self._onFrame(frame); },
        error: function (e) { self._fatal("VideoDecoder: " + (e && e.message)); }
      });
      dec.configure({ codec: this.codec, description: buildAvcC(this.sps, this.pps), optimizeForLatency: true });
      this.dec = dec; this.hasKey = false;
      this._emitCodec();
      return true;
    } catch (e) { this._fatal("configure: " + e.message); return false; }
  };
  WcBackend.prototype._onFrame = function (frame) {
    if (this._pendingFrame) { try { this._pendingFrame.close(); } catch (e) {} }
    this._pendingFrame = frame;
    this.meter.decoded++;
    this._scheduleFlush();
  };
  WcBackend.prototype._scheduleFlush = function () {
    var self = this;
    if (this._rafScheduled) return;
    this._rafScheduled = requestAnimationFrame(function () { self._flush(); });
  };
  WcBackend.prototype._flush = function () {
    this._rafScheduled = 0;
    var f = this._pendingFrame; this._pendingFrame = null;
    if (!f) return;
    try {
      var w = f.displayWidth || f.codedWidth, h = f.displayHeight || f.codedHeight;
      if (this.canvas.width !== w || this.canvas.height !== h) { this.canvas.width = w; this.canvas.height = h; this._emitResize(w, h); }
      this.ctx.drawImage(f, 0, 0, w, h);
      var lat = this._lastPushUs !== null ? (nowUs() - this._lastPushUs) / 1000 : -1;
      this.meter.frame(lat);
      if (this.opts.onFrame) { try { this.opts.onFrame({ presented: this.meter.presented, width: w, height: h }); } catch (e) {} }
      if (this._awaitFirstFrame) {
        this._awaitFirstFrame = false;
        this.state = "playing";
        if (this.opts.onFirstFrame) { try { this.opts.onFirstFrame({ backend: "webcodecs", width: w, height: h }); } catch (e) {} }
      }
    } catch (e) { /* 坏帧丢弃 */ }
    finally { try { f.close(); } catch (e) {} }
  };
  WcBackend.prototype._fatal = function (msg) {
    this.state = "error";
    if (this.opts.onError) { try { this.opts.onError(new Error(msg)); } catch (e) {} }
  };

  WcBackend.prototype.push = function (payload, ptsUs, keyHint) {
    var nalus = parseAnnexB(payload);
    if (!nalus.length) return false;
    var info = this._scanConfig(payload, nalus);
    var hasIdr = info.hasIdr || keyHint === true;

    if (info.configChanged && this.sps && this.pps) {
      this.needKey = true;
      this.hasKey = false;
      this._makeDecoder();
      this._notifyNeedKey("config-changed");
      if (!hasIdr) return false;
    }
    if (this.needKey) {
      if (!hasIdr) { this._notifyNeedKey("wait-idr"); this.meter.dropped++; return false; }
      if (!this.sps || !this.pps) { this._notifyNeedKey("no-config"); this.meter.dropped++; return false; }
      if (!this.dec || this.dec.state === "closed") { if (!this._makeDecoder()) return false; }
    }
    // 拥塞：超预算 → 丢待解、等 IDR
    if (this._queue > this.opts.maxBufferFrames || this._queueBytes > this.opts.maxBufferBytes) {
      this.needKey = true;
      this.meter.dropped++; this.meter.congested++;
      this._notifyNeedKey("congested");
      return false;
    }
    var sample = buildAvccSample(payload, nalus);
    if (!sample) { this.meter.dropped++; return false; }
    this.timing.duration(ptsUs); // 推进计时（WebCodecs 用自带 timestamp，这里只为对齐节奏）
    this._lastPushUs = ptsUs;
    try {
      this.dec.decode(new EncodedVideoChunk({
        type: (hasIdr || !this.hasKey) ? "key" : "delta",
        timestamp: Math.round(ptsUs),
        data: sample
      }));
      if (hasIdr) { this.hasKey = true; this.needKey = false; }
      this._queue = this.dec.decodeQueueSize; this._queueBytes = 0;
    } catch (e) { this.meter.dropped++; return false; }
    return true;
  };
  WcBackend.prototype.reset = function () {
    this.timing.reset();
    this.needKey = true; this.hasKey = false;
    this._awaitFirstFrame = true;
    this.meter.reset();
    if (this._pendingFrame) { try { this._pendingFrame.close(); } catch (e) {} this._pendingFrame = null; }
    if (this.dec) { try { this.dec.close(); } catch (e) {} this.dec = null; }
    if (this.sps && this.pps) this._makeDecoder();
  };
  WcBackend.prototype.close = function () {
    if (this._rafScheduled) { try { cancelAnimationFrame(this._rafScheduled); } catch (e) {} }
    if (this._pendingFrame) { try { this._pendingFrame.close(); } catch (e) {} this._pendingFrame = null; }
    if (this.dec) { try { this.dec.close(); } catch (e) {} this.dec = null; }
    if (this.canvas.parentNode) this.canvas.parentNode.removeChild(this.canvas);
    this.state = "closed";
  };
  WcBackend.prototype.stats = function () {
    return {
      backend: "webcodecs", codec: this.codec, width: this.width, height: this.height,
      presented: this.meter.presented, decoded: this.meter.decoded,
      fps: this.meter.fps(), latencyMs: Math.round(this.meter.avgLatency()),
      droppedFrames: this.meter.dropped, needKeyCount: this.meter.needKeyCount,
      congested: this.meter.congested,
      queueFrames: this.dec ? this.dec.decodeQueueSize : 0, queueBytes: this._queueBytes,
      bufferedMs: 0, bufferedBytes: 0,
      needKey: this.needKey, state: this.state
    };
  };

  // ---------------------------------------------------------------------------
  // 能力探测 + 门面
  // ---------------------------------------------------------------------------
  function hasWebCodecs() {
    return typeof global.VideoDecoder !== "undefined" &&
           typeof global.EncodedVideoChunk !== "undefined" &&
           global.isSecureContext !== false;
  }
  function hasMSE() {
    return typeof global.MediaSource !== "undefined" && typeof global.MediaSource.isTypeSupported === "function";
  }
  function pickBackend(opts) {
    var want = opts.backend || "auto";
    if (want === "webcodecs") return hasWebCodecs() ? "webcodecs" : "mse";
    if (want === "mse") return "mse";
    if (hasWebCodecs()) return "webcodecs";
    if (hasMSE()) return "mse";
    return "mse"; // 最终兜底也走 mse（会自行报错）
  }

  function Player(opts) {
    opts = opts || {};
    var d = {
      container: null, backend: "auto",
      maxLatencyMs: 300, maxBufferFrames: 90, maxBufferBytes: 4 << 20,
      maxBufferSec: 6, minBufferSec: 1
    };
    for (var k in d) if (opts[k] === undefined) opts[k] = d[k];
    this.opts = opts;
    this.backendName = pickBackend(opts);
    if (this.backendName === "webcodecs") this.impl = new WcBackend(opts);
    else this.impl = new MseBackend(opts);
    this.element = this.impl.element;
    if (opts.onBackend) { try { opts.onBackend(this.backendName); } catch (e) {} }
  }
  Player.prototype.pushAnnexB = function (payload, pts, keyframe) {
    if (this.impl.state === "closed") return false;
    var ptsUs;
    if (typeof pts === "number" && isFinite(pts)) ptsUs = pts;
    else ptsUs = nowUs();
    return this.impl.push(payload, ptsUs, keyframe);
  };
  Player.prototype.push = function (payload, pts, keyframe) { return this.pushAnnexB(payload, pts, keyframe); };
  Player.prototype.reset = function () { this.impl.reset(); };
  Player.prototype.close = function () { this.impl.close(); };
  Player.prototype.stats = function () { return this.impl.stats(); };
  Player.prototype.requestKeyframe = function () { this.impl.needKey = true; this.impl._notifyNeedKey("requested"); };
  Object.defineProperty(Player.prototype, "backend", { get: function () { return this.backendName; } });
  Object.defineProperty(Player.prototype, "codec", { get: function () { return this.impl.codec; } });
  Object.defineProperty(Player.prototype, "width", { get: function () { return this.impl.width; } });
  Object.defineProperty(Player.prototype, "height", { get: function () { return this.impl.height; } });

  var H264Player = {
    create: function (opts) { return new Player(opts); },
    hasWebCodecs: hasWebCodecs,
    hasMSE: hasMSE,
    pickBackend: pickBackend,
    _internals: { Fmp4Muxer: Fmp4Muxer, parseAnnexB: parseAnnexB, parseSps: parseSps, buildAvccSample: buildAvccSample, codecString: codecString }
  };

  global.H264Player = H264Player;
  if (typeof module !== "undefined" && module.exports) module.exports = H264Player;
})(typeof self !== "undefined" ? self : (typeof globalThis !== "undefined" ? globalThis : this));

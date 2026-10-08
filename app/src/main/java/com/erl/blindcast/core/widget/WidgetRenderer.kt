package com.erl.blindcast.core.widget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * widget 离屏渲染器（Desktop-Widget-1）。
 *
 * ## 渲染路径（与 AndroMeld 同构，全部公开 API）
 * ```
 * AppWidgetHost.createView(ctx, id, info)          // 真 View，非截图
 *   → wrapper.measure/layout(w, h)                 // 手动排版（离屏，无 window）
 *   → wrapper.draw(picture.beginRecording(w, h))   // 主线程录制成 Picture
 *   → Canvas(bitmap).drawPicture(picture)          // 后台光栅化
 *   → bitmap.compress(WEBP_LOSSY, 82)              // 后台编码
 * ```
 * 逆向样本走 `Presentation` + `VirtualDisplay` 承载（`C0137k.java:167-177`），
 * 其 ImageReader 只作占位消费者、拿到帧即丢弃（`C0133g.java:11-14`），
 * 出图同样靠上面的软件重绘。本实现省掉 VirtualDisplay 这一层：
 * widget 是**离屏 View 树**，手动 measure/layout 后直接绘制——
 * 少一个系统组件、少一处失败点，代价是 widget 内的 `onAttachedToWindow` 不会触发。
 * 若真机上发现部分 widget 渲染异常，把 [ensureRoot] 换成
 * `DisplayManager.createVirtualDisplay` + `Presentation` 即可，本类其余部分不动。
 *
 * ## 密度（`density` 参数的真实作用）
 * 逆向样本把 density 交给 `VirtualDisplay`，本实现改用
 * `ContextThemeWrapper.applyOverrideConfiguration` 覆写 `densityDpi`：
 * 视图树按 `dpi/160` 做 dp→px 换算，于是浏览器给的**像素数即布局 dp 数**
 * （dpi=160 时 400px 宽的 widget 拿到 400dp 布局空间，出图 1:1 不缩放）。
 * 这是「桌面模式」与「手机同尺寸」的分界：dpi 取屏幕原值就得到手机上的观感，
 * 取 160 就得到桌面观感。密度变化会重建视图树（dp→px 在构造时固化进 Resources）。
 *
 * ## 变化检测
 * 每次 [render] 都重新光栅化，与上次**已发送**的帧做 `Bitmap.sameAs` 比对：
 * 一致则不返回（调用方不下发），避免时钟类 widget 每秒推同一张图。
 *
 * ## 线程模型
 * - View 操作（创建/measure/layout/draw 录制/事件注入）一律切主线程（[onMain]）；
 * - 光栅化与 WebP 编码留在调用方线程（连接池线程），不占主线程；
 * - 每个 widget 一把锁串行渲染，多会话并发拉同一 widget 不会撕裂位图。
 */
object WidgetRenderer {

    private const val TAG = "BlindCast-Widget"

    /** WebP 有损质量（对齐逆向样本 `RunnableC0134h.java:85` 的 82）。 */
    private const val WEBP_QUALITY = 82

    /** 单边像素上限（防远端传超大尺寸打爆内存）。 */
    private const val MAX_DIMEN = 2048

    /** provider 未声明推荐尺寸时的兜底（dp，约 4x2 格）。 */
    private const val DEFAULT_W_DP = 250
    private const val DEFAULT_H_DP = 110

    private val mainHandler = Handler(Looper.getMainLooper())
    private val entries = ConcurrentHashMap<Int, Entry>()

    /** 离屏根容器（**永不**附着到任何 window）。 */
    @Volatile
    private var root: FrameLayout? = null

    private class Entry(val id: Int) {
        val lock = Any()

        @Volatile
        var wrapper: FrameLayout? = null

        @Volatile
        var view: AppWidgetHostView? = null

        @Volatile
        var widthPx = 0

        @Volatile
        var heightPx = 0

        @Volatile
        var densityDpi = 160

        @Volatile
        var stale = false

        /** 复用位图（每次渲染擦除重画）。 */
        var scratch: Bitmap? = null

        /** 上次已下发帧的副本（`sameAs` 比对基准）。 */
        var lastSent: Bitmap? = null
    }

    /**
     * 建（或更新）一个 widget 的离屏视图。
     * @param widthPx 目标宽（像素，1..[MAX_DIMEN]）
     * @param heightPx 目标高（像素）
     * @param densityDpi 目标密度（换算 dp 供 widget 自适应）
     * @return true = 视图就绪；false = provider 缺失（stale）或创建失败
     */
    fun ensure(id: Int, widthPx: Int, heightPx: Int, densityDpi: Int): Boolean {
        val w = widthPx.coerceIn(1, MAX_DIMEN)
        val h = heightPx.coerceIn(1, MAX_DIMEN)
        val dpi = if (densityDpi > 0) densityDpi else 160
        // 快路径：几何未变直接返回，免去渲染泵每轮的主线程往返。
        val cached = entries[id]
        if (cached != null && cached.wrapper != null &&
            cached.widthPx == w && cached.heightPx == h && cached.densityDpi == dpi
        ) {
            return true
        }
        return onMain { ensureOnMain(id, w, h, dpi) } ?: false
    }

    /**
     * 渲染为 WebP。
     * @return WebP 字节；**内容与上次下发一致时返回 null**（调用方跳过下发）；失败也返回 null。
     */
    fun render(id: Int): ByteArray? {
        val entry = entries[id] ?: return null
        if (entry.stale) return null
        val picture = onMain { recordPicture(entry) } ?: return null
        // 录制期间可能被 release（widget-hide / 解绑）：已摘除就放弃本轮，
        // 否则会在孤儿 Entry 上重建位图并永久泄漏。
        if (entries[id] !== entry) return null
        return synchronized(entry.lock) { rasterizeAndEncode(entry, picture) }
    }

    /** 移除并回收一个 widget 的视图与位图（幂等）。 */
    fun release(id: Int) {
        val entry = entries.remove(id) ?: return
        synchronized(entry.lock) {
            entry.lastSent?.recycle()
            entry.lastSent = null
            entry.scratch?.recycle()
            entry.scratch = null
        }
        onMain {
            entry.wrapper?.let { w -> root?.removeView(w) }
            entry.wrapper = null
            entry.view = null
            true
        }
    }

    /** 释放全部（停服 / 重置用）。 */
    fun releaseAll() {
        for (id in entries.keys.toList()) release(id)
    }

    fun isStale(id: Int): Boolean = entries[id]?.stale ?: false

    /**
     * provider 的推荐尺寸换算为像素（供前端首次同步时给默认值）。
     *
     * 必须按**目标密度**换算：density 覆写后视图树按 `dpi/160` 排版，
     * 用手机屏密度算出来的 px 在 dpi=160 的会话里会偏大数倍。
     * @param densityDpi 会话密度；<=0 按 160 计
     * @return (宽px, 高px)；provider 缺失返回 null
     */
    fun naturalSizePx(id: Int, densityDpi: Int): Pair<Int, Int>? {
        val info = WidgetHostManager.providerInfo(id) ?: return null
        val dpi = if (densityDpi > 0) densityDpi else 160
        val wDp = if (info.minWidth > 0) info.minWidth else DEFAULT_W_DP
        val hDp = if (info.minHeight > 0) info.minHeight else DEFAULT_H_DP
        val w = Math.round(wDp * dpi / 160f).coerceIn(1, MAX_DIMEN)
        val h = Math.round(hDp * dpi / 160f).coerceIn(1, MAX_DIMEN)
        return w to h
    }

    /**
     * 注入触摸事件（同进程 `dispatchTouchEvent`，**无需提权**——
     * 与屏幕镜像的 `InputManager.injectInputEvent` 是两条完全不同的路）。
     * @param x/y 相对该 widget 左上角的像素坐标
     */
    fun dispatchTouch(id: Int, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long): Boolean {
        return onMain {
            val wrapper = entries[id]?.wrapper ?: return@onMain false
            val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            val handled = runCatching { wrapper.dispatchTouchEvent(ev) }.getOrDefault(false)
            ev.recycle()
            handled
        } ?: false
    }

    /** 注入滚轮（`ACTION_SCROLL` + 双轴值）。 */
    fun dispatchScroll(id: Int, x: Float, y: Float, dx: Float, dy: Float, downTime: Long, eventTime: Long): Boolean {
        return onMain {
            val wrapper = entries[id]?.wrapper ?: return@onMain false
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    this.id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    setAxisValue(MotionEvent.AXIS_VSCROLL, dy)
                    setAxisValue(MotionEvent.AXIS_HSCROLL, dx)
                },
            )
            val ev = MotionEvent.obtain(
                downTime, eventTime, MotionEvent.ACTION_SCROLL, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            val handled = runCatching { wrapper.dispatchGenericMotionEvent(ev) }.getOrDefault(false)
            ev.recycle()
            handled
        } ?: false
    }

    /**
     * 探测本 build 的 widget 点击拦截 API。
     * `AppWidgetHost.setInteractionHandler` 是 Android 15+ 的 hidden API（不在公开 SDK），
     * 逆向样本靠反射调用把远端点击拦回浏览器（`C0131e.java:53-73`，失败日志
     * `"AppWidgetHost.setInteractionHandler is gone: taps would open on the phone"`）。
     * 本实现只探测并记录签名，**不接管**——未接管时点击会在手机上打开，
     * 而手机画面本来就在浏览器里镜像可见，属于可接受降级。
     */
    fun probeInteractionApi() {
        val host = WidgetHostManager.hostOrNull() ?: return
        runCatching {
            val methods = host.javaClass.methods.filter { it.name.contains("Interaction", ignoreCase = true) }
            if (methods.isEmpty()) {
                Log.i(TAG, "no AppWidgetHost interaction API on this build: widget clicks open on the phone")
            } else {
                for (m in methods) {
                    Log.i(TAG, "AppWidgetHost interaction API: ${m.name}(${m.parameterTypes.joinToString { it.name }}) -> ${m.returnType.name}")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 主线程实现
    // ------------------------------------------------------------------

    private fun ensureOnMain(id: Int, w: Int, h: Int, dpi: Int): Boolean {
        val ctx = WidgetHostManager.contextOrNull() ?: return false
        val entry = entries.getOrPut(id) { Entry(id) }
        val info = WidgetHostManager.providerInfo(id)
        if (info == null) {
            entry.stale = true
            return false
        }
        entry.stale = false
        val rootView = ensureRoot(ctx) ?: return false
        val host = WidgetHostManager.ensureHost() ?: return false

        var wrapper = entry.wrapper
        // 密度变更必须重建视图树：dp→px 换算在构造时固化进 Resources，事后改不回来。
        if (wrapper != null && entry.densityDpi != dpi) {
            rootView.removeView(wrapper)
            entry.wrapper = null
            entry.view = null
            wrapper = null
        }
        if (wrapper == null) {
            val themed = themedContext(ctx, dpi)
            wrapper = FrameLayout(themed)
            val view = runCatching { host.createView(themed, id, info) }.getOrNull()
            if (view == null) {
                Log.w(TAG, "createView failed for widget $id")
                return false
            }
            wrapper.addView(view, FrameLayout.LayoutParams(-1, -1))
            rootView.addView(wrapper)
            entry.wrapper = wrapper
            entry.view = view
            entry.densityDpi = dpi
        }

        entry.widthPx = w
        entry.heightPx = h
        val lp = wrapper.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(w, h)
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            wrapper.layoutParams = lp
        }
        // 让 widget 知道自己的尺寸（dp），RemoteViews 据此选布局分支。
        // 用 `List<SizeF>` 重载而非已废弃的 5 参版：后者会把 OPTION_APPWIDGET_SIZES 清空，
        // 而响应式 widget（如 Google 系）正是靠这个列表选多尺寸布局。
        val dpW = Math.round(w * 160f / dpi)
        val dpH = Math.round(h * 160f / dpi)
        runCatching {
            entry.view?.updateAppWidgetSize(
                Bundle(),
                listOf(android.util.SizeF(dpW.toFloat(), dpH.toFloat())),
            )
        }
        return true
    }

    /** 主线程：measure + layout + 录制为 Picture（view.draw 的返回值在离屏树上不可靠，故用 Picture）。 */
    private fun recordPicture(entry: Entry): Picture? {
        val wrapper = entry.wrapper ?: return null
        val w = entry.widthPx
        val h = entry.heightPx
        if (w <= 0 || h <= 0) return null
        return runCatching {
            wrapper.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            wrapper.layout(0, 0, w, h)
            val picture = Picture()
            wrapper.draw(picture.beginRecording(w, h))
            picture.endRecording()
            picture
        }.getOrElse {
            Log.w(TAG, "recordPicture(${entry.id}) failed: ${it.message}")
            null
        }
    }

    private fun rasterizeAndEncode(entry: Entry, picture: Picture): ByteArray? {
        val w = entry.widthPx
        val h = entry.heightPx
        if (w <= 0 || h <= 0) return null
        return runCatching {
            var bmp = entry.scratch
            if (bmp == null || bmp.width != w || bmp.height != h) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                entry.scratch = bmp
            }
            bmp.eraseColor(0)
            Canvas(bmp).drawPicture(picture)

            val last = entry.lastSent
            if (last != null && last.width == w && last.height == h && last.sameAs(bmp)) {
                return@runCatching null // 无变化：调用方跳过下发
            }
            last?.recycle()
            entry.lastSent = bmp.copy(Bitmap.Config.ARGB_8888, false)

            val out = ByteArrayOutputStream(16 * 1024)
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
            out.toByteArray()
        }.getOrElse {
            Log.w(TAG, "rasterize(${entry.id}) failed: ${it.message}")
            null
        }
    }

    private fun ensureRoot(ctx: Context): FrameLayout? {
        root?.let { return it }
        return runCatching {
            FrameLayout(themedContext(ctx, 0)).also { root = it }
        }.getOrElse {
            Log.w(TAG, "root container create failed: ${it.message}")
            null
        }
    }

    /**
     * 主题包装上下文；[dpi] > 0 时同时覆写 `densityDpi`（widget 视图树按此做 dp→px 换算）。
     *
     * 注意顺序：`ContextThemeWrapper.applyOverrideConfiguration` 一旦访问过该 wrapper 的
     * Resources 就会抛异常，所以基准配置取自**父** context，不能碰 `wrapper.resources`。
     */
    private fun themedContext(ctx: Context, dpi: Int): Context {
        val theme = runCatching { ctx.applicationInfo.theme }.getOrDefault(0)
        val fallback = android.R.style.Theme_DeviceDefault
        val base = ContextThemeWrapper(ctx, if (theme != 0) theme else fallback)
        if (dpi <= 0) return base
        return runCatching {
            val cfg = Configuration(ctx.resources.configuration)
            cfg.densityDpi = dpi
            base.applyOverrideConfiguration(cfg)
            base
        }.getOrElse {
            Log.w(TAG, "density override ($dpi) failed: ${it.message}")
            base
        }
    }

    /** 主线程执行（已在主线程则直接跑）；超时 2s 返回 null，不把连接池线程卡死。 */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val latch = CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                Log.w(TAG, "onMain block failed: ${t.message}")
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return null
        return try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "onMain timeout")
                null
            } else {
                result
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}

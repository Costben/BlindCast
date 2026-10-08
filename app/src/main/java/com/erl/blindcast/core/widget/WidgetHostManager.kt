package com.erl.blindcast.core.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * `AppWidgetHost` 封装：绑定 / 解绑 / provider 枚举（Desktop-Widget-1）。
 *
 * ## 与 AndroMeld 的对应关系
 * 逆向样本用 `AppWidgetHost(context, 16717)` + 反射 `setInteractionHandler` 把远端点击
 * 拦回浏览器；本实现同构，但只保留**公开 API**（`setInteractionHandler` 是 Android 15+
 * 的 hidden API，见 [WidgetRenderer] 的反射降级）。
 *
 * ## 权限模型（绕不过的 Android 安全模型）
 * - `allocateAppWidgetId()` / `deleteAppWidgetId()` / `createView()` 无需权限；
 * - **绑定** provider 需要本应用在系统 appwidget 白名单内：
 *   先试 `bindAppWidgetIdIfAllowed`（已在白名单时直接成功），
 *   返回 false 则发 [buildBindIntent]（`ACTION_APPWIDGET_BIND`），
 *   由用户在系统 UI 里授权一次，此后本应用长期在册；
 * - 白名单是**按应用**的，一旦授权，后续新增 widget 无需再次弹窗。
 *
 * ## 线程模型
 * - [AppWidgetHost] 在**主线程**创建（其内部 Handler 绑定创建线程的 Looper）；
 * - 本对象的绑定/枚举方法可从任意线程调用，内部按需切主线程（见 [onMain]）。
 */
object WidgetHostManager {

    private const val TAG = "BlindCast-Widget"

    /** 本应用的 host id（固定值，系统按它隔离各 host 的 widget 集合）。 */
    const val HOST_ID = 0x4243

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var host: AppWidgetHost? = null

    /** 预存上下文 + 建 host（幂等，任意线程）。 */
    fun init(context: Context) {
        val ctx = context.applicationContext ?: context
        appContext = ctx
        WidgetStore.init(ctx)
        onMain { ensureHostLocked() }
    }

    /** 应用上下文（可能 null，调用方各自退化）。 */
    fun contextOrNull(): Context? = appContext

    /** 主线程上取（或创建）host；失败返回 null。 */
    fun ensureHost(): AppWidgetHost? = onMain { ensureHostLocked() }

    fun hostOrNull(): AppWidgetHost? = host ?: ensureHost()

    /** 本机全部可用 widget provider（按展示名排序）。 */
    fun availableProviders(): List<AppWidgetProviderInfo> {
        val ctx = appContext ?: return emptyList()
        val m = AppWidgetManager.getInstance(ctx) ?: return emptyList()
        return runCatching {
            m.installedProviders.orEmpty().sortedBy { info ->
                runCatching { info.loadLabel(ctx.packageManager).toString() }.getOrDefault("")
            }
        }.getOrElse {
            Log.w(TAG, "availableProviders failed: ${it.message}")
            emptyList()
        }
    }

    /** 单个 id 的 provider 信息；provider 已卸载返回 null（调用方置 stale）。 */
    fun providerInfo(id: Int): AppWidgetProviderInfo? {
        val ctx = appContext ?: return null
        val m = AppWidgetManager.getInstance(ctx) ?: return null
        return runCatching { m.getAppWidgetInfo(id) }.getOrNull()
    }

    /** 分配一个新 id（不绑定）。 */
    fun allocate(): Int? {
        val h = hostOrNull() ?: return null
        return onMain { runCatching { h.allocateAppWidgetId() }.getOrNull() }
    }

    /** 解绑并释放 id（幂等）。 */
    fun delete(id: Int) {
        runCatching { hostOrNull()?.let { h -> onMain { h.deleteAppWidgetId(id) } } }
    }

    /**
     * 尝试直接绑定（已在系统白名单时成功）。
     * @return true = 已绑定；false = 需走 [buildBindIntent] 由用户授权。
     */
    fun bindIfAllowed(id: Int, provider: ComponentName): Boolean {
        val ctx = appContext ?: return false
        val m = AppWidgetManager.getInstance(ctx) ?: return false
        return runCatching { m.bindAppWidgetIdIfAllowed(id, provider) }.getOrDefault(false)
    }

    /** 系统授权对话框 Intent（`ACTION_APPWIDGET_BIND`，必须从 Activity 启动）。 */
    fun buildBindIntent(id: Int, provider: ComponentName): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)

    /** provider 的展示名（provider 卸载后由调用方回退到 [WidgetStore.Record.label]）。 */
    fun labelOf(info: AppWidgetProviderInfo?): String {
        if (info == null) return ""
        val ctx = appContext ?: return ""
        return runCatching { info.loadLabel(ctx.packageManager).toString() }.getOrDefault("")
    }

    /** provider 组件名的字符串形式（落 [WidgetStore] 用）。 */
    fun componentString(info: AppWidgetProviderInfo?): String {
        val cn = info?.provider ?: return ""
        return runCatching { cn.flattenToShortString() }.getOrDefault(cn.flattenToString())
    }

    // ------------------------------------------------------------------

    private fun ensureHostLocked(): AppWidgetHost? {
        host?.let { return it }
        val ctx = appContext ?: return null
        return runCatching {
            AppWidgetHost(ctx, HOST_ID).also {
                it.startListening()
                host = it
            }
        }.getOrElse {
            Log.w(TAG, "AppWidgetHost create failed: ${it.message}")
            null
        }
    }

    /**
     * 主线程执行（已在主线程则直接跑）。
     * 超时 2s 兜底返回 null，避免调用方（连接池线程）被主线程卡死。
     */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                result = block()
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return null
        return try {
            if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
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

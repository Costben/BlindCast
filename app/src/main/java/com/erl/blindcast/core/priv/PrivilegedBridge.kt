package com.erl.blindcast.core.priv

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.erl.blindcast.BuildConfig
import com.erl.blindcast.core.blackout.PowerController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * Shizuku 特权桥（Priv-Bridge-1 · 熄屏/抓屏/注入统一提权入口）。
 *
 * 背景：[com.erl.blindcast.core.blackout.PowerController] 在普通应用进程直接反射
 * `setDisplayPowerMode` 必吃 SecurityException（需系统签名 / CONTROL_DISPLAY），
 * 因此一切特权操作必须跑在 Shizuku UserService 进程（root UID 0 / shell UID 2000）内。
 *
 * ## 三路径（调用方必读）
 * 1. **Shizuku 已授权（主路径）**：[isPrivilegedGranted] 为 true 时，
 *    [withPrivileged] 绑定 [PrivilegedUserService] 并在特权进程内执行 [block]，
 *    调用完解绑 + 销毁特权进程（`destroy()` → `System.exit(0)`，防特权进程泄漏）。
 * 2. **未授权（报错引导）**：Shizuku 未运行 / 未授权时，[withPrivileged] 直接抛
 *    [IllegalStateException]（文案见 [REQUIRE_SHIZUKU_MESSAGE]，引导用户去 Shizuku
 *    管理器启动服务并授权 BlindCast），绝不静默吞错；
 *    [PowerController][com.erl.blindcast.core.blackout.PowerController] 的 routed
 *    入口负责把该异常记入 `lastError` 并返回 false，供 UI 弹 Toast。
 * 3. **Root 直跑（后续 Slice，注释预留）**：设备有 su / KernelSU 时，可不经 Shizuku、
 *    直接以 root 身份 `app_process` 拉起同一 [PrivilegedUserService] 类（或经 su
 *    透传 binder 调用）。本 Slice 不实现，仅保证 [IPrivilegedOps] 契约与
 *    [PrivilegedUserService] 实现均不依赖 Shizuku 特有类型，可被 Root 宿主复用。
 *    // TODO(priv-bridge-next): Root 直跑宿主（su --mount-master 下 app_process 入口）。
 *
 * ## Shizuku 状态机封装
 * - 存活：[isShizukuRunning]（[Shizuku.pingBinder]，binder 未就绪抛异常时按 false 计）。
 * - 鉴权：[isPrivilegedGranted]（存活 + `checkSelfPermission() == PERMISSION_GRANTED`；
 *   注意 `checkSelfPermission()` 在 binder 未就绪时会抛 IllegalStateException，内部已吞错）。
 * - 四态：[shizukuState]（NOT_RUNNING / RUNNING_UNAUTHORIZED / GRANTED，供权限页授权行展示
 *   “未运行 / 运行中 / 已授权 / 未授权”）。
 * - 申请（应用内一键授权，Priv-Bridge-2）：[awaitPermission] 注册一次性监听 → 发起申请 →
 *   挂起等结果，Shizuku 授权弹窗自动弹出，用户点允许即回 true；权限页授权行与
 *   `HomeViewModel.blackoutNow`（daemon 在跑但未授权时自动走一次）均调此入口。
 *   底层另透传 [requestPermission] + [addRequestPermissionResultListener] /
 *   [removeRequestPermissionResultListener]（调用方按 Android 运行时权限范式处理：
 *   GRANTED 继续绑定，`shouldShowRequestPermissionRationale()` 为 true 说明用户勾了
 *   “拒绝并不再询问”，需引导去 Shizuku 管理器手动开）。
 * - 绑定/解绑：[withPrivileged] 按次绑定（`daemon(false)`，用完即焚，不留常驻特权进程）。
 *
 * 线程：所有 binder/绑定调用都在 [Dispatchers.IO] 上执行，可在任意调度器上调用；
 * `onServiceConnected` 回调线程由 Shizuku 决定，恢复协程是线程安全的.
 */
object PrivilegedBridge {

    /** 全链路统一 TAG（与 SurfaceControl / PowerController / HomeViewModel 一致）。 */
    private const val TAG = "BlindCast"

    private fun tid(): String {
        val t = Thread.currentThread()
        return "t=${t.id}(${t.name})"
    }

    /**
     * 未授权引导文案（[withPrivileged] 抛错 / PowerController.lastError 共用同一文案，
     * Home 侧直接展示）。
     */
    const val REQUIRE_SHIZUKU_MESSAGE =
        "需要 Shizuku 授权（去 Shizuku 管理器启动服务并授权 BlindCast）"

    /** Shizuku 未运行引导文案（daemon 没起来时与未授权区分提示）。 */
    const val SHIZUKU_NOT_RUNNING_MESSAGE =
        "Shizuku 服务未运行（去 Shizuku 管理器启动服务后再试）"

    /** `Shizuku.requestPermission(code)` 默认请求码（0~65535 业务自定义区）。 */
    const val PERMISSION_REQUEST_CODE = 0xB11C

    /** UserService 进程名后缀（最终进程名如 `com.erl.blindcast:privileged`）。 */
    private const val PROCESS_SUFFIX = "privileged"

    /** 单次绑定等待超时（特权进程冷启动含 app_process 拉起，留足余量）。 */
    private const val BIND_TIMEOUT_MS = 15_000L

    /** 单次鉴权等待超时（需用户在 Shizuku 管理器侧点允许）。 */
    private const val PERMISSION_TIMEOUT_MS = 60_000L

    /** Shizuku 管理器包名（即 [ShizukuProvider.MANAGER_APPLICATION_ID]，供诊断/跳转使用）。 */
    const val SHIZUKU_MANAGER_PACKAGE: String = ShizukuProvider.MANAGER_APPLICATION_ID

    /**
     * 组装本次绑定的 UserService 参数（每次新建，ComponentName 必须指向 [PrivilegedUserService]）。
     *
     * @param packageName 调用方包名（传 `context.packageName`，勿硬编码，防 applicationIdSuffix 变体）。
     */
    fun userServiceArgs(packageName: String): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(packageName, PrivilegedUserService::class.java.name))
            .daemon(false)
            .processNameSuffix(PROCESS_SUFFIX)
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    /** Shizuku binder 是否存活（daemon 是否运行）。binder 未就绪抛异常时按 false 计。 */
    fun isShizukuRunning(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

    /**
     * 特权是否可用：binder 存活 **且** Shizuku 侧已授权。
     * `checkSelfPermission()` 在 binder 未就绪时会抛 IllegalStateException，内部已吞错计为 false。
     */
    fun isPrivilegedGranted(): Boolean {
        if (!isShizukuRunning()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Shizuku 四态（Priv-Bridge-2 · 权限页“Shizuku 授权”行展示用）。
     * - [NOT_RUNNING]：daemon 未运行（未启动 Shizuku）→ 点行弹 Toast 引导启动；
     * - [RUNNING_UNAUTHORIZED]：运行中但未授权 → 点行调 [awaitPermission] 弹系统授权框；
     * - [GRANTED]：已授权 → 点行无操作（仅刷新）。
     */
    enum class ShizukuState {
        NOT_RUNNING,
        RUNNING_UNAUTHORIZED,
        GRANTED,
    }

    /** 读取当前 Shizuku 四态（同步，两次 binder 查询，均吞错计为未运行/未授权）。 */
    fun shizukuState(): ShizukuState {
        if (!isShizukuRunning()) return ShizukuState.NOT_RUNNING
        return if (isPrivilegedGranted()) ShizukuState.GRANTED else ShizukuState.RUNNING_UNAUTHORIZED
    }

    /**
     * 发起 Shizuku 授权申请（异步，结果经 [Shizuku.OnRequestPermissionResultListener] 回调）。
     * 调用前建议先 [isShizukuRunning]，未运行则申请无响应。
     */
    fun requestPermission(code: Int = PERMISSION_REQUEST_CODE) {
        Shizuku.requestPermission(code)
    }

    /** 透传：监听授权结果（记得配对移除，参考 Shizuku-API demo 范式）。 */
    fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    /** 透传：移除授权结果监听。 */
    fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    /**
     * 等待用户完成一次授权申请：注册一次性监听 → 发起申请 → 挂起等结果。
     *
     * @return true = 用户点了允许；false = 拒绝 / 超时（[PERMISSION_TIMEOUT_MS]）/ Shizuku 未运行。
     */
    suspend fun awaitPermission(code: Int = PERMISSION_REQUEST_CODE): Boolean =
        withContext(Dispatchers.IO) {
            if (!isShizukuRunning()) return@withContext false
            if (isPrivilegedGranted()) return@withContext true
            val done = CompletableDeferred<Boolean>()
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != code) return
                    done.complete(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            addRequestPermissionResultListener(listener)
            try {
                try {
                    requestPermission(code)
                } catch (t: Throwable) {
                    done.completeExceptionally(t)
                }
                val granted = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { done.await() } ?: false
                if (granted) true else isPrivilegedGranted()
            } finally {
                runCatching { removeRequestPermissionResultListener(listener) }
            }
        }

    /**
     * 在特权进程内执行 [block]（按次绑定，用完即焚）。
     *
     * 前置检查失败直接抛 [IllegalStateException]（未运行 / 未授权，文案可直接展示给用户）；
     * 绑定超时（[BIND_TIMEOUT_MS]）抛 [IllegalStateException]；[block] 内异常原样上抛。
     * 无论成功失败，最终都会 `destroy()` 特权进程并 `unbindUserService`（均吞错，不掩盖主异常）。
     *
     * @param packageName 调用方包名（`context.packageName`）。
     * @param block 特权操作（运行在 IO 线程，参数为跨进程 [IPrivilegedOps]，binder 死亡时抛
     *   DeadObjectException / RemoteException，由调用方按失败计）。
     */
    suspend fun <T> withPrivileged(packageName: String, block: (IPrivilegedOps) -> T): T =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "[PrivilegedBridge] ${tid()} withPrivileged enter")
            if (!isShizukuRunning()) {
                Log.d(TAG, "[PrivilegedBridge] ${tid()} withPrivileged abort not_running")
                throw IllegalStateException(SHIZUKU_NOT_RUNNING_MESSAGE)
            }
            if (!isPrivilegedGranted()) {
                Log.d(TAG, "[PrivilegedBridge] ${tid()} withPrivileged abort unauthorized")
                throw IllegalStateException(REQUIRE_SHIZUKU_MESSAGE)
            }
            val args = userServiceArgs(packageName)
            Log.d(TAG, "[PrivilegedBridge] ${tid()} withPrivileged bind start")
            // bindOps 内部含 BIND_TIMEOUT_MS 超时 + 失败解绑（超时抛 IllegalStateException）。
            val bound = try {
                bindOps(args)
            } catch (t: Throwable) {
                Log.e(TAG, "[PrivilegedBridge] ${tid()} withPrivileged bind failed", t)
                throw t
            }
            Log.d(TAG, "[PrivilegedBridge] ${tid()} withPrivileged bind ok, block start")
            try {
                val result = block(bound.ops)
                Log.d(TAG, "[PrivilegedBridge] ${tid()} withPrivileged block ok " +
                    "resultType=${result?.let { it::class.java.simpleName } ?: "null"}")
                result
            } catch (t: Throwable) {
                Log.e(TAG, "[PrivilegedBridge] ${tid()} withPrivileged block failed", t)
                throw t
            } finally {
                // 用完即焚：先让特权进程自杀（DeadObjectException 属预期，吞掉），再解绑（吞错，不掩盖主异常）。
                val destroyErr = runCatching { bound.ops.destroy() }.exceptionOrNull()
                Log.d(TAG, "[PrivilegedBridge] ${tid()} withPrivileged destroy " +
                    "err=${destroyErr?.toString() ?: "none"}")
                val unbindErr = runCatching { Shizuku.unbindUserService(args, bound.conn, true) }
                    .exceptionOrNull()
                Log.d(TAG, "[PrivilegedBridge] ${tid()} withPrivileged unbind " +
                    "err=${unbindErr?.toString() ?: "none"}")
            }
        }

    /**
     * 特权熄屏/点亮快捷入口（[withPrivileged] 特化，包名由调用方传入）。
     *
     * Priv-Bridge-7：服务端已含验效 + 按键兜底，返回值即验效后最终结果。
     *
     * @return 特权进程内验效后最终结果。
     */
    suspend fun setDisplayPower(packageName: String, on: Boolean): Boolean {
        Log.d(TAG, "[PrivilegedBridge] ${tid()} setDisplayPower enter on=$on")
        val ok = withPrivileged(packageName) { ops -> ops.setDisplayPower(on) }
        Log.d(TAG, "[PrivilegedBridge] ${tid()} setDisplayPower exit on=$on ok=$ok")
        return ok
    }

    /**
     * 特权熄屏/点亮详细入口（Priv-Bridge-7 · 单次绑定内取回失败明细）。
     *
     * 用完即焚：[withPrivileged] 每次新建特权进程，静态 lastError 跨绑定清零，
     * 故必须同一次 [withPrivileged] 内先调 [IPrivilegedOps.setDisplayPower]、
     * 失败再调 [IPrivilegedOps.getLastError]，否则明细丢失。
     * [PowerController.setDisplayPowerRouted][com.erl.blindcast.core.blackout.PowerController]
     * 走本入口，失败文案（含 binder→验效→已试按键步骤）进 lastError/状态行/Toast，契约不变。
     *
     * @return first = 验效后最终结果；second = 失败明细（成功时 null）。
     */
    suspend fun setDisplayPowerDetailed(packageName: String, on: Boolean): Pair<Boolean, String?> {
        Log.d(TAG, "[PrivilegedBridge] ${tid()} setDisplayPowerDetailed enter on=$on")
        val result = withPrivileged(packageName) { ops ->
            val ok = ops.setDisplayPower(on)
            var err: String? = null
            if (!ok) {
                err = runCatching { ops.lastError }.getOrNull()
                Log.d(TAG, "[PrivilegedBridge] ${tid()} setDisplayPowerDetailed " +
                    "ok=false privErr=${err?.take(200)}")
            }
            ok to err
        }
        Log.d(TAG, "[PrivilegedBridge] ${tid()} setDisplayPowerDetailed exit on=$on " +
            "ok=${result.first} hasErr=${result.second != null}")
        return result
    }

    /**
     * 按键兜底直调：熄屏（Priv-Bridge-7 · 特权进程内 KEYCODE_SLEEP + 验 STATE_OFF）。
     *
     * @return 验效通过 true，否则 false。
     */
    suspend fun sleepByKey(packageName: String): Boolean {
        Log.d(TAG, "[PrivilegedBridge] ${tid()} sleepByKey enter")
        val ok = withPrivileged(packageName) { ops -> ops.sleepByKey() }
        Log.d(TAG, "[PrivilegedBridge] ${tid()} sleepByKey exit ok=$ok")
        return ok
    }

    /**
     * 按键兜底直调：点亮（Priv-Bridge-7 · 特权进程内 KEYCODE_WAKEUP→KEYCODE_POWER + 验 STATE_ON）。
     *
     * @return 验效通过 true，否则 false。
     */
    suspend fun wakeByKey(packageName: String): Boolean {
        Log.d(TAG, "[PrivilegedBridge] ${tid()} wakeByKey enter")
        val ok = withPrivileged(packageName) { ops -> ops.wakeByKey() }
        Log.d(TAG, "[PrivilegedBridge] ${tid()} wakeByKey exit ok=$ok")
        return ok
    }

    /**
     * 取特权进程侧最近一次失败明细（须同绑定内调用， standalone 排障用）。
     *
     * @return 失败文案，成功/无记录时 null。
     */
    suspend fun lastPrivError(packageName: String): String? {
        return withPrivileged(packageName) { ops -> runCatching { ops.lastError }.getOrNull() }
    }

    // ------------------------------------------------------------------
    // 反控注入快捷入口（ControlWsRoute 专用 · Root优先→Shizuku→无路引导）
    // ------------------------------------------------------------------

    /**
     * Universal-1 反控两段路由共用：先 Root 单次 `app_process`（无 Shizuku 可用兜底，
     * 单次冷起约 1-2s 可接受），失败再走 Shizuku 按次绑定，均失败返回组合引导文案。
     *
     * @param packageName 调用方包名（`context.packageName`，Root 段拼 CLASSPATH 用，勿硬编码）。
     * @param subOp RootMain input 子操作（tap|drag|key|text）。
     * @param params 子操作参数（tap:[x,y]；drag:[x0,y0,x1,y1]；key:[code]；text:[b64]）。
     * @param shizukuBlock Shizuku 段执行体（按次绑定内原子注入，返回 ok to err）。
     * @return first=是否成功；second=失败明细（成功时 null，为 Root 段 + Shizuku 段组合文案）。
     */
    private suspend fun injectRouted(
        packageName: String,
        subOp: String,
        params: List<String>,
        shizukuBlock: suspend () -> Pair<Boolean, String?>,
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        // Smooth-1 常驻 daemon 段（首选；单次冷起约 1-2s → 常驻 ack 约数十 ms）。
        // 存活时一次往返即注入；不可用记文案并回退既有单次 Root→Shizuku 两段（逻辑不动）。
        try {
            val (daemonOk, daemonErr, latencyMs) = tryDaemonInput(packageName, subOp, params)
            Log.d(TAG, "[PrivilegedBridge] ${tid()} injectRouted sub=$subOp daemonOk=$daemonOk " +
                "latencyMs=$latencyMs err=${daemonErr?.take(200)}")
            if (daemonOk) return@withContext true to null
            // daemon 失败：文案并入 rootNote，与单次段组合（调用方据最终文案排障）。
            val daemonNote: String? = daemonErr?.takeIf { it.isNotBlank() }
                ?.let { "常驻daemon段失败（${it}，延迟${latencyMs}ms）" }
                ?: "常驻daemon段不可用"
            // Root 段（既有单次 app_process；跳过/失败记文案并回退 Shizuku，既有 Shizuku 逻辑不动）。
            var rootNote: String? = daemonNote
            var rootOk = false
            try {
                val rootRes = tryRootInput(packageName, subOp, params)
                rootOk = rootRes.first
                rootNote = combineInputError(daemonNote, rootRes.second)
                Log.d(TAG, "[PrivilegedBridge] ${tid()} injectRouted sub=$subOp rootOk=$rootOk rootNote=${rootNote?.take(200)}")
                if (rootOk) return@withContext true to null
            } catch (t: Throwable) {
                rootNote = combineInputError(daemonNote, "Root段异常（${t.message ?: t}）")
                Log.e(TAG, "[PrivilegedBridge] ${tid()} injectRouted sub=$subOp root threw", t)
            }
            // Shizuku 段（既有按次绑定用完即焚不动；未运行/未授权抛引导文案，记入组合）。
            try {
                val (ok, err) = shizukuBlock()
                if (ok) return@withContext true to null
                val shizukuPart = err?.takeIf { it.isNotBlank() } ?: "Shizuku段执行失败（返回 false）"
                return@withContext false to combineInputError(rootNote, "Shizuku段失败：$shizukuPart")
            } catch (t: Throwable) {
                val base = t.message ?: t.toString()
                // withPrivileged 的未运行/未授权引导文案原样透出（调用方直接展示），仅前拼 Root 段。
                val msg = combineInputError(rootNote, base)
                Log.d(TAG, "[PrivilegedBridge] ${tid()} injectRouted sub=$subOp shizuku threw msg=${msg.take(200)}")
                return@withContext false to msg
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedBridge] ${tid()} injectRouted sub=$subOp daemon threw", t)
            // daemon 段抛异常同样回退老路（不掀翻反控）：按单次 Root→Shizuku 重走简化版。
            return@withContext shizukuBlock()
        }
    }

    /**
     * 试常驻 daemon 段单次 input（失败不抛，只记文案供组合；成功含 ack 延迟）。
     * @return Triple(ok, err, latencyMs)：latencyMs 为发→ack 回包耗时（daemon 不可用时 -1）。
     */
    private suspend fun tryDaemonInput(
        packageName: String,
        subOp: String,
        params: List<String>,
    ): Triple<Boolean, String?, Long> = withContext(Dispatchers.IO) {
        try {
            val res = when (subOp) {
                "tap" -> RootInputDaemon.tap(packageName, params[0].toFloat(), params[1].toFloat())
                "drag" -> RootInputDaemon.drag(
                    packageName,
                    params[0].toFloat(), params[1].toFloat(), params[2].toFloat(), params[3].toFloat(),
                )
                "key" -> RootInputDaemon.key(packageName, params[0].toInt())
                "text" -> RootInputDaemon.text(packageName, params[0])
                else -> return@withContext Triple(false, "非法 input 子操作：$subOp", -1L)
            }
            Triple(res.first, res.second, res.third)
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedBridge] ${tid()} tryDaemonInput threw", t)
            Triple(false, "常驻daemon段异常（${t.message ?: t}）", -1L)
        }
    }

    /**
     * 试 Root 段单次 input（失败不抛，只记文案供组合）。
     * @return first=Root 段是否成功；second=Root 段文案（成功时 null）。
     */
    private suspend fun tryRootInput(
        packageName: String,
        subOp: String,
        params: List<String>,
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val available: Boolean = try {
            RootExecutor.isRootAvailable()
        } catch (_: Throwable) {
            false
        }
        Log.d(TAG, "[PrivilegedBridge] ${tid()} tryRootInput sub=$subOp available=$available")
        if (!available) {
            return@withContext false to "Root段不可用（无su/未授权，去KernelSU管理器点允许BlindCast）"
        }
        val apkPath: String? = try {
            PowerController.resolveApkPath(packageName)
        } catch (_: Throwable) {
            null
        }
        if (apkPath.isNullOrBlank()) {
            Log.d(TAG, "[PrivilegedBridge] ${tid()} tryRootInput skip no apkPath pkg=$packageName")
            return@withContext false to "Root段跳过（取APK路径失败）"
        }
        try {
            val (ok, err) = RootExecutor.runAsRootInput(packageName, apkPath, subOp, params)
            Log.d(TAG, "[PrivilegedBridge] ${tid()} tryRootInput done sub=$subOp ok=$ok err=${err?.take(200)}")
            if (ok) true to null else false to (err?.takeIf { !it.isNullOrBlank() } ?: "Root段已试失败（见logcat [RootExecutor]/[RootMain]明细）")
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedBridge] ${tid()} tryRootInput run threw", t)
            false to "Root段异常（${t.message ?: t}）"
        }
    }

    /** 组合最终失败文案（Root 段 + Shizuku 段，任一为空取另一段）。 */
    private fun combineInputError(rootNote: String?, shizukuPart: String?): String {
        val root = rootNote?.takeIf { it.isNotBlank() }
        val shizuku = shizukuPart?.takeIf { it.isNotBlank() }
        return when {
            root != null && shizuku != null -> "$root；$shizuku"
            root != null -> root
            shizuku != null -> shizuku
            else -> "反控注入失败（未知原因）"
        }
    }

    /**
     * 特权轻点（Down+Up 原子；归一化坐标相对真实主屏）。
     * @return first=是否成功；second=失败明细（成功时 null）。
     */
    suspend fun injectTap(packageName: String, x: Float, y: Float): Pair<Boolean, String?> =
        injectRouted(packageName, "tap", listOf(x.toString(), y.toString())) {
            withPrivileged(packageName) { ops ->
                val ok = ops.injectTap(x, y)
                ok to (if (!ok) runCatching { ops.inputError }.getOrNull() else null)
            }
        }

    /**
     * 特权拖拽（Down+插值Move+Up 单次调用内完成，松手执行）。
     * @return 同 [injectTap]。
     */
    suspend fun injectDrag(
        packageName: String,
        x0: Float, y0: Float, x1: Float, y1: Float,
    ): Pair<Boolean, String?> =
        injectRouted(packageName, "drag", listOf(x0.toString(), y0.toString(), x1.toString(), y1.toString())) {
            withPrivileged(packageName) { ops ->
                val ok = ops.injectDrag(x0, y0, x1, y1)
                ok to (if (!ok) runCatching { ops.inputError }.getOrNull() else null)
            }
        }

    /** 特权完整按键 Down+Up（无状态）。@return 同 [injectTap]。 */
    suspend fun injectKey(packageName: String, keyCode: Int): Pair<Boolean, String?> =
        injectRouted(packageName, "key", listOf(keyCode.toString())) {
            withPrivileged(packageName) { ops ->
                val ok = ops.injectKey(keyCode)
                ok to (if (!ok) runCatching { ops.inputError }.getOrNull() else null)
            }
        }

    /** 特权文本注入（虚拟键盘映射；无状态）。@return 同 [injectTap]。 */
    suspend fun injectText(packageName: String, text: String): Pair<Boolean, String?> {
        val b64 = try {
            android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            ""
        }
        return injectRouted(packageName, "text", listOf(b64)) {
            withPrivileged(packageName) { ops ->
                val ok = ops.injectText(text)
                ok to (if (!ok) runCatching { ops.inputError }.getOrNull() else null)
            }
        }
    }

    // ------------------------------------------------------------------
    // 实时跟手三件套（Smooth-1 · 常驻 daemon 直透，无单次/Shizuku 回退；
    // Shizuku 按次绑定无状态、无 AIDL down/move/up 编号，故 daemon 不可用时
    // 调用方（ControlWsRoute）回退 pending 缓存 + up 时原子 tap/drag）。
    // ------------------------------------------------------------------

    /** 实时按下（常驻 daemon down，跨指令保持手势；失败调用方回退批量）。 */
    suspend fun injectDown(packageName: String, x: Float, y: Float): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                val (ok, err, latencyMs) = RootInputDaemon.down(packageName, x, y)
                Log.d(TAG, "[PrivilegedBridge] ${tid()} injectDown ok=$ok latencyMs=$latencyMs err=${err?.take(200)}")
                if (ok) true to null else false to err
            } catch (t: Throwable) {
                Log.e(TAG, "[PrivilegedBridge] ${tid()} injectDown threw", t)
                false to (t.message ?: t.toString())
            }
        }

    /** 实时移动（常驻 daemon move 直透；失败调用方降级批量 + cancel 解卡）。 */
    suspend fun injectMove(packageName: String, x: Float, y: Float): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                val (ok, err, _) = RootInputDaemon.move(packageName, x, y)
                if (!ok) {
                    Log.d(TAG, "[PrivilegedBridge] ${tid()} injectMove miss err=${err?.take(200)}")
                }
                if (ok) true to null else false to err
            } catch (t: Throwable) {
                Log.e(TAG, "[PrivilegedBridge] ${tid()} injectMove threw", t)
                false to (t.message ?: t.toString())
            }
        }

    /** 实时抬起（常驻 daemon up 结束手势）。 */
    suspend fun injectUp(packageName: String, x: Float, y: Float): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                val (ok, err, latencyMs) = RootInputDaemon.up(packageName, x, y)
                Log.d(TAG, "[PrivilegedBridge] ${tid()} injectUp ok=$ok latencyMs=$latencyMs err=${err?.take(200)}")
                if (ok) true to null else false to err
            } catch (t: Throwable) {
                Log.e(TAG, "[PrivilegedBridge] ${tid()} injectUp threw", t)
                false to (t.message ?: t.toString())
            }
        }

    /** 解卡（常驻 daemon cancel，best-effort 恒 true；断连/降级时调）。 */
    suspend fun cancelInput(): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            try {
                RootInputDaemon.cancel()
            } catch (t: Throwable) {
                Log.e(TAG, "[PrivilegedBridge] cancelInput threw", t)
                true to null
            }
        }

    // ------------------------------------------------------------------
    // 内部：单次绑定
    // ------------------------------------------------------------------

    private class BoundOps(
        val conn: ServiceConnection,
        val ops: IPrivilegedOps,
    )

    private suspend fun bindOps(args: Shizuku.UserServiceArgs): BoundOps {
        Log.d(TAG, "[PrivilegedBridge] ${tid()} bindOps start timeoutMs=$BIND_TIMEOUT_MS")
        val done = CompletableDeferred<BoundOps>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val ping = binder != null && runCatching { binder.pingBinder() }.getOrDefault(false)
                Log.d(TAG, "[PrivilegedBridge] ${tid()} onServiceConnected " +
                    "binderNull=${binder == null} ping=$ping")
                if (ping) {
                    done.complete(BoundOps(this, IPrivilegedOps.Stub.asInterface(binder)))
                } else {
                    done.completeExceptionally(IllegalStateException("特权服务 binder 无效（ping 失败）"))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // 已建连后的断开由 block 侧 binder 异常体现；等待中建连失败才走这里
                //（建连成功后 complete 已返回 false，此调用无副作用）。
                Log.d(TAG, "[PrivilegedBridge] ${tid()} onServiceDisconnected")
                done.completeExceptionally(IllegalStateException("特权服务连接断开"))
            }
        }
        try {
            Shizuku.bindUserService(args, conn)
            Log.d(TAG, "[PrivilegedBridge] ${tid()} bindUserService called")
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedBridge] ${tid()} bindUserService threw", t)
            done.completeExceptionally(t)
        }
        try {
            val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) { done.await() }
                ?: throw IllegalStateException("绑定特权服务超时（${BIND_TIMEOUT_MS}ms），请确认 Shizuku 运行正常后重试")
            Log.d(TAG, "[PrivilegedBridge] ${tid()} bindOps ok")
            return bound
        } catch (e: Throwable) {
            Log.e(TAG, "[PrivilegedBridge] ${tid()} bindOps failed", e)
            // 超时 / 绑定失败 / 外部取消：一律解绑防泄漏，再原样上抛（CancellationException 不吞，保协程语义）。
            runCatching { Shizuku.unbindUserService(args, conn, true) }
            throw e
        }
    }
}

package com.erl.blindcast.core.priv

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.util.Log
import com.erl.blindcast.core.blackout.PowerController
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Root 常驻输入 daemon 的 App 侧客户端（Smooth-1 · 随前台 Service 启停）。
 *
 * - [ensureStarted]：探活（`ping` ack）→ 存活直接复用；否则经 libsu root shell
 *   后台拉起 [RootInputMain]（`&` 常驻，stop 文件固定同名），轮询探活至多约 3s；
 * - [send]：一连接一行指令 → 一行 ack（connect/读写均有超时，超时按失败计），
 *   返回 ack 延迟毫秒（任务包 tap 延迟以 ack 回包计时为准）；
 * - [stopAsync]：`touch` stop 文件停 daemon（停服时调，fire-and-forget 不阻塞调用方）；
 * - 无 su/未授权/拉起失败一律返回 false + 文案，不抛（调用方回退单次 Root/Shizuku 老路）。
 *
 * 线程：均为阻塞 IO，suspend 版切 Dispatchers.IO；[stopAsync] 任意线程。
 * 单文件零依赖（只用 libsu 同步 Shell.cmd + LocalSocket，与 RootExecutor 同风格）。
 */
object RootInputDaemon {

    private const val TAG = "BlindCast"

    /** 抽象 socket 名（与 [RootInputMain.DEFAULT_SOCKET_NAME] 同值）。 */
    const val SOCKET_NAME = "blindcast_input"

    /** stop 文件（固定同名：新进程可停旧 daemon、可复用旧 daemon，随服务启停）。 */
    private const val STOP_FILE = "/data/local/tmp/blindcast_input_stop"

    /** 探活单次超时 600ms（daemon 存活时 ping 回包约 <50ms）。 */
    private const val PING_TIMEOUT_MS = 600

    /** 指令单次超时 2s（注入为单次 binder 往返，远小于单次 app_process 冷起）。 */
    private const val CMD_TIMEOUT_MS = 2_000

    /** 拉起后等 daemon 就绪轮询：间隔 200ms，至多约 3s。 */
    private const val START_POLL_INTERVAL_MS = 200L
    private const val START_POLL_ROUNDS = 15

    /** 停服 touch 后等 daemon 退出再清文件（daemon 侧轮询 500ms，宽限覆盖 ≥2 周期）。 */
    private const val STOP_GRACE_MS = 1_200L

    /**
     * 确保 daemon 存活（探活复用 administrivia，失败则拉起）。
     * @param packageName 调用方包名（拼 CLASSPATH 的 APK 解析用，勿硬编码）。
     * @return true = ping 通；false = 无 su/拉起失败（明细进 logcat，调用方回退老路）。
     */
    suspend fun ensureStarted(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (ping(PING_TIMEOUT_MS)) {
            return@withContext true
        }
        val available = try {
            RootExecutor.isRootAvailable()
        } catch (_: Throwable) {
            false
        }
        if (!available) {
            Log.d(TAG, "[RootInputDaemon] ensure skip no-root")
            return@withContext false
        }
        val apkPath = try {
            PowerController.resolveApkPath(packageName)
        } catch (_: Throwable) {
            null
        }
        if (apkPath.isNullOrBlank()) {
            Log.d(TAG, "[RootInputDaemon] ensure skip no apkPath pkg=$packageName")
            return@withContext false
        }
        // 陈旧 stop 先清（防上次崩溃残留导致新 daemon 秒退），再后台拉起。
        runCatching { Shell.cmd("rm -f $STOP_FILE").exec() }
        val cmd =
            "CLASSPATH=$apkPath app_process /system/bin com.erl.blindcast.core.priv.RootInputMain " +
                "daemon $STOP_FILE $SOCKET_NAME &"
        val launched = try {
            runCatching { Shell.cmd(cmd).exec() }.getOrNull() != null
        } catch (t: Throwable) {
            Log.e(TAG, "[RootInputDaemon] launch threw", t)
            false
        }
        Log.i(TAG, "[RootInputDaemon] launch cmd sent ok=$launched")
        repeat(START_POLL_ROUNDS) {
            if (ping(PING_TIMEOUT_MS)) {
                Log.i(TAG, "[RootInputDaemon] ensure ok (round=$it)")
                // 新拉起的 daemon 注入链是冷的（首次 tap 约 600ms+）：立即 warm 一次
                // （无副作用），把冷初始化摊在服务起/daemon 拉起时，tap 常态 ≤300ms。
                val (warmOk, warmErr) = sendBlocking("warm", CMD_TIMEOUT_MS)
                Log.i(TAG, "[RootInputDaemon] warm after launch ok=$warmOk err=${warmErr?.take(120)}")
                return@withContext true
            }
            try {
                Thread.sleep(START_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@withContext false
            }
        }
        Log.w(TAG, "[RootInputDaemon] ensure timeout (daemon not pong)")
        false
    }

    /** 停 daemon（fire-and-forget：另起线程 touch + grace + rm，不阻塞调用方）。 */
    fun stopAsync() {
        val t = Thread({
            try {
                Shell.cmd("touch $STOP_FILE").exec()
            } catch (_: Throwable) {
            }
            try {
                Thread.sleep(STOP_GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            runCatching { Shell.cmd("rm -f $STOP_FILE").exec() }
            Log.i(TAG, "[RootInputDaemon] stop signaled file=$STOP_FILE")
        }, "BlindCast-RootInputStop")
        t.isDaemon = true
        t.start()
    }

    /**
     * 发一行指令并等 ack。
     * @param line 指令行（不含换行）。
     * @param timeoutMs 单次超时（含 connect + 写 + 读 ack）。
     * @return Triple(ok, err, latencyMs)：成功时 err=null；latencyMs 为发→ack 回包耗时。
     */
    suspend fun send(line: String, timeoutMs: Int = CMD_TIMEOUT_MS): Triple<Boolean, String?, Long> =
        withContext(Dispatchers.IO) {
            val start = SystemClock.uptimeMillis()
            val res = sendBlocking(line, timeoutMs)
            val latency = SystemClock.uptimeMillis() - start
            Triple(res.first, res.second, latency)
        }

    // ---- 类型化便捷入口（ensure + send，失败回退由调用方 PrivilegedBridge 决定） ----

    suspend fun tap(packageName: String, x: Float, y: Float): Triple<Boolean, String?, Long> {
        if (!ensureStarted(packageName)) {
            return Triple(false, "常驻输入daemon不可用（无su/拉起失败）", -1L)
        }
        return send("tap $x $y")
    }

    suspend fun down(packageName: String, x: Float, y: Float): Triple<Boolean, String?, Long> {
        if (!ensureStarted(packageName)) {
            return Triple(false, "常驻输入daemon不可用（无su/拉起失败）", -1L)
        }
        return send("down $x $y")
    }

    suspend fun move(packageName: String, x: Float, y: Float): Triple<Boolean, String?, Long> {
        // move 高频（~60Hz）：跳过 ensure 的 ping（省一次往返），直发；
        // daemon 死了则本发失败，调用方降级（ensure 只在 down/tap 时做）。
        return send("move $x $y")
    }

    suspend fun up(packageName: String, x: Float, y: Float): Triple<Boolean, String?, Long> {
        return send("up $x $y")
    }

    suspend fun drag(
        packageName: String,
        x0: Float, y0: Float, x1: Float, y1: Float,
    ): Triple<Boolean, String?, Long> {
        if (!ensureStarted(packageName)) {
            return Triple(false, "常驻输入daemon不可用（无su/拉起失败）", -1L)
        }
        return send("drag $x0 $y0 $x1 $y1")
    }

    suspend fun key(packageName: String, keyCode: Int): Triple<Boolean, String?, Long> {
        if (!ensureStarted(packageName)) {
            return Triple(false, "常驻输入daemon不可用（无su/拉起失败）", -1L)
        }
        return send("key $keyCode")
    }

    suspend fun text(packageName: String, b64: String): Triple<Boolean, String?, Long> {
        if (!ensureStarted(packageName)) {
            return Triple(false, "常驻输入daemon不可用（无su/拉起失败）", -1L)
        }
        return send("text $b64")
    }

    suspend fun cancel(): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        // 解卡 best-effort：不 ensure（daemon 死了则无手势可卡，直接视成功）。
        val (ok, err) = sendBlocking("cancel", PING_TIMEOUT_MS)
        if (!ok) {
            Log.d(TAG, "[RootInputDaemon] cancel best-effort miss err=${err?.take(120)}")
        }
        true to null
    }

    // ---- 内部 ----

    /** 探活（阻塞，调用方已在 IO）。 */
    private fun ping(timeoutMs: Int): Boolean {
        val (ok, err) = sendBlocking("ping", timeoutMs)
        if (!ok) {
            Log.d(TAG, "[RootInputDaemon] ping miss err=${err?.take(120)}")
        }
        return ok
    }

    /**
     * 一连接一行同步收发（阻塞）。
     * 注意：LocalSocket.connect(address, timeout) 未实现（抛 UnsupportedOperationException），
     * 故用自管线程 + join 实现 connect 超时（与 PrivilegedCapture 同手法）。
     */
    private fun sendBlocking(line: String, timeoutMs: Int): Pair<Boolean, String?> {
        var sock: LocalSocket? = null
        try {
            val s = LocalSocket()
            sock = s
            val address = LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT)
            var connectError: Throwable? = null
            val ct = Thread {
                try {
                    s.connect(address)
                } catch (t: Throwable) {
                    connectError = t
                }
            }.apply { isDaemon = true }
            ct.start()
            ct.join(timeoutMs.toLong())
            if (ct.isAlive) {
                runCatching { s.close() }
                return false to "connect $SOCKET_NAME timeout ${timeoutMs}ms"
            }
            connectError?.let { return false to "connect failed: ${it.message ?: it}" }
            try {
                s.soTimeout = timeoutMs
            } catch (_: Throwable) {
            }
            try {
                val out = s.outputStream
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (t: Throwable) {
                return false to "write failed: ${t.message ?: t}"
            }
            val ack: String? = try {
                val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                reader.readLine()
            } catch (t: Throwable) {
                return false to "read ack failed: ${t.message ?: t}"
            }
            if (ack == null) return false to "ack eof"
            val t = ack.trim()
            if (t == "ok" || t.startsWith("ok ")) return true to null
            if (t.startsWith("err ")) return false to t.removePrefix("err ").trim().ifBlank { "daemon rejected" }
            if (t == "err") return false to "daemon rejected"
            return false to "bad ack: ${t.take(120)}"
        } catch (t: Throwable) {
            return false to (t.message ?: t.toString())
        } finally {
            runCatching { sock?.close() }
        }
    }
}

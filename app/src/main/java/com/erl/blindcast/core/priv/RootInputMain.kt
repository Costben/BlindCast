package com.erl.blindcast.core.priv

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Root 常驻输入 daemon（Smooth-1 · 反控上行常驻通道，替代每次 tap/drag 冷起一次
 * `app_process` 约 1-2s；仿 [com.erl.blindcast.core.scrcpy.RootCaptureMain] 常驻模式）。
 *
 * 运行身份：由 App 侧经 libsu `Shell.cmd("CLASSPATH=<apk> app_process /system/bin
 * com.erl.blindcast.core.priv.RootInputMain daemon <stopFile> [socketName] &")`
 * 以 uid 0 真 root 身份拉起的独立 `app_process` 常驻进程（后台 `&`，随前台 Service
 * 启停：起服 ensure、停服 touch stop 文件；不用完即焚）。
 *
 * ## 为什么另起常驻而不是复用 RootMain
 * - RootMain 为单次 `input tap|drag|... <resultFile>` 即退的短进程模型（Universal-1，
 *   每次冷起约 1-2s，且 drag 是松手才一次性执行）；
 * - 实时跟手需 down/move/up 跨指令保持手势状态（TouchInjector 单例），短进程模型
 *   无法承载（状态跨进程不保留），故另起本常驻宿主，daemon 内复用同一
 *   [PrivilegedUserService]/TouchInjector 单例。
 *
 * ## 指令协议（App 侧 [RootInputDaemon] 经抽象 socket 喂指令，一连接一行）
 * - 文本行 UTF-8 `\n` 结尾，每连接只读首行、执行、回 ack 行即关连接；
 * - `ping` → `ok pong`（探活）；
 * - `tap <x> <y>`（归一化 Down+Up 原子）/ `drag <x0> <y0> <x1> <y1>`（原子，
 *   松手兼容）/ `key <code>` / `text <b64>`（`-`/空=空串）；
 * - `down <x> <y>` / `move <x> <y>` / `up <x> <y>`（实时跟手三件套，
 *   daemon 内 TouchInjector 状态机跨连接保持，move 直透不再等松手）；
 * - `cancel` → 补 Up 解卡；
 * - 回包：`ok` / `ok <detail>` 成功；`err <单行明细>` 失败。
 * - 效果目标：tap 延迟从 ~1.5s 降到 ≤300ms（以 ack 回包计时为准）。
 *
 * ## 调用契约
 * - `args = ["daemon", stopFile, socketName?]`（缺 stopFile 直接退出码 1）；
 * - 启动后阻塞 accept 直到 `<stopFile>` 出现（accept 500ms 超时轮询，stop 侧 `touch` 即退）；
 * - 陈旧 stop 文件先清（防上次崩溃残留导致秒退）；socket 抢绑（EADDRINUSE）说明旧
 *   daemon 存活，本进程直接退出码 0（调用方 ping 复用旧 daemon，不算失败）；
 * - 全程 runCatching 不抛，退出码 0=正常停（含复用让路），1=启动失败/异常；
 * - 普通 App 进程不要直接调（只在 root `app_process` 内有意义）。
 *
 * R8 注意：release 启用 minify，需 keep 本类及 main 方法（见 proguard-rules.pro）。
 */
@Keep
object RootInputMain {

    private const val TAG = "BlindCast"

    /** 默认抽象 socket 名（App 侧 [RootInputDaemon.SOCKET_NAME] 同值）。 */
    const val DEFAULT_SOCKET_NAME = "blindcast_input"

    /** stop 文件轮询步进 500ms（与 RootCaptureMain 同步进，watcher 线程用）。 */
    private const val ACCEPT_TIMEOUT_MS = 500

    /** 单连接读指令超时 5s（防半包连接长期占 accept 循环）。 */
    private const val READ_TIMEOUT_MS = 5_000

    /**
     * `app_process` 入口（签名必须 `public static void main(String[])`）。
     */
    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        val pid = runCatching { Process.myPid() }.getOrDefault(-1)
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        var code = 1
        var stopFile: File? = null
        var server: LocalServerSocket? = null
        try {
            runCatching { Log.i(TAG, "[RootInputMain] pid=$pid uid=$uid enter args=${args.toList().take(3)}") }
            if (args.getOrNull(0) != "daemon") {
                runCatching { Log.e(TAG, "[RootInputMain] unknown op ${args.getOrNull(0)} (only daemon)") }
                return
            }
            val stopPath = args.getOrNull(1)
            if (stopPath.isNullOrBlank()) {
                runCatching { Log.e(TAG, "[RootInputMain] missing stopFile args[1]") }
                return
            }
            val socketName = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: DEFAULT_SOCKET_NAME
            stopFile = File(stopPath)
            // 陈旧 stop 文件先清（防上次崩溃残留导致秒退）。
            runCatching { if (stopFile.exists()) stopFile.delete() }
            // 单例复用：同一 PrivilegedUserService（内含 TouchInjector 单例状态机）。
            val svc = try {
                PrivilegedUserService()
            } catch (t: Throwable) {
                runCatching { Log.e(TAG, "[RootInputMain] new PrivilegedUserService failed", t) }
                return
            }
            try {
                server = LocalServerSocket(socketName)
            } catch (t: Throwable) {
                // 抢绑 = 旧 daemon 存活（上次停服漏杀或并发拉起）：让路退出码 0，
                // 调用方 ping 复用旧 daemon（stop 文件固定同名，可正常停）。
                runCatching { Log.i(TAG, "[RootInputMain] bind $socketName failed (old daemon alive?), give way: ${t.message}") }
                code = 0
                server = null
                return
            }
            // accept 本身无超时：另起 watcher 线程轮询 stop 文件，命中即关服
            // （accept 抛错跳出），stop 侧 `touch` 即退。
            val srv = server
            if (srv != null) {
                val watcher = Thread({
                    while (!stopFile.exists()) {
                        try {
                            Thread.sleep(ACCEPT_TIMEOUT_MS.toLong())
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    runCatching { srv.close() }
                }, "BlindCast-RootInputStop")
                watcher.isDaemon = true
                watcher.start()
            }
            runCatching { Log.i(TAG, "[RootInputMain] serving sock=$socketName stop=$stopPath") }
            var served = 0L
            while (true) {
                if (stopFile.exists()) {
                    runCatching { Log.i(TAG, "[RootInputMain] stop file hit served=$served, exiting") }
                    break
                }
                val client: LocalSocket = try {
                    val s = server ?: break
                    s.accept()
                } catch (t: Throwable) {
                    // server 被 watcher 关（stop）或致命：stop 文件在即正常退，否则记错退。
                    if (stopFile.exists()) {
                        runCatching { Log.i(TAG, "[RootInputMain] accept closed by stop served=$served, exiting") }
                    } else {
                        runCatching { Log.e(TAG, "[RootInputMain] accept failed served=$served", t) }
                    }
                    break
                }
                try {
                    serveOne(client, svc)
                    served++
                } catch (t: Throwable) {
                    runCatching { Log.w(TAG, "[RootInputMain] serveOne threw: ${t.message}") }
                } finally {
                    runCatching { client.close() }
                }
            }
            code = 0
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "[RootInputMain] top-level threw", t) }
            code = 1
        } finally {
            runCatching { server?.close() }
            runCatching { Log.i(TAG, "[RootInputMain] pid=$pid uid=$uid exit code=$code") }
            try {
                Runtime.getRuntime().halt(code)
            } catch (_: Throwable) {
                try { System.exit(code) } catch (_: Throwable) { }
            }
        }
    }

    /**
     * 单连接服务：一行指令 → 执行 → 一行 ack（同步阻塞，调用方 accept 循环内）。
     * 异常只记 err 回包，不抛（不断 accept 循环）。
     */
    private fun serveOne(client: LocalSocket, svc: PrivilegedUserService) {
        runCatching { client.soTimeout = READ_TIMEOUT_MS }
        val reader = BufferedReader(InputStreamReader(client.inputStream, Charsets.UTF_8))
        val line = try {
            reader.readLine()
        } catch (t: Throwable) {
            writeAck(client, false, "read failed: ${t.message ?: t}")
            return
        }
        if (line.isNullOrBlank()) {
            writeAck(client, false, "empty command")
            return
        }
        val (ok, detail) = runCatching { exec(line.trim(), svc) }.getOrElse { t ->
            false to (t.message ?: t.toString())
        }
        writeAck(client, ok, detail)
    }

    private fun writeAck(client: LocalSocket, ok: Boolean, detail: String?) {
        try {
            val out = client.outputStream
            val msg = if (ok) {
                if (detail.isNullOrBlank()) "ok\n" else "ok ${singleLine(detail)}\n"
            } else {
                "err ${singleLine(detail ?: "failed")}\n"
            }
            out.write(msg.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Throwable) {
        }
    }

    private fun singleLine(s: String): String =
        s.replace("\n", " ").replace("\r", " ").trim().take(300).ifBlank { "failed" }

    /**
     * 执行一行指令。
     * @return first=是否成功；second=成功明细（ping pong）或失败明细。
     */
    private fun exec(line: String, svc: PrivilegedUserService): Pair<Boolean, String?> {
        val parts = line.split(" ").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false to "empty command"
        return when (parts[0]) {
            "ping" -> true to "pong"
            "warm" -> {
                // 注入链预热（无副作用，拉起后调一次，摊掉首次 tap 冷初始化）。
                val ok = svc.warmup()
                if (ok) true to "warmed" else false to (svc.inputError ?: "warmup rejected")
            }
            "cancel" -> {
                val ok = svc.cancelInput()
                if (ok) true to null else false to (svc.inputError ?: "cancel rejected")
            }
            "tap" -> {
                if (parts.size != 3) return false to "tap need x y"
                val x = parts[1].toFloatOrNull()
                val y = parts[2].toFloatOrNull()
                if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
                    return false to "tap bad coord"
                }
                val ok = svc.injectTap(x, y)
                if (ok) true to null else false to (svc.inputError ?: "tap rejected")
            }
            "down" -> {
                if (parts.size != 3) return false to "down need x y"
                val x = parts[1].toFloatOrNull()
                val y = parts[2].toFloatOrNull()
                if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
                    return false to "down bad coord"
                }
                val ok = svc.injectDown(x, y)
                if (ok) true to null else false to (svc.inputError ?: "down rejected")
            }
            "move" -> {
                if (parts.size != 3) return false to "move need x y"
                val x = parts[1].toFloatOrNull()
                val y = parts[2].toFloatOrNull()
                if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
                    return false to "move bad coord"
                }
                val ok = svc.injectMove(x, y)
                if (ok) true to null else false to (svc.inputError ?: "move rejected")
            }
            "up" -> {
                if (parts.size != 3) return false to "up need x y"
                val x = parts[1].toFloatOrNull()
                val y = parts[2].toFloatOrNull()
                if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
                    return false to "up bad coord"
                }
                val ok = svc.injectUp(x, y)
                if (ok) true to null else false to (svc.inputError ?: "up rejected")
            }
            "drag" -> {
                if (parts.size != 5) return false to "drag need x0 y0 x1 y1"
                val f = parts.subList(1, 5).map { it.toFloatOrNull() }
                if (f.any { it == null || !it.isFinite() }) return false to "drag bad coord"
                val ok = svc.injectDrag(f[0]!!, f[1]!!, f[2]!!, f[3]!!)
                if (ok) true to null else false to (svc.inputError ?: "drag rejected")
            }
            "key" -> {
                if (parts.size != 2) return false to "key need code"
                val code = parts[1].toIntOrNull() ?: return false to "key bad code"
                val ok = svc.injectKey(code)
                if (ok) true to null else false to (svc.inputError ?: "key rejected")
            }
            "text" -> {
                // `text <b64>`（`-`/缺参=空串；b64 字母表无空格故单 token 安全）。
                val b64 = parts.getOrNull(1).orEmpty()
                val text = if (b64.isEmpty() || b64 == "-") {
                    ""
                } else {
                    try {
                        String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP), Charsets.UTF_8)
                    } catch (t: Throwable) {
                        return false to "text bad b64: ${t.message ?: t}"
                    }
                }
                val ok = svc.injectText(text)
                if (ok) true to null else false to (svc.inputError ?: "text rejected")
            }
            else -> false to "unknown op ${parts[0]}"
        }
    }
}

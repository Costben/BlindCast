package com.erl.blindcast.core.priv

import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.erl.blindcast.core.blackout.PowerController
import java.io.File

/**
 * Root 真身单次执行器（Root-Backend-1 · 不搭常驻 daemon，只做断电/点亮两个操作）。
 *
 * 运行身份：由 [RootExecutor] 经 `su -c "CLASSPATH=<apk> app_process /system/bin
 * com.erl.blindcast.core.priv.RootMain displayPower on|off <resultFile>"` 拉起，
 * 以 uid 0 真 root 身份跑在独立 `app_process` 中（实锤结论：MAA-Meow 在本机起
 * `com.aliothmoon.maameow:root_service` 跑在 uid 0 真 root 身份调断电才生效；
 * 我们的 Shizuku UserService 是 shell 身份，被 OPlus 静默忽略）。
 *
 * ## 调用契约（RootExecutor 侧组装，勿硬编码 APK 路径）
 * - `CLASSPATH=<调用方 applicationInfo.sourceDir>`（RootExecutor 传参，勿硬编码）；
 * - `app_process /system/bin com.erl.blindcast.core.priv.RootMain displayPower on|off <resultFile>`；
 * - `<resultFile>` 为 `/data/local/tmp/blindcast_root_result_<nonce>`（RootExecutor 生成 nonce）。
 *
 * ## 进程内行为
 * - 参数 `displayPower on/off`（`args[0]=="displayPower"`，`args[1]=="on"|"off"`，
 *   `args[2]=结果文件路径`），进程内直调既有 [PowerController.setDisplayPower]（非 routed 版，
 *   含 binder→验效→按键兜底全链路，熄屏为 binder→SLEEP→POWER 三段）；
 * - 结果写结果文件两行：`ok=true|false` / `err=<message>`（成功时 err 为空）；
 * - 全程 `runCatching` 包住不抛，`finally` 按成功失败 `System.exit(0/1)`；
 * - 普通 App 进程不要直接调本入口（本入口只在 root `app_process` 内有意义）。
 *
 * R8 注意：release 启用 minify，proguard-rules.pro 中 keep 本类及 main 方法。
 */
@Keep
object RootMain {

    /** 全链路统一 TAG（与 PowerController / RootExecutor 一致，root 进程 logcat 可见）。 */
    private const val TAG = "BlindCast"

    /**
     * `app_process` 入口（签名必须为 `public static void main(String[])`，Kotlin 侧为
     * `object + @JvmStatic fun main(args: Array<String>)`）。
     *
     * @param args 期望 `["displayPower", "on"|"off", "<resultFile>"]`。
     */
    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        var ok = false
        var errMsg: String = ""
        var resultFile: File? = null
        val pid = runCatching { Process.myPid() }.getOrDefault(-1)
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        try {
            runCatching {
                Log.d(TAG, "[RootMain] pid=$pid uid=$uid enter args=${args.toList().take(3)}")
            }
            // 参数解析（全包住，缺参/非法参数同样写文件 + 非零退出，不抛）。
            val op = args.getOrNull(0)
            val onOff = args.getOrNull(1)
            val resultPath = args.getOrNull(2)
            if (op != "displayPower") {
                errMsg = "未知操作：${op ?: "null"}（仅支持 displayPower）"
                return
            }
            val on: Boolean = when (onOff) {
                "on" -> true
                "off" -> false
                else -> {
                    errMsg = "未知 on/off 参数：${onOff ?: "null"}（仅支持 on|off）"
                    return
                }
            }
            if (resultPath.isNullOrBlank()) {
                errMsg = "缺结果文件路径（args[2] 为空）"
                return
            }
            resultFile = File(resultPath)
            // 直调非 routed 版（必须在提权进程内：此处即 root app_process 本身）。
            val callOk: Boolean = runCatching {
                PowerController.setDisplayPower(on)
            }.getOrElse { t ->
                val msg = t.message ?: t.toString()
                errMsg = "setDisplayPower抛异常：$msg"
                runCatching {
                    Log.e(TAG, "[RootMain] pid=$pid uid=$uid setDisplayPower on=$on threw", t)
                }
                return
            }
            if (callOk) {
                ok = true
                errMsg = ""
            } else {
                ok = false
                val privErr = runCatching { PowerController.lastError?.message }.getOrNull()
                    ?: runCatching { PowerController.lastPrivError }.getOrNull()
                errMsg = privErr?.takeIf { it.isNotBlank() } ?: "特权执行返回false（见root进程logcat明细）"
            }
        } catch (t: Throwable) {
            // 顶层兜底：任何意外都不抛，只记文案（finally 写文件 + 退出码）。
            ok = false
            errMsg = t.message ?: t.toString()
            runCatching {
                Log.e(TAG, "[RootMain] pid=$pid uid=$uid top-level threw", t)
            }
        } finally {
            val code = if (ok) 0 else 1
            runCatching {
                Log.d(TAG, "[RootMain] pid=$pid uid=$uid exit ok=$ok code=$code err=${errMsg.take(200)}")
            }
            // 结果文件两行：ok=/err=（写失败只记日志，不改变退出码语义）。
            runCatching {
                val f = resultFile ?: args.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { File(it) }
                if (f != null) {
                    runCatching { f.parentFile?.mkdirs() }.getOrDefault(false)
                    // 单行 err（去换行，防解析歧义，截断防超长）。
                    val singleLineErr = errMsg.replace("\n", " ").replace("\r", " ").take(500)
                    f.writeText("ok=$ok\nerr=$singleLineErr\n")
                    // 尽力放行给 App 进程读（/data/local/tmp 默认可读，但 root 建文件可能 0600）。
                    runCatching { f.setReadable(true, false) }.getOrDefault(false)
                } else {
                    Log.e(TAG, "[RootMain] pid=$pid uid=$uid no resultFile, skip write")
                }
            }.exceptionOrNull()?.let { t ->
                runCatching {
                    Log.e(TAG, "[RootMain] pid=$pid uid=$uid write result failed", t)
                }
            }
            // finally 退出码 0/1（0=ok，1=失败；kill 进程防 app_process 驻留）。
            try {
                Runtime.getRuntime().halt(code)
            } catch (_: Throwable) {
                try {
                    System.exit(code)
                } catch (_: Throwable) {
                    // 退出都失败则自然返回（app_process 会自行结束）。
                }
            }
        }
    }
}

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
 *   `args[2]=结果文件路径`），No-Lock-1 顺序：先 [PowerController.tryBinderDisplayPower]
 *   binder 物理断电/点亮直试（混合路由+日志不动，仅 binder→STATE 严格验效约 2s，
 *   熄屏验 STATE_OFF，点亮验 STATE_ON），成了直接返回 ok（无锁屏、无 AOD 真黑）；
 *   熄屏 binder 验效失败直接返 false，不进任何锁屏/按键兜底；
 *   点亮 binder 验效失败则试 [PowerController.wakeByKey]
 *  （KEYCODE_WAKEUP→KEYCODE_POWER，只点亮不制造新锁）；
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
            // Stream-Priv-1 同名 op：Root 单次执行器不适合视频长流（短进程即退模型），
            // 采集长流请走 RootCaptureMain 常驻（libsu app_process 常驻 + stop 文件信号，
            // 同一 CaptureSocketLink 服）。此处保留同名入口仅作路由指引，不做采集。
            if (op == "startCapture" || op == "captureVideo") {
                val rp = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: args.getOrNull(2)
                if (!rp.isNullOrBlank()) resultFile = File(rp)
                errMsg = "Root 单次执行器不承载长流采集，请走 RootCaptureMain 常驻 " +
                    "（本机优先 Shizuku UserService 常驻：daemon root 启动，SurfaceControl 身份够用，见 ForegroundService [CaptureRoute] 日志）"
                runCatching {
                    Log.i(TAG, "[RootMain] pid=$pid uid=$uid startCapture routed to RootCaptureMain daemon, reject single-shot")
                }
                return
            }
            val onOff = args.getOrNull(1)
            val resultPath = args.getOrNull(2)
            if (op != "displayPower") {
                errMsg = "未知操作：${op ?: "null"}（仅支持 displayPower/startCapture）"
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
            // No-Lock-1 接线：先 binder 物理断电/点亮直试（root 身份下 OPlus 很可能放行，
            // 成了即无锁屏真黑）；熄屏 miss 直接失败不进锁屏链，点亮 miss 试 wakeByKey。
            // 混合路由+日志不动，App 侧
            // isBlackedOut/lastError/日志/Home状态行/Toast/路由契约不变：App 侧 routed 入口
            // 仍据返回值 + 结果文件 ok/err 自行翻转，本进程经 recordPrivResult 记状态行。
            // 直调非 routed 版（必须在提权进程内：此处即 root app_process 本身）。
            val callOk: Boolean = runCatching {
                setDisplayPowerNoLock(on)
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

    /**
     * No-Lock-1 特权熄屏/点亮（root app_process 内直调，同步阻塞）。
     * 顺序：先 [PowerController.tryBinderDisplayPower] binder 物理直试（混合路由+日志不动，
     * 仅 binder→STATE 严格验效约 2s：熄屏 STATE_OFF，点亮 STATE_ON），成了直接返回 ok
     * （无锁屏、无 AOD 的真黑）；熄屏 binder 验效失败直接返 false，
     * 永不调 lockNow/lockAndSleep/ensureScreenOff，不注入 KEY_SLEEP/KEY_POWER；
     * 点亮 binder 验效失败则试 [PowerController.wakeByKey]
     * （KEYCODE_WAKEUP→KEYCODE_POWER，只点亮不制造新锁，自立无 lockNow）。
     * 成功/失败均经 [PowerController.recordPrivResult] 记状态行（供结果文件 err 回读）；
     * 返回值即验效后最终结果（App 侧据此翻转 isBlackedOut/lastError，契约不变）。
     */
    private fun setDisplayPowerNoLock(on: Boolean): Boolean {
        val op = if (on) "restore" else "blackout"
        // 先 binder 物理直试（root 真身下先试，成了即无锁屏真黑）。
        var binderDesc: String? = null
        var binderRead: String? = null
        try {
            val r = PowerController.tryBinderDisplayPower(on)
            if (r.verified) {
                Log.d(TAG, "[RootMain] binder-first hit on=$on route=${r.route} read=${r.read}")
                PowerController.recordPrivResult(op, true, null)
                return true
            }
            binderDesc = PowerController.binderFirstSegmentDesc(on, r)
            binderRead = r.read
            Log.d(TAG, "[RootMain] binder-first miss on=$on $binderDesc (No-Lock-1 no lock fallback for off)")
        } catch (t: Throwable) {
            binderDesc = "binder-root段异常：${t.message ?: t}"
            Log.e(TAG, "[RootMain] binder-first threw on=$on $binderDesc", t)
        }
        return try {
            if (on) {
                val ok = try {
                    PowerController.wakeByKey()
                } catch (t: Throwable) {
                    Log.e(TAG, "[RootMain] wakeByKey threw", t)
                    PowerController.recordPrivResult(op, false, "点亮失败：${binderDesc}→按键唤醒段异常：${t.message ?: t}")
                    return false
                }
                if (ok) {
                    PowerController.recordPrivResult(op, true, null)
                } else {
                    val wakeErr = runCatching { PowerController.lastError?.message }
                        .getOrNull()?.takeIf { !it.isNullOrBlank() }
                        ?: runCatching { PowerController.lastPrivError }.getOrNull()
                    val msg = "点亮失败：${binderDesc}→${wakeErr ?: "按键唤醒WAKEUP→POWER复验仍未STATE_ON"}（见root进程logcat [PowerController]明细）"
                    PowerController.recordPrivResult(op, false, msg)
                }
                ok
            } else {
                val read = binderRead ?: runCatching { PowerController.lastError?.message }.getOrNull()
                val msg = "熄屏失败：${binderDesc}；物理断电未生效（本机忽略），未执行锁屏兜底" +
                    (if (!binderRead.isNullOrBlank()) "（当前$read" +
                        "，见root进程logcat [PowerController][BinderFirst]明细）" else "")
                PowerController.recordPrivResult(op, false, msg)
                Log.d(TAG, "[RootMain] blackout binder-only miss, no fallback err=$msg")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[RootMain] setDisplayPowerNoLock threw", t)
            PowerController.recordPrivResult(op, false, t.message ?: t.toString())
            false
        }
    }
}

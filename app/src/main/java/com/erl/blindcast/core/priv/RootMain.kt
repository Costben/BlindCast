package com.erl.blindcast.core.priv

import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.blackout.meow.ServiceManager as MeowServiceManager
import com.erl.blindcast.core.blackout.meow.WakeUnlockController as MeowWakeUnlockController
import com.erl.blindcast.core.blackout.meow.WakeUnlockResult as MeowWakeUnlockResult
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
 *   `args[2]=结果文件路径`），Root-Cut-1 顺序：先 [PowerController.tryBinderDisplayPower]
 *   binder 物理断电/点亮直试（混合路由+日志不动，仅 binder→STATE 严格验效约 2s，
 *   熄屏验 STATE_OFF，点亮验 STATE_ON），成了直接返回 ok（无锁屏、无 AOD 真黑）；
 *   binder 验效失败才进 meow 锁屏链兜底（熄屏 lockAndSleep→ensureScreenOff，
 *   点亮 ensureScreenOn，既有不动）；
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
            // Root-Cut-1 接线：先 binder 物理断电/点亮直试（root 身份下 OPlus 很可能放行，
            // 成了即无锁屏真黑），失败才进 meow 锁屏链兜底。混合路由+日志不动，App 侧
            // isBlackedOut/lastError/日志/Home状态行/Toast/路由契约不变：App 侧 routed 入口
            // 仍据返回值 + 结果文件 ok/err 自行翻转，本进程经 recordPrivResult 记状态行。
            // 直调非 routed 版（必须在提权进程内：此处即 root app_process 本身）。
            val callOk: Boolean = runCatching {
                meowSetDisplayPower(on)
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
     * Root-Cut-1 特权熄屏/点亮（root app_process 内直调，同步阻塞）。
     * 顺序：先 [PowerController.tryBinderDisplayPower] binder 物理直试（混合路由+日志不动，
     * 仅 binder→STATE 严格验效约 2s：熄屏 STATE_OFF，点亮 STATE_ON），成了直接返回 ok
     * （无锁屏、无 AOD 的真黑）；binder 验效失败才进 meow 锁屏链兜底（既有不动）：
     * 熄屏先 [MeowWakeUnlockController.lockAndSleep]（lockNow+goToSleep+轮询），
     * 未 OK（含 NO_KEYGUARD 无锁屏早返未息屏）则 fallback
     * [MeowWakeUnlockController.ensureScreenOff] 保证物理熄屏；
     * 点亮：[MeowWakeUnlockController.wakeScreen]（即 ensureScreenOn）。
     * 成功/失败均经 [PowerController.recordPrivResult] 记状态行（供结果文件 err 回读），
     * 失败文案区分 binder-root 段/meow 段；返回值即验效后最终结果
     * （App 侧据此翻转 isBlackedOut/lastError，契约不变）。
     */
    private fun meowSetDisplayPower(on: Boolean): Boolean {
        val op = if (on) "restore" else "blackout"
        // 先 binder 物理直试（root 真身下先试，成了就不用锁屏链）。
        val binderDesc: String? = try {
            val r = PowerController.tryBinderDisplayPower(on)
            if (r.verified) {
                Log.d(TAG, "[RootMain] binder-first hit on=$on route=${r.route} read=${r.read}")
                PowerController.recordPrivResult(op, true, null)
                return true
            }
            val d = PowerController.binderFirstSegmentDesc(on, r)
            Log.d(TAG, "[RootMain] binder-first miss on=$on $d, fallback meow")
            d
        } catch (t: Throwable) {
            val d = "binder-root段异常：${t.message ?: t}"
            Log.e(TAG, "[RootMain] binder-first threw on=$on $d", t)
            d
        }
        return try {
            if (on) {
                val ok = try {
                    MeowWakeUnlockController.wakeScreen()
                } catch (t: Throwable) {
                    Log.e(TAG, "[RootMain] meow ensureScreenOn threw", t)
                    PowerController.recordPrivResult(op, false, "点亮失败：${binderDesc}→meow段点亮异常：${t.message ?: t}")
                    return false
                }
                if (ok) {
                    PowerController.recordPrivResult(op, true, null)
                } else {
                    val msg = "点亮失败：${binderDesc}→meow段ensureScreenOn验效未通过（BINDER→WAKEUP→POWER三段均miss，见root进程logcat [MeowWakeUnlock]明细）"
                    PowerController.recordPrivResult(op, false, msg)
                }
                ok
            } else {
                val lockCode = try {
                    MeowWakeUnlockController.lockAndSleep()
                } catch (t: Throwable) {
                    Log.e(TAG, "[RootMain] meow lockAndSleep threw", t)
                    MeowWakeUnlockResult.UNSUPPORTED
                }
                if (lockCode == MeowWakeUnlockResult.OK) {
                    PowerController.recordPrivResult(op, true, null)
                    return true
                }
                val offOk = try {
                    val pm = MeowServiceManager.getPowerManager()
                    MeowWakeUnlockController.ensureScreenOff(pm)
                } catch (t: Throwable) {
                    Log.e(TAG, "[RootMain] meow ensureScreenOff threw", t)
                    false
                }
                if (offOk) {
                    PowerController.recordPrivResult(op, true, null)
                } else {
                    val msg = "熄屏失败：${binderDesc}→meow段lockAndSleep=$lockCode→ensureScreenOff验效未通过（BINDER→SLEEP→POWER三段均miss，见root进程logcat明细）"
                    PowerController.recordPrivResult(op, false, msg)
                }
                offOk
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[RootMain] meowSetDisplayPower threw", t)
            PowerController.recordPrivResult(op, false, t.message ?: t.toString())
            false
        }
    }
}

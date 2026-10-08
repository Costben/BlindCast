package com.erl.blindcast.core.priv

import android.os.Process
import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * Phase C 自管理 companion 关联（Vdm-Assoc-1）。
 *
 * ## 为什么需要
 * Android 16 的 `VirtualDeviceManager.createVirtualDevice(associationId, params)` 要求
 * 调用方持有一个 **companion 关联**（profile ∈ {APP_STREAMING 等}），否则直接
 * `IllegalArgumentException: No association with ID N`。真机实证见
 * `outputs/probe/phase-c-probe.md` §3.1。
 *
 * ## 只管理自己的那一条（Vdm-Assoc-Own-1 · 真机缺陷修复）
 * 本对象按 **MAC 分身份**，每个用途一条，互不可见：
 * - [OWN_MAC]（`BC:01`）= **桌面会话**（[DesktopController] 用）；
 * - [PROBE_MAC]（`BC:02`）= **探针**（`/api/probe/vd*` 用）。
 *
 * 所有读写入口都带 `mac` 参数（默认 [OWN_MAC]），`ensure/release` 只认自己那条 MAC。
 * **真机踩过的坑**：早期只有一条 MAC，探针跑完 `release()` 会把**正在运行的桌面**那条
 * 关联一起删掉（`/api/probe/vd` 一来，桌面宿主当场被拆、虚拟屏消失）。分身份后
 * 探针清理只动 `BC:02`，桌面 `BC:01` 与任何他人关联一律不读不改不删。
 *
 * ## 执行身份
 * 关联的创建/删除必须由 root 或 shell 执行（`cmd companiondevice associate|disassociate`），
 * 故经 libsu root Shell 下发 [Shell.cmd]。真机实证 root 三条命令均可（见 VERIFICATION）。
 *
 * ## 生命周期
 * `ensure` → 使用 → `release`，每次状态变化写 logcat `TAG=BlindCast-VDAssoc`，
 * 供 `VirtualDeviceBridge` 与验收脚本核对。是否在桌面会话期间保持由调用方决定。
 */
object VirtualDeviceAssociation {

    /** 生命周期专用 TAG（`BlindCast-*` 前缀，logcat 可过滤）。 */
    private const val TAG = "BlindCast-VDAssoc"

    /** VDM 身份对应的包名（与 [VirtualDeviceProbe.SHELL_PACKAGE] 一致，evidence-based）。 */
    const val SHELL_PACKAGE = "com.android.shell"

    /** Android 16 接受的 device profile（findings 记录 AndroMeld 同款取值）。 */
    const val PROFILE = "android.app.role.COMPANION_DEVICE_APP_STREAMING"

    /**
     * **桌面会话**专属关联 MAC（唯一用途：`VirtualDesktopSession` 建设备的那条）。
     * 取值只要满足 `MacAddress.fromString` 的 6 组十六进制即可；
     * 末两字节 `BC:01` 作为 BlindCast 桌面标记，与他方取值不重叠。
     */
    const val OWN_MAC = "FA:CE:FE:ED:BC:01"

    /**
     * **探针**专属关联 MAC（`/api/probe/vd`、`/api/probe/vdcreate` 用）。
     *
     * 刻意与 [OWN_MAC] 分开：探针是只读/一次性诊断，它的 `release()` 绝不允许
     * 摘掉正在跑的桌面会话的关联（真机缺陷 Vdm-Assoc-Own-1）。
     */
    const val PROBE_MAC = "FA:CE:FE:ED:BC:02"

    /** 当前用户 id（uid/100000；root 与 shell 均为 0）。 */
    fun userId(): Int = runCatching { Process.myUid() / 100000 }.getOrDefault(0)

    /** 跑一条 root shell 命令，返回 stdout+stderr（失败不抛，返回错误文本）。 */
    private fun rootShell(cmd: String): String = runCatching {
        val r = Shell.cmd(cmd).exec()
        val out = runCatching { r.out }.getOrDefault(emptyList())
        val err = runCatching { r.err }.getOrDefault(emptyList())
        (out + err).joinToString("\n").trim()
    }.getOrElse { "shell failed: ${it.javaClass.simpleName}: ${it.message}" }

    /** `cmd companiondevice list <user>` 原文（仅诊断用）。 */
    fun listRaw(): String = rootShell("cmd companiondevice list ${userId()}")

    /**
     * 纯解析：从 `cmd companiondevice list` 原文里取指定 MAC 那条的关联 id（无则 -1）。
     *
     * 真机输出形如：
     * ```
     * Max ID: 5
     * Association ID | Package Name | Mac Address
     * 5 | com.android.shell | fa:ce:fe:ed:bc:01
     * ```
     * 只认给定 MAC，故他人条目不参与匹配、不被误删。**无 libsu 依赖**，
     * 可在 shell 子进程（裸 `app_process`）内直接用。
     */
    fun parseOwnId(listOut: String, mac: String = OWN_MAC): Int = runCatching {
        val want = mac.lowercase()
        listOut.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("|") && it.lowercase().contains(want) }
            ?.substringBefore("|")
            ?.trim()
            ?.toIntOrNull()
            ?: -1
    }.getOrDefault(-1)

    /**
     * 解析指定 MAC 在本机的关联 id（默认 [OWN_MAC]）。
     *
     * 输出形如 `4 | com.android.shell | fa:ce:fe:ed:bc:01`；命中返回 id，否则 -1。
     */
    fun findOwnId(mac: String = OWN_MAC): Int = parseOwnId(listRaw(), mac)

    /**
     * 确保指定 MAC 的关联存在（默认桌面会话那条）。
     *
     * @return 关联 id（>0）；创建失败返回 -1（调用方据此走失败回退，不静默成功）。
     */
    fun ensure(mac: String = OWN_MAC): Int {
        val uid = userId()
        val existing = findOwnId(mac)
        if (existing > 0) {
            Log.i(TAG, "[ensure] association already present id=$existing mac=$mac user=$uid")
            return existing
        }
        Log.i(TAG, "[ensure] creating association mac=$mac pkg=$SHELL_PACKAGE profile=$PROFILE user=$uid")
        val out = rootShell("cmd companiondevice associate $uid $SHELL_PACKAGE $mac $PROFILE true")
        if (out.isNotBlank()) Log.i(TAG, "[ensure] associate output=${out.take(300)}")
        val created = findOwnId(mac)
        if (created > 0) {
            Log.i(TAG, "[ensure] created association id=$created mac=$mac")
        } else {
            Log.e(TAG, "[ensure] associate failed mac=$mac; list=${listRaw().take(300)}")
        }
        return created
    }

    /**
     * 删除**指定 MAC** 的关联（默认桌面会话那条；只删这一条，不存在时直接返回 false）。
     *
     * @return true = 删除命令已执行且此后查不到该 MAC 的关联。
     */
    fun release(mac: String = OWN_MAC): Boolean {
        val uid = userId()
        val id = findOwnId(mac)
        if (id <= 0) {
            Log.i(TAG, "[release] no association for mac=$mac, nothing to do")
            return false
        }
        Log.i(TAG, "[release] removing association id=$id mac=$mac user=$uid")
        val out = rootShell("cmd companiondevice disassociate $uid $SHELL_PACKAGE $mac")
        if (out.isNotBlank()) Log.i(TAG, "[release] disassociate output=${out.take(300)}")
        val still = findOwnId(mac)
        val ok = still <= 0
        Log.i(TAG, "[release] done ok=$ok mac=$mac remainingOwnId=$still " +
            "list=${listRaw().replace("\n", " | ").take(200)}")
        return ok
    }

    /** 单行状态摘要（供路由/控制台展示；默认桌面会话那条）。 */
    fun stateLine(mac: String = OWN_MAC): String {
        val id = findOwnId(mac)
        return "ownId=$id mac=$mac pkg=$SHELL_PACKAGE profile=$PROFILE user=${userId()}"
    }
}

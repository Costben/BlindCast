package com.erl.blindcast.core.priv

import android.app.ActivityOptions
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Phase C 虚拟桌面任务管理器与同屏复用控制器（DesktopTaskController）。
 *
 * ## 三条强约束验收红线：
 * 1. 【严防主屏被夺】查询与切换严格按 [targetDisplayId] 校验；若目标 task 属于物理主屏 0 或其他屏，
 *    坚决阻断并返回错误，绝对禁止调用 move-to-front 挪动主屏任务；
 * 2. 【同屏任务复用】副屏已有该应用 task 优先拉到前台，未运行时仅用 FLAG_ACTIVITY_NEW_TASK 启动，
 *    坚决杜绝 FLAG_ACTIVITY_MULTIPLE_TASK 导致多实例泄漏；
 * 3. 【Fail-Closed 熔断】targetDisplayId <= 0 时立即拒绝，严禁静默 fallback 到主屏 0。
 */
object DesktopTaskController {

    private const val TAG = "BlindCast-TaskCtrl"

    data class DesktopTask(
        val taskId: Int,
        val packageName: String,
        val activityName: String,
        val displayId: Int,
        val isTop: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("taskId", taskId)
            .put("packageName", packageName)
            .put("activityName", activityName)
            .put("displayId", displayId)
            .put("isTop", isTop)
    }

    data class TaskSwitchResult(
        val ok: Boolean,
        val taskId: Int,
        val displayId: Int,
        val switched: Boolean,
        val error: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("ok", ok)
            .put("taskId", taskId)
            .put("displayId", displayId)
            .put("switched", switched)
            .put("error", error)
    }

    /**
     * 查询指定 [targetDisplayId] 上的任务列表。
     * 严格限制：只返回当前副屏上的任务；[targetDisplayId] <= 0 时返回空列表（防透传主屏）。
     */
    fun listTasks(targetDisplayId: Int): List<DesktopTask> {
        if (targetDisplayId <= 0) {
            Log.w(TAG, "[listTasks] targetDisplayId=$targetDisplayId <= 0, fail-closed rejected")
            return emptyList()
        }

        // 1. 优先尝试 Binder 反射（已由 VirtualDeviceBridge.addHiddenApiExemptions 放行）
        val binderTasks = runCatching { listTasksViaBinder(targetDisplayId) }.getOrNull()
        if (!binderTasks.isNullOrEmpty()) {
            return binderTasks.filter { it.displayId == targetDisplayId }
        }

        // 2. 回落到特权 dumpsys activity activities 解析
        val shellTasks = runCatching { listTasksViaShell(targetDisplayId) }.getOrDefault(emptyList())
        return shellTasks.filter { it.displayId == targetDisplayId }
    }

    /**
     * 切换/恢复任务到目标副屏前台。
     *
     * 强安全校验：
     * 1. targetDisplayId <= 0 立即拒绝；
     * 2. 查询系统所有任务，校验目标 taskId 的实际所在屏；
     * 3. 若实际在主屏 0 或其它屏，坚决拒绝并返回清晰错误文案；
     * 4. 仅当确认属于该副屏时，执行 bring-to-front。
     */
    fun switchTask(taskId: Int, targetDisplayId: Int): TaskSwitchResult {
        if (targetDisplayId <= 0) {
            return TaskSwitchResult(
                ok = false,
                taskId = taskId,
                displayId = targetDisplayId,
                switched = false,
                error = "targetDisplayId <= 0 无效，已阻断避免触碰物理主屏",
            )
        }

        // 全局查找该 taskId 的当前归属 displayId
        val actualDisplayId = findTaskDisplayId(taskId)
        if (actualDisplayId == null) {
            return TaskSwitchResult(
                ok = false,
                taskId = taskId,
                displayId = targetDisplayId,
                switched = false,
                error = "task $taskId 不存在或已退出",
            )
        }

        if (actualDisplayId != targetDisplayId) {
            val msg = "task $taskId 不在虚拟屏 $targetDisplayId 上（在 $actualDisplayId），拒绝移动"
            Log.w(TAG, "[switchTask] Cross-display switch blocked: $msg")
            return TaskSwitchResult(
                ok = false,
                taskId = taskId,
                displayId = targetDisplayId,
                switched = false,
                error = msg,
            )
        }

        // 归属校验通过，执行恢复前台
        val success = runCatching { moveTaskToFrontInternal(taskId, targetDisplayId) }.getOrDefault(false)
        return if (success) {
            Log.i(TAG, "[switchTask] task $taskId successfully switched on display $targetDisplayId")
            TaskSwitchResult(ok = true, taskId = taskId, displayId = targetDisplayId, switched = true)
        } else {
            TaskSwitchResult(
                ok = false,
                taskId = taskId,
                displayId = targetDisplayId,
                switched = false,
                error = "执行 move-task-to-front 失败",
            )
        }
    }

    /**
     * 查找目标包名在副屏上已有的任务（同屏任务复用）。
     */
    fun findTaskByPackage(packageName: String, targetDisplayId: Int): DesktopTask? {
        if (targetDisplayId <= 0 || packageName.isBlank()) return null
        return listTasks(targetDisplayId).firstOrNull { it.packageName == packageName }
    }

    // =========================================================================
    // 内部实现与引擎
    // =========================================================================

    /** 全局查找某 taskId 的实际 displayId（用于防挪动主屏 task 拦截） */
    private fun findTaskDisplayId(taskId: Int): Int? {
        // 1. 优先特权 dumpsys 解析：真机实证 displayId 100% 正确（App 进程无 DUMP，走 RootMain dumpsysActs）
        val shellMap = runCatching { getAllTaskDisplaysViaShell() }.getOrNull()
        shellMap?.get(taskId)?.let { return it }

        // 2. binder 兜底：displayId 字段读取被 hiddenapi 拦截时会静默变 0，
        //    因此「全部为 0」的 map 绝对不可信（说明字段读取失败），必须直接丢弃返回 null，绝不误判为「在物理主屏 0」
        val binderMap = runCatching { getAllTaskDisplaysViaBinder() }.getOrNull() ?: return null
        if (binderMap.isEmpty() || binderMap.values.all { it == 0 }) return null
        return binderMap[taskId]
    }

    private fun moveTaskToFrontInternal(taskId: Int, targetDisplayId: Int): Boolean {
        // 1. 优先经特权 IPC 通道（RootMain taskFront，由特权进程反射并二次核验）
        val privRes = runCatching {
            runBlocking { PrivilegedBridge.moveTaskToFront(taskId, targetDisplayId) }
        }.getOrNull()
        if (privRes != null && privRes.first) {
            return true
        }

        // 2. 尝试 Binder 反射
        val binderOk = runCatching {
            val atm = getActivityTaskManager() ?: return@runCatching false
            val options = ActivityOptions.makeBasic().apply {
                setLaunchDisplayId(targetDisplayId)
            }.toBundle()

            var moved = false
            for (m in atm.javaClass.methods) {
                if (m.name == "moveTaskToFront") {
                    try {
                        val pts = m.parameterTypes
                        when (pts.size) {
                            3 -> if (pts[0] == Int::class.javaPrimitiveType && pts[1] == Int::class.javaPrimitiveType && pts[2] == Bundle::class.java) {
                                m.invoke(atm, taskId, 0, options)
                                moved = true
                                break
                            }
                            5 -> if (pts[2] == Int::class.javaPrimitiveType && pts[3] == Int::class.javaPrimitiveType && pts[4] == Bundle::class.java) {
                                m.invoke(atm, null, null, taskId, 0, options)
                                moved = true
                                break
                            }
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "moveTaskToFront method invoke failed: ${t.message}")
                    }
                }
            }
            moved
        }.getOrDefault(false)

        if (binderOk) return true

        // 3. 回落到 Shell 执行 cmd activity move-task-to-front
        val res = Shell.cmd("cmd activity move-task-to-front $taskId").exec()
        if (res.isSuccess) return true

        // 4. 再次回落 am
        val resAm = Shell.cmd("am task to-top $taskId").exec()
        return resAm.isSuccess
    }

    private fun listTasksViaBinder(targetDisplayId: Int): List<DesktopTask>? {
        val atm = getActivityTaskManager() ?: return null
        val tasks = mutableListOf<DesktopTask>()

        val method = atm.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterTypes.isNotEmpty() }
            ?: return null

        val rawList = try {
            val pts = method.parameterTypes
            when (pts.size) {
                4 -> method.invoke(atm, 100, false, false, targetDisplayId) as? List<*>
                3 -> method.invoke(atm, 100, false, false) as? List<*>
                2 -> method.invoke(atm, 100, false) as? List<*>
                1 -> method.invoke(atm, 100) as? List<*>
                else -> null
            }
        } catch (t: Throwable) {
            return null
        } ?: return null

        var anyNonZeroDisplay = false
        for (item in rawList) {
            if (item == null) continue
            val taskInfoClass = item.javaClass
            val tid = runCatching {
                taskInfoClass.getField("taskId").getInt(item)
            }.getOrElse {
                taskInfoClass.getField("id").getInt(item)
            }
            val did = runCatching {
                taskInfoClass.getField("displayId").getInt(item)
            }.getOrNull() ?: return null

            if (did > 0) anyNonZeroDisplay = true
            if (did != targetDisplayId) continue

            val topActivity = runCatching {
                taskInfoClass.getField("topActivity").get(item) as? android.content.ComponentName
            }.getOrNull()

            val isVisible = runCatching {
                taskInfoClass.getField("isVisible").getBoolean(item)
            }.getOrDefault(false)

            val pkg = topActivity?.packageName ?: ""
            val act = topActivity?.className ?: ""

            // 过滤桌面 Home 自身，避免干扰应用列表
            if (pkg == "com.erl.blindcast" && act.contains("FusionHomeActivity")) {
                continue
            }

            tasks.add(
                DesktopTask(
                    taskId = tid,
                    packageName = pkg,
                    activityName = act,
                    displayId = did,
                    isTop = isVisible,
                )
            )
        }
        if (targetDisplayId > 0 && !anyNonZeroDisplay) {
            // 全 0 说明 displayId 字段反射被平台拦截，视为不可信丢弃
            return null
        }
        return tasks
    }

    private fun getAllTaskDisplaysViaBinder(): Map<Int, Int>? {
        val atm = getActivityTaskManager() ?: return null
        val map = mutableMapOf<Int, Int>()
        val method = atm.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterTypes.isNotEmpty() }
            ?: return null
        val pts = method.parameterTypes
        val rawList = try {
            when (pts.size) {
                4 -> method.invoke(atm, 100, false, false, -1) as? List<*>
                else -> method.invoke(atm, 100) as? List<*>
            }
        } catch (t: Throwable) {
            null
        } ?: return null

        for (item in rawList) {
            if (item == null) continue
            val taskInfoClass = item.javaClass
            val tid = runCatching { taskInfoClass.getField("taskId").getInt(item) }.getOrNull() ?: continue
            val did = runCatching { taskInfoClass.getField("displayId").getInt(item) }.getOrNull() ?: return null
            map[tid] = did
        }
        if (map.isEmpty() || map.values.all { it == 0 }) {
            // 全 0 视为 hiddenapi 拦截导致的无效读，不可信直接返回 null
            return null
        }
        return map
    }

    /**
     * 通过特权 IPC 执行 `dumpsys activity activities` 解析目标副屏 Tasks。
     */
    private fun listTasksViaShell(targetDisplayId: Int): List<DesktopTask> {
        val raw = runCatching { runBlocking { PrivilegedBridge.dumpActivities() } }.getOrNull()
        val lines = if (!raw.isNullOrBlank()) {
            raw.lines()
        } else {
            Shell.cmd("dumpsys activity activities").exec().out
        }
        if (lines.isEmpty()) return emptyList()
        return parseDumpsysActivities(lines, targetDisplayId)
    }

    private fun getAllTaskDisplaysViaShell(): Map<Int, Int> {
        val raw = runCatching { runBlocking { PrivilegedBridge.dumpActivities() } }.getOrNull()
        val lines = if (!raw.isNullOrBlank()) {
            raw.lines()
        } else {
            Shell.cmd("dumpsys activity activities").exec().out
        }
        if (lines.isEmpty()) return emptyMap()
        return parseAllTaskDisplays(lines)
    }

    /**
     * 解析 dumpsys 输出中指定 displayId 的 tasks。
     *
     * 兼容真机格式：
     * `* Task{5c72730 #14047 type=standard A=10644:com.erl.blindcast ...}`
     * 以及 `Task id #12`、`TaskRecord #12`、`Task #12`。
     */
    fun parseDumpsysActivities(lines: List<String>, targetDisplayId: Int): List<DesktopTask> {
        val result = mutableListOf<DesktopTask>()
        var currentDisplay = -1
        var currentTaskId = -1
        var currentPkg = ""
        var currentAct = ""
        var isTopTask = true

        val displayPattern = Pattern.compile("""Display\s+#(\d+)""")
        // 关键增强：兼容 Task{[0-9a-fA-F]+ #\d+ 与 Task id #\d+ 等
        val taskPattern = Pattern.compile("""(?:Task\{[0-9a-fA-F]+\s+#|Task\s+id\s+#|TaskRecord\s+#|Task\s+#?)(\d+)""")
        val activityRecordPattern = Pattern.compile("""ActivityRecord\{[0-9a-fA-F]+\s+[^/]+(?:\s+u\d+)?\s+([^/]+)/([^ \t\r\n}]+)""")
        val realActivityPattern = Pattern.compile("""realActivity=([^/]+)/([^ \t\r\n}]+)""")

        fun flushCurrentTask() {
            if (currentDisplay == targetDisplayId && currentTaskId > 0 && currentPkg.isNotBlank()) {
                if (!(currentPkg == "com.erl.blindcast" && currentAct.contains("FusionHomeActivity"))) {
                    if (result.none { it.taskId == currentTaskId }) {
                        result.add(
                            DesktopTask(
                                taskId = currentTaskId,
                                packageName = currentPkg,
                                activityName = currentAct,
                                displayId = currentDisplay,
                                isTop = isTopTask,
                            )
                        )
                        isTopTask = false
                    }
                }
            }
            currentTaskId = -1
            currentPkg = ""
            currentAct = ""
        }

        for (line in lines) {
            val trimmed = line.trim()

            // 检查 Display #
            val dm = displayPattern.matcher(trimmed)
            if (dm.find()) {
                flushCurrentTask()
                currentDisplay = dm.group(1)?.toIntOrNull() ?: -1
                isTopTask = true
                continue
            }

            if (currentDisplay != targetDisplayId) {
                continue
            }

            // 检查 Task
            val tm = taskPattern.matcher(trimmed)
            if (tm.find()) {
                flushCurrentTask()
                currentTaskId = tm.group(1)?.toIntOrNull() ?: -1
                continue
            }

            // 检查 realActivity=
            val ram = realActivityPattern.matcher(trimmed)
            if (ram.find()) {
                currentPkg = ram.group(1).orEmpty()
                currentAct = ram.group(2).orEmpty()
                continue
            }

            // 检查 ActivityRecord
            val arm = activityRecordPattern.matcher(trimmed)
            if (arm.find()) {
                if (currentPkg.isBlank()) {
                    currentPkg = arm.group(1).orEmpty()
                    currentAct = arm.group(2).orEmpty()
                }
            }
        }
        flushCurrentTask()

        return result
    }

    /**
     * 解析 dumpsys 输出中所有 task 对应的 displayId。
     */
    fun parseAllTaskDisplays(lines: List<String>): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        var currentDisplay = 0
        val displayPattern = Pattern.compile("""Display\s+#(\d+)""")
        val taskPattern = Pattern.compile("""(?:Task\{[0-9a-fA-F]+\s+#|Task\s+id\s+#|TaskRecord\s+#|Task\s+#?)(\d+)""")

        for (line in lines) {
            val trimmed = line.trim()
            val dm = displayPattern.matcher(trimmed)
            if (dm.find()) {
                currentDisplay = dm.group(1)?.toIntOrNull() ?: 0
                continue
            }
            val tm = taskPattern.matcher(trimmed)
            if (tm.find()) {
                val tid = tm.group(1)?.toIntOrNull()
                if (tid != null && tid > 0 && !map.containsKey(tid)) {
                    map[tid] = currentDisplay
                }
            }
        }
        return map
    }

    private fun getActivityTaskManager(): Any? {
        // 先调用隐藏 API 豁免放行
        runCatching { VirtualDeviceBridge.addHiddenApiExemptions() }

        return runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            atmClass.getMethod("getService").invoke(null)
        }.getOrElse {
            runCatching {
                val amClass = Class.forName("android.app.ActivityManager")
                amClass.getMethod("getService").invoke(null)
            }.getOrNull()
        }
    }
}

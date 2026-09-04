package com.erl.blindcast.core.ha

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * HA 传感器实时上报器（Slice 5.2 · MVP.md 二(6)传感器 + PLAN.md Slice 5.2）。
 *
 * ## 采集（经 BatteryManager + 粘性广播）
 * - 电量：`EXTRA_LEVEL / EXTRA_SCALE` 换算百分比（无效时回退
 *   `BatteryManager.BATTERY_PROPERTY_CAPACITY`）；
 * - 充电状态：`EXTRA_STATUS == CHARGING || FULL`；
 * - 温度：`EXTRA_TEMPERATURE / 10f`（协议单位为 0.1°C）。
 *
 * ## 上报
 * - 按 [intervalMs] 周期 publish 至 [HaDiscoveryPayload.batteryStateTopic]（整数 `%`）
 *   与 [HaDiscoveryPayload.temperatureStateTopic]（保留 1 位小数 °C）；
 * - QoS 0 + retained=false（高频可丢，broker 不留旧值；开关 state 对齐仍走 QoS 1 见
 *   [HaCommandHandler]）；
 * - 首次启动立即上报一次，之后按间隔循环（与 `UserActivityKeeper` 同语义的先行一次）。
 *
 * ## 线程与幂等
 * - [start]/[stop] 幂等：重复 start 不产生第二条协程（仅更新间隔/node/client）；
 * - 所有采集与发布均在 IO 协程，采集失败跳过该轮、不崩溃；
 * - 仅做能力封装，不接 UI（HA 页面绑定在 6.2；6.1 主页可复用 [readSnapshot]）。
 */
object HaSensorReporter {

    private const val TAG = "BlindCast-HaSensor"

    /** 默认上报间隔 60s。 */
    const val DEFAULT_INTERVAL_MS: Long = 60_000L

    /** 间隔下限 5s（防刷屏 broker）。 */
    const val MIN_INTERVAL_MS: Long = 5_000L

    /** 间隔上限 10min（再大即视为保活断线）。 */
    const val MAX_INTERVAL_MS: Long = 600_000L

    /** 是否正在上报（volatile，跨线程可见）。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** 当前上报间隔（钳制后值）。 */
    @Volatile
    var intervalMs: Long = DEFAULT_INTERVAL_MS
        private set

    /** 当前上报的 nodeId（未运行时为空串）。 */
    @Volatile
    var activeNodeId: String = ""
        private set

    /** 最近一次异常；最近一次整轮双 topic 成功后清零，永不崩溃。 */
    @Volatile
    var lastError: Throwable? = null
        private set

    /** 最近一次成功上报的电量 %（-1 = 尚未成功过）。 */
    @Volatile
    var lastBatteryLevel: Int = -1
        private set

    /** 最近一次成功上报的温度 °C（NaN = 尚未成功过）。 */
    @Volatile
    var lastTemperatureC: Float = Float.NaN
        private set

    /** 最近一次采集的充电态（判空快照时保持旧值）。 */
    @Volatile
    var lastCharging: Boolean = false
        private set

    /** 电池快照（原始 level/scale 保留供诊断）。 */
    data class Snapshot(
        val levelPercent: Int,
        val charging: Boolean,
        val temperatureC: Float,
        val rawLevel: Int,
        val rawScale: Int,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activeClient: HaMqttClient? = null

    /**
     * 预存应用上下文（只记 applicationContext，不泄漏）。
     * 调 [start] 时若已传 Context 可不调本方法；6.2 Service/ViewModel 二选一即可。
     */
    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    /**
     * 开始定时上报（幂等，任务包字面签名）。
     *
     * 使用 [init] 预存的上下文 + 默认 node/client；无预存上下文时返回 false 并记 [lastError]。
     * 已在运行时仅更新 [intervalMs]，不重启协程。
     */
    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS): Boolean {
        val ctx = appContext
        if (ctx == null) {
            lastError = IllegalStateException("HaSensorReporter: no context (call init/start(context) first)")
            Log.w(TAG, "start without context", lastError)
            return false
        }
        return start(
            ctx,
            intervalMs,
            activeNodeId.ifEmpty { HaDiscoveryPayload.DEFAULT_NODE_ID },
            activeClient ?: HaMqttClient,
        )
    }

    /**
     * 开始定时上报（幂等，主入口）。
     *
     * @param context 任意 Context（内部只持 applicationContext）。
     * @param intervalMs 上报间隔（钳制 5s..10min）。
     * @param nodeId 设备节点 ID（默认 `blindcast`）。
     * @param client MQTT 客户端（默认单例）。
     * @return true=已进入上报态（含已在运行）；无有效快照源时仍返回 true（下轮重试），
     *   仅无 Context/协程起不来时 false。
     */
    fun start(
        context: Context,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID,
        client: HaMqttClient = HaMqttClient,
    ): Boolean {
        val ctx = context.applicationContext ?: context
        val clamped = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        synchronized(lock) {
            appContext = ctx
            activeNodeId = nodeId
            activeClient = client
            this.intervalMs = clamped
            if (isRunning && job?.isActive == true) return true
            isRunning = true
            job = scope.launch {
                publishOnce(ctx, nodeId, client)
                while (isActive && isRunning) {
                    delay(this@HaSensorReporter.intervalMs)
                    if (!isRunning) break
                    val c = appContext ?: ctx
                    val n = activeNodeId.ifEmpty { nodeId }
                    publishOnce(c, n, activeClient ?: client)
                }
            }
        }
        return true
    }

    /** 停止定时上报（幂等）：取消协程并清标记，不碰 MQTT 连接。 */
    fun stop() {
        synchronized(lock) {
            isRunning = false
            job?.cancel()
            job = null
        }
    }

    /**
     * 同步采集一次电池快照（供上报循环与 6.1 主页复用）。
     * @return 快照；无粘性广播且无 BatteryManager 回退时 null（不抛异常）。
     */
    fun readSnapshot(context: Context): Snapshot? {
        return runCatching {
            val ctx = context.applicationContext ?: context
            val bm = runCatching { ctx.getSystemService(BatteryManager::class.java) }.getOrNull()
            val sticky = runCatching {
                ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            if (sticky == null) {
                val cap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                if (cap < 0) return null
                return Snapshot(cap, false, Float.NaN, cap, 100)
            }
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val tempTenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val tempC = if (tempTenths == Int.MIN_VALUE) Float.NaN else tempTenths / 10f
            var percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            if (percent < 0) {
                percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            }
            if (percent < 0 && tempC.isNaN()) return null
            Snapshot(percent, charging, tempC, level, scale)
        }.getOrElse {
            lastError = it
            Log.w(TAG, "readSnapshot failed", it)
            null
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private suspend fun publishOnce(ctx: Context, nodeId: String, client: HaMqttClient) {
        try {
            val snap = readSnapshot(ctx)
            if (snap == null) {
                Log.w(TAG, "battery snapshot unavailable, skip this round")
                return
            }
            lastCharging = snap.charging
            var ok = true
            if (snap.levelPercent >= 0) {
                val sent = runCatching {
                    client.publish(
                        HaDiscoveryPayload.batteryStateTopic(nodeId),
                        snap.levelPercent.toString(),
                        qos = 0,
                        retained = false,
                    )
                }.getOrDefault(false)
                if (sent) {
                    lastBatteryLevel = snap.levelPercent
                } else {
                    ok = false
                    Log.w(TAG, "battery publish failed: ${snap.levelPercent}")
                }
            }
            if (!snap.temperatureC.isNaN()) {
                val text = "%.1f".format(Locale.US, snap.temperatureC)
                val sent = runCatching {
                    client.publish(
                        HaDiscoveryPayload.temperatureStateTopic(nodeId),
                        text,
                        qos = 0,
                        retained = false,
                    )
                }.getOrDefault(false)
                if (sent) {
                    lastTemperatureC = snap.temperatureC
                } else {
                    ok = false
                    Log.w(TAG, "temperature publish failed: $text")
                }
            }
            if (ok) {
                lastError = null
            } else {
                lastError = client.lastError
                    ?: IllegalStateException("HaSensorReporter: sensor publish failed")
            }
        } catch (t: Throwable) {
            lastError = t
            Log.w(TAG, "publishOnce crashed guard", t)
        }
    }
}

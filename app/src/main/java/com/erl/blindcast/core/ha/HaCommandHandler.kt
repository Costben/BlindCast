package com.erl.blindcast.core.ha

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.erl.blindcast.core.blackout.PowerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * HA 开关双向联动处理器（Slice 5.2 · MVP.md 二(6) + PLAN.md Slice 5.2）。
 *
 * ## 任务包约定（铁线）
 * - 订阅开关 command Topic（[HaDiscoveryPayload.screenCommandTopic]，HA → 手机）；
 * - `ON → PowerController.blackout()`（进入挂机黑屏态）；
 * - `OFF → PowerController.restore()`（恢复亮屏）；
 * - 执行后回写 state Topic（[HaDiscoveryPayload.screenStateTopic]，手机 → HA）双向对齐；
 * - 异常记 [lastError]，永不抛崩溃。
 *
 * ## 语义说明
 * switch 的 `ON` 在本项目表示"挂机黑屏态开"（与直觉的"屏幕亮"相反，遵循任务包原文），
 * 故 state 回写同样遵循 `isBlackedOut=true → ON / false → OFF`，保证 HA 开关显示与
 * 手机实际电源态一致。未知载荷直接忽略（记 log，不回写、不记错）。
 *
 * ## 线程模型
 * - Paho 回调线程禁止 Binder 阻塞：[subscribe] 登记的 handler 只做转发，
 *   真正的 `blackout/restore + state 回写` 切到 IO 协程执行；
 * - [start]/[stop] 幂等：重复 start 不叠加订阅（切换 nodeId 时先退订旧值）；
 * - 仅做能力封装，不接 UI（HA 页面绑定在 6.2）。
 */
object HaCommandHandler {

    private const val TAG = "BlindCast-HaCmd"

    /** 开关 state 回写 QoS（对齐指令必须到达，用 1）。 */
    private const val STATE_QOS = 1

    /** 是否已订阅 command Topic（volatile，跨线程可见）。 */
    @Volatile
    var isStarted: Boolean = false
        private set

    /** 当前订阅的 nodeId（未启动时为空串）。 */
    @Volatile
    var activeNodeId: String = ""
        private set

    /** 最近一次异常（订阅失败 / 电源失败 / 回写失败）；最近一次全成功后清零，永不崩溃。 */
    @Volatile
    var lastError: Throwable? = null
        private set

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 开始监听开关 command Topic（幂等）。
     *
     * 离线时 [HaMqttClient.subscribe] 会登记为 pending（返回 false，重连后自动生效），
     * 此时仍标记 [isStarted]=true，调用方可视返回值为离线提示而非失败。
     *
     * @param nodeId 设备节点 ID（默认 `blindcast`，与 Discovery 侧一致）。
     * @param client MQTT 客户端（默认单例；单测可传替身——类型即单例类型）。
     * @return broker 侧订阅成功 true；离线 pending / 异常 false（已记 [lastError]）。
     */
    fun start(
        nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID,
        client: HaMqttClient = HaMqttClient,
    ): Boolean {
        val filter = HaDiscoveryPayload.screenCommandTopic(nodeId)
        synchronized(lock) {
            if (isStarted && activeNodeId == nodeId) {
                // 已在监听同一 node：刷新 handler 绑定（覆盖旧闭包持有的 client），不叠加。
                return resubscribe(filter, nodeId, client)
            }
            if (isStarted && activeNodeId != nodeId) {
                runCatching { client.unsubscribe(HaDiscoveryPayload.screenCommandTopic(activeNodeId)) }
            }
            isStarted = true
            activeNodeId = nodeId
        }
        return resubscribe(filter, nodeId, client)
    }

    /**
     * 停止监听（幂等）：退订当前 [activeNodeId] 的 command Topic。
     * 离线时仅清除本地登记与标记，不记错。
     */
    fun stop(client: HaMqttClient = HaMqttClient) {
        val nodeId = synchronized(lock) {
            val n = activeNodeId
            isStarted = false
            activeNodeId = ""
            n
        }
        if (nodeId.isNotEmpty()) {
            runCatching { client.unsubscribe(HaDiscoveryPayload.screenCommandTopic(nodeId)) }
        }
    }

    /**
     * 发布当前屏幕电源态到 state Topic（双向对齐用）。
     * @return 发布成功 true；离线/异常 false（已记 [lastError]）。
     */
    fun publishCurrentState(
        client: HaMqttClient = HaMqttClient,
        nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID,
    ): Boolean {
        val state = if (PowerController.isBlackedOut) "ON" else "OFF"
        val ok = runCatching {
            client.publish(HaDiscoveryPayload.screenStateTopic(nodeId), state, qos = STATE_QOS, retained = false)
        }.getOrDefault(false)
        if (ok) {
            lastError = null
        } else {
            lastError = client.lastError
                ?: IllegalStateException("HaCommandHandler: publish state failed ($state)")
            Log.w(TAG, "publish state failed: $state", lastError)
        }
        return ok
    }

    /**
     * 指令解析（供分发与单测）：大小写不敏感，前后空白容忍。
     * @return true=ON(熄屏) / false=OFF(点亮) / null=未知载荷（忽略）。
     */
    @VisibleForTesting
    fun parseCommand(payload: String): Boolean? = when (payload.trim().uppercase()) {
        "ON" -> true
        "OFF" -> false
        else -> null
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun resubscribe(filter: String, nodeId: String, client: HaMqttClient): Boolean {
        val ok = runCatching {
            client.subscribe(filter, qos = 1) { bytes ->
                // Paho 回调线程：只转发，切 IO 执行 Binder + 回写。
                scope.launch { executeCommand(bytes, nodeId, client) }
            }
        }.getOrDefault(false)
        if (!ok) {
            // 离线 pending 不算硬错：只在确有异常时记错，纯离线保持 lastError 为 client 侧即可。
            val cause = client.lastError
            if (cause != null) {
                lastError = cause
                Log.w(TAG, "subscribe failed: $filter", cause)
            }
        }
        return ok
    }

    private fun executeCommand(bytes: ByteArray, nodeId: String, client: HaMqttClient) {
        val raw = runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")
        val cmd = runCatching { parseCommand(raw) }.getOrNull()
        if (cmd == null) {
            Log.w(TAG, "ignore unknown command payload: \"$raw\"")
            return
        }
        try {
            val powerOk = if (cmd) {
                runCatching { PowerController.blackout() }.getOrDefault(false)
            } else {
                runCatching { PowerController.restore() }.getOrDefault(false)
            }
            if (!powerOk) {
                lastError = PowerController.lastError
                    ?: IllegalStateException("HaCommandHandler: PowerController failed (cmd=$raw)")
                Log.w(TAG, "PowerController failed for cmd=$raw", lastError)
            }
            // 双向对齐：回写实际电源态（成功时与指令一致，失败时把 HA 开关纠回现实）。
            val actual = if (PowerController.isBlackedOut) "ON" else "OFF"
            val pubOk = runCatching {
                client.publish(
                    HaDiscoveryPayload.screenStateTopic(nodeId),
                    actual,
                    qos = STATE_QOS,
                    retained = false,
                )
            }.getOrDefault(false)
            if (powerOk && pubOk) {
                lastError = null
                Log.i(TAG, "cmd=$raw applied, state=$actual reported")
            } else if (!pubOk && powerOk) {
                lastError = client.lastError
                    ?: IllegalStateException("HaCommandHandler: state publish failed ($actual)")
                Log.w(TAG, "state publish failed: $actual", lastError)
            }
            // powerOk=false 时 lastError 已记电源失败，不被 publish 结果覆盖。
        } catch (t: Throwable) {
            lastError = t
            Log.w(TAG, "executeCommand crashed guard: \"$raw\"", t)
        }
    }
}

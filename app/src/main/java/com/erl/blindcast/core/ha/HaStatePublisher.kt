package com.erl.blindcast.core.ha

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HA 状态发布门面（Slice 5.1 · MVP.md 二(6) + 第四章 core/ha）。
 *
 * 本 Slice 只做三件事：
 * 1. 发布 Discovery 配置包（switch + battery + temperature，retained）；
 * 2. 上线 birth（availability `online`，retained）与 Last-Will `offline`
 *    （[offlineWill] 随 CONNECT 注册，异常掉线由 broker 代发）；
 * 3. 预留 state / command Topic 命名（实际开关订阅与传感器数值上报在 5.2 落子）。
 *
 * 仅做能力封装，不接 UI（HA 页面绑定在 6.2），不碰 PowerController 与电量轮询。
 * 所有发布失败只返回 false + 记 log，永不抛崩溃（[HaMqttClient.lastError] 可查）。
 */
object HaStatePublisher {

    private const val TAG = "BlindCast-HaState"

    const val PAYLOAD_ON = "ON"
    const val PAYLOAD_OFF = "OFF"
    const val PAYLOAD_ONLINE = "online"
    const val PAYLOAD_OFFLINE = "offline"

    // ------------------------------------------------------------------
    // Topic 预留（命名唯一出口，5.2 直接消费）
    // ------------------------------------------------------------------

    /** 开关控制 topic（HA → 手机，5.2 订阅）。 */
    fun screenCommandTopic(nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID): String =
        HaDiscoveryPayload.screenCommandTopic(nodeId)

    /** 开关状态 topic（手机 → HA，5.2 发布）。 */
    fun screenStateTopic(nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID): String =
        HaDiscoveryPayload.screenStateTopic(nodeId)

    /** 电量状态 topic（5.2 发布）。 */
    fun batteryStateTopic(nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID): String =
        HaDiscoveryPayload.batteryStateTopic(nodeId)

    /** 温度状态 topic（5.2 发布）。 */
    fun temperatureStateTopic(nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID): String =
        HaDiscoveryPayload.temperatureStateTopic(nodeId)

    /** 在线状态 topic（birth/will）。 */
    fun availabilityTopic(nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID): String =
        HaDiscoveryPayload.availabilityTopic(nodeId)

    /**
     * 标准的 Last-Will：availability `offline`（retained，qos 1）。
     * 调用方在 [HaMqttClient.connect] 时传入，异常掉线由 broker 代发。
     */
    fun offlineWill(nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID): HaMqttWill =
        HaMqttWill(availabilityTopic(nodeId), PAYLOAD_OFFLINE, qos = 1, retained = true)

    // ------------------------------------------------------------------
    // 发布动作
    // ------------------------------------------------------------------

    /**
     * 发布全部 Discovery 配置包（3 条，retained，qos 1）。
     * @return 3 条全部成功 true；任一失败 false（已发出的不回滚，调用方可重试）。
     */
    fun publishDiscovery(
        client: HaMqttClient = HaMqttClient,
        ctx: HaDiscoveryPayload.Context,
    ): Boolean {
        var ok = true
        for (msg in HaDiscoveryPayload.buildAll(ctx)) {
            val sent = client.publish(msg.topic, msg.payload, qos = msg.qos, retained = msg.retained)
            if (!sent) {
                ok = false
                Log.w(TAG, "discovery publish failed: ${msg.topic}")
            }
        }
        if (ok) Log.i(TAG, "discovery published (node=${ctx.nodeId})")
        return ok
    }

    /**
     * 发布上线 birth：availability `online`（retained，qos 1）。
     * 建连成功后调用（遗嘱 offline 已在 CONNECT 时注册，一上线即覆盖为 online）。
     */
    fun publishBirth(
        client: HaMqttClient = HaMqttClient,
        nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID,
    ): Boolean {
        val ok = client.publish(availabilityTopic(nodeId), PAYLOAD_ONLINE, qos = 1, retained = true)
        if (ok) Log.i(TAG, "birth published (node=$nodeId)") else Log.w(TAG, "birth publish failed")
        return ok
    }

    /**
     * 优雅下线：主动发布 availability `offline`（retained），再由调用方 [HaMqttClient.disconnect]。
     * 正常退出走此路径；崩溃/断网走 Last-Will，同 topic 语义一致。
     */
    fun publishOfflineGraceful(
        client: HaMqttClient = HaMqttClient,
        nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID,
    ): Boolean =
        client.publish(availabilityTopic(nodeId), PAYLOAD_OFFLINE, qos = 1, retained = true)

    /**
     * 通用文本发布透传（5.2 的开关 state 与传感器数值经此发送，本 Slice 不直接调用）。
     * @param retained state 类建议 false（高频），availability/discovery 类 true。
     */
    fun publishText(
        client: HaMqttClient = HaMqttClient,
        topic: String,
        text: String,
        qos: Int = 1,
        retained: Boolean = false,
    ): Boolean = client.publish(topic, text, qos = qos, retained = retained)

    // ------------------------------------------------------------------
    // HA 全栈编排（Slice 5.2）：connect → Discovery → 订阅 command → 传感器上报
    // ------------------------------------------------------------------

    /**
     * 一键启动 HA 全栈（Slice 5.2 编排出口）。
     *
     * 顺序：`connect（带 offline 遗嘱）→ publishDiscovery → publishBirth →
     * HaCommandHandler.start（订阅 command）→ 回写当前屏幕 state →
     * HaSensorReporter.start（定时上报电量/温度）`。
     *
     * 失败永不抛崩溃：connect 失败直接 false；其余步骤失败记 log 并继续，
     * 原因可查 [HaMqttClient.lastError] / [HaCommandHandler.lastError] /
     * [HaSensorReporter.lastError]。
     *
     * 接入点（留给 Slice 6.2 HA 页面）：
     * ```
     * // HomeAssistantViewModel 内（禁止主线程直调 connect）：
     * viewModelScope.launch {
     *     val ok = HaStatePublisher.startHaStack(ctx, mqttConfig, discoveryCtx)
     *     // 刷新 UI 状态…
     * }
     * // 关闭时：HaStatePublisher.stopHaStack(nodeId)
     * ```
     *
     * @param appContext 任意 Context（传感器侧只持 applicationContext）。
     * @param mqttConfig Broker 连接配置。
     * @param discoveryCtx Discovery 上下文（含 nodeId / 前缀 / Web 基址 / Token）。
     * @param sensorIntervalMs 传感器上报间隔（默认 60s，钳制 5s..10min）。
     * @param client MQTT 客户端（默认单例）。
     * @return connect 成功 true；connect 失败 false（后台已按退避自动重试）。
     */
    suspend fun startHaStack(
        appContext: Context,
        mqttConfig: HaMqttConfig,
        discoveryCtx: HaDiscoveryPayload.Context,
        sensorIntervalMs: Long = HaSensorReporter.DEFAULT_INTERVAL_MS,
        client: HaMqttClient = HaMqttClient,
    ): Boolean = withContext(Dispatchers.IO) {
        val nodeId = discoveryCtx.nodeId
        try {
            val connected = runCatching {
                client.connect(config = mqttConfig, will = offlineWill(nodeId), autoReconnect = true)
            }.getOrDefault(false)
            if (!connected) {
                Log.w(TAG, "startHaStack: connect failed, retry in background")
                return@withContext false
            }
            val discOk = runCatching { publishDiscovery(client, discoveryCtx) }.getOrDefault(false)
            if (!discOk) Log.w(TAG, "startHaStack: discovery partial failure")
            runCatching { publishBirth(client, nodeId) }
            runCatching { HaCommandHandler.start(nodeId, client) }
            // 初始对齐：让 HA 开关一连上即显示手机真实电源态。
            runCatching { HaCommandHandler.publishCurrentState(client, nodeId) }
            runCatching { HaSensorReporter.start(appContext, sensorIntervalMs, nodeId, client) }
            Log.i(TAG, "ha stack started (node=$nodeId)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startHaStack crashed guard", t)
            false
        }
    }

    /**
     * 一键停止 HA 全栈（幂等，同步速返）：
     * `HaSensorReporter.stop → HaCommandHandler.stop → publishOfflineGraceful → disconnect`。
     * 正常退出走此路径（崩溃/断网走 Last-Will，同 topic 语义一致）。永不抛异常。
     *
     * 接入点（留给 Slice 6.2 HA 页面）：关闭联动开关 / ViewModel.onCleared 内调用。
     */
    fun stopHaStack(
        nodeId: String = HaDiscoveryPayload.DEFAULT_NODE_ID,
        publishOffline: Boolean = true,
        client: HaMqttClient = HaMqttClient,
    ) {
        runCatching { HaSensorReporter.stop() }
        runCatching { HaCommandHandler.stop(client) }
        if (publishOffline) {
            runCatching { publishOfflineGraceful(client, nodeId) }
        }
        runCatching { client.disconnect() }
        Log.i(TAG, "ha stack stopped (node=$nodeId)")
    }
}

package com.erl.blindcast.core.ha

import android.util.Log

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
}

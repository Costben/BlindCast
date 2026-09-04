package com.erl.blindcast.core.ha

/**
 * HA MQTT Discovery 配置包构造器（Slice 5.1 · MVP.md 二(6) + 第四章 core/ha）。
 *
 * 纯函数、无 Android 依赖、无网络：输入上下文即产出 (topic, payload)，
 * 发送动作统一走 [HaMqttClient]（见 [HaStatePublisher]）。
 *
 * 注册的 4 项资产（entity_id 由 `object_id` 决定）：
 * - `switch.blindcast_screen` —— 屏幕电源开关；
 * - `configuration_url` —— 设备块直达 Web 监控链接（HA 设备卡片「访问设备」）；
 * - `sensor.blindcast_battery` —— 电池电量；
 * - `sensor.blindcast_temperature` —— 电池温度。
 *
 * 仅做能力封装：开关订阅与传感器数值上报在 Slice 5.2 落子，本文件只预留
 * state / command / availability 的 Topic 命名。
 */
object HaDiscoveryPayload {

    /** HA Discovery 前缀默认值（HA 标准 `homeassistant`）。 */
    const val DEFAULT_DISCOVERY_PREFIX = "homeassistant"

    /** 设备节点 ID 默认值（同时用于 entity object_id 前缀与 topic 段）。 */
    const val DEFAULT_NODE_ID = "blindcast"

    /** 状态基址：`blindcast/<nodeId>/...`（与 Discovery 前缀解耦，方便自建 broker）。 */
    const val STATE_BASE = "blindcast"

    /** 设备展示名。 */
    const val DEVICE_NAME = "BlindCast"

    /** 设备 manufacturer（HA 设备块）。 */
    const val MANUFACTURER = "BlindCast"

    /** 设备 model（HA 设备块）。 */
    const val MODEL = "BlindCast Screen Station"

    /** 固件版本（HA 设备块 sw_version；发版时随 versionName 手动同步）。 */
    const val SW_VERSION = "1.0.0"

    /**
     * Discovery 上下文。
     * @param nodeId 设备节点 ID（默认 `blindcast`）。
     * @param discoveryPrefix Discovery 前缀（默认 `homeassistant`）。
     * @param webBaseUrl Web 监控基址，如 `http://192.168.1.10:8888`（不带 token）。
     * @param token 访问 Token；非空时拼入 `configuration_url`（`?token=xxx` 免密秒进）。
     */
    data class Context(
        val nodeId: String = DEFAULT_NODE_ID,
        val discoveryPrefix: String = DEFAULT_DISCOVERY_PREFIX,
        val webBaseUrl: String = "",
        val token: String = "",
    )

    /**
     * 一条待发布的 Discovery 消息。
     * @param topic 完整 topic；@param payload JSON 字符串；
     * @param retained Discovery 包必须 retained（HA 重启后仍能自发现）；
     * @param qos 固定 1（至少一次，到达即可）。
     */
    data class DiscoveryMessage(
        val topic: String,
        val payload: String,
        val retained: Boolean = true,
        val qos: Int = 1,
    )

    // ------------------------------------------------------------------
    // Topic 命名（state / command / availability 预留，5.2 消费）
    // ------------------------------------------------------------------

    /** 开关 Discovery 配置 topic：`<prefix>/switch/<node>/screen/config`。 */
    fun switchConfigTopic(ctx: Context): String =
        "${ctx.discoveryPrefix}/switch/${ctx.nodeId}/screen/config"

    /** 电量 Discovery 配置 topic：`<prefix>/sensor/<node>/battery/config`。 */
    fun batteryConfigTopic(ctx: Context): String =
        "${ctx.discoveryPrefix}/sensor/${ctx.nodeId}/battery/config"

    /** 温度 Discovery 配置 topic：`<prefix>/sensor/<node>/temperature/config`。 */
    fun temperatureConfigTopic(ctx: Context): String =
        "${ctx.discoveryPrefix}/sensor/${ctx.nodeId}/temperature/config"

    /** 开关控制 topic（HA → 手机，5.2 订阅）：`blindcast/<node>/screen/set`。 */
    fun screenCommandTopic(nodeId: String = DEFAULT_NODE_ID): String =
        "$STATE_BASE/$nodeId/screen/set"

    /** 开关状态 topic（手机 → HA，5.2 发布）：`blindcast/<node>/screen/state`。 */
    fun screenStateTopic(nodeId: String = DEFAULT_NODE_ID): String =
        "$STATE_BASE/$nodeId/screen/state"

    /** 电量状态 topic（5.2 发布）：`blindcast/<node>/battery/state`。 */
    fun batteryStateTopic(nodeId: String = DEFAULT_NODE_ID): String =
        "$STATE_BASE/$nodeId/battery/state"

    /** 温度状态 topic（5.2 发布）：`blindcast/<node>/temperature/state`。 */
    fun temperatureStateTopic(nodeId: String = DEFAULT_NODE_ID): String =
        "$STATE_BASE/$nodeId/temperature/state"

    /** 在线状态 topic（birth/will，见 [HaStatePublisher]）。 */
    fun availabilityTopic(nodeId: String = DEFAULT_NODE_ID): String =
        "$STATE_BASE/$nodeId/availability"

    /**
     * Web 直达链接：基址 + `?token=xxx`（token 为空则原样返回基址）。
     * 该值写入 device 块 `configuration_url`，HA 设备卡片一点即达。
     */
    fun configurationUrl(webBaseUrl: String, token: String): String {
        val base = webBaseUrl.trim().trimEnd('/')
        if (base.isEmpty() || token.isBlank()) return base
        val sep = if (base.contains('?')) '&' else '?'
        return "$base${sep}token=${token.trim()}"
    }

    // ------------------------------------------------------------------
    // Discovery 包构造
    // ------------------------------------------------------------------

    /** 一次性构造全部 3 条 Discovery 配置包（switch + battery + temperature）。 */
    fun buildAll(ctx: Context): List<DiscoveryMessage> = listOf(
        DiscoveryMessage(switchConfigTopic(ctx), buildSwitchConfig(ctx)),
        DiscoveryMessage(batteryConfigTopic(ctx), buildBatteryConfig(ctx)),
        DiscoveryMessage(temperatureConfigTopic(ctx), buildTemperatureConfig(ctx)),
    )

    /** `switch.blindcast_screen` 配置包。 */
    fun buildSwitchConfig(ctx: Context): String {
        val device = buildDeviceBlock(ctx)
        return "{" +
            "\"name\":\"BlindCast Screen\"," +
            "\"object_id\":\"${esc(ctx.nodeId)}_screen\"," +
            "\"unique_id\":\"${esc(ctx.nodeId)}_screen\"," +
            "\"command_topic\":\"${esc(screenCommandTopic(ctx.nodeId))}\"," +
            "\"state_topic\":\"${esc(screenStateTopic(ctx.nodeId))}\"," +
            "\"payload_on\":\"ON\"," +
            "\"payload_off\":\"OFF\"," +
            "\"state_on\":\"ON\"," +
            "\"state_off\":\"OFF\"," +
            "\"availability_topic\":\"${esc(availabilityTopic(ctx.nodeId))}\"," +
            "\"payload_available\":\"online\"," +
            "\"payload_not_available\":\"offline\"," +
            "\"device\":$device" +
            "}"
    }

    /** `sensor.blindcast_battery` 配置包（device_class=battery，单位 %）。 */
    fun buildBatteryConfig(ctx: Context): String {
        val device = buildDeviceBlock(ctx)
        return "{" +
            "\"name\":\"BlindCast Battery\"," +
            "\"object_id\":\"${esc(ctx.nodeId)}_battery\"," +
            "\"unique_id\":\"${esc(ctx.nodeId)}_battery\"," +
            "\"device_class\":\"battery\"," +
            "\"state_class\":\"measurement\"," +
            "\"unit_of_measurement\":\"%\"," +
            "\"state_topic\":\"${esc(batteryStateTopic(ctx.nodeId))}\"," +
            "\"availability_topic\":\"${esc(availabilityTopic(ctx.nodeId))}\"," +
            "\"payload_available\":\"online\"," +
            "\"payload_not_available\":\"offline\"," +
            "\"device\":$device" +
            "}"
    }

    /** `sensor.blindcast_temperature` 配置包（device_class=temperature，单位 °C）。 */
    fun buildTemperatureConfig(ctx: Context): String {
        val device = buildDeviceBlock(ctx)
        return "{" +
            "\"name\":\"BlindCast Temperature\"," +
            "\"object_id\":\"${esc(ctx.nodeId)}_temperature\"," +
            "\"unique_id\":\"${esc(ctx.nodeId)}_temperature\"," +
            "\"device_class\":\"temperature\"," +
            "\"state_class\":\"measurement\"," +
            "\"unit_of_measurement\":\"°C\"," +
            "\"state_topic\":\"${esc(temperatureStateTopic(ctx.nodeId))}\"," +
            "\"availability_topic\":\"${esc(availabilityTopic(ctx.nodeId))}\"," +
            "\"payload_available\":\"online\"," +
            "\"payload_not_available\":\"offline\"," +
            "\"device\":$device" +
            "}"
    }

    /** HA device 块：manufacturer / model / configuration_url（任务包铁线）。 */
    fun buildDeviceBlock(ctx: Context): String {
        val url = configurationUrl(ctx.webBaseUrl, ctx.token)
        return "{" +
            "\"identifiers\":[\"${esc(ctx.nodeId)}\"]," +
            "\"name\":\"$DEVICE_NAME\"," +
            "\"manufacturer\":\"$MANUFACTURER\"," +
            "\"model\":\"$MODEL\"," +
            "\"sw_version\":\"$SW_VERSION\"," +
            "\"configuration_url\":\"${esc(url)}\"" +
            "}"
    }

    /** 最小 JSON 字符串转义（引号/反斜杠/控制字符；中文与 ° 等直接透传 UTF-8）。 */
    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}

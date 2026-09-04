package com.erl.blindcast.core.ha

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.SocketException
import kotlin.math.min

/**
 * Broker 连接配置（Slice 5.1）。
 * @param host Broker 地址（如 `192.168.1.50`，不带协议头）。
 * @param port Broker 端口（默认 `1883`）。
 * @param username 用户名（可选，为空即匿名）。
 * @param password 密码（可选）。
 * @param clientId 客户端 ID（为空则自动生成 `blindcast-<8位hex>`；同 broker 下勿重复）。
 * @param keepAliveSec 心跳间隔秒（Paho 自动发 PINGREQ；钳制 10..120）。
 * @param connectionTimeoutSec 单次 TCP 建连超时秒（钳制 5..60）。
 */
data class HaMqttConfig(
    val host: String,
    val port: Int = 1883,
    val username: String? = null,
    val password: String? = null,
    val clientId: String? = null,
    val keepAliveSec: Int = 30,
    val connectionTimeoutSec: Int = 10,
)

/**
 * Last-Will 遗嘱（连接参数，随 CONNECT 注册；异常掉线时 broker 代发）。
 * [HaStatePublisher.offlineWill] 产出标准的 availability-offline 遗嘱。
 */
data class HaMqttWill(
    val topic: String,
    val payload: String,
    val qos: Int = 1,
    val retained: Boolean = true,
)

/**
 * 轻量 MQTT 3.1.1 客户端（Slice 5.1 · 基于 Eclipse Paho，经版本目录引入）。
 *
 * ## 能力
 * - [connect] / [disconnect] / [publish] / [subscribe] / [unsubscribe]；
 * - 断线自动重连：Paho 原生自动重连保持关闭，由本对象统一做**指数退避**
 *   （1s → 2s → 4s … 上限 60s，成功即清零），重连后自动恢复 [subscribe] 登记的订阅；
 * - 心跳 keepalive：透传 [HaMqttConfig.keepAliveSec] 给 Paho（自动 PINGREQ）；
 * - [lastError] 记录最近一次失败，成功建连后清零（与 `BlindCastServer` 同语义）；
 * - 消息分发：支持 MQTT 通配符（`+` / `#`）的订阅登记 + 全局 [onMessage] 监听。
 *
 * ## 约束
 * - [connect] 内部是阻塞式 TCP 建连，**必须在后台线程调用**（6.2 由 ViewModel 协程承载）；
 * - 仅做能力封装，不接 UI、不碰采集，开关订阅联动与传感器上报在 5.2 落子；
 * - 失败永不抛崩溃：同步 [connect] 的首次失败返回 false（同时后台退避重试），
 *   [publish]/[subscribe] 离线时返回 false 并记录 [lastError]。
 */
object HaMqttClient {

    private const val TAG = "BlindCast-HaMqtt"

    /** 退避基线 1s，指数上限 60s。 */
    private const val BACKOFF_BASE_MS = 1_000L
    private const val BACKOFF_MAX_MS = 60_000L

    /** 是否已建连（volatile，跨线程可见）。 */
    @Volatile
    var isConnected: Boolean = false
        private set

    /** 最近一次失败；最近一次成功建连后清零，失败永不崩溃。 */
    @Volatile
    var lastError: Throwable? = null
        private set

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var paho: MqttClient? = null
    private var activeServerUri: String? = null
    private var activeConfig: HaMqttConfig? = null
    private var activeWill: HaMqttWill? = null
    private var wantReconnect: Boolean = false
    private var reconnectAttempts: Int = 0
    private var retryJob: Job? = null

    /** 待恢复的订阅（filter → qos；重连后自动重订）。 */
    private val subscriptions = LinkedHashMap<String, Int>()

    /** 按订阅登记的消息处理器（filter → handler；支持 `+`/`#` 匹配）。 */
    private val handlers = LinkedHashMap<String, (ByteArray) -> Unit>()

    /** 全局消息监听（6.2/5.2 可选注册；异常被吞掉只记 log）。 */
    @Volatile
    private var globalListener: ((topic: String, payload: ByteArray) -> Unit)? = null

    private val callback = object : MqttCallback {
        override fun connectionLost(cause: Throwable?) {
            isConnected = false
            lastError = cause ?: SocketException("mqtt connection lost")
            Log.w(TAG, "connection lost", cause)
            synchronized(lock) { scheduleRetryLocked() }
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            val bytes: ByteArray = message.payload ?: ByteArray(0)
            runCatching { globalListener?.invoke(topic, bytes) }
                .onFailure { Log.w(TAG, "globalListener threw", it) }
            val matched = synchronized(lock) {
                handlers.filterKeys { topicMatches(it, topic) }.values.toList()
            }
            for (h in matched) {
                runCatching { h(bytes) }.onFailure { Log.w(TAG, "handler threw for $topic", it) }
            }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) {
            // QoS1 发布确认，无需动作。
        }
    }

    /**
     * 建连（同步阻塞，必须后台线程调用；幂等：已连直接 true）。
     * @param will Last-Will（通常传 [HaStatePublisher.offlineWill]）。
     * @param autoReconnect 是否开启指数退避断线重连（默认开；[disconnect] 关闭）。
     * @param onMessage 全局消息监听（可空；覆盖式设置）。
     * @return 本次建连成功 true；失败 false（已记 [lastError]，后台按退避自动重试）。
     */
    fun connect(
        config: HaMqttConfig,
        will: HaMqttWill? = null,
        autoReconnect: Boolean = true,
        onMessage: ((topic: String, payload: ByteArray) -> Unit)? = null,
    ): Boolean {
        synchronized(lock) {
            if (isConnected) return true
            wantReconnect = autoReconnect
            activeConfig = config
            activeWill = will
            if (onMessage != null) globalListener = onMessage
            cancelRetryLocked()
            return try {
                ensureClientLocked(config)
                doConnectLocked()
                true
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "connect failed, retry in background", t)
                scheduleRetryLocked()
                false
            }
        }
    }

    /** 断开并关闭客户端（幂等）：停退避任务、发 DISCONNECT、释放 Paho 资源。 */
    fun disconnect() {
        val client = synchronized(lock) {
            wantReconnect = false
            cancelRetryLocked()
            val c = paho
            paho = null
            activeServerUri = null
            isConnected = false
            c
        }
        runCatching { if (client?.isConnected == true) client.disconnect() }
        runCatching { client?.close() }
    }

    /**
     * 发布文本消息。
     * @return 发送成功 true；未建连/异常 false（记 [lastError]）。
     */
    fun publish(topic: String, payload: String, qos: Int = 1, retained: Boolean = false): Boolean {
        val client = synchronized(lock) { paho }
        if (client == null || !client.isConnected) {
            lastError = IllegalStateException("HaMqttClient: publish while offline ($topic)")
            return false
        }
        return runCatching {
            val msg = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                this.qos = qos.coerceIn(0, 2)
                isRetained = retained
            }
            client.publish(topic, msg)
            true
        }.getOrElse {
            lastError = it
            Log.w(TAG, "publish failed: $topic", it)
            false
        }
    }

    /**
     * 订阅 topic filter（登记即持久：重连后自动重订）。
     * @param handler 该 filter 专属处理器（可空；全局监听见 [connect]）。
     * @return broker 侧订阅成功 true；离线时登记为 pending 返回 false（重连后自动生效）。
     */
    fun subscribe(filter: String, qos: Int = 1, handler: ((ByteArray) -> Unit)? = null): Boolean {
        synchronized(lock) {
            subscriptions[filter] = qos.coerceIn(0, 2)
            if (handler != null) handlers[filter] = handler
        }
        val client = synchronized(lock) { paho }
        if (client == null || !client.isConnected) return false
        return runCatching {
            client.subscribe(filter, qos.coerceIn(0, 2))
            true
        }.getOrElse {
            lastError = it
            Log.w(TAG, "subscribe failed: $filter", it)
            false
        }
    }

    /** 取消订阅（同时移除登记与处理器；离线时仅移除登记）。 */
    fun unsubscribe(filter: String): Boolean {
        synchronized(lock) {
            subscriptions.remove(filter)
            handlers.remove(filter)
        }
        val client = synchronized(lock) { paho }
        if (client == null || !client.isConnected) return false
        return runCatching {
            client.unsubscribe(filter)
            true
        }.getOrElse {
            lastError = it
            false
        }
    }

    /** 注册/覆盖全局消息监听（线程安全；回调异常只记 log）。 */
    fun setOnMessageListener(listener: ((topic: String, payload: ByteArray) -> Unit)?) {
        globalListener = listener
    }

    /**
     * MQTT topic filter 匹配（供分发与单测）：
     * `filter == "#"` 全匹配；`+` 占一层；`#` 须为末段、吞掉剩余所有层。
     */
    fun topicMatches(filter: String, topic: String): Boolean {
        if (filter == "#") return true
        val f = filter.split('/')
        val t = topic.split('/')
        var i = 0
        var j = 0
        while (i < f.size) {
            val seg = f[i]
            if (seg == "#") return true
            if (j >= t.size) return false
            if (seg != "+" && seg != t[j]) return false
            i++
            j++
        }
        return j == t.size
    }

    // ------------------------------------------------------------------
    // 内部：建连与退避
    // ------------------------------------------------------------------

    private fun ensureClientLocked(config: HaMqttConfig) {
        val uri = "tcp://${config.host.trim()}:${config.port}"
        if (paho != null && activeServerUri != uri) {
            runCatching { paho?.close() }
            paho = null
        }
        if (paho == null) {
            require(config.host.isNotBlank()) { "mqtt host is blank" }
            require(config.port in 1..65535) { "mqtt port out of range: ${config.port}" }
            activeServerUri = uri
            val id = config.clientId?.takeIf { it.isNotBlank() } ?: defaultClientId()
            paho = MqttClient(uri, id, MemoryPersistence()).apply { setCallback(callback) }
        }
    }

    private fun doConnectLocked() {
        val client = paho ?: throw IllegalStateException("mqtt client not created")
        val cfg = activeConfig ?: throw IllegalStateException("mqtt config missing")
        if (client.isConnected) {
            onConnectedLocked()
            return
        }
        val opts = MqttConnectOptions().apply {
            isCleanSession = true
            keepAliveInterval = cfg.keepAliveSec.coerceIn(10, 120)
            connectionTimeout = cfg.connectionTimeoutSec.coerceIn(5, 60)
            if (!cfg.username.isNullOrBlank()) {
                userName = cfg.username
                if (cfg.password != null) password = cfg.password.toCharArray()
            }
            activeWill?.let { w ->
                setWill(w.topic, w.payload.toByteArray(Charsets.UTF_8), w.qos.coerceIn(0, 2), w.retained)
            }
        }
        client.connect(opts)
        onConnectedLocked()
    }

    private fun onConnectedLocked() {
        reconnectAttempts = 0
        lastError = null
        isConnected = true
        for ((filter, qos) in subscriptions) {
            runCatching { paho?.subscribe(filter, qos) }
                .onFailure { Log.w(TAG, "resubscribe failed: $filter", it) }
        }
        Log.i(TAG, "connected to $activeServerUri")
    }

    private fun backoffMs(): Long {
        val shift = min(reconnectAttempts.coerceAtLeast(0), 6)
        return min(BACKOFF_BASE_MS shl shift, BACKOFF_MAX_MS)
    }

    private fun scheduleRetryLocked() {
        if (!wantReconnect) return
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (true) {
                delay(backoffMs())
                val done = runCatching { attemptReconnect() }.getOrDefault(false)
                if (done) break
                if (!wantReconnect) break
            }
        }
    }

    private fun attemptReconnect(): Boolean {
        synchronized(lock) {
            if (!wantReconnect || isConnected) return true
            return try {
                val cfg = activeConfig ?: return false
                ensureClientLocked(cfg)
                doConnectLocked()
                true
            } catch (t: Throwable) {
                lastError = t
                reconnectAttempts++
                Log.w(TAG, "reconnect failed (attempt $reconnectAttempts)", t)
                false
            }
        }
    }

    private fun cancelRetryLocked() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun defaultClientId(): String {
        val hex = (0 until 8).map { "0123456789abcdef".random() }.joinToString("")
        return "blindcast-$hex"
    }
}

package com.erl.blindcast.core.clipboard

/**
 * 剪贴板读取结果的中转持有者（进程内静态）。
 *
 * Android 10+ 后台应用读剪贴板受限，须由**前台 Activity** 读取；[ClipboardBridgeActivity]
 * 读完后把结果发布到这里，服务侧 [com.erl.blindcast.core.server.routes.ClipboardApiRoute]
 * 在超时窗口内轮询本持有者拿到新鲜结果（对齐原版 `ReadClipboardActivity` 经 Binder 回传的语义，
 * 但用进程内静态替代 Binder，因为读写同进程）。
 */
object ClipboardBridge {

    /** 最近一次读取的文本（`null` = 读不到/无文本）。 */
    @Volatile
    var lastText: String? = null
        private set

    /** 最近一次读取是否拿到非空 clip。 */
    @Volatile
    var lastAvailable: Boolean = false
        private set

    /** 最近一次读取的时间戳（ms）。 */
    @Volatile
    var lastAt: Long = 0L
        private set

    fun publish(text: String?, available: Boolean) {
        lastText = text
        lastAvailable = available
        lastAt = System.currentTimeMillis()
    }
}

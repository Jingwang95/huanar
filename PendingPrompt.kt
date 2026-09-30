package com.huanar.le

/**
 * 待确认的付款。监听服务解析出金额后放这里，然后把悬浮窗拉起来。
 * 悬浮窗 Activity 从 overlay 布局启动（非主界面模式）时从这里取数据。
 */
object PendingPrompt {

    @Volatile
    var current: Hit? = null
        private set

    /** 同一个来源短时间内不重复弹窗（比如微信连发两条通知） */
    private var lastAmount = -1.0
    private var lastAt = 0L

    fun put(hit: Hit): Boolean {
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(hit.amount - lastAmount) < 0.001 && now - lastAt < 2000) {
            return false
        }
        lastAmount = hit.amount
        lastAt = now
        current = hit
        return true
    }

    fun take(): Hit? {
        val h = current
        current = null
        return h
    }

    fun peek(): Hit? = current

    fun clear() { current = null }
}

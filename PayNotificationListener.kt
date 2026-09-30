package com.huanar.le

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 监听通知栏，抓付款通知。
 *
 * 注意：这里读的是**通知栏内容**，不是微信聊天记录——后者系统不允许任何应用读取。
 * 微信/支付宝付款成功后会发一条通知，本服务在它出现的瞬间拿到文字并解析金额。
 */
class PayNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "HuaNaErLe"

        /** 让界面能显示"权限是否真的生效" */
        @Volatile
        var connected: Boolean = false
            private set

        fun isEnabled(ctx: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val me = ComponentName(ctx, PayNotificationListener::class.java)
            return flat.contains(me.flattenToString()) ||
                    flat.contains(ctx.packageName + "/" + PayNotificationListener::class.java.name)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        Log.i(TAG, "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        Log.i(TAG, "通知监听已断开")
        // 系统有时候会断，主动请求重连
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { requestRebind(ComponentName(this, PayNotificationListener::class.java)) } catch (e: Exception) { }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        try {
            handle(n)
        } catch (e: Exception) {
            Log.w(TAG, "解析通知出错: " + e.message)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (!PaySources.all().contains(pkg)) return

        val store = Store.get(this)
        if (!store.watchEnabled()) return

        val extras: Bundle = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        // 大文本形态通常信息最全，拼在一起让规则自己找
        val body = sequenceOf(text, bigText, sub)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")

        if (title.isBlank() && body.isBlank()) return

        store.markSeen()

        // 把用户自定义的规则交给解析器（他改过规则就用他的）
        StoreHolder.customRules = store.customRules()

        val hit = Rules.parse(pkg, title, body) ?: return

        // 去掉重复（微信有时连发两条）
        if (!PendingPrompt.put(hit)) return
        // 已经记过一模一样的，5 分钟内不再提示
        if (store.isDuplicate(hit.amount, hit.merchant, hit.source)) return

        store.bumpHit()
        Log.i(TAG, "抓到付款: " + hit.amount + " / " + hit.merchant + " / " + hit.ruleName)

        showPrompt(hit)
    }

    /** 把分类小窗拉起来。行得通的前提是用户授予了「后台弹出界面/悬浮窗」权限。 */
    private fun showPrompt(hit: Hit) {
        val i = Intent()
        i.setClassName(
            this,
            "com.huanar.le.OverlayAlias"
        )
        i.action = "com.huanar.le.action.PROMPT"
        i.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        i.putExtra("amount", hit.amount)
        i.putExtra("store", hit.merchant)
        i.putExtra("source", hit.source)
        try {
            startActivity(i)
        } catch (e: Exception) {
            // 没给悬浮窗权限时系统会拦下来，这时候只能等用户自己打开 App 补录
            Log.w(TAG, "拉起分类窗失败（多半是缺悬浮窗权限）: " + e.message)
        }
    }
}

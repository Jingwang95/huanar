package com.huanar.le

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val T_EXPENSE = "expense"
const val T_INCOME = "income"

/**
 * 一笔记录。
 * @param source 来源：微信 / 支付宝 / 农业银行 / 手动
 * @param store  商家名（从通知里抠出来的）
 * @param catId  分类 id
 */
data class Tx(
    var id: String = "",
    var type: String = T_EXPENSE,
    var amount: Double = 0.0,
    var catId: String = "other",
    var date: String = "",          // yyyy-MM-dd
    var pay: String = "",           // 支付方式/来源
    var store: String = "",         // 商家
    var note: String = "",
    var ts: Long = 0L,
    var src: String = "auto"        // auto = 通知抓的, manual = 手动补的
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type); put("amount", amount); put("catId", catId)
        put("date", date); put("pay", pay); put("store", store); put("note", note)
        put("ts", ts); put("src", src)
    }

    companion object {
        fun from(o: JSONObject) = Tx(
            id = o.optString("id"),
            type = o.optString("type", T_EXPENSE),
            amount = o.optDouble("amount", 0.0),
            catId = o.optString("catId", "other"),
            date = o.optString("date"),
            pay = o.optString("pay"),
            store = o.optString("store"),
            note = o.optString("note"),
            ts = o.optLong("ts"),
            src = o.optString("src", "auto")
        )
    }
}

/**
 * 存储层：SharedPreferences + Base64(JSON)。
 * 用 Base64 是为了避免换行/引号在 XML 存储里出问题。
 * 数据量按"一天 10 笔、十年"估算约 3.6 万条，JSON 字符串完全扛得住。
 */
class Store private constructor(ctx: Context) {

    private val sp = ctx.applicationContext
        .getSharedPreferences("huanar", Context.MODE_PRIVATE)

    companion object {
        private const val K_TX = "tx"
        private const val K_CATS = "cats"
        private const val K_BUDGET = "budget"
        private const val K_RULES = "rules"
        private const val K_WATCH = "watch_pkgs"

        @Volatile private var inst: Store? = null

        fun get(ctx: Context): Store = inst ?: synchronized(this) {
            inst ?: Store(ctx).also { inst = it }
        }
    }

    // ---------------- 记录 ----------------

    var txs: MutableList<Tx> = mutableListOf()
        private set

    init {
        load()
    }

    private fun load() {
        txs = mutableListOf()
        val raw = sp.getString(K_TX, null) ?: return
        try {
            val arr = JSONArray(String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8))
            for (i in 0 until arr.length()) txs.add(Tx.from(arr.getJSONObject(i)))
        } catch (e: Exception) {
            // 数据坏了就当空的，不要让 App 起不来
            txs = mutableListOf()
        }
    }

    private fun persist() {
        val arr = JSONArray()
        txs.forEach { arr.put(it.toJson()) }
        val b64 = Base64.encodeToString(arr.toString().toByteArray(Charsets.UTF_8), Base64.DEFAULT)
        sp.edit().putString(K_TX, b64).apply()
    }

    fun add(tx: Tx) {
        if (tx.id.isEmpty()) tx.id = newId()
        if (tx.ts == 0L) tx.ts = System.currentTimeMillis()
        txs.add(tx)
        persist()
    }

    fun update(tx: Tx) {
        val i = txs.indexOfFirst { it.id == tx.id }
        if (i >= 0) { txs[i] = tx; persist() }
    }

    fun remove(id: String) {
        txs.removeAll { it.id == id }
        persist()
    }

    fun removeAll() {
        txs.clear(); persist()
    }

    fun byId(id: String): Tx? = txs.firstOrNull { it.id == id }

    fun month(ym: String): List<Tx> = txs.filter { it.date.startsWith(ym) }

    /** 防重复：同一来源、同一天、同金额、同商家，5 分钟内视为重复 */
    fun isDuplicate(amount: Double, store: String, source: String): Boolean {
        val now = System.currentTimeMillis()
        return txs.any {
            kotlin.math.abs(it.amount - amount) < 0.001 &&
                    it.store == store &&
                    (it.pay == source || it.pay.isEmpty() || source.isEmpty()) &&
                    now - it.ts < 5 * 60 * 1000
        }
    }

    fun newId(): String = "t" + System.currentTimeMillis().toString(36) +
            Integer.toHexString((Math.random() * 0xFFFF).toInt())

    // ---------------- 分类 ----------------

    fun cats(): MutableList<Cat> {
        val raw = sp.getString(K_CATS, null) ?: return Cats.DEFAULTS.toMutableList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Cat>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                val def = Cats.DEFAULTS.firstOrNull { it.id == id }
                out.add(
                    Cat(
                        id = id,
                        name = o.optString("name", def?.name ?: id),
                        emoji = o.optString("emoji", def?.emoji ?: "❓"),
                        color = o.optString("color", def?.color ?: "#94A3B8"),
                        keys = def?.keys ?: emptyList()
                    )
                )
            }
            if (out.isEmpty()) Cats.DEFAULTS.toMutableList() else out
        } catch (e: Exception) {
            Cats.DEFAULTS.toMutableList()
        }
    }

    fun saveCats(list: List<Cat>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(K_CATS, arr.toString()).apply()
    }

    // ---------------- 预算 ----------------

    fun budget(ym: String): Double {
        val raw = sp.getString(K_BUDGET, "{}") ?: "{}"
        return try { JSONObject(raw).optDouble(ym, 0.0) } catch (e: Exception) { 0.0 }
    }

    fun setBudget(ym: String, v: Double) {
        val raw = sp.getString(K_BUDGET, "{}") ?: "{}"
        val o = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
        if (v > 0) o.put(ym, v) else o.remove(ym)
        sp.edit().putString(K_BUDGET, o.toString()).apply()
    }

    // ---------------- 自定义规则 ----------------

    /** 返回用户自定义的规则（JSON），没有就返回 null 表示用内置 */
    fun customRules(): List<PayRule>? {
        val raw = sp.getString(K_RULES, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            if (arr.length() == 0) return null
            val out = mutableListOf<PayRule>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    PayRule(
                        name = o.optString("name"),
                        pattern = o.optString("pattern"),
                        amountGroup = o.optInt("amountGroup", 1),
                        merchantGroup = o.optInt("merchantGroup", 0),
                        income = o.optBoolean("income", false),
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
            out
        } catch (e: Exception) { null }
    }

    fun saveCustomRules(list: List<PayRule>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("name", r.name); put("pattern", r.pattern)
                put("amountGroup", r.amountGroup); put("merchantGroup", r.merchantGroup)
                put("income", r.income); put("enabled", r.enabled)
            })
        }
        sp.edit().putString(K_RULES, arr.toString()).apply()
    }

    fun resetRules() {
        sp.edit().remove(K_RULES).apply()
    }

    // ---------------- 监控开关 ----------------

    fun watchEnabled(): Boolean = sp.getBoolean("watch", true)

    fun setWatchEnabled(b: Boolean) = sp.edit().putBoolean("watch", b).apply()

    /** 监听服务是否收到过通知（用来判断权限是否真的生效） */
    fun markSeen() = sp.edit().putLong("last_hit", System.currentTimeMillis()).apply()

    fun lastHitAt(): Long = sp.getLong("last_hit", 0L)

    fun hitCount(): Int = sp.getInt("hit_count", 0)

    fun bumpHit() = sp.edit().putInt("hit_count", hitCount() + 1).apply()
}

// ---------------- 日期工具 ----------------

object D {
    private val ymFmt = SimpleDateFormat("yyyy-MM", Locale.CHINA)
    private val ymdFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun today(): String = ymdFmt.format(Date())

    fun thisMonth(): String = ymFmt.format(Date())

    fun shiftMonth(ym: String, delta: Int): String {
        val c = Calendar.getInstance()
        c.set(Calendar.YEAR, ym.substring(0, 4).toInt())
        c.set(Calendar.MONTH, ym.substring(5, 7).toInt() - 1)
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.MONTH, delta)
        return ymFmt.format(c.time)
    }

    fun daysInMonth(ym: String): Int {
        val c = Calendar.getInstance()
        c.set(Calendar.YEAR, ym.substring(0, 4).toInt())
        c.set(Calendar.MONTH, ym.substring(5, 7).toInt() - 1)
        return c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    fun label(ym: String): String =
        ym.substring(0, 4) + "年" + ym.substring(5, 7).toInt() + "月"

    private val WEEK = arrayOf("日", "一", "二", "三", "四", "五", "六")

    fun dayLabel(ymd: String): String {
        return try {
            val c = Calendar.getInstance()
            c.set(Calendar.YEAR, ymd.substring(0, 4).toInt())
            c.set(Calendar.MONTH, ymd.substring(5, 7).toInt() - 1)
            c.set(Calendar.DAY_OF_MONTH, ymd.substring(8, 10).toInt())
            val w = WEEK[c.get(Calendar.DAY_OF_WEEK) - 1]
            when (ymd) {
                today() -> "今天 周$w"
                else -> "${ymd.substring(5, 7).toInt()}月${ymd.substring(8, 10).toInt()}日 周$w"
            }
        } catch (e: Exception) { ymd }
    }
}

object M {
    fun two(n: Double): String = String.format(Locale.CHINA, "%,.2f", n)
    fun zero(n: Double): String = String.format(Locale.CHINA, "%,.0f", n)
}

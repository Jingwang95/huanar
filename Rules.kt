package com.huanar.le

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 一条识别规则。
 *
 * 设计原则一：**多套写法兜底**。微信/支付宝/银行的文案会随版本变化，
 * 所以每个来源都写多条规则，命中任意一条即可。
 *
 * 设计原则二：**规则要能在设置里改**。文案变了不用等更新，自己加一条就行，
 * 改完可以用「识别测试台」粘真实通知当场验证。
 *
 * @param amountGroup   第几个捕获组是金额
 * @param merchantGroup 第几个捕获组是商家名，0 表示没有
 */
data class PayRule(
    val name: String,
    val pattern: String,
    val amountGroup: Int = 1,
    val merchantGroup: Int = 2,
    val income: Boolean = false,
    val enabled: Boolean = true
)

/** 识别结果 */
data class Hit(
    val amount: Double,
    val merchant: String,
    val income: Boolean,
    val source: String,
    val ruleName: String,
    val raw: String
)

/**
 * 通知来源包名。只有这些包名的通知才会去解析，
 * 避免把聊天消息、群公告之类的误当付款。
 */
object PaySources {
    val WECHAT = listOf("com.tencent.mm")
    val ALIPAY = listOf("com.eg.android.AlipayGphone")
    val ABC = listOf("com.android.bankabc", "com.abc.bank", "cn.com.abc")
    val OTHER_BANKS = listOf(
        "cmb.pb",                          // 招商银行
        "com.icbc",                        // 工商银行
        "com.chinamworld.main",            // 建设银行
        "com.bankcomm.Bankcomm",           // 交通银行
        "com.boc.bocsoft.mobile.bocmobile" // 中国银行
    )

    fun all(): List<String> = WECHAT + ALIPAY + ABC + OTHER_BANKS

    fun label(pkg: String): String = when {
        WECHAT.contains(pkg) -> "微信"
        ALIPAY.contains(pkg) -> "支付宝"
        ABC.contains(pkg) -> "农业银行"
        OTHER_BANKS.contains(pkg) -> "银行"
        else -> pkg
    }

    /** 按来源分组返回规则：先试本来源的专属规则，再试通用的兜底规则 */
    fun rulesFor(pkg: String): List<PayRule> {
        val all = Rules.DEFAULTS
        val tag = when {
            WECHAT.contains(pkg) -> "微信"
            ALIPAY.contains(pkg) -> "支付宝"
            ABC.contains(pkg) -> "农行"
            OTHER_BANKS.contains(pkg) -> "银行"
            else -> return all
        }
        val own = all.filter { it.name.startsWith(tag) }
        val rest = all.filterNot { it.name.startsWith(tag) }
        return own + rest
    }
}

object Rules {

    /**
     * 金额写法：数字，允许千分位逗号和两位小数。
     * 写成常量拼进正则里，是为了让规则本身保持可读、可被用户直接编辑。
     */
    private const val NUM = "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)"

    /**
     * 商户名的字符范围：允许中文、字母、数字、少量符号、括号。
     * **故意不含"、"和"，"**，这样就不会把银行通知里的
     * "您尾号1234的账户于06月30日，向…" 整段当成商家名。
     */
    private const val WHO = "([\\u4e00-\\u9fa5A-Za-z0-9_\\-·&'()（）]{1,40}?)"

    val DEFAULTS: List<PayRule> = listOf(
        // ==================== 微信支付 ====================
        PayRule("微信·向商家付款", "向\\s*$WHO\\s*付款\\s*[¥￥]?\\s*$NUM\\s*元?", 2, 1),
        PayRule("微信·已付款给", "已(?:支付|付款)给\\s*$WHO\\s*[¥￥]?\\s*$NUM\\s*元?", 2, 1),
        PayRule("微信·商户收款", "$WHO\\s*(?:收款|已收款)\\s*[¥￥]?\\s*$NUM\\s*元?", 2, 1),
        PayRule("微信·付款成功含商家", "$WHO\\s*付款成功[，,]?\\s*(?:金额)?\\s*[¥￥]?\\s*$NUM\\s*元?", 2, 1),
        PayRule("微信·通用（商家+金额）", "$WHO\\s*[¥￥]\\s*$NUM", 2, 1),
        PayRule("微信·付款成功", "付款成功[，,]?\\s*(?:金额)?\\s*[¥￥]?\\s*$NUM\\s*元?", 1, 0),
        PayRule("微信·零钱支出", "零钱[^\\n]{0,12}?支出\\s*[¥￥]?\\s*$NUM\\s*元?", 1, 0),
        PayRule("微信·收款到账（收入）", "收款到账\\s*[¥￥]?\\s*$NUM\\s*元?", 1, 0, true),
        PayRule("微信·转账到账（收入）", "(?:转账)?到账\\s*[¥￥]?\\s*$NUM\\s*元?", 1, 0, true),

        // ==================== 支付宝 ====================
        PayRule("支付宝·向X付款", "向\\s*$WHO\\s*(?:付款|转账)\\s*[¥￥]?\\s*$NUM\\s*元?", 2, 1),
        PayRule("支付宝·X消费", "$WHO\\s*消费\\s*[¥￥]?\\s*$NUM\\s*元?", 2, 1),
        PayRule("支付宝·付款成功含商家", "$WHO\\s*(?:付款成功|支付成功)[，,]?\\s*[¥￥]?\\s*$NUM\\s*元?", 2, 1),
        PayRule("支付宝·通用（商家+金额）", "$WHO\\s*[¥￥]\\s*$NUM", 2, 1),
        PayRule("支付宝·付款成功", "(?:付款成功|支付成功)[，,\\s]*[^\\n]{0,20}?[¥￥]?\\s*$NUM\\s*元?", 1, 0),
        PayRule("支付宝·支出", "支出\\s*[¥￥]?\\s*$NUM\\s*元?", 1, 0),
        PayRule("支付宝·收款（收入）", "(?:收款|到账)\\s*[¥￥]?\\s*$NUM\\s*元?", 1, 0, true),

        // ==================== 农业银行 App 通知 ====================
        PayRule("农行·向X付款", "向\\s*$WHO\\s*付款\\s*(?:人民币)?\\s*[¥￥]?\\s*$NUM\\s*元?", 2, 1),
        PayRule("农行·在X消费", "在\\s*$WHO\\s*(?:消费|支付)\\s*(?:人民币)?\\s*[¥￥]?\\s*$NUM\\s*元?", 2, 1),
        PayRule("农行·快捷支付", "(?:快捷支付|网上支付|银联支付|云闪付)[，,\\s]*$WHO?\\s*(?:人民币)?\\s*[¥￥]?\\s*$NUM\\s*元?", 2, 1),
        PayRule("农行·支出扣款", "(?:支出|扣款|支取)\\s*(?:人民币)?\\s*[¥￥]?\\s*$NUM\\s*元?", 1, 0),
        PayRule("农行·通用（金额+商户）", "[¥￥]\\s*$NUM\\s*元?[，,\\s]*$WHO?", 1, 2),
        PayRule("农行·收入", "(?:收入|存入|转入)\\s*(?:人民币)?\\s*[¥￥]?\\s*$NUM\\s*元?", 1, 0, true),

        // ==================== 其他银行（兜底） ====================
        PayRule("银行·消费支出", "(?:消费|支出|扣款|支付)\\s*(?:人民币)?\\s*[¥￥]?\\s*$NUM\\s*元?", 1, 0),
        PayRule("银行·收入", "(?:收入|存入|转入|入账)\\s*(?:人民币)?\\s*[¥￥]?\\s*$NUM\\s*元?", 1, 0, true)
    )

    /** 命中就直接忽略——这些是广告/提醒，不是付款 */
    private val JUNK = listOf(
        "优惠券", "满减", "立减金", "领红包", "恭喜", "中奖", "点击查看",
        "账单已出", "还款提醒", "积分", "升级", "邀请", "下载", "安全提醒",
        "登录提醒", "验证码", "广告", "活动期间"
    )

    /** 退款/返还，不算消费 */
    private val NOT_EXPENSE = listOf("退款", "退回", "返还", "撤销")

    /**
     * 不是真实商户，是支付通道名。识别到就当"没有商家名"，
     * 否则所有扫码付款都会显示成"支付宝/财付通"，完全没法区分。
     */
    private val CHANNELS = listOf(
        "支付宝", "微信支付", "财付通", "银联", "云闪付", "网上支付", "快捷支付",
        "支付", "商户", "商家", "收款方", "付款方", "对方", "未知"
    )

    /**
     * 银行通知的固定前缀，解析前先削掉，
     * 否则"向支付宝付款"前面的"您尾号1234的账户于06月30日"会被当成商家名。
     */
    private val PREFIX_NOISE = listOf(
        Regex("您(?:的)?(?:尾号|卡号)\\s*\\d{2,6}\\s*的?(?:账户|卡)?"),
        Regex("(?:账户|卡号)\\s*(?:尾号)?\\s*\\d{2,6}\\s*的?(?:账户|卡)?"),
        Regex("于\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日"),
        Regex("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日\\s*\\d{1,2}[:：]\\d{2}"),
        Regex("(?:人民币)?(?:余额|可用余额)[:：]?\\s*[¥￥]?\\s*[0-9,.]+")
    )

    /**
     * 真正的解析入口。
     * @param pkg   通知来源包名
     * @param title 通知标题
     * @param text  通知正文
     */
    fun parse(pkg: String, title: String, text: String): Hit? {
        var raw = (title + " " + text).trim()
        if (raw.length < 4) return null
        if (!raw.any { it.isDigit() }) return null
        if (JUNK.any { raw.contains(it) }) return null

        PREFIX_NOISE.forEach { raw = it.replace(raw, " ") }
        raw = raw.replace(Regex("\\s+"), " ").trim()
        if (raw.length < 4) return null

        // 用户在设置里改过规则就用他的，否则用内置
        val pool = StoreHolder.customRules ?: PaySources.rulesFor(pkg)

        for (r in pool) {
            if (!r.enabled) continue
            val h = apply(r, raw, pkg)
            if (h != null) return h
        }
        return null
    }

    /** 供测试台调用：不管包名，全规则都试一遍 */
    fun parseAny(text: String): Hit? {
        val pkgs = listOf("com.tencent.mm", "com.eg.android.AlipayGphone", "com.android.bankabc")
        for (p in pkgs) {
            val h = parse(p, "", text)
            if (h != null) return h
        }
        return null
    }

    private fun apply(r: PayRule, raw: String, pkg: String): Hit? {
        val m: Matcher = try {
            Pattern.compile(r.pattern, Pattern.DOTALL).matcher(raw)
        } catch (e: Exception) {
            return null   // 用户改坏了正则，跳过这条
        }
        if (!m.find()) return null

        val amtStr = group(m, r.amountGroup) ?: return null
        val amount = amtStr.replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0.0 || amount > 9_999_999) return null

        var merchant = cleanMerchant(group(m, r.merchantGroup).orEmpty())

        // 退款/返还：不要记成消费
        if (NOT_EXPENSE.any { raw.contains(it) }) return null

        val income = r.income || looksLikeIncome(raw)

        // 没商家名的规则就认，有商家名但抠出来是支付通道（支付宝/财付通）的也认，
        // 这种情况用户自己写备注更靠谱。
        return Hit(
            amount = amount,
            merchant = merchant,
            income = income,
            source = PaySources.label(pkg),
            ruleName = r.name,
            raw = raw
        )
    }

    private fun group(m: Matcher, idx: Int): String? {
        if (idx <= 0 || idx > m.groupCount()) return null
        return m.group(idx)?.trim()
    }

    private fun looksLikeIncome(raw: String): Boolean {
        if (raw.contains("支出") || raw.contains("付款") || raw.contains("消费") ||
            raw.contains("扣款") || raw.contains("支付成功") || raw.contains("已支付")
        ) return false
        return raw.contains("收款") || raw.contains("到账") || raw.contains("收入") ||
                raw.contains("转入") || raw.contains("入账")
    }

    /** 清理商家名里的噪音，认不出来就返回空串（表示"没识别到"） */
    private fun cleanMerchant(s: String): String {
        var t = s.trim()
        t = t.replace(Regex("^(?:商户|商家|对方|收款方|付款方)[:：]?"), "")
        t = t.replace(Regex("^[\"'“”‘’《》\\[\\]]+"), "")
        t = t.replace(Regex("[\"'“”‘’《》\\[\\]]+$"), "")
        t = t.replace(Regex("[，,。.；;：:、]+$"), "")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.length > 24) t = t.substring(0, 24)
        if (CHANNELS.contains(t)) return ""
        // 纯数字 / 纯符号，不是商户名
        if (t.isEmpty() || t.none { it.isLetter() }) return ""
        return t
    }
}

/**
 * 规则来源的小桥接。
 * Rules 是纯逻辑对象，不持有 Context；监听服务在解析前把用户自定义规则塞进来。
 */
object StoreHolder {
    @Volatile
    var customRules: List<PayRule>? = null
}

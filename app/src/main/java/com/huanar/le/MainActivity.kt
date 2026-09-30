package com.huanar.le

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())

    private var isOverlay = false
    private var month = D.thisMonth()
    private var tab = 0                       // 0记一笔 1明细 2报表 3设置
    private var pickedCat = "food"
    private var filterCat = "all"
    private var query = ""
    private var savedTx: Tx? = null

    private lateinit var cats: MutableList<Cat>

    // 主界面控件
    private lateinit var mLabel: TextView
    private lateinit var hdSpend: TextView
    private lateinit var hdCount: TextView
    private lateinit var hdAvg: TextView
    private lateinit var tabBar: LinearLayout
    private lateinit var paneAdd: View
    private lateinit var paneList: View
    private lateinit var paneReport: View
    private lateinit var paneSet: View
    private lateinit var headerBox: View

    private lateinit var fAmount: EditText
    private lateinit var fNote: EditText
    private lateinit var fGrid: GridLayout
    private lateinit var q: EditText
    private lateinit var filterChips: LinearLayout
    private lateinit var listBox: LinearLayout

    private lateinit var rpLabel: TextView
    private lateinit var rpTotal: TextView
    private lateinit var rpCount: TextView
    private lateinit var rpAvg: TextView
    private lateinit var rpMax: TextView
    private lateinit var rpIncome: TextView
    private lateinit var rpBudget: TextView
    private lateinit var rpBars: LinearLayout
    private lateinit var rpBarsHint: TextView
    private lateinit var rpCats: LinearLayout

    private lateinit var setBudget: EditText
    private lateinit var stNotify: TextView
    private lateinit var stOverlay: TextView
    private lateinit var stBattery: TextView
    private lateinit var stHit: TextView
    private lateinit var testInput: EditText
    private lateinit var testResult: TextView
    private lateinit var setInfo: TextView

    // 悬浮窗控件
    private var ovRoot: View? = null
    private var ovCard: View? = null
    private var ovAmount: TextView? = null
    private var ovStore: TextView? = null
    private var ovSource: TextView? = null
    private var ovGrid: GridLayout? = null
    private var ovDone: View? = null
    private var ovDoneText: TextView? = null

    private val autoDismiss = Runnable { if (isOverlay) finish() }

    // ============================================================
    // 生命周期
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        cats = store.cats()

        isOverlay = intent?.action == "com.huanar.le.action.PROMPT"

        if (isOverlay) setupOverlay() else setupMain()

        KeepAliveService.start(this)
        askNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (!isOverlay) {
            cats = store.cats()
            renderMain()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * 关键：悬浮窗用的是 singleInstance 的 activity-alias。
     * 如果它已经存在（比如用户还在微信里、上一笔的窗刚关掉），
     * 系统不会重新走 onCreate，而是走这里。
     * 不处理的话第二笔付款会显示上一笔的金额。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        if (intent.action == "com.huanar.le.action.PROMPT") {
            isOverlay = true
            if (ovGrid == null) {
                setupOverlay()
            } else {
                populateOverlay()
            }
        }
    }

    // ============================================================
    // 悬浮分类小窗
    // ============================================================

    private fun setupOverlay() {
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setContentView(R.layout.activity_overlay)

        ovRoot = findViewById(R.id.overlayRoot)
        ovCard = findViewById(R.id.overlayCard)
        ovAmount = findViewById(R.id.ovAmount)
        ovStore = findViewById(R.id.ovStore)
        ovSource = findViewById(R.id.ovSource)
        ovGrid = findViewById(R.id.ovGrid)
        ovDone = findViewById(R.id.ovDone)
        ovDoneText = findViewById(R.id.ovDoneText)
        findViewById<View>(R.id.ovClose).setOnClickListener { dismissOverlay() }

        // 点卡片外面＝取消
        ovRoot?.setOnTouchListener { _, _ -> true }
        ovCard?.setOnTouchListener { _, _ -> false }

        populateOverlay()
    }

    /** 把当前这笔付款填进小窗。onCreate 和 onNewIntent 都会走这里。 */
    private fun populateOverlay() {
        handler.removeCallbacks(autoDismiss)

        // 先复位到「选题」状态，避免上一次的确认画面残留
        ovGrid?.visibility = View.VISIBLE
        ovStore?.visibility = View.VISIBLE
        ovDone?.visibility = View.GONE

        val amt = intent?.getDoubleExtra("amount", -1.0) ?: -1.0
        val hit = PendingPrompt.peek()
        val amount = if (amt > 0) amt else (hit?.amount ?: -1.0)
        val storeName = intent?.getStringExtra("store").orEmpty().ifEmpty { hit?.merchant.orEmpty() }
        val source = intent?.getStringExtra("source").orEmpty().ifEmpty { hit?.source.orEmpty() }

        if (amount <= 0) {
            // 没有待确认的付款，直接关掉，别挡着用户
            finish()
            return
        }

        ovAmount?.text = M.two(amount)
        ovSource?.text = if (source.isEmpty()) "付款" else source
        ovStore?.text = if (storeName.isEmpty()) "未识别到商家" else storeName

        buildOverlayGrid(storeName)

        handler.postDelayed(autoDismiss, 20000)
    }

    private fun buildOverlayGrid(hintText: String) {
        val grid = ovGrid ?: return
        grid.removeAllViews()

        val guess = Cats.guess(hintText)
        val ordered = cats.sortedBy { if (it.id == guess) 0 else 1 }
        val show = ordered.take(14)

        val w = dp(58)
        val h = dp(68)
        val m = dp(3)
        show.forEach { c ->
            val v = layoutInflater.inflate(R.layout.item_cat, grid, false)
            v.findViewById<TextView>(R.id.catEmoji).text = c.emoji
            v.findViewById<TextView>(R.id.catName).text = c.name

            val lp = GridLayout.LayoutParams()
            lp.width = w
            lp.height = h
            lp.setMargins(m, m, m, m)
            v.layoutParams = lp

            if (c.id == guess) {
                // 高亮最可能的分类，但只是建议——还是要你点一下确认
                val bg = GradientDrawable()
                bg.cornerRadius = dp(11).toFloat()
                bg.setColor(Color.parseColor("#EAF0FF"))
                bg.setStroke(dp(2), Color.parseColor("#2F6BFF"))
                v.background = bg
                v.findViewById<TextView>(R.id.catName).setTextColor(Color.parseColor("#2F6BFF"))
            }
            v.setOnClickListener { saveFromOverlay(c) }
            grid.addView(v)
        }
    }

    private fun saveFromOverlay(c: Cat) {
        val amt = intent?.getDoubleExtra("amount", -1.0) ?: -1.0
        val hit = PendingPrompt.peek()
        val amount = if (amt > 0) amt else (hit?.amount ?: return)
        val storeName = intent?.getStringExtra("store").orEmpty().ifEmpty { hit?.merchant.orEmpty() }
        val source = intent?.getStringExtra("source").orEmpty().ifEmpty { hit?.source.orEmpty() }

        val tx = Tx(
            id = store.newId(),
            type = T_EXPENSE,
            amount = amount,
            catId = c.id,
            date = D.today(),
            pay = source,
            store = storeName,
            note = "",
            ts = System.currentTimeMillis(),
            src = "auto"
        )
        store.add(tx)
        savedTx = tx
        PendingPrompt.clear()

        // 显示确认 + 撤销
        handler.removeCallbacks(autoDismiss)
        ovGrid?.visibility = View.GONE
        ovStore?.visibility = View.GONE
        ovDone?.visibility = View.VISIBLE
        ovDoneText?.text = "已记下：${c.emoji} ${c.name}  ¥${M.two(amount)}"

        findViewById<View>(R.id.ovUndo).setOnClickListener {
            store.remove(tx.id)
            Toast.makeText(this, "已撤销", Toast.LENGTH_SHORT).show()
            finish()
        }
        handler.postDelayed(autoDismiss, 2200)
    }

    private fun dismissOverlay() {
        PendingPrompt.clear()
        finish()
    }

    override fun onBackPressed() {
        if (isOverlay) dismissOverlay() else super.onBackPressed()
    }

    // ============================================================
    // 主界面
    // ============================================================

    private fun setupMain() {
        setContentView(R.layout.activity_main)

        mLabel = findViewById(R.id.mLabel)
        hdSpend = findViewById(R.id.hdSpend)
        hdCount = findViewById(R.id.hdCount)
        hdAvg = findViewById(R.id.hdAvg)
        tabBar = findViewById(R.id.tabBar)
        paneAdd = findViewById(R.id.paneAdd)
        paneList = findViewById(R.id.paneList)
        paneReport = findViewById(R.id.paneReport)
        paneSet = findViewById(R.id.paneSet)
        headerBox = findViewById(R.id.headerBox)

        fAmount = findViewById(R.id.fAmount)
        fNote = findViewById(R.id.fNote)
        fGrid = findViewById(R.id.fGrid)
        q = findViewById(R.id.q)
        filterChips = findViewById(R.id.filterChips)
        listBox = findViewById(R.id.listBox)

        rpLabel = findViewById(R.id.rpLabel)
        rpTotal = findViewById(R.id.rpTotal)
        rpCount = findViewById(R.id.rpCount)
        rpAvg = findViewById(R.id.rpAvg)
        rpMax = findViewById(R.id.rpMax)
        rpIncome = findViewById(R.id.rpIncome)
        rpBudget = findViewById(R.id.rpBudget)
        rpBars = findViewById(R.id.rpBars)
        rpBarsHint = findViewById(R.id.rpBarsHint)
        rpCats = findViewById(R.id.rpCats)

        setBudget = findViewById(R.id.setBudget)
        stNotify = findViewById(R.id.stNotify)
        stOverlay = findViewById(R.id.stOverlay)
        stBattery = findViewById(R.id.stBattery)
        stHit = findViewById(R.id.stHit)
        testInput = findViewById(R.id.testInput)
        testResult = findViewById(R.id.testResult)
        setInfo = findViewById(R.id.setInfo)

        findViewById<View>(R.id.mPrev).setOnClickListener {
            month = D.shiftMonth(month, -1); renderMain()
        }
        findViewById<View>(R.id.mNext).setOnClickListener {
            month = D.shiftMonth(month, 1); renderMain()
        }
        mLabel.setOnClickListener {
            month = D.thisMonth(); renderMain(); toast("回到本月")
        }

        // 如果是从悬浮窗点进来的（点通知），直接进记一笔
        if (intent?.action == "com.huanar.le.action.PROMPT") {
            val hit = PendingPrompt.peek()
            if (hit != null) {
                fAmount.setText(M.two(hit.amount))
                fNote.setText(hit.merchant)
                pickedCat = Cats.guess(hit.merchant)
            }
        }

        findViewById<View>(R.id.fSave).setOnClickListener { saveFromForm() }
        q.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString().orEmpty().trim(); renderList()
            }
        })

        findViewById<View>(R.id.btnBudget).setOnClickListener {
            val v = setBudget.text.toString().toDoubleOrNull() ?: 0.0
            store.setBudget(month, v)
            toast(if (v > 0) "预算已保存" else "已取消预算")
            renderReport()
        }
        findViewById<View>(R.id.btnNotifyAccess).setOnClickListener { openNotificationAccess() }
        findViewById<View>(R.id.btnOverlayPerm).setOnClickListener { openOverlayPermission() }
        findViewById<View>(R.id.btnBattery).setOnClickListener { openBatterySettings() }
        findViewById<View>(R.id.btnOppoHelp).setOnClickListener { showOppoHelp() }
        findViewById<View>(R.id.btnTest).setOnClickListener { runTest() }
        findViewById<View>(R.id.btnRules).setOnClickListener { showRules() }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportCsv() }
        findViewById<View>(R.id.btnBackup).setOnClickListener { exportJson() }
        findViewById<View>(R.id.btnWipe).setOnClickListener { confirmWipe() }

        buildTabBar()
        selectTab(0)
    }

    private fun buildTabBar() {
        val names = listOf("✏️ 记一笔", "📋 明细", "📊 报表", "⚙️ 设置")
        tabBar.removeAllViews()
        names.forEachIndexed { i, n ->
            val tv = TextView(this)
            tv.text = n
            tv.textSize = 13f
            tv.setPadding(dp(13), dp(8), dp(13), dp(8))
            tv.setOnClickListener { selectTab(i) }
            tabBar.addView(tv)
        }
    }

    private fun selectTab(i: Int) {
        tab = i
        for (idx in 0 until tabBar.childCount) {
            val tv = tabBar.getChildAt(idx) as TextView
            val on = idx == i
            tv.setTextColor(if (on) Color.parseColor("#2F6BFF") else Color.parseColor("#9AA3B2"))
            tv.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            val bg = GradientDrawable()
            bg.cornerRadius = dp(9).toFloat()
            bg.setColor(if (on) Color.parseColor("#EAF0FF") else Color.TRANSPARENT)
            tv.background = bg
        }
        paneAdd.visibility = if (i == 0) View.VISIBLE else View.GONE
        paneList.visibility = if (i == 1) View.VISIBLE else View.GONE
        paneReport.visibility = if (i == 2) View.VISIBLE else View.GONE
        paneSet.visibility = if (i == 3) View.VISIBLE else View.GONE

        when (i) {
            0 -> renderAdd()
            1 -> renderList()
            2 -> renderReport()
            3 -> renderSettings()
        }
    }

    private fun renderMain() {
        renderHeader()
        renderAdd()
        if (tab == 1) renderList()
        if (tab == 2) renderReport()
        if (tab == 3) renderSettings()
    }

    private fun renderHeader() {
        mLabel.text = D.label(month)
        val list = store.month(month).filter { it.type == T_EXPENSE }
        val total = list.sumOf { it.amount }
        val dim = D.daysInMonth(month)
        val denom = if (month == D.thisMonth())
            java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH) else dim
        hdSpend.text = "本月支出 ${M.zero(total)}"
        hdCount.text = "笔数 ${list.size}"
        hdAvg.text = "日均 ${M.zero(if (denom > 0) total / denom else 0.0)}"
    }

    // ---------------- 记一笔 ----------------

    private fun renderAdd() {
        val grid = fGrid
        grid.removeAllViews()
        val w = dp(60)
        val h = dp(70)
        val m = dp(3)

        val used = store.txs.sortedByDescending { it.ts }.take(6)
            .map { it.catId }.distinct()
        val ordered = cats.sortedBy { if (used.contains(it.id)) 0 else 1 }.take(14)

        ordered.forEach { c ->
            val v = layoutInflater.inflate(R.layout.item_cat, grid, false)
            v.findViewById<TextView>(R.id.catEmoji).text = c.emoji
            v.findViewById<TextView>(R.id.catName).text = c.name
            val lp = GridLayout.LayoutParams()
            lp.width = w; lp.height = h
            lp.setMargins(m, m, m, m)
            v.layoutParams = lp
            applyCatStyle(v, c.id == pickedCat)
            v.setOnClickListener {
                pickedCat = c.id
                renderAdd()
            }
            grid.addView(v)
        }
    }

    private fun applyCatStyle(v: View, on: Boolean) {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(11).toFloat()
        bg.setColor(if (on) Color.parseColor("#EAF0FF") else Color.WHITE)
        bg.setStroke(dp(if (on) 2 else 1), Color.parseColor(if (on) "#2F6BFF" else "#E6E9F0"))
        v.background = bg
        v.findViewById<TextView>(R.id.catName)
            .setTextColor(Color.parseColor(if (on) "#2F6BFF" else "#6B7280"))
    }

    private fun saveFromForm() {
        val amount = fAmount.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
        if (amount <= 0) { toast("请填写金额"); return }
        val tx = Tx(
            id = store.newId(),
            type = T_EXPENSE,
            amount = amount,
            catId = pickedCat,
            date = D.today(),
            pay = "手动",
            store = "",
            note = fNote.text.toString().trim(),
            ts = System.currentTimeMillis(),
            src = "manual"
        )
        store.add(tx)
        fAmount.setText("")
        fNote.setText("")
        toast("已记下 ¥${M.two(amount)}")
        month = D.thisMonth()
        renderMain()
    }

    // ---------------- 明细 ----------------

    private fun renderList() {
        // 筛选 chips
        filterChips.removeAllViews()
        val all = Cat("all", "全部", "🗂", "#94A3B8")
        (listOf(all) + cats).forEach { c ->
            val tv = TextView(this)
            tv.text = "${c.emoji} ${c.name}"
            tv.textSize = 12f
            tv.setPadding(dp(11), dp(6), dp(11), dp(6))
            val on = filterCat == c.id
            val bg = GradientDrawable()
            bg.cornerRadius = dp(20).toFloat()
            bg.setColor(Color.parseColor(if (on) "#2F6BFF" else "#F4F6FB"))
            bg.setStroke(dp(1), Color.parseColor(if (on) "#2F6BFF" else "#E6E9F0"))
            tv.background = bg
            tv.setTextColor(Color.parseColor(if (on) "#FFFFFF" else "#6B7280"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = dp(6)
            tv.layoutParams = lp
            tv.setOnClickListener { filterCat = c.id; renderList() }
            filterChips.addView(tv)
        }

        // 数据
        var list = store.month(month)
        if (filterCat != "all") list = list.filter { it.catId == filterCat }
        if (query.isNotEmpty()) {
            list = list.filter {
                it.note.contains(query, true) || it.store.contains(query, true) ||
                        Cats.byId(cats, it.catId).name.contains(query, true)
            }
        }
        list = list.sortedByDescending { it.date + it.ts }

        listBox.removeAllViews()
        if (list.isEmpty()) {
            listBox.addView(hintView("这个月还没有符合条件的记录。\n付完款会自动弹窗，也可以在上一个标签手动补录。"))
            return
        }

        // 按日期分组
        val byDay = LinkedHashMap<String, MutableList<Tx>>()
        list.forEach { byDay.getOrPut(it.date) { mutableListOf() }.add(it) }

        byDay.forEach { (day, arr) ->
            val sum = arr.filter { it.type == T_EXPENSE }.sumOf { it.amount }
            val hd = TextView(this)
            hd.text = "${D.dayLabel(day)}        支出 ¥${M.two(sum)}"
            hd.textSize = 11.5f
            hd.setTextColor(Color.parseColor("#6B7280"))
            hd.setPadding(dp(14), dp(10), dp(14), dp(6))
            listBox.addView(hd)

            arr.forEach { tx -> listBox.addView(buildRow(tx)) }
        }
    }

    private fun buildRow(tx: Tx): View {
        val v = layoutInflater.inflate(R.layout.item_row, listBox, false)
        val c = Cats.byId(cats, tx.catId)
        v.findViewById<TextView>(R.id.rowIco).text = c.emoji
        v.findViewById<TextView>(R.id.rowTitle).text =
            tx.note.ifEmpty { tx.store.ifEmpty { c.name } }
        val srcTag = if (tx.src == "manual") "手动" else tx.pay.ifEmpty { "通知" }
        v.findViewById<TextView>(R.id.rowSub).text = "${c.name} · $srcTag"
        val amt = v.findViewById<TextView>(R.id.rowAmount)
        amt.text = (if (tx.type == T_INCOME) "+" else "-") + M.two(tx.amount)
        if (tx.type == T_INCOME) amt.setTextColor(Color.parseColor("#12A150"))
        v.setOnClickListener { editDialog(tx) }
        return v
    }

    private fun editDialog(tx: Tx) {
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(18), dp(8), dp(18), dp(8))

        val amountEt = EditText(this)
        amountEt.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        amountEt.setText(M.two(tx.amount))
        body.addView(labelView("金额"))
        body.addView(amountEt)

        val noteEt = EditText(this)
        noteEt.inputType = InputType.TYPE_CLASS_TEXT
        noteEt.setText(tx.note.ifEmpty { tx.store })
        body.addView(labelView("商家 / 备注"))
        body.addView(noteEt)

        val catNames = cats.map { "${it.emoji} ${it.name}" }.toTypedArray()
        val catIdx = cats.indexOfFirst { it.id == tx.catId }.coerceAtLeast(0)
        body.addView(labelView("当前分类：${cats[catIdx].name}（点下面的按钮换）"))

        val grid = GridLayout(this)
        grid.columnCount = 5
        cats.forEach { c ->
            val b = TextView(this)
            b.text = c.emoji
            b.textSize = 20f
            b.gravity = Gravity.CENTER
            val lp = GridLayout.LayoutParams()
            lp.width = dp(48); lp.height = dp(44)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            b.layoutParams = lp
            val on = c.id == tx.catId
            val bg = GradientDrawable()
            bg.cornerRadius = dp(10).toFloat()
            bg.setColor(Color.parseColor(if (on) "#EAF0FF" else "#F4F6FB"))
            bg.setStroke(dp(if (on) 2 else 1), Color.parseColor(if (on) "#2F6BFF" else "#E6E9F0"))
            b.background = bg
            b.setOnClickListener {
                tx.catId = c.id
                store.update(tx)
                // 重画一遍选中态
                val holder = b.parent as? GridLayout
                if (holder != null) {
                    val keep = holder.indexOfChild(b)
                    for (i in 0 until holder.childCount) {
                        val child = holder.getChildAt(i)
                        val bg2 = GradientDrawable()
                        bg2.cornerRadius = dp(10).toFloat()
                        bg2.setColor(Color.parseColor(if (i == keep) "#EAF0FF" else "#F4F6FB"))
                        bg2.setStroke(dp(if (i == keep) 2 else 1), Color.parseColor(if (i == keep) "#2F6BFF" else "#E6E9F0"))
                        child.background = bg2
                    }
                }
                toast("已改为 ${c.name}")
                renderMain()
            }
            grid.addView(b)
        }
        body.addView(grid)

        AlertDialog.Builder(this)
            .setTitle("修改记录")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("保存") { _, _ ->
                val a = amountEt.text.toString().replace(",", "").toDoubleOrNull() ?: tx.amount
                tx.amount = a
                tx.note = noteEt.text.toString().trim()
                store.update(tx)
                renderMain()
                toast("已更新")
            }
            .setNeutralButton("删除") { _, _ ->
                store.remove(tx.id); renderMain(); toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 报表 ----------------

    private fun renderReport() {
        val r = ReportGen.build(store, month)
        rpLabel.text = D.label(month) + " 支出"
        rpTotal.text = "¥" + M.zero(r.total)
        rpCount.text = "笔数\n${r.count}"
        rpAvg.text = "日均\n${M.zero(r.avgPerDay)}"
        rpMax.text = "最大单笔\n${M.zero(r.maxOne)}"
        rpIncome.text = "收入\n${M.zero(r.income)}"

        rpBudget.text = when {
            r.budget <= 0 -> "还没设预算。去「设置」填一个数，就能看到每天还能花多少。"
            r.overBudget -> "本月预算 ¥${M.zero(r.budget)}，已经超了 ¥${M.zero(-r.budgetLeft)}。后面几天收着点。"
            r.daysLeft > 0 -> "预算 ¥${M.zero(r.budget)}，还剩 ¥${M.zero(r.budgetLeft)}，之后每天可花约 ¥${M.zero(r.perDayLeft)}。"
            else -> "预算 ¥${M.zero(r.budget)}，没有超支。"
        }

        // 每日柱状
        rpBars.removeAllViews()
        val dim = D.daysInMonth(month)
        val maxV = maxOf(r.daily.drop(1).maxOrNull() ?: 0.0, 1.0)
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        for (d in 1..dim) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL

            val bar = View(this)
            val hp = if (r.daily[d] > 0) maxOf(dp(4), (r.daily[d] / maxV * dp(80)).toInt()) else dp(3)
            val lp = LinearLayout.LayoutParams(dp(6), hp)
            bar.layoutParams = lp
            val bg = GradientDrawable()
            bg.cornerRadius = dp(3).toFloat()
            bg.setColor(
                if (month == D.thisMonth() && d == today) Color.parseColor("#FF8A3D")
                else if (r.daily[d] > 0) Color.parseColor("#2F6BFF")
                else Color.parseColor("#E6E9F0")
            )
            bar.background = bg
            col.addView(bar)

            val lp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            col.layoutParams = lp2
            rpBars.addView(col)
        }

        val peak = r.daily.drop(1).maxOrNull() ?: 0.0
        val peakDay = r.daily.indexOfFirst { it == peak && it > 0 }
        rpBarsHint.text = if (r.total > 0 && peakDay > 0)
            "花得最多的一天是 $peakDay 日，¥${M.two(peak)}"
        else "这个月还没有支出记录"

        // 分类占比
        rpCats.removeAllViews()
        if (r.cats.isEmpty()) {
            rpCats.addView(hintView("还没有数据"))
        } else {
            r.cats.forEach { cs ->
                val wrap = LinearLayout(this)
                wrap.orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(12)
                wrap.layoutParams = lp

                val top = LinearLayout(this)
                top.orientation = LinearLayout.HORIZONTAL
                top.gravity = Gravity.CENTER_VERTICAL
                val name = TextView(this)
                name.text = "${cs.cat.emoji} ${cs.cat.name}"
                name.textSize = 13.5f
                name.setTextColor(Color.parseColor("#12151C"))
                name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val pct = TextView(this)
                val p = if (r.total > 0) cs.amount / r.total * 100 else 0.0
                pct.text = String.format(Locale.CHINA, "%.1f%%", p)
                pct.textSize = 11.5f
                pct.setTextColor(Color.parseColor("#9AA3B2"))
                val valTv = TextView(this)
                valTv.text = "  ¥" + M.two(cs.amount)
                valTv.textSize = 13.5f
                valTv.setTextColor(Color.parseColor("#12151C"))
                valTv.setTypeface(null, android.graphics.Typeface.BOLD)
                top.addView(name); top.addView(pct); top.addView(valTv)
                wrap.addView(top)

                val tbg = GradientDrawable()
                tbg.cornerRadius = dp(4).toFloat()
                tbg.setColor(Color.parseColor("#F4F6FB"))

                // 进度条：装进一个容器，填充宽度在布局完成后按容器实际宽度算
                val barWrap = FrameLayout(this)
                val blp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(8)
                )
                blp.topMargin = dp(6)
                barWrap.layoutParams = blp
                barWrap.background = tbg

                val fill = View(this)
                fill.layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
                val fbg = GradientDrawable()
                fbg.cornerRadius = dp(4).toFloat()
                fbg.setColor(parseColorSafe(cs.cat.color))
                fill.background = fbg
                barWrap.addView(fill)
                barWrap.post {
                    val avail = barWrap.width -
                            barWrap.paddingLeft - barWrap.paddingRight
                    if (avail > 0) {
                        val p2 = (cs.amount / maxOf(r.total, 0.01) * 100.0)
                        fill.layoutParams = FrameLayout.LayoutParams(
                            maxOf(dp(3), (avail * p2 / 100.0).toInt()),
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                }
                wrap.addView(barWrap)

                wrap.setOnClickListener {
                    filterCat = cs.cat.id; selectTab(1)
                }
                rpCats.addView(wrap)
            }
        }
    }

    // ---------------- 设置 ----------------

    private fun renderSettings() {
        val enabled = PayNotificationListener.isEnabled(this)
        stNotify.text = if (enabled) "✅ 已开启，能读到付款通知"
        else "❌ 未开启 —— 不开这个，付完款不会有任何反应"

        val canOverlay = Settings.canDrawOverlays(this)
        stOverlay.text = if (canOverlay) "✅ 已允许，付款后会弹在微信上面"
        else "❌ 未允许 —— 不开这个，只能收到一条普通通知提醒"

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        stBattery.text = if (ignoring) "✅ 已加入白名单"
        else "⚠️ 建议关闭电池优化，否则后台可能被杀"

        val hits = store.hitCount()
        val last = store.lastHitAt()
        val lastStr = if (last > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(last))
        } else "从未"
        stHit.text = "已抓到付款 $hits 笔 · 最近一次：$lastStr\n" +
                "如果这里是 0，说明权限没配好，或者付完款的通知被划掉了。"

        setBudget.hint = D.label(month) + "预算，例如 3000"
        val b = store.budget(month)
        if (b > 0 && setBudget.text.isNullOrEmpty()) setBudget.setText(M.zero(b))

        setInfo.text = "共 ${store.txs.size} 条记录 · 数据只存在这台手机里，不联网上传\n" +
                "换手机前请先备份 JSON"
    }

    private fun runTest() {
        val t = testInput.text.toString().trim()
        if (t.isEmpty()) { toast("先粘一段通知文字"); return }

        // 依次按三个来源试，哪个能认出来就报哪个
        val found = Rules.parseAny(t)
        val byPkg = if (found == null) "" else found.source

        testResult.visibility = View.VISIBLE
        testResult.text = if (found == null) {
            "❌ 没认出来。\n\n说明这条通知的写法不在现有规则里。把这段文字发给我，我给你加一条规则；或者你自己在「查看识别规则」里照着格式补。"
        } else {
            "✅ 认出来了（按「$byPkg」的规则）\n\n" +
                    "金额：¥${M.two(found.amount)}\n" +
                    "商家：${found.merchant.ifEmpty { "（没识别到，不影响记账）" }}\n" +
                    "方向：${if (found.income) "收入" else "支出"}\n" +
                    "命中规则：${found.ruleName}\n" +
                    "推荐分类：${Cats.byId(cats, Cats.guess(found.merchant)).emoji} " +
                    Cats.byId(cats, Cats.guess(found.merchant)).name
        }
    }

    private fun showRules() {
        val sb = StringBuilder()
        Rules.DEFAULTS.forEach { r ->
            sb.append("【").append(r.name).append("】")
            if (r.income) sb.append("（收入）")
            sb.append("\n").append(r.pattern)
                .append("\n  金额=第").append(r.amountGroup).append("组")
            if (r.merchantGroup > 0) sb.append("，商家=第").append(r.merchantGroup).append("组")
            sb.append("\n\n")
        }
        AlertDialog.Builder(this)
            .setTitle("内置识别规则（按顺序尝试）")
            .setMessage(sb.toString())
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------------- 权限引导 ----------------

    private fun openNotificationAccess() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                toast("请手动打开：设置 → 通知与状态栏 → 通知使用权")
            }
        }
        toast("在列表里找到「花哪儿了」并打开开关")
    }

    private fun openOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                toast("请手动打开：设置 → 应用管理 → 花哪儿了 → 显示悬浮窗")
            }
        }
    }

    private fun openBatterySettings() {
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (e: Exception) {
            try {
                @Suppress("DEPRECATION")
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("请手动打开：设置 → 电池 → 更多 → 允许后台运行")
            }
        }
    }

    private fun showOppoHelp() {
        AlertDialog.Builder(this)
            .setTitle("OPPO / 一加 / realme 必须做的设置")
            .setMessage(
                "国产系统默认会杀掉后台监听，不做这几步付完款收不到弹窗：\n\n" +
                        "1. 设置 → 电池 → 耗电管理（或「应用耗电管理」）→ 找到「花哪儿了」→ 选择「允许完全后台行为」或「不优化」\n\n" +
                        "2. 设置 → 应用管理 → 花哪儿了 → 权限管理 → 打开「自启动」和「关联启动」\n\n" +
                        "3. 设置 → 应用管理 → 花哪儿了 → 把「后台弹出界面」打开（这一项最关键，不开就不会弹窗）\n\n" +
                        "4. 最近任务界面里，给「花哪儿了」上锁（下拉卡片点小锁图标），防止一键清理时被清掉\n\n" +
                        "做完这 4 步，再付一笔 1 分钱试试，弹窗应该马上就出来。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                try {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
                } catch (e: Exception) { }
            }
        }
    }

    // ---------------- 导出 ----------------

    private fun exportCsv() {
        if (store.txs.isEmpty()) { toast("还没有数据"); return }
        val sb = StringBuilder()
        sb.append("日期,类型,分类,金额,来源,商家,备注,来源方式\n")
        store.txs.sortedBy { it.date }.forEach { t ->
            val c = Cats.byId(cats, t.catId)
            sb.append(csv(t.date)).append(",")
                .append(csv(if (t.type == T_INCOME) "收入" else "支出")).append(",")
                .append(csv(c.name)).append(",")
                .append(String.format(Locale.CHINA, "%.2f", t.amount)).append(",")
                .append(csv(t.pay)).append(",")
                .append(csv(t.store)).append(",")
                .append(csv(t.note)).append(",")
                .append(csv(if (t.src == "auto") "通知抓取" else "手动")).append("\n")
        }
        writeFile("花哪儿了-明细-${D.today()}.csv", "\ufeff" + sb.toString())
    }

    private fun exportJson() {
        val arr = org.json.JSONArray()
        store.txs.forEach { arr.put(it.toJson()) }
        val catsArr = org.json.JSONArray()
        cats.forEach { catsArr.put(it.toJson()) }
        val root = org.json.JSONObject().apply {
            put("v", 1)
            put("exported", D.today())
            put("tx", arr)
            put("cats", catsArr)
        }
        writeFile("花哪儿了-备份-${D.today()}.json", root.toString(1))
    }

    private fun writeFile(name: String, content: String) {
        try {
            val dir = File(getExternalFilesDir(null), "")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            FileOutputStream(f).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            toast("已导出到：\nAndroid/data/$packageName/files/$name")
        } catch (e: Exception) {
            toast("导出失败：" + e.message)
        }
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle("清空全部数据？")
            .setMessage("会删掉所有记录，无法撤销。建议先点「备份 JSON」。")
            .setPositiveButton("确定清空") { _, _ ->
                store.removeAll()
                renderMain()
                toast("已清空")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 小工具 ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun csv(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    private fun parseColorSafe(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: Exception) {
        Color.parseColor("#2F6BFF")
    }

    private fun labelView(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(Color.parseColor("#6B7280"))
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun hintView(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.parseColor("#9AA3B2"))
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(30), dp(20), dp(30))
    }
}

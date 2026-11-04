package vn.mbfinance.tracker

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import vn.mbfinance.tracker.Ui.Companion.MATCH
import vn.mbfinance.tracker.Ui.Companion.WRAP
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/** 3 tab: Tổng quan, Giao dịch (thống kê tính ngay trên máy) và Cài đặt (quyền, app ngân hàng, quét thông báo). Mọi dữ liệu chỉ nằm trên máy. */
class MainActivity : Activity() {

    private enum class Filter { ALL, IN, OUT }

    private lateinit var settings: Settings
    private lateinit var store: Store
    private lateinit var ui: Ui

    private lateinit var headerStatus: TextView
    private lateinit var pages: List<ScrollView>
    private lateinit var overviewBox: LinearLayout
    private lateinit var txBox: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var fab: View
    private val tabViews = ArrayList<TextView>()

    private lateinit var permText: TextView
    private lateinit var permButton: View
    private lateinit var batteryText: TextView
    private lateinit var batteryButton: View
    private lateinit var pkgText: TextView
    private lateinit var sourcesBox: LinearLayout
    private lateinit var scanText: TextView
    private lateinit var recentBox: LinearLayout
    private lateinit var tagsBox: Flow
    private var lastSourcesKey = ""
    private var lastRecentKey = ""

    private var ledger = Ledger.EMPTY
    private var ledgerKey = ""
    private var loading = false
    private var month: YearMonth = YearMonth.now(Ledger.VN)
    private var filter = Filter.ALL
    /** Lọc theo tag ở tab Giao dịch: null = mọi tag, [UNTAGGED] = chưa gắn. */
    private var tagFilter: Long? = null
    /** Thẻ "Theo tag" ở Tổng quan đang xem tiền vào hay ra. */
    private var tagIn = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 3000)
        }
    }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(Ledger.VN)
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(Ledger.VN)
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy", Locale("vi", "VN"))
    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val hmFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val groupFmt = NumberFormat.getInstance(Locale("vi", "VN"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = Store.get(this)
        ui = Ui(this)

        window.statusBarColor = ui.bg
        window.navigationBarColor = ui.surface
        if (!ui.night) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        val shell = ui.vbox().apply {
            fitsSystemWindows = true
            setBackgroundColor(ui.bg)
        }
        val header = ui.vbox().apply { setPadding(dp(20), dp(14), dp(20), dp(2)) }
        header.addView(ui.text("Thu chi MB", 24f, bold = true))
        headerStatus = ui.text("", 13f, ui.muted).also { header.addView(it) }
        shell.addView(header)

        overviewBox = ui.vbox()
        txBox = ui.vbox()
        settingsBox = ui.vbox()
        val content = FrameLayout(this)
        pages = listOf(overviewBox, txBox, settingsBox).map { box ->
            box.setPadding(dp(16), dp(4), dp(16), dp(24))
            ScrollView(this).apply { addView(box) }.also { content.addView(it) }
        }
        txBox.setPadding(dp(16), dp(4), dp(16), dp(96)) // chừa chỗ cho nút nổi
        fab = ui.text("+", 30f, ui.onPrimary).apply {
            gravity = Gravity.CENTER
            background = ui.pressable(ui.primary, 28)
            elevation = dp(6).toFloat()
            contentDescription = "Thêm giao dịch"
            setOnClickListener { showManualForm(null) }
        }
        content.addView(fab, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(20), dp(20))
        })
        shell.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        shell.addView(bottomNav())
        setContentView(shell)

        buildSettings()
        renderData()
        selectTab(0)
        settings.dropServerConfig()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        // Mở app = quét bù thông báo bị lỡ khi listener bị hệ thống ngắt
        thread { runCatching { MbNotificationListener.catchUp(applicationContext) } }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------------ khung + tab

    private fun bottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.surface)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        listOf("Tổng quan", "Giao dịch", "Cài đặt").forEachIndexed { i, s ->
            val v = ui.text(s, 14f).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { selectTab(i) }
            }
            tabViews.add(v)
            nav.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        return ui.vbox().apply {
            addView(ui.divider(), LinearLayout.LayoutParams(MATCH, 1))
            addView(nav)
        }
    }

    private fun selectTab(i: Int) {
        pages.forEachIndexed { j, p -> p.visibility = if (j == i) View.VISIBLE else View.GONE }
        fab.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabViews.forEachIndexed { j, v ->
            val on = j == i
            v.setTextColor(if (on) ui.primary else ui.muted)
            v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            v.background = if (on) ui.rounded(ui.primarySoft, 14) else null
        }
    }

    // ------------------------------------------------------------------ cập nhật định kỳ

    private fun render() {
        renderStatus()
        val key = store.dataKey() + "|" + settings.mbPackage
        if (key == ledgerKey || loading) return
        loading = true
        thread {
            val l = try {
                Ledger.load(applicationContext)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                loading = false
                if (l == null || isDestroyed) return@runOnUiThread
                ledger = l
                ledgerKey = key
                renderData()
                BalanceWidget.refresh(this)
            }
        }
    }

    private fun renderData() {
        renderOverview()
        renderTransactions()
    }

    /** Đổi tag không làm đổi dataKey → ép đọc lại sổ. */
    private fun reload() {
        ledgerKey = ""
        render()
    }

    // ------------------------------------------------------------------ tab Tổng quan

    private fun renderOverview() {
        val box = overviewBox
        box.removeAllViews()
        ui.add(box, monthSwitcher(), 4)
        val s = ledger.sum(month)

        val hero = ui.card(radius = 22, color = ui.primary).apply { setPadding(dp(20), dp(18), dp(20), dp(18)) }
        hero.addView(ui.text("Chênh lệch thu − chi", 13f, ui.onPrimary).apply { alpha = 0.8f })
        hero.addView(ui.text(ui.money(s.net, signed = true), 30f, ui.onPrimary, bold = true))
        ui.add(box, hero)

        // Số dư hiện tại (không theo tháng): ngân hàng = SD thông báo MB mới nhất, tiền mặt = giao dịch tay
        val bank = ledger.latestBalance
        if (bank != null || ledger.hasCash) {
            val c = ui.card()
            c.addView(ui.text("Tổng số dư", 13f, ui.muted))
            c.addView(ui.text(ui.money((bank ?: 0) + ledger.cashBalance), 22f, bold = true))
            fun part(label: String, value: String) = ui.vbox().apply {
                addView(ui.text(label, 12f, ui.muted))
                addView(ui.text(value, 15f, bold = true))
            }
            ui.add(c, ui.row(
                part("Ngân hàng", bank?.let { ui.money(it) } ?: "—"),
                part("Tiền mặt", ui.money(ledger.cashBalance)),
                gap = 12,
            ), 10)
            ui.add(box, c)
        }

        ui.add(box, ui.row(
            kpi("Tiền vào", s.totalIn, s.countIn, ui.green),
            kpi("Tiền ra", s.totalOut, s.countOut, ui.red),
            gap = 12,
        ))

        val now = YearMonth.now(Ledger.VN)
        val end = if (month < now.minusMonths(5)) month else now
        val chart = ui.card()
        chart.addView(ui.split(ui.text("6 tháng", 15f, bold = true), legend()))
        chart.addView(
            MonthBars(this, ui, ledger.lastMonths(end, 6), month) { month = it; renderData() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) },
        )
        ui.add(box, chart)

        ui.add(box, tagBreakdown())

        val top = ledger.inMonth(month).filter { !it.isIn }.sortedByDescending { it.amount }.take(5)
        if (top.isNotEmpty()) {
            val c = ui.card()
            c.addView(ui.text("Chi nhiều nhất tháng ${month.monthValue}", 15f, bold = true))
            txList(c, top, timeFmt)
            ui.add(box, c)
        }

        val recent = ui.card()
        recent.addView(ui.text("Giao dịch gần đây", 15f, bold = true))
        if (ledger.txs.isEmpty()) {
            recent.addView(ui.text(
                "Chưa có giao dịch. Khi MB Bank báo biến động số dư, giao dịch sẽ hiện ở đây.",
                14f, ui.muted,
            ).apply { setPadding(0, dp(8), 0, 0) })
        } else {
            txList(recent, ledger.txs.take(5), timeFmt)
            ui.add(recent, ui.button("Xem tất cả giao dịch", filled = false) { selectTab(1) }, 8)
        }
        ui.add(box, recent)
    }

    /** Tổng thu/chi tháng theo tag; chạm 1 dòng để xem các giao dịch của tag đó. */
    private fun tagBreakdown(): View {
        val c = ui.card()
        val side = LinearLayout(this).apply {
            addView(ui.tagChip("Chi", null, !tagIn) { tagIn = false; renderOverview() })
            addView(
                ui.tagChip("Thu", null, tagIn) { tagIn = true; renderOverview() },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) },
            )
        }
        c.addView(ui.split(ui.text("Theo tag", 15f, bold = true), side))
        val rows = ledger.byTag(month, tagIn)
        if (rows.isEmpty()) {
            ui.add(c, ui.text("Không có giao dịch ${if (tagIn) "vào" else "ra"} trong tháng này.", 14f, ui.muted), 8)
            return c
        }
        val total = rows.sumOf { it.second }.coerceAtLeast(1)
        for ((tag, amount) in rows) {
            val color = tag?.color ?: ui.muted
            val pct = amount * 100 / total
            val row = ui.vbox().apply {
                setPadding(dp(4), dp(8), dp(4), dp(8))
                background = ui.pressable(ui.surface, 8)
                addView(ui.split(
                    ui.text(ui.dotLabel(tag?.name ?: "Chưa gắn tag", color), 14f),
                    ui.text("${ui.money(amount)} · $pct%", 13f, ui.muted),
                ))
                ui.add(this, ui.bar(amount.toFloat() / total, color), 6)
                setOnClickListener {
                    tagFilter = tag?.id ?: UNTAGGED
                    filter = if (tagIn) Filter.IN else Filter.OUT
                    renderTransactions()
                    selectTab(1)
                }
            }
            ui.add(c, row, 4)
        }
        return c
    }

    private fun kpi(label: String, amount: Long, count: Int, color: Int) = ui.card().apply {
        addView(ui.text(label, 13f, ui.muted))
        addView(ui.text(ui.money(amount), 18f, color, bold = true).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(2))
        })
        addView(ui.text("$count giao dịch", 12f, ui.muted))
    }

    private fun legend() = LinearLayout(this).apply {
        addView(ui.text("● Vào", 12f, ui.green))
        addView(ui.text("● Ra", 12f, ui.red).apply { setPadding(dp(10), 0, 0, 0) })
    }

    private fun monthSwitcher(): View {
        val now = YearMonth.now(Ledger.VN)
        val prev = ui.button("‹", filled = false) { month = month.minusMonths(1); renderData() }
        val next = ui.button("›", filled = false) {
            if (month < now) {
                month = month.plusMonths(1)
                renderData()
            }
        }
        prev.textSize = 20f
        next.textSize = 20f
        prev.setPadding(0, dp(4), 0, dp(6))
        next.setPadding(0, dp(4), 0, dp(6))
        next.alpha = if (month < now) 1f else 0.35f
        val title = ui.text("Tháng ${month.monthValue}/${month.year}", 17f, bold = true).apply {
            gravity = Gravity.CENTER
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prev, LinearLayout.LayoutParams(dp(48), WRAP))
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(next, LinearLayout.LayoutParams(dp(48), WRAP))
        }
    }

    private fun txList(parent: LinearLayout, list: List<Tx>, fmt: DateTimeFormatter) {
        list.forEachIndexed { i, t ->
            if (i > 0) parent.addView(ui.divider(), LinearLayout.LayoutParams(MATCH, 1))
            parent.addView(txRow(t, fmt))
        }
    }

    private fun txRow(t: Tx, fmt: DateTimeFormatter): View {
        val left = ui.vbox()
        left.addView(ui.text(t.description ?: "(không có nội dung)", 14f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        // Thông báo không ghi giờ giao dịch → đang dùng giờ máy nhận, ghi chú để phân biệt
        val meta = SpannableStringBuilder(fmt.format(Instant.ofEpochMilli(t.time)))
        if (!t.timeFromText) meta.append(" · giờ nhận thông báo")
        if (t.manual) meta.append(" · Tiền mặt")
        t.tag?.let { meta.append("   ").append(ui.dotLabel(it.name, it.color)) }
        left.addView(ui.text(meta, 12f, ui.muted))
        val amount = ui.text(
            ui.money(if (t.isIn) t.amount else -t.amount, signed = true),
            15f, if (t.isIn) ui.green else ui.red, bold = true,
        )
        return ui.split(left, amount).apply {
            setPadding(0, dp(10), 0, dp(10))
            background = ui.pressable(ui.surface, 8)
            setOnClickListener { showTx(t) }
        }
    }

    /** Chi tiết 1 giao dịch dạng bottom sheet: số tiền, tag (chạm để gắn/bỏ), thông tin và thông báo gốc. */
    private fun showTx(t: Tx) {
        val dialog = Dialog(this)
        val color = if (t.isIn) ui.green else ui.red
        val box = sheetBox()
        fun addWrap(v: View, gap: Int) = box.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(gap) })

        addWrap(ui.text(if (t.isIn) "Tiền vào" else "Tiền ra", 12f, color, bold = true).apply {
            background = ui.rounded(ui.soft(color), 20)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }, 14)
        ui.add(box, ui.text(ui.money(if (t.isIn) t.amount else -t.amount, signed = true), 32f, color, bold = true), 6)
        ui.add(box, ui.text(t.description ?: "(không có nội dung)", 16f).apply { setTextIsSelectable(true) }, 4)
        val day = dayFmt.format(t.date).replaceFirstChar { it.uppercase() }
        val clock = clockFmt.format(Instant.ofEpochMilli(t.time)) + if (t.timeFromText) "" else " (giờ nhận thông báo)"
        ui.add(box, ui.text("$day · $clock" + if (t.manual) " · Tiền mặt" else "", 13f, ui.muted), 4)

        // Tag: chạm để gắn, chạm lại tag đang chọn để bỏ
        ui.add(box, ui.text("TAG", 12f, ui.muted, bold = true), 20)
        val tagFlow = ui.flow().also { ui.add(box, it, 8) }
        var current = t.tag?.id
        var changed = false
        fun pick(id: Long?) {
            current = id
            store.setTag(t.id, id)
            changed = true
        }
        fun fillTags() {
            tagFlow.removeAllViews()
            for (g in store.tags()) {
                tagFlow.addView(ui.tagChip(g.name, g.color, g.id == current) {
                    pick(if (current == g.id) null else g.id)
                    fillTags()
                })
            }
            tagFlow.addView(ui.tagChip("+ Tag mới", null, false) {
                askTagName("Tag mới", "") { name ->
                    val g = store.addTag(name)
                    if (g == null) toast("Đã có tag \"$name\"") else pick(g.id)
                    renderTagSettings()
                    fillTags()
                }
            })
        }
        fillTags()

        val raw = if (t.manual) null else store.text(t.id)
        // amount lớn hơn GD trong thông báo = đã cộng phí/VAT khớp số dư
        val gd = raw?.let { Parser.parse(it) }?.amount?.takeIf { it < t.amount }
        val info = listOfNotNull(
            gd?.let { "Số tiền GD" to ui.money(it) },
            gd?.let { "Phí/VAT (khớp số dư)" to ui.money(t.amount - it) },
            t.balanceAfter?.let { "Số dư sau giao dịch" to ui.money(it) },
            t.account?.let { "Tài khoản" to it },
        )
        if (info.isNotEmpty()) {
            val card = ui.card(radius = 14, color = ui.bg)
            info.forEachIndexed { i, (label, value) ->
                if (i > 0) card.addView(ui.divider(), LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = dp(10) })
                card.addView(ui.split(
                    ui.text(label, 13f, ui.muted),
                    ui.text(value, 14f, bold = true).apply { setTextIsSelectable(true) },
                ).apply { setPadding(0, if (i > 0) dp(10) else 0, 0, 0) })
            }
            ui.add(box, card, 20)
        }

        if (raw != null) {
            val rawText = ui.text(raw, 13f).apply {
                setTextIsSelectable(true)
                background = ui.rounded(ui.bg, 10)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                visibility = View.GONE
            }
            val toggle = ui.text("Xem thông báo gốc ▾", 13f, ui.primary, bold = true).apply {
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    val show = rawText.visibility != View.VISIBLE
                    rawText.visibility = if (show) View.VISIBLE else View.GONE
                    text = if (show) "Ẩn thông báo gốc ▴" else "Xem thông báo gốc ▾"
                }
            }
            addWrap(toggle, 14)
            ui.add(box, rawText, 4)
        }

        ui.add(box, ui.button("Xong") { dialog.dismiss() }, 20)
        if (t.manual) {
            ui.add(box, ui.button("Sửa", filled = false) {
                dialog.dismiss()
                showManualForm(t)
            }, 8)
        }
        ui.add(box, ui.button("Xoá giao dịch", filled = false) {
            AlertDialog.Builder(this)
                .setTitle("Xoá giao dịch này?")
                .setMessage(
                    if (t.manual) "Giao dịch sẽ không còn trong thống kê."
                    else "Giao dịch sẽ không còn trong thống kê. MB gửi lại thông báo này cũng không thêm lại."
                )
                .setPositiveButton("Xoá") { _, _ ->
                    store.deleteTransaction(t.id)
                    changed = true
                    dialog.dismiss()
                }
                .setNegativeButton("Huỷ", null)
                .show()
        }.apply { setTextColor(ui.red) }, 8)

        dialog.setOnDismissListener { if (changed) reload() }
        showSheet(dialog, box)
    }

    /** Form thêm ([t] null) hoặc sửa giao dịch nhập tay: chiều, số tiền, ngày giờ, nội dung, tag. */
    private fun showManualForm(t: Tx?) {
        val dialog = Dialog(this)
        val box = sheetBox()
        ui.add(box, ui.text(if (t == null) "Thêm giao dịch" else "Sửa giao dịch", 18f, bold = true), 14)
        // Mẫu gần đây: điền sau khi dựng xong các ô bên dưới
        val tplRow = LinearLayout(this)
        val templates = if (t == null) ledger.templates(8) else emptyList()
        if (templates.isNotEmpty()) {
            ui.add(box, HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(tplRow)
            }, 12)
        }

        var isIn = t?.isIn ?: false
        val dirRow = LinearLayout(this)
        fun fillDir() {
            dirRow.removeAllViews()
            listOf(false to "Tiền ra", true to "Tiền vào").forEachIndexed { i, (v, s) ->
                dirRow.addView(
                    ui.chip(s, v == isIn) { isIn = v; fillDir() },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(8) },
                )
            }
        }
        fillDir()
        ui.add(box, dirRow, 14)

        val amount = ui.input("Số tiền (₫)").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        // Tự thêm dấu chấm hàng nghìn khi gõ
        amount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val digits = s.filter { it.isDigit() }.toString().trimStart('0').take(13)
                val f = if (digits.isEmpty()) "" else groupFmt.format(digits.toLong())
                if (f != s.toString()) {
                    amount.setText(f)
                    amount.setSelection(f.length)
                }
            }
        })
        t?.let { amount.setText(it.amount.toString()) }
        ui.add(box, amount, 12)
        val quick = LinearLayout(this)
        for (v in QUICK_AMOUNTS) {
            quick.addView(
                ui.chip(short(v), false) { amount.setText(v.toString()) },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { if (quick.childCount > 0) marginStart = dp(6) },
            )
        }
        ui.add(box, HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(quick)
        }, 8)

        val now = LocalDateTime.now(Ledger.VN).withSecond(0).withNano(0)
        val today = now.toLocalDate()
        // Thêm mới khi đang xem tháng cũ → mặc định cuối tháng đó
        var at = t?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.time), Ledger.VN) }
            ?: if (month < YearMonth.from(today)) month.atEndOfMonth().atTime(now.toLocalTime()) else now
        val dateBtn = ui.button("", filled = false) {}
        val timeBtn = ui.button("", filled = false) {}
        val dayRow = LinearLayout(this)
        fun showAt() {
            dateBtn.text = at.format(dateFmt)
            timeBtn.text = at.format(hmFmt)
            dayRow.removeAllViews()
            listOf("Hôm nay" to today, "Hôm qua" to today.minusDays(1)).forEachIndexed { i, (label, d) ->
                dayRow.addView(
                    ui.chip(label, at.toLocalDate() == d) {
                        at = LocalDateTime.of(d, at.toLocalTime())
                        showAt()
                    },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(6) },
                )
            }
        }
        showAt()
        dateBtn.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                at = LocalDateTime.of(LocalDate.of(y, m + 1, d), at.toLocalTime())
                showAt()
            }, at.year, at.monthValue - 1, at.dayOfMonth).show()
        }
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, min ->
                at = at.withHour(h).withMinute(min)
                showAt()
            }, at.hour, at.minute, true).show()
        }
        ui.add(box, ui.row(dateBtn, timeBtn), 12)
        ui.add(box, dayRow, 8)

        val desc = ui.input("Nội dung (tuỳ chọn), vd: Ăn sáng").apply { setText(t?.description.orEmpty()) }
        ui.add(box, desc, 12)

        ui.add(box, ui.text("TAG", 12f, ui.muted, bold = true), 20)
        val tagFlow = ui.flow().also { ui.add(box, it, 8) }
        var tagId = if (t == null) ledger.lastManualTag?.id else t.tag?.id
        fun fillTags() {
            tagFlow.removeAllViews()
            for (g in store.tags()) {
                tagFlow.addView(ui.tagChip(g.name, g.color, g.id == tagId) {
                    tagId = if (tagId == g.id) null else g.id
                    fillTags()
                })
            }
            tagFlow.addView(ui.tagChip("+ Tag mới", null, false) {
                askTagName("Tag mới", "") { name ->
                    val g = store.addTag(name)
                    if (g == null) toast("Đã có tag \"$name\"") else { tagId = g.id }
                    renderTagSettings()
                    fillTags()
                }
            })
        }
        fillTags()

        for (m in templates) {
            tplRow.addView(
                ui.chip("${m.description} · ${short(m.amount)}", false) {
                    isIn = m.isIn
                    fillDir()
                    amount.setText(m.amount.toString())
                    desc.setText(m.description)
                    tagId = m.tag?.id
                    fillTags()
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { if (tplRow.childCount > 0) marginStart = dp(6) },
            )
        }

        ui.add(box, ui.button("Lưu") {
            val value = amount.text.filter { it.isDigit() }.toString().toLongOrNull() ?: 0L
            if (value <= 0) {
                toast("Nhập số tiền")
                return@button
            }
            val time = at.atZone(Ledger.VN).toInstant().toEpochMilli()
            store.saveManual(t?.id, value, isIn, time, desc.text.toString().trim().take(500).ifEmpty { null }, tagId)
            month = YearMonth.from(at)
            dialog.dismiss()
            reload()
        }, 20)
        showSheet(dialog, box)
    }

    /** 30000 → "30k", 1500000 → "1,5tr". */
    private fun short(n: Long): String = when {
        n >= 1_000_000 && n % 100_000 == 0L -> {
            val d = n / 100_000
            if (d % 10 == 0L) "${d / 10}tr" else "${d / 10},${d % 10}tr"
        }
        n % 1000 == 0L -> "${n / 1000}k"
        else -> groupFmt.format(n)
    }

    /** Khung bottom sheet có thanh kéo; thêm nội dung rồi gọi [showSheet]. */
    private fun sheetBox() = ui.vbox().apply {
        background = ui.sheet()
        setPadding(dp(20), dp(10), dp(20), dp(20))
        addView(View(this@MainActivity).apply { background = ui.rounded(ui.line, 3) }, LinearLayout.LayoutParams(dp(40), dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    private fun showSheet(dialog: Dialog, box: View) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(ScrollView(this).apply { addView(box) })
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    /** Hỏi tên tag (≤ 30 ký tự, không rỗng). */
    private fun askTagName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = ui.input("Tên tag, vd: Ăn uống").apply {
            setText(initial)
            setSelection(initial.length)
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("Lưu") { _, _ ->
                val name = input.text.toString().trim().take(30)
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ------------------------------------------------------------------ tab Giao dịch

    private fun renderTransactions() {
        val box = txBox
        box.removeAllViews()
        ui.add(box, monthSwitcher(), 4)

        val chips = LinearLayout(this)
        listOf(Filter.ALL to "Tất cả", Filter.IN to "Tiền vào", Filter.OUT to "Tiền ra").forEachIndexed { i, (f, s) ->
            chips.addView(
                ui.chip(s, f == filter) { filter = f; renderTransactions() },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(8) },
            )
        }
        ui.add(box, chips, 8)

        val tags = store.tags()
        if (tagFilter != null && tagFilter != UNTAGGED && tags.none { it.id == tagFilter }) tagFilter = null
        val tagRow = LinearLayout(this)
        fun tagOption(label: String, color: Int?, id: Long?) = tagRow.addView(
            ui.tagChip(label, color, tagFilter == id) { tagFilter = id; renderTransactions() },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { if (tagRow.childCount > 0) marginStart = dp(6) },
        )
        tagOption("Mọi tag", null, null)
        tagOption("Chưa gắn", null, UNTAGGED)
        for (g in tags) tagOption(g.name, g.color, g.id)
        ui.add(box, HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tagRow)
        }, 8)

        val list = ledger.inMonth(month).filter {
            val dirOk = when (filter) {
                Filter.ALL -> true
                Filter.IN -> it.isIn
                Filter.OUT -> !it.isIn
            }
            val tagOk = when (tagFilter) {
                null -> true
                UNTAGGED -> it.tag == null
                else -> it.tag?.id == tagFilter
            }
            dirOk && tagOk
        }
        if (list.isEmpty()) {
            ui.add(box, ui.card().apply { addView(ui.text("Không có giao dịch phù hợp trong tháng này.", 14f, ui.muted)) })
        }
        for ((day, items) in list.groupBy { it.date }) {
            val net = items.sumOf { if (it.isIn) it.amount else -it.amount }
            val head = ui.split(
                ui.text(dayFmt.format(day).replaceFirstChar { it.uppercase() }, 13f, ui.muted, bold = true),
                ui.text(ui.money(net, signed = true), 13f, ui.muted),
            ).apply { setPadding(dp(4), 0, dp(4), 0) }
            ui.add(box, head, 16)
            val c = ui.card().apply { setPadding(dp(16), dp(2), dp(16), dp(2)) }
            txList(c, items, clockFmt)
            ui.add(box, c, 6)
        }

        val unparsed = ledger.unparsed.filter { Ledger.monthOf(it.postedAt) == month }
        if (unparsed.isNotEmpty()) {
            val c = ui.card()
            c.addView(ui.text("Giao dịch chưa đọc được (${unparsed.size})", 14f, bold = true))
            c.addView(ui.text("Mẫu thông báo mới parser chưa hỗ trợ — chưa tính vào thống kê.", 12f, ui.muted))
            for (it in unparsed.take(20)) {
                c.addView(ui.text(timeFmt.format(Instant.ofEpochMilli(it.postedAt)), 12f, ui.muted).apply {
                    setPadding(0, dp(10), 0, 0)
                })
                c.addView(ui.text(it.text, 13f).apply {
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            ui.add(box, c, 20)
        }
    }

    // ------------------------------------------------------------------ tab Cài đặt

    private fun section(title: String): LinearLayout {
        ui.add(settingsBox, ui.text(title, 13f, ui.muted, bold = true).apply { setPadding(dp(4), 0, 0, 0) }, 20)
        return ui.card().also { ui.add(settingsBox, it, 6) }
    }

    private fun buildSettings() {
        val perm = section("QUYỀN")
        permText = ui.text("", 14f).also { perm.addView(it) }
        permButton = ui.button("Mở cài đặt quyền đọc thông báo") {
            startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.also { ui.add(perm, it, 8) }
        batteryText = ui.text("", 14f).also { ui.add(perm, it, 12) }
        batteryButton = ui.button("Cho phép chạy nền không giới hạn") { requestBatteryExemption() }
            .also { ui.add(perm, it, 8) }
        ui.add(perm, ui.text(
            "Máy Xiaomi/Oppo/Vivo/Samsung: vào Cài đặt → Ứng dụng → Thu chi MB → bật Tự khởi chạy " +
                "và đặt Pin = Không giới hạn, nếu không hệ thống có thể tắt app.",
            12f, ui.muted,
        ), 10)

        val tagSec = section("TAG THU CHI")
        tagSec.addView(ui.text("Gắn tag trong màn chi tiết giao dịch. Chạm tag ở đây để đổi tên hoặc xoá.", 12f, ui.muted))
        tagsBox = ui.flow().also { ui.add(tagSec, it, 10) }
        renderTagSettings()

        val bank = section("APP NGÂN HÀNG")
        pkgText = ui.text("", 14f).also { bank.addView(it) }
        ui.add(bank, ui.text(
            "App vừa có thông báo (bấm để chọn làm app MB Bank). Chỉ lưu tên app, không lưu nội dung:",
            12f, ui.muted,
        ), 6)
        sourcesBox = ui.vbox().also { ui.add(bank, it, 6) }

        val scan = section("QUÉT THÔNG BÁO")
        scanText = ui.text("", 14f).also { scan.addView(it) }
        ui.add(scan, ui.button("Quét lại thông báo đang hiện") { scanNotifications() }, 10)
        ui.add(scan, ui.button("Kiểm tra lại dữ liệu đã lưu", filled = false) { recheckStored() }, 8)
        ui.add(scan, ui.text(
            "Quét lại thanh thông báo, đọc lại mọi thông báo MB đã lưu, cộng phí/VAT khi khớp số dư và xoá giao dịch bị lưu trùng.",
            12f, ui.muted,
        ), 6)

        val log = section("THÔNG BÁO MB ĐÃ BẮT")
        recentBox = ui.vbox().also { log.addView(it) }
    }

    private fun renderStatus() {
        val listenerOn = MbNotificationListener.isEnabled(this)
        val connected = MbNotificationListener.instance != null
        permText.text = when {
            !listenerOn -> "❌ Chưa cấp quyền đọc thông báo"
            !connected -> "⚠️ Đã cấp quyền nhưng dịch vụ đọc thông báo chưa chạy — đang thử kết nối lại. " +
                "Nếu kéo dài: tắt rồi bật lại quyền."
            else -> "✅ Đang nhận thông báo"
        }
        permButton.visibility = if (listenerOn && connected) View.GONE else View.VISIBLE

        val pm = getSystemService(PowerManager::class.java)
        val unrestricted = pm?.isIgnoringBatteryOptimizations(packageName) == true
        batteryText.text = if (unrestricted) "✅ Được chạy nền không giới hạn" else "⚠️ Hệ thống có thể tắt app để tiết kiệm pin"
        batteryButton.visibility = if (unrestricted) View.GONE else View.VISIBLE

        headerStatus.text = when {
            !listenerOn -> "⚠ Chưa cấp quyền đọc thông báo — vào tab Cài đặt"
            !connected -> "⚠ Dịch vụ đọc thông báo chưa chạy — xem tab Cài đặt"
            else -> "Đang theo dõi ${label(settings.mbPackage)}"
        }
        headerStatus.setTextColor(if (!listenerOn || !connected) ui.warn else ui.muted)

        pkgText.text = "Đang theo dõi: ${label(settings.mbPackage)} (${settings.mbPackage})"

        val sources = store.sources(12)
        val sKey = sources.joinToString { "${it.pkg}:${it.count}" } + settings.mbPackage
        if (sKey != lastSourcesKey) {
            lastSourcesKey = sKey
            sourcesBox.removeAllViews()
            if (sources.isEmpty()) sourcesBox.addView(ui.text("Chưa thấy thông báo nào. Hãy cấp quyền ở trên.", 13f, ui.muted))
            for (s in sources) {
                val chosen = s.pkg == settings.mbPackage
                val v = ui.vbox().apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = ui.pressable(if (chosen) ui.primarySoft else ui.surface, 10)
                    setOnClickListener { choosePackage(s.pkg) }
                    addView(ui.text(label(s.pkg), 14f, if (chosen) ui.primary else ui.fg, bold = chosen))
                    addView(ui.text("${s.pkg} · ${s.count} thông báo · ${fmt(s.lastSeen)}", 12f, ui.muted))
                }
                ui.add(sourcesBox, v, 4)
            }
        }

        scanText.text = "Đã lưu ${ledger.txs.size} giao dịch trên máy" +
            (settings.lastError?.let { "\n⚠️ $it (${fmt(settings.lastErrorAt)})" } ?: "")

        val recent = store.recent(15)
        val rKey = recent.joinToString { "${it.id}" }
        if (rKey != lastRecentKey) {
            lastRecentKey = rKey
            recentBox.removeAllViews()
            if (recent.isEmpty()) recentBox.addView(ui.text("Chưa có. Thông báo từ app đang theo dõi sẽ hiện ở đây.", 13f, ui.muted))
            recent.forEachIndexed { i, it ->
                val state = when {
                    it.deleted -> "🗑 giao dịch đã xoá"
                    Parser.parse(it.text) != null -> "✅ giao dịch"
                    Parser.looksLikeTransaction(it.text) -> "❔ giống giao dịch nhưng chưa đọc được"
                    else -> "không phải giao dịch"
                }
                if (i > 0) ui.add(recentBox, ui.divider().apply { minimumHeight = 1 }, 10)
                ui.add(recentBox, ui.text("${fmt(it.postedAt)} · $state", 12f, ui.muted), if (i > 0) 10 else 0)
                recentBox.addView(ui.text(it.text, 13f).apply {
                    maxLines = 5
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
        }
    }

    // ------------------------------------------------------------------ hành động

    private fun renderTagSettings() {
        tagsBox.removeAllViews()
        for (g in store.tags()) tagsBox.addView(ui.tagChip(g.name, g.color, false) { manageTag(g) })
        tagsBox.addView(ui.tagChip("+ Thêm tag", null, false) {
            askTagName("Tag mới", "") { name ->
                if (store.addTag(name) == null) toast("Đã có tag \"$name\"")
                renderTagSettings()
                reload()
            }
        })
    }

    private fun manageTag(g: Tag) {
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(arrayOf("Đổi tên", "Xoá tag")) { _, which ->
                if (which == 0) {
                    askTagName("Đổi tên tag", g.name) { name ->
                        if (!store.renameTag(g.id, name)) toast("Đã có tag \"$name\"")
                        renderTagSettings()
                        reload()
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Xoá tag ${g.name}?")
                        .setMessage("Các giao dịch đang gắn tag này sẽ trở về \"chưa gắn tag\".")
                        .setPositiveButton("Xoá") { _, _ ->
                            store.deleteTag(g.id)
                            renderTagSettings()
                            reload()
                        }
                        .setNegativeButton("Huỷ", null)
                        .show()
                }
            }
            .show()
    }

    /** Quét bù thông báo, phân tích lại toàn bộ, xoá bản trùng và đối soát phí (chạy nền vì có thể nhiều dữ liệu). */
    private fun recheckStored() {
        thread {
            val scanned = runCatching { MbNotificationListener.catchUp(applicationContext) }.getOrDefault(-1)
            val r = runCatching { store.reparseAll() }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val msg = if (r == null) "Kiểm tra lại thất bại" else buildString {
                    append("Đã kiểm tra lại")
                    if (scanned > 0) append(", lưu thêm $scanned thông báo")
                    append(if (r.duplicates > 0) ", xoá ${r.duplicates} bản trùng" else ", không có bản trùng")
                    if (r.withFee > 0) append(", ${r.withFee} giao dịch có phí/VAT")
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                reload()
            }
        }
    }

    private fun scanNotifications() {
        val listener = MbNotificationListener.instance
        if (listener == null) {
            thread { MbNotificationListener.catchUp(applicationContext) }
            AlertDialog.Builder(this)
                .setTitle("Dịch vụ đọc thông báo không chạy")
                .setMessage(
                    "App chưa được hệ thống kết nối để đọc thông báo (dù có thể đã cấp quyền).\n\n" +
                        "Vào Truy cập thông báo → tắt rồi bật lại Thu chi MB, kiểm tra Tự khởi chạy, " +
                        "rồi thử lại."
                )
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Đóng", null)
                .show()
            return
        }
        thread {
            val added = try {
                listener.scanActive()
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                toast(
                    when {
                        added < 0 -> "Không đọc được thanh thông báo"
                        added == 0 -> "Không có thông báo MB mới trên thanh thông báo"
                        else -> "Đã lưu thêm $added thông báo"
                    }
                )
                render()
            }
        }
    }

    private fun choosePackage(pkg: String) {
        if (pkg == settings.mbPackage) return
        AlertDialog.Builder(this)
            .setTitle("Theo dõi ${label(pkg)}?")
            .setMessage(
                "Từ giờ app sẽ lưu (trên máy) toàn bộ nội dung thông báo của:\n$pkg\n\n" +
                    "Chỉ chọn app ngân hàng MB."
            )
            .setPositiveButton("Chọn") { _, _ ->
                settings.mbPackage = pkg
                lastSourcesKey = ""
                render()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ------------------------------------------------------------------ tiện ích

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun fmt(ms: Long) = timeFmt.format(Instant.ofEpochMilli(ms))

    private fun dp(v: Int) = ui.dp(v)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val UNTAGGED = -1L
        private val QUICK_AMOUNTS = listOf(20_000L, 50_000L, 100_000L, 200_000L, 500_000L)
    }
}

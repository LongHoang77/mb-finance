package vn.mbfinance.tracker

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
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
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
    private lateinit var cashBox: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var fab: View
    private class NavItem(val pill: View, val icon: ImageView, val label: TextView)

    private val tabViews = ArrayList<NavItem>()

    private lateinit var permText: TextView
    private lateinit var permButton: View
    private lateinit var batteryText: TextView
    private lateinit var batteryButton: View
    private lateinit var pkgText: TextView
    private lateinit var sourcesBox: LinearLayout
    private lateinit var scanText: TextView
    private lateinit var recentBox: LinearLayout
    private lateinit var tagsBox: Flow
    private lateinit var marksBox: LinearLayout
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
        val header = ui.vbox().apply { setPadding(dp(20), dp(16), dp(20), dp(6)) }
        header.addView(ui.text("Thu chi MB", 26f, bold = true).apply { letterSpacing = -0.02f })
        headerStatus = ui.text("", 13f, ui.muted).also { ui.add(header, it, 4) }
        shell.addView(header)

        overviewBox = ui.vbox()
        txBox = ui.vbox()
        cashBox = ui.vbox()
        settingsBox = ui.vbox()
        val content = FrameLayout(this)
        pages = listOf(overviewBox, txBox, cashBox, settingsBox).map { box ->
            box.setPadding(dp(16), dp(4), dp(16), dp(28))
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(box)
            }.also { content.addView(it) }
        }
        cashBox.setPadding(dp(16), dp(4), dp(16), dp(104)) // chừa chỗ cho nút nổi
        fab = ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = ColorStateList.valueOf(ui.onPrimary)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = ui.pressable(ui.primary, 20)
            elevation = dp(6).toFloat()
            contentDescription = "Thêm giao dịch"
            setOnClickListener { showManualForm(null) }
        }
        content.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
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

    /** Thanh tab dưới: icon trong viên nền (tab đang chọn) + nhãn. */
    private fun bottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.surface)
            setPadding(dp(8), dp(10), dp(8), dp(12))
        }
        listOf(
            "Tổng quan" to R.drawable.ic_home,
            "Giao dịch" to R.drawable.ic_list,
            "Tiền mặt" to R.drawable.ic_wallet,
            "Cài đặt" to R.drawable.ic_settings,
        ).forEachIndexed { i, (s, res) ->
            val icon = ui.icon(res, ui.muted, 22)
            val pill = FrameLayout(this).apply {
                addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }
            val label = ui.text(s, 12f, ui.muted).apply { gravity = Gravity.CENTER }
            val item = ui.vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(pill, LinearLayout.LayoutParams(dp(60), dp(32)))
                addView(label, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
                setOnClickListener { selectTab(i) }
            }
            tabViews.add(NavItem(pill, icon, label))
            nav.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return ui.vbox().apply {
            addView(ui.divider(), LinearLayout.LayoutParams(MATCH, 1))
            addView(nav)
        }
    }

    private fun selectTab(i: Int) {
        pages.forEachIndexed { j, p -> p.visibility = if (j == i) View.VISIBLE else View.GONE }
        fab.visibility = if (i == CASH_TAB) View.VISIBLE else View.GONE
        tabViews.forEachIndexed { j, v ->
            val on = j == i
            v.pill.background = if (on) ui.rounded(ui.primarySoft, 16) else null
            v.icon.imageTintList = ColorStateList.valueOf(if (on) ui.primary else ui.muted)
            v.label.setTextColor(if (on) ui.fg else ui.muted)
            v.label.typeface = if (on) ui.medium else Typeface.DEFAULT
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
        renderCash()
        renderMarks() // lệch của mốc đổi khi có giao dịch đến trễ
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

        // Thẻ chính: số dư hiện tại (không theo tháng) + chênh lệch tháng đang xem
        val bank = ledger.latestBalance
        val hero = ui.hero()
        val onHero = ui.onHero
        fun faded(v: TextView) = v.apply { alpha = 0.78f }
        if (bank != null || ledger.hasCash) {
            hero.addView(faded(ui.text("Tổng số dư", 13f, onHero)))
            ui.add(hero, ui.amount((bank ?: 0) + ledger.cashBalance, 32f, onHero), 6)
            fun part(res: Int, label: String, value: String) = ui.vbox().apply {
                addView(faded(ui.text(ui.iconText(res, label, onHero, 14), 12f, onHero)))
                ui.add(this, ui.text(value, 15f, onHero, bold = true).apply { fontFeatureSettings = "tnum" }, 4)
            }
            ui.add(hero, ui.row(
                part(R.drawable.ic_bank, "Ngân hàng", bank?.let { ui.money(it) } ?: "—"),
                part(R.drawable.ic_wallet, "Tiền mặt", ui.money(ledger.cashBalance)),
                gap = 12,
            ), 16)
            ui.add(hero, View(this).apply { setBackgroundColor(0x33FFFFFF) }.also { it.minimumHeight = 1 }, 16)
            ui.add(hero, ui.split(
                faded(ui.text("Chênh lệch tháng ${month.monthValue}", 13f, onHero)),
                ui.amount(s.net, 16f, onHero, signed = true),
            ), 14)
        } else {
            hero.addView(faded(ui.text("Chênh lệch thu − chi tháng ${month.monthValue}", 13f, onHero)))
            ui.add(hero, ui.amount(s.net, 32f, onHero, signed = true), 6)
        }
        ui.add(box, hero, 8)

        ui.add(box, ui.row(
            kpi(R.drawable.ic_arrow_down, "Tiền vào", s.totalIn, s.countIn, ui.green),
            kpi(R.drawable.ic_arrow_up, "Tiền ra", s.totalOut, s.countOut, ui.red),
            gap = 12,
        ))
        ledger.checks[month]?.let { ui.add(box, monthCheckCard(it)) }

        val now = YearMonth.now(Ledger.VN)
        val end = if (month < now.minusMonths(5)) month else now
        val chart = ui.card()
        chart.addView(ui.split(ui.text("6 tháng gần đây", 16f, bold = true), legend()))
        chart.addView(
            MonthBars(this, ui, ledger.lastMonths(end, 6), month) { month = it; renderData() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) },
        )
        ui.add(box, chart)

        ui.add(box, tagBreakdown())

        val top = ledger.inMonth(month).filter { !it.isIn }.sortedByDescending { it.amount }.take(5)
        if (top.isNotEmpty()) {
            val c = listCard()
            c.addView(ui.header("Chi nhiều nhất tháng ${month.monthValue}").apply { setPadding(0, 0, 0, dp(4)) })
            txList(c, top, timeFmt)
            ui.add(box, c)
        }

        val recent = listCard()
        recent.addView(ui.header("Giao dịch gần đây", if (ledger.txs.isEmpty()) null else "Xem tất cả") { selectTab(1) }
            .apply { setPadding(0, 0, 0, dp(4)) })
        if (ledger.txs.isEmpty()) {
            ui.add(recent, empty(R.drawable.ic_bell, "Chưa có giao dịch. Khi MB Bank báo biến động số dư, giao dịch sẽ hiện ở đây."), 8)
        } else {
            txList(recent, ledger.txs.take(5), timeFmt)
        }
        ui.add(box, recent)
    }

    /** Card chứa danh sách dòng giao dịch (lề dọc nhỏ hơn card thường vì mỗi dòng đã có padding). */
    private fun listCard() = ui.card().apply { setPadding(dp(16), dp(14), dp(16), dp(6)) }

    /** Trạng thái trống: icon tròn + lời nhắc. */
    private fun empty(res: Int, msg: String) = ui.vbox().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(12), 0, dp(16))
        addView(ui.iconCircle(res, ui.muted, 48))
        ui.add(this, ui.text(msg, 14f, ui.muted).apply { gravity = Gravity.CENTER }, 10)
    }

    /** Đối soát số dư tháng đang xem cho từng TK: đầu tháng + vào − ra so với số dư cuối theo MB. */
    private fun monthCheckCard(checks: List<Store.MonthCheck>): View {
        val c = ui.card()
        c.addView(ui.listRow(ui.iconCircle(R.drawable.ic_shield, ui.primary, 36), ui.text("Đối soát số dư", 16f, bold = true), null))
        for (k in checks) {
            if (checks.size > 1) ui.add(c, ui.text("TK ${k.account}", 13f, ui.muted, bold = true), 14)
            val table = ui.card(radius = 14, color = ui.surface2).apply { setPadding(dp(14), dp(4), dp(14), dp(12)) }
            fun line(label: String, value: String, strong: Boolean = false) = ui.add(table, ui.split(
                ui.text(label, 13f, if (strong) ui.fg else ui.muted, bold = strong),
                ui.text(value, 13f, bold = strong).apply { fontFeatureSettings = "tnum" },
            ), 8)
            line("Số dư đầu tháng", k.opening?.let { ui.money(it) } ?: "—")
            line("+ Tiền vào", ui.money(k.totalIn))
            line("− Tiền ra" + if (k.fees > 0) " (gồm phí ${ui.short(k.fees)})" else "", ui.money(k.totalOut))
            k.opening?.let { line("Số dư cuối tính ra", ui.money(it + k.totalIn - k.totalOut), strong = true) }
            line("Số dư cuối theo MB", ui.money(k.closing), strong = true)
            ui.add(c, table, 12)

            val diff = k.diff
            val gaps = ledger.inMonth(month).filter { it.gap != null && !it.manual && (it.account == k.account || it.account == null) }
            val status = when {
                diff == null -> ui.callout(
                    R.drawable.ic_info, "Chưa có số dư trước tháng này để đối soát. Nhập số dư thực tế ở tab Cài đặt.", ui.primary,
                )
                diff == 0L && gaps.isEmpty() -> ui.callout(R.drawable.ic_check_circle, "Khớp: không trùng, không sót, phí đã đủ", ui.green)
                else -> ui.callout(
                    R.drawable.ic_warning,
                    buildString {
                        append(if (diff == 0L) "Tổng khớp nhưng" else "Lệch ${if (diff > 0) "thiếu" else "thừa"} ${ui.money(kotlin.math.abs(diff))}")
                        append(
                            if (gaps.isNotEmpty()) "${if (diff == 0L) "" else " ·"} ${gaps.size} giao dịch lệch số dư — chạm để xem"
                            else " · có thể trùng/sót giao dịch không ghi số dư hoặc mốc số dư nhập chưa đúng"
                        )
                    },
                    ui.warn,
                ).apply {
                    if (gaps.isNotEmpty()) setOnClickListener { showTxSheet("Giao dịch lệch số dư", gaps) }
                }
            }
            ui.add(c, status, 10)
        }
        return c
    }

    /** Danh sách giao dịch dạng bottom sheet; chạm để xem chi tiết. */
    private fun showTxSheet(title: String, list: List<Tx>) {
        val dialog = Dialog(this)
        val box = sheetBox()
        ui.add(box, ui.text(title, 20f, bold = true), 16)
        val c = ui.vbox().also { ui.add(box, it, 8) }
        txList(c, list, timeFmt)
        ui.add(box, ui.button("Đóng", filled = false) { dialog.dismiss() }, 16)
        showSheet(dialog, box)
    }

    /** Tổng thu/chi tháng theo tag; chạm 1 dòng để xem các giao dịch của tag đó. */
    private fun tagBreakdown(): View {
        val c = ui.card()
        val side = ui.segmented(listOf("Chi", "Thu"), if (tagIn) 1 else 0) { tagIn = it == 1; renderOverview() }
        c.addView(ui.split(ui.text("Theo tag", 16f, bold = true), side.apply { minimumWidth = dp(128) }))
        val rows = ledger.byTag(month, tagIn)
        if (rows.isEmpty()) {
            ui.add(c, empty(R.drawable.ic_tag, "Không có giao dịch ${if (tagIn) "vào" else "ra"} trong tháng này."), 8)
            return c
        }
        val total = rows.sumOf { it.second }.coerceAtLeast(1)
        for ((tag, amount) in rows) {
            val color = tag?.color ?: ui.muted
            val pct = amount * 100 / total
            val avatar = if (tag == null) ui.iconCircle(R.drawable.ic_tag, color, 36) else ui.letterCircle(tag.name, color, 36)
            val body = ui.vbox().apply {
                addView(ui.split(
                    ui.text(tag?.name ?: "Chưa gắn tag", 14f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    ui.text(ui.money(amount), 14f, bold = true).apply { fontFeatureSettings = "tnum" },
                ))
                ui.add(this, ui.split(ui.bar(amount.toFloat() / total, color), ui.text("$pct%", 12f, ui.muted)), 6)
            }
            val row = ui.listRow(avatar, body, null).apply {
                setPadding(dp(4), dp(10), dp(4), dp(10))
                background = ui.pressable(ui.surface, 12)
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

    private fun kpi(res: Int, label: String, amount: Long, count: Int, color: Int) = ui.card().apply {
        addView(ui.listRow(ui.iconCircle(res, color, 32), ui.text(label, 13f, ui.muted), null))
        ui.add(this, ui.amount(amount, 18f, ui.fg), 12)
        ui.add(this, ui.text("$count giao dịch", 12f, ui.muted), 4)
    }

    private fun legend() = LinearLayout(this).apply {
        addView(ui.text(ui.dotLabel("Vào", ui.green), 12f, ui.muted))
        addView(ui.text(ui.dotLabel("Ra", ui.red), 12f, ui.muted).apply { setPadding(dp(10), 0, 0, 0) })
    }

    private fun monthSwitcher(): View {
        val now = YearMonth.now(Ledger.VN)
        fun arrow(res: Int, enabled: Boolean, onClick: () -> Unit) = ui.iconCircle(res, ui.fg, 40, fill = ui.surface).apply {
            background = ui.pressable(ui.surface, 20, stroke = ui.line)
            alpha = if (enabled) 1f else 0.35f
            setOnClickListener { if (enabled) onClick() }
        }
        val prev = arrow(R.drawable.ic_chevron_left, true) { month = month.minusMonths(1); renderData() }
        val next = arrow(R.drawable.ic_chevron_right, month < now) { month = month.plusMonths(1); renderData() }
        val title = ui.vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ui.text("Tháng ${month.monthValue}", 17f, bold = true))
            addView(ui.text(if (month == now) "${month.year} · tháng này" else "${month.year}", 12f, ui.muted).apply {
                setPadding(0, dp(2), 0, 0)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prev)
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(next)
        }
    }

    private fun txList(parent: LinearLayout, list: List<Tx>, fmt: DateTimeFormatter) {
        list.forEachIndexed { i, t ->
            // Đường kẻ thụt vào sau avatar
            if (i > 0) parent.addView(ui.divider(), LinearLayout.LayoutParams(MATCH, 1).apply { marginStart = dp(52) })
            parent.addView(txRow(t, fmt))
        }
    }

    /** Avatar giao dịch: chữ cái tag, ví (tiền mặt) hoặc mũi tên vào/ra. */
    private fun txAvatar(t: Tx, size: Int = 40): View = when {
        t.tag != null -> ui.letterCircle(t.tag.name, t.tag.color, size)
        t.manual -> ui.iconCircle(R.drawable.ic_wallet, ui.green, size)
        t.isIn -> ui.iconCircle(R.drawable.ic_arrow_down, ui.green, size)
        else -> ui.iconCircle(R.drawable.ic_arrow_up, ui.muted, size)
    }

    /** "Có thể trùng" (số dư không đổi so với mắt xích trước) / "Lệch số dư"; null nếu không lệch. */
    private fun gapLabel(t: Tx): String? {
        val gap = t.gap ?: return null
        val gd = t.amount - t.fee
        return if (gap == (if (t.isIn) gd else -gd)) "Có thể trùng" else "Lệch số dư"
    }

    private fun txRow(t: Tx, fmt: DateTimeFormatter): View {
        val body = ui.vbox()
        body.addView(ui.text(t.description ?: "(không có nội dung)", 15f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        // Thông báo không ghi giờ giao dịch → đang dùng giờ máy nhận, ghi chú để phân biệt
        val meta = SpannableStringBuilder(fmt.format(Instant.ofEpochMilli(t.time)))
        if (!t.timeFromText) meta.append(" · giờ nhận TB")
        t.tag?.let { meta.append(" · ").append(it.name) }
        if (t.manual) meta.append(" · Tiền mặt")
        gapLabel(t)?.let {
            val start = meta.length
            meta.append(" · ").append(it)
            meta.setSpan(ForegroundColorSpan(ui.warn), start, meta.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        ui.add(body, ui.text(meta, 12f, ui.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, 4)
        val amount = ui.amount(if (t.isIn) t.amount else -t.amount, 15f, if (t.isIn) ui.green else ui.fg, signed = true)
        return ui.listRow(txAvatar(t), body, amount).apply {
            setPadding(0, dp(12), 0, dp(12))
            background = ui.pressable(ui.surface, 12)
            setOnClickListener { showTx(t) }
        }
    }

    /** Chi tiết 1 giao dịch dạng bottom sheet: số tiền, tag (chạm để gắn/bỏ), thông tin và thông báo gốc. */
    private fun showTx(t: Tx) {
        val dialog = Dialog(this)
        val color = if (t.isIn) ui.green else ui.red
        val box = sheetBox()
        fun addWrap(v: View, gap: Int) = box.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(gap) })
        // Giữ kích thước view tự đặt (avatar tròn), còn lại WRAP
        fun addCenter(v: View, gap: Int) = box.addView(v, LinearLayout.LayoutParams(v.layoutParams?.width ?: WRAP, v.layoutParams?.height ?: WRAP).apply {
            topMargin = dp(gap)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Đầu sheet căn giữa: avatar, số tiền, nội dung, thời gian
        addCenter(txAvatar(t, 56), 18)
        addCenter(ui.amount(if (t.isIn) t.amount else -t.amount, 32f, if (t.isIn) ui.green else ui.fg, signed = true), 12)
        ui.add(box, ui.text(t.description ?: "(không có nội dung)", 15f).apply {
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
        }, 6)
        val day = dayFmt.format(t.date).replaceFirstChar { it.uppercase() }
        val clock = clockFmt.format(Instant.ofEpochMilli(t.time)) + if (t.timeFromText) "" else " (giờ nhận thông báo)"
        ui.add(box, ui.text("$clock · $day", 13f, ui.muted).apply { gravity = Gravity.CENTER }, 6)
        val pills = LinearLayout(this).apply {
            addView(ui.pill(if (t.isIn) "Tiền vào" else "Tiền ra", color))
            if (t.manual) addView(ui.pill("Tiền mặt", ui.primary), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            gapLabel(t)?.let { addView(ui.pill(it, ui.warn), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) }) }
        }
        addCenter(pills, 10)

        // Tag: chạm để gắn, chạm lại tag đang chọn để bỏ
        ui.add(box, ui.text("Tag", 14f, bold = true), 22)
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
        val fee = t.fee.takeIf { it > 0 }
        val info = listOfNotNull(
            fee?.let { "Số tiền GD" to ui.money(t.amount - it) },
            fee?.let { (if (t.feeConfirmed) "Phí (bạn đã ghi nhận)" else "Phí/VAT (khớp số dư)") to ui.money(it) },
            t.balanceAfter?.let { "Số dư sau giao dịch" to ui.money(it) },
            t.gap?.let { "Lệch số dư" to (if (it > 0) "thiếu " else "thừa ") + ui.money(kotlin.math.abs(it)) },
            t.account?.let { "Tài khoản" to it },
        )
        if (info.isNotEmpty()) {
            val card = ui.card(radius = 16, color = ui.surface2).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
            info.forEachIndexed { i, (label, value) ->
                if (i > 0) card.addView(ui.divider(), LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = dp(11) })
                card.addView(ui.split(
                    ui.text(label, 13f, ui.muted),
                    ui.text(value, 14f, bold = true).apply {
                        fontFeatureSettings = "tnum"
                        setTextIsSelectable(true)
                    },
                ).apply { setPadding(0, if (i > 0) dp(11) else 0, 0, 0) })
            }
            ui.add(box, card, 20)
        }

        t.gap?.let { gap ->
            val gd = t.amount - t.fee
            // Số dư không đổi so với mắt xích trước → lệch đúng bằng GD
            val sameBalance = gap == (if (t.isIn) gd else -gd)
            ui.add(box, ui.callout(
                R.drawable.ic_warning,
                when {
                    sameBalance -> "Số dư không đổi so với giao dịch liền trước — nhiều khả năng đây là bản trùng. " +
                        "Xem thông báo gốc, đúng thì xoá giao dịch này."
                    gap > 0 -> "Số dư sau giao dịch thấp hơn mức tính từ giao dịch liền trước. " +
                        "Có thể sót một giao dịch tiền ra, hoặc bị trừ phí không ghi trong thông báo."
                    else -> "Số dư sau giao dịch cao hơn mức tính từ giao dịch liền trước — có thể sót một giao dịch tiền vào."
                },
                ui.warn,
            ), 10)
            if (gap > 0 && !t.isIn) {
                ui.add(box, ui.button("Ghi nhận ${ui.money(gap)} là phí", filled = false, icon = R.drawable.ic_check_circle) {
                    store.confirmFee(t.id, true)
                    changed = true
                    dialog.dismiss()
                }, 8)
            }
        }
        if (t.feeConfirmed) {
            ui.add(box, ui.button("Bỏ ghi nhận phí", filled = false) {
                store.confirmFee(t.id, false)
                changed = true
                dialog.dismiss()
            }, 8)
        }

        if (raw != null) {
            val rawText = ui.text(raw, 13f).apply {
                setTextIsSelectable(true)
                background = ui.rounded(ui.surface2, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                visibility = View.GONE
            }
            fun label(show: Boolean) = ui.iconText(R.drawable.ic_bell, if (show) "Ẩn thông báo gốc" else "Xem thông báo gốc", ui.primary, 16)
            val toggle = ui.text(label(false), 13f, ui.primary, bold = true).apply {
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    val show = rawText.visibility != View.VISIBLE
                    rawText.visibility = if (show) View.VISIBLE else View.GONE
                    text = label(show)
                }
            }
            addWrap(toggle, 14)
            ui.add(box, rawText, 4)
        }

        ui.add(box, ui.button("Xong") { dialog.dismiss() }, 22)
        if (t.manual) {
            ui.add(box, ui.button("Sửa", filled = false, icon = R.drawable.ic_edit) {
                dialog.dismiss()
                showManualForm(t)
            }, 8)
        }
        ui.add(box, ui.button("Xoá giao dịch", danger = true, icon = R.drawable.ic_delete) {
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
        }, 8)

        dialog.setOnDismissListener { if (changed) reload() }
        showSheet(dialog, box)
    }

    /** Form thêm ([t] null) hoặc sửa giao dịch nhập tay: chiều, số tiền, ngày giờ, nội dung, tag. */
    private fun showManualForm(t: Tx?) {
        val dialog = Dialog(this)
        val box = sheetBox()
        ui.add(box, ui.text(if (t == null) "Thêm giao dịch tiền mặt" else "Sửa giao dịch", 20f, bold = true), 16)
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
        val dirRow = FrameLayout(this)
        fun fillDir() {
            dirRow.removeAllViews()
            dirRow.addView(ui.segmented(listOf("Tiền ra", "Tiền vào"), if (isIn) 1 else 0) { isIn = it == 1; fillDir() })
        }
        fillDir()
        ui.add(box, dirRow, 14)

        val amount = moneyInput("Số tiền (₫)")
        t?.let { amount.setText(it.amount.toString()) }
        ui.add(box, amount, 12)
        val quick = LinearLayout(this)
        for (v in QUICK_AMOUNTS) {
            quick.addView(
                ui.chip(ui.short(v), false) { amount.setText(v.toString()) },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { if (quick.childCount > 0) marginStart = dp(6) },
            )
        }
        ui.add(box, HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(quick)
        }, 8)

        val now = LocalDateTime.now(Ledger.VN).withSecond(0).withNano(0)
        // Thêm mới khi đang xem tháng cũ → mặc định cuối tháng đó
        val at = addDateTime(
            box,
            t?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.time), Ledger.VN) }
                ?: if (month < YearMonth.from(now)) month.atEndOfMonth().atTime(now.toLocalTime()) else now,
        )

        val desc = ui.input("Nội dung (tuỳ chọn), vd: Ăn sáng").apply { setText(t?.description.orEmpty()) }
        ui.add(box, desc, 12)

        ui.add(box, ui.text("Tag", 14f, bold = true), 20)
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
                ui.chip("${m.description} · ${ui.short(m.amount)}", false) {
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
            val time = at().atZone(Ledger.VN).toInstant().toEpochMilli()
            store.saveManual(t?.id, value, isIn, time, desc.text.toString().trim().take(500).ifEmpty { null }, tagId)
            month = YearMonth.from(at())
            dialog.dismiss()
            reload()
        }, 20)
        showSheet(dialog, box)
    }

    /** Ô nhập số tiền cỡ lớn, tự thêm dấu chấm hàng nghìn khi gõ. */
    private fun moneyInput(hint: String): EditText = ui.input(hint).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        textSize = 24f
        typeface = ui.medium
        fontFeatureSettings = "tnum"
        setPadding(dp(16), dp(14), dp(16), dp(14))
        val input = this
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val digits = s.filter { it.isDigit() }.toString().trimStart('0').take(13)
                val f = if (digits.isEmpty()) "" else groupFmt.format(digits.toLong())
                if (f != s.toString()) {
                    input.setText(f)
                    input.setSelection(f.length)
                }
            }
        })
    }

    /** Nút chọn ngày, giờ và chip Hôm nay/Hôm qua thêm vào [box]; trả về hàm đọc thời điểm đang chọn. */
    private fun addDateTime(box: LinearLayout, initial: LocalDateTime): () -> LocalDateTime {
        val today = LocalDate.now(Ledger.VN)
        var at = initial
        val dateBtn = ui.button("", filled = false) {}
        val timeBtn = ui.button("", filled = false) {}
        val dayRow = LinearLayout(this)
        fun showAt() {
            dateBtn.text = ui.iconText(R.drawable.ic_calendar, at.format(dateFmt), ui.primary, 18)
            timeBtn.text = ui.iconText(R.drawable.ic_clock, at.format(hmFmt), ui.primary, 18)
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
        return { at }
    }

    /** Form nhập số dư thực tế (xem trong app MB) làm mốc đối soát. */
    private fun showMarkForm() {
        val accounts = store.accounts(settings.mbPackage)
        if (accounts.isEmpty()) {
            toast("Chưa có giao dịch MB nào ghi số tài khoản và số dư")
            return
        }
        val dialog = Dialog(this)
        val box = sheetBox()
        ui.add(box, ui.text("Số dư thực tế", 20f, bold = true), 16)
        ui.add(box, ui.text(
            "Số dư đang hiện trong app MB tại thời điểm bên dưới (đã gồm mọi giao dịch tới lúc đó). " +
                "App dùng làm mốc để kiểm tra phí, giao dịch sót hoặc trùng ở giao dịch kế tiếp.",
            12f, ui.muted,
        ), 4)
        var account = accounts.first()
        if (accounts.size > 1) {
            val accRow = ui.flow()
            fun fillAcc() {
                accRow.removeAllViews()
                for (a in accounts) accRow.addView(ui.chip("TK $a", a == account) { account = a; fillAcc() })
            }
            fillAcc()
            ui.add(box, accRow, 12)
        }
        val amount = moneyInput("Số dư (₫)")
        ui.add(box, amount, 12)
        val at = addDateTime(box, LocalDateTime.now(Ledger.VN).withSecond(0).withNano(0))
        ui.add(box, ui.button("Lưu") {
            val value = amount.text.filter { it.isDigit() }.toString().toLongOrNull()
            if (value == null) {
                toast("Nhập số dư")
                return@button
            }
            store.addMark(account, value, at().atZone(Ledger.VN).toInstant().toEpochMilli())
            dialog.dismiss()
            renderMarks()
            reload()
        }, 20)
        showSheet(dialog, box)
    }

    /** Danh sách mốc số dư ở Cài đặt; chạm để xoá. */
    private fun renderMarks() {
        marksBox.removeAllViews()
        val marks = store.marks(10)
        if (marks.isEmpty()) {
            marksBox.addView(ui.text("Chưa nhập mốc nào.", 13f, ui.muted))
            return
        }
        for (m in marks) {
            val gap = m.gap
            val (res, color, state) = when {
                gap == null -> Triple(R.drawable.ic_info, ui.muted, "Chưa có giao dịch trước để so")
                gap == 0L -> Triple(R.drawable.ic_check_circle, ui.green, "Khớp với giao dịch trước")
                else -> Triple(
                    R.drawable.ic_warning, ui.warn,
                    "Lệch ${if (gap > 0) "thiếu" else "thừa"} ${ui.money(kotlin.math.abs(gap))} so với giao dịch trước",
                )
            }
            val body = ui.vbox().apply {
                addView(ui.split(ui.amount(m.balance, 15f), ui.text(fmt(m.at), 12f, ui.muted)))
                ui.add(this, ui.text("TK ${m.account} · $state", 12f, if (color == ui.muted) ui.muted else color), 4)
            }
            val v = ui.listRow(ui.iconCircle(res, color, 36), body, null).apply {
                setPadding(dp(4), dp(10), dp(4), dp(10))
                background = ui.pressable(ui.surface, 12)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Xoá mốc số dư này?")
                        .setPositiveButton("Xoá") { _, _ ->
                            store.deleteMark(m.id)
                            renderMarks()
                            reload()
                        }
                        .setNegativeButton("Huỷ", null)
                        .show()
                }
            }
            ui.add(marksBox, v, 4)
        }
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

        val filters = listOf(Filter.ALL, Filter.IN, Filter.OUT)
        ui.add(box, ui.segmented(listOf("Tất cả", "Tiền vào", "Tiền ra"), filters.indexOf(filter)) {
            filter = filters[it]
            renderTransactions()
        }, 12)

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
        }, 10)

        val list = ledger.inMonth(month).filter {
            if (it.manual) return@filter false
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
            ui.add(box, ui.card().apply { addView(empty(R.drawable.ic_list, "Không có giao dịch phù hợp trong tháng này.")) })
        } else {
            val (ins, outs) = list.partition { it.isIn }
            ui.add(box, ui.text(
                "${list.size} giao dịch · vào ${ui.short(ins.sumOf { it.amount })} · ra ${ui.short(outs.sumOf { it.amount })}",
                12f, ui.muted,
            ).apply { setPadding(dp(4), 0, dp(4), 0) }, 14)
        }
        dayGroups(box, list)

        val unparsed = ledger.unparsed.filter { Ledger.monthOf(it.postedAt) == month }
        if (unparsed.isNotEmpty()) {
            val c = ui.card()
            c.addView(ui.listRow(
                ui.iconCircle(R.drawable.ic_warning, ui.warn, 36),
                ui.vbox().apply {
                    addView(ui.text("Chưa đọc được (${unparsed.size})", 15f, bold = true))
                    ui.add(this, ui.text("Mẫu thông báo mới parser chưa hỗ trợ — chưa tính vào thống kê.", 12f, ui.muted), 2)
                },
                null,
            ))
            for (it in unparsed.take(20)) {
                ui.add(c, ui.vbox().apply {
                    background = ui.rounded(ui.surface2, 12)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(ui.text(timeFmt.format(Instant.ofEpochMilli(it.postedAt)), 12f, ui.muted))
                    ui.add(this, ui.text(it.text, 13f).apply {
                        maxLines = 4
                        ellipsize = TextUtils.TruncateAt.END
                    }, 4)
                }, 10)
            }
            ui.add(box, c, 20)
        }
    }

    /** Danh sách giao dịch nhóm theo ngày, mỗi ngày kèm tổng vào − ra. */
    private fun dayGroups(box: LinearLayout, list: List<Tx>) {
        for ((day, items) in list.groupBy { it.date }) {
            val net = items.sumOf { if (it.isIn) it.amount else -it.amount }
            val head = ui.split(
                ui.text(dayFmt.format(day).replaceFirstChar { it.uppercase() }, 13f, ui.muted, bold = true),
                ui.text(ui.money(net, signed = true), 13f, if (net > 0) ui.green else ui.muted, bold = true).apply {
                    fontFeatureSettings = "tnum"
                },
            ).apply { setPadding(dp(4), 0, dp(4), 0) }
            ui.add(box, head, 18)
            val c = ui.card().apply { setPadding(dp(14), dp(2), dp(14), dp(2)) }
            txList(c, items, clockFmt)
            ui.add(box, c, 8)
        }
    }

    // ------------------------------------------------------------------ tab Tiền mặt

    /** Giao dịch nhập tay: tiền mặt hiện có (mọi thời gian), vào/ra và danh sách của tháng đang xem. */
    private fun renderCash() {
        val box = cashBox
        box.removeAllViews()
        ui.add(box, monthSwitcher(), 4)

        val hero = ui.hero(ui.cashStart, ui.cashEnd)
        hero.addView(ui.text(ui.iconText(R.drawable.ic_wallet, "Tiền mặt hiện có", ui.onHero, 16), 13f, ui.onHero).apply { alpha = 0.85f })
        ui.add(hero, ui.amount(ledger.cashBalance, 32f, ui.onHero), 8)
        ui.add(box, hero, 8)

        val list = ledger.inMonth(month).filter { it.manual }
        val (ins, outs) = list.partition { it.isIn }
        ui.add(box, ui.row(
            kpi(R.drawable.ic_arrow_down, "Tiền vào", ins.sumOf { it.amount }, ins.size, ui.green),
            kpi(R.drawable.ic_arrow_up, "Tiền ra", outs.sumOf { it.amount }, outs.size, ui.red),
            gap = 12,
        ))
        if (list.isEmpty()) {
            ui.add(box, ui.card().apply {
                addView(empty(R.drawable.ic_wallet, "Chưa có giao dịch tiền mặt trong tháng này. Bấm + để thêm."))
            })
        }
        dayGroups(box, list)
    }

    // ------------------------------------------------------------------ tab Cài đặt

    /** Mục cài đặt: tiêu đề có icon + card nội dung. */
    private fun section(title: String, res: Int): LinearLayout {
        ui.add(settingsBox, ui.text(ui.iconText(res, title, ui.primary, 18), 15f, bold = true).apply {
            setPadding(dp(4), 0, 0, 0)
        }, 24)
        return ui.card().also { ui.add(settingsBox, it, 10) }
    }

    private fun buildSettings() {
        val perm = section("Quyền & chạy nền", R.drawable.ic_shield)
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

        val tagSec = section("Tag thu chi", R.drawable.ic_tag)
        tagSec.addView(ui.text("Gắn tag trong màn chi tiết giao dịch. Chạm tag ở đây để đổi tên hoặc xoá.", 12f, ui.muted))
        tagsBox = ui.flow().also { ui.add(tagSec, it, 10) }
        renderTagSettings()

        val bank = section("App ngân hàng", R.drawable.ic_bank)
        pkgText = ui.text("", 14f).also { bank.addView(it) }
        ui.add(bank, ui.text(
            "App vừa có thông báo (bấm để chọn làm app MB Bank). Chỉ lưu tên app, không lưu nội dung:",
            12f, ui.muted,
        ), 6)
        sourcesBox = ui.vbox().also { ui.add(bank, it, 6) }

        val markSec = section("Số dư thực tế", R.drawable.ic_wallet)
        markSec.addView(ui.text(
            "Thỉnh thoảng (vd đầu tháng, hoặc khi thấy lệch) mở app MB xem số dư rồi nhập vào đây. " +
                "Mốc này giúp đối soát phí, giao dịch sót/trùng ở giao dịch kế tiếp và số dư đầu tháng ở Tổng quan. Chạm mốc để xoá.",
            12f, ui.muted,
        ))
        ui.add(markSec, ui.button("Nhập số dư thực tế", icon = R.drawable.ic_add) { showMarkForm() }, 12)
        marksBox = ui.vbox().also { ui.add(markSec, it, 8) }
        renderMarks()

        val scan = section("Quét thông báo", R.drawable.ic_sync)
        scanText = ui.text("", 14f).also { scan.addView(it) }
        ui.add(scan, ui.button("Quét lại thông báo đang hiện", icon = R.drawable.ic_sync) { scanNotifications() }, 12)
        ui.add(scan, ui.button("Kiểm tra lại dữ liệu đã lưu", filled = false, icon = R.drawable.ic_shield) { recheckStored() }, 8)
        ui.add(scan, ui.text(
            "Quét lại thanh thông báo, đọc lại mọi thông báo MB đã lưu, xoá giao dịch bị lưu trùng (kể cả bản bị cắt nội dung), " +
                "tính lại phí/VAT theo số dư và đánh dấu giao dịch lệch số dư.",
            12f, ui.muted,
        ), 6)

        val log = section("Thông báo MB đã bắt", R.drawable.ic_bell)
        recentBox = ui.vbox().also { log.addView(it) }
    }

    private fun renderStatus() {
        val listenerOn = MbNotificationListener.isEnabled(this)
        val connected = MbNotificationListener.instance != null
        permText.text = when {
            !listenerOn -> ui.iconText(R.drawable.ic_error, "Chưa cấp quyền đọc thông báo", ui.red, 18)
            !connected -> ui.iconText(
                R.drawable.ic_warning,
                "Đã cấp quyền nhưng dịch vụ đọc thông báo chưa chạy — đang thử kết nối lại. Nếu kéo dài: tắt rồi bật lại quyền.",
                ui.warn, 18,
            )
            else -> ui.iconText(R.drawable.ic_check_circle, "Đang nhận thông báo", ui.green, 18)
        }
        permButton.visibility = if (listenerOn && connected) View.GONE else View.VISIBLE

        val pm = getSystemService(PowerManager::class.java)
        val unrestricted = pm?.isIgnoringBatteryOptimizations(packageName) == true
        batteryText.text =
            if (unrestricted) ui.iconText(R.drawable.ic_check_circle, "Được chạy nền không giới hạn", ui.green, 18)
            else ui.iconText(R.drawable.ic_warning, "Hệ thống có thể tắt app để tiết kiệm pin", ui.warn, 18)
        batteryButton.visibility = if (unrestricted) View.GONE else View.VISIBLE

        val ok = listenerOn && connected
        headerStatus.text = ui.dotLabel(
            when {
                !listenerOn -> "Chưa cấp quyền đọc thông báo — vào tab Cài đặt"
                !connected -> "Dịch vụ đọc thông báo chưa chạy — xem tab Cài đặt"
                else -> "Đang theo dõi ${label(settings.mbPackage)}"
            },
            if (ok) ui.green else ui.warn,
        )
        headerStatus.setTextColor(if (ok) ui.muted else ui.warn)

        pkgText.text = "Đang theo dõi: ${label(settings.mbPackage)} (${settings.mbPackage})"

        val sources = store.sources(12)
        val sKey = sources.joinToString { "${it.pkg}:${it.count}" } + settings.mbPackage
        if (sKey != lastSourcesKey) {
            lastSourcesKey = sKey
            sourcesBox.removeAllViews()
            if (sources.isEmpty()) sourcesBox.addView(ui.text("Chưa thấy thông báo nào. Hãy cấp quyền ở trên.", 13f, ui.muted))
            for (s in sources) {
                val chosen = s.pkg == settings.mbPackage
                val body = ui.vbox().apply {
                    addView(ui.text(label(s.pkg), 14f, if (chosen) ui.primary else ui.fg, bold = true))
                    ui.add(this, ui.text("${s.pkg} · ${s.count} thông báo · ${fmt(s.lastSeen)}", 12f, ui.muted), 2)
                }
                val v = ui.listRow(
                    ui.iconCircle(if (chosen) R.drawable.ic_check_circle else R.drawable.ic_bell, if (chosen) ui.primary else ui.muted, 36),
                    body, null,
                ).apply {
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = ui.pressable(if (chosen) ui.primarySoft else ui.surface, 12)
                    setOnClickListener { choosePackage(s.pkg) }
                }
                ui.add(sourcesBox, v, 4)
            }
        }

        scanText.text = SpannableStringBuilder("Đã lưu ${ledger.txs.size} giao dịch trên máy").apply {
            settings.lastError?.let { append("\n").append(ui.iconText(R.drawable.ic_warning, "$it (${fmt(settings.lastErrorAt)})", ui.warn)) }
        }

        val recent = store.recent(15)
        val rKey = recent.joinToString { "${it.id}" }
        if (rKey != lastRecentKey) {
            lastRecentKey = rKey
            recentBox.removeAllViews()
            if (recent.isEmpty()) recentBox.addView(ui.text("Chưa có. Thông báo từ app đang theo dõi sẽ hiện ở đây.", 13f, ui.muted))
            recent.forEachIndexed { i, it ->
                val (state, color) = when {
                    it.deleted -> "Đã xoá" to ui.muted
                    Parser.parse(it.text) != null -> "Giao dịch" to ui.green
                    Parser.looksLikeTransaction(it.text) -> "Chưa đọc được" to ui.warn
                    else -> "Không phải giao dịch" to ui.muted
                }
                if (i > 0) ui.add(recentBox, ui.divider().apply { minimumHeight = 1 }, 12)
                ui.add(recentBox, ui.split(ui.text(fmt(it.postedAt), 12f, ui.muted), ui.pill(state, color)), if (i > 0) 12 else 0)
                ui.add(recentBox, ui.text(it.text, 13f).apply {
                    maxLines = 5
                    ellipsize = TextUtils.TruncateAt.END
                }, 6)
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
                    if (r.gaps > 0) append(", ${r.gaps} giao dịch lệch số dư")
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
        private const val CASH_TAB = 2
        private val QUICK_AMOUNTS = listOf(20_000L, 50_000L, 100_000L, 200_000L, 500_000L)
    }
}

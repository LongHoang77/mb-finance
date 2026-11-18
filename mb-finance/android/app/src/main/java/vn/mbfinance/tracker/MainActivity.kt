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
import vn.mbfinance.tracker.game.GameJob
import vn.mbfinance.tracker.game.GameScreen
import vn.mbfinance.tracker.game.GameSync
import vn.mbfinance.tracker.game.Hoyolab
import vn.mbfinance.tracker.update.Updater
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/** 3 tab: Tổng quan, Giao dịch (ngân hàng + tiền mặt, lọc theo nguồn) và Cài đặt. Mọi dữ liệu chỉ nằm trên máy. */
class MainActivity : Activity() {

    private enum class Filter { ALL, IN, OUT }
    private enum class Source { ALL, BANK, CASH }

    private lateinit var settings: Settings
    private lateinit var store: Store
    private lateinit var ui: Ui

    private lateinit var headerTitle: TextView
    private lateinit var headerStatus: TextView
    /** 2 module tách biệt: Tài chính (3 tab + nút +) và Game (GameScreen, package game/). */
    private var module = MODULE_FINANCE
    private lateinit var financeContent: View
    private lateinit var financeNav: View
    private lateinit var game: GameScreen
    private val moduleButtons = ArrayList<Pair<View, ImageView>>()
    private lateinit var pages: List<ScrollView>
    private lateinit var overviewBox: LinearLayout
    private lateinit var txBox: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var fab: View
    private class NavItem(val pill: View, val icon: ImageView, val label: TextView)

    private val tabViews = ArrayList<NavItem>()

    private lateinit var permText: TextView
    private lateinit var permButton: View
    private lateinit var batteryText: TextView
    private lateinit var batteryButton: View
    private lateinit var pkgText: TextView
    private lateinit var logText: TextView
    private lateinit var dupText: TextView
    private lateinit var updateText: TextView
    private lateinit var updateButton: TextView
    private lateinit var tokenButton: TextView
    private lateinit var scanText: TextView
    private lateinit var tagsBox: LinearLayout
    private lateinit var marksBox: LinearLayout

    private var ledger = Ledger.EMPTY
    private var ledgerKey = ""
    private var loading = false
    private var month: YearMonth = YearMonth.now(Ledger.VN)
    private var filter = Filter.ALL
    /** Lọc theo nguồn ở tab Giao dịch: ngân hàng (thông báo MB) / tiền mặt (nhập tay). */
    private var source = Source.ALL
    /** Lọc theo tag ở tab Giao dịch: null = mọi tag, [UNTAGGED] = chưa gắn. */
    private var tagFilter: Long? = null
    /** Thẻ "Theo tag" ở Tổng quan đang xem tiền vào hay ra, và có hiện hết các tag không (mặc định 5). */
    private var tagIn = false
    private var tagsExpanded = false

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
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(6))
        }
        val titleCol = ui.vbox()
        headerTitle = ui.text("Thu chi MB", 26f, bold = true).apply { letterSpacing = -0.02f }
        titleCol.addView(headerTitle)
        headerStatus = ui.text("", 13f, ui.muted).also { ui.add(titleCol, it, 4) }
        header.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(moduleSwitch())
        shell.addView(header)

        overviewBox = ui.vbox()
        txBox = ui.vbox()
        settingsBox = ui.vbox()
        val content = FrameLayout(this)
        pages = listOf(overviewBox, txBox, settingsBox).map { box ->
            box.setPadding(dp(16), dp(4), dp(16), dp(28))
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(box)
            }.also { content.addView(it) }
        }
        // Chừa chỗ cho nút nổi
        for (box in listOf(overviewBox, txBox)) box.setPadding(dp(16), dp(4), dp(16), dp(104))
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
        game = GameScreen(this, ui)
        shell.addView(game.root, LinearLayout.LayoutParams(MATCH, 0, 1f))
        financeContent = content
        financeNav = bottomNav()
        shell.addView(financeNav)
        setContentView(shell)

        buildSettings()
        renderData()
        selectTab(0)
        settings.dropServerConfig()
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        selectModule(intent.getIntExtra(EXTRA_MODULE, prefs.getInt("module", MODULE_FINANCE)), show = false)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        // Mở app = quét bù thông báo bị lỡ khi listener bị hệ thống ngắt
        thread { runCatching { MbNotificationListener.catchUp(applicationContext) } }
        if (Updater.shouldAutoCheck(this)) checkUpdate(manual = false)
        renderUpdate() // lỗi cài đặt (UpdateReceiver) ghi khi app ở nền
        // Mở app cũng là dịp tự điểm danh bù (ROM hay giết tác vụ nền)
        if (module == MODULE_GAME) {
            game.onShow()
        } else {
            val app = applicationContext
            thread {
                runCatching {
                    if (Hoyolab.loggedIn()) {
                        GameJob.ensure(app)
                        GameSync.run(app)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ module

    /** Nút chuyển module ở header: ví (Tài chính) | tay cầm (Game). */
    private fun moduleSwitch(): View {
        val wrap = LinearLayout(this).apply {
            background = ui.rounded(ui.surface2, 22)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf(R.drawable.ic_wallet to "Tài chính", R.drawable.ic_game to "Game").forEachIndexed { i, (res, label) ->
            val icon = ui.icon(res, ui.muted, 22)
            val btn = FrameLayout(this).apply {
                contentDescription = label
                addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                setOnClickListener { selectModule(i) }
            }
            moduleButtons.add(btn to icon)
            wrap.addView(btn, LinearLayout.LayoutParams(dp(44), dp(36)))
        }
        return wrap
    }

    private fun selectModule(m: Int, show: Boolean = true) {
        module = if (m == MODULE_GAME) MODULE_GAME else MODULE_FINANCE
        val fin = module == MODULE_FINANCE
        financeContent.visibility = if (fin) View.VISIBLE else View.GONE
        financeNav.visibility = if (fin) View.VISIBLE else View.GONE
        game.root.visibility = if (fin) View.GONE else View.VISIBLE
        headerTitle.text = if (fin) "Thu chi MB" else "Honkai: Star Rail"
        headerTitle.textSize = if (fin) 26f else 22f
        headerStatus.visibility = if (fin) View.VISIBLE else View.GONE
        moduleButtons.forEachIndexed { i, (btn, icon) ->
            val on = i == module
            btn.background = if (on) ui.rounded(ui.surface, 18) else null
            icon.imageTintList = ColorStateList.valueOf(if (on) ui.primary else ui.muted)
        }
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("module", module).apply()
        if (!fin && show) game.onShow()
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
        fab.visibility = if (i == SETTINGS_TAB) View.GONE else View.VISIBLE
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
        renderTagSettings() // số liệu bảng tag theo sổ mới
        renderMarks() // lệch của mốc đổi khi có giao dịch đến trễ
    }

    /** Đổi tag không làm đổi dataKey → ép đọc lại sổ. */
    private fun reload() {
        ledgerKey = ""
        render()
    }

    // ------------------------------------------------------------------ tab Tổng quan

    /**
     * Tổng quan gọn: thẻ chính (số dư, vào/ra/chênh lệch tháng, 1 dòng đối soát), mục tiêu (≤ 3),
     * theo tag (≤ 5, mở rộng được), biểu đồ 6 tháng, 5 giao dịch gần đây.
     */
    private fun renderOverview() {
        val box = overviewBox
        box.removeAllViews()
        ui.add(box, monthSwitcher(), 4)
        val s = ledger.sum(month)

        val bank = ledger.latestBalance
        val hero = ui.hero()
        val onHero = ui.onHero
        fun faded(v: TextView) = v.apply { alpha = 0.78f }
        // Chạm → tab Giao dịch lọc theo nguồn / chiều đó
        fun openTx(src: Source, f: Filter) {
            source = src
            filter = f
            tagFilter = null
            renderTransactions()
            selectTab(TX_TAB)
        }
        fun stat(res: Int, label: String, value: String, onClick: () -> Unit) = ui.vbox().apply {
            addView(faded(ui.text(ui.iconText(res, label, onHero, 14), 12f, onHero)))
            ui.add(this, ui.text(value, 15f, onHero, bold = true).apply {
                fontFeatureSettings = "tnum"
                maxLines = 1
            }, 4)
            setOnClickListener { onClick() }
        }
        if (bank != null || ledger.hasCash) {
            hero.addView(faded(ui.text("Tổng số dư", 13f, onHero)))
            ui.add(hero, ui.amount((bank ?: 0) + ledger.cashBalance, 32f, onHero), 6)
            ui.add(hero, ui.row(
                stat(R.drawable.ic_bank, "Ngân hàng", bank?.let { ui.money(it) } ?: "—") { openTx(Source.BANK, Filter.ALL) },
                stat(R.drawable.ic_wallet, "Tiền mặt", ui.money(ledger.cashBalance)) { openTx(Source.CASH, Filter.ALL) },
                gap = 12,
            ), 14)
            ui.add(hero, View(this).apply { setBackgroundColor(0x33FFFFFF) }.also { it.minimumHeight = 1 }, 14)
        }
        ui.add(hero, ui.row(
            stat(R.drawable.ic_arrow_down, "Vào T${month.monthValue}", ui.short(s.totalIn)) { openTx(Source.ALL, Filter.IN) },
            stat(R.drawable.ic_arrow_up, "Ra T${month.monthValue}", ui.short(s.totalOut)) { openTx(Source.ALL, Filter.OUT) },
            stat(R.drawable.ic_calendar, "Chênh lệch", (if (s.net > 0) "+" else "") + ui.short(s.net)) { openTx(Source.ALL, Filter.ALL) },
            gap = 8,
        ), if (bank != null || ledger.hasCash) 14 else 0)
        ledger.checks[month]?.let { checks ->
            val (res, msg) = checkSummary(checks)
            ui.add(hero, ui.text(ui.iconText(res, msg, onHero, 16), 12f, onHero, bold = true).apply {
                background = ui.rounded(0x26FFFFFF, 12)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { showChecks(checks) }
            }, 14)
        }
        ui.add(box, hero, 8)

        ui.add(box, plansCard(s))
        ui.add(box, tagBreakdown())

        val now = YearMonth.now(Ledger.VN)
        val end = if (month < now.minusMonths(5)) month else now
        val chart = ui.card()
        chart.addView(ui.split(ui.text("6 tháng gần đây", 16f, bold = true), legend()))
        chart.addView(
            MonthBars(this, ui, ledger.lastMonths(end, 6), month) { month = it; renderData() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) },
        )
        ui.add(box, chart)

        val recent = listCard()
        recent.addView(ui.header("Giao dịch gần đây", if (ledger.txs.isEmpty()) null else "Xem tất cả") { openTx(Source.ALL, Filter.ALL) }
            .apply { setPadding(0, 0, 0, dp(4)) })
        if (ledger.txs.isEmpty()) {
            ui.add(recent, empty(R.drawable.ic_bell, "Chưa có giao dịch. Khi MB Bank báo biến động số dư, giao dịch sẽ hiện ở đây."), 8)
        } else {
            txList(recent, ledger.txs.take(5), timeFmt)
        }
        ui.add(box, recent)
    }

    /** 1 dòng tóm tắt đối soát tháng cho thẻ chính: icon + lời nhắn. */
    private fun checkSummary(checks: List<Store.MonthCheck>): Pair<Int, String> {
        val gaps = ledger.inMonth(month).count { it.gap != null && !it.manual }
        val diffs = checks.mapNotNull { it.diff }
        val off = diffs.filter { it != 0L }
        return when {
            diffs.isEmpty() && gaps == 0 -> R.drawable.ic_info to "Đối soát: chưa có số dư đầu tháng · xem"
            off.isEmpty() && gaps == 0 -> R.drawable.ic_check_circle to "Đối soát số dư: khớp"
            else -> R.drawable.ic_warning to buildString {
                append("Đối soát: ")
                val parts = listOfNotNull(
                    off.takeIf { it.isNotEmpty() }?.let { "lệch ${ui.short(kotlin.math.abs(it.sum()))}" },
                    gaps.takeIf { it > 0 }?.let { "$it giao dịch lệch" },
                )
                append(parts.joinToString(" · ").ifEmpty { "cần xem" })
                append(" · xem")
            }
        }
    }

    /** Bảng đối soát số dư tháng đang xem trong bottom sheet. */
    private fun showChecks(checks: List<Store.MonthCheck>) {
        val dialog = Dialog(this)
        val box = sheetBox()
        ui.add(box, monthCheckCard(checks).apply { setPadding(0, dp(8), 0, 0); background = null }, 8)
        ui.add(box, ui.button("Đóng", filled = false) { dialog.dismiss() }, 16)
        showSheet(dialog, box)
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

    /**
     * Thu/chi tháng theo tag: biểu đồ vành khuyên + chú thích (tên, số tiền, %). 5 tag lớn nhất, còn lại gộp "Khác"
     * (chạm để xem hết). Chạm phần biểu đồ để xem nhanh, chạm dòng chú thích để mở giao dịch của tag đó.
     */
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
        val shown = if (tagsExpanded || rows.size <= 6) rows else rows.take(5)
        val rest = rows.drop(shown.size)
        // null tag = chưa gắn (xám đậm); "Khác" = xám nhạt
        val parts = shown.map { (tag, amount) -> DonutChart.Part(tag?.name ?: "Chưa gắn tag", amount, tag?.color ?: ui.muted) } +
            listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let { r ->
                DonutChart.Part("Khác (${r.size} tag)", r.sumOf { it.second }, ui.soft(ui.muted, 0x66))
            })
        val donut = DonutChart(this, ui, parts, "${if (tagIn) "Thu" else "Chi"} tháng ${month.monthValue}")
        c.addView(donut, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        val legendRows = parts.mapIndexed { i, part ->
            val tag = shown.getOrNull(i)?.first
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(9), dp(8), dp(9))
                background = ui.pressable(ui.surface, 10)
                addView(View(this@MainActivity).apply { background = ui.oval(part.color) }, LinearLayout.LayoutParams(dp(10), dp(10)))
                addView(ui.text(part.label, 14f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
                addView(ui.text(ui.money(part.value), 14f, bold = true).apply { fontFeatureSettings = "tnum" })
                addView(ui.text("${part.value * 100 / total}%", 12f, ui.muted).apply {
                    gravity = Gravity.END
                    fontFeatureSettings = "tnum"
                }, LinearLayout.LayoutParams(dp(44), WRAP))
                setOnClickListener {
                    if (i >= shown.size) {
                        tagsExpanded = true
                        renderOverview()
                    } else {
                        tagFilter = tag?.id ?: UNTAGGED
                        filter = if (tagIn) Filter.IN else Filter.OUT
                        source = Source.ALL
                        renderTransactions()
                        selectTab(TX_TAB)
                    }
                }
            }.also { ui.add(c, it, if (i == 0) 8 else 2) }
        }
        // Phần đang chọn trên biểu đồ ↔ dòng chú thích được tô nền
        donut.onSelect = { sel ->
            legendRows.forEachIndexed { j, v -> v.background = if (j == sel) ui.rounded(ui.surface2, 10) else ui.pressable(ui.surface, 10) }
        }
        if (tagsExpanded && rows.size > 6) {
            ui.add(c, ui.text("Thu gọn", 13f, ui.primary, bold = true).apply {
                setPadding(dp(8), dp(8), dp(8), dp(4))
                setOnClickListener {
                    tagsExpanded = false
                    renderOverview()
                }
            }, 4)
        }
        return c
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

    private fun descText(t: Tx) = t.description?.takeIf { it.isNotBlank() } ?: "(không có nội dung)"

    /** Avatar giao dịch: chữ cái tag, ví (tiền mặt) hoặc mũi tên vào/ra. */
    private fun txAvatar(t: Tx, size: Int = 40): View = when {
        t.tag != null -> ui.letterCircle(t.tag.name, t.tag.color, size)
        t.manual -> ui.iconCircle(R.drawable.ic_wallet, ui.green, size)
        t.isIn -> ui.iconCircle(R.drawable.ic_arrow_down, ui.green, size)
        else -> ui.iconCircle(R.drawable.ic_arrow_up, ui.muted, size)
    }

    /**
     * "Có thể trùng" (số dư không đổi so với mắt xích trước → số tiền theo số dư = 0) / "Lệch số dư"
     * (GD trong tin nhắn khác biến động số dư); null nếu khớp.
     */
    private fun gapLabel(t: Tx): String? {
        if (t.gap == null) return null
        return if (t.amount == 0L) "Có thể trùng" else "Lệch số dư"
    }

    private fun txRow(t: Tx, fmt: DateTimeFormatter): View {
        val body = ui.vbox()
        body.addView(ui.text(descText(t), 15f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        // Thông báo không ghi giờ giao dịch → đang dùng giờ máy nhận, ghi chú để phân biệt
        val meta = SpannableStringBuilder(fmt.format(Instant.ofEpochMilli(t.time)))
        if (!t.timeFromText) meta.append(" · giờ nhận TB")
        t.tag?.let { meta.append(" · ").append(it.name) }
        if (t.manual) meta.append(" · Tiền mặt")
        t.plan?.let { meta.append(" · ${planLabel(t, it)}: ${it.name}") }
        ledger.suggestions[t.id]?.let {
            val start = meta.length
            meta.append(" · Trả nốt \"${it.name}\"?")
            meta.setSpan(ForegroundColorSpan(ui.warn), start, meta.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        gapLabel(t)?.let {
            val start = meta.length
            meta.append(" · ").append(it)
            meta.setSpan(ForegroundColorSpan(ui.warn), start, meta.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (t.dups > 0) meta.append(" · gộp ${t.dups} TB trùng")
        ui.add(body, ui.text(meta, 12f, ui.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, 4)
        t.note?.let {
            ui.add(body, ui.text(ui.iconText(R.drawable.ic_note, it, ui.primary, 14), 12f, ui.fg).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, 3)
        }
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
        ui.add(box, ui.text(descText(t), 15f).apply {
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
        }, 6)
        val day = dayFmt.format(t.date).replaceFirstChar { it.uppercase() }
        val clock = clockFmt.format(Instant.ofEpochMilli(t.time)) + if (t.timeFromText) "" else " (giờ nhận thông báo)"
        ui.add(box, ui.text("$clock · $day", 13f, ui.muted).apply { gravity = Gravity.CENTER }, 6)
        val pills = LinearLayout(this).apply {
            addView(ui.pill(if (t.isIn) "Tiền vào" else "Tiền ra", color))
            if (t.manual) addView(ui.pill("Tiền mặt", ui.primary), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            t.plan?.let {
                addView(ui.pill(planLabel(t, it), planColor(it)), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            }
            if (ledger.suggestions.containsKey(t.id)) {
                addView(ui.pill("Chờ xác nhận", ui.warn), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            }
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

        // Khoản: chỉ tiền ra (đặt cọc, trả tiếp cho khoản cọc, chuyển tiết kiệm)
        if (!t.isIn) {
            ui.add(box, ui.text("Khoản", 14f, bold = true), 22)
            val plan = t.plan
            val suggested = ledger.suggestions[t.id]
            fun link(p: Plan) {
                store.setPlan(t.id, p.id)
                changed = true
                dialog.dismiss()
            }
            when {
                plan != null -> {
                    ui.add(box, planRow(plan).apply {
                        setOnClickListener {
                            dialog.dismiss()
                            showPlan(plan)
                        }
                    }, 6)
                    ui.add(box, ui.button("Gỡ khỏi khoản", filled = false) {
                        store.setPlan(t.id, null)
                        changed = true
                        dialog.dismiss()
                    }, 6)
                }
                // Đúng bằng số còn phải trả của 1 khoản cọc → hỏi xác nhận
                suggested != null -> {
                    ui.add(box, ui.callout(
                        R.drawable.ic_warning,
                        "Giao dịch này đúng bằng số còn phải trả của khoản cọc \"${suggested.name}\". Có phải trả nốt không?",
                        ui.warn,
                    ), 8)
                    ui.add(box, ui.row(
                        ui.button("Đúng, trả nốt", icon = R.drawable.ic_check_circle) { link(suggested) },
                        ui.button("Không phải", filled = false) {
                            store.dismissSuggestion(t.id, suggested.id)
                            changed = true
                            dialog.dismiss()
                        },
                    ), 8)
                }
                else -> {
                    ui.add(box, ui.row(
                        ui.button("Đánh dấu là cọc", filled = false, icon = R.drawable.ic_calendar) {
                            showPlanForm(null, false, deposit = t.amount - t.fee) { link(it) }
                        },
                        ui.button("Chuyển tiết kiệm", filled = false, icon = R.drawable.ic_wallet) { showPlanPicker(true) { link(it) } },
                    ), 8)
                    if (ledger.plans.any { !it.saving && !it.done }) {
                        ui.add(box, ui.button("Trả tiếp cho khoản cọc…", filled = false, icon = R.drawable.ic_arrow_up) {
                            showPlanPicker(false) { link(it) }
                        }, 8)
                    }
                }
            }
        }

        val raw = if (t.manual) null else store.text(t.id)
        val fee = t.fee.takeIf { it > 0 }
        // Số tiền hiển thị tính theo biến động số dư; GD trong tin nhắn chỉ để đối chiếu khi khác
        val msg = raw?.let { Parser.parse(it) }?.takeIf { it.amount != t.amount || it.isIn != t.isIn }
        val info = listOfNotNull(
            msg?.let { "Tin nhắn MB ghi" to ui.money(if (it.isIn) it.amount else -it.amount, signed = true) },
            fee?.let { (if (t.feeConfirmed) "Phí (bạn đã ghi nhận)" else "Phí/VAT (khớp số dư)") to ui.money(it) },
            t.balanceAfter?.let { "Số dư sau giao dịch" to ui.money(it) },
            t.gap?.let { "Chênh so với tin nhắn" to (if (it > 0) "số dư thấp hơn " else "số dư cao hơn ") + ui.money(kotlin.math.abs(it)) },
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
            ui.add(box, ui.callout(
                R.drawable.ic_warning,
                when {
                    t.amount == 0L -> "Số dư không đổi so với giao dịch liền trước nên giao dịch này tính 0đ — nhiều khả năng " +
                        "MB gửi trùng. Xem thông báo gốc, đúng thì xoá giao dịch này."
                    gap > 0 -> "Số tiền đã tính theo số dư: cao hơn số MB ghi ${ui.money(gap)}. Phần chênh có thể là phí MB không ghi, " +
                        "hoặc 1 giao dịch tiền ra bị sót được gộp vào đây. Ghi chú lại nếu cần."
                    else -> "Số tiền đã tính theo số dư: số dư cao hơn mức tin nhắn ${ui.money(-gap)} — có thể 1 giao dịch tiền vào " +
                        "bị sót được gộp vào đây. Ghi chú lại nếu cần."
                },
                ui.warn,
            ), 10)
            if (gap > 0 && !t.isIn) {
                ui.add(box, ui.button("Đúng, ${ui.money(gap)} là phí", filled = false, icon = R.drawable.ic_check_circle) {
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

        // Ghi chú: vd MB không ghi phí VAT, giải thích khoản lệch
        ui.add(box, ui.text("Ghi chú", 14f, bold = true), 22)
        t.note?.let {
            ui.add(box, ui.text(it, 14f).apply {
                setTextIsSelectable(true)
                background = ui.rounded(ui.surface2, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }, 8)
        }
        ui.add(box, ui.button(if (t.note == null) "Thêm ghi chú" else "Sửa ghi chú", filled = false, icon = R.drawable.ic_note) {
            askNote(t) {
                changed = true
                dialog.dismiss()
            }
        }, 8)

        // Thông báo MB bị coi là trùng và gộp vào giao dịch này: xem, tách ra nếu thật ra là giao dịch khác
        if (t.dups > 0) {
            ui.add(box, ui.text("Thông báo trùng đã gộp (${t.dups})", 14f, bold = true), 22)
            ui.add(box, ui.text(
                "Các thông báo này được coi là MB gửi lại cùng giao dịch nên không tính thêm. " +
                    "Nếu thật ra là giao dịch khác (vd 2 lần chuyển cùng số tiền), bấm Tách riêng.",
                12f, ui.muted,
            ), 4)
            for (d in store.duplicates(t.id)) {
                ui.add(box, dupCard(d) {
                    changed = true
                    dialog.dismiss()
                }, 8)
            }
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

    /** Sửa ghi chú của [t] (để trống = xoá); gợi ý sẵn khi số dư lệch như bị trừ phí MB không ghi. */
    private fun askNote(t: Tx, onSaved: () -> Unit) {
        val input = ui.input("Vd: MB không ghi phí VAT 1.100đ").apply {
            setSingleLine(false)
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(Store.NOTE_MAX))
            setText(t.note.orEmpty())
            setSelection(text.length)
        }
        val wrap = ui.vbox().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val gap = t.gap
        if (gap != null && gap > 0 && !t.isIn) {
            ui.add(wrap, ui.chip("MB không ghi phí/VAT ${ui.money(gap)}", false) {
                input.setText("MB không ghi phí/VAT ${ui.money(gap)}")
                input.setSelection(input.text.length)
            }, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("Ghi chú giao dịch")
            .setView(wrap)
            .setPositiveButton("Lưu") { _, _ ->
                store.setNote(t.id, input.text.toString())
                onSaved()
            }
            .setNegativeButton("Huỷ", null)
            .apply { if (t.note != null) setNeutralButton("Xoá") { _, _ -> store.setNote(t.id, ""); onSaved() } }
            .show()
    }

    /** Thẻ 1 thông báo đã gộp vì trùng: giờ nhận, nội dung, nút tách thành giao dịch riêng (hỏi lại). */
    private fun dupCard(d: Store.Item, onSplit: () -> Unit): LinearLayout {
        val c = ui.card(radius = 14, color = ui.surface2).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        c.addView(ui.split(ui.text("Nhận lúc ${fmt(d.postedAt)}", 12f, ui.muted), ui.pill("Đã gộp", ui.warn)))
        ui.add(c, ui.text(d.text, 13f).apply { setTextIsSelectable(true) }, 6)
        ui.add(c, ui.button("Không phải trùng — tách riêng", filled = false, icon = R.drawable.ic_add) {
            AlertDialog.Builder(this)
                .setTitle("Tách thành giao dịch riêng?")
                .setMessage("Thông báo này sẽ thành 1 giao dịch riêng, tính vào thống kê. \"Kiểm tra lại dữ liệu\" sẽ không gộp lại.")
                .setPositiveButton("Tách riêng") { _, _ ->
                    if (store.splitDuplicate(d.id)) onSplit() else toast("Không tách được thông báo này")
                }
                .setNegativeButton("Huỷ", null)
                .show()
        }, 8)
        return c
    }

    /** Mọi thông báo đã gộp vì trùng (mới nhất trước), chạm "Xem giao dịch" để mở giao dịch được giữ lại. */
    private fun showDuplicates() {
        val dialog = Dialog(this)
        val box = sheetBox()
        ui.add(box, ui.text("Thông báo trùng đã gộp", 18f, bold = true), 16)
        ui.add(box, ui.text(
            "MB hay gửi lại cùng 1 giao dịch (hoặc bản bị cắt nội dung) — app gộp vào giao dịch đã có thay vì bỏ đi. " +
                "Nếu thật ra là giao dịch khác, bấm Tách riêng.",
            12f, ui.muted,
        ), 6)
        val list = store.duplicates()
        if (list.isEmpty()) ui.add(box, empty(R.drawable.ic_check_circle, "Chưa có thông báo nào bị gộp."), 16)
        var changed = false
        for (d in list.take(100)) {
            val card = dupCard(d) {
                changed = true
                dialog.dismiss()
            }
            ledger.txs.firstOrNull { it.id == d.dupOf }?.let { kept ->
                ui.add(card, ui.text(ui.iconText(R.drawable.ic_chevron_right, "Gộp vào: ${descText(kept)} · ${ui.money(kept.amount)}", ui.primary, 14), 13f, ui.primary).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(8), 0, dp(4))
                    setOnClickListener {
                        dialog.dismiss()
                        showTx(kept)
                    }
                }, 2)
            }
            ui.add(box, card, 10)
        }
        ui.add(box, ui.button("Đóng") { dialog.dismiss() }, 18)
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

    // ------------------------------------------------------------------ khoản lớn (cọc / tiết kiệm)

    private fun planColor(p: Plan) = if (p.saving) ui.green else ui.primary

    /** Nhãn giao dịch đã gắn khoản: tiết kiệm / cọc (lần đầu của khoản cọc) / trả cọc (các lần sau). */
    private fun planLabel(t: Tx, p: Plan) = when {
        p.saving -> "Tiết kiệm"
        t.id in ledger.depositIds -> "Cọc"
        else -> "Trả cọc"
    }

    /** Thẻ Tổng quan: để dành tháng này + các khoản đang mở; khoản đã xong gom lại 1 dòng. */
    private fun plansCard(s: Ledger.MonthSum): View {
        val c = listCard()
        c.addView(ui.header("Mục tiêu & khoản cọc", "+ Thêm") { showPlanForm(null, true) }.apply { setPadding(0, 0, 0, dp(4)) })
        // Giao dịch có thể là lần trả nốt khoản cọc → chạm để xác nhận
        for ((txId, p) in ledger.suggestions) {
            val t = ledger.txs.firstOrNull { it.id == txId } ?: continue
            ui.add(c, ui.callout(
                R.drawable.ic_warning,
                "${ui.money(t.amount - t.fee)} ngày ${dateFmt.format(t.date)} đúng bằng số còn phải trả của \"${p.name}\" — chạm để xác nhận",
                ui.warn,
            ).apply { setOnClickListener { showTx(t) } }, 8)
        }
        if (s.saved > 0) {
            ui.add(c, ui.text(
                ui.iconText(R.drawable.ic_wallet, "Để dành tháng ${month.monthValue}: ${ui.money(s.saved)}", ui.green, 16), 13f, ui.green,
            ), 4)
        }
        val open = ledger.plans.filter { !it.done }
        if (open.isEmpty()) {
            ui.add(c, ui.text(
                "Tạo mục tiêu tiết kiệm, hoặc mở 1 giao dịch tiền ra → \"Đánh dấu là cọc\" và nhập tổng giá sản phẩm để theo dõi số còn phải trả.",
                13f, ui.muted,
            ).apply { setPadding(0, 0, 0, dp(10)) }, 8)
        }
        for (p in open.take(3)) ui.add(c, planRow(p), 4)
        val more = ledger.plans.size - open.take(3).size
        if (more > 0) {
            val done = ledger.plans.count { it.done }
            ui.add(c, ui.text(
                "Xem tất cả (${ledger.plans.size} khoản${if (done > 0) ", $done đã xong" else ""})", 13f, ui.primary, bold = true,
            ).apply {
                setPadding(dp(4), dp(8), dp(4), dp(10))
                setOnClickListener { showPlanList("Mục tiêu & khoản cọc", ledger.plans) }
            }, 4)
        }
        return c
    }

    private fun leftText(p: Plan, left: Long) = when {
        left > 0 -> (if (p.saving) "còn thiếu " else "còn phải trả ") + ui.money(left)
        p.saving -> "Đã đủ"
        else -> "Đã trả đủ"
    }

    /** 1 khoản: tên, %, thanh tiến độ, đã có / tổng và còn thiếu (còn phải trả); chạm để xem chi tiết. */
    private fun planRow(p: Plan): View {
        val paid = ledger.paid(p)
        val color = planColor(p)
        val pct = if (p.target > 0) paid * 100 / p.target else 100
        val left = p.target - paid
        val body = ui.vbox().apply {
            addView(ui.split(
                ui.text(p.name, 15f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                ui.text("$pct%", 13f, color, bold = true),
            ))
            ui.add(this, ui.bar((paid.toFloat() / p.target.coerceAtLeast(1)).coerceIn(0f, 1f), color), 6)
            ui.add(this, ui.text(
                "${ui.short(paid)} / ${ui.short(p.target)} · " + leftText(p, left),
                12f, ui.muted,
            ), 6)
        }
        val icon = if (p.saving) R.drawable.ic_wallet else R.drawable.ic_calendar
        return ui.listRow(ui.iconCircle(icon, color, 36), body, null).apply {
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = ui.pressable(ui.surface, 12)
            setOnClickListener { showPlan(p) }
        }
    }

    /** Chi tiết khoản: tiến độ, giao dịch đã gắn, sửa / hoàn tất / xoá. */
    private fun showPlan(p: Plan) {
        val dialog = Dialog(this)
        val box = sheetBox()
        val paid = ledger.paid(p)
        val color = planColor(p)
        ui.add(box, ui.text(p.name, 20f, bold = true), 16)
        ui.add(box, LinearLayout(this).apply {
            addView(ui.pill(if (p.saving) "Mục tiêu tiết kiệm" else "Khoản cọc", color))
            if (p.done) addView(ui.pill("Đã xong", ui.muted), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
        }, 8)
        ui.add(box, ui.split(ui.amount(paid, 26f, color), ui.text("/ ${ui.money(p.target)}", 14f, ui.muted)), 16)
        ui.add(box, ui.bar((paid.toFloat() / p.target.coerceAtLeast(1)).coerceIn(0f, 1f), color), 8)
        val left = p.target - paid
        ui.add(box, ui.text(leftText(p, left).replaceFirstChar { it.uppercase() }, 13f, if (left > 0 && !p.saving) ui.warn else ui.muted), 8)

        val txs = ledger.txsOf(p)
        ui.add(box, ui.text(if (p.saving) "Các lần chuyển tiết kiệm" else "Cọc & các lần trả", 14f, bold = true), 20)
        if (txs.isEmpty()) {
            ui.add(box, ui.text(
                "Chưa gắn giao dịch nào. Mở 1 giao dịch tiền ra → \"${if (p.saving) "Chuyển tiết kiệm" else "Trả tiếp cho khoản cọc"}\".",
                13f, ui.muted,
            ), 6)
        } else {
            txList(ui.vbox().also { ui.add(box, it, 4) }, txs, timeFmt)
        }

        ui.add(box, ui.button("Đóng") { dialog.dismiss() }, 22)
        ui.add(box, ui.button("Sửa", filled = false, icon = R.drawable.ic_edit) {
            dialog.dismiss()
            showPlanForm(p, p.saving)
        }, 8)
        ui.add(box, ui.button(if (p.done) "Mở lại" else "Đánh dấu hoàn tất", filled = false, icon = R.drawable.ic_check_circle) {
            store.setPlanDone(p.id, !p.done)
            dialog.dismiss()
            reload()
        }, 8)
        ui.add(box, ui.button("Xoá khoản", danger = true, icon = R.drawable.ic_delete) {
            AlertDialog.Builder(this)
                .setTitle("Xoá khoản \"${p.name}\"?")
                .setMessage("Các giao dịch đã gắn vẫn giữ nguyên, chỉ gỡ khỏi khoản này.")
                .setPositiveButton("Xoá") { _, _ ->
                    store.deletePlan(p.id)
                    dialog.dismiss()
                    reload()
                }
                .setNegativeButton("Huỷ", null)
                .show()
        }, 8)
        showSheet(dialog, box)
    }

    /** Danh sách khoản dạng bottom sheet; chạm để xem chi tiết. */
    private fun showPlanList(title: String, plans: List<Plan>) {
        val dialog = Dialog(this)
        val box = sheetBox()
        ui.add(box, ui.text(title, 20f, bold = true), 16)
        for (p in plans) {
            ui.add(box, planRow(p).apply {
                setOnClickListener {
                    dialog.dismiss()
                    showPlan(p)
                }
            }, 4)
        }
        ui.add(box, ui.button("Đóng", filled = false) { dialog.dismiss() }, 16)
        showSheet(dialog, box)
    }

    /** Chọn khoản đang mở cùng loại để gắn giao dịch; mục tiêu tiết kiệm có thể tạo mới ngay (khoản cọc tạo bằng "Đánh dấu là cọc"). */
    private fun showPlanPicker(saving: Boolean, onPick: (Plan) -> Unit) {
        val dialog = Dialog(this)
        val box = sheetBox()
        ui.add(box, ui.text(if (saving) "Chuyển tiết kiệm cho mục tiêu" else "Trả tiếp cho khoản cọc", 20f, bold = true), 16)
        val open = store.plans().filter { !it.done && it.saving == saving }
        if (open.isEmpty()) ui.add(box, ui.text(if (saving) "Chưa có mục tiêu nào." else "Chưa có khoản cọc nào đang mở.", 13f, ui.muted), 8)
        for (p in open) {
            ui.add(box, planRow(p).apply {
                setOnClickListener {
                    dialog.dismiss()
                    onPick(p)
                }
            }, 4)
        }
        if (saving) {
            ui.add(box, ui.button("Tạo mục tiêu mới", filled = false, icon = R.drawable.ic_add) {
                dialog.dismiss()
                showPlanForm(null, true, onSaved = onPick)
            }, 16)
        }
        showSheet(dialog, box)
    }

    /**
     * Form tạo ([p] null) hoặc sửa khoản: loại (khoá khi tạo từ giao dịch), tên, số tiền.
     * [deposit] > 0 = đánh dấu cọc từ giao dịch có số tiền đó; tổng giá phải ≥ tiền cọc.
     */
    private fun showPlanForm(p: Plan?, saving: Boolean, deposit: Long = 0, onSaved: ((Plan) -> Unit)? = null) {
        val dialog = Dialog(this)
        val box = sheetBox()
        val title = when {
            p != null -> "Sửa khoản"
            deposit > 0 -> "Đánh dấu là cọc"
            else -> "Khoản mới"
        }
        ui.add(box, ui.text(title, 20f, bold = true), 16)
        if (deposit > 0) {
            ui.add(box, ui.text(
                "Tiền cọc ${ui.money(deposit)}. Nhập tổng giá trị sản phẩm, app sẽ theo dõi số còn phải trả " +
                    "và tự hỏi khi có giao dịch đúng bằng số đó.",
                12f, ui.muted,
            ), 4)
        }
        var isSaving = p?.saving ?: saving
        val amount = moneyInput("")
        fun hint() {
            amount.hint = if (isSaving) "Số tiền mục tiêu (₫)" else "Tổng giá trị sản phẩm (₫)"
        }
        if (onSaved == null) {
            val kindRow = FrameLayout(this)
            fun fillKind() {
                kindRow.removeAllViews()
                kindRow.addView(ui.segmented(listOf("Tiết kiệm", "Khoản cọc"), if (isSaving) 0 else 1) {
                    isSaving = it == 0
                    fillKind()
                })
                hint()
            }
            fillKind()
            ui.add(box, kindRow, 14)
        } else {
            hint()
        }
        val name = ui.input("Tên, vd: Laptop, Xe máy").apply { setText(p?.name.orEmpty()) }
        ui.add(box, name, 12)
        p?.let { amount.setText(it.target.toString()) }
        ui.add(box, amount, 12)
        ui.add(box, ui.button("Lưu") {
            val n = name.text.toString().trim().take(60)
            val value = amount.text.filter { it.isDigit() }.toString().toLongOrNull() ?: 0L
            if (n.isEmpty()) {
                toast("Nhập tên khoản")
                return@button
            }
            if (value <= 0) {
                toast("Nhập số tiền")
                return@button
            }
            if (!isSaving && value < deposit) {
                toast("Tổng giá trị phải lớn hơn hoặc bằng tiền cọc ${ui.money(deposit)}")
                return@button
            }
            val result = if (p == null) {
                store.addPlan(n, isSaving, value)
            } else {
                store.updatePlan(p.id, n, isSaving, value)
                p.copy(name = n, saving = isSaving, target = value)
            }
            dialog.dismiss()
            onSaved?.invoke(result)
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

        val sources = listOf(Source.ALL, Source.BANK, Source.CASH)
        ui.add(box, ui.segmented(listOf("Mọi nguồn", "Ngân hàng", "Tiền mặt"), sources.indexOf(source)) {
            source = sources[it]
            renderTransactions()
        }, 12)
        val filters = listOf(Filter.ALL, Filter.IN, Filter.OUT)
        ui.add(box, ui.segmented(listOf("Tất cả", "Tiền vào", "Tiền ra"), filters.indexOf(filter)) {
            filter = filters[it]
            renderTransactions()
        }, 8)

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

        if (source == Source.CASH) {
            val c = ui.card()
            c.addView(ui.listRow(
                ui.iconCircle(R.drawable.ic_wallet, ui.green, 36),
                ui.text("Tiền mặt hiện có", 14f, ui.muted),
                ui.amount(ledger.cashBalance, 17f),
            ))
            ui.add(box, c, 12)
        }

        val list = ledger.inMonth(month).filter {
            val srcOk = when (source) {
                Source.ALL -> true
                Source.BANK -> !it.manual
                Source.CASH -> it.manual
            }
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
            srcOk && dirOk && tagOk
        }
        if (list.isEmpty()) {
            val msg = if (source == Source.CASH) "Chưa có giao dịch tiền mặt phù hợp trong tháng này. Bấm + để thêm."
            else "Không có giao dịch phù hợp trong tháng này."
            ui.add(box, ui.card().apply { addView(empty(R.drawable.ic_list, msg)) })
        } else {
            val (ins, outs) = list.partition { it.isIn }
            ui.add(box, ui.text(
                "${list.size} giao dịch · vào ${ui.short(ins.sumOf { it.amount })} · ra ${ui.short(outs.sumOf { it.amount })}",
                12f, ui.muted,
            ).apply { setPadding(dp(4), 0, dp(4), 0) }, 14)
        }
        dayGroups(box, list)

        val unparsed = if (source == Source.CASH) emptyList()
        else ledger.unparsed.filter { Ledger.monthOf(it.postedAt) == month }
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
        tagSec.addView(ui.text("Chạm 1 dòng để xem chi tiết, đổi tên, đổi màu hoặc xoá tag.", 12f, ui.muted))
        tagsBox = ui.vbox().also { ui.add(tagSec, it, 6) }
        ui.add(tagSec, ui.button("Thêm tag", filled = false, icon = R.drawable.ic_add) {
            askTagName("Tag mới", "") { name ->
                if (store.addTag(name) == null) toast("Đã có tag \"$name\"")
                renderTagSettings()
                reload()
            }
        }, 10)
        renderTagSettings()

        val notif = section("Thông báo", R.drawable.ic_bell)
        pkgText = ui.text("", 12f, ui.muted)
        notif.addView(navRow(R.drawable.ic_bank, "App ngân hàng", pkgText) { showSources() })
        notif.addView(ui.divider(), LinearLayout.LayoutParams(MATCH, 1).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
            marginStart = dp(52)
        })
        logText = ui.text("", 12f, ui.muted)
        notif.addView(navRow(R.drawable.ic_list, "Nhật ký thông báo", logText) { showNotifLog() })
        notif.addView(ui.divider(), LinearLayout.LayoutParams(MATCH, 1).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
            marginStart = dp(52)
        })
        dupText = ui.text("", 12f, ui.muted)
        notif.addView(navRow(R.drawable.ic_sync, "Thông báo trùng đã gộp", dupText) { showDuplicates() })

        val upd = section("Cập nhật app", R.drawable.ic_download)
        updateText = ui.text("", 14f).also { upd.addView(it) }
        updateButton = ui.button("Kiểm tra cập nhật", icon = R.drawable.ic_sync) { onUpdateButton() }.also { ui.add(upd, it, 12) }
        tokenButton = ui.button("", filled = false, icon = R.drawable.ic_shield) { askToken() }.also { ui.add(upd, it, 8) }
        ui.add(upd, ui.text(
            "Mỗi lần push code, GitHub Actions build APK và đăng bản phát hành. App tự kiểm tra khi mở (tối đa 3 giờ/lần), " +
                "tải về và cài đè — dữ liệu giữ nguyên. Repo riêng tư thì cần nhập token chỉ đọc.",
            12f, ui.muted,
        ), 10)
        renderUpdate()

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
            "Quét lại thanh thông báo, đọc lại mọi thông báo MB đã lưu, gộp giao dịch bị lưu trùng (kể cả bản bị cắt nội dung), " +
                "tính lại phí/VAT theo số dư và đánh dấu giao dịch lệch số dư.",
            12f, ui.muted,
        ), 6)
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

        pkgText.text = "Đang theo dõi ${label(settings.mbPackage)} · ${settings.mbPackage}"
        val dups = ledger.txs.sumOf { it.dups }
        dupText.text = if (dups == 0) "Chưa có thông báo nào bị gộp" else "$dups thông báo · chạm để xem hoặc tách riêng"
        logText.text = store.recent(1).firstOrNull()?.let { "Mới nhất lúc ${fmt(it.postedAt)} · chạm để xem theo ngày" }
            ?: "Chưa có thông báo nào từ app đang theo dõi"

        scanText.text = SpannableStringBuilder("Đã lưu ${ledger.txs.size} giao dịch trên máy").apply {
            settings.lastError?.let { append("\n").append(ui.iconText(R.drawable.ic_warning, "$it (${fmt(settings.lastErrorAt)})", ui.warn)) }
        }
    }

    // ------------------------------------------------------------------ hành động

    /** Dòng mở màn chi tiết: icon, tiêu đề, dòng phụ [sub], mũi tên. */
    private fun navRow(res: Int, title: String, sub: TextView, onClick: () -> Unit): View {
        val body = ui.vbox().apply {
            addView(ui.text(title, 15f, bold = true))
            ui.add(this, sub, 2)
        }
        return ui.listRow(ui.iconCircle(res, ui.primary, 36), body, ui.icon(R.drawable.ic_chevron_right, ui.muted, 20)).apply {
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = ui.pressable(ui.surface, 12)
            setOnClickListener { onClick() }
        }
    }

    /** Bảng tag: số giao dịch (mọi thời gian), chi và thu tháng đang xem (không tính tiền chuyển tiết kiệm). */
    private fun renderTagSettings() {
        tagsBox.removeAllViews()
        val all = ledger.txs.filter { !it.saving }
        val inMonth = ledger.spending(month)
        fun cells(name: View, count: String, out: String, inc: String, size: Float, color: Int, bold: Boolean) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
                for ((s, w) in listOf(count to 40, out to 68, inc to 68)) {
                    addView(ui.text(s, size, color, bold).apply {
                        gravity = Gravity.END
                        fontFeatureSettings = "tnum"
                        maxLines = 1
                    }, LinearLayout.LayoutParams(dp(w), WRAP))
                }
            }
        fun nameCell(name: String, color: Int?, bold: Boolean = false) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply { background = ui.oval(color ?: ui.line) }, LinearLayout.LayoutParams(dp(10), dp(10)))
            addView(ui.text(name, 14f, bold = bold).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }
        fun money(list: List<Tx>, isIn: Boolean) = list.filter { it.isIn == isIn }.sumOf { it.amount }.let { if (it == 0L) "—" else ui.short(it) }
        fun line() = tagsBox.addView(ui.divider(), LinearLayout.LayoutParams(MATCH, 1))

        val m = month.monthValue
        tagsBox.addView(cells(ui.text("Tag", 12f, ui.muted, bold = true), "GD", "Chi T$m", "Thu T$m", 12f, ui.muted, true))
        line()
        val rows = store.tags().map { g -> Triple<Tag?, List<Tx>, List<Tx>>(g, all.filter { it.tag?.id == g.id }, inMonth.filter { it.tag?.id == g.id }) } +
            Triple<Tag?, List<Tx>, List<Tx>>(null, all.filter { it.tag == null }, inMonth.filter { it.tag == null })
        for ((g, ever, now) in rows) {
            val row = cells(
                nameCell(g?.name ?: "Chưa gắn tag", g?.color), "${ever.size}", money(now, false), money(now, true), 13f, ui.fg, false,
            ).apply {
                background = ui.pressable(ui.surface, 8)
                setOnClickListener {
                    if (g != null) showTag(g) else openTagFilter(UNTAGGED)
                }
            }
            tagsBox.addView(row)
            line()
        }
        tagsBox.addView(cells(
            ui.text("Tổng", 14f, bold = true), "${all.size}", money(inMonth, false), money(inMonth, true), 13f, ui.fg, true,
        ))
    }

    /** Sang tab Giao dịch tháng đang xem, lọc theo tag [id] ([UNTAGGED] = chưa gắn). */
    private fun openTagFilter(id: Long) {
        tagFilter = id
        filter = Filter.ALL
        source = Source.ALL
        renderTransactions()
        selectTab(TX_TAB)
    }

    /** Chi tiết tag: thống kê, đổi màu, đổi tên, xem giao dịch, xoá. */
    private fun showTag(g: Tag) {
        val dialog = Dialog(this)
        val box = sheetBox()
        var changed = false
        var color = g.color
        val txs = ledger.txs.filter { it.tag?.id == g.id && !it.saving }
        val now = ledger.spending(month).filter { it.tag?.id == g.id }

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun fillHead() {
            head.removeAllViews()
            head.addView(ui.letterCircle(g.name, color, 48))
            head.addView(ui.text(g.name, 20f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        }
        fillHead()
        ui.add(box, head, 16)

        val m = month.monthValue
        val info = listOf(
            "Số giao dịch" to "${txs.size}",
            "Chi tháng $m" to ui.money(now.filter { !it.isIn }.sumOf { it.amount }),
            "Thu tháng $m" to ui.money(now.filter { it.isIn }.sumOf { it.amount }),
            "Tổng chi từ trước tới nay" to ui.money(txs.filter { !it.isIn }.sumOf { it.amount }),
            "Tổng thu từ trước tới nay" to ui.money(txs.filter { it.isIn }.sumOf { it.amount }),
            "Giao dịch gần nhất" to (txs.firstOrNull()?.let { fmt(it.time) } ?: "—"),
        )
        val card = ui.card(radius = 16, color = ui.surface2).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        info.forEachIndexed { i, (label, value) ->
            if (i > 0) card.addView(ui.divider(), LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = dp(11) })
            card.addView(ui.split(
                ui.text(label, 13f, ui.muted),
                ui.text(value, 14f, bold = true).apply { fontFeatureSettings = "tnum" },
            ).apply { setPadding(0, if (i > 0) dp(11) else 0, 0, 0) })
        }
        ui.add(box, card, 16)

        ui.add(box, ui.text("Màu", 14f, bold = true), 20)
        val swatches = ui.flow().also { ui.add(box, it, 8) }
        fun fillSwatches() {
            swatches.removeAllViews()
            for (c in Store.TAG_COLORS) {
                val sw = FrameLayout(this).apply {
                    background = ui.oval(c)
                    if (c == color) addView(ui.icon(R.drawable.ic_check_circle, Color.WHITE, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                    setOnClickListener {
                        color = c
                        store.recolorTag(g.id, c)
                        changed = true
                        fillSwatches()
                        fillHead()
                    }
                }
                swatches.addView(sw, android.view.ViewGroup.LayoutParams(dp(34), dp(34)))
            }
        }
        fillSwatches()

        ui.add(box, ui.button("Xem giao dịch tháng $m", icon = R.drawable.ic_list) {
            dialog.dismiss()
            openTagFilter(g.id)
        }, 22)
        ui.add(box, ui.button("Đổi tên", filled = false, icon = R.drawable.ic_edit) {
            askTagName("Đổi tên tag", g.name) { name ->
                if (!store.renameTag(g.id, name)) toast("Đã có tag \"$name\"")
                changed = true
                dialog.dismiss()
            }
        }, 8)
        ui.add(box, ui.button("Xoá tag", danger = true, icon = R.drawable.ic_delete) {
            AlertDialog.Builder(this)
                .setTitle("Xoá tag ${g.name}?")
                .setMessage("${txs.size} giao dịch đang gắn tag này sẽ trở về \"chưa gắn tag\".")
                .setPositiveButton("Xoá") { _, _ ->
                    store.deleteTag(g.id)
                    changed = true
                    dialog.dismiss()
                }
                .setNegativeButton("Huỷ", null)
                .show()
        }, 8)
        dialog.setOnDismissListener {
            if (changed) {
                renderTagSettings()
                reload()
            }
        }
        showSheet(dialog, box)
    }

    /** Chọn app ngân hàng trong các app vừa có thông báo (với app khác chỉ lưu tên gói + số lượng). */
    private fun showSources() {
        val dialog = Dialog(this)
        val box = sheetBox()
        ui.add(box, ui.text("App ngân hàng", 20f, bold = true), 16)
        ui.add(box, ui.text(
            "App vừa có thông báo — chạm để chọn làm app MB Bank. Với app khác chỉ lưu tên app và số lượng, không lưu nội dung.",
            12f, ui.muted,
        ), 4)
        val sources = store.sources(12)
        if (sources.isEmpty()) ui.add(box, ui.text("Chưa thấy thông báo nào. Hãy cấp quyền ở mục Quyền & chạy nền.", 13f, ui.muted), 12)
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
                setOnClickListener { choosePackage(s.pkg) { dialog.dismiss() } }
            }
            ui.add(box, v, 6)
        }
        ui.add(box, ui.button("Đóng", filled = false) { dialog.dismiss() }, 16)
        showSheet(dialog, box)
    }

    /**
     * Nhật ký thông báo toàn màn hình: thanh tiêu đề + dải 14 ngày (kèm số thông báo) cố định ở trên,
     * danh sách của ngày đang chọn cuộn bên dưới; nút lịch để chọn ngày xa hơn.
     */
    private fun showNotifLog() {
        val dialog = Dialog(
            this,
            if (ui.night) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar,
        )
        val today = LocalDate.now(Ledger.VN)
        var day = today
        val zone = Ledger.VN
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        // Số thông báo mỗi ngày trong dải, đọc 1 lần
        val stripDays = (13 downTo 0).map { today.minusDays(it.toLong()) }
        val counts = store.notifications(start(stripDays.first()), start(today.plusDays(1)))
            .groupingBy { Instant.ofEpochMilli(it.postedAt).atZone(zone).toLocalDate() }.eachCount()

        val root = ui.vbox().apply {
            setBackgroundColor(ui.bg)
            fitsSystemWindows = true
        }
        lateinit var select: (LocalDate) -> Unit
        fun circleButton(res: Int, onClick: () -> Unit) = ui.iconCircle(res, ui.fg, 40, fill = ui.surface).apply {
            background = ui.pressable(ui.surface, 20, stroke = ui.line)
            setOnClickListener { onClick() }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            addView(circleButton(R.drawable.ic_chevron_left) { dialog.dismiss() })
            addView(ui.text("Nhật ký thông báo", 20f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
            addView(circleButton(R.drawable.ic_calendar) {
                DatePickerDialog(this@MainActivity, { _, y, mo, d ->
                    select(LocalDate.of(y, mo + 1, d).let { if (it > today) today else it })
                }, day.year, day.monthValue - 1, day.dayOfMonth).show()
            })
        }
        root.addView(top)

        val stripRow = LinearLayout(this).apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(stripRow)
        }
        root.addView(strip)
        val summary = ui.text("", 12f, ui.muted).apply { setPadding(dp(20), dp(10), dp(20), dp(6)) }
        root.addView(summary)
        root.addView(ui.divider(), LinearLayout.LayoutParams(MATCH, 1))
        val list = ui.vbox().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val weekdays = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
        fun fillStrip() {
            stripRow.removeAllViews()
            for (d in stripDays) {
                val on = d == day
                val fg = if (on) ui.onPrimary else ui.fg
                val n = counts[d] ?: 0
                val cell = ui.vbox().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(8), 0, dp(8))
                    background = if (on) ui.rounded(ui.primary, 14) else ui.pressable(ui.surface, 14, stroke = ui.line)
                    addView(ui.text(if (d == today) "Nay" else weekdays[d.dayOfWeek.value - 1], 11f, if (on) fg else ui.muted))
                    addView(ui.text("${d.dayOfMonth}", 17f, fg, bold = true).apply { setPadding(0, dp(2), 0, dp(2)) })
                    addView(ui.text(if (n > 0) "$n" else "·", 11f, if (on) fg else if (n > 0) ui.primary else ui.muted, bold = n > 0))
                    setOnClickListener { select(d) }
                }
                stripRow.addView(cell, LinearLayout.LayoutParams(dp(48), WRAP).apply { if (stripRow.childCount > 0) marginStart = dp(6) })
            }
        }

        fun fillList() {
            val items = store.notifications(start(day), start(day.plusDays(1)))
            val parsed = items.count { !it.deleted && it.dupOf == null && Parser.parse(it.text) != null }
            val dayLabel = dayFmt.format(day).replaceFirstChar { it.uppercase() }
            summary.text = if (items.isEmpty()) dayLabel else "$dayLabel · ${items.size} thông báo · $parsed giao dịch"
            list.removeAllViews()
            if (items.isEmpty()) {
                ui.add(list, empty(R.drawable.ic_bell, "Không có thông báo nào trong ngày này."), 24)
            }
            items.forEachIndexed { i, item ->
                val (state, color) = when {
                    item.deleted -> "Đã xoá" to ui.muted
                    item.dupOf != null -> "Trùng · đã gộp" to ui.warn
                    Parser.parse(item.text) != null -> "Giao dịch" to ui.green
                    Parser.looksLikeTransaction(item.text) -> "Chưa đọc được" to ui.warn
                    else -> "Không phải giao dịch" to ui.muted
                }
                val c = ui.card(radius = 16).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
                c.addView(ui.split(ui.text(clockFmt.format(Instant.ofEpochMilli(item.postedAt)), 12f, ui.muted), ui.pill(state, color)))
                ui.add(c, ui.text(item.text, 13f).apply { setTextIsSelectable(true) }, 8)
                if (item.dupOf != null) {
                    ui.add(c, ui.text("Xem / tách riêng", 13f, ui.primary, bold = true).apply {
                        setPadding(0, dp(4), 0, dp(2))
                        setOnClickListener {
                            dialog.dismiss()
                            showDuplicates()
                        }
                    }, 6)
                }
                ui.add(list, c, if (i > 0) 8 else 4)
            }
            scroll.scrollTo(0, 0)
        }

        select = { d ->
            day = d
            fillStrip()
            fillList()
        }
        select(today)

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(ui.bg))
            setLayout(MATCH, MATCH)
            statusBarColor = ui.bg
            navigationBarColor = ui.bg
            if (!ui.night) {
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        // Hôm nay nằm cuối dải → cuộn sang phải
        strip.post { strip.fullScroll(View.FOCUS_RIGHT) }
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
                    append(if (r.duplicates > 0) ", gộp ${r.duplicates} bản trùng" else ", không có bản trùng")
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

    private fun choosePackage(pkg: String, onChosen: () -> Unit = {}) {
        if (pkg == settings.mbPackage) return
        AlertDialog.Builder(this)
            .setTitle("Theo dõi ${label(pkg)}?")
            .setMessage(
                "Từ giờ app sẽ lưu (trên máy) toàn bộ nội dung thông báo của:\n$pkg\n\n" +
                    "Chỉ chọn app ngân hàng MB."
            )
            .setPositiveButton("Chọn") { _, _ ->
                settings.mbPackage = pkg
                onChosen()
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

    // ------------------------------------------------------------------ cập nhật app

    /** Đang tải/cài bản cập nhật (chặn bấm lại). */
    private var updating = false

    private fun renderUpdate() {
        if (updating) return
        val cur = Updater.currentName(this)
        val r = Updater.available
        val err = Updater.lastError(this)
        val hasToken = Updater.hasToken(this)
        updateText.text = when {
            r != null -> SpannableStringBuilder(ui.iconText(R.drawable.ic_download, "Có bản mới ${r.name} (đang dùng $cur)", ui.primary, 18)).apply {
                if (r.notes.isNotEmpty()) append("\n").append(r.notes.take(300))
            }
            err != null -> ui.iconText(R.drawable.ic_warning, "Bản $cur · $err", ui.warn, 18)
            Updater.lastCheck(this) > 0 -> ui.iconText(
                R.drawable.ic_check_circle, "Bản $cur · đã là bản mới nhất (kiểm tra lúc ${fmt(Updater.lastCheck(this))})", ui.green, 18,
            )
            else -> ui.iconText(R.drawable.ic_info, "Bản $cur", ui.muted, 18)
        }
        updateButton.text = if (r != null) ui.iconText(R.drawable.ic_download, "Tải và cài bản ${r.name}", ui.onPrimary, 18)
        else ui.iconText(R.drawable.ic_sync, "Kiểm tra cập nhật", ui.onPrimary, 18)
        tokenButton.text = ui.iconText(R.drawable.ic_shield, if (hasToken) "Đổi / xoá GitHub token" else "GitHub token (chỉ khi repo riêng tư)", ui.primary, 18)
    }

    private fun onUpdateButton() {
        if (updating) return
        val r = Updater.available
        if (r == null) checkUpdate(manual = true) else startUpdate(r)
    }

    /** Kiểm tra bản mới ở luồng nền; tự kiểm tra ([manual] false) chỉ hỏi cập nhật 1 lần cho mỗi bản. */
    private fun checkUpdate(manual: Boolean) {
        val app = applicationContext
        if (manual) updateText.text = "Đang kiểm tra…"
        thread {
            val res = runCatching { Updater.check(app) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                renderUpdate()
                val r = res.getOrNull()
                when {
                    res.isFailure -> if (manual) toast(Updater.lastError(this) ?: "Kiểm tra cập nhật thất bại")
                    r == null -> if (manual) toast("Đang dùng bản mới nhất")
                    manual || r.code != Updater.seenCode(this) -> {
                        Updater.markSeen(this, r.code)
                        AlertDialog.Builder(this)
                            .setTitle("Có bản cập nhật ${r.name}")
                            .setMessage(r.notes.ifEmpty { "Bản mới đã được build từ code mới nhất." }.take(600) + "\n\nDữ liệu trên máy giữ nguyên.")
                            .setPositiveButton("Cập nhật") { _, _ -> startUpdate(r) }
                            .setNegativeButton("Để sau", null)
                            .show()
                    }
                }
            }
        }
    }

    /** Tải APK rồi cài đè; lần đầu cần cho phép app "cài ứng dụng không rõ nguồn gốc". */
    private fun startUpdate(r: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Cho phép tự cài bản cập nhật")
                .setMessage("Bật \"Cho phép từ nguồn này\" cho Thu chi MB, quay lại app rồi bấm cập nhật lần nữa.")
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Huỷ", null)
                .show()
            return
        }
        updating = true
        updateText.text = "Đang tải bản ${r.name}…"
        val app = applicationContext
        thread {
            val res = runCatching {
                val apk = Updater.download(app, r) { pct -> runOnUiThread { updateText.text = "Đang tải bản ${r.name}… $pct%" } }
                runOnUiThread { updateText.text = "Đang cài bản ${r.name}…" }
                Updater.install(app, apk)
            }
            runOnUiThread {
                updating = false
                res.exceptionOrNull()?.let {
                    val msg = if (it is Exception) Updater.describe(it) else it.toString()
                    Updater.setError(app, msg)
                    toast(msg)
                }
                if (!isDestroyed) renderUpdate()
            }
        }
    }

    /** Nhập token GitHub chỉ đọc (fine-grained, chỉ repo mb-finance, Contents: Read-only). */
    private fun askToken() {
        val input = ui.input("github_pat_…").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val wrap = ui.vbox().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(ui.text(
                "Chỉ cần khi repo để riêng tư. GitHub → Settings → Developer settings → Fine-grained tokens → Generate: " +
                    "chỉ chọn repo mb-finance, quyền Contents = Read-only. Token chỉ lưu trên máy (mã hoá) và chỉ gửi tới api.github.com.",
                13f, ui.muted,
            ))
            ui.add(this, input, 10)
        }
        AlertDialog.Builder(this)
            .setTitle("GitHub token")
            .setView(wrap)
            .setPositiveButton("Lưu") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                Updater.setToken(this, t)
                checkUpdate(manual = true)
            }
            .setNegativeButton("Huỷ", null)
            .apply {
                if (Updater.hasToken(this@MainActivity)) setNeutralButton("Xoá token") { _, _ ->
                    Updater.setToken(this@MainActivity, null)
                    renderUpdate()
                }
            }
            .show()
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
        /** Intent extra mở thẳng 1 module (vd chạm thông báo game). */
        const val EXTRA_MODULE = "module"
        const val MODULE_FINANCE = 0
        const val MODULE_GAME = 1

        private const val UNTAGGED = -1L
        private const val TX_TAB = 1
        private const val SETTINGS_TAB = 2
        private val QUICK_AMOUNTS = listOf(20_000L, 50_000L, 100_000L, 200_000L, 500_000L)
    }
}

package vn.mbfinance.tracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import vn.mbfinance.tracker.Ui.Companion.MATCH
import vn.mbfinance.tracker.Ui.Companion.WRAP
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/** 3 tab: Tổng quan, Giao dịch (thống kê tính ngay trên máy) và Cài đặt (quyền, server, đồng bộ). */
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
    private val tabViews = ArrayList<TextView>()

    private lateinit var permText: TextView
    private lateinit var permButton: View
    private lateinit var batteryText: TextView
    private lateinit var batteryButton: View
    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var pkgText: TextView
    private lateinit var sourcesBox: LinearLayout
    private lateinit var syncText: TextView
    private lateinit var recentBox: LinearLayout
    private var lastSourcesKey = ""
    private var lastRecentKey = ""

    private var ledger = Ledger.EMPTY
    private var ledgerKey = ""
    private var loading = false
    private var month: YearMonth = YearMonth.now(Ledger.VN)
    private var filter = Filter.ALL

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
        shell.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        shell.addView(bottomNav())
        setContentView(shell)

        buildSettings()
        renderData()
        selectTab(0)
        Sync.schedulePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
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

    // ------------------------------------------------------------------ tab Tổng quan

    private fun renderOverview() {
        val box = overviewBox
        box.removeAllViews()
        ui.add(box, monthSwitcher(), 4)
        val s = ledger.sum(month)

        val hero = ui.card(radius = 22, color = ui.primary).apply { setPadding(dp(20), dp(18), dp(20), dp(18)) }
        hero.addView(ui.text("Chênh lệch thu − chi", 13f, ui.onPrimary).apply { alpha = 0.8f })
        hero.addView(ui.text(ui.money(s.net, signed = true), 30f, ui.onPrimary, bold = true))
        ledger.latestBalance?.let {
            hero.addView(ui.text("Số dư gần nhất: ${ui.money(it)}", 13f, ui.onPrimary).apply {
                alpha = 0.85f
                setPadding(0, dp(6), 0, 0)
            })
        }
        ui.add(box, hero)

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
        val time = fmt.format(Instant.ofEpochMilli(t.time)) + if (t.timeFromText) "" else " · giờ nhận thông báo"
        left.addView(ui.text(time, 12f, ui.muted))
        val amount = ui.text(
            ui.money(if (t.isIn) t.amount else -t.amount, signed = true),
            15f, if (t.isIn) ui.green else ui.red, bold = true,
        )
        return ui.split(left, amount).apply { setPadding(0, dp(10), 0, dp(10)) }
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

        val list = ledger.inMonth(month).filter {
            when (filter) {
                Filter.ALL -> true
                Filter.IN -> it.isIn
                Filter.OUT -> !it.isIn
            }
        }
        if (list.isEmpty()) {
            ui.add(box, ui.card().apply { addView(ui.text("Không có giao dịch trong tháng này.", 14f, ui.muted)) })
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
            c.addView(ui.text("Thông báo không phải giao dịch (${unparsed.size})", 14f, bold = true))
            c.addView(ui.text("OTP, quảng cáo… hoặc mẫu mới parser chưa đọc được.", 12f, ui.muted))
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

        val srv = section("SERVER")
        urlInput = ui.input("https://mb-finance.xxxx.ts.net").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(settings.serverUrl)
        }
        srv.addView(urlInput)
        tokenInput = ui.input(if (settings.hasToken) "Token đã lưu (nhập để thay)" else "Token từ lệnh device:add").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ui.add(srv, tokenInput, 8)
        ui.add(srv, ui.row(
            ui.button("Lưu") { save() },
            ui.button("Thử kết nối", filled = false) { testConnection() },
        ), 10)

        val bank = section("APP NGÂN HÀNG")
        pkgText = ui.text("", 14f).also { bank.addView(it) }
        ui.add(bank, ui.text(
            "App vừa có thông báo (bấm để chọn làm app MB Bank). Chỉ lưu tên app, không lưu nội dung:",
            12f, ui.muted,
        ), 6)
        sourcesBox = ui.vbox().also { ui.add(bank, it, 6) }

        val sync = section("ĐỒNG BỘ")
        syncText = ui.text("", 14f).also { sync.addView(it) }
        ui.add(sync, ui.row(
            ui.button("Gửi ngay") {
                Sync.kick(this)
                toast("Đã yêu cầu gửi")
            },
            ui.button("Mở dashboard", filled = false) { openDashboard() },
        ), 10)

        val log = section("THÔNG BÁO MB ĐÃ BẮT")
        recentBox = ui.vbox().also { log.addView(it) }
    }

    private fun renderStatus() {
        val listenerOn = AndroidSettings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        permText.text = if (listenerOn) "✅ Đã cấp quyền đọc thông báo" else "❌ Chưa cấp quyền đọc thông báo"
        permButton.visibility = if (listenerOn) View.GONE else View.VISIBLE

        val pm = getSystemService(PowerManager::class.java)
        val unrestricted = pm?.isIgnoringBatteryOptimizations(packageName) == true
        batteryText.text = if (unrestricted) "✅ Được chạy nền không giới hạn" else "⚠️ Hệ thống có thể tắt app để tiết kiệm pin"
        batteryButton.visibility = if (unrestricted) View.GONE else View.VISIBLE

        val pending = store.count(Store.PENDING)
        headerStatus.text = when {
            !listenerOn -> "⚠ Chưa cấp quyền đọc thông báo — vào tab Cài đặt"
            settings.lastError != null -> "⚠ Chưa gửi được lên server — xem tab Cài đặt"
            else -> "Đang theo dõi ${label(settings.mbPackage)}" + if (pending > 0) " · $pending chờ gửi" else ""
        }
        headerStatus.setTextColor(if (!listenerOn || settings.lastError != null) ui.warn else ui.muted)

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

        val sb = StringBuilder()
        sb.append("Chờ gửi: $pending · Đã gửi: ${store.count(Store.SYNCED)}\n")
        sb.append("Gửi thành công lần cuối: ${if (settings.lastOkAt > 0) fmt(settings.lastOkAt) else "chưa có"}")
        settings.lastError?.let { sb.append("\n⚠️ $it (${fmt(settings.lastErrorAt)})") }
        syncText.text = sb.toString()

        val recent = store.recent(15)
        val rKey = recent.joinToString { "${it.id}:${it.status}" }
        if (rKey != lastRecentKey) {
            lastRecentKey = rKey
            recentBox.removeAllViews()
            if (recent.isEmpty()) recentBox.addView(ui.text("Chưa có. Thông báo từ app đang theo dõi sẽ hiện ở đây.", 13f, ui.muted))
            recent.forEachIndexed { i, it ->
                val state = when {
                    it.status == Store.PENDING -> "⏳ chờ gửi"
                    it.serverStatus == "parsed" -> "✅ đã ghi nhận"
                    it.serverStatus == "duplicate" -> "✅ đã có trên server"
                    it.serverStatus == "unparsed" -> "❔ server chưa đọc được"
                    it.serverStatus == "ignored" -> "⛔ server bỏ qua (sai package?)"
                    else -> "✅ đã gửi"
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

    private fun save() {
        val url = urlInput.text.toString().trim().trimEnd('/')
        if (!url.startsWith("https://")) {
            toast("Địa chỉ phải bắt đầu bằng https://")
            return
        }
        settings.serverUrl = url
        val tok = tokenInput.text.toString().trim()
        if (tok.isNotEmpty()) {
            settings.token = tok
            tokenInput.setText("")
            tokenInput.hint = "Token đã lưu (nhập để thay)"
        }
        if (!settings.hasToken) {
            toast("Chưa có token")
            return
        }
        toast("Đã lưu")
        Sync.schedulePeriodic(this)
        Sync.kick(this)
    }

    private fun testConnection() {
        toast("Đang thử…")
        thread {
            val err = Sync.heartbeat(applicationContext)
            runOnUiThread {
                if (err == null) {
                    toast("✅ Kết nối thành công")
                    Sync.kick(this)
                } else {
                    AlertDialog.Builder(this).setTitle("Không kết nối được").setMessage(err)
                        .setPositiveButton("OK", null).show()
                }
                render()
            }
        }
    }

    private fun choosePackage(pkg: String) {
        if (pkg == settings.mbPackage) return
        AlertDialog.Builder(this)
            .setTitle("Theo dõi ${label(pkg)}?")
            .setMessage(
                "Từ giờ app sẽ lưu và gửi lên server toàn bộ nội dung thông báo của:\n$pkg\n\n" +
                    "Chỉ chọn app ngân hàng MB. Nhớ đặt MB_PACKAGES=$pkg trong docker-compose.yml trên server."
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

    private fun openDashboard() {
        val url = settings.serverUrl
        if (url.isBlank()) {
            toast("Chưa nhập địa chỉ server")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Không mở được trình duyệt")
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
}

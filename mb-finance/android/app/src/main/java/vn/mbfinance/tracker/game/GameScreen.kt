package vn.mbfinance.tracker.game

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import vn.mbfinance.tracker.R
import vn.mbfinance.tracker.Ui
import vn.mbfinance.tracker.Ui.Companion.MATCH
import vn.mbfinance.tracker.Ui.Companion.WRAP
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** Module Game (Honkai: Star Rail): tài khoản HoYoLAB, sức mạnh khai phá, nhiệm vụ ngày/tuần, ủy thác, điểm danh. */
class GameScreen(private val act: Activity, private val ui: Ui) {

    private val store = GameStore(act)
    private val box = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(28)) }
    val root: View = ScrollView(act).apply {
        isVerticalScrollBarEnabled = false
        addView(box)
    }
    private var busy = false

    private val vn = ZoneId.of("Asia/Ho_Chi_Minh")
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(vn)
    private val stamp = DateTimeFormatter.ofPattern("HH:mm dd/MM").withZone(vn)

    /** Gọi khi chuyển sang module Game / mở lại app. */
    fun onShow() {
        render()
        if (Hoyolab.loggedIn()) {
            GameJob.ensure(act.applicationContext)
            sync()
        }
    }

    private fun sync(force: Boolean = false, forceSign: Boolean = false) {
        if (busy) return
        busy = true
        render()
        val app = act.applicationContext
        thread {
            GameSync.run(app, force, forceSign)
            act.runOnUiThread {
                busy = false
                if (!act.isDestroyed) render()
            }
        }
    }

    // ------------------------------------------------------------------ dựng giao diện

    private fun render() {
        box.removeAllViews()
        if (!Hoyolab.loggedIn()) {
            renderLogin()
            return
        }
        val role = store.role
        val note = store.note
        val now = System.currentTimeMillis()

        // Tài khoản + làm mới
        val acc = ui.card()
        val refresh = ui.iconCircle(R.drawable.ic_sync, ui.primary, 40).apply {
            alpha = if (busy) 0.4f else 1f
            setOnClickListener { sync(force = true) }
        }
        val accBody = ui.vbox().apply {
            addView(ui.text(role?.let { "${it.nickname} · Cấp ${it.level}" } ?: "Đang tìm nhân vật HSR…", 15f, bold = true))
            ui.add(this, ui.text(
                role?.let { "UID ${it.uid} · ${serverName(it.server)}" } ?: "Honkai: Star Rail",
                12f, ui.muted,
            ), 2)
            ui.add(this, ui.text(
                if (busy) "Đang cập nhật…" else if (store.noteAt > 0) "Cập nhật lúc ${stamp.format(Instant.ofEpochMilli(store.noteAt))}" else "Chưa có dữ liệu",
                12f, ui.muted,
            ), 2)
        }
        // Tự dựng hàng để giữ kích thước nút tròn (listRow ép WRAP cho phần cuối)
        acc.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.iconCircle(R.drawable.ic_game, ui.primary, 40))
            addView(accBody, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = ui.dp(12)
                marginEnd = ui.dp(8)
            })
            addView(refresh)
        })
        store.lastError?.let { ui.add(acc, ui.callout(R.drawable.ic_warning, it, ui.warn), 12) }
        ui.add(box, acc, 8)

        if (note != null) {
            ui.add(box, staminaCard(note, now))
            ui.add(box, tasksCard(note))
            if (note.expeditions.isNotEmpty()) ui.add(box, expeditionsCard(note, now))
        }
        ui.add(box, signCard())
        ui.add(box, settingsCard())
    }

    private fun renderLogin() {
        val c = ui.card()
        c.addView(ui.listRow(
            ui.iconCircle(R.drawable.ic_game, ui.primary, 44),
            ui.vbox().apply {
                addView(ui.text("Kết nối HoYoLAB", 16f, bold = true))
                ui.add(this, ui.text("Để tự điểm danh và theo dõi sức mạnh khai phá Honkai: Star Rail.", 13f, ui.muted), 2)
            },
            null,
        ))
        ui.add(c, ui.callout(
            R.drawable.ic_shield,
            "Đăng nhập bằng email/mật khẩu HoYoLAB ngay trong app. App không lưu mật khẩu; phiên đăng nhập nằm trong " +
                "trình duyệt nội bộ của app và chỉ được gửi tới máy chủ HoYoLAB. Đăng nhập bằng Google không hỗ trợ trong trình duyệt nhúng.",
            ui.primary,
        ), 14)
        ui.add(c, ui.button("Đăng nhập HoYoLAB", icon = R.drawable.ic_shield) {
            showWeb("Đăng nhập HoYoLAB", Hoyolab.LOGIN_URL) {
                if (Hoyolab.loggedIn()) {
                    store.warnedDay = null
                    GameJob.ensure(act.applicationContext)
                    sync(force = true)
                } else {
                    render()
                }
            }
        }, 14)
        ui.add(box, c, 8)
    }

    /** Thanh tiến độ trên nền gradient. */
    private fun heroBar(fraction: Float) = LinearLayout(act).apply {
        background = ui.rounded(0x40FFFFFF, 4)
        addView(View(act).apply { background = ui.rounded(ui.onHero, 4) }, LinearLayout.LayoutParams(0, ui.dp(8), fraction))
        addView(View(act), LinearLayout.LayoutParams(0, ui.dp(8), 1f - fraction))
    }

    private fun staminaCard(n: Hoyolab.Note, now: Long): View {
        val hero = ui.hero()
        val on = ui.onHero
        val cur = store.staminaAt(now) ?: n.stamina
        val full = store.fullAt ?: now
        // Đọc quá 6 phút → số đang hiện là ước tính
        val estimated = now - store.noteAt > GameStore.RECOVER_MS
        hero.addView(ui.text(ui.iconText(R.drawable.ic_clock, "Sức mạnh khai phá" + if (estimated) " (ước tính)" else "", on, 14), 13f, on).apply {
            alpha = 0.85f
        })
        ui.add(hero, ui.text("$cur / ${n.max}", 34f, on, bold = true).apply { fontFeatureSettings = "tnum" }, 6)
        ui.add(hero, heroBar((cur.toFloat() / n.max.coerceAtLeast(1)).coerceIn(0f, 1f)), 10)
        val left = full - now
        ui.add(hero, ui.text(
            if (left <= 0) "Đã đầy — vào game dùng kẻo phí"
            else "Đầy lúc ${clock.format(Instant.ofEpochMilli(full))} · còn ${duration(left)}",
            14f, on, bold = true,
        ), 10)
        ui.add(hero, ui.text("Dự trữ: ${n.reserve}${if (n.reserveFull) " (đầy)" else ""}", 12f, on).apply { alpha = 0.85f }, 4)
        return hero
    }

    private fun tasksCard(n: Hoyolab.Note): View {
        val c = ui.card()
        c.addView(ui.text("Nhiệm vụ", 16f, bold = true))
        fun line(label: String, cur: Int, max: Int, doneWhenFull: Boolean = true) {
            if (max <= 0) return
            val done = if (doneWhenFull) cur >= max else cur <= 0
            ui.add(c, ui.split(
                ui.text(label, 14f),
                ui.text("$cur / $max", 14f, if (done) ui.green else ui.fg, bold = true).apply { fontFeatureSettings = "tnum" },
            ), 12)
            val f = if (doneWhenFull) cur.toFloat() / max else 1f - cur.toFloat() / max
            ui.add(c, ui.bar(f.coerceIn(0f, 1f), if (done) ui.green else ui.primary), 6)
        }
        line("Huấn luyện hằng ngày", n.train, n.trainMax)
        line("Vũ trụ mô phỏng (điểm tuần)", n.rogue, n.rogueMax)
        // Echo of War: số lượt giảm giá còn lại trong tuần, dùng hết = xong
        line("Echo of War còn lại trong tuần", n.cocoon, n.cocoonMax, doneWhenFull = false)
        return c
    }

    private fun expeditionsCard(n: Hoyolab.Note, now: Long): View {
        val c = ui.card()
        c.addView(ui.split(
            ui.text("Ủy thác", 16f, bold = true),
            ui.text("${n.expAccepted}/${n.expTotal}", 13f, ui.muted),
        ))
        val elapsed = (now - store.noteAt) / 1000
        for (e in n.expeditions) {
            val left = (e.remainingSec - elapsed).coerceAtLeast(0)
            ui.add(c, ui.split(
                ui.text(e.name, 14f).apply { maxLines = 1 },
                if (left == 0L) ui.pill("Xong", ui.green) else ui.text("còn ${duration(left * 1000)}", 13f, ui.muted),
            ), 10)
        }
        return c
    }

    private fun signCard(): View {
        val c = ui.card()
        c.addView(ui.text("Điểm danh hằng ngày", 16f, bold = true))
        val today = GameSync.today()
        when {
            store.signDay == today -> ui.add(c, ui.callout(
                R.drawable.ic_check_circle,
                "Đã điểm danh hôm nay" + (store.signReward?.let { " · $it" } ?: ""),
                ui.green,
            ), 10)
            store.captchaDay == today -> {
                ui.add(c, ui.callout(R.drawable.ic_warning, "HoYoLAB yêu cầu xác minh — hãy mở trang điểm danh và bấm tay.", ui.warn), 10)
                ui.add(c, ui.button("Mở trang điểm danh", filled = false) {
                    showWeb("Điểm danh HSR", Hoyolab.CHECKIN_PAGE) { sync(force = true) }
                }, 8)
            }
            else -> ui.add(c, ui.callout(R.drawable.ic_info, "Chưa điểm danh hôm nay", ui.primary), 10)
        }
        if (store.signTotal > 0) ui.add(c, ui.text("Tháng này đã điểm danh ${store.signTotal} ngày", 12f, ui.muted), 8)
        if (store.signDay != today) {
            ui.add(c, ui.button(if (busy) "Đang điểm danh…" else "Điểm danh ngay", icon = R.drawable.ic_check_circle) {
                sync(forceSign = true)
            }, 10)
        }
        return c
    }

    private fun settingsCard(): View {
        val c = ui.card()
        c.addView(ui.text("Cài đặt", 16f, bold = true))

        ui.add(c, ui.text("Tự điểm danh mỗi ngày", 14f), 12)
        ui.add(c, ui.segmented(listOf("Bật", "Tắt"), if (store.autoSign) 0 else 1) {
            store.autoSign = it == 0
            render()
        }, 6)

        ui.add(c, ui.text("Nhắc sức mạnh khai phá", 14f), 16)
        val opts = listOf(0 to "Khi đầy", 30 to "Trước 30′", 60 to "Trước 1 giờ", -1 to "Tắt")
        ui.add(c, ui.segmented(opts.map { it.second }, opts.indexOfFirst { it.first == store.notifyBefore }.coerceAtLeast(0)) { i ->
            store.notifyBefore = opts[i].first
            if (opts[i].first >= 0) askNotifyPermission()
            GameAlarm.schedule(act.applicationContext, store)
            render()
        }, 6)
        if (store.notifyBefore >= 0 && !GameNotify.canPost(act)) {
            ui.add(c, ui.callout(R.drawable.ic_warning, "Chưa cho phép app gửi thông báo — chạm để cấp quyền.", ui.warn).apply {
                setOnClickListener { askNotifyPermission() }
            }, 10)
        }

        ui.add(c, ui.button("Mở HoYoLAB", filled = false, icon = R.drawable.ic_game) {
            showWeb("HoYoLAB", Hoyolab.LOGIN_URL) { sync(force = true) }
        }, 16)
        ui.add(c, ui.button("Đăng xuất HoYoLAB", danger = true) {
            AlertDialog.Builder(act)
                .setTitle("Đăng xuất HoYoLAB?")
                .setMessage("Xoá phiên đăng nhập và dữ liệu game đã lưu trên máy, dừng tự điểm danh.")
                .setPositiveButton("Đăng xuất") { _, _ -> logout() }
                .setNegativeButton("Huỷ", null)
                .show()
        }, 8)
        ui.add(c, ui.text(
            "Chỉ module Game dùng mạng, và chỉ tới máy chủ HoYoLAB. Dữ liệu tài chính không bao giờ được gửi đi.",
            12f, ui.muted,
        ), 10)
        return c
    }

    private fun askNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !GameNotify.canPost(act)) {
            act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun logout() {
        val app = act.applicationContext
        GameJob.cancel(app)
        GameAlarm.cancel(app)
        GameNotify.cancel(app, GameNotify.STAMINA)
        GameNotify.cancel(app, GameNotify.SIGN)
        store.clear()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            act.runOnUiThread { render() }
        }
    }

    // ------------------------------------------------------------------ trình duyệt nội bộ

    /**
     * Mở trang HoYoLAB toàn màn hình. Chỉ cho điều hướng HTTPS trong [Hoyolab.WEB_HOSTS]; không cầu nối JS,
     * không truy cập file. Đóng → lưu cookie rồi gọi [onClose].
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(title: String, url: String, onClose: () -> Unit) {
        val dialog = Dialog(
            act,
            if (ui.night) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar,
        )
        WebView.setWebContentsDebuggingEnabled(false)
        val status = ui.text("", 12f, ui.muted)
        val web = WebView(act).apply {
            settings.javaScriptEnabled = true // trang đăng nhập cần JS
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val host = req.url.host ?: return true
                    val ok = req.url.scheme == "https" && Hoyolab.WEB_HOSTS.any { host == it || host.endsWith(".$it") }
                    if (!ok) status.text = "Đã chặn trang ngoài HoYoLAB: $host"
                    return !ok
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (Hoyolab.loggedIn()) status.text = "Đã đăng nhập — bấm Xong để quay lại."
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true) // đăng nhập đi qua cả hoyoverse.com
        }

        val rootView = ui.vbox().apply {
            setBackgroundColor(ui.bg)
            fitsSystemWindows = true
        }
        val top = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(6))
            addView(ui.iconCircle(R.drawable.ic_chevron_left, ui.fg, 40, fill = ui.surface).apply {
                background = ui.pressable(ui.surface, 20, stroke = ui.line)
                setOnClickListener { dialog.dismiss() }
            })
            addView(ui.vbox().apply {
                addView(ui.text(title, 17f, bold = true))
                addView(status)
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(12) })
            addView(ui.text("Xong", 15f, ui.primary, bold = true).apply {
                setPadding(ui.dp(12), ui.dp(8), ui.dp(4), ui.dp(8))
                setOnClickListener { dialog.dismiss() }
            })
        }
        rootView.addView(top)
        rootView.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(rootView)
        dialog.setOnDismissListener {
            CookieManager.getInstance().flush()
            web.stopLoading()
            web.destroy()
            onClose()
        }
        dialog.setOnKeyListener { _, keyCode, e ->
            // Nút back: lùi trang trước trong WebView nếu có
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && e.action == android.view.KeyEvent.ACTION_UP && web.canGoBack()) {
                web.goBack()
                true
            } else {
                false
            }
        }
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
        web.loadUrl(url)
    }

    // ------------------------------------------------------------------ tiện ích

    private fun serverName(s: String) = when (s) {
        "prod_official_usa" -> "America"
        "prod_official_eur" -> "Europe"
        "prod_official_cht" -> "TW/HK/MO"
        else -> "Asia"
    }

    /** 3h12m / 45m */
    private fun duration(ms: Long): String {
        val m = (ms + 59_999) / 60_000
        return if (m >= 60) "${m / 60}h${(m % 60).toString().padStart(2, '0')}m" else "${m}m"
    }
}

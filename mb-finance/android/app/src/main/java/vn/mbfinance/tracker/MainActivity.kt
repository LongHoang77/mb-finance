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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Màn hình duy nhất: quyền, cấu hình server, chọn app MB, trạng thái đồng bộ, nhật ký gần nhất. */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var store: Store
    private lateinit var root: LinearLayout

    private lateinit var permText: TextView
    private lateinit var permButton: Button
    private lateinit var batteryText: TextView
    private lateinit var batteryButton: Button
    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var pkgText: TextView
    private lateinit var sourcesBox: LinearLayout
    private lateinit var syncText: TextView
    private lateinit var recentBox: LinearLayout

    private var lastSourcesKey = ""
    private var lastRecentKey = ""

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 3000)
        }
    }

    private val timeFmt = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = Store.get(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        }
        setContentView(scroll)
        build()
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

    // ------------------------------------------------------------------ dựng giao diện

    private fun build() {
        root.addView(text("Thu chi MB", 22f, bold = true))
        root.addView(text("Đọc thông báo MB Bank và gửi lên server riêng của bạn.", 14f, muted = true))

        header("1. Quyền")
        permText = text("", 15f).also { root.addView(it) }
        permButton = button("Mở cài đặt quyền đọc thông báo") {
            startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.also { root.addView(it) }
        batteryText = text("", 15f).also { root.addView(it) }
        batteryButton = button("Cho phép chạy nền không giới hạn") { requestBatteryExemption() }
            .also { root.addView(it) }
        root.addView(
            text(
                "Máy Xiaomi/Oppo/Vivo/Samsung: vào Cài đặt → Ứng dụng → Thu chi MB → bật Tự khởi chạy " +
                    "và đặt Pin = Không giới hạn, nếu không hệ thống có thể tắt app.",
                13f, muted = true
            )
        )

        header("2. Server")
        urlInput = EditText(this).apply {
            hint = "https://mb-finance.xxxx.ts.net"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(settings.serverUrl)
        }
        root.addView(urlInput)
        tokenInput = EditText(this).apply {
            hint = if (settings.hasToken) "Token đã lưu (nhập để thay)" else "Token từ lệnh device:add"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        root.addView(tokenInput)
        root.addView(row(
            button("Lưu") { save() },
            button("Thử kết nối") { testConnection() },
        ))

        header("3. App ngân hàng")
        pkgText = text("", 15f).also { root.addView(it) }
        root.addView(text("App vừa có thông báo (bấm để chọn làm app MB Bank). Chỉ lưu tên app, không lưu nội dung:", 13f, muted = true))
        sourcesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sourcesBox)

        header("4. Đồng bộ")
        syncText = text("", 15f).also { root.addView(it) }
        root.addView(row(
            button("Gửi ngay") {
                Sync.kick(this)
                toast("Đã yêu cầu gửi")
            },
            button("Mở dashboard") { openDashboard() },
        ))

        header("5. Thông báo MB đã bắt")
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recentBox)
    }

    // ------------------------------------------------------------------ cập nhật trạng thái

    private fun render() {
        val listenerOn = AndroidSettings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        permText.text = if (listenerOn) "✅ Đã cấp quyền đọc thông báo" else "❌ Chưa cấp quyền đọc thông báo"
        permButton.visibility = if (listenerOn) View.GONE else View.VISIBLE

        val pm = getSystemService(PowerManager::class.java)
        val unrestricted = pm?.isIgnoringBatteryOptimizations(packageName) == true
        batteryText.text = if (unrestricted) "✅ Được chạy nền không giới hạn" else "⚠️ Hệ thống có thể tắt app để tiết kiệm pin"
        batteryButton.visibility = if (unrestricted) View.GONE else View.VISIBLE

        pkgText.text = "Đang theo dõi: ${label(settings.mbPackage)} (${settings.mbPackage})"

        val sources = store.sources(12)
        val sKey = sources.joinToString { "${it.pkg}:${it.count}" } + settings.mbPackage
        if (sKey != lastSourcesKey) {
            lastSourcesKey = sKey
            sourcesBox.removeAllViews()
            if (sources.isEmpty()) sourcesBox.addView(text("Chưa thấy thông báo nào. Hãy cấp quyền ở mục 1.", 14f, muted = true))
            for (s in sources) {
                val chosen = s.pkg == settings.mbPackage
                val v = text(
                    "${if (chosen) "● " else ""}${label(s.pkg)}\n${s.pkg} · ${s.count} thông báo · ${timeFmt.format(Date(s.lastSeen))}",
                    14f, bold = chosen
                )
                v.setPadding(dp(8), dp(8), dp(8), dp(8))
                v.setOnClickListener { choosePackage(s.pkg) }
                sourcesBox.addView(v)
            }
        }

        val pending = store.count(Store.PENDING)
        val synced = store.count(Store.SYNCED)
        val sb = StringBuilder()
        sb.append("Chờ gửi: $pending · Đã gửi (30 ngày): $synced\n")
        sb.append("Gửi thành công lần cuối: ${if (settings.lastOkAt > 0) timeFmt.format(Date(settings.lastOkAt)) else "chưa có"}")
        settings.lastError?.let { sb.append("\n⚠️ $it (${timeFmt.format(Date(settings.lastErrorAt))})") }
        syncText.text = sb.toString()

        val recent = store.recent(15)
        val rKey = recent.joinToString { "${it.id}:${it.status}" }
        if (rKey != lastRecentKey) {
            lastRecentKey = rKey
            recentBox.removeAllViews()
            if (recent.isEmpty()) recentBox.addView(text("Chưa có. Thông báo từ app đang theo dõi sẽ hiện ở đây.", 14f, muted = true))
            for (it in recent) {
                val state = when {
                    it.status == Store.PENDING -> "⏳ chờ gửi"
                    it.serverStatus == "parsed" -> "✅ đã ghi nhận"
                    it.serverStatus == "duplicate" -> "✅ đã có trên server"
                    it.serverStatus == "unparsed" -> "❔ server chưa đọc được"
                    it.serverStatus == "ignored" -> "⛔ server bỏ qua (sai package?)"
                    else -> "✅ đã gửi"
                }
                recentBox.addView(text("${timeFmt.format(Date(it.postedAt))} · $state", 13f, muted = true).apply {
                    setPadding(0, dp(10), 0, 0)
                })
                recentBox.addView(text(it.text, 14f).apply { maxLines = 5 })
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

    // ------------------------------------------------------------------ tiện ích giao diện

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (muted) alpha = 0.7f
        setTextIsSelectable(false)
    }

    private fun header(s: String) {
        root.addView(text(s, 17f, bold = true).apply { setPadding(0, dp(24), 0, dp(6)) })
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) {
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

package vn.mbfinance.tracker

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlin.concurrent.thread

/**
 * Hệ thống gọi service này mỗi khi có thông báo mới (không cần chạy nền liên tục).
 * - Mọi app: chỉ đếm tên gói (để tìm đúng package MB Bank), KHÔNG lưu nội dung.
 * - App MB Bank đã chọn: lưu toàn bộ nội dung trên máy rồi cập nhật widget
 *   (thông báo không phải giao dịch tự xoá sau 7 ngày, xem Store.purgeNonTransactions).
 */
class MbNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            // Không bao giờ để lỗi làm hệ thống ngắt listener
            Settings(this).markError("Lỗi đọc thông báo: ${e.javaClass.simpleName}")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        Store.get(this).recordSource(pkg, System.currentTimeMillis())

        if (save(sbn)) saved(this)
    }

    /** Lưu nếu là thông báo của app MB đã chọn. false nếu bỏ qua hoặc đã có. */
    private fun save(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName ?: return false
        if (pkg != Settings(this).mbPackage) return false

        val n = sbn.notification ?: return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false

        val text = extractText(n) ?: return false
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        return Store.get(this).enqueue(pkg, title, text, sbn.postTime)
    }

    /** Đọc lại thông báo MB còn trên thanh thông báo. Trả về số bản mới lưu. */
    fun scanActive(): Int {
        var added = 0
        for (sbn in activeNotifications.orEmpty()) {
            try {
                if (save(sbn)) added++
            } catch (e: Exception) {
                Settings(this).markError("Lỗi đọc thông báo: ${e.javaClass.simpleName}")
            }
        }
        if (added > 0) saved(this)
        return added
    }

    override fun onListenerConnected() {
        instance = this
        // Thông báo đến lúc listener bị ngắt (app bị hệ thống giết) không được gọi onNotificationPosted → quét bù
        thread {
            runCatching { scanActive() }
            BalanceWidget.refresh(this)
        }
    }

    override fun onListenerDisconnected() {
        instance = null
        BalanceWidget.refresh(this)
        // Xin hệ thống kết nối lại khi bị ngắt (vd sau khi app được cập nhật)
        requestRebind(ComponentName(this, MbNotificationListener::class.java))
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** Listener đang kết nối với hệ thống; null nghĩa là dịch vụ không chạy dù có thể đã cấp quyền. */
        @Volatile
        var instance: MbNotificationListener? = null
            private set

        @Volatile
        private var lastRebindAt = 0L

        private fun saved(ctx: Context) = BalanceWidget.refresh(ctx)

        /** Đã cấp quyền "Truy cập thông báo" (chưa chắc dịch vụ đang chạy, xem [instance]). */
        fun isEnabled(ctx: Context): Boolean =
            AndroidSettings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?.contains(ctx.packageName) == true

        /**
         * Bù thông báo bị lỡ: listener đang chạy thì quét thanh thông báo, không thì ép hệ thống kết nối lại
         * (onListenerConnected sẽ tự quét). Trả về số bản mới lưu, -1 nếu listener chưa chạy.
         */
        fun catchUp(ctx: Context): Int {
            instance?.let { return it.scanActive() }
            rebind(ctx)
            return -1
        }

        /** Tắt/bật lại component để hệ thống bind lại listener (requestRebind một mình hay bị bỏ qua trên ROM tuỳ biến). */
        private fun rebind(ctx: Context) {
            val now = System.currentTimeMillis()
            if (!isEnabled(ctx) || now - lastRebindAt < 60_000) return
            lastRebindAt = now
            val cn = ComponentName(ctx, MbNotificationListener::class.java)
            try {
                val pm = ctx.packageManager
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                requestRebind(cn)
            } catch (e: Exception) {
                // Không ép được thì chờ hệ thống tự kết nối
            }
        }

        /** Lấy phần nội dung dài nhất: text ngắn thường bị cắt, bigText/textLines mới đầy đủ. */
        fun extractText(n: Notification): String? {
            val e = n.extras ?: return null
            val big = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n") { it.toString() }
            return listOfNotNull(big, text, lines)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .maxByOrNull { it.length }
        }
    }
}

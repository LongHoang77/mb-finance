package vn.mbfinance.tracker

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Hệ thống gọi service này mỗi khi có thông báo mới (không cần chạy nền liên tục).
 * - Mọi app: chỉ đếm tên gói (để tìm đúng package MB Bank), KHÔNG lưu nội dung.
 * - App MB Bank đã chọn: lưu toàn bộ nội dung vào hàng đợi rồi kích hoạt gửi
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

        if (save(sbn)) {
            Sync.kick(this)
            BalanceWidget.refresh(this)
        }
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

    /** Đọc lại thông báo MB còn trên thanh thông báo (nút "Quét lại"). Trả về số bản mới lưu. */
    fun scanActive(): Int {
        var added = 0
        for (sbn in activeNotifications.orEmpty()) {
            try {
                if (save(sbn)) added++
            } catch (e: Exception) {
                Settings(this).markError("Lỗi đọc thông báo: ${e.javaClass.simpleName}")
            }
        }
        if (added > 0) {
            Sync.kick(this)
            BalanceWidget.refresh(this)
        }
        return added
    }

    override fun onListenerConnected() {
        instance = this
        Sync.schedulePeriodic(this)
    }

    override fun onListenerDisconnected() {
        instance = null
        // Xin hệ thống kết nối lại khi bị ngắt (vd sau khi app được cập nhật)
        NotificationListenerService.requestRebind(ComponentName(this, MbNotificationListener::class.java))
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

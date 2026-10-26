package vn.mbfinance.tracker

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Hệ thống gọi service này mỗi khi có thông báo mới (không cần chạy nền liên tục).
 * - Mọi app: chỉ đếm tên gói (để tìm đúng package MB Bank), KHÔNG lưu nội dung.
 * - App MB Bank đã chọn: lưu toàn bộ nội dung vào hàng đợi rồi kích hoạt gửi.
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

        val store = Store.get(this)
        store.recordSource(pkg, System.currentTimeMillis())

        if (pkg != Settings(this).mbPackage) return

        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val text = extractText(n) ?: return
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        if (store.enqueue(pkg, title, text, sbn.postTime)) {
            Sync.kick(this)
        }
    }

    override fun onListenerConnected() {
        Sync.schedulePeriodic(this)
    }

    override fun onListenerDisconnected() {
        // Xin hệ thống kết nối lại khi bị ngắt (vd sau khi app được cập nhật)
        NotificationListenerService.requestRebind(ComponentName(this, MbNotificationListener::class.java))
    }

    companion object {
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

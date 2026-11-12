package vn.mbfinance.tracker.game

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Báo thức nội bộ "sức mạnh khai phá sắp đầy": tính sẵn giờ từ lần đọc gần nhất, không cần mạng khi báo. */
class GameAlarm : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        remind(ctx.applicationContext, GameStore(ctx.applicationContext))
    }

    companion object {
        private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"))

        private fun intent(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, GameAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Đặt lại báo thức theo snapshot mới; đã tới ngưỡng thì nhắc ngay (1 lần cho mỗi lần đầy). */
        fun schedule(ctx: Context, s: GameStore) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(intent(ctx))
            val full = s.fullAt ?: return
            if (s.notifyBefore < 0) return
            val at = full - s.notifyBefore * 60_000L
            val now = System.currentTimeMillis()
            if (at > now) {
                s.notified = false
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
            } else {
                remind(ctx, s)
            }
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(intent(ctx))
        }

        private fun remind(ctx: Context, s: GameStore) {
            if (s.notified || s.notifyBefore < 0) return
            val note = s.note ?: return
            val full = s.fullAt ?: return
            val now = System.currentTimeMillis()
            val text = if (now >= full) "Đã đầy ${note.max}/${note.max} — vào game dùng kẻo phí"
            else "~${s.staminaAt(now)}/${note.max}, đầy lúc ${clock.format(Instant.ofEpochMilli(full))}"
            GameNotify.post(ctx, GameNotify.STAMINA, "Sức mạnh khai phá sắp đầy", text)
            s.notified = true
        }
    }
}

package vn.mbfinance.tracker.game

import android.content.Context
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

/** Việc của module game: điểm danh nếu hôm nay chưa, đọc ghi chú (sức mạnh khai phá…), đặt báo thức sắp đầy. */
internal object GameSync {

    /** Ngày mới của HSR bắt đầu 0h UTC+8 (23h giờ VN). */
    fun today(): String = LocalDate.now(ZoneOffset.ofHours(8)).toString()

    /** Ghi chú cũ hơn chừng này mới đọc lại (trừ khi [run] được ép). */
    private const val NOTE_TTL = 5 * 60_000L

    /**
     * Chạy ở luồng nền. [force] = người dùng bấm làm mới (đọc lại ngay), [forceSign] = bấm "Điểm danh ngay".
     * Lỗi được lưu vào [GameStore.lastError] để màn Game hiện ra.
     */
    @Synchronized
    fun run(ctx: Context, force: Boolean = false, forceSign: Boolean = false) {
        val s = GameStore(ctx)
        if (!Hoyolab.loggedIn()) return
        try {
            val role = s.role ?: Hoyolab.roles().firstOrNull()?.also { s.role = it }
            if (forceSign || (s.autoSign && s.signDay != today())) sign(ctx, s)
            if (role != null && (force || System.currentTimeMillis() - s.noteAt > NOTE_TTL)) {
                s.saveNote(Hoyolab.note(role), System.currentTimeMillis())
                GameAlarm.schedule(ctx, s)
            }
            s.lastError = if (role == null) "Không tìm thấy nhân vật Honkai: Star Rail trong tài khoản HoYoLAB này." else null
        } catch (e: Hoyolab.NotLoggedIn) {
            s.lastError = "Phiên đăng nhập HoYoLAB đã hết hạn — hãy đăng nhập lại."
            warnOnce(ctx, s, "Cần đăng nhập lại HoYoLAB", "Phiên đăng nhập hết hạn, chưa tự điểm danh được.")
        } catch (e: Exception) {
            s.lastError = message(e)
        }
    }

    private fun sign(ctx: Context, s: GameStore) {
        val day = today()
        val (signed, total) = Hoyolab.signInfo()
        if (signed) {
            s.signDay = day
            s.signTotal = total
            return
        }
        when (Hoyolab.sign()) {
            Hoyolab.SignResult.CAPTCHA -> {
                s.captchaDay = day
                warnOnce(ctx, s, "Chưa điểm danh HSR", "HoYoLAB yêu cầu xác minh — mở app để điểm danh tay.")
            }
            else -> {
                val days = Hoyolab.signInfo().second
                s.signDay = day
                s.signTotal = days
                s.signReward = runCatching { Hoyolab.reward(days) }.getOrNull()
                GameNotify.cancel(ctx, GameNotify.SIGN)
            }
        }
    }

    /** Nhắc lỗi cần người dùng xử lý tối đa 1 lần mỗi ngày. */
    private fun warnOnce(ctx: Context, s: GameStore, title: String, text: String) {
        val day = today()
        if (s.warnedDay == day) return
        s.warnedDay = day
        GameNotify.post(ctx, GameNotify.SIGN, title, text)
    }

    private fun message(e: Exception): String = when (e) {
        is Hoyolab.ApiError -> when (e.retcode) {
            10102 -> "Chưa bật Ghi chú thời gian thực: HoYoLAB → Sổ tay chiến tích → Cài đặt → bật \"Ghi chú thời gian thực\"."
            10101, 1034, 5003 -> "HoYoLAB tạm chặn (cần xác minh). Thử lại sau, hoặc mở HoYoLAB trong app."
            else -> "HoYoLAB báo lỗi ${e.retcode}: ${e.message}"
        }
        is Net.HttpError -> "HoYoLAB trả lỗi HTTP ${e.code}"
        is IOException -> "Không kết nối được HoYoLAB"
        else -> e.message ?: e.javaClass.simpleName
    }
}

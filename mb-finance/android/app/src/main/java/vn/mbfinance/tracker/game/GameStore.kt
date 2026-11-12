package vn.mbfinance.tracker.game

import android.content.Context
import org.json.JSONObject
import kotlin.math.ceil

/** Trạng thái module game (SharedPreferences riêng "game"); không chứa cookie hay mật khẩu. */
internal class GameStore(ctx: Context) {

    private val p = ctx.getSharedPreferences("game", Context.MODE_PRIVATE)

    var role: Hoyolab.Role?
        get() = p.getString("uid", null)?.let {
            Hoyolab.Role(it, p.getString("server", null) ?: Hoyolab.serverOf(it), p.getString("nickname", "").orEmpty(), p.getInt("level", 0))
        }
        set(v) {
            p.edit().apply {
                if (v == null) {
                    remove("uid")
                } else {
                    putString("uid", v.uid)
                    putString("server", v.server)
                    putString("nickname", v.nickname)
                    putInt("level", v.level)
                }
            }.apply()
        }

    /** JSON gốc của ghi chú lần đọc gần nhất + thời điểm đọc. */
    val note: Hoyolab.Note? get() = p.getString("note", null)?.let { runCatching { Hoyolab.Note.parse(JSONObject(it)) }.getOrNull() }
    val noteAt: Long get() = p.getLong("note_at", 0)

    fun saveNote(json: JSONObject, at: Long) = p.edit().putString("note", json.toString()).putLong("note_at", at).apply()

    /** Ngày (giờ UTC+8 của HSR) đã điểm danh xong, số ngày trong tháng, quà nhận được. */
    var signDay: String?
        get() = p.getString("sign_day", null)
        set(v) = p.edit().putString("sign_day", v).apply()
    var signTotal: Int
        get() = p.getInt("sign_total", 0)
        set(v) = p.edit().putInt("sign_total", v).apply()
    var signReward: String?
        get() = p.getString("sign_reward", null)
        set(v) = p.edit().putString("sign_reward", v).apply()
    /** Ngày bị captcha khi tự điểm danh (cần bấm tay). */
    var captchaDay: String?
        get() = p.getString("captcha_day", null)
        set(v) = p.edit().putString("captcha_day", v).apply()

    var lastError: String?
        get() = p.getString("error", null)
        set(v) = p.edit().putString("error", v).putLong("error_at", System.currentTimeMillis()).apply()
    val lastErrorAt: Long get() = p.getLong("error_at", 0)

    var autoSign: Boolean
        get() = p.getBoolean("auto_sign", true)
        set(v) = p.edit().putBoolean("auto_sign", v).apply()

    /** Nhắc trước khi đầy bao nhiêu phút; -1 = tắt. */
    var notifyBefore: Int
        get() = p.getInt("notify_before", 0)
        set(v) = p.edit().putInt("notify_before", v).apply()

    /** Đã nhắc cho lần đầy hiện tại chưa (đặt lại khi sức mạnh tụt dưới ngưỡng). */
    var notified: Boolean
        get() = p.getBoolean("notified", false)
        set(v) = p.edit().putBoolean("notified", v).apply()

    /** Ngày đã nhắc đăng nhập lại / captcha, tránh nhắc liên tục. */
    var warnedDay: String?
        get() = p.getString("warned_day", null)
        set(v) = p.edit().putString("warned_day", v).apply()

    /** Thời điểm sức mạnh khai phá đầy theo lần đọc gần nhất; null nếu chưa đọc. */
    val fullAt: Long? get() = note?.let { noteAt + it.recoverSec * 1000 }

    /** Sức mạnh khai phá ước tính lúc [now]: hồi 1 điểm mỗi 6 phút, tính ngược từ giờ đầy. */
    fun staminaAt(now: Long): Int? {
        val n = note ?: return null
        val full = fullAt ?: return null
        if (n.stamina >= n.max || now >= full) return maxOf(n.stamina, n.max)
        val left = ceil((full - now) / RECOVER_MS.toDouble()).toInt()
        return maxOf(n.stamina, n.max - left)
    }

    fun clear() = p.edit().clear().apply()

    companion object {
        const val RECOVER_MS = 6 * 60_000L
    }
}

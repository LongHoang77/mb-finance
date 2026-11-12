package vn.mbfinance.tracker.game

import android.webkit.CookieManager
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * API HoYoLAB (không chính thức, theo các dự án cộng đồng) cho Honkai: Star Rail bản quốc tế.
 * Cookie lấy từ WebView đã đăng nhập ([CookieManager]); không lưu ra chỗ khác, không ghi log.
 */
internal object Hoyolab {

    private const val ACT_ID = "e202303301540311"
    const val LOGIN_URL = "https://www.hoyolab.com/home"
    const val CHECKIN_PAGE = "https://act.hoyolab.com/bbs/event/signin/hkrpg/index.html?act_id=$ACT_ID"

    /** Host WebView được phép mở (đăng nhập, trang điểm danh). */
    val WEB_HOSTS = listOf("hoyolab.com", "hoyoverse.com")

    private const val SIGN = "https://sg-public-api.hoyolab.com/event/luna/os"
    private const val RECORD = "https://bbs-api-os.hoyolab.com/game_record/hkrpg/api"
    private const val ROLES = "https://api-account-os.hoyolab.com/account/binding/api/getUserGameRolesByCookie?game_biz=hkrpg_global"
    private const val DS_SALT = "6s25p5ox5y14umn1p61aqyyvbvvl3lrt"
    private const val LANG = "vi-vn"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    class NotLoggedIn : Exception("Chưa đăng nhập HoYoLAB")
    class ApiError(val retcode: Int, message: String) : Exception(message)

    data class Role(val uid: String, val server: String, val nickname: String, val level: Int)

    data class Expedition(val name: String, val remainingSec: Long)

    /** Ghi chú thời gian thực; [recoverSec] = số giây tới khi đầy (0 = đã đầy). */
    data class Note(
        val stamina: Int,
        val max: Int,
        val recoverSec: Long,
        val reserve: Int,
        val reserveFull: Boolean,
        val train: Int,
        val trainMax: Int,
        val rogue: Int,
        val rogueMax: Int,
        val cocoon: Int,
        val cocoonMax: Int,
        val expAccepted: Int,
        val expTotal: Int,
        val expeditions: List<Expedition>,
    ) {
        companion object {
            fun parse(d: JSONObject): Note {
                val exps = d.optJSONArray("expeditions")
                return Note(
                    stamina = d.optInt("current_stamina"),
                    max = d.optInt("max_stamina", 240),
                    recoverSec = d.optLong("stamina_recover_time"),
                    reserve = d.optInt("current_reserve_stamina"),
                    reserveFull = d.optBoolean("is_reserve_stamina_full"),
                    train = d.optInt("current_train_score"),
                    trainMax = d.optInt("max_train_score"),
                    rogue = d.optInt("current_rogue_score"),
                    rogueMax = d.optInt("max_rogue_score"),
                    cocoon = d.optInt("weekly_cocoon_cnt"),
                    cocoonMax = d.optInt("weekly_cocoon_limit"),
                    expAccepted = d.optInt("accepted_epedition_num", d.optInt("accepted_expedition_num")),
                    expTotal = d.optInt("total_expedition_num"),
                    expeditions = (0 until (exps?.length() ?: 0)).map { i ->
                        val e = exps!!.getJSONObject(i)
                        Expedition(e.optString("name"), e.optLong("remaining_time"))
                    },
                )
            }
        }
    }

    enum class SignResult { OK, ALREADY, CAPTCHA }

    /** Cookie đăng nhập HoYoLAB trong WebView; null nếu chưa đăng nhập. */
    fun cookie(): String? =
        CookieManager.getInstance().getCookie("https://www.hoyolab.com")?.takeIf { "ltoken_v2=" in it && "ltuid_v2=" in it }

    fun loggedIn() = cookie() != null

    /** UID HSR → server (chữ số trước 8 số cuối): 6 Mỹ, 7 Âu, 8 Á, 9 TW/HK/MO. */
    fun serverOf(uid: String) = when (uid.dropLast(8)) {
        "6" -> "prod_official_usa"
        "7" -> "prod_official_eur"
        "9" -> "prod_official_cht"
        else -> "prod_official_asia"
    }

    /** Nhân vật HSR của tài khoản đang đăng nhập, cấp cao nhất trước. */
    fun roles(): List<Role> {
        val list = data(ROLES).optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            val uid = o.getString("game_uid")
            Role(uid, o.optString("region").ifEmpty { serverOf(uid) }, o.optString("nickname"), o.optInt("level"))
        }.sortedByDescending { it.level }
    }

    /** Ghi chú thời gian thực (sức mạnh khai phá, ủy thác…); trả cả JSON gốc để lưu snapshot. */
    fun note(r: Role): JSONObject = data("$RECORD/note?server=${r.server}&role_id=${r.uid}", extra = recordHeaders())

    /** (đã điểm danh hôm nay chưa, số ngày đã điểm danh trong tháng). */
    fun signInfo(): Pair<Boolean, Int> {
        val d = data("$SIGN/info?lang=$LANG&act_id=$ACT_ID")
        return d.optBoolean("is_sign") to d.optInt("total_sign_day")
    }

    /** Tên + số lượng phần quà của ngày điểm danh thứ [day] (1 = ngày đầu tháng). */
    fun reward(day: Int): String? {
        val awards = data("$SIGN/home?lang=$LANG&act_id=$ACT_ID").optJSONArray("awards") ?: return null
        val a = awards.optJSONObject(day - 1) ?: return null
        return "${a.optString("name")} ×${a.optInt("cnt")}"
    }

    fun sign(): SignResult {
        val j = raw("$SIGN/sign?lang=$LANG&act_id=$ACT_ID", "POST", mapOf("x-rpc-signgame" to "hkrpg"))
        return when (val rc = j.optInt("retcode", -1)) {
            0 -> if (j.optJSONObject("data")?.optJSONObject("gt_result")?.optBoolean("is_risk") == true) SignResult.CAPTCHA else SignResult.OK
            -5003 -> SignResult.ALREADY
            else -> throw error(rc, j)
        }
    }

    private fun data(url: String, method: String = "GET", extra: Map<String, String> = emptyMap()): JSONObject {
        val j = raw(url, method, extra)
        val rc = j.optInt("retcode", -1)
        if (rc != 0) throw error(rc, j)
        return j.optJSONObject("data") ?: JSONObject()
    }

    private fun raw(url: String, method: String, extra: Map<String, String>): JSONObject {
        val ck = cookie() ?: throw NotLoggedIn()
        val base = mapOf(
            "Cookie" to ck,
            "User-Agent" to UA,
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "https://act.hoyolab.com",
            "Referer" to "https://act.hoyolab.com/",
            "x-rpc-app_version" to "2.34.1",
            "x-rpc-client_type" to "4",
        )
        return Net.request(url, method, base + extra)
    }

    // -100 / 10001: cookie hết hạn hoặc không hợp lệ
    private fun error(rc: Int, j: JSONObject): Exception =
        if (rc == -100 || rc == 10001) NotLoggedIn() else ApiError(rc, j.optString("message"))

    /** Header cho API sổ tay chiến tích: DS = "t,r,md5(salt=…&t=…&r=…)". */
    private fun recordHeaders(): Map<String, String> {
        val t = System.currentTimeMillis() / 1000
        val letters = ('a'..'z') + ('A'..'Z')
        val rnd = SecureRandom()
        val r = (1..6).map { letters[rnd.nextInt(letters.size)] }.joinToString("")
        val h = MessageDigest.getInstance("MD5").digest("salt=$DS_SALT&t=$t&r=$r".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return mapOf(
            "DS" to "$t,$r,$h",
            "x-rpc-app_version" to "1.5.0",
            "x-rpc-client_type" to "5",
            "x-rpc-language" to LANG,
            "x-rpc-lang" to LANG,
            "Origin" to "https://www.hoyolab.com",
            "Referer" to "https://www.hoyolab.com/",
        )
    }
}

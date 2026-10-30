package vn.mbfinance.tracker

import android.content.Context
import java.security.KeyStore

class Settings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mbPackage: String
        get() = p.getString("mb_package", DEFAULT_MB_PACKAGE) ?: DEFAULT_MB_PACKAGE
        set(v) = p.edit().putString("mb_package", v).apply()

    /** Phiên bản Parser đã dùng để điền bảng transactions. */
    var parserVersion: Int
        get() = p.getInt("parser_version", 0)
        set(v) = p.edit().putInt("parser_version", v).apply()

    val lastError: String? get() = p.getString("last_error", null)
    val lastErrorAt: Long get() = p.getLong("last_error_at", 0L)

    fun markError(msg: String) {
        p.edit().putString("last_error", msg).putLong("last_error_at", System.currentTimeMillis()).apply()
    }

    /** Bản cũ có gửi lên server: xoá địa chỉ, token đã lưu và khoá mã hoá token. */
    fun dropServerConfig() {
        if (!p.contains("url") && !p.contains("token_enc")) return
        p.edit().remove("url").remove("token_enc").remove("last_ok").remove("last_error").apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("thu_chi_mb_token") }
    }

    companion object {
        const val DEFAULT_MB_PACKAGE = "com.mbmobile"
    }
}

package vn.mbfinance.tracker.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import vn.mbfinance.tracker.game.Net
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Tự cập nhật từ GitHub Releases của repo: CI build APK mỗi lần push lên main và đăng bản phát hành
 * tag `build-<versionCode>` kèm file .apk. Repo công khai thì không cần token; repo riêng tư cần token chỉ đọc
 * của người dùng (lưu mã hoá bằng Android Keystore). Tải về rồi cài đè qua PackageInstaller — hệ thống chỉ cho
 * cài khi cùng khoá ký.
 * Không gửi dữ liệu tài chính đi đâu; mọi request qua [Net].
 */
object Updater {

    private const val REPO = "LongHoang77/mb-finance"
    private const val KEY_ALIAS = "thu_chi_mb_github"
    private const val MAX_APK = 100L shl 20
    /** Tự kiểm tra khi mở app, tối đa 1 lần mỗi chừng này. */
    private const val AUTO_CHECK_MS = 3 * 60 * 60_000L

    data class Release(val code: Long, val name: String, val notes: String, val assetUrl: String, val size: Long, val sha256: String?)

    /** Bản mới hơn bản đang chạy, tìm được ở lần kiểm tra gần nhất (null = chưa có / đã mới nhất). */
    @Volatile
    var available: Release? = null
        private set

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    fun currentName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    fun hasToken(ctx: Context) = prefs(ctx).contains("token_enc")

    fun lastCheck(ctx: Context) = prefs(ctx).getLong("last_check", 0L)

    fun lastError(ctx: Context): String? = prefs(ctx).getString("last_error", null)

    fun setError(ctx: Context, msg: String?) = prefs(ctx).edit().putString("last_error", msg).apply()

    /** Bản đã hỏi "cập nhật không?" khi mở app → không hỏi lại bản đó. */
    fun seenCode(ctx: Context) = prefs(ctx).getLong("seen_code", 0L)

    fun markSeen(ctx: Context, code: Long) = prefs(ctx).edit().putLong("seen_code", code).apply()

    fun shouldAutoCheck(ctx: Context) = System.currentTimeMillis() - lastCheck(ctx) > AUTO_CHECK_MS

    /** Lưu token (rỗng/null = xoá), mã hoá AES-GCM bằng khoá trong Android Keystore. */
    fun setToken(ctx: Context, token: String?) {
        val t = token?.trim().orEmpty()
        if (t.isEmpty()) {
            prefs(ctx).edit().remove("token_enc").remove("last_error").apply()
            available = null
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = c.iv + c.doFinal(t.toByteArray())
        prefs(ctx).edit().putString("token_enc", Base64.encodeToString(enc, Base64.NO_WRAP)).remove("last_error").apply()
    }

    private fun token(ctx: Context): String? {
        val enc = prefs(ctx).getString("token_enc", null) ?: return null
        return runCatching {
            val raw = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    /** Header gọi GitHub API; không có token (repo công khai) thì gọi ẩn danh (60 lượt/giờ, đủ cho 3 giờ/lần). */
    private fun headers(token: String?, accept: String) = buildMap {
        if (token != null) put("Authorization", "Bearer $token")
        put("Accept", accept)
        put("X-GitHub-Api-Version", "2022-11-28")
        put("User-Agent", "thu-chi-mb")
    }

    /**
     * Đọc bản phát hành mới nhất; trả về nó nếu mới hơn bản đang chạy. Ghi lại giờ kiểm tra và lỗi (nếu có).
     * Chạy ở luồng nền.
     */
    fun check(ctx: Context): Release? {
        val p = prefs(ctx)
        p.edit().putLong("last_check", System.currentTimeMillis()).apply()
        try {
            val j = Net.request("https://api.github.com/repos/$REPO/releases/latest", "GET", headers(token(ctx), "application/vnd.github+json"))
            val tag = j.getString("tag_name")
            val code = tag.substringAfterLast('-').toLongOrNull() ?: throw IllegalStateException("Tag lạ: $tag")
            val assets = j.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
                ?: throw IllegalStateException("Bản $tag không có file APK")
            val r = Release(
                code = code,
                name = j.optString("name").ifBlank { tag },
                notes = j.optString("body").trim(),
                assetUrl = apk.getString("url"),
                size = apk.optLong("size"),
                sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
            available = r.takeIf { it.code > currentCode(ctx) }
            setError(ctx, null)
            return available
        } catch (e: Exception) {
            setError(ctx, describe(e))
            throw e
        }
    }

    fun describe(e: Exception): String = when {
        e is Net.HttpError && e.code == 401 -> "Token sai hoặc đã hết hạn"
        e is Net.HttpError && e.code == 404 -> "Không thấy bản phát hành (CI chưa đăng bản nào, hoặc repo riêng tư mà chưa có token)"
        e is Net.HttpError && e.code == 403 -> "GitHub từ chối (hết lượt gọi API hoặc token thiếu quyền)"
        e is java.io.IOException -> "Không kết nối được GitHub"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Tải APK của [r] vào cache; [onProgress] nhận phần trăm. Kiểm tra SHA-256 (nếu GitHub có) và đúng package/phiên bản. */
    fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File {
        val token = token(ctx)
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "thu-chi-mb-${r.code}.apk")
        var last = -1
        Net.download(r.assetUrl, headers(token, "application/octet-stream"), f, MAX_APK) { done ->
            val pct = if (r.size > 0) (done * 100 / r.size).toInt().coerceIn(0, 100) else 0
            if (pct != last) {
                last = pct
                onProgress(pct)
            }
        }
        if (r.sha256 != null) {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(r.sha256, ignoreCase = true)) throw IllegalStateException("File tải về bị hỏng (sai SHA-256)")
        }
        val info = ctx.packageManager.getPackageArchiveInfo(f.path, 0) ?: throw IllegalStateException("File tải về không phải APK hợp lệ")
        if (info.packageName != ctx.packageName) throw IllegalStateException("APK không phải của app này")
        return f
    }

    /**
     * Cài đè [apk] bằng PackageInstaller. Android 12+ không cần bấm xác nhận khi app đã là bên cài bản đang chạy;
     * còn lại hệ thống hỏi (xem [UpdateReceiver]). App bị đóng khi cài xong.
     */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }
}

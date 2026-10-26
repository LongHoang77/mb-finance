package vn.mbfinance.tracker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Settings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("url", "") ?: ""
        set(v) = p.edit().putString("url", v.trim().trimEnd('/')).apply()

    /** Token được mã hoá bằng khoá trong Android Keystore trước khi lưu. */
    var token: String?
        get() = p.getString("token_enc", null)?.let { TokenCrypto.decrypt(it) }
        set(v) {
            val e = p.edit()
            if (v.isNullOrBlank()) e.remove("token_enc") else e.putString("token_enc", TokenCrypto.encrypt(v.trim()))
            e.apply()
        }

    val hasToken: Boolean get() = p.contains("token_enc")

    var mbPackage: String
        get() = p.getString("mb_package", DEFAULT_MB_PACKAGE) ?: DEFAULT_MB_PACKAGE
        set(v) = p.edit().putString("mb_package", v).apply()

    val lastOkAt: Long get() = p.getLong("last_ok", 0L)
    val lastError: String? get() = p.getString("last_error", null)
    val lastErrorAt: Long get() = p.getLong("last_error_at", 0L)

    fun markOk() {
        p.edit().putLong("last_ok", System.currentTimeMillis()).remove("last_error").apply()
    }

    fun markError(msg: String) {
        p.edit().putString("last_error", msg).putLong("last_error_at", System.currentTimeMillis()).apply()
    }

    companion object {
        const val DEFAULT_MB_PACKAGE = "com.mbmobile"
    }
}

private object TokenCrypto {
    private const val ALIAS = "thu_chi_mb_token"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = try {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_LEN))
        String(c.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

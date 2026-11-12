package vn.mbfinance.tracker.game

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Điểm gọi mạng DUY NHẤT của app: chỉ HTTPS tới các host HoYoLAB trong [ALLOWED], không theo chuyển hướng.
 * Module tài chính không dùng mạng.
 */
internal object Net {

    private val ALLOWED = setOf("sg-public-api.hoyolab.com", "bbs-api-os.hoyolab.com", "api-account-os.hoyolab.com")
    private const val MAX_BODY = 1 shl 20

    class HttpError(val code: Int) : Exception("HTTP $code")

    fun request(url: String, method: String, headers: Map<String, String>): JSONObject {
        val u = URL(url)
        require(u.protocol == "https" && u.host in ALLOWED) { "Host không được phép: ${u.host}" }
        val c = u.openConnection() as HttpsURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = false
            c.useCaches = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (method == "POST") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code)
            // Phản hồi API nhỏ; quá 1 MB coi là bất thường
            val body = StringBuilder()
            c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(8192)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    body.append(buf, 0, n)
                    if (body.length > MAX_BODY) throw IllegalStateException("Phản hồi quá lớn")
                }
            }
            return JSONObject(body.toString())
        } finally {
            c.disconnect()
        }
    }
}

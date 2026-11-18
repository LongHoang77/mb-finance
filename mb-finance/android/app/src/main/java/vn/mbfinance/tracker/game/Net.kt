package vn.mbfinance.tracker.game

import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Điểm gọi mạng DUY NHẤT của app: chỉ HTTPS tới các host trong [ALLOWED] (HoYoLAB cho module Game,
 * api.github.com cho tự cập nhật), không theo chuyển hướng. Module tài chính không dùng mạng.
 */
internal object Net {

    private val ALLOWED = setOf(
        "sg-public-api.hoyolab.com", "bbs-api-os.hoyolab.com", "api-account-os.hoyolab.com",
        "api.github.com",
    )

    /** GitHub chuyển file đính kèm của bản phát hành sang các host này (URL ký sẵn, không cần token). */
    private val DOWNLOAD_HOSTS = setOf("objects.githubusercontent.com", "release-assets.githubusercontent.com")

    private const val MAX_BODY = 1 shl 20

    class HttpError(val code: Int) : Exception("HTTP $code")

    private fun open(u: URL, method: String, headers: Map<String, String>, readTimeout: Int): HttpsURLConnection =
        (u.openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            this.readTimeout = readTimeout
            instanceFollowRedirects = false
            useCaches = false
            for ((k, v) in headers) setRequestProperty(k, v)
        }

    fun request(url: String, method: String, headers: Map<String, String>): JSONObject {
        val u = URL(url)
        require(u.protocol == "https" && u.host in ALLOWED) { "Host không được phép: ${u.host}" }
        val c = open(u, method, headers, 15_000)
        try {
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

    /**
     * Tải file về [dest] (tối đa [maxBytes]). Cho phép đúng 1 lần chuyển hướng sang [DOWNLOAD_HOSTS], khi đó
     * KHÔNG gửi kèm [headers] (token). [onProgress] nhận số byte đã tải.
     */
    fun download(url: String, headers: Map<String, String>, dest: File, maxBytes: Long, onProgress: (Long) -> Unit) {
        var u = URL(url)
        require(u.protocol == "https" && u.host in ALLOWED) { "Host không được phép: ${u.host}" }
        var h = headers
        for (hop in 0..1) {
            val c = open(u, "GET", h, 30_000)
            try {
                val code = c.responseCode
                if (code in 301..308 && hop == 0) {
                    val next = URL(u, c.getHeaderField("Location") ?: throw HttpError(code))
                    require(next.protocol == "https" && next.host in DOWNLOAD_HOSTS) { "Chuyển hướng tới host lạ: ${next.host}" }
                    u = next
                    h = mapOf("User-Agent" to (headers["User-Agent"] ?: "thu-chi-mb"))
                    continue
                }
                if (code !in 200..299) throw HttpError(code)
                if (c.contentLengthLong > maxBytes) throw IllegalStateException("File quá lớn")
                var total = 0L
                c.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw IllegalStateException("File quá lớn")
                            out.write(buf, 0, n)
                            onProgress(total)
                        }
                    }
                }
                return
            } finally {
                c.disconnect()
            }
        }
        throw IllegalStateException("Chuyển hướng quá nhiều lần")
    }
}

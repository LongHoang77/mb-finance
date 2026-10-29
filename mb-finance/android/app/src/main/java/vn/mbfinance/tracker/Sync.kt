package vn.mbfinance.tracker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

class HttpError(val code: Int) : Exception("HTTP $code")

class Api(private val baseUrl: String, private val token: String) {
    fun post(path: String, body: JSONObject): JSONObject {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw HttpError(code)
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}

object Sync {
    private const val SYNC_WORK = "sync"
    private const val HEARTBEAT_WORK = "heartbeat"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Gửi ngay khi có mạng; lỗi thì tự thử lại với thời gian chờ tăng dần. */
    fun kick(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Nhịp tim mỗi 6 giờ, đồng thời gửi nốt những gì còn tồn. */
    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<HeartbeatWorker>(6, TimeUnit.HOURS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(HEARTBEAT_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun api(ctx: Context): Api? {
        val s = Settings(ctx)
        val token = s.token
        if (s.serverUrl.isBlank() || token.isNullOrBlank()) {
            s.markError("Chưa nhập địa chỉ server hoặc token")
            return null
        }
        return Api(s.serverUrl, token)
    }

    /** Gửi toàn bộ hàng đợi theo lô 100. Chạy trên luồng nền. */
    fun pushAll(ctx: Context): ListenableWorker.Result {
        val s = Settings(ctx)
        val store = Store.get(ctx)
        val api = api(ctx) ?: return ListenableWorker.Result.failure()
        return try {
            while (true) {
                val batch = store.pending(100)
                if (batch.isEmpty()) break
                val items = JSONArray()
                for (it in batch) {
                    items.put(
                        JSONObject()
                            .put("clientUuid", it.uuid)
                            .put("package", it.pkg)
                            .put("title", it.title ?: JSONObject.NULL)
                            .put("text", it.text)
                            .put("postedAt", Instant.ofEpochMilli(it.postedAt).toString())
                    )
                }
                val res = api.post("/api/v1/notifications", JSONObject().put("items", items))
                val accepted = res.getJSONArray("accepted")
                val results = HashMap<String, String>()
                for (i in 0 until accepted.length()) {
                    val o = accepted.getJSONObject(i)
                    results[o.getString("clientUuid")] = o.getString("status")
                }
                if (results.isEmpty()) break
                store.markSynced(results)
            }
            s.markOk()
            ListenableWorker.Result.success()
        } catch (e: HttpError) {
            s.markError(
                when (e.code) {
                    401 -> "Token sai hoặc đã bị thu hồi (401)"
                    else -> "Server trả lỗi ${e.code}"
                }
            )
            // Lỗi do cấu hình/dữ liệu: thử lại cũng vô ích, chờ người dùng sửa rồi bấm "Gửi ngay"
            if (e.code == 400 || e.code == 401 || e.code == 413) ListenableWorker.Result.failure()
            else ListenableWorker.Result.retry()
        } catch (e: Exception) {
            s.markError("Không kết nối được server (${e.javaClass.simpleName}) — Tailscale có đang bật?")
            ListenableWorker.Result.retry()
        }
    }

    /** Gửi nhịp tim. Trả về null nếu thành công, ngược lại là thông báo lỗi. */
    fun heartbeat(ctx: Context): String? {
        val api = api(ctx) ?: return "Chưa nhập địa chỉ server hoặc token"
        return try {
            val version = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
            } catch (e: Exception) {
                "?"
            }
            api.post(
                "/api/v1/heartbeat",
                JSONObject().put("pendingCount", Store.get(ctx).count(Store.PENDING)).put("appVersion", version)
            )
            Settings(ctx).markOk()
            null
        } catch (e: HttpError) {
            if (e.code == 401) "Token sai hoặc đã bị thu hồi" else "Server trả lỗi ${e.code}"
        } catch (e: Exception) {
            "Không kết nối được (${e.javaClass.simpleName}). Kiểm tra Tailscale và địa chỉ."
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Sync.pushAll(applicationContext)
    }
}

class HeartbeatWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Sync.pushAll(applicationContext)
        val err = Sync.heartbeat(applicationContext)
        if (err != null) Settings(applicationContext).markError(err)
        // Lỗi thì để lần chạy định kỳ sau thử lại; không cần retry riêng
        Result.success()
    }
}

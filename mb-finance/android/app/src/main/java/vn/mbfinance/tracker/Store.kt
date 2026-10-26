package vn.mbfinance.tracker

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * Hàng đợi cục bộ: mọi thông báo MB Bank được ghi ở đây TRƯỚC khi gửi lên server,
 * nên mất mạng hay server lỗi cũng không mất dữ liệu.
 */
class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "store.db", null, 1) {

    data class Item(
        val id: Long,
        val uuid: String,
        val pkg: String,
        val title: String?,
        val text: String,
        val postedAt: Long,
        val status: String,
        val serverStatus: String?,
    )

    data class Source(val pkg: String, val count: Int, val lastSeen: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE queue (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              uuid TEXT NOT NULL UNIQUE,
              package TEXT NOT NULL,
              title TEXT,
              text TEXT NOT NULL,
              posted_at INTEGER NOT NULL,
              status TEXT NOT NULL DEFAULT 'pending',
              server_status TEXT,
              UNIQUE(package, text, posted_at)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_queue_status ON queue(status, id)")
        // Chỉ lưu TÊN GÓI và số lượng thông báo của mọi app (không lưu nội dung),
        // để tìm đúng package ID của MB Bank.
        db.execSQL("CREATE TABLE sources (package TEXT PRIMARY KEY, count INTEGER NOT NULL, last_seen INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun recordSource(pkg: String, now: Long) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO sources(package, count, last_seen) VALUES (?, 0, ?)", arrayOf<Any>(pkg, now))
        db.execSQL("UPDATE sources SET count = count + 1, last_seen = ? WHERE package = ?", arrayOf<Any>(now, pkg))
    }

    /** Trả về false nếu đã có (cùng app + cùng nội dung + cùng thời điểm). */
    fun enqueue(pkg: String, title: String?, text: String, postedAt: Long): Boolean {
        val cv = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("package", pkg)
            put("title", title?.take(500))
            put("text", text.take(4000))
            put("posted_at", postedAt)
        }
        return writableDatabase.insertWithOnConflict("queue", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun pending(limit: Int): List<Item> = query("WHERE status = 'pending' ORDER BY id LIMIT $limit")

    fun recent(limit: Int): List<Item> = query("ORDER BY id DESC LIMIT $limit")

    private fun query(tail: String): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.rawQuery(
            "SELECT id, uuid, package, title, text, posted_at, status, server_status FROM queue $tail", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Item(
                        id = c.getLong(0),
                        uuid = c.getString(1),
                        pkg = c.getString(2),
                        title = if (c.isNull(3)) null else c.getString(3),
                        text = c.getString(4),
                        postedAt = c.getLong(5),
                        status = c.getString(6),
                        serverStatus = if (c.isNull(7)) null else c.getString(7),
                    )
                )
            }
        }
        return out
    }

    fun markSynced(results: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((uuid, serverStatus) in results) {
                db.execSQL(
                    "UPDATE queue SET status = 'synced', server_status = ? WHERE uuid = ?",
                    arrayOf<Any>(serverStatus, uuid)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun count(status: String): Int =
        DatabaseUtils.queryNumEntries(readableDatabase, "queue", "status = ?", arrayOf(status)).toInt()

    fun sources(limit: Int): List<Source> {
        val out = ArrayList<Source>()
        readableDatabase.rawQuery(
            "SELECT package, count, last_seen FROM sources ORDER BY last_seen DESC LIMIT $limit", null
        ).use { c ->
            while (c.moveToNext()) out.add(Source(c.getString(0), c.getInt(1), c.getLong(2)))
        }
        return out
    }

    /** Xoá bản ghi ĐÃ GỬI cũ hơn mốc thời gian (bản chưa gửi không bao giờ bị xoá). */
    fun purgeSyncedBefore(before: Long) {
        writableDatabase.delete("queue", "status = 'synced' AND posted_at < ?", arrayOf(before.toString()))
    }

    companion object {
        const val PENDING = "pending"
        const val SYNCED = "synced"

        @Volatile
        private var instance: Store? = null

        fun get(ctx: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(ctx.applicationContext).also { instance = it }
            }
    }
}

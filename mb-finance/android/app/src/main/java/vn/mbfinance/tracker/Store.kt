package vn.mbfinance.tracker

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Hàng đợi cục bộ: mọi thông báo MB Bank được ghi ở đây TRƯỚC khi gửi lên server,
 * nên mất mạng hay server lỗi cũng không mất dữ liệu.
 * Thông báo giao dịch giữ mãi (bảng transactions và thống kê dựa trên đó);
 * thông báo khác (OTP, quảng cáo…) tự xoá sau 7 ngày.
 */
class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "store.db", null, 2) {

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
        createTransactions(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: thêm bảng giao dịch; dữ liệu cũ được điền khi Ledger thấy Parser.VERSION thay đổi
        if (oldVersion < 2) createTransactions(db)
    }

    /** Giao dịch phân tích từ queue (1-1), tách sẵn ngày/tháng/năm theo giờ VN. */
    private fun createTransactions(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE transactions (
              queue_id       INTEGER PRIMARY KEY REFERENCES queue(id) ON DELETE CASCADE,
              amount         INTEGER NOT NULL,   -- VND, luôn dương
              direction      TEXT NOT NULL,      -- 'in' | 'out'
              balance_after  INTEGER,
              account        TEXT,
              description    TEXT,
              tx_time        INTEGER NOT NULL,   -- epoch ms
              tx_date        TEXT NOT NULL,      -- yyyy-MM-dd
              tx_year        INTEGER NOT NULL,
              tx_month       INTEGER NOT NULL,
              tx_day         INTEGER NOT NULL,
              time_from_text INTEGER NOT NULL    -- 1: giờ ghi trong thông báo, 0: giờ máy nhận thông báo
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_tx_date ON transactions(tx_year, tx_month, tx_day)")
    }

    fun recordSource(pkg: String, now: Long) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO sources(package, count, last_seen) VALUES (?, 0, ?)", arrayOf<Any>(pkg, now))
        db.execSQL("UPDATE sources SET count = count + 1, last_seen = ? WHERE package = ?", arrayOf<Any>(now, pkg))
    }

    /** Lưu thông báo + giao dịch phân tích được. Trả về false nếu đã có (cùng app + nội dung + thời điểm). */
    fun enqueue(pkg: String, title: String?, text: String, postedAt: Long): Boolean {
        val cv = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("package", pkg)
            put("title", title?.take(500))
            put("text", text.take(4000))
            put("posted_at", postedAt)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertWithOnConflict("queue", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            if (id == -1L) return false
            saveParsed(db, id, text.take(4000), postedAt)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    private fun saveParsed(db: SQLiteDatabase, queueId: Long, text: String, postedAt: Long) {
        val p = Parser.parse(text) ?: return
        val time = p.time ?: postedAt
        val d = Instant.ofEpochMilli(time).atZone(Ledger.VN).toLocalDate()
        val cv = ContentValues().apply {
            put("queue_id", queueId)
            put("amount", p.amount)
            put("direction", if (p.isIn) "in" else "out")
            put("balance_after", p.balanceAfter)
            put("account", p.account)
            put("description", p.description)
            put("tx_time", time)
            put("tx_date", d.toString())
            put("tx_year", d.year)
            put("tx_month", d.monthValue)
            put("tx_day", d.dayOfMonth)
            put("time_from_text", if (p.time != null) 1 else 0)
        }
        db.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Phân tích lại toàn bộ queue (sau khi sửa parser). */
    @Synchronized
    fun reparseAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transactions", null, null)
            db.rawQuery("SELECT id, text, posted_at FROM queue", null).use { c ->
                while (c.moveToNext()) saveParsed(db, c.getLong(0), c.getString(1), c.getLong(2))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Giao dịch của [pkg], mới nhất trước. */
    fun transactions(pkg: String): List<Tx> {
        val out = ArrayList<Tx>()
        readableDatabase.rawQuery(
            """
            SELECT t.queue_id, t.amount, t.direction, t.balance_after, t.account, t.description,
                   t.tx_time, t.tx_year, t.tx_month, t.tx_day, t.time_from_text
            FROM transactions t JOIN queue q ON q.id = t.queue_id
            WHERE q.package = ?
            ORDER BY t.tx_time DESC, t.queue_id DESC
            """.trimIndent(),
            arrayOf(pkg),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Tx(
                        id = c.getLong(0),
                        amount = c.getLong(1),
                        isIn = c.getString(2) == "in",
                        balanceAfter = if (c.isNull(3)) null else c.getLong(3),
                        account = if (c.isNull(4)) null else c.getString(4),
                        description = if (c.isNull(5)) null else c.getString(5),
                        time = c.getLong(6),
                        date = LocalDate.of(c.getInt(7), c.getInt(8), c.getInt(9)),
                        timeFromText = c.getInt(10) == 1,
                    )
                )
            }
        }
        return out
    }

    /** Thông báo của [pkg] không phân tích được thành giao dịch, mới nhất trước. */
    fun unparsed(pkg: String): List<Item> =
        query("WHERE package = ? AND id NOT IN (SELECT queue_id FROM transactions) ORDER BY posted_at DESC", arrayOf(pkg))

    /** Xoá thông báo không phải giao dịch (OTP, quảng cáo…) cũ hơn [before]; giao dịch giữ mãi. */
    fun purgeNonTransactions(before: Long) {
        val ids = query(
            "WHERE posted_at < ? AND id NOT IN (SELECT queue_id FROM transactions)", arrayOf(before.toString())
        ).filter { !Parser.looksLikeTransaction(it.text) }.map { it.id }
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.delete("queue", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Nội dung thông báo gốc của 1 bản ghi queue. */
    fun text(id: Long): String? = query("WHERE id = ?", arrayOf(id.toString())).firstOrNull()?.text

    fun pending(limit: Int): List<Item> = query("WHERE status = 'pending' ORDER BY id LIMIT $limit")

    fun recent(limit: Int): List<Item> = query("ORDER BY id DESC LIMIT $limit")

    /** Đổi mỗi khi hàng đợi có bản ghi mới/bị xoá — dùng để biết khi nào cần tính lại thống kê. */
    fun dataKey(): String =
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(MAX(id), 0) FROM queue", null).use { c ->
            c.moveToFirst()
            "${c.getLong(0)}:${c.getLong(1)}"
        }

    private fun query(tail: String, args: Array<String>? = null): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.rawQuery(
            "SELECT id, uuid, package, title, text, posted_at, status, server_status FROM queue $tail", args
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

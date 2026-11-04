package vn.mbfinance.tracker

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Dữ liệu chỉ nằm trên máy: thông báo MB Bank nguyên văn (queue), giao dịch phân tích từ đó và tag.
 * Thông báo giao dịch giữ mãi (bảng transactions và thống kê dựa trên đó);
 * thông báo khác (OTP, quảng cáo…) tự xoá sau 7 ngày.
 */
class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "store.db", null, 4) {

    data class Item(
        val id: Long,
        val pkg: String,
        val title: String?,
        val text: String,
        val postedAt: Long,
        val deleted: Boolean = false, // người dùng đã xoá giao dịch này (xem [deleteTransaction])
    )

    data class Source(val pkg: String, val count: Int, val lastSeen: Long)

    /** Kết quả [reparseAll]: số bản trùng đã xoá, số giao dịch có cộng phí/VAT. */
    data class Recheck(val duplicates: Int, val withFee: Int)

    /** 1 giao dịch MB có số dư, dùng để đối soát phí theo số dư liền trước (xem [chain]). */
    private data class Link(val id: Long, val amount: Long, val balance: Long, val text: String)

    /** Số liệu cho widget, tính bằng SQL thay vì đọc hết bảng transactions. */
    /** [balance] = số dư ngân hàng, [cash] = tiền mặt (null nếu chưa nhập tay giao dịch nào). */
    data class Brief(val totalIn: Long, val totalOut: Long, val balance: Long?, val lastTxTime: Long?, val cash: Long?) {
        val net get() = totalIn - totalOut
    }

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
        createTags(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: thêm bảng giao dịch; dữ liệu cũ được điền khi Ledger thấy Parser.VERSION thay đổi
        if (oldVersion < 2) createTransactions(db)
        // v3: tag thu chi (cột status/server_status của queue từ thời gửi server giờ bỏ không dùng)
        if (oldVersion < 3) createTags(db)
        // v4: xoá giao dịch trùng đã lưu từ bản chưa có kiểm tra trùng
        if (oldVersion < 4) dropDuplicates(db)
    }

    /** Tag gắn theo queue_id (không theo bảng transactions vì bảng đó bị xoá/điền lại khi reparse). */
    private fun createTags(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE, color INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE tx_tags (queue_id INTEGER PRIMARY KEY, tag_id INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_tx_tags_tag ON tx_tags(tag_id)")
        DEFAULT_TAGS.forEachIndexed { i, name ->
            db.execSQL("INSERT INTO tags(name, color) VALUES (?, ?)", arrayOf<Any>(name, TAG_COLORS[i % TAG_COLORS.size]))
        }
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

    /**
     * Lưu thông báo + giao dịch phân tích được. Trả về false nếu đã có: cùng app + nội dung + thời điểm,
     * hoặc trùng một giao dịch đã lưu (xem [isDuplicate]).
     */
    fun enqueue(pkg: String, title: String?, text: String, postedAt: Long): Boolean {
        val body = text.take(4000)
        val cv = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("package", pkg)
            put("title", title?.take(500))
            put("text", body)
            put("posted_at", postedAt)
        }
        val p = Parser.parse(body)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (p != null && (isDuplicate(db, p) || isDeleted(db, pkg, p))) return false
            val id = db.insertWithOnConflict("queue", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            if (id == -1L) return false
            if (p != null) {
                saveParsed(db, id, p, postedAt)
                // Đối soát phí cho giao dịch mới và giao dịch ngay sau nó (thông báo có thể đến trễ)
                if (p.account != null && p.balanceAfter != null) {
                    val c = chain(db, p.account)
                    val i = c.indexOfFirst { it.id == id }
                    if (i >= 0) {
                        settle(db, c, i)
                        if (i + 1 < c.size) settle(db, c, i + 1)
                    }
                }
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * MB gửi lại / cập nhật thông báo → postTime hoặc text khác nhưng vẫn là 1 giao dịch.
     * Coi là trùng khi khớp TK, chiều, số tiền, số dư sau, nội dung và giờ GHI TRONG thông báo;
     * thiếu số dư hoặc giờ thì không đủ chắc → không coi là trùng.
     * amount đã lưu có thể gồm phí → so số tiền GD đọc lại từ thông báo gốc.
     */
    private fun isDuplicate(db: SQLiteDatabase, p: Parser.Parsed): Boolean {
        val time = p.time ?: return false
        val balance = p.balanceAfter ?: return false
        return db.rawQuery(
            """
            SELECT q.text FROM transactions t JOIN queue q ON q.id = t.queue_id
            WHERE t.time_from_text = 1 AND t.tx_time = ? AND t.direction = ?
              AND t.balance_after = ? AND COALESCE(t.account, '') = ? AND COALESCE(t.description, '') = ?
            """.trimIndent(),
            // rawQuery không nhận tham số null → so sánh qua COALESCE
            arrayOf(
                time.toString(), if (p.isIn) "in" else "out", balance.toString(),
                p.account.orEmpty(), p.description.orEmpty(),
            ),
        ).use { c ->
            while (c.moveToNext()) if (Parser.parse(c.getString(0))?.amount == p.amount) return true
            false
        }
    }

    /** MB gửi lại một giao dịch người dùng đã xoá → không thêm lại. Cùng tiêu chí với [isDuplicate]. */
    private fun isDeleted(db: SQLiteDatabase, pkg: String, p: Parser.Parsed): Boolean {
        if (p.time == null || p.balanceAfter == null) return false
        db.rawQuery("SELECT text FROM queue WHERE package = ? AND status = '$DELETED'", arrayOf(pkg)).use { c ->
            while (c.moveToNext()) {
                val d = Parser.parse(c.getString(0)) ?: continue
                if (d.time == p.time && d.balanceAfter == p.balanceAfter && d.amount == p.amount && d.isIn == p.isIn &&
                    d.account == p.account && d.description == p.description
                ) return true
            }
        }
        return false
    }

    /**
     * Người dùng xoá 1 giao dịch: bỏ khỏi transactions và tag, nhưng GIỮ dòng queue (đánh dấu deleted)
     * để quét lại thanh thông báo hay MB gửi lại không thêm lại giao dịch đó.
     */
    fun deleteTransaction(queueId: Long) {
        val db = writableDatabase
        val args = arrayOf(queueId.toString())
        db.beginTransaction()
        try {
            db.execSQL("UPDATE queue SET status = '$DELETED' WHERE id = ?", args)
            db.delete("transactions", "queue_id = ?", args)
            db.delete("tx_tags", "queue_id = ?", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Xoá thông báo trùng giao dịch đã có (giữ bản lưu trước); tag của bản bị xoá chuyển sang bản giữ lại nếu bản đó chưa có tag.
     * Trả về số bản đã xoá.
     */
    private fun dropDuplicates(db: SQLiteDatabase): Int {
        val pairs = ArrayList<Pair<Long, Long>>() // bản trùng → bản giữ lại
        db.rawQuery(
            """
            SELECT d.queue_id, MIN(k.queue_id) FROM transactions d JOIN transactions k
              ON k.queue_id < d.queue_id AND k.time_from_text = 1 AND d.time_from_text = 1
             AND k.tx_time = d.tx_time AND k.amount = d.amount AND k.direction = d.direction
             AND k.balance_after = d.balance_after AND k.account IS d.account AND k.description IS d.description
            GROUP BY d.queue_id
            """.trimIndent(),
            null,
        ).use { c -> while (c.moveToNext()) pairs.add(c.getLong(0) to c.getLong(1)) }
        for ((dup, keep) in pairs) {
            val args = arrayOf(dup.toString())
            db.execSQL(
                "INSERT OR IGNORE INTO tx_tags(queue_id, tag_id) SELECT ?, tag_id FROM tx_tags WHERE queue_id = ?",
                arrayOf<Any>(keep, dup),
            )
            db.delete("tx_tags", "queue_id = ?", args)
            db.delete("transactions", "queue_id = ?", args)
            db.delete("queue", "id = ?", args)
        }
        return pairs.size
    }

    private fun saveParsed(db: SQLiteDatabase, queueId: Long, p: Parser.Parsed, postedAt: Long) =
        saveTx(db, queueId, p.amount, p.isIn, p.balanceAfter, p.account, p.description, p.time ?: postedAt, p.time != null)

    private fun saveTx(
        db: SQLiteDatabase, queueId: Long, amount: Long, isIn: Boolean, balanceAfter: Long?, account: String?,
        description: String?, time: Long, timeFromText: Boolean,
    ) {
        val d = Instant.ofEpochMilli(time).atZone(Ledger.VN).toLocalDate()
        val cv = ContentValues().apply {
            put("queue_id", queueId)
            put("amount", amount)
            put("direction", if (isIn) "in" else "out")
            put("balance_after", balanceAfter)
            put("account", account)
            put("description", description)
            put("tx_time", time)
            put("tx_date", d.toString())
            put("tx_year", d.year)
            put("tx_month", d.monthValue)
            put("tx_day", d.dayOfMonth)
            put("time_from_text", if (timeFromText) 1 else 0)
        }
        db.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Phân tích lại toàn bộ thông báo (sau khi sửa parser hoặc người dùng bấm kiểm tra lại), xoá bản trùng rồi đối soát phí.
     * Giao dịch nhập tay giữ nguyên.
     */
    @Synchronized
    fun reparseAll(): Recheck {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transactions", "queue_id NOT IN (SELECT id FROM queue WHERE package = '$MANUAL')", null)
            db.rawQuery(
                "SELECT id, text, posted_at FROM queue WHERE status != '$DELETED' AND package != '$MANUAL'", null
            ).use { c ->
                while (c.moveToNext()) Parser.parse(c.getString(1))?.let { saveParsed(db, c.getLong(0), it, c.getLong(2)) }
            }
            val removed = dropDuplicates(db)
            val accounts = ArrayList<String>()
            db.rawQuery(
                "SELECT DISTINCT account FROM transactions WHERE account IS NOT NULL AND balance_after IS NOT NULL", null
            ).use { c -> while (c.moveToNext()) accounts.add(c.getString(0)) }
            var withFee = 0
            for (acc in accounts) {
                val c = chain(db, acc)
                for (i in c.indices) if (settle(db, c, i)) withFee++
            }
            db.setTransactionSuccessful()
            return Recheck(removed, withFee)
        } finally {
            db.endTransaction()
        }
    }

    /** Giao dịch MB có số dư của [account], theo thứ tự thời gian. */
    private fun chain(db: SQLiteDatabase, account: String): List<Link> {
        val out = ArrayList<Link>()
        db.rawQuery(
            """
            SELECT t.queue_id, t.amount, t.balance_after, q.text FROM transactions t JOIN queue q ON q.id = t.queue_id
            WHERE t.account = ? AND t.balance_after IS NOT NULL AND q.package != '$MANUAL'
            ORDER BY t.tx_time, t.queue_id
            """.trimIndent(),
            arrayOf(account),
        ).use { c -> while (c.moveToNext()) out.add(Link(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3))) }
        return out
    }

    /** amount của chain[i] = GD + phí xác nhận được bằng số dư liền trước ([Parser.fee]); true nếu có phí. */
    private fun settle(db: SQLiteDatabase, chain: List<Link>, i: Int): Boolean {
        val l = chain[i]
        val p = Parser.parse(l.text) ?: return false
        val fee = Parser.fee(p, chain.getOrNull(i - 1)?.balance)
        if (p.amount + fee != l.amount) {
            db.execSQL("UPDATE transactions SET amount = ? WHERE queue_id = ?", arrayOf<Any>(p.amount + fee, l.id))
        }
        return fee > 0
    }

    /** Thêm ([queueId] null) hoặc sửa giao dịch nhập tay (tiền mặt…); trả về queue_id. */
    fun saveManual(queueId: Long?, amount: Long, isIn: Boolean, time: Long, description: String?, tagId: Long?): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Dòng queue chỉ làm khoá cho tag/xoá; dữ liệu nằm ở transactions
            val id = queueId ?: db.insertOrThrow("queue", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("package", MANUAL)
                put("text", "")
                put("posted_at", System.currentTimeMillis())
            })
            saveTx(db, id, amount, isIn, null, null, description, time, true)
            setTag(id, tagId)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /** Giao dịch của [pkg] và giao dịch nhập tay, mới nhất trước. */
    fun transactions(pkg: String): List<Tx> {
        val out = ArrayList<Tx>()
        readableDatabase.rawQuery(
            """
            SELECT t.queue_id, t.amount, t.direction, t.balance_after, t.account, t.description,
                   t.tx_time, t.tx_year, t.tx_month, t.tx_day, t.time_from_text, g.id, g.name, g.color,
                   q.package = '$MANUAL'
            FROM transactions t JOIN queue q ON q.id = t.queue_id
            LEFT JOIN tx_tags tt ON tt.queue_id = t.queue_id
            LEFT JOIN tags g ON g.id = tt.tag_id
            WHERE q.package IN (?, '$MANUAL')
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
                        tag = if (c.isNull(11)) null else Tag(c.getLong(11), c.getString(12), c.getInt(13)),
                        manual = c.getInt(14) == 1,
                    )
                )
            }
        }
        return out
    }

    fun brief(pkg: String, month: YearMonth): Brief {
        val db = readableDatabase
        var totalIn = 0L
        var totalOut = 0L
        db.rawQuery(
            """
            SELECT t.direction, SUM(t.amount) FROM transactions t JOIN queue q ON q.id = t.queue_id
            WHERE q.package IN (?, '$MANUAL') AND t.tx_year = ? AND t.tx_month = ? GROUP BY t.direction
            """.trimIndent(),
            arrayOf(pkg, month.year.toString(), month.monthValue.toString()),
        ).use { c ->
            while (c.moveToNext()) if (c.getString(0) == "in") totalIn = c.getLong(1) else totalOut = c.getLong(1)
        }
        // Cùng thứ tự với transactions(): số dư = giao dịch mới nhất có ghi số dư
        fun latest(col: String, extra: String): Long? = db.rawQuery(
            """
            SELECT $col FROM transactions t JOIN queue q ON q.id = t.queue_id
            WHERE q.package = ? $extra ORDER BY t.tx_time DESC, t.queue_id DESC LIMIT 1
            """.trimIndent(),
            arrayOf(pkg),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        val cash = db.rawQuery(
            """
            SELECT SUM(CASE t.direction WHEN 'in' THEN t.amount ELSE -t.amount END)
            FROM transactions t JOIN queue q ON q.id = t.queue_id WHERE q.package = '$MANUAL'
            """.trimIndent(),
            null,
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        return Brief(
            totalIn, totalOut,
            balance = latest("t.balance_after", "AND t.balance_after IS NOT NULL"),
            lastTxTime = latest("t.tx_time", ""),
            cash = cash,
        )
    }

    /** Thông báo của [pkg] không phân tích được thành giao dịch, mới nhất trước. */
    fun unparsed(pkg: String): List<Item> =
        query(
            "WHERE package = ? AND status != '$DELETED' AND id NOT IN (SELECT queue_id FROM transactions) ORDER BY posted_at DESC",
            arrayOf(pkg),
        )

    /** Xoá thông báo không phải giao dịch (OTP, quảng cáo…) cũ hơn [before]; giao dịch giữ mãi. */
    fun purgeNonTransactions(before: Long) {
        val ids = query(
            "WHERE posted_at < ? AND id NOT IN (SELECT queue_id FROM transactions)", arrayOf(before.toString())
        ).filter { !Parser.looksLikeTransaction(it.text) }.map { it.id }
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.delete("queue", "id = ?", arrayOf(id.toString()))
                db.delete("tx_tags", "queue_id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Nội dung thông báo gốc của 1 bản ghi queue. */
    fun text(id: Long): String? = query("WHERE id = ?", arrayOf(id.toString())).firstOrNull()?.text

    fun recent(limit: Int): List<Item> = query("WHERE package != '$MANUAL' ORDER BY id DESC LIMIT $limit")

    /** Đổi mỗi khi hàng đợi có bản ghi mới/bị xoá — dùng để biết khi nào cần tính lại thống kê. */
    fun dataKey(): String =
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(MAX(id), 0) FROM queue", null).use { c ->
            c.moveToFirst()
            "${c.getLong(0)}:${c.getLong(1)}"
        }

    private fun query(tail: String, args: Array<String>? = null): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.rawQuery(
            "SELECT id, package, title, text, posted_at, status = '$DELETED' FROM queue $tail", args
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Item(
                        id = c.getLong(0),
                        pkg = c.getString(1),
                        title = if (c.isNull(2)) null else c.getString(2),
                        text = c.getString(3),
                        postedAt = c.getLong(4),
                        deleted = c.getInt(5) == 1,
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------------ tag

    fun tags(): List<Tag> {
        val out = ArrayList<Tag>()
        readableDatabase.rawQuery("SELECT id, name, color FROM tags ORDER BY id", null).use { c ->
            while (c.moveToNext()) out.add(Tag(c.getLong(0), c.getString(1), c.getInt(2)))
        }
        return out
    }

    /** Thêm tag mới, màu xoay vòng theo bảng màu. null nếu trùng tên. */
    fun addTag(name: String): Tag? {
        val db = writableDatabase
        val color = TAG_COLORS[(DatabaseUtils.queryNumEntries(db, "tags") % TAG_COLORS.size).toInt()]
        val cv = ContentValues().apply {
            put("name", name.trim())
            put("color", color)
        }
        val id = db.insertWithOnConflict("tags", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        return if (id == -1L) null else Tag(id, name.trim(), color)
    }

    /** false nếu trùng tên tag khác. */
    fun renameTag(id: Long, name: String): Boolean {
        val cv = ContentValues().apply { put("name", name.trim()) }
        return writableDatabase.updateWithOnConflict("tags", cv, "id = ?", arrayOf(id.toString()), SQLiteDatabase.CONFLICT_IGNORE) > 0
    }

    /** Xoá tag; các giao dịch đang gắn tag này trở về "chưa gắn tag". */
    fun deleteTag(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("tx_tags", "tag_id = ?", arrayOf(id.toString()))
            db.delete("tags", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Gắn [tagId] cho giao dịch [queueId]; null = bỏ tag. */
    fun setTag(queueId: Long, tagId: Long?) {
        val db = writableDatabase
        if (tagId == null) {
            db.delete("tx_tags", "queue_id = ?", arrayOf(queueId.toString()))
        } else {
            db.execSQL("INSERT OR REPLACE INTO tx_tags(queue_id, tag_id) VALUES (?, ?)", arrayOf<Any>(queueId, tagId))
        }
    }

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
        /** queue.status của thông báo có giao dịch người dùng đã xoá. */
        private const val DELETED = "deleted"

        /** queue.package của giao dịch nhập tay. */
        private const val MANUAL = "manual"

        private val DEFAULT_TAGS = listOf("Ăn uống", "Đi lại", "Mua sắm", "Hoá đơn", "Nhà cửa", "Lương", "Chuyển khoản", "Khác")

        /** Đủ tương phản trên cả nền sáng và tối. */
        val TAG_COLORS = listOf(
            0xFFF97316, 0xFF0EA5E9, 0xFFEC4899, 0xFFEAB308, 0xFF8B5CF6, 0xFF22C55E, 0xFF64748B, 0xFF14B8A6, 0xFFEF4444,
        ).map { it.toInt() }

        @Volatile
        private var instance: Store? = null

        fun get(ctx: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(ctx.applicationContext).also { instance = it }
            }
    }
}

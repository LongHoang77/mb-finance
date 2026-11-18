package vn.mbfinance.tracker

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
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
class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "store.db", null, 8) {

    /** Tăng khi dữ liệu đổi mà số dòng queue không đổi (thay nội dung, tính lại) → [dataKey] đổi. */
    @Volatile
    private var revision = 0

    data class Item(
        val id: Long,
        val pkg: String,
        val title: String?,
        val text: String,
        val postedAt: Long,
        val deleted: Boolean = false, // người dùng đã xoá giao dịch này (xem [deleteTransaction])
        val dupOf: Long? = null, // bị gộp vì trùng giao dịch queue_id này (xem [splitDuplicate])
    )

    data class Source(val pkg: String, val count: Int, val lastSeen: Long)

    /** Kết quả [reparseAll]: số bản trùng đã gộp, số giao dịch có cộng phí/VAT, số giao dịch lệch số dư. */
    data class Recheck(val duplicates: Int, val withFee: Int, val gaps: Int)

    /**
     * 1 mắt xích đối soát số dư (xem [chain]): giao dịch MB có số dư, hoặc mốc số dư nhập tay ([p] null, [id] = balance_marks.id).
     * [confirmed] = phí người dùng đã xác nhận.
     */
    private data class Link(val id: Long, val time: Long, val balance: Long, val p: Parser.Parsed?, val confirmed: Long?)

    /** Mốc số dư thực tế người dùng nhập; [gap] = lệch so với mắt xích liền trước (0 khớp, null chưa có gì để so). */
    data class Mark(val id: Long, val account: String, val balance: Long, val at: Long, val gap: Long?)

    /** Đối soát 1 tháng của 1 TK: số dư đầu tháng + vào − ra (đã gồm phí) so với số dư cuối tháng. */
    data class MonthCheck(val account: String, val opening: Long?, val totalIn: Long, val totalOut: Long, val fees: Long, val closing: Long) {
        /** > 0: số dư thực tế thấp hơn tính ra, < 0: cao hơn; null = chưa có số dư trước tháng. */
        val diff get() = opening?.let { it + totalIn - totalOut - closing }
    }

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
        createBalanceCheck(db)
        createPlans(db)
        createPlanDismissed(db)
        createNotes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: thêm bảng giao dịch; dữ liệu cũ được điền khi Ledger thấy Parser.VERSION thay đổi
        if (oldVersion < 2) createTransactions(db)
        // v3: tag thu chi (cột status/server_status của queue từ thời gửi server giờ bỏ không dùng)
        if (oldVersion < 3) createTags(db)
        // v4 (dọn bản trùng) giờ do reparseAll làm khi Parser.VERSION tăng
        if (oldVersion < 5) createBalanceCheck(db)
        if (oldVersion < 6) createPlans(db)
        if (oldVersion < 7) {
            createPlanDismissed(db)
            OLD_TAG_COLORS.forEachIndexed { i, old ->
                db.execSQL("UPDATE tags SET color = ? WHERE color = ?", arrayOf<Any>(TAG_COLORS[i], old))
            }
        }
        if (oldVersion < 8) createNotes(db)
    }

    /**
     * v8: ghi chú của giao dịch (theo queue_id như tag); thông báo trùng không bỏ đi mà giữ lại (status 'dup',
     * queue.dup_of = giao dịch được giữ) để người dùng xem / tách ra; not_dup = cặp người dùng xác nhận không trùng (a < b).
     */
    private fun createNotes(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes (queue_id INTEGER PRIMARY KEY, text TEXT NOT NULL)")
        db.execSQL("ALTER TABLE queue ADD COLUMN dup_of INTEGER")
        db.execSQL("CREATE INDEX idx_queue_dup ON queue(dup_of)")
        db.execSQL("CREATE TABLE not_dup (a INTEGER NOT NULL, b INTEGER NOT NULL, PRIMARY KEY (a, b))")
    }

    /** v7: gợi ý "trả nốt khoản cọc" người dùng đã bấm "Không phải" → không hỏi lại. */
    private fun createPlanDismissed(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE plan_dismissed (queue_id INTEGER NOT NULL, plan_id INTEGER NOT NULL, PRIMARY KEY (queue_id, plan_id))")
    }

    /** v6 khoản lớn: plans (cọc/trả dần hoặc mục tiêu tiết kiệm), plan_tx gắn giao dịch theo queue_id như tag. */
    private fun createPlans(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE plans (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kind TEXT NOT NULL, " +
                "target INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE plan_tx (queue_id INTEGER PRIMARY KEY, plan_id INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_plan_tx_plan ON plan_tx(plan_id)")
    }

    /**
     * v5 đối soát số dư: transactions.fee (phần phí đã cộng vào amount), transactions.gap (lệch chưa giải thích được),
     * confirmed_fees (khoản lệch người dùng xác nhận là phí), balance_marks (số dư thực tế người dùng nhập làm mốc).
     */
    private fun createBalanceCheck(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN fee INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transactions ADD COLUMN gap INTEGER")
        db.execSQL("CREATE TABLE confirmed_fees (queue_id INTEGER PRIMARY KEY, fee INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE balance_marks (id INTEGER PRIMARY KEY AUTOINCREMENT, account TEXT NOT NULL, balance INTEGER NOT NULL, " +
                "at INTEGER NOT NULL, gap INTEGER)"
        )
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
     * trùng một giao dịch đã lưu hoặc đã xoá (xem [sameTx]). Thông báo trùng giao dịch đã lưu vẫn được ghi lại
     * (status 'dup') để người dùng xem và tách ra nếu thật ra là giao dịch khác ([splitDuplicate]).
     * Bản trùng đầy đủ hơn bản đã lưu (bản trước bị cắt) → thay nội dung bản đã lưu, trả về true.
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
            if (p != null) {
                val mine = Seen(0, body, postedAt, p)
                if (isDeleted(db, pkg, mine)) return false
                val same = findSame(db, pkg, mine)
                if (same != null) {
                    val replaced = richer(mine, same) && replaceText(db, same, title, mine)
                    cv.put("status", DUP)
                    cv.put("dup_of", same.id)
                    // Quét lại đúng thông báo đã lưu → trùng UNIQUE, không ghi thêm
                    val kept = db.insertWithOnConflict("queue", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                    if (!replaced && kept == -1L) return false
                    db.setTransactionSuccessful()
                    revision++
                    return replaced
                }
            }
            val id = db.insertWithOnConflict("queue", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            if (id == -1L) return false
            if (p != null) {
                saveParsed(db, id, p, postedAt)
                if (p.balanceAfter != null) resettle(db, p.account, p.time ?: postedAt)
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** 1 thông báo giao dịch đã đọc: dòng queue [id] (0 = chưa lưu), nội dung, giờ máy nhận và kết quả phân tích. */
    private data class Seen(val id: Long, val text: String, val postedAt: Long, val p: Parser.Parsed)

    /**
     * Cùng 1 giao dịch: đủ mọi trường và khớp ([Parser.sameTx]), hoặc 1 bản bị cắt ([Parser.partialSame])
     * nhận gần nhau / cùng giờ ghi trong text.
     */
    private fun sameTx(a: Seen, b: Seen): Boolean =
        Parser.sameTx(a.p, b.p) || (
            (Math.abs(a.postedAt - b.postedAt) <= NEAR_MS || (a.p.time != null && a.p.time == b.p.time)) &&
                Parser.partialSame(a.p, b.p)
            )

    /** [a] đầy đủ hơn [b]: đọc được nhiều trường hơn, bằng nhau thì nội dung dài hơn. */
    private fun richer(a: Seen, b: Seen): Boolean {
        val ca = Parser.completeness(a.p)
        val cb = Parser.completeness(b.p)
        return ca > cb || (ca == cb && a.text.length > b.text.length)
    }

    /** Giao dịch đã lưu (chưa xoá) trùng [s], null nếu không có. */
    private fun findSame(db: SQLiteDatabase, pkg: String, s: Seen): Seen? {
        // Ứng viên: nhận gần nhau hoặc cùng giờ ghi trong text; amount đã lưu có thể gồm phí → so trên thông báo gốc
        db.rawQuery(
            """
            SELECT q.id, q.text, q.posted_at FROM transactions t JOIN queue q ON q.id = t.queue_id
            WHERE q.package = ? AND t.direction = ? AND (q.posted_at BETWEEN ? AND ? OR t.tx_time = ?)
            """.trimIndent(),
            arrayOf(
                pkg, if (s.p.isIn) "in" else "out", (s.postedAt - NEAR_MS).toString(), (s.postedAt + NEAR_MS).toString(),
                (s.p.time ?: -1L).toString(),
            ),
        ).use { c ->
            while (c.moveToNext()) {
                val d = Parser.parse(c.getString(1)) ?: continue
                val old = Seen(c.getLong(0), c.getString(1), c.getLong(2), d)
                if (sameTx(old, s)) return old
            }
        }
        return null
    }

    /**
     * MB gửi lại / quét lại một giao dịch người dùng đã xoá → không thêm lại. Bản đã xoá là bản cụt
     * (kém đầy đủ hơn) thì không chặn: người dùng xoá bản lỗi, bản đầy đủ vẫn phải vào.
     */
    private fun isDeleted(db: SQLiteDatabase, pkg: String, s: Seen): Boolean {
        db.rawQuery("SELECT id, text, posted_at FROM queue WHERE package = ? AND status = '$DELETED'", arrayOf(pkg)).use { c ->
            while (c.moveToNext()) {
                val d = Parser.parse(c.getString(1)) ?: continue
                val old = Seen(c.getLong(0), c.getString(1), c.getLong(2), d)
                if (sameTx(old, s) && !richer(s, old)) return true
            }
        }
        return false
    }

    /** Thay nội dung giao dịch đã lưu [old] bằng bản đầy đủ hơn [new]; giữ queue_id nên giữ tag. */
    private fun replaceText(db: SQLiteDatabase, old: Seen, title: String?, new: Seen): Boolean {
        val cv = ContentValues().apply {
            put("text", new.text)
            if (title != null) put("title", title.take(500))
        }
        if (db.updateWithOnConflict("queue", cv, "id = ?", arrayOf(old.id.toString()), SQLiteDatabase.CONFLICT_IGNORE) <= 0) {
            return false
        }
        saveParsed(db, old.id, new.p, old.postedAt)
        // Bản cũ có thể đã nằm trong chuỗi số dư ở thời điểm khác (giờ máy nhận) → đối soát lại cả 2 chỗ
        if (old.p.balanceAfter != null) resettle(db, old.p.account, old.p.time ?: old.postedAt)
        if (new.p.balanceAfter != null) resettle(db, new.p.account, new.p.time ?: old.postedAt)
        return true
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
            val at = position(db, queueId)
            // Bản trùng đã gộp vào nó cũng coi như xoá (không thì "kiểm tra lại" sẽ đưa chúng trở lại)
            db.execSQL("UPDATE queue SET status = '$DELETED' WHERE id = ? OR (dup_of = ? AND status = '$DUP')", arrayOf<Any>(queueId, queueId))
            for (table in listOf("transactions", "tx_tags", "confirmed_fees", "plan_tx", "plan_dismissed", "notes")) {
                db.delete(table, "queue_id = ?", args)
            }
            // Giao dịch sau nó đổi số dư liền trước → đối soát lại
            if (at != null) resettle(db, at.first, at.second)
            db.setTransactionSuccessful()
            revision++
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Người dùng xác nhận khoản số dư giảm thêm ngoài GD của giao dịch ra [queueId] là phí ([confirm] false = bỏ).
     * Phí xác nhận chỉ được cộng khi khoản lệch vẫn đúng bằng nó (bắt được giao dịch bị sót thì tự hết hiệu lực).
     */
    fun confirmFee(queueId: Long, confirm: Boolean) {
        val db = writableDatabase
        val args = arrayOf(queueId.toString())
        db.beginTransaction()
        try {
            val at = position(db, queueId) ?: return
            if (confirm) {
                // phí = phần đã cộng (nếu có) + phần còn lệch
                val fee = db.rawQuery("SELECT fee + gap FROM transactions WHERE queue_id = ? AND gap IS NOT NULL", args)
                    .use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                if (fee == null || fee <= 0) return
                db.execSQL("INSERT OR REPLACE INTO confirmed_fees(queue_id, fee) VALUES (?, ?)", arrayOf<Any>(queueId, fee))
            } else {
                db.delete("confirmed_fees", "queue_id = ?", args)
            }
            resettle(db, at.first, at.second)
            db.setTransactionSuccessful()
            revision++
        } finally {
            db.endTransaction()
        }
    }

    /** Lưu mốc số dư thực tế của [account] tại [at] (sau mọi giao dịch đến lúc đó). */
    fun addMark(account: String, balance: Long, at: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO balance_marks(account, balance, at) VALUES (?, ?, ?)", arrayOf<Any>(account, balance, at))
            resettle(db, account, at)
            db.setTransactionSuccessful()
            revision++
        } finally {
            db.endTransaction()
        }
    }

    fun deleteMark(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val at = db.rawQuery("SELECT account, at FROM balance_marks WHERE id = ?", arrayOf(id.toString()))
                .use { c -> if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null } ?: return
            db.delete("balance_marks", "id = ?", arrayOf(id.toString()))
            resettle(db, at.first, at.second)
            db.setTransactionSuccessful()
            revision++
        } finally {
            db.endTransaction()
        }
    }

    /** Mốc số dư, mới nhất trước. */
    fun marks(limit: Int): List<Mark> {
        val out = ArrayList<Mark>()
        readableDatabase.rawQuery("SELECT id, account, balance, at, gap FROM balance_marks ORDER BY at DESC, id DESC LIMIT $limit", null)
            .use { c ->
                while (c.moveToNext()) {
                    out.add(Mark(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), if (c.isNull(4)) null else c.getLong(4)))
                }
            }
        return out
    }

    /** TK có giao dịch ghi số dư của [pkg], TK có giao dịch gần nhất trước. */
    fun accounts(pkg: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            """
            SELECT t.account FROM transactions t JOIN queue q ON q.id = t.queue_id
            WHERE q.package = ? AND t.account IS NOT NULL AND t.balance_after IS NOT NULL
            GROUP BY t.account ORDER BY MAX(t.tx_time) DESC
            """.trimIndent(),
            arrayOf(pkg),
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /**
     * Đối soát theo tháng cho từng TK: số dư đầu tháng = mắt xích cuối trước tháng (giao dịch hoặc mốc nhập tay),
     * cuối tháng = mắt xích cuối trong tháng, vào/ra = mọi giao dịch MB của TK trong tháng (kể cả không ghi số dư).
     * Lệch ≠ 0 khi có giao dịch trùng/sót hoặc phí chưa tính.
     */
    fun monthChecks(pkg: String): Map<YearMonth, List<MonthCheck>> {
        val db = readableDatabase
        val accounts = accounts(pkg)
        val out = HashMap<YearMonth, MutableList<MonthCheck>>()
        for (acc in accounts) {
            val flows = HashMap<YearMonth, LongArray>() // vào, ra, phí
            // Giao dịch không ghi TK vẫn làm đổi số dư; chỉ gán được khi có 1 TK
            val accCond = if (accounts.size == 1) "(t.account = ? OR t.account IS NULL)" else "t.account = ?"
            db.rawQuery(
                """
                SELECT t.tx_year, t.tx_month, t.direction, SUM(t.amount), SUM(t.fee) FROM transactions t
                JOIN queue q ON q.id = t.queue_id WHERE q.package = ? AND $accCond
                GROUP BY t.tx_year, t.tx_month, t.direction
                """.trimIndent(),
                arrayOf(pkg, acc),
            ).use { c ->
                while (c.moveToNext()) {
                    val f = flows.getOrPut(YearMonth.of(c.getInt(0), c.getInt(1))) { LongArray(3) }
                    f[if (c.getString(2) == "in") 0 else 1] += c.getLong(3)
                    f[2] += c.getLong(4)
                }
            }
            var before: Long? = null
            for ((m, links) in chain(db, acc).groupBy { Ledger.monthOf(it.time) }) {
                val f = flows[m] ?: LongArray(3)
                val closing = links.last().balance
                out.getOrPut(m) { ArrayList() }.add(MonthCheck(acc, before, f[0], f[1], f[2], closing))
                before = closing
            }
        }
        return out
    }

    /** (TK, tx_time) của giao dịch có số dư [queueId]; null nếu không nằm trong chuỗi đối soát. */
    private fun position(db: SQLiteDatabase, queueId: Long): Pair<String, Long>? =
        db.rawQuery(
            "SELECT account, tx_time FROM transactions WHERE queue_id = ? AND account IS NOT NULL AND balance_after IS NOT NULL",
            arrayOf(queueId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null }

    /**
     * Gộp thông báo trùng giao dịch đã có ([sameTx], kể cả bản bị cắt): giữ dòng lưu trước (giữ tag) nhưng lấy
     * nội dung đầy đủ hơn; tag/khoản/ghi chú của bản bị gộp chuyển sang bản giữ lại nếu bản đó chưa có.
     * Bản bị gộp thành 'dup' (dup_of = bản giữ lại), không xoá; cặp người dùng đã tách (bảng not_dup) không gộp.
     * Giao dịch đã xoá không tính: người dùng có thể đã tự xoá bản trùng, bản còn lại là giao dịch thật.
     * Trả về số bản đã gộp.
     */
    private fun dropDuplicates(db: SQLiteDatabase): Int {
        data class Row(val pkg: String, val seen: Seen)
        val rows = ArrayList<Row>()
        db.rawQuery(
            """
            SELECT q.id, q.package, q.text, q.posted_at FROM transactions t JOIN queue q ON q.id = t.queue_id
            WHERE q.package != '$MANUAL' ORDER BY q.posted_at, q.id
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val p = Parser.parse(c.getString(2)) ?: continue
                rows.add(Row(c.getString(1), Seen(c.getLong(0), c.getString(2), c.getLong(3), p)))
            }
        }
        val apart = apart(db)
        fun differ(a: Seen, b: Seen) = (minOf(a.id, b.id) to maxOf(a.id, b.id)) in apart
        var removed = 0
        for (group in rows.groupBy { listOf(it.pkg, it.seen.p.amount, it.seen.p.isIn) }.values) {
            // kept[i] = (dòng giữ lại, nội dung tốt nhất của nhóm trùng đó)
            val kept = ArrayList<Pair<Seen, Seen>>()
            for (r in group) {
                val i = kept.indexOfFirst { (keep, best) ->
                    !differ(keep, r.seen) && !differ(best, r.seen) && (sameTx(best, r.seen) || sameTx(keep, r.seen))
                }
                if (i < 0) {
                    kept.add(r.seen to r.seen)
                    continue
                }
                val (keep, best) = kept[i]
                val args = arrayOf(r.seen.id.toString())
                db.execSQL(
                    "INSERT OR IGNORE INTO tx_tags(queue_id, tag_id) SELECT ?, tag_id FROM tx_tags WHERE queue_id = ?",
                    arrayOf<Any>(keep.id, r.seen.id),
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO plan_tx(queue_id, plan_id) SELECT ?, plan_id FROM plan_tx WHERE queue_id = ?",
                    arrayOf<Any>(keep.id, r.seen.id),
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO notes(queue_id, text) SELECT ?, text FROM notes WHERE queue_id = ?",
                    arrayOf<Any>(keep.id, r.seen.id),
                )
                for (table in listOf("tx_tags", "confirmed_fees", "plan_tx", "plan_dismissed", "notes", "transactions")) {
                    db.delete(table, "queue_id = ?", args)
                }
                db.execSQL("UPDATE queue SET status = '$DUP', dup_of = ? WHERE id = ?", arrayOf<Any>(keep.id, r.seen.id))
                removed++
                if (richer(r.seen, best)) kept[i] = keep to r.seen
            }
            for ((keep, best) in kept) {
                if (best.id == keep.id) continue
                val cv = ContentValues().apply { put("text", best.text) }
                if (db.updateWithOnConflict("queue", cv, "id = ?", arrayOf(keep.id.toString()), SQLiteDatabase.CONFLICT_IGNORE) > 0) {
                    saveParsed(db, keep.id, best.p, keep.postedAt)
                }
            }
        }
        return removed
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
     * Phân tích lại toàn bộ thông báo (sau khi sửa parser hoặc người dùng bấm kiểm tra lại), xoá bản trùng rồi đối soát
     * số dư (phí, lệch). Giao dịch nhập tay và phí người dùng đã xác nhận giữ nguyên.
     */
    @Synchronized
    fun reparseAll(): Recheck {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transactions", "queue_id NOT IN (SELECT id FROM queue WHERE package = '$MANUAL')", null)
            // Bản trùng được xét lại từ đầu (parser có thể đã đổi); dropDuplicates đánh dấu lại
            val dupIds = { db.rawQuery("SELECT id FROM queue WHERE status = '$DUP'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } } }
            val wasDup = dupIds()
            db.execSQL("UPDATE queue SET status = 'pending', dup_of = NULL WHERE status = '$DUP'")
            db.rawQuery(
                "SELECT id, text, posted_at FROM queue WHERE status != '$DELETED' AND package != '$MANUAL'", null
            ).use { c ->
                while (c.moveToNext()) Parser.parse(c.getString(1))?.let { saveParsed(db, c.getLong(0), it, c.getLong(2)) }
            }
            dropDuplicates(db)
            // Chỉ báo bản trùng mới gộp lần này
            val removed = (dupIds() - wasDup).size
            val accounts = ArrayList<String>()
            db.rawQuery(
                "SELECT DISTINCT account FROM transactions WHERE account IS NOT NULL AND balance_after IS NOT NULL", null
            ).use { c -> while (c.moveToNext()) accounts.add(c.getString(0)) }
            var withFee = 0
            for (acc in accounts) {
                val c = chain(db, acc)
                for (i in c.indices) if (settle(db, c, i) > 0) withFee++
            }
            val gaps = DatabaseUtils.queryNumEntries(db, "transactions", "gap IS NOT NULL").toInt()
            db.setTransactionSuccessful()
            revision++
            return Recheck(removed, withFee, gaps)
        } finally {
            db.endTransaction()
        }
    }

    /** Giao dịch MB có số dư và mốc số dư nhập tay của [account] trong [[from], [to]], theo thứ tự diễn ra. */
    private fun chain(db: SQLiteDatabase, account: String, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): List<Link> {
        val rows = ArrayList<Link>()
        val range = arrayOf(account, from.toString(), to.toString())
        db.rawQuery(
            """
            SELECT t.queue_id, t.tx_time, t.balance_after, q.text, f.fee FROM transactions t
            JOIN queue q ON q.id = t.queue_id LEFT JOIN confirmed_fees f ON f.queue_id = t.queue_id
            WHERE t.account = ? AND t.balance_after IS NOT NULL AND q.package != '$MANUAL' AND t.tx_time BETWEEN ? AND ?
            ORDER BY t.tx_time, t.queue_id
            """.trimIndent(),
            range,
        ).use { c ->
            while (c.moveToNext()) {
                val p = Parser.parse(c.getString(3)) ?: continue
                rows.add(Link(c.getLong(0), c.getLong(1), c.getLong(2), p, if (c.isNull(4)) null else c.getLong(4)))
            }
        }
        db.rawQuery("SELECT id, at, balance FROM balance_marks WHERE account = ? AND at BETWEEN ? AND ?", range).use { c ->
            while (c.moveToNext()) rows.add(Link(c.getLong(0), c.getLong(1), c.getLong(2), null, null))
        }
        rows.sortBy { it.time } // ổn định: cùng thời điểm thì mốc nhập tay đứng sau giao dịch
        // Giờ trong thông báo chỉ tới phút, quét bù có thể lưu ngược thứ tự → giao dịch cùng phút xếp theo số dư nối tiếp
        val out = ArrayList<Link>(rows.size)
        for (run in rows.groupBy { it.time }.values) {
            val left = run.toMutableList()
            while (left.isNotEmpty()) {
                val prev = out.lastOrNull()?.balance
                val next = left.firstOrNull { prev != null && follows(prev, it) }
                    ?: left.firstOrNull { x -> left.none { it !== x && follows(it.balance, x) } }
                    ?: left.first()
                out.add(next)
                left.remove(next)
            }
        }
        return out
    }

    /** [l] nối tiếp số dư [prev]: giao dịch khớp đúng GD hoặc GD + phí trong thông báo; mốc nhập tay bằng đúng số dư. */
    private fun follows(prev: Long, l: Link): Boolean {
        val p = l.p ?: return prev == l.balance
        return (if (p.isIn) prev + p.amount else prev - p.amount) == l.balance || Parser.fee(p, prev) > 0
    }

    /**
     * Đối soát chain[i] với số dư liền trước: amount = GD + phí (xác nhận tay hoặc [Parser.fee]),
     * gap = phần lệch còn lại (> 0: số dư thấp hơn dự tính — phí không ghi / sót giao dịch ra; < 0: sót giao dịch vào).
     * Mốc nhập tay chỉ ghi lệch của nó. Trả về phí.
     */
    private fun settle(db: SQLiteDatabase, chain: List<Link>, i: Int): Long {
        val l = chain[i]
        val prev = chain.getOrNull(i - 1)?.balance
        val p = l.p
        if (p == null) {
            db.execSQL("UPDATE balance_marks SET gap = ? WHERE id = ?", arrayOf<Any?>(prev?.let { it - l.balance }, l.id))
            return 0
        }
        val diff = prev?.let { (if (p.isIn) it + p.amount else it - p.amount) - l.balance }
        val fee = l.confirmed?.takeIf { !p.isIn && it == diff } ?: Parser.fee(p, prev)
        val gap = diff?.minus(fee)?.takeIf { it != 0L }
        db.execSQL(
            "UPDATE transactions SET amount = ?, fee = ?, gap = ? WHERE queue_id = ?",
            arrayOf<Any?>(p.amount + fee, fee, gap, l.id),
        )
        return fee
    }

    /**
     * Đối soát lại các mắt xích cùng thời điểm [time] và lượt kế tiếp (số dư liền trước của chúng có thể vừa đổi),
     * chỉ đọc quanh [time] thay vì cả TK vì chạy mỗi khi có thông báo.
     */
    private fun resettle(db: SQLiteDatabase, account: String?, time: Long) {
        if (account == null) return
        val links = """
            SELECT t.tx_time AS x FROM transactions t JOIN queue q ON q.id = t.queue_id
            WHERE t.account = ? AND t.balance_after IS NOT NULL AND q.package != '$MANUAL'
            UNION ALL SELECT at FROM balance_marks WHERE account = ?
        """.trimIndent()
        fun bound(sql: String): Long? = db.rawQuery(sql, arrayOf(account, account, time.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
        val from = bound("SELECT MAX(x) FROM ($links) WHERE x < ?") ?: time
        val to = bound("SELECT MIN(x) FROM ($links) WHERE x > ?") ?: time
        val c = chain(db, account, from, to)
        for (i in c.indices) if (c[i].time >= time) settle(db, c, i)
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
                   q.package = '$MANUAL', t.gap, f.queue_id IS NOT NULL, t.fee,
                   p.id, p.name, p.kind, p.target, p.done, n.text,
                   (SELECT COUNT(*) FROM queue d WHERE d.dup_of = t.queue_id AND d.status = '$DUP')
            FROM transactions t JOIN queue q ON q.id = t.queue_id
            LEFT JOIN tx_tags tt ON tt.queue_id = t.queue_id
            LEFT JOIN tags g ON g.id = tt.tag_id
            LEFT JOIN confirmed_fees f ON f.queue_id = t.queue_id
            LEFT JOIN plan_tx pt ON pt.queue_id = t.queue_id
            LEFT JOIN plans p ON p.id = pt.plan_id
            LEFT JOIN notes n ON n.queue_id = t.queue_id
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
                        gap = if (c.isNull(15)) null else c.getLong(15),
                        feeConfirmed = c.getInt(16) == 1,
                        fee = c.getLong(17),
                        plan = if (c.isNull(18)) null else plan(c, 18),
                        note = if (c.isNull(23)) null else c.getString(23),
                        dups = c.getInt(24),
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
            WHERE q.package IN (?, '$MANUAL') AND t.tx_year = ? AND t.tx_month = ?
              AND NOT (t.direction = 'out' AND t.queue_id IN ($SAVING_TX))
            GROUP BY t.direction
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
            "WHERE package = ? AND status NOT IN ('$DELETED', '$DUP') AND id NOT IN (SELECT queue_id FROM transactions) ORDER BY posted_at DESC",
            arrayOf(pkg),
        )

    /** Xoá thông báo không phải giao dịch (OTP, quảng cáo…) cũ hơn [before]; giao dịch giữ mãi. */
    fun purgeNonTransactions(before: Long) {
        val ids = query(
            "WHERE posted_at < ? AND status != '$DUP' AND id NOT IN (SELECT queue_id FROM transactions)", arrayOf(before.toString())
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

    /** Thông báo đã lưu (không gồm giao dịch nhập tay) nhận trong [[from], [to]), mới nhất trước. */
    fun notifications(from: Long, to: Long): List<Item> =
        query(
            "WHERE package != '$MANUAL' AND posted_at >= ? AND posted_at < ? ORDER BY posted_at DESC",
            arrayOf(from.toString(), to.toString()),
        )

    /** Đổi mỗi khi hàng đợi có bản ghi mới/bị xoá hoặc dữ liệu được tính lại — dùng để biết khi nào cần tính lại thống kê. */
    fun dataKey(): String =
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(MAX(id), 0) FROM queue", null).use { c ->
            c.moveToFirst()
            "${c.getLong(0)}:${c.getLong(1)}:$revision"
        }

    private fun query(tail: String, args: Array<String>? = null): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.rawQuery(
            "SELECT id, package, title, text, posted_at, status = '$DELETED', CASE status WHEN '$DUP' THEN dup_of END FROM queue $tail",
            args,
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
                        dupOf = if (c.isNull(6)) null else c.getLong(6),
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------------ bản trùng + ghi chú

    /** Cặp (a < b) người dùng đã xác nhận là 2 giao dịch khác nhau. */
    private fun apart(db: SQLiteDatabase): Set<Pair<Long, Long>> {
        val out = HashSet<Pair<Long, Long>>()
        db.rawQuery("SELECT a, b FROM not_dup", null).use { c -> while (c.moveToNext()) out.add(c.getLong(0) to c.getLong(1)) }
        return out
    }

    /** Thông báo đã gộp vào giao dịch [queueId] (coi là trùng), cũ nhất trước. */
    fun duplicates(queueId: Long): List<Item> =
        query("WHERE status = '$DUP' AND dup_of = ? ORDER BY posted_at, id", arrayOf(queueId.toString()))

    /** Mọi thông báo đã gộp vì trùng, mới nhất trước. */
    fun duplicates(): List<Item> = query("WHERE status = '$DUP' ORDER BY posted_at DESC, id DESC")

    /**
     * Người dùng xác nhận thông báo trùng [id] thật ra là giao dịch khác: thành giao dịch riêng, ghi nhớ cặp
     * để "kiểm tra lại" không gộp lại. false nếu không còn là bản trùng / không đọc được.
     */
    fun splitDuplicate(id: Long): Boolean {
        val item = query("WHERE id = ? AND status = '$DUP'", arrayOf(id.toString())).firstOrNull() ?: return false
        val of = item.dupOf ?: return false
        val p = Parser.parse(item.text) ?: return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE queue SET status = 'pending', dup_of = NULL WHERE id = ?", arrayOf<Any>(id))
            db.execSQL("INSERT OR IGNORE INTO not_dup(a, b) VALUES (?, ?)", arrayOf<Any>(minOf(id, of), maxOf(id, of)))
            saveParsed(db, id, p, item.postedAt)
            if (p.balanceAfter != null) resettle(db, p.account, p.time ?: item.postedAt)
            db.setTransactionSuccessful()
            revision++
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** Ghi chú của giao dịch [queueId] (vd "MB không ghi phí VAT 1.100đ"); rỗng = xoá ghi chú. */
    fun setNote(queueId: Long, text: String) {
        val t = text.trim().take(NOTE_MAX)
        if (t.isEmpty()) {
            writableDatabase.delete("notes", "queue_id = ?", arrayOf(queueId.toString()))
        } else {
            writableDatabase.execSQL("INSERT OR REPLACE INTO notes(queue_id, text) VALUES (?, ?)", arrayOf<Any>(queueId, t))
        }
        revision++
    }

    // ------------------------------------------------------------------ khoản lớn (cọc / tiết kiệm)

    /** Đọc Plan từ 5 cột id, name, kind, target, done bắt đầu ở [i]. */
    private fun plan(c: Cursor, i: Int) =
        Plan(c.getLong(i), c.getString(i + 1), c.getString(i + 2) == SAVE, c.getLong(i + 3), c.getInt(i + 4) == 1)

    /** Mọi khoản: đang mở trước, mới tạo trước. */
    fun plans(): List<Plan> {
        val out = ArrayList<Plan>()
        readableDatabase.rawQuery("SELECT id, name, kind, target, done FROM plans ORDER BY done, created_at DESC, id DESC", null)
            .use { c -> while (c.moveToNext()) out.add(plan(c, 0)) }
        return out
    }

    fun addPlan(name: String, saving: Boolean, target: Long): Plan {
        val cv = ContentValues().apply {
            put("name", name.trim())
            put("kind", if (saving) SAVE else PAY)
            put("target", target)
            put("created_at", System.currentTimeMillis())
        }
        val id = writableDatabase.insertOrThrow("plans", null, cv)
        revision++
        return Plan(id, name.trim(), saving, target, false)
    }

    fun updatePlan(id: Long, name: String, saving: Boolean, target: Long) {
        val cv = ContentValues().apply {
            put("name", name.trim())
            put("kind", if (saving) SAVE else PAY)
            put("target", target)
        }
        writableDatabase.update("plans", cv, "id = ?", arrayOf(id.toString()))
        revision++
    }

    fun setPlanDone(id: Long, done: Boolean) {
        writableDatabase.execSQL("UPDATE plans SET done = ? WHERE id = ?", arrayOf<Any>(if (done) 1 else 0, id))
        revision++
    }

    /** Xoá khoản; giao dịch đã gắn vẫn giữ, chỉ gỡ liên kết. */
    fun deletePlan(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("plan_tx", "plan_dismissed")) db.delete(table, "plan_id = ?", arrayOf(id.toString()))
            db.delete("plans", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            revision++
        } finally {
            db.endTransaction()
        }
    }

    /** Gắn giao dịch [queueId] vào khoản [planId] (null = gỡ); khoản cọc trả đủ tổng giá thì tự hoàn tất. */
    fun setPlan(queueId: Long, planId: Long?) {
        val db = writableDatabase
        if (planId == null) {
            db.delete("plan_tx", "queue_id = ?", arrayOf(queueId.toString()))
        } else {
            db.execSQL("INSERT OR REPLACE INTO plan_tx(queue_id, plan_id) VALUES (?, ?)", arrayOf<Any>(queueId, planId))
            db.execSQL(
                """
                UPDATE plans SET done = 1 WHERE id = ? AND kind = '$PAY' AND target <= (
                  SELECT COALESCE(SUM(t.amount - t.fee), 0) FROM plan_tx pt JOIN transactions t ON t.queue_id = pt.queue_id
                  WHERE pt.plan_id = ? AND t.direction = 'out'
                )
                """.trimIndent(),
                arrayOf<Any>(planId, planId),
            )
        }
        revision++
    }

    /** Người dùng xác nhận giao dịch [queueId] không phải lần trả nốt khoản cọc [planId]. */
    fun dismissSuggestion(queueId: Long, planId: Long) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO plan_dismissed(queue_id, plan_id) VALUES (?, ?)", arrayOf<Any>(queueId, planId))
        revision++
    }

    /** Các cặp (queue_id, plan_id) đã bấm "Không phải". */
    fun dismissed(): Set<Pair<Long, Long>> {
        val out = HashSet<Pair<Long, Long>>()
        readableDatabase.rawQuery("SELECT queue_id, plan_id FROM plan_dismissed", null)
            .use { c -> while (c.moveToNext()) out.add(c.getLong(0) to c.getLong(1)) }
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

    fun recolorTag(id: Long, color: Int) {
        writableDatabase.execSQL("UPDATE tags SET color = ? WHERE id = ?", arrayOf<Any>(color, id))
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

        /** queue.status của thông báo trùng 1 giao dịch đã lưu (queue.dup_of), không thành giao dịch riêng. */
        private const val DUP = "dup"

        /** Độ dài tối đa của ghi chú giao dịch. */
        const val NOTE_MAX = 500

        /** queue.package của giao dịch nhập tay. */
        private const val MANUAL = "manual"

        /** plans.kind: mục tiêu tiết kiệm / đặt cọc, trả dần. */
        private const val SAVE = "save"
        private const val PAY = "pay"

        /** queue_id của giao dịch gắn vào mục tiêu tiết kiệm (tách khỏi thu/chi). */
        private const val SAVING_TX = "SELECT pt.queue_id FROM plan_tx pt JOIN plans p ON p.id = pt.plan_id WHERE p.kind = '$SAVE'"

        /** 2 thông báo cùng giao dịch (1 bản bị cắt) nhận cách nhau tối đa chừng này. */
        private const val NEAR_MS = 10 * 60_000L

        private val DEFAULT_TAGS = listOf("Ăn uống", "Đi lại", "Mua sắm", "Hoá đơn", "Nhà cửa", "Lương", "Chuyển khoản", "Khác")

        /** Bảng màu tag (đã kiểm tra tương phản/mù màu trên nền sáng và tối); xám dành cho "chưa gắn tag". */
        val TAG_COLORS = listOf(
            0xFFEA580C, 0xFF0284C7, 0xFFDB2777, 0xFFA16207, 0xFF7C3AED, 0xFF16A34A, 0xFF4F46E5, 0xFF0D9488, 0xFFDC2626,
        ).map { it.toInt() }

        /** Bảng màu tag trước v7, cùng thứ tự với [TAG_COLORS] → đổi màu tag cũ khi nâng cấp. */
        private val OLD_TAG_COLORS = listOf(
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

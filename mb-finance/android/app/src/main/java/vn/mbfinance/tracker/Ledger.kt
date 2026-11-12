package vn.mbfinance.tracker

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 1 dòng của bảng transactions. [date] là ngày giao dịch theo giờ VN. */
data class Tx(
    val id: Long,
    val amount: Long,
    val isIn: Boolean,
    val balanceAfter: Long?,
    val account: String?,
    val description: String?,
    val time: Long,
    val date: LocalDate,
    val timeFromText: Boolean,
    val tag: Tag?,
    val manual: Boolean = false, // nhập tay (tiền mặt…), không từ thông báo MB
    val gap: Long? = null, // lệch số dư so với giao dịch liền trước (> 0: thiếu, < 0: thừa), xem Store.settle
    val feeConfirmed: Boolean = false, // người dùng đã xác nhận khoản lệch là phí
    val fee: Long = 0, // phần phí/VAT đã cộng vào amount
    val plan: Plan? = null, // khoản cọc/trả dần hoặc mục tiêu tiết kiệm đang gắn
) {
    /** Tiền ra chuyển tiết kiệm: không phải chi tiêu, tách khỏi thống kê thu/chi. */
    val saving get() = !isIn && plan?.saving == true
}

/** Tag thu chi do người dùng tự đặt; [color] là ARGB. */
data class Tag(val id: Long, val name: String, val color: Int)

/** Khoản lớn: [saving] = mục tiêu tiết kiệm, ngược lại = đặt cọc / trả dần; [target] = số tiền cần có / tổng giá. */
data class Plan(val id: Long, val name: String, val saving: Boolean, val target: Long, val done: Boolean)

/** Sổ giao dịch đọc từ bảng transactions trên máy (không cần server). */
class Ledger(
    val txs: List<Tx>,
    val unparsed: List<Store.Item>,
    val checks: Map<YearMonth, List<Store.MonthCheck>> = emptyMap(), // đối soát số dư theo tháng, mỗi TK 1 dòng
    val plans: List<Plan> = emptyList(), // đang mở trước, mới tạo trước
    private val dismissed: Set<Pair<Long, Long>> = emptySet(), // (tx, khoản cọc) đã bấm "Không phải"
) {

    data class MonthSum(
        val month: YearMonth,
        val totalIn: Long,
        val totalOut: Long,
        val countIn: Int,
        val countOut: Int,
        val saved: Long = 0, // chuyển tiết kiệm, không tính vào totalOut
    ) {
        val net get() = totalIn - totalOut
    }

    private val byMonth = txs.groupBy { YearMonth.from(it.date) }

    private val byPlan = txs.filter { it.plan != null }.groupBy { it.plan!!.id }

    /** Số dư theo thông báo mới nhất có ghi số dư. */
    val latestBalance: Long? = txs.firstOrNull { it.balanceAfter != null }?.balanceAfter

    private val manual = txs.filter { it.manual }.sortedByDescending { it.id } // nhập gần nhất trước

    val hasCash get() = manual.isNotEmpty()

    /** Tiền mặt còn lại = tiền vào − tiền ra nhập tay, mọi thời gian. */
    val cashBalance: Long = manual.sumOf { if (it.isIn) it.amount else -it.amount }

    /** Tag của giao dịch tay nhập gần nhất (mặc định cho form). */
    val lastManualTag: Tag? get() = manual.firstOrNull()?.tag

    /** Mẫu nhập nhanh: giao dịch tay dùng nhiều nhất (cùng nội dung, số tiền, chiều, tag); bằng nhau thì mới hơn trước. */
    fun templates(n: Int): List<Tx> =
        manual.filter { it.description != null }.take(100)
            .groupBy { listOf(it.description, it.amount, it.isIn, it.tag?.id) }
            .values.sortedByDescending { it.size }.map { it.first() }.take(n)

    fun inMonth(m: YearMonth): List<Tx> = byMonth[m].orEmpty()

    /** Giao dịch thu/chi thật trong tháng (bỏ tiền chuyển tiết kiệm). */
    fun spending(m: YearMonth): List<Tx> = inMonth(m).filter { !it.saving }

    fun sum(m: YearMonth): MonthSum {
        val (ins, outs) = spending(m).partition { it.isIn }
        val saved = inMonth(m).filter { it.saving }.sumOf { it.amount }
        return MonthSum(m, ins.sumOf { it.amount }, outs.sumOf { it.amount }, ins.size, outs.size, saved)
    }

    /** Giao dịch đã gắn vào [p], mới nhất trước. */
    fun txsOf(p: Plan): List<Tx> = byPlan[p.id].orEmpty()

    /** Đã trả / đã để dành cho [p]: tổng tiền ra đã gắn, không tính phí. */
    fun paid(p: Plan): Long = txsOf(p).filter { !it.isIn }.sumOf { it.amount - it.fee }

    /** Giao dịch cọc = lần trả đầu tiên của mỗi khoản cọc; các lần sau là "trả". */
    val depositIds: Set<Long> = plans.filter { !it.saving }.mapNotNull { p -> txsOf(p).minByOrNull { it.time }?.id }.toSet()

    /**
     * Gợi ý trả nốt: tiền ra chưa gắn khoản, sau lần cọc, đúng bằng số còn phải trả của 1 khoản cọc đang mở
     * (trừ cặp người dùng đã bấm "Không phải"). tx id → khoản.
     */
    val suggestions: Map<Long, Plan> = buildMap {
        for (p in plans) {
            if (p.saving || p.done) continue
            val linked = txsOf(p)
            val since = linked.minOfOrNull { it.time } ?: continue
            val left = p.target - paid(p)
            if (left <= 0) continue
            for (t in txs) {
                if (t.isIn || t.plan != null || t.time <= since || t.amount - t.fee != left) continue
                if ((t.id to p.id) in dismissed || containsKey(t.id)) continue
                put(t.id, p)
            }
        }
    }

    /** Tổng tiền vào ([isIn]) hoặc ra theo tag trong tháng, lớn nhất trước; tag null = chưa gắn. */
    fun byTag(m: YearMonth, isIn: Boolean): List<Pair<Tag?, Long>> =
        spending(m).filter { it.isIn == isIn }
            .groupBy { it.tag }
            .map { (tag, list) -> tag to list.sumOf { it.amount } }
            .sortedByDescending { it.second }

    /** [n] tháng liên tiếp, kết thúc ở [end]. */
    fun lastMonths(end: YearMonth, n: Int) = (n - 1 downTo 0).map { sum(end.minusMonths(it.toLong())) }

    companion object {
        val VN: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
        val EMPTY = Ledger(emptyList(), emptyList())

        /** Thông báo không phải giao dịch được giữ 7 ngày. */
        private const val KEEP_OTHER_MS = 7L * 24 * 60 * 60 * 1000

        fun monthOf(ms: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(ms).atZone(VN))

        /** Trước khi đọc: phân tích lại nếu Parser đổi phiên bản, xoá thông báo không phải giao dịch quá 7 ngày. */
        fun prepare(ctx: Context) {
            val store = Store.get(ctx)
            val settings = Settings(ctx)
            synchronized(this) {
                if (settings.parserVersion != Parser.VERSION) {
                    store.reparseAll()
                    settings.parserVersion = Parser.VERSION
                }
                store.purgeNonTransactions(System.currentTimeMillis() - KEEP_OTHER_MS)
            }
        }

        fun load(ctx: Context): Ledger {
            prepare(ctx)
            val store = Store.get(ctx)
            val pkg = Settings(ctx).mbPackage
            // Bỏ OTP/quảng cáo đã lưu từ bản cũ, chỉ giữ mẫu giao dịch parser chưa đọc được
            return Ledger(
                store.transactions(pkg),
                store.unparsed(pkg).filter { Parser.looksLikeTransaction(it.text) },
                store.monthChecks(pkg),
                store.plans(),
                store.dismissed(),
            )
        }
    }
}

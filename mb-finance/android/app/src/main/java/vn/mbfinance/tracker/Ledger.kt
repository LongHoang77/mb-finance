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
)

/** Tag thu chi do người dùng tự đặt; [color] là ARGB. */
data class Tag(val id: Long, val name: String, val color: Int)

/** Sổ giao dịch đọc từ bảng transactions trên máy (không cần server). */
class Ledger(
    val txs: List<Tx>,
    val unparsed: List<Store.Item>,
    val checks: Map<YearMonth, List<Store.MonthCheck>> = emptyMap(), // đối soát số dư theo tháng, mỗi TK 1 dòng
) {

    data class MonthSum(
        val month: YearMonth,
        val totalIn: Long,
        val totalOut: Long,
        val countIn: Int,
        val countOut: Int,
    ) {
        val net get() = totalIn - totalOut
    }

    private val byMonth = txs.groupBy { YearMonth.from(it.date) }

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

    fun sum(m: YearMonth): MonthSum {
        val (ins, outs) = inMonth(m).partition { it.isIn }
        return MonthSum(m, ins.sumOf { it.amount }, outs.sumOf { it.amount }, ins.size, outs.size)
    }

    /** Tổng tiền vào ([isIn]) hoặc ra theo tag trong tháng, lớn nhất trước; tag null = chưa gắn. */
    fun byTag(m: YearMonth, isIn: Boolean): List<Pair<Tag?, Long>> =
        inMonth(m).filter { it.isIn == isIn }
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
            )
        }
    }
}

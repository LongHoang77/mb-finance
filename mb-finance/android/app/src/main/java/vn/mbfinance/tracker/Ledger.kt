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
)

/** Sổ giao dịch đọc từ bảng transactions trên máy (không cần server). */
class Ledger(val txs: List<Tx>, val unparsed: List<Store.Item>) {

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

    fun inMonth(m: YearMonth): List<Tx> = byMonth[m].orEmpty()

    fun sum(m: YearMonth): MonthSum {
        val (ins, outs) = inMonth(m).partition { it.isIn }
        return MonthSum(m, ins.sumOf { it.amount }, outs.sumOf { it.amount }, ins.size, outs.size)
    }

    /** [n] tháng liên tiếp, kết thúc ở [end]. */
    fun lastMonths(end: YearMonth, n: Int) = (n - 1 downTo 0).map { sum(end.minusMonths(it.toLong())) }

    companion object {
        val VN: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
        val EMPTY = Ledger(emptyList(), emptyList())

        /** Thông báo không phải giao dịch được giữ 7 ngày. */
        private const val KEEP_OTHER_MS = 7L * 24 * 60 * 60 * 1000

        fun monthOf(ms: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(ms).atZone(VN))

        /** Trước khi đọc: phân tích lại nếu Parser đổi phiên bản, xoá thông báo không phải giao dịch quá 7 ngày. */
        fun load(ctx: Context): Ledger {
            val store = Store.get(ctx)
            val settings = Settings(ctx)
            synchronized(this) {
                if (settings.parserVersion != Parser.VERSION) {
                    store.reparseAll()
                    settings.parserVersion = Parser.VERSION
                }
                store.purgeNonTransactions(System.currentTimeMillis() - KEEP_OTHER_MS)
            }
            val pkg = settings.mbPackage
            // Bỏ OTP/quảng cáo đã lưu từ bản cũ, chỉ giữ mẫu giao dịch parser chưa đọc được
            return Ledger(store.transactions(pkg), store.unparsed(pkg).filter { Parser.looksLikeTransaction(it.text) })
        }
    }
}

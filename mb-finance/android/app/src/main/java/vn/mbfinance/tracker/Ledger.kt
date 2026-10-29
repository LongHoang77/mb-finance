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

        fun monthOf(ms: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(ms).atZone(VN))

        /** Parser đổi phiên bản (hoặc lần đầu lên DB v2) thì phân tích lại toàn bộ trước khi đọc. */
        fun load(ctx: Context): Ledger {
            val store = Store.get(ctx)
            val settings = Settings(ctx)
            synchronized(this) {
                if (settings.parserVersion != Parser.VERSION) {
                    store.reparseAll()
                    settings.parserVersion = Parser.VERSION
                }
            }
            val pkg = settings.mbPackage
            return Ledger(store.transactions(pkg), store.unparsed(pkg))
        }
    }
}

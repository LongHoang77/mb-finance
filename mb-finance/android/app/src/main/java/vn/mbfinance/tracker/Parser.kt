package vn.mbfinance.tracker

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Bản Kotlin của server/src/parser.ts — sửa regex một bên thì sửa cả bên kia.
 * Không chắc thì trả null, KHÔNG đoán bừa.
 */
object Parser {

    /** Tăng mỗi khi sửa regex hoặc cách đối soát trùng/phí để app tự phân tích lại dữ liệu cũ. */
    const val VERSION = 4

    data class Parsed(
        val amount: Long, // VND, luôn dương
        val isIn: Boolean,
        val balanceAfter: Long?,
        val account: String?,
        val description: String?,
        val time: Long?, // epoch ms nếu thông báo có ghi giờ
        val extras: List<Long> = emptyList(), // số tiền VND khác trong thông báo (phí, VAT…), ứng viên cho [fee]
    )

    private val VN_OFFSET = ZoneOffset.ofHours(7)

    // "GD: +5,000,000VND" / "GD: -150,000 VND"
    private val AMOUNT = Regex("""GD\s*:?\s*([+-])\s*([\d.,]+)\s*(?:VND|đ)""", RegexOption.IGNORE_CASE)
    // "SD: 12,345,678VND"
    private val BALANCE = Regex("""SD\s*:?\s*([\d.,]+)\s*(?:VND|đ)""", RegexOption.IGNORE_CASE)
    // "26/10/26 13:54"
    private val TIME = Regex("""(\d{1,2}/\d{1,2}/(?:\d{4}|\d{2}))\s+(\d{1,2}:\d{2}(?::\d{2})?)""")
    // "TK 0123xxx456" / "TK: 0123xxx456"
    private val ACCOUNT = Regex("""TK\s*:?\s*([0-9xX*]{4,})""")
    // "ND: ..." tới hết chuỗi; cần ":" và đứng sau "|"/khoảng trắng để không khớp "ND" trong "VND"
    private val DESC = Regex("""(?:^|[|\s])ND\s*:\s*(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    // Mọi số tiền VND, không phụ thuộc chữ đứng trước ("PHI", "VAT"…)
    private val MONEY = Regex("""(\d[\d.,]*)\s*(?:VND|đ)""", RegexOption.IGNORE_CASE)
    // Số tiền có dấu +/-: dấu hiệu thông báo biến động số dư, kể cả mẫu mới parser chưa đọc được
    private val SIGNED_MONEY = Regex("""[+-]\s*[\d.,]+\s*(?:VND|đ)""", RegexOption.IGNORE_CASE)
    private val SEPARATORS = Regex("""[.,\s]""")
    private val SPACES = Regex("""\s+""")

    /** "1,234,567" | "1.234.567" | "1234567" → 1234567 */
    private fun toVnd(s: String): Long? {
        val digits = s.replace(SEPARATORS, "")
        return if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) digits.toLongOrNull() else null
    }

    /** "26/10/26" + "13:54[:ss]" (giờ VN) → epoch ms */
    private fun toEpoch(date: String, time: String): Long? = try {
        val d = date.split('/')
        val t = time.split(':')
        val year = if (d[2].length == 2) 2000 + d[2].toInt() else d[2].toInt()
        LocalDateTime.of(year, d[1].toInt(), d[0].toInt(), t[0].toInt(), t[1].toInt(), t.getOrNull(2)?.toInt() ?: 0)
            .toInstant(VN_OFFSET).toEpochMilli()
    } catch (e: DateTimeException) {
        null
    }

    /** OTP, quảng cáo… → false. Mẫu giao dịch mới chưa parse được vẫn true để không bị mất. */
    fun looksLikeTransaction(text: String) = parse(text) != null || SIGNED_MONEY.containsMatchIn(text)

    fun parse(text: String): Parsed? {
        val a = AMOUNT.find(text) ?: return null
        val amount = toVnd(a.groupValues[2]) ?: return null
        if (amount <= 0) return null
        val t = TIME.find(text)
        val b = BALANCE.find(text)
        val taken = listOfNotNull(a.range, b?.range)
        val extras = MONEY.findAll(text)
            .filter { m -> taken.none { it.first <= m.range.last && m.range.first <= it.last } }
            .mapNotNull { toVnd(it.groupValues[1])?.takeIf { v -> v > 0 } }
            .toList()
        return Parsed(
            amount = amount,
            isIn = a.groupValues[1] == "+",
            balanceAfter = b?.let { toVnd(it.groupValues[1]) },
            account = ACCOUNT.find(text)?.groupValues?.get(1),
            description = DESC.find(text)?.groupValues?.get(1)?.trim()?.replace(SPACES, " "),
            time = t?.let { toEpoch(it.groupValues[1], it.groupValues[2]) },
            extras = extras,
        )
    }

    /**
     * Phí/VAT bị trừ thêm ngoài GD: số dư giảm nhiều hơn GD đúng bằng 1 số (hoặc tổng vài số) trong [Parsed.extras].
     * Không khớp, thiếu số dư, là tiền vào hoặc lệch quá lớn (dễ là sót giao dịch, để người dùng tự xác nhận) → 0.
     */
    fun fee(p: Parsed, prevBalance: Long?): Long {
        val after = p.balanceAfter ?: return 0
        if (p.isIn || prevBalance == null) return 0
        val extra = prevBalance - after - p.amount
        if (extra <= 0 || extra > maxOf(50_000L, p.amount / 10)) return 0
        val xs = p.extras.take(8)
        for (mask in 1 until (1 shl xs.size)) {
            if (xs.indices.sumOf { if ((mask shr it) and 1 == 1) xs[it] else 0L } == extra) return extra
        }
        return 0
    }

    /**
     * Cùng 1 giao dịch (MB gửi lại / cập nhật thông báo): khớp TK, chiều, số tiền GD, số dư sau, giờ ghi trong text
     * và ND (ND bị cắt vẫn khớp nếu là phần đầu của bên kia). Thiếu số dư hoặc giờ → không đủ chắc, false.
     */
    fun sameTx(a: Parsed, b: Parsed): Boolean =
        a.time != null && a.balanceAfter != null && a.time == b.time && a.balanceAfter == b.balanceAfter &&
            a.amount == b.amount && a.isIn == b.isIn && a.account == b.account && prefixMatch(a.description, b.description)

    /**
     * Bản bị cắt của cùng 1 giao dịch (thiếu ND/SD/giờ/TK): cùng GD và chiều, trường cả 2 cùng có thì khớp,
     * 1 bên thiếu trường bên kia có. Chưa đủ chắc nếu đứng riêng → Store kèm điều kiện thời gian gần nhau.
     */
    fun partialSame(a: Parsed, b: Parsed): Boolean {
        if (a.amount != b.amount || a.isIn != b.isIn) return false
        if (!agree(a.balanceAfter, b.balanceAfter) || !agree(a.time, b.time)) return false
        if (!prefixMatch(a.account, b.account) || !prefixMatch(a.description, b.description)) return false
        return fields(a) != fields(b)
    }

    /** Số trường đọc được (ND, SD, giờ, TK); bản nhiều hơn là bản đầy đủ hơn. */
    fun completeness(p: Parsed) = fields(p).count { it }

    private fun fields(p: Parsed) = listOf(p.description != null, p.balanceAfter != null, p.time != null, p.account != null)

    private fun <T> agree(x: T?, y: T?) = x == null || y == null || x == y

    private fun prefixMatch(a: String?, b: String?): Boolean {
        // Bỏ dấu "…"/"..." ở cuối bản bị cắt
        val x = a.orEmpty().trimEnd('…', '.', ' ')
        val y = b.orEmpty().trimEnd('…', '.', ' ')
        return x.startsWith(y) || y.startsWith(x)
    }
}

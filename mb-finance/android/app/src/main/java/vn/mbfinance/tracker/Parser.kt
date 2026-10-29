package vn.mbfinance.tracker

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Bản Kotlin của server/src/parser.ts — sửa regex một bên thì sửa cả bên kia.
 * Không chắc thì trả null, KHÔNG đoán bừa.
 */
object Parser {

    /** Tăng mỗi khi sửa regex để app tự phân tích lại dữ liệu cũ. */
    const val VERSION = 1

    data class Parsed(
        val amount: Long, // VND, luôn dương
        val isIn: Boolean,
        val balanceAfter: Long?,
        val account: String?,
        val description: String?,
        val time: Long?, // epoch ms nếu thông báo có ghi giờ
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

    fun parse(text: String): Parsed? {
        val a = AMOUNT.find(text) ?: return null
        val amount = toVnd(a.groupValues[2]) ?: return null
        if (amount <= 0) return null
        val t = TIME.find(text)
        return Parsed(
            amount = amount,
            isIn = a.groupValues[1] == "+",
            balanceAfter = BALANCE.find(text)?.let { toVnd(it.groupValues[1]) },
            account = ACCOUNT.find(text)?.groupValues?.get(1),
            description = DESC.find(text)?.groupValues?.get(1)?.trim()?.replace(SPACES, " "),
            time = t?.let { toEpoch(it.groupValues[1], it.groupValues[2]) },
        )
    }
}

package vn.mbfinance.tracker

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Bảng màu sáng/tối và các khối giao diện dựng bằng code (app không dùng XML layout). */
class Ui(private val ctx: Context) {
    val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    val bg = c(0xFFF3F5F9, 0xFF0E1014)
    val surface = c(0xFFFFFFFF, 0xFF1A1D23)
    val fg = c(0xFF111827, 0xFFE8EAED)
    val muted = c(0xFF6B7280, 0xFF9AA0A6)
    val line = c(0xFFE5E7EB, 0xFF2A2E35)
    val primary = c(0xFF1D4ED8, 0xFF8AA8FF)
    val onPrimary = c(0xFFFFFFFF, 0xFF0B1220)
    val primarySoft = c(0xFFE3EAFE, 0xFF1F2940)
    val green = c(0xFF15803D, 0xFF4ADE80)
    val red = c(0xFFDC2626, 0xFFF87171)
    val warn = c(0xFFB45309, 0xFFFCD34D)

    private fun c(light: Long, dark: Long) = (if (night) dark else light).toInt()

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private val nf = NumberFormat.getInstance(Locale("vi", "VN"))

    /** 1234567 → "1.234.567 ₫"; [signed] thêm dấu + cho số dương. */
    fun money(n: Long, signed: Boolean = false): String {
        val sign = if (n < 0) "−" else if (signed && n > 0) "+" else ""
        return "$sign${nf.format(abs(n))} ₫"
    }

    fun rounded(color: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun pressable(color: Int, radius: Int) =
        RippleDrawable(ColorStateList.valueOf(fg and 0x22FFFFFF), rounded(color, radius), null)

    fun text(s: CharSequence, size: Float, color: Int = fg, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun vbox() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun card(radius: Int = 18, color: Int = surface) = vbox().apply {
        background = rounded(color, radius)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    /** Thêm [v] vào cột, cách view phía trên [gap] dp. */
    fun add(parent: LinearLayout, v: View, gap: Int = 12) {
        parent.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(gap) })
    }

    fun divider() = View(ctx).apply { setBackgroundColor(line) }

    fun button(s: String, filled: Boolean = true, onClick: () -> Unit) = TextView(ctx).apply {
        text = s
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (filled) onPrimary else primary)
        background = pressable(if (filled) primary else primarySoft, 12)
        setPadding(dp(14), dp(11), dp(14), dp(11))
        setOnClickListener { onClick() }
    }

    fun chip(s: String, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(if (selected) onPrimary else fg)
        if (selected) typeface = Typeface.DEFAULT_BOLD
        background = pressable(if (selected) primary else surface, 20)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener { onClick() }
    }

    fun input(hintText: String) = EditText(ctx).apply {
        hint = hintText
        setSingleLine()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(fg)
        setHintTextColor(muted)
        background = rounded(bg, 12, stroke = line)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    /** Hàng ngang chia đều chiều rộng. */
    fun row(vararg views: View, gap: Int = 8) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(gap) })
        }
    }

    /** [left] chiếm phần còn lại, [right] sát lề phải. */
    fun split(left: View, right: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(right, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
    }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** Biểu đồ cột vào/ra theo tháng; chạm vào cột để chọn tháng. */
@SuppressLint("ViewConstructor")
class MonthBars(
    ctx: Context,
    private val ui: Ui,
    private val data: List<Ledger.MonthSum>,
    private val selected: YearMonth,
    private val onPick: (YearMonth) -> Unit,
) : View(ctx) {

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ui.dp(11).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), ui.dp(160))
    }

    override fun onDraw(canvas: Canvas) {
        if (data.isEmpty()) return
        val base = (height - ui.dp(22)).toFloat()
        val top = ui.dp(4).toFloat()
        val peak = max(1L, data.maxOf { max(it.totalIn, it.totalOut) })
        val slot = width.toFloat() / data.size
        val w = min(slot * 0.3f, ui.dp(16).toFloat())
        val gap = ui.dp(2).toFloat()

        bar.color = ui.line
        canvas.drawRect(0f, base, width.toFloat(), base + ui.dp(1), bar)

        data.forEachIndexed { i, m ->
            val cx = slot * i + slot / 2
            val on = m.month == selected
            if (on) {
                bar.color = ui.primarySoft
                rect.set(slot * i + gap, 0f, slot * (i + 1) - gap, base)
                canvas.drawRoundRect(rect, ui.dp(8).toFloat(), ui.dp(8).toFloat(), bar)
            }
            column(canvas, cx - w - gap / 2, w, m.totalIn, peak, top, base, ui.green, on)
            column(canvas, cx + gap / 2, w, m.totalOut, peak, top, base, ui.red, on)
            label.color = if (on) ui.fg else ui.muted
            label.isFakeBoldText = on
            canvas.drawText("T${m.month.monthValue}", cx, height - ui.dp(6).toFloat(), label)
        }
    }

    private fun column(c: Canvas, left: Float, w: Float, v: Long, peak: Long, top: Float, base: Float, color: Int, on: Boolean) {
        if (v <= 0) return
        val h = max(ui.dp(3).toFloat(), (v.toDouble() / peak * (base - top)).toFloat())
        bar.color = color
        bar.alpha = if (on) 255 else 120
        rect.set(left, base - h, left + w, base)
        c.drawRoundRect(rect, w / 3, w / 3, bar)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && data.isNotEmpty()) {
            val i = (e.x / (width.toFloat() / data.size)).toInt().coerceIn(0, data.size - 1)
            onPick(data[i].month)
        }
        return true
    }
}

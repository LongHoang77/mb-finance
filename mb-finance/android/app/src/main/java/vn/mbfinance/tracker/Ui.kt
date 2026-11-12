package vn.mbfinance.tracker

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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

    val bg = c(0xFFF4F6FA, 0xFF0A0D13)
    val surface = c(0xFFFFFFFF, 0xFF131823)
    /** Nền ô nhập, khối phụ trong card. */
    val surface2 = c(0xFFF1F4F9, 0xFF1A2030)
    /** Phần được chọn nổi trên [surface2] (segmented). */
    val raised = c(0xFFFFFFFF, 0xFF2A3245)
    val fg = c(0xFF0F172A, 0xFFE6EAF2)
    val muted = c(0xFF64748B, 0xFF8B95A7)
    val line = c(0xFFE7EBF2, 0xFF232A3A)
    val primary = c(0xFF2563EB, 0xFF7AA2FF)
    val onPrimary = c(0xFFFFFFFF, 0xFF0A1226)
    val primarySoft = c(0xFFE8F0FE, 0xFF1A2544)
    val green = c(0xFF059669, 0xFF34D399)
    val red = c(0xFFE11D48, 0xFFFB7185)
    val warn = c(0xFFD97706, 0xFFFBBF24)

    /** Gradient thẻ chính, cùng tông icon app; thẻ tiền mặt dùng tông xanh lá. */
    val heroStart = c(0xFF3B82F6, 0xFF2563EB)
    val heroEnd = c(0xFF1E3A8A, 0xFF172554)
    val cashStart = c(0xFF10B981, 0xFF059669)
    val cashEnd = c(0xFF065F46, 0xFF064E3B)
    val onHero = 0xFFFFFFFF.toInt()

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun c(light: Long, dark: Long) = (if (night) dark else light).toInt()

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private val nf = NumberFormat.getInstance(Locale("vi", "VN"))

    /** 1234567 → "1.234.567 ₫"; [signed] thêm dấu + cho số dương. */
    fun money(n: Long, signed: Boolean = false): String {
        val sign = if (n < 0) "−" else if (signed && n > 0) "+" else ""
        return "$sign${nf.format(abs(n))} ₫"
    }

    /** Gọn cho chip/widget: ≥ 1 triệu làm tròn "12,6tr", số nghìn chẵn "30k", còn lại để nguyên. */
    fun short(n: Long): String {
        val sign = if (n < 0) "−" else ""
        val a = abs(n)
        return sign + when {
            a >= 1_000_000 -> {
                val d = (a + 50_000) / 100_000
                if (d % 10 == 0L) "${d / 10}tr" else "${d / 10},${d % 10}tr"
            }
            a % 1000 == 0L && a > 0 -> "${a / 1000}k"
            else -> nf.format(a)
        }
    }

    fun rounded(color: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun gradient(start: Int, end: Int, radius: Int = 24) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply { cornerRadius = dp(radius).toFloat() }

    fun pressable(color: Int, radius: Int, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf(fg and 0x22FFFFFF), rounded(color, radius, stroke), null)

    /** Bản nhạt của [color] (mặc định alpha ~15%), làm nền cho chip/nhãn/icon. */
    fun soft(color: Int, alpha: Int = 0x26) = (color and 0x00FFFFFF) or (alpha shl 24)

    /** Nền bottom sheet: chỉ bo 2 góc trên. */
    fun sheet() = GradientDrawable().apply {
        setColor(surface)
        val r = dp(28).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    /** "● name" với chấm tô màu [color]. */
    fun dotLabel(name: String, color: Int): CharSequence = SpannableString("● $name").apply {
        setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** [s] kèm icon [res] màu [color] đứng trước, căn giữa theo dòng chữ. */
    fun iconText(res: Int, s: CharSequence, color: Int, size: Int = 16): CharSequence {
        val d = drawable(res, color, size)
        return SpannableStringBuilder(" ").apply {
            setSpan(CenteredImageSpan(d), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("  ").append(s)
        }
    }

    fun drawable(res: Int, color: Int, size: Int): Drawable = ctx.getDrawable(res)!!.mutate().apply {
        setTint(color)
        setBounds(0, 0, dp(size), dp(size))
    }

    /** bold = chữ đậm vừa (medium), nhìn gọn hơn bold hệ thống. */
    fun text(s: CharSequence, size: Float, color: Int = fg, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = medium
        includeFontPadding = false
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    /** Số tiền: chữ số đều nhau để các cột số thẳng hàng, 1 dòng. */
    fun amount(n: Long, size: Float, color: Int = fg, signed: Boolean = false) = text(money(n, signed), size, color, bold = true).apply {
        fontFeatureSettings = "tnum"
        if (size >= 22f) letterSpacing = -0.02f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun vbox() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    /** Card nền [surface] có viền mảnh; màu khác (vd [surface2]) thì không viền. */
    fun card(radius: Int = 20, color: Int = surface) = vbox().apply {
        background = rounded(color, radius, stroke = if (color == surface) line else null)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    /** Thẻ chính nền gradient, chữ trắng ([onHero]). */
    fun hero(start: Int = heroStart, end: Int = heroEnd) = vbox().apply {
        background = gradient(start, end)
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }

    /** Tiêu đề card, có thể kèm hành động chữ xanh bên phải. */
    fun header(title: String, action: String? = null, onAction: (() -> Unit)? = null): View {
        val right: View = if (action == null) View(ctx) else text(action, 13f, primary, bold = true).apply {
            setPadding(dp(8), dp(6), dp(4), dp(6))
            background = pressable(surface, 8)
            setOnClickListener { onAction?.invoke() }
        }
        return split(text(title, 16f, bold = true), right)
    }

    /** Thêm [v] vào cột, cách view phía trên [gap] dp. */
    fun add(parent: LinearLayout, v: View, gap: Int = 12) {
        parent.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(gap) })
    }

    fun divider() = View(ctx).apply { setBackgroundColor(line) }

    fun icon(res: Int, color: Int, size: Int = 20) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** Ô tròn [size]dp nền nhạt màu [color], icon [res] ở giữa. */
    fun iconCircle(res: Int, color: Int, size: Int = 40, fill: Int = soft(color)) = FrameLayout(ctx).apply {
        background = oval(fill)
        val s = size * 11 / 20
        addView(icon(res, color, s), FrameLayout.LayoutParams(dp(s), dp(s), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** Ô tròn chữ cái đầu của [name] (avatar tag). */
    fun letterCircle(name: String, color: Int, size: Int = 40) = text(name.trim().take(1).uppercase(), size * 0.4f, color, bold = true).apply {
        gravity = Gravity.CENTER
        background = oval(soft(color))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /**
     * Nút cao 48dp. [filled]: nền xanh chữ trắng; không thì nền xanh nhạt. [danger]: chữ đỏ nền đỏ nhạt.
     * [icon] hiện trước chữ.
     */
    fun button(s: String, filled: Boolean = true, icon: Int? = null, danger: Boolean = false, onClick: () -> Unit) =
        TextView(ctx).apply {
            val color = when {
                danger -> red
                filled -> onPrimary
                else -> primary
            }
            text = if (icon == null) s else iconText(icon, s, color, 18)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = medium
            setTextColor(color)
            background = pressable(
                when {
                    danger -> soft(red, 0x1A)
                    filled -> primary
                    else -> primarySoft
                },
                14,
            )
            minHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
        }

    fun chip(s: String, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(if (selected) primary else fg)
        if (selected) typeface = medium
        background = pressable(if (selected) primarySoft else surface, 20, stroke = if (selected) primary else line)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    /** Chip tag: chấm màu + tên, chọn thì nền màu tag nhạt. [color] null = chip thường (vd "+ Tag mới"). */
    fun tagChip(name: String, color: Int?, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = if (color == null) name else dotLabel(name, color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(if (color == null && !selected) primary else fg)
        if (selected) typeface = medium
        val c = color ?: primary
        background = pressable(if (selected) soft(c) else surface, 20, stroke = if (selected) c else line)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    /** Chọn 1 trong vài lựa chọn: thanh nền [surface2], ô được chọn nổi lên. */
    fun segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit) = LinearLayout(ctx).apply {
        background = rounded(surface2, 14)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        labels.forEachIndexed { i, s ->
            val on = i == selected
            addView(text(s, 14f, if (on) fg else muted, bold = on).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(9), dp(6), dp(9))
                background = if (on) rounded(raised, 11, stroke = if (night) null else line) else pressable(surface2, 11)
                setOnClickListener { if (!on) onPick(i) }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    /** Hộp ghi chú có icon, nền nhạt màu [color]. */
    fun callout(res: Int, s: CharSequence, color: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(soft(color, 0x1A), 14)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        addView(icon(res, color, 18), LinearLayout.LayoutParams(dp(18), dp(18)).apply { topMargin = dp(1) })
        addView(text(s, 13f, fg), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
    }

    /** Nhãn nhỏ dạng viên thuốc. */
    fun pill(s: CharSequence, color: Int) = text(s, 11f, color, bold = true).apply {
        background = rounded(soft(color, 0x22), 8)
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }

    /** Thanh tỉ lệ ngang, [fraction] trong 0..1. */
    fun bar(fraction: Float, color: Int) = LinearLayout(ctx).apply {
        background = rounded(surface2, 4)
        addView(View(ctx).apply { background = rounded(color, 4) }, LinearLayout.LayoutParams(0, dp(8), fraction))
        addView(View(ctx), LinearLayout.LayoutParams(0, dp(8), 1f - fraction))
    }

    fun flow() = Flow(ctx, dp(8))

    fun input(hintText: String) = EditText(ctx).apply {
        hint = hintText
        setSingleLine()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(fg)
        setHintTextColor(muted)
        background = rounded(surface2, 14)
        setPadding(dp(14), dp(13), dp(14), dp(13))
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

    /** [lead] (icon/avatar) + cột chữ [body] + [trail] bên phải, căn giữa theo chiều dọc. */
    fun listRow(lead: View?, body: View, trail: View?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (lead != null) addView(lead)
        addView(body, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (lead != null) marginStart = dp(12) })
        if (trail != null) addView(trail, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
    }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** ImageSpan căn giữa theo chiều cao chữ (ALIGN_CENTER chỉ có từ API 29). */
private class CenteredImageSpan(d: Drawable) : ImageSpan(d) {
    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val d = drawable
        val fm = paint.fontMetricsInt
        canvas.save()
        canvas.translate(x, (y + (fm.descent + fm.ascent) / 2 - d.bounds.bottom / 2).toFloat())
        d.draw(canvas)
        canvas.restore()
    }
}

/** Xếp view con theo hàng, hết chỗ thì xuống dòng (danh sách tag). */
@SuppressLint("ViewConstructor")
class Flow(ctx: Context, private val gap: Int) : ViewGroup(ctx) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val h = place(width - paddingLeft - paddingRight) { c, _, _ ->
            measureChild(c, widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        setMeasuredDimension(width, h + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(r - l - paddingLeft - paddingRight) { c, x, y ->
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
        }
    }

    /** Duyệt con theo thứ tự xếp hàng, gọi [each] (đo hoặc đặt vị trí); trả về tổng chiều cao. */
    private fun place(maxW: Int, each: (View, Int, Int) -> Unit): Int {
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            each(c, x, y)
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + gap
                rowH = 0
                each(c, x, y)
            }
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
        }
        return y + rowH
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
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.line
        strokeWidth = ui.dp(1).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(ui.dp(4).toFloat(), ui.dp(4).toFloat()), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ui.dp(11).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), ui.dp(168))
    }

    override fun onDraw(canvas: Canvas) {
        if (data.isEmpty()) return
        val base = (height - ui.dp(26)).toFloat()
        val top = ui.dp(8).toFloat()
        val peak = max(1L, data.maxOf { max(it.totalIn, it.totalOut) })
        val slot = width.toFloat() / data.size
        val w = min(slot * 0.26f, ui.dp(14).toFloat())
        val gap = ui.dp(3).toFloat()

        for (k in 0..2) {
            val y = top + (base - top) * k / 3
            canvas.drawLine(0f, y, width.toFloat(), y, grid)
        }
        bar.color = ui.line
        bar.alpha = 255
        canvas.drawRect(0f, base, width.toFloat(), base + ui.dp(1), bar)

        data.forEachIndexed { i, m ->
            val cx = slot * i + slot / 2
            val on = m.month == selected
            if (on) {
                bar.color = ui.soft(ui.primary, 0x1A)
                bar.alpha = 255
                rect.set(slot * i + gap, 0f, slot * (i + 1) - gap, height.toFloat())
                canvas.drawRoundRect(rect, ui.dp(12).toFloat(), ui.dp(12).toFloat(), bar)
            }
            column(canvas, cx - w - gap / 2, w, m.totalIn, peak, top, base, ui.green, on)
            column(canvas, cx + gap / 2, w, m.totalOut, peak, top, base, ui.red, on)
            label.color = if (on) ui.primary else ui.muted
            label.typeface = if (on) ui.medium else Typeface.DEFAULT
            canvas.drawText("T${m.month.monthValue}", cx, height - ui.dp(8).toFloat(), label)
        }
    }

    private fun column(c: Canvas, left: Float, w: Float, v: Long, peak: Long, top: Float, base: Float, color: Int, on: Boolean) {
        if (v <= 0) return
        val h = max(ui.dp(4).toFloat(), (v.toDouble() / peak * (base - top)).toFloat())
        bar.color = color
        bar.alpha = if (on) 255 else 110
        rect.set(left, base - h, left + w, base)
        c.drawRoundRect(rect, w / 2, w / 2, bar)
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

/**
 * Biểu đồ vành khuyên: mỗi phần 1 màu cố định theo thực thể, khe 2dp giữa các phần.
 * Chạm 1 phần để chọn (giữa vành hiện tên, số tiền, %), chạm lại hoặc chạm giữa để bỏ chọn; [onSelect] nhận index (-1 = bỏ).
 */
class DonutChart(
    ctx: Context,
    private val ui: Ui,
    private val parts: List<Part>,
    private val title: String,
) : View(ctx) {

    data class Part(val label: String, val value: Long, val color: Int)

    var onSelect: ((Int) -> Unit)? = null
    private var selected = -1
    private val total = max(1L, parts.sumOf { it.value })
    private val stroke = ui.dp(22).toFloat()
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ui.dp(12).toFloat()
        textAlign = Paint.Align.CENTER
        color = ui.muted
    }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ui.dp(20).toFloat()
        textAlign = Paint.Align.CENTER
        typeface = ui.medium
        fontFeatureSettings = "tnum"
        color = ui.fg
    }
    private val oval = RectF()
    private var radius = 0f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), ui.dp(188))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // Chừa chỗ cho phần đang chọn dày hơn
        radius = min(width, height) / 2f - stroke / 2 - ui.dp(5)
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val gap = if (parts.size > 1) Math.toDegrees(ui.dp(2) / radius.toDouble()).toFloat() else 0f
        var start = -90f
        parts.forEachIndexed { i, p ->
            val sweep = 360f * p.value / total
            arc.color = p.color
            arc.alpha = if (selected < 0 || selected == i) 255 else 70
            arc.strokeWidth = if (selected == i) stroke + ui.dp(6) else stroke
            canvas.drawArc(oval, start + gap / 2, max(0.5f, sweep - gap), false, arc)
            start += sweep
        }

        val inner = (radius - stroke) * 2 - ui.dp(8)
        val p = parts.getOrNull(selected)
        val label = p?.label ?: title
        val value = p?.value ?: total
        canvas.drawText(TextUtils.ellipsize(label, small, inner, TextUtils.TruncateAt.END).toString(), cx, cy - ui.dp(14), small)
        canvas.drawText(ui.short(value), cx, cy + ui.dp(8), big)
        if (p != null) canvas.drawText("${p.value * 100 / total}%", cx, cy + ui.dp(28), small)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val dx = e.x - width / 2f
        val dy = e.y - height / 2f
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        // Chạm giữa hoặc ngoài vành → bỏ chọn; vùng chạm rộng hơn nét vẽ
        val hit = if (dist < radius - stroke || dist > radius + stroke) -1 else {
            // Góc tính từ 12 giờ theo chiều kim đồng hồ, giống thứ tự vẽ
            val deg = ((Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()) + 90 + 360) % 360).toFloat()
            var acc = 0f
            parts.indexOfFirst { acc += 360f * it.value / total; deg < acc }
        }
        selected = if (hit == selected) -1 else hit
        invalidate()
        onSelect?.invoke(selected)
        return true
    }
}

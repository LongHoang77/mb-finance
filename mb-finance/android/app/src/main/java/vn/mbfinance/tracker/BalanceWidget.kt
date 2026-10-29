package vn.mbfinance.tracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** Widget màn hình chính: thu/chi/chênh lệch tháng này + số dư gần nhất. Chạm để mở app. */
class BalanceWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val done = goAsync()
        thread {
            try {
                update(ctx, manager, ids)
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(Ledger.VN)

        /** Gọi khi có giao dịch mới; không có widget nào thì bỏ qua. */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, BalanceWidget::class.java))
            if (ids.isEmpty()) return
            thread {
                try {
                    update(app, manager, ids)
                } catch (e: Exception) {
                    // Widget lỗi không được làm hỏng listener/app
                }
            }
        }

        private fun update(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val ui = Ui(ctx)
            val ledger = Ledger.load(ctx)
            val month = YearMonth.now(Ledger.VN)
            val s = ledger.sum(month)

            val v = RemoteViews(ctx.packageName, R.layout.widget_balance)
            v.setTextViewText(R.id.w_title, "Tháng ${month.monthValue} · Thu chi MB")
            v.setTextViewText(R.id.w_net, ui.money(s.net, signed = true))
            v.setTextColor(R.id.w_net, ctx.getColor(if (s.net < 0) R.color.w_red else R.color.w_fg))
            v.setTextViewText(R.id.w_in, "▲ ${ui.money(s.totalIn)}")
            v.setTextViewText(R.id.w_out, "▼ ${ui.money(s.totalOut)}")
            val last = ledger.txs.firstOrNull()
            v.setTextViewText(
                R.id.w_balance,
                when {
                    ledger.latestBalance != null && last != null ->
                        "Số dư ${ui.money(ledger.latestBalance)} · ${timeFmt.format(Instant.ofEpochMilli(last.time))}"
                    last != null -> "GD cuối ${timeFmt.format(Instant.ofEpochMilli(last.time))}"
                    else -> "Chưa có giao dịch"
                },
            )
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            manager.updateAppWidget(ids, v)
        }
    }
}

package vn.mbfinance.tracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** Widget màn hình chính: thu/chi/chênh lệch tháng này + số dư + nút quét lại thông báo. Chạm phần còn lại để mở app. */
class BalanceWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = ctx.applicationContext
        val done = goAsync()
        thread {
            try {
                // Cập nhật định kỳ 30 phút cũng là dịp quét bù thông báo bị lỡ
                runCatching { MbNotificationListener.catchUp(app) }
                update(app, manager, ids)
            } finally {
                done.finish()
            }
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC) return super.onReceive(ctx, intent)
        val app = ctx.applicationContext
        val done = goAsync()
        thread {
            try {
                syncNow(app)
            } catch (e: Exception) {
                // Widget lỗi không được làm hỏng app
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        private const val ACTION_SYNC = "vn.mbfinance.tracker.WIDGET_SYNC"

        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm dd/MM").withZone(Ledger.VN)
        private val clockFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(Ledger.VN)

        /** Đang chạy nút quét: mọi lần vẽ lại trong lúc này đều hiện vòng xoay. */
        @Volatile
        private var syncing = false

        /** Gọi khi dữ liệu/trạng thái listener thay đổi; không có widget nào thì bỏ qua. */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            val ids = ids(app)
            if (ids.isEmpty()) return
            thread {
                try {
                    update(app, AppWidgetManager.getInstance(app), ids)
                } catch (e: Exception) {
                    // Widget lỗi không được làm hỏng listener/app
                }
            }
        }

        private fun ids(ctx: Context) =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, BalanceWidget::class.java))

        /** Quét bù thông báo đang hiện rồi vẽ lại kết quả. */
        private fun syncNow(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            syncing = true
            try {
                update(ctx, manager, ids(ctx))
                runCatching { MbNotificationListener.catchUp(ctx) }
            } finally {
                syncing = false
                update(ctx, manager, ids(ctx))
            }
        }

        private fun update(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val ui = Ui(ctx)
            val settings = Settings(ctx)
            val store = Store.get(ctx)
            Ledger.prepare(ctx)
            val month = YearMonth.now(Ledger.VN)
            val b = store.brief(settings.mbPackage, month)

            val v = RemoteViews(ctx.packageName, R.layout.widget_balance)
            v.setTextViewText(R.id.w_title, "Tháng ${month.monthValue}/${month.year}")
            v.setTextViewText(R.id.w_net, ui.money(b.net, signed = true))
            v.setTextColor(R.id.w_net, ctx.getColor(if (b.net < 0) R.color.w_red else R.color.w_fg))
            v.setTextViewText(R.id.w_in, "▲ ${ui.money(b.totalIn)}")
            v.setTextViewText(R.id.w_out, "▼ ${ui.money(b.totalOut)}")
            v.setTextViewText(
                R.id.w_balance,
                when {
                    b.balance != null || b.cash != null -> listOfNotNull(
                        "Tổng ${ui.short((b.balance ?: 0) + (b.cash ?: 0))}",
                        b.balance?.let { "Bank ${ui.short(it)}" },
                        b.cash?.let { "Money ${ui.short(it)}" },
                    ).joinToString(" · ")
                    b.lastTxTime != null -> "GD cuối ${timeFmt.format(Instant.ofEpochMilli(b.lastTxTime))}"
                    else -> "Chưa có giao dịch"
                },
            )

            val (status, warn) = status(ctx)
            v.setTextViewText(R.id.w_status, status)
            v.setTextColor(R.id.w_status, ctx.getColor(if (warn) R.color.w_warn else R.color.w_muted))
            v.setViewVisibility(R.id.w_sync, if (syncing) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.w_progress, if (syncing) View.VISIBLE else View.GONE)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            v.setOnClickPendingIntent(
                R.id.w_root, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags),
            )
            v.setOnClickPendingIntent(
                R.id.w_sync,
                PendingIntent.getBroadcast(ctx, 1, Intent(ctx, BalanceWidget::class.java).setAction(ACTION_SYNC), flags),
            )
            manager.updateAppWidget(ids, v)
        }

        /** Dòng trạng thái nhỏ cạnh nút quét (giờ vẽ lại gần nhất); second = true thì tô màu cảnh báo. */
        private fun status(ctx: Context): Pair<String, Boolean> = when {
            syncing -> "Đang quét…" to false
            !MbNotificationListener.isEnabled(ctx) -> "Chưa cấp quyền" to true
            MbNotificationListener.instance == null -> "Mất kết nối" to true
            else -> "Cập nhật ${clockFmt.format(Instant.now())}" to false
        }
    }
}

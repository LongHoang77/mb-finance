package vn.mbfinance.tracker.game

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

/** Tác vụ định kỳ (~3 giờ, khi có mạng, giữ qua khởi động lại): chạy [GameSync]. */
class GameJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            runCatching { GameSync.run(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 7001
        private const val PERIOD = 3 * 60 * 60_000L

        /** Đặt lịch nếu chưa có (đặt lại mỗi lần sẽ làm lùi chu kỳ). */
        fun ensure(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            js.schedule(
                JobInfo.Builder(ID, ComponentName(ctx, GameJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD)
                    .setPersisted(true)
                    .build()
            )
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java)?.cancel(ID)
        }
    }
}

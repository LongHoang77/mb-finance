package vn.mbfinance.tracker.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Kết quả cài bản cập nhật ([Updater.install]): cần người dùng xác nhận → mở hộp thoại của hệ thống; lỗi → ghi lại. */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(confirm) }
                    .onFailure { Updater.setError(ctx, "Không mở được hộp thoại cài đặt — mở app rồi bấm cập nhật lại") }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.setError(ctx, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.setError(ctx, "Đã huỷ cài bản cập nhật")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "mã $status"
                val text = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    "Không cài đè được (khác khoá ký?): $msg"
                } else {
                    "Cài bản cập nhật lỗi: $msg"
                }
                Updater.setError(ctx, text)
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}

package moe.gbxin.saltreader.ui.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.gbxin.saltreader.R

/**
 * Android 16 Live Update for the current reading position.
 *
 * The notification is an ongoing ProgressStyle notification that requests promotion
 * (`setRequestPromotedOngoing`) and carries a short critical text (the percentage). It is posted
 * when the reader goes to the background and cancelled on return; tapping it (or its "continue"
 * action) opens [ReaderActivity], which restores the book from its persisted progress.
 */
object ReadingLiveUpdate {
    private const val CHANNEL_ID = "reading_progress_live"
    private const val NOTIFICATION_ID = 0x5A17
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Snapshot(
        val bookId: String,
        val title: String,
        val percent: Int,
        val page: Int,
        val total: Int,
    )

    fun post(context: Context, snapshot: Snapshot) {
        // Live Updates are an Android 16 (API 36) feature; older systems get no notification.
        if (Build.VERSION.SDK_INT < 36) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        scope.launch {
            val cover = coverBitmap(context, snapshot.bookId)
            ensureChannel(context, manager)
            val contentIntent = readerIntent(context, snapshot.bookId)
            val percent = snapshot.percent.coerceIn(0, 100)
            val contentText = if (snapshot.total > 0) {
                context.getString(R.string.reader_live_update_pages, snapshot.page, snapshot.total)
            } else {
                context.getString(R.string.reader_live_update_progress, percent)
            }
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_reading_live)
                .setContentTitle(snapshot.title)
                .setContentText(contentText)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setProgress(100, percent, false)
                .setStyle(
                    Notification.ProgressStyle()
                        .setStyledByProgress(true)
                        .setProgress(percent),
                )
                .setShortCriticalText("$percent%")
                .setRequestPromotedOngoing(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(contentIntent)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_reading_live),
                        context.getString(R.string.reader_live_update_continue),
                        contentIntent,
                    ).build(),
                )
            if (cover != null) builder.setLargeIcon(cover)
            runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reader_live_update_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.settings_reader_live_update_supporting)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun readerIntent(context: Context, bookId: String): PendingIntent {
        val intent = Intent(context, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        return PendingIntent.getActivity(
            context,
            bookId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Cover cache lives at `filesDir/library/covers/<bookId>.jpg`; decode it off the main thread. */
    private fun coverBitmap(context: Context, bookId: String): Bitmap? = runCatching {
        val file = File(File(context.filesDir, "library/covers"), "$bookId.jpg")
        if (!file.isFile) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 256) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

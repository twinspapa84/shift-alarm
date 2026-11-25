package io.github.twinspapa84.shiftalarm.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import androidx.core.app.NotificationCompat
import io.github.twinspapa84.shiftalarm.AlarmActivity
import io.github.twinspapa84.shiftalarm.R
import io.github.twinspapa84.shiftalarm.core.AlarmKind

/** 알람 알림 채널/알림 생성. 소리는 서비스가 직접 재생하므로 채널은 무음으로 둔다. */
object NotificationHelper {

    const val CHANNEL_ALARM = "shift_alarm_ringing"
    const val NOTIFICATION_ID = 4101

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ALARM) != null) return
        val channel = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(R.string.channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alarm_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun buildAlarmNotification(
        context: Context,
        kind: AlarmKind,
        snooze: Boolean
    ): android.app.Notification {
        val title = if (snooze) {
            context.getString(R.string.notification_snooze_title, kind.label)
        } else {
            kind.label
        }

        val activityIntent = Intent(context, AlarmActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AlarmService.EXTRA_KIND, kind.name)
            .putExtra(AlarmService.EXTRA_SNOOZE, snooze)
        val fullScreenIntent = PendingIntent.getActivity(
            context, 2001, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = PendingIntent.getService(
            context, 2002,
            Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_DISMISS)
                .putExtra(AlarmService.EXTRA_KIND, kind.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeIntent = PendingIntent.getService(
            context, 2003,
            Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_SNOOZE)
                .putExtra(AlarmService.EXTRA_KIND, kind.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(
                R.drawable.ic_alarm,
                context.getString(R.string.notification_yes),
                dismissIntent
            )
            .addAction(
                R.drawable.ic_snooze,
                context.getString(R.string.notification_snooze),
                snoozeIntent
            )
            .build()
    }

    /** 알람 서비스가 쓰는 오디오 속성(알람 볼륨/무음모드 정책을 따르도록). */
    fun alarmAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}

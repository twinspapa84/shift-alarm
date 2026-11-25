package io.github.twinspapa84.shiftalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.github.twinspapa84.shiftalarm.MainActivity
import io.github.twinspapa84.shiftalarm.core.AlarmInstance
import io.github.twinspapa84.shiftalarm.core.AlarmKind
import io.github.twinspapa84.shiftalarm.core.ShiftConfig
import io.github.twinspapa84.shiftalarm.core.ShiftEngine
import java.time.LocalDateTime

/**
 * AlarmManager 예약 담당.
 * - 실제 알람 1건: setAlarmClock() (잠금화면/도즈에서도 정확, 시스템에 다음 알람 표시)
 * - 22:00 자동 복구: setExactAndAllowWhileIdle()
 * - 5분 뒤 다시 울림: setAlarmClock()
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    const val RC_ALARM = 1001
    const val RC_RESTORE = 1002
    const val RC_SNOOZE = 1003
    const val RC_TEST = 1004
    const val SNOOZE_MINUTES = 5L
    const val TEST_DELAY_SECONDS = 60L

    /** 알람/복구를 모두 다시 예약한다. 설정이 바뀔 때마다 부르면 된다. */
    fun rescheduleAll(context: Context, config: ShiftConfig) {
        scheduleNextAlarm(context, config)
        scheduleRestore(context, config)
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    /** 지금 이후 가장 가까운 알람을 한 건 예약한다. */
    fun scheduleNextAlarm(context: Context, config: ShiftConfig) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmPendingIntent(context, null)
        manager.cancel(pending)

        val next = ShiftEngine.nextAlarm(LocalDateTime.now(), config)
        if (next == null) {
            Log.i(TAG, "예약할 알람 없음")
            return
        }
        setExact(context, manager, next, alarmPendingIntent(context, next))
        Log.i(TAG, "다음 알람 예약: ${next.date} ${next.time} ${next.kind}")
    }

    /** "테스트 알람" 버튼: 1분 뒤 한 번 울려 권한/소리를 확인한다. */
    fun scheduleTestAlarm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + TEST_DELAY_SECONDS * 1_000L
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_KIND, AlarmKind.TEST.name)
            .putExtra(AlarmReceiver.EXTRA_TEST, true)
        val pending = PendingIntent.getBroadcast(
            context, RC_TEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val showIntent = PendingIntent.getActivity(
            context, RC_TEST,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (canScheduleExactAlarms(context)) {
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            Log.i(TAG, "테스트 알람 예약(60초 뒤)")
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    /** 5분 뒤 한 번 더 울리는 알람. */
    fun scheduleSnooze(context: Context, kind: AlarmKind?) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_SNOOZE, true)
            .putExtra(AlarmReceiver.EXTRA_KIND, kind?.name ?: AlarmKind.DAY.name)
        val pending = PendingIntent.getBroadcast(
            context, RC_SNOOZE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val showIntent = PendingIntent.getActivity(
            context, RC_SNOOZE,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (canScheduleExactAlarms(context)) {
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            Log.i(TAG, "5분 뒤 다시 울림 예약")
        } catch (e: SecurityException) {
            Log.w(TAG, "다시 울림 예약 실패", e)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    /** 매일 22:00 자동 복구 예약. */
    fun scheduleRestore(context: Context, config: ShiftConfig) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = restorePendingIntent(context)
        manager.cancel(pending)

        val next = ShiftEngine.nextRestore(LocalDateTime.now(), config)
        val triggerAt = next.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (canScheduleExactAlarms(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            Log.i(TAG, "자동 복구 예약: $next")
        } catch (e: SecurityException) {
            Log.w(TAG, "자동 복구 예약 실패", e)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(alarmPendingIntent(context, null))
        manager.cancel(restorePendingIntent(context))
        manager.cancel(
            PendingIntent.getBroadcast(
                context, RC_SNOOZE,
                Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    private fun setExact(
        context: Context,
        manager: AlarmManager,
        alarm: AlarmInstance,
        pending: PendingIntent
    ) {
        val triggerAt = alarm.triggerAtMillis()
        val showIntent = PendingIntent.getActivity(
            context, RC_ALARM,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (canScheduleExactAlarms(context)) {
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pending)
            } else {
                // 권한이 없을 때: 그래도 최대한 가깝게 울리도록 대체 예약한다.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "정확한 알람 권한 없음, 대체 예약", e)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun alarmPendingIntent(context: Context, alarm: AlarmInstance?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
        if (alarm != null) {
            intent.putExtra(AlarmReceiver.EXTRA_KIND, alarm.kind.name)
            intent.putExtra(AlarmReceiver.EXTRA_DATE, alarm.date.toEpochDay())
            intent.putExtra(AlarmReceiver.EXTRA_HOUR, alarm.time.hour)
            intent.putExtra(AlarmReceiver.EXTRA_MINUTE, alarm.time.minute)
        }
        return PendingIntent.getBroadcast(
            context, RC_ALARM, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun restorePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RestoreReceiver::class.java)
            .setAction(RestoreReceiver.ACTION_RESTORE)
        return PendingIntent.getBroadcast(
            context, RC_RESTORE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

package io.github.twinspapa84.shiftalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.twinspapa84.shiftalarm.core.AlarmInstance
import io.github.twinspapa84.shiftalarm.core.AlarmKind
import io.github.twinspapa84.shiftalarm.core.ShiftEngine
import io.github.twinspapa84.shiftalarm.data.ShiftPrefs
import java.time.LocalDate

/**
 * AlarmManager 가 알람 시각에 깨우는 리시버.
 * 알람 서비스(소리·진동·알림)를 띄우고, 다음 알람을 미리 다시 잡아 둔다.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return

        val prefs = ShiftPrefs(context)
        val config = ShiftEngine.normalize(LocalDate.now(), prefs.load())
        prefs.save(config)

        val snooze = intent.getBooleanExtra(EXTRA_SNOOZE, false)
        val test = intent.getBooleanExtra(EXTRA_TEST, false)
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { runCatching { AlarmKind.valueOf(it) }.getOrNull() }
            ?: AlarmKind.DAY

        // 예약해 둔 뒤 사용자가 스위치를 껐거나 특근을 취소했다면 울리지 않는다.
        if (!snooze && !test && !stillValid(intent, config)) {
            Log.i(TAG, "취소된 알람이라 건너뜀")
            AlarmScheduler.rescheduleAll(context, config)
            return
        }

        val serviceIntent = Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_START)
            .putExtra(AlarmService.EXTRA_KIND, kind.name)
            .putExtra(AlarmService.EXTRA_SNOOZE, snooze)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "알람 서비스 시작 실패", e)
        }

        // 알람이 울리는 동안 다음 알람을 미리 잡아 둔다(사용자가 해제하지 않아도 이어지도록).
        if (!snooze && !test) {
            prefs.setLastFired("${LocalDate.now()} ${kind.label}")
            AlarmScheduler.scheduleNextAlarm(context, config)
        }
    }

    private fun stillValid(intent: Intent, config: io.github.twinspapa84.shiftalarm.core.ShiftConfig): Boolean {
        val epochDay = intent.getLongExtra(EXTRA_DATE, Long.MIN_VALUE)
        val hour = intent.getIntExtra(EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(EXTRA_MINUTE, -1)
        if (epochDay == Long.MIN_VALUE || hour < 0 || minute < 0) return true // 정보가 없으면 그대로 울린다
        val date = LocalDate.ofEpochDay(epochDay)
        val time = java.time.LocalTime.of(hour, minute)
        val scheduled = AlarmInstance(date, time, AlarmKind.DAY)
        return ShiftEngine.alarmsFor(date, config).any {
            it.date == scheduled.date && it.time == scheduled.time
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"

        const val ACTION_FIRE = "io.github.twinspapa84.shiftalarm.action.FIRE"
        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_DATE = "extra_date"
        const val EXTRA_HOUR = "extra_hour"
        const val EXTRA_MINUTE = "extra_minute"
        const val EXTRA_SNOOZE = "extra_snooze"
        const val EXTRA_TEST = "extra_test"
    }
}

package io.github.twinspapa84.shiftalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.twinspapa84.shiftalarm.core.ShiftEngine
import io.github.twinspapa84.shiftalarm.data.ShiftPrefs
import java.time.LocalDate

/**
 * 폰 재부팅 / 앱 업데이트 / 시간·시간대 변경 / 정확한 알람 권한 부여 시
 * 저장된 설정을 다시 읽어 알람과 복구 예약을 새로 건다.
 * (재부팅하면 AlarmManager 예약이 모두 사라지기 때문에 반드시 필요하다)
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val prefs = ShiftPrefs(context)
        val config = ShiftEngine.normalize(LocalDate.now(), prefs.load())
        prefs.save(config)
        AlarmScheduler.rescheduleAll(context, config)
        Log.i(TAG, "재예약 완료 ($action)")
    }

    companion object {
        private const val TAG = "BootReceiver"
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )
    }
}

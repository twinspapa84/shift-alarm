package io.github.twinspapa84.shiftalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.twinspapa84.shiftalarm.core.ShiftEngine
import io.github.twinspapa84.shiftalarm.data.ShiftPrefs
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 매일 22:00(설정값)에 도는 자동 복구.
 * 낮에 알람을 꺼 두었어도 내일이 주간 주 평일이면 다시 켜고, 다음 알람을 정확히 다시 건다.
 */
class RestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != null && intent.action != ACTION_RESTORE) return

        val prefs = ShiftPrefs(context)
        val before = prefs.load()
        val result = ShiftEngine.restore(LocalDateTime.now(), before)
        prefs.save(result.config)
        AlarmScheduler.rescheduleAll(context, result.config)
        Log.i(TAG, "자동 복구: ${result.note}")
    }

    companion object {
        private const val TAG = "RestoreReceiver"
        const val ACTION_RESTORE = "io.github.twinspapa84.shiftalarm.action.RESTORE"
    }
}

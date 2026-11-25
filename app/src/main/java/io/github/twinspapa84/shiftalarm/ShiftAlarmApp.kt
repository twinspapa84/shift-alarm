package io.github.twinspapa84.shiftalarm

import android.app.Application
import io.github.twinspapa84.shiftalarm.alarm.AlarmScheduler
import io.github.twinspapa84.shiftalarm.alarm.NotificationHelper
import io.github.twinspapa84.shiftalarm.core.ShiftEngine
import io.github.twinspapa84.shiftalarm.data.ShiftPrefs
import java.time.LocalDate

/**
 * 앱이 뜰 때마다 알림 채널을 확인하고, 저장된 설정으로 알람/복구 예약을 다시 건다.
 * (앱 업데이트 직후나 정확한 알람 권한을 새로 허용한 직후의 안전장치)
 */
class ShiftAlarmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        val prefs = ShiftPrefs(this)
        val config = ShiftEngine.normalize(LocalDate.now(), prefs.load())
        prefs.save(config)
        AlarmScheduler.rescheduleAll(this, config)
    }
}

package io.github.twinspapa84.shiftalarm.data

import android.content.Context
import android.content.SharedPreferences
import io.github.twinspapa84.shiftalarm.core.ShiftConfig
import java.time.LocalTime

/**
 * 설정 저장소. 알람 리시버/서비스도 같은 값을 읽어야 하므로 SharedPreferences 한 곳만 쓴다.
 */
class ShiftPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): ShiftConfig {
        val d = ShiftConfig.DEFAULT
        return ShiftConfig(
            anchorMondayEpochDay = prefs.getLong(KEY_ANCHOR_DAY, d.anchorMondayEpochDay),
            anchorIsDay = prefs.getBoolean(KEY_ANCHOR_IS_DAY, d.anchorIsDay),
            dayTime = loadTime(KEY_DAY_TIME, d.dayTime),
            nightTime = loadTime(KEY_NIGHT_TIME, d.nightTime),
            dayAlarmEnabled = prefs.getBoolean(KEY_DAY_ON, d.dayAlarmEnabled),
            nightAlarmEnabled = prefs.getBoolean(KEY_NIGHT_ON, d.nightAlarmEnabled),
            saturdayOvertime = prefs.getBoolean(KEY_SAT_OT, d.saturdayOvertime),
            saturdayTargetEpochDay = prefs.getLong(KEY_SAT_TARGET, NO_VALUE)
                .takeIf { it != NO_VALUE },
            saturdayAutoReset = prefs.getBoolean(KEY_SAT_AUTO_RESET, d.saturdayAutoReset),
            restoreTime = loadTime(KEY_RESTORE_TIME, d.restoreTime),
            autoRestoreEnabled = prefs.getBoolean(KEY_AUTO_RESTORE, d.autoRestoreEnabled)
        )
    }

    fun save(config: ShiftConfig) {
        prefs.edit()
            .putLong(KEY_ANCHOR_DAY, config.anchorMondayEpochDay)
            .putBoolean(KEY_ANCHOR_IS_DAY, config.anchorIsDay)
            .putTime(KEY_DAY_TIME, config.dayTime)
            .putTime(KEY_NIGHT_TIME, config.nightTime)
            .putBoolean(KEY_DAY_ON, config.dayAlarmEnabled)
            .putBoolean(KEY_NIGHT_ON, config.nightAlarmEnabled)
            .putBoolean(KEY_SAT_OT, config.saturdayOvertime)
            .putLong(KEY_SAT_TARGET, config.saturdayTargetEpochDay ?: NO_VALUE)
            .putBoolean(KEY_SAT_AUTO_RESET, config.saturdayAutoReset)
            .putTime(KEY_RESTORE_TIME, config.restoreTime)
            .putBoolean(KEY_AUTO_RESTORE, config.autoRestoreEnabled)
            .apply()
    }

    /** 마지막으로 실제 울린 알람(화면 표시용). */
    fun setLastFired(text: String) {
        prefs.edit().putString(KEY_LAST_FIRED, text).apply()
    }

    fun lastFired(): String? = prefs.getString(KEY_LAST_FIRED, null)

    private fun loadTime(key: String, fallback: LocalTime): LocalTime {
        val minutes = prefs.getInt(key, fallback.hour * 60 + fallback.minute)
        return LocalTime.of((minutes / 60) % 24, minutes % 60)
    }

    private fun SharedPreferences.Editor.putTime(key: String, time: LocalTime): SharedPreferences.Editor =
        putInt(key, time.hour * 60 + time.minute)

    companion object {
        private const val NAME = "shift_alarm_prefs"
        private const val NO_VALUE = Long.MIN_VALUE

        private const val KEY_ANCHOR_DAY = "anchor_monday_epoch_day"
        private const val KEY_ANCHOR_IS_DAY = "anchor_is_day"
        private const val KEY_DAY_TIME = "day_time_minutes"
        private const val KEY_NIGHT_TIME = "night_time_minutes"
        private const val KEY_DAY_ON = "day_alarm_enabled"
        private const val KEY_NIGHT_ON = "night_alarm_enabled"
        private const val KEY_SAT_OT = "saturday_overtime"
        private const val KEY_SAT_TARGET = "saturday_target_epoch_day"
        private const val KEY_SAT_AUTO_RESET = "saturday_auto_reset"
        private const val KEY_RESTORE_TIME = "restore_time_minutes"
        private const val KEY_AUTO_RESTORE = "auto_restore_enabled"
        private const val KEY_LAST_FIRED = "last_fired_text"
    }
}

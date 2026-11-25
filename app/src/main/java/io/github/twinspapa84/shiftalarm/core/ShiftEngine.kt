package io.github.twinspapa84.shiftalarm.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 앱의 핵심 로직. 안드로이드 클래스에 전혀 의존하지 않으므로 JVM 단위 테스트로
 * 그대로 검증할 수 있다 (교대 주차 계산 / 다음 알람 계산 / 22:00 자동 복구 판단).
 */
object ShiftEngine {

    const val DAYS_PER_WEEK = 7L

    /** 한 주의 시작은 월요일. epochDay 0 = 1970-01-01(목). */
    fun mondayOf(date: LocalDate): LocalDate {
        val indexFromMonday = Math.floorMod(date.toEpochDay() + 3L, 7L) // 월=0 ... 일=6
        return date.minusDays(indexFromMonday)
    }

    /** date 가 속한 주의 근무 종류. 기준 주에서 1주씩 번갈아 바뀐다. */
    fun weekType(date: LocalDate, config: ShiftConfig): WeekType {
        val weeks = Math.floorDiv(
            mondayOf(date).toEpochDay() - mondayOf(config.anchorMonday).toEpochDay(),
            DAYS_PER_WEEK
        )
        val switched = Math.floorMod(weeks, 2L) != 0L
        val isDay = if (switched) !config.anchorIsDay else config.anchorIsDay
        return if (isDay) WeekType.DAY else WeekType.NIGHT
    }

    fun isWeekday(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY

    fun isSaturday(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SATURDAY

    /** 오늘부터 가장 가까운 토요일(오늘이 토요일이면 오늘). */
    fun upcomingSaturday(date: LocalDate): LocalDate {
        val saturday = mondayOf(date).plusDays(5)
        return if (saturday.isBefore(date)) saturday.plusDays(DAYS_PER_WEEK) else saturday
    }

    /** 토요일 특근 스위치가 이 날짜에 실제로 적용되는지. */
    fun saturdayOvertimeApplies(date: LocalDate, config: ShiftConfig): Boolean {
        if (!config.saturdayOvertime) return false
        if (!isSaturday(date)) return false
        val target = config.saturdayTargetEpochDay
        return target == null || target == date.toEpochDay()
    }

    /** 주간 알람(평일 주간 근무 + 토요일 특근)이 이 날짜에 울려야 하는지. */
    fun dayAlarmApplies(date: LocalDate, config: ShiftConfig): Boolean {
        if (isWeekday(date)) return weekType(date, config) == WeekType.DAY
        return saturdayOvertimeApplies(date, config)
    }

    /** 야간 알람은 야간 근무 주의 평일에만 울린다. */
    fun nightAlarmApplies(date: LocalDate, config: ShiftConfig): Boolean =
        isWeekday(date) && weekType(date, config) == WeekType.NIGHT

    /** 그 날짜에 실제로 울릴 알람 목록(사용자 스위치 반영). */
    fun alarmsFor(date: LocalDate, config: ShiftConfig): List<AlarmInstance> {
        val alarms = mutableListOf<AlarmInstance>()
        if (config.dayAlarmEnabled && dayAlarmApplies(date, config)) {
            val kind = if (isSaturday(date)) AlarmKind.SATURDAY_OVERTIME else AlarmKind.DAY
            alarms += AlarmInstance(date, config.dayTime, kind)
        }
        if (config.nightAlarmEnabled && nightAlarmApplies(date, config)) {
            alarms += AlarmInstance(date, config.nightTime, AlarmKind.NIGHT)
        }
        return alarms.sortedBy { it.time }
    }

    /**
     * now 이후 가장 가까운 알람. 없으면 null.
     * [horizonDays] 는 탐색 범위(기본 14일 = 2주, 어떤 교대 조합이든 한 번은 걸린다).
     */
    fun nextAlarm(
        now: LocalDateTime,
        config: ShiftConfig,
        horizonDays: Int = 14
    ): AlarmInstance? {
        val start = now.toLocalDate()
        for (offset in 0..horizonDays.toLong()) {
            val date = start.plusDays(offset)
            for (alarm in alarmsFor(date, config)) {
                if (alarm.dateTime.isAfter(now)) return alarm
            }
        }
        return null
    }

    /**
     * 토요일 특근 스위치 정리.
     * - 지정한 토요일이 지났으면: 자동 리셋 ON 이면 스위치를 끄고, OFF 이면 다음 토요일로 넘긴다.
     * - 스위치가 켜져 있는데 대상 토요일이 없으면 가장 가까운 토요일을 채운다.
     */
    fun normalize(today: LocalDate, config: ShiftConfig): ShiftConfig {
        if (!config.saturdayOvertime) {
            return if (config.saturdayTargetEpochDay == null) config
            else config.copy(saturdayTargetEpochDay = null)
        }
        val target = config.saturdayTarget
        if (target == null) {
            return config.copy(saturdayTargetEpochDay = upcomingSaturday(today).toEpochDay())
        }
        if (target.isBefore(today)) {
            return if (config.saturdayAutoReset) {
                config.copy(saturdayOvertime = false, saturdayTargetEpochDay = null)
            } else {
                config.copy(saturdayTargetEpochDay = upcomingSaturday(today).toEpochDay())
            }
        }
        if (!isSaturday(target)) {
            return config.copy(saturdayTargetEpochDay = upcomingSaturday(today).toEpochDay())
        }
        return config
    }

    /**
     * 매일 22:00 자동 복구.
     * 내일 근무가 주간 주 평일(또는 특근 토요일)이면 주간 알람을 다시 ON,
     * 아니면(야간 주·주말) 주간 알람을 OFF 로 맞춘 뒤 다음 알람을 다시 잡는다.
     */
    fun restore(now: LocalDateTime, config: ShiftConfig): RestoreResult {
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val normalized = normalize(today, config)
        val saturdayReset = config.saturdayOvertime && !normalized.saturdayOvertime
        val tomorrowType = weekType(tomorrow, normalized)

        if (!normalized.autoRestoreEnabled) {
            return RestoreResult(
                config = normalized,
                tomorrow = tomorrow,
                tomorrowWeekType = tomorrowType,
                dayAlarmTurnedOn = false,
                dayAlarmTurnedOff = false,
                saturdayOvertimeReset = saturdayReset,
                skipped = true,
                note = "자동 복구가 꺼져 있어 알람 상태를 건드리지 않았습니다."
            )
        }

        val shouldBeOn = dayAlarmApplies(tomorrow, normalized)
        val newConfig = normalized.copy(dayAlarmEnabled = shouldBeOn)
        val turnedOn = shouldBeOn && !normalized.dayAlarmEnabled
        val turnedOff = !shouldBeOn && normalized.dayAlarmEnabled

        val note = when {
            turnedOn -> "내일(${tomorrowType.label} ${weekdayName(tomorrow)}) 주간 알람을 다시 켰습니다."
            turnedOff -> "내일은 ${tomorrowType.label} ${weekdayName(tomorrow)}이라 주간 알람을 껐습니다."
            shouldBeOn -> "내일 주간 알람은 이미 켜져 있습니다."
            else -> "내일은 울릴 주간 알람이 없습니다."
        }

        return RestoreResult(
            config = newConfig,
            tomorrow = tomorrow,
            tomorrowWeekType = tomorrowType,
            dayAlarmTurnedOn = turnedOn,
            dayAlarmTurnedOff = turnedOff,
            saturdayOvertimeReset = saturdayReset,
            skipped = false,
            note = note
        )
    }

    /** now 이후 가장 가까운 22:00 복구 시각. */
    fun nextRestore(now: LocalDateTime, config: ShiftConfig): LocalDateTime {
        val todayAt = LocalDateTime.of(now.toLocalDate(), config.restoreTime)
        return if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
    }

    /** 앱에서 "이번 주를 주간/야간으로" 눌렀을 때 기준 주를 다시 잡는다. */
    fun setCurrentWeekType(today: LocalDate, target: WeekType, config: ShiftConfig): ShiftConfig =
        config.copy(
            anchorMondayEpochDay = mondayOf(today).toEpochDay(),
            anchorIsDay = target == WeekType.DAY
        )

    /** 토요일 특근 스위치를 켤 때 대상 토요일을 잡아 준다. 켜면 주간 알람도 함께 켠다. */
    fun withSaturdayOvertime(today: LocalDate, enabled: Boolean, config: ShiftConfig): ShiftConfig =
        if (enabled) {
            config.copy(
                saturdayOvertime = true,
                saturdayTargetEpochDay = upcomingSaturday(today).toEpochDay(),
                dayAlarmEnabled = true
            )
        } else {
            config.copy(saturdayOvertime = false, saturdayTargetEpochDay = null)
        }

    /** 화면 표시용: 이번 주 / 다음 주 근무 종류와 기간. */
    fun weekRangeLabel(date: LocalDate): String {
        val monday = mondayOf(date)
        val sunday = monday.plusDays(6)
        return "%d/%d~%d/%d".format(
            monday.monthValue, monday.dayOfMonth, sunday.monthValue, sunday.dayOfMonth
        )
    }

    fun weekdayName(date: LocalDate): String = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "월요일"
        DayOfWeek.TUESDAY -> "화요일"
        DayOfWeek.WEDNESDAY -> "수요일"
        DayOfWeek.THURSDAY -> "목요일"
        DayOfWeek.FRIDAY -> "금요일"
        DayOfWeek.SATURDAY -> "토요일"
        DayOfWeek.SUNDAY -> "일요일"
    }

    fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)
}

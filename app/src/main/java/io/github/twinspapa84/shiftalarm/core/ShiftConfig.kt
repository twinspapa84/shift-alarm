package io.github.twinspapa84.shiftalarm.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 주간 / 야간 교대 종류. */
enum class WeekType {
    DAY,
    NIGHT;

    val label: String
        get() = if (this == DAY) "주간" else "야간"

    fun other(): WeekType = if (this == DAY) NIGHT else DAY
}

/** 알람이 어떤 근무를 위한 것인지. */
enum class AlarmKind {
    DAY,
    NIGHT,
    SATURDAY_OVERTIME,
    TEST;

    val label: String
        get() = when (this) {
            DAY -> "주간 알람"
            NIGHT -> "야간 알람"
            SATURDAY_OVERTIME -> "토요일 특근 알람"
            TEST -> "테스트 알람"
        }
}

/**
 * 앱의 전체 설정. 화면(Activity)과 알람(리시버/서비스)이 같은 값을 보도록
 * SharedPreferences 에 이 한 덩어리만 저장한다.
 */
data class ShiftConfig(
    /** 기준이 되는 월요일 (기본 2026-11-23). 이 날이 속한 주의 근무 종류가 [anchorIsDay]. */
    val anchorMondayEpochDay: Long,
    /** 기준 주가 주간이면 true, 야간이면 false. */
    val anchorIsDay: Boolean,
    val dayTime: LocalTime,
    val nightTime: LocalTime,
    val dayAlarmEnabled: Boolean,
    val nightAlarmEnabled: Boolean,
    val saturdayOvertime: Boolean,
    /** 토요일 특근 스위치가 가리키는 토요일 (epochDay). null 이면 아직 지정 안 됨. */
    val saturdayTargetEpochDay: Long?,
    val saturdayAutoReset: Boolean,
    val restoreTime: LocalTime,
    val autoRestoreEnabled: Boolean
) {
    val anchorMonday: LocalDate
        get() = LocalDate.ofEpochDay(anchorMondayEpochDay)

    val saturdayTarget: LocalDate?
        get() = saturdayTargetEpochDay?.let { LocalDate.ofEpochDay(it) }

    companion object {
        /** 2026-11-23(월) = 주간 주. */
        val DEFAULT_ANCHOR: LocalDate = LocalDate.of(2026, 11, 23)

        /** 출근 1시간 전을 기본 알람 시각으로 둔다(주간 05:45, 야간 14:30). */
        val DEFAULT = ShiftConfig(
            anchorMondayEpochDay = DEFAULT_ANCHOR.toEpochDay(),
            anchorIsDay = true,
            dayTime = LocalTime.of(5, 45),
            nightTime = LocalTime.of(14, 30),
            dayAlarmEnabled = true,
            nightAlarmEnabled = false,
            saturdayOvertime = false,
            saturdayTargetEpochDay = null,
            saturdayAutoReset = true,
            restoreTime = LocalTime.of(22, 0),
            autoRestoreEnabled = true
        )
    }
}

/** 다음에 울릴 알람 하나. */
data class AlarmInstance(
    val date: LocalDate,
    val time: LocalTime,
    val kind: AlarmKind
) {
    val dateTime: LocalDateTime
        get() = LocalDateTime.of(date, time)

    fun triggerAtMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    val weekdayLabel: String
        get() = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "월"
            DayOfWeek.TUESDAY -> "화"
            DayOfWeek.WEDNESDAY -> "수"
            DayOfWeek.THURSDAY -> "목"
            DayOfWeek.FRIDAY -> "금"
            DayOfWeek.SATURDAY -> "토"
            DayOfWeek.SUNDAY -> "일"
        }
}

/** 22:00 자동 복구가 무엇을 했는지. 화면 표시와 로그에 쓴다. */
data class RestoreResult(
    val config: ShiftConfig,
    val tomorrow: LocalDate,
    val tomorrowWeekType: WeekType,
    val dayAlarmTurnedOn: Boolean,
    val dayAlarmTurnedOff: Boolean,
    val saturdayOvertimeReset: Boolean,
    val skipped: Boolean,
    val note: String
)

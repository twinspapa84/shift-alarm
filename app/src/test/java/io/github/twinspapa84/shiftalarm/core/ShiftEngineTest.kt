package io.github.twinspapa84.shiftalarm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 핵심 로직 단위 테스트 (JVM). 기준: 2026-11-23(월) = 주간 주.
 */
class ShiftEngineTest {

    private fun config(
        dayEnabled: Boolean = true,
        nightEnabled: Boolean = false,
        overtime: Boolean = false,
        overtimeTarget: LocalDate? = null,
        autoReset: Boolean = true,
        autoRestore: Boolean = true,
        anchor: LocalDate = ShiftConfig.DEFAULT_ANCHOR,
        anchorIsDay: Boolean = true
    ) = ShiftConfig.DEFAULT.copy(
        anchorMondayEpochDay = anchor.toEpochDay(),
        anchorIsDay = anchorIsDay,
        dayAlarmEnabled = dayEnabled,
        nightAlarmEnabled = nightEnabled,
        saturdayOvertime = overtime,
        saturdayTargetEpochDay = overtimeTarget?.toEpochDay(),
        saturdayAutoReset = autoReset,
        autoRestoreEnabled = autoRestore
    )

    private fun date(y: Int, m: Int, d: Int): LocalDate = LocalDate.of(y, m, d)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): LocalDateTime =
        LocalDateTime.of(y, m, d, h, min)

    // ---------------------------------------------------------------- 주차 계산

    @Test
    fun `기준일 2026-11-23 은 주간 주다`() {
        val c = config()
        assertEquals(WeekType.DAY, ShiftEngine.weekType(date(2026, 11, 23), c))
    }

    @Test
    fun `한 주는 월요일부터 일요일까지 같은 근무다`() {
        val c = config()
        for (day in 23..29) {
            assertEquals("11/$day", WeekType.DAY, ShiftEngine.weekType(date(2026, 11, day), c))
        }
    }

    @Test
    fun `다음 주는 야간이고 그다음 주는 다시 주간이다`() {
        val c = config()
        assertEquals(WeekType.NIGHT, ShiftEngine.weekType(date(2026, 11, 30), c))
        assertEquals(WeekType.NIGHT, ShiftEngine.weekType(date(2026, 12, 6), c))
        assertEquals(WeekType.DAY, ShiftEngine.weekType(date(2026, 12, 7), c))
        assertEquals(WeekType.DAY, ShiftEngine.weekType(date(2026, 12, 13), c))
        assertEquals(WeekType.NIGHT, ShiftEngine.weekType(date(2026, 12, 14), c))
    }

    @Test
    fun `기준일 이전 주도 1주 간격으로 계산된다`() {
        val c = config()
        assertEquals(WeekType.NIGHT, ShiftEngine.weekType(date(2026, 11, 16), c))
        assertEquals(WeekType.DAY, ShiftEngine.weekType(date(2026, 11, 9), c))
    }

    @Test
    fun `mondayOf 는 항상 그 주 월요일을 준다`() {
        assertEquals(DayOfWeek.MONDAY, ShiftEngine.mondayOf(date(2026, 11, 23)).dayOfWeek)
        assertEquals(date(2026, 11, 23), ShiftEngine.mondayOf(date(2026, 11, 29)))
        assertEquals(date(2026, 11, 23), ShiftEngine.mondayOf(date(2026, 11, 23)))
        assertEquals(date(2026, 11, 30), ShiftEngine.mondayOf(date(2026, 12, 6)))
    }

    @Test
    fun `이번 주를 야간으로 다시 잡으면 이번 주만 야간이 된다`() {
        val today = date(2026, 11, 25) // 수요일, 원래 주간
        val c = config()
        val changed = ShiftEngine.setCurrentWeekType(today, WeekType.NIGHT, c)
        assertEquals(WeekType.NIGHT, ShiftEngine.weekType(today, changed))
        assertEquals(WeekType.NIGHT, ShiftEngine.weekType(date(2026, 11, 29), changed))
        assertEquals(WeekType.DAY, ShiftEngine.weekType(date(2026, 11, 30), changed))
        assertEquals(WeekType.NIGHT, ShiftEngine.weekType(date(2026, 12, 7), changed))
        assertEquals(WeekType.DAY, ShiftEngine.weekType(date(2026, 12, 14), changed))
    }

    // ------------------------------------------------------------- 알람 적용 범위

    @Test
    fun `주간 주 평일에는 주간 알람이 울린다`() {
        val c = config()
        for (day in 23..27) {
            assertTrue("11/$day", ShiftEngine.dayAlarmApplies(date(2026, 11, day), c))
        }
        assertFalse(ShiftEngine.dayAlarmApplies(date(2026, 11, 28), c)) // 토
        assertFalse(ShiftEngine.dayAlarmApplies(date(2026, 11, 29), c)) // 일
    }

    @Test
    fun `야간 주에는 주간 알람이 울리지 않는다`() {
        val c = config()
        assertFalse(ShiftEngine.dayAlarmApplies(date(2026, 11, 30), c))
        assertFalse(ShiftEngine.dayAlarmApplies(date(2026, 12, 1), c))
        assertFalse(ShiftEngine.dayAlarmApplies(date(2026, 12, 2), c))
        assertFalse(ShiftEngine.dayAlarmApplies(date(2026, 12, 4), c))
    }

    @Test
    fun `야간 알람은 야간 주 평일에만 울린다`() {
        val c = config(nightEnabled = true)
        assertFalse(ShiftEngine.nightAlarmApplies(date(2026, 11, 23), c))
        assertTrue(ShiftEngine.nightAlarmApplies(date(2026, 11, 30), c))
        assertFalse(ShiftEngine.nightAlarmApplies(date(2026, 12, 5), c)) // 토
    }

    @Test
    fun `토요일 특근을 켜면 토요일에 주간 알람이 울린다`() {
        val saturday = date(2026, 11, 28)
        val c = config(overtime = true, overtimeTarget = saturday)
        assertTrue(ShiftEngine.dayAlarmApplies(saturday, c))
        val alarms = ShiftEngine.alarmsFor(saturday, c)
        assertEquals(1, alarms.size)
        assertEquals(AlarmKind.SATURDAY_OVERTIME, alarms[0].kind)
        assertEquals(ShiftConfig.DEFAULT.dayTime, alarms[0].time)
    }

    @Test
    fun `야간 주 토요일 특근도 주간 알람 시각에 울린다`() {
        val saturday = date(2026, 12, 5)
        val c = config(overtime = true, overtimeTarget = saturday)
        assertTrue(ShiftEngine.dayAlarmApplies(saturday, c))
    }

    @Test
    fun `주간 알람 스위치를 끄면 알람이 없다`() {
        val c = config(dayEnabled = false)
        assertTrue(ShiftEngine.alarmsFor(date(2026, 11, 23), c).isEmpty())
        assertNull(ShiftEngine.nextAlarm(at(2026, 11, 23, 0, 10), c))
    }

    // ------------------------------------------------------------- 다음 알람 계산

    @Test
    fun `금요일 밤에는 주말을 건너 월요일 알람을 잡는다`() {
        val c = config()
        val next = ShiftEngine.nextAlarm(at(2026, 11, 27, 22, 30), c)
        assertNotNull(next)
        assertEquals(date(2026, 12, 7), next!!.date)
        assertEquals(LocalTime.of(5, 45), next.time)
        assertEquals(AlarmKind.DAY, next.kind)
    }

    @Test
    fun `야간 알람을 켜면 야간 주 월요일 야간 알람이 먼저 잡힌다`() {
        val c = config(nightEnabled = true)
        val next = ShiftEngine.nextAlarm(at(2026, 11, 27, 22, 30), c)
        assertNotNull(next)
        assertEquals(date(2026, 11, 30), next!!.date)
        assertEquals(ShiftConfig.DEFAULT.nightTime, next.time)
        assertEquals(AlarmKind.NIGHT, next.kind)
    }

    @Test
    fun `알람 시각이 지나면 다음 날 알람으로 넘어간다`() {
        val c = config()
        val beforeRinging = ShiftEngine.nextAlarm(at(2026, 11, 24, 5, 44), c)
        assertEquals(date(2026, 11, 24), beforeRinging!!.date)
        val afterRinging = ShiftEngine.nextAlarm(at(2026, 11, 24, 5, 46), c)
        assertEquals(date(2026, 11, 25), afterRinging!!.date)
    }

    @Test
    fun `정각에는 그 알람이 아니라 다음 알람을 잡는다`() {
        val c = config()
        val next = ShiftEngine.nextAlarm(at(2026, 11, 24, 5, 45), c)
        assertEquals(date(2026, 11, 25), next!!.date)
    }

    @Test
    fun `다음 알람 트리거 시각은 그 날짜의 알람 시각이다`() {
        val c = config()
        val next = ShiftEngine.nextAlarm(at(2026, 11, 26, 21, 0), c)!!
        val millis = next.triggerAtMillis()
        assertEquals(
            LocalDateTime.of(2026, 11, 27, 5, 45).atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli(),
            millis
        )
    }

    // --------------------------------------------------------------- 22시 자동 복구

    @Test
    fun `낮에 껐어도 목요일 밤에는 금요일 주간 알람을 다시 켠다`() {
        val c = config(dayEnabled = false)
        val result = ShiftEngine.restore(at(2026, 11, 26, 22, 0), c)
        assertTrue(result.dayAlarmTurnedOn)
        assertTrue(result.config.dayAlarmEnabled)
        assertEquals(date(2026, 11, 27), result.tomorrow)
        assertEquals(WeekType.DAY, result.tomorrowWeekType)
    }

    @Test
    fun `금요일 밤에는 토요일 주간 알람을 꺼 둔다`() {
        val c = config(dayEnabled = true)
        val result = ShiftEngine.restore(at(2026, 11, 27, 22, 0), c)
        assertTrue(result.dayAlarmTurnedOff)
        assertFalse(result.config.dayAlarmEnabled)
        assertNull(ShiftEngine.nextAlarm(at(2026, 11, 27, 22, 1), result.config))
    }

    @Test
    fun `토요일 특근이 켜져 있으면 금요일 밤에 꺼지지 않는다`() {
        val c = config(dayEnabled = true, overtime = true, overtimeTarget = date(2026, 11, 28))
        val result = ShiftEngine.restore(at(2026, 11, 27, 22, 0), c)
        assertFalse(result.dayAlarmTurnedOff)
        assertTrue(result.config.dayAlarmEnabled)
        val next = ShiftEngine.nextAlarm(at(2026, 11, 27, 22, 1), result.config)
        assertEquals(date(2026, 11, 28), next!!.date)
        assertEquals(AlarmKind.SATURDAY_OVERTIME, next.kind)
    }

    @Test
    fun `일요일 밤에는 야간 주라서 주간 알람을 끈다`() {
        val c = config(dayEnabled = true)
        val result = ShiftEngine.restore(at(2026, 11, 29, 22, 0), c)
        assertTrue(result.dayAlarmTurnedOff)
        assertEquals(WeekType.NIGHT, result.tomorrowWeekType)
        assertEquals(date(2026, 11, 30), result.tomorrow)
    }

    @Test
    fun `야간 주 수요일 밤에도 주간 알람은 꺼진 상태로 유지된다`() {
        val c = config(dayEnabled = false)
        val result = ShiftEngine.restore(at(2026, 12, 2, 22, 0), c)
        assertFalse(result.config.dayAlarmEnabled)
        assertFalse(result.dayAlarmTurnedOn)
        assertFalse(result.dayAlarmTurnedOff)
    }

    @Test
    fun `자동 복구를 끄면 알람 상태를 건드리지 않는다`() {
        val c = config(dayEnabled = false, autoRestore = false)
        val result = ShiftEngine.restore(at(2026, 11, 26, 22, 0), c)
        assertTrue(result.skipped)
        assertFalse(result.config.dayAlarmEnabled)
    }

    @Test
    fun `복구 시각이 지났으면 다음 날 복구를 예약한다`() {
        val c = config()
        assertEquals(
            at(2026, 11, 23, 22, 0),
            ShiftEngine.nextRestore(at(2026, 11, 23, 21, 59), c)
        )
        assertEquals(
            at(2026, 11, 24, 22, 0),
            ShiftEngine.nextRestore(at(2026, 11, 23, 22, 0), c)
        )
    }

    // ------------------------------------------------------------ 토요일 특근 관리

    @Test
    fun `가장 가까운 토요일 계산`() {
        assertEquals(date(2026, 11, 28), ShiftEngine.upcomingSaturday(date(2026, 11, 23)))
        assertEquals(date(2026, 11, 28), ShiftEngine.upcomingSaturday(date(2026, 11, 28)))
        assertEquals(date(2026, 12, 5), ShiftEngine.upcomingSaturday(date(2026, 11, 29)))
    }

    @Test
    fun `특근 스위치를 켜면 대상 토요일이 잡힌다`() {
        val c = ShiftEngine.withSaturdayOvertime(date(2026, 11, 25), true, config())
        assertTrue(c.saturdayOvertime)
        assertEquals(date(2026, 11, 28), c.saturdayTarget)
    }

    @Test
    fun `주간 알람을 끄고 특근만 켜도 토요일 알람이 살아난다`() {
        val before = config(dayEnabled = false)
        val c = ShiftEngine.withSaturdayOvertime(date(2026, 11, 27), true, before)
        assertTrue(c.dayAlarmEnabled)
        val next = ShiftEngine.nextAlarm(at(2026, 11, 27, 23, 0), c)
        assertEquals(saturdayTargetOf(c), next!!.date)
        assertEquals(AlarmKind.SATURDAY_OVERTIME, next.kind)
    }

    private fun saturdayTargetOf(c: ShiftConfig): LocalDate = c.saturdayTarget!!

    @Test
    fun `자동 리셋이 켜져 있으면 토요일이 지난 뒤 특근이 꺼진다`() {
        val c = config(overtime = true, overtimeTarget = date(2026, 11, 28))
        val normalized = ShiftEngine.normalize(date(2026, 11, 29), c)
        assertFalse(normalized.saturdayOvertime)
        assertNull(normalized.saturdayTargetEpochDay)
        val result = ShiftEngine.restore(at(2026, 11, 29, 22, 0), c)
        assertTrue(result.saturdayOvertimeReset)
        assertFalse(result.config.saturdayOvertime)
    }

    @Test
    fun `자동 리셋이 꺼져 있으면 다음 토요일로 넘어간다`() {
        val c = config(overtime = true, overtimeTarget = date(2026, 11, 28), autoReset = false)
        val normalized = ShiftEngine.normalize(date(2026, 11, 29), c)
        assertTrue(normalized.saturdayOvertime)
        assertEquals(date(2026, 12, 5), normalized.saturdayTarget)
    }

    @Test
    fun `특근 스위치를 켠 뒤 토요일 당일에는 유지된다`() {
        val c = config(overtime = true, overtimeTarget = date(2026, 11, 28))
        val normalized = ShiftEngine.normalize(date(2026, 11, 28), c)
        assertTrue(normalized.saturdayOvertime)
        assertEquals(date(2026, 11, 28), normalized.saturdayTarget)
    }

    @Test
    fun `대상 토요일 정보가 없으면 가장 가까운 토요일로 채운다`() {
        val c = config(overtime = true, overtimeTarget = null)
        val normalized = ShiftEngine.normalize(date(2026, 11, 24), c)
        assertTrue(normalized.saturdayOvertime)
        assertEquals(date(2026, 11, 28), normalized.saturdayTarget)
    }

    @Test
    fun `특근이 꺼져 있으면 대상 토요일은 비운다`() {
        val c = config(overtime = false, overtimeTarget = date(2026, 11, 28))
        assertNull(ShiftEngine.normalize(date(2026, 11, 24), c).saturdayTargetEpochDay)
    }

    // ------------------------------------------------------------------ 종합 시뮬레이션

    @Test
    fun `8주 교대 시뮬레이션 - 주간 주 평일과 특근 토요일에만 주간 알람`() {
        var c = config()
        val start = date(2026, 11, 23)
        val expectedDayDates = mutableSetOf<LocalDate>()
        val expectedNightDates = mutableSetOf<LocalDate>()

        // 2026-11-23 ~ 2027-01-17 (8주) 를 날짜별로 돌며 기대값을 만든다.
        var cursor = start
        while (!cursor.isAfter(date(2027, 1, 17))) {
            val type = ShiftEngine.weekType(cursor, c)
            val isWeekday = cursor.dayOfWeek != DayOfWeek.SATURDAY && cursor.dayOfWeek != DayOfWeek.SUNDAY
            if (isWeekday && type == WeekType.DAY) expectedDayDates += cursor
            if (isWeekday && type == WeekType.NIGHT) expectedNightDates += cursor
            cursor = cursor.plusDays(1)
        }

        // 매일 밤 22:00 자동 복구를 돌리면서 실제 알람 스케줄이 기대와 같은지 확인한다.
        cursor = start
        var checkedDays = 0
        while (!cursor.isAfter(date(2027, 1, 10))) {
            c = ShiftEngine.restore(LocalDateTime.of(cursor, LocalTime.of(22, 0)), c).config
            val tomorrow = cursor.plusDays(1)
            val alarms = ShiftEngine.alarmsFor(tomorrow, c)
            val dayAlarmDates = alarms.filter { it.kind == AlarmKind.DAY || it.kind == AlarmKind.SATURDAY_OVERTIME }
            if (tomorrow in expectedDayDates) {
                assertEquals("$tomorrow 주간 알람", 1, dayAlarmDates.size)
            } else {
                assertEquals("$tomorrow 주간 알람 없음", 0, dayAlarmDates.size)
            }
            checkedDays++
            cursor = cursor.plusDays(1)
        }
        assertTrue(checkedDays >= 49)
    }

    @Test
    fun `시뮬레이션 표를 출력하고 CSV로 남긴다`() {
        val c = config(nightEnabled = true)
        val builder = StringBuilder()
        builder.appendLine("날짜         요일    주차   22시복구 후 주간알람      야간알람  스위치")
        val rows = mutableListOf("date,weekday,weekType,dayOn,dayTime,dayKind,nightTime")
        var cursor = date(2026, 11, 21)
        var running = c
        while (!cursor.isAfter(date(2026, 12, 20))) {
            running = ShiftEngine.restore(LocalDateTime.of(cursor, LocalTime.of(22, 0)), running).config
            val tomorrow = cursor.plusDays(1)
            val alarms = ShiftEngine.alarmsFor(tomorrow, running)
            val dayAlarm = alarms.firstOrNull { it.kind != AlarmKind.NIGHT }
            val nightAlarm = alarms.firstOrNull { it.kind == AlarmKind.NIGHT }
            val dayLabel = dayAlarm?.let { "${it.kind.label} ${ShiftEngine.formatTime(it.time)}" } ?: "-"
            val nightLabel = nightAlarm?.let { ShiftEngine.formatTime(it.time) } ?: "-"
            val sat = if (ShiftEngine.saturdayOvertimeApplies(tomorrow, running)) " [특근]" else ""
            builder.appendLine(
                String.format(
                    "%-12s %-6s %-5s %-22s %-8s %s%s",
                    tomorrow.toString(),
                    ShiftEngine.weekdayName(tomorrow),
                    ShiftEngine.weekType(tomorrow, running).label,
                    dayLabel,
                    nightLabel,
                    running.dayAlarmEnabled,
                    sat
                )
            )
            rows += listOf(
                tomorrow.toString(),
                tomorrow.dayOfWeek.name,
                ShiftEngine.weekType(tomorrow, running).label,
                running.dayAlarmEnabled.toString(),
                dayAlarm?.let { ShiftEngine.formatTime(it.time) } ?: "-",
                dayAlarm?.kind?.name ?: "-",
                nightAlarm?.let { ShiftEngine.formatTime(it.time) } ?: "-"
            ).joinToString(",")
            cursor = cursor.plusDays(1)
        }
        println(builder.toString())
        val out = java.io.File("build/test-output/schedule-kt.csv")
        out.parentFile?.mkdirs()
        out.writeText(rows.joinToString("\n"))
        println("CSV 저장: ${out.absolutePath}")
        assertTrue(rows.size > 25)
    }
}

package io.github.twinspapa84.shiftalarm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.twinspapa84.shiftalarm.alarm.AlarmScheduler
import io.github.twinspapa84.shiftalarm.core.ShiftConfig
import io.github.twinspapa84.shiftalarm.core.ShiftEngine
import io.github.twinspapa84.shiftalarm.core.WeekType
import io.github.twinspapa84.shiftalarm.databinding.ActivityMainBinding
import io.github.twinspapa84.shiftalarm.data.ShiftPrefs
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 메인 화면: 이번 주 교대 확인, 주간/야간 알람 시간, 토요일 특근, 자동 복구, 권한 안내.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: ShiftPrefs
    private var config: ShiftConfig = ShiftConfig.DEFAULT
    private var rendering = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = ShiftPrefs(this)
        config = ShiftEngine.normalize(LocalDate.now(), prefs.load())
        prefs.save(config)
        AlarmScheduler.rescheduleAll(this, config)

        bindListeners()
    }

    override fun onResume() {
        super.onResume()
        config = ShiftEngine.normalize(LocalDate.now(), prefs.load())
        prefs.save(config)
        AlarmScheduler.rescheduleAll(this, config)
        render()
        refreshPermissions()
    }

    private fun bindListeners() {
        binding.btnSetDay.setOnClickListener { setWeek(WeekType.DAY) }
        binding.btnSetNight.setOnClickListener { setWeek(WeekType.NIGHT) }

        binding.switchSatOvertime.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            config = ShiftEngine.withSaturdayOvertime(LocalDate.now(), checked, config)
            persist()
        }

        binding.switchSatAutoReset.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            config = config.copy(saturdayAutoReset = checked)
            persist()
        }

        binding.switchDayAlarm.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            config = config.copy(dayAlarmEnabled = checked)
            persist()
        }

        binding.switchNightAlarm.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            config = config.copy(nightAlarmEnabled = checked)
            persist()
        }

        binding.switchAutoRestore.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            config = config.copy(autoRestoreEnabled = checked)
            persist()
        }

        binding.btnDayTime.setOnClickListener {
            pickTime(getString(R.string.time_dialog_day), config.dayTime) { picked ->
                config = config.copy(dayTime = picked)
                persist()
            }
        }
        binding.btnNightTime.setOnClickListener {
            pickTime(getString(R.string.time_dialog_night), config.nightTime) { picked ->
                config = config.copy(nightTime = picked)
                persist()
            }
        }
        binding.btnRestoreTime.setOnClickListener {
            pickTime(getString(R.string.time_dialog_restore), config.restoreTime) { picked ->
                config = config.copy(restoreTime = picked)
                persist()
            }
        }

        binding.btnExact.setOnClickListener { openExactAlarmSettings() }
        binding.btnNotif.setOnClickListener { requestNotificationPermission() }
        binding.btnBattery.setOnClickListener { requestBatteryExemption() }
        binding.btnTestAlarm.setOnClickListener {
            AlarmScheduler.scheduleTestAlarm(this)
            Toast.makeText(this, R.string.test_alarm_toast, Toast.LENGTH_LONG).show()
        }
    }

    private fun setWeek(type: WeekType) {
        config = ShiftEngine.setCurrentWeekType(LocalDate.now(), type, config)
        persist()
        Toast.makeText(this, getString(R.string.week_set_toast, type.label), Toast.LENGTH_SHORT).show()
    }

    private fun persist() {
        config = ShiftEngine.normalize(LocalDate.now(), config)
        prefs.save(config)
        AlarmScheduler.rescheduleAll(this, config)
        render()
    }

    private fun render() {
        rendering = true
        val today = LocalDate.now()
        val thisWeek = ShiftEngine.weekType(today, config)
        binding.weekLabel.text = getString(R.string.week_label, thisWeek.label)
        binding.weekRangeText.text = ShiftEngine.weekRangeLabel(today)

        val nextWeek = today.plusWeeks(1)
        binding.nextWeekText.text = getString(
            R.string.home_next_week,
            ShiftEngine.weekType(nextWeek, config).label,
            ShiftEngine.weekRangeLabel(nextWeek)
        )

        val next = ShiftEngine.nextAlarm(LocalDateTime.now(), config)
        binding.nextAlarmText.text = if (next == null) {
            getString(R.string.next_alarm_none)
        } else {
            getString(
                R.string.next_alarm,
                shortDate(next.date),
                next.weekdayLabel,
                ShiftEngine.formatTime(next.time),
                next.kind.label
            )
        }

        val target = config.saturdayTarget ?: ShiftEngine.upcomingSaturday(today)
        binding.switchSatOvertime.isChecked = config.saturdayOvertime
        binding.satTargetText.text = if (config.saturdayOvertime) {
            getString(
                R.string.sat_on,
                shortDate(target),
                weekdayLabel(target),
                ShiftEngine.formatTime(config.dayTime)
            )
        } else {
            getString(R.string.sat_off)
        }
        binding.switchSatAutoReset.isChecked = config.saturdayAutoReset
        binding.switchSatAutoReset.isEnabled = config.saturdayOvertime

        binding.switchDayAlarm.isChecked = config.dayAlarmEnabled
        binding.btnDayTime.text = ShiftEngine.formatTime(config.dayTime)
        binding.dayHintText.text = if (config.dayAlarmEnabled) {
            getString(R.string.day_hint_on, ShiftEngine.formatTime(config.dayTime))
        } else {
            getString(R.string.day_hint_off)
        }

        binding.switchNightAlarm.isChecked = config.nightAlarmEnabled
        binding.btnNightTime.text = ShiftEngine.formatTime(config.nightTime)
        binding.nightHintText.text = if (config.nightAlarmEnabled) {
            getString(R.string.night_hint_on, ShiftEngine.formatTime(config.nightTime))
        } else {
            getString(R.string.night_hint_off)
        }

        binding.switchAutoRestore.isChecked = config.autoRestoreEnabled
        binding.btnRestoreTime.text = ShiftEngine.formatTime(config.restoreTime)
        binding.btnRestoreTime.isEnabled = config.autoRestoreEnabled
        binding.restoreHintText.text = if (config.autoRestoreEnabled) {
            getString(R.string.restore_hint, ShiftEngine.formatTime(config.restoreTime))
        } else {
            getString(R.string.restore_off_hint)
        }
        rendering = false
    }

    private fun refreshPermissions() {
        val exactOk = AlarmScheduler.canScheduleExactAlarms(this)
        binding.rowExact.visibility = if (exactOk) View.GONE else View.VISIBLE

        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        binding.rowNotif.visibility = if (notifOk) View.GONE else View.VISIBLE

        val power = getSystemService(PowerManager::class.java)
        val batteryOk = power?.isIgnoringBatteryOptimizations(packageName) == true
        binding.rowBattery.visibility = if (batteryOk) View.GONE else View.VISIBLE

        val allOk = exactOk && notifOk && batteryOk
        binding.permissionOkText.visibility = if (allOk) View.VISIBLE else View.GONE
    }

    private fun pickTime(title: String, initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(initial.hour)
            .setMinute(initial.minute)
            .setTitleText(title)
            .build()
        picker.addOnPositiveButtonClickListener {
            onPicked(LocalTime.of(picker.hour, picker.minute))
        }
        picker.show(supportFragmentManager, "time_picker")
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            } catch (e: Exception) {
                // 아래 앱 상세 설정으로 대체
            }
        }
        openAppDetails()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power?.isIgnoringBatteryOptimizations(packageName) == true) {
            openBatterySettings()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            openBatterySettings()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS
                ).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    private fun shortDate(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}"

    private fun weekdayLabel(date: LocalDate): String = when (date.dayOfWeek) {
        java.time.DayOfWeek.MONDAY -> "월"
        java.time.DayOfWeek.TUESDAY -> "화"
        java.time.DayOfWeek.WEDNESDAY -> "수"
        java.time.DayOfWeek.THURSDAY -> "목"
        java.time.DayOfWeek.FRIDAY -> "금"
        java.time.DayOfWeek.SATURDAY -> "토"
        java.time.DayOfWeek.SUNDAY -> "일"
    }
}

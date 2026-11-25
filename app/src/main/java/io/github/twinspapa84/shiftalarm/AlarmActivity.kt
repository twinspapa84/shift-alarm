package io.github.twinspapa84.shiftalarm

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.github.twinspapa84.shiftalarm.alarm.AlarmService
import io.github.twinspapa84.shiftalarm.core.AlarmKind
import io.github.twinspapa84.shiftalarm.core.ShiftEngine
import io.github.twinspapa84.shiftalarm.databinding.ActivityAlarmBinding
import java.time.LocalDate
import java.time.LocalTime

/**
 * 알람이 울릴 때 잠금화면 위로 뜨는 화면. 해제 / 5분 후 다시 울림.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding
    private val handler = Handler(Looper.getMainLooper())
    private var kind: AlarmKind = AlarmKind.DAY
    private var snooze = false
    private var shownAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            binding.timeText.text = ShiftEngine.formatTime(LocalTime.now())
            val serviceEndedEarly = SystemClock.elapsedRealtime() - shownAt > 2_500 &&
                !AlarmService.isRinging
            if (serviceEndedEarly) {
                finish()
                return
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val kindName = intent.getStringExtra(AlarmService.EXTRA_KIND)
        if (kindName == null) {
            // 런처 등에서 실수로 열린 경우: 알람 화면을 띄우지 않는다.
            finish()
            return
        }
        kind = runCatching { AlarmKind.valueOf(kindName) }.getOrDefault(AlarmKind.DAY)
        snooze = intent.getBooleanExtra(AlarmService.EXTRA_SNOOZE, false)

        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showOverLockScreen()

        binding.kindText.text = if (snooze) {
            getString(R.string.notification_snooze_title, kind.label)
        } else {
            kind.label
        }
        val today = LocalDate.now()
        binding.dateText.text = getString(
            R.string.alarm_date,
            today.monthValue,
            today.dayOfMonth,
            weekdayLabel(today)
        )
        binding.timeText.text = ShiftEngine.formatTime(LocalTime.now())

        binding.dismissButton.setOnClickListener {
            AlarmService.startAction(this, AlarmService.ACTION_DISMISS, kind)
            finish()
        }
        binding.snoozeButton.setOnClickListener {
            AlarmService.startAction(this, AlarmService.ACTION_SNOOZE, kind)
            Toast.makeText(this, R.string.alarm_snoozed_toast, Toast.LENGTH_SHORT).show()
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 알람은 해제 버튼으로만 끈다.
            }
        })
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguard = getSystemService(KeyguardManager::class.java)
            keyguard?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onStart() {
        super.onStart()
        shownAt = SystemClock.elapsedRealtime()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

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

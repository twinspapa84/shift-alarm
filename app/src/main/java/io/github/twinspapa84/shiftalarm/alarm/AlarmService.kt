package io.github.twinspapa84.shiftalarm.alarm

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.twinspapa84.shiftalarm.AlarmActivity
import io.github.twinspapa84.shiftalarm.core.AlarmKind
import io.github.twinspapa84.shiftalarm.data.ShiftPrefs

/**
 * 알람이 울리는 동안 도는 포그라운드 서비스.
 * 벨소리 + 진동을 직접 재생하고, 잠금화면 위로 AlarmActivity 를 띄운다.
 */
class AlarmService : Service() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable {
        Log.i(TAG, "자동 종료(10분 경과)")
        stopRingingAndFinish()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.ensureChannel(this)
        val kind = parseKind(intent?.getStringExtra(EXTRA_KIND))
        val snooze = intent?.getBooleanExtra(EXTRA_SNOOZE, false) ?: false

        // startForegroundService 로 들어올 수 있으므로 액션과 무관하게 먼저 포그라운드로 전환한다.
        enterForeground(kind, snooze)

        when (intent?.action) {
            ACTION_DISMISS -> stopRingingAndFinish()
            ACTION_SNOOZE -> {
                AlarmScheduler.scheduleSnooze(this, kind)
                stopRingingAndFinish()
            }
            else -> beginAlarm(kind, snooze)
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(kind: AlarmKind, snooze: Boolean) {
        val notification = NotificationHelper.buildAlarmNotification(this, kind, snooze)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "포그라운드 전환 실패", e)
        }
    }

    private fun beginAlarm(kind: AlarmKind, snooze: Boolean) {
        isRinging = true
        acquireWakeLock()
        startRingtone()
        startVibration()

        val activityIntent = Intent(this, AlarmActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(EXTRA_SNOOZE, snooze)
        try {
            startActivity(activityIntent)
        } catch (e: Exception) {
            // 백그라운드 액티비티 시작 제한에 걸리면 알림의 전체화면 인텐트가 대신 띄운다.
            Log.w(TAG, "액티비티 직접 실행 실패(알림으로 대체)", e)
        }

        handler.removeCallbacks(autoStopRunnable)
        handler.postDelayed(autoStopRunnable, AUTO_STOP_MS)
    }

    private fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val tone = RingtoneManager.getRingtone(this, uri) ?: return
            tone.audioAttributes = NotificationHelper.alarmAudioAttributes()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tone.isLooping = true
            }
            tone.play()
            ringtone = tone
        } catch (e: Exception) {
            Log.e(TAG, "벨소리 재생 실패", e)
        }
    }

    private fun startVibration() {
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            if (vib == null || !vib.hasVibrator()) return
            val pattern = longArrayOf(0, 800, 600)
            vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
            vibrator = vib
        } catch (e: Exception) {
            Log.e(TAG, "진동 실패", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val power = getSystemService(PowerManager::class.java) ?: return
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "wakelock 획득 실패", e)
        }
    }

    private fun stopRingingAndFinish() {
        handler.removeCallbacks(autoStopRunnable)
        isRinging = false
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "벨소리 정지 실패", e)
        }
        ringtone = null
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "진동 정지 실패", e)
        }
        vibrator = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NotificationHelper.NOTIFICATION_ID)

        // 해제/다시 울림 후 다음 알람과 22:00 복구를 다시 건다.
        val config = ShiftPrefs(this).load()
        AlarmScheduler.rescheduleAll(this, config)
        stopSelf()
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "wakelock 해제 실패", e)
        }
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStopRunnable)
        isRinging = false
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun parseKind(name: String?): AlarmKind =
        name?.let { runCatching { AlarmKind.valueOf(it) }.getOrNull() } ?: AlarmKind.DAY

    companion object {
        private const val TAG = "AlarmService"
        private const val WAKE_LOCK_TAG = "ShiftAlarm::ringing"
        private const val WAKE_LOCK_TIMEOUT_MS = 11 * 60_000L
        private const val AUTO_STOP_MS = 10 * 60_000L

        const val ACTION_START = "io.github.twinspapa84.shiftalarm.action.ALARM_START"
        const val ACTION_DISMISS = "io.github.twinspapa84.shiftalarm.action.DISMISS"
        const val ACTION_SNOOZE = "io.github.twinspapa84.shiftalarm.action.SNOOZE"
        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_SNOOZE = "extra_snooze"

        /** 알람 화면이 스스로 닫히도록 서비스 상태를 공유한다. */
        @Volatile
        var isRinging: Boolean = false
            private set

        fun startAction(context: android.content.Context, action: String, kind: AlarmKind) {
            val intent = Intent(context, AlarmService::class.java)
                .setAction(action)
                .putExtra(EXTRA_KIND, kind.name)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "서비스 액션 전달 실패", e)
                context.startService(intent)
            }
        }
    }
}

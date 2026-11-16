package app.sutra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.sutra.R
import app.sutra.data.Rule
import app.sutra.data.SutraStore
import app.sutra.ui.MainActivity
import java.util.Locale
import kotlin.math.sqrt

/**
 * The one long-running piece of Sutra. It is a foreground service, so Android keeps it alive in the
 * background (also after the app is swiped away from Recents) until the user presses Stop.
 *
 * Nothing in here needs a runtime permission:
 *  - all event sources are broadcasts registered at runtime or the accelerometer;
 *  - all actions are text-to-speech, vibration (normal permission), toasts and the torch
 *    (CameraManager.setTorchMode does not need the CAMERA permission).
 */
class SutraService : Service(), SensorEventListener, TextToSpeech.OnInitListener {

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = mutableListOf<String>()

    private var receiverRegistered = false
    private var sensorManager: SensorManager? = null
    private var screenOn = true
    private var lastShakeAt = 0L
    private var torchOn = false
    private var torchCameraId: String? = null

    // Edge detection so battery rules fire once per crossing, not on every battery broadcast.
    private var lastBatteryPct = -1
    private var lastPlugged: Boolean? = null
    private var lowFired = false
    private var fullFired = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SutraStore.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SutraStore.serviceWanted = false
            SutraStore.log(getString(R.string.log_stopped))
            stopEverything()
            return START_NOT_STICKY
        }

        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!receiverRegistered) {
            SutraStore.serviceWanted = true
            SutraStore.setRunning(true)
            SutraStore.log(getString(R.string.log_started))
            tts = TextToSpeech(this, this)
            registerEventReceiver()
            startShakeDetection()
            scheduleHeartbeat()
        } else if (intent?.action == ACTION_REFRESH) {
            if (on(Rule.SHAKE_TORCH)) startShakeDetection() else stopShakeDetection()
        }
        // START_STICKY: if the system has to kill the process, it restarts the service later.
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        // Android 14+ (API 34) requires the type passed here to match the manifest.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        true
    } catch (e: Exception) {
        // Android 12+ (API 31) can refuse an FGS start from the background
        // (ForegroundServiceStartNotAllowedException). Give up quietly; the user can start it again.
        SutraStore.log(getString(R.string.log_start_refused, e.javaClass.simpleName))
        false
    }

    // ---------------------------------------------------------------- events

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> if (on(Rule.CHARGER_CONNECTED)) {
                    SutraStore.log(getString(R.string.log_charger_on))
                    speak(getString(R.string.say_charger_on))
                }
                Intent.ACTION_POWER_DISCONNECTED -> if (on(Rule.CHARGER_DISCONNECTED)) {
                    SutraStore.log(getString(R.string.log_charger_off))
                    speak(getString(R.string.say_charger_off))
                }
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                AudioManager.ACTION_HEADSET_PLUG -> {
                    // The sticky broadcast is replayed on registration; ignore that first copy.
                    if (isInitialStickyBroadcast) return
                    if (!on(Rule.HEADPHONES)) return
                    val plugged = intent.getIntExtra("state", 0) == 1
                    val msg = getString(if (plugged) R.string.log_headphones_in else R.string.log_headphones_out)
                    SutraStore.log(msg)
                    toast(msg)
                }
                Intent.ACTION_SCREEN_ON -> { screenOn = true; startShakeDetection() }
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; stopShakeDetection() }
                Intent.ACTION_USER_PRESENT -> if (on(Rule.UNLOCK_COUNTER)) {
                    SutraStore.log(getString(R.string.log_unlock, SutraStore.incrementUnlocks()))
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> if (on(Rule.AIRPLANE)) {
                    val enabled = intent.getBooleanExtra("state", false)
                    val msg = getString(if (enabled) R.string.log_airplane_on else R.string.log_airplane_off)
                    SutraStore.log(msg)
                    speak(msg)
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> if (on(Rule.POWER_SAVE)) {
                    val pm = getSystemService(PowerManager::class.java)
                    SutraStore.log(getString(if (pm.isPowerSaveMode) R.string.log_saver_on else R.string.log_saver_off))
                }
            }
        }
    }

    private fun registerEventReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        // Android 14+ (API 34) requires an explicit exported flag; these are system broadcasts.
        ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        screenOn = getSystemService(PowerManager::class.java).isInteractive
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val first = lastBatteryPct < 0

        if (pct > LOW_THRESHOLD) lowFired = false
        if (pct < 100 || !plugged) fullFired = false

        if (!first && on(Rule.BATTERY_LOW) && !plugged && pct <= LOW_THRESHOLD && !lowFired) {
            lowFired = true
            SutraStore.log(getString(R.string.log_battery_low, pct))
            vibrate()
            speak(getString(R.string.say_battery_low, pct))
        }
        if (!first && on(Rule.BATTERY_FULL) && plugged && pct >= 100 && !fullFired) {
            fullFired = true
            SutraStore.log(getString(R.string.log_battery_full))
            speak(getString(R.string.say_battery_full))
        }
        if (first) {
            // Don't fire for a state that was already true when the service started.
            lowFired = pct <= LOW_THRESHOLD
            fullFired = plugged && pct >= 100
        }
        if (pct != lastBatteryPct || plugged != lastPlugged) {
            lastBatteryPct = pct
            lastPlugged = plugged
            updateNotification()
        }
    }

    // ---------------------------------------------------------------- shake → torch

    private fun startShakeDetection() {
        if (!on(Rule.SHAKE_TORCH) || !screenOn || sensorManager != null) return
        val sm = getSystemService(SensorManager::class.java) ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager = sm
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
    }

    /** Sensors are paused while the screen is off to keep idle battery cost near zero. */
    private fun stopShakeDetection() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()
        if (gForce > SHAKE_G && now - lastShakeAt > SHAKE_COOLDOWN_MS) {
            lastShakeAt = now
            toggleTorch()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun toggleTorch() {
        val cm = getSystemService(CameraManager::class.java) ?: return
        try {
            val id = torchCameraId ?: cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }?.also { torchCameraId = it } ?: run {
                SutraStore.log(getString(R.string.log_no_torch)); return
            }
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
            SutraStore.log(getString(if (torchOn) R.string.log_torch_on else R.string.log_torch_off))
            vibrate(40)
        } catch (e: Exception) {
            torchOn = false
            SutraStore.log(getString(R.string.log_torch_error, e.javaClass.simpleName))
        }
    }

    // ---------------------------------------------------------------- heartbeat

    private val heartbeat = object : Runnable {
        override fun run() {
            if (on(Rule.HEARTBEAT)) SutraStore.log(getString(R.string.log_heartbeat))
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun scheduleHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    // ---------------------------------------------------------------- actions

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.getDefault()
            pendingSpeech.forEach { speak(it) }
        }
        pendingSpeech.clear()
    }

    private fun speak(text: String) {
        if (!ttsReady) { pendingSpeech += text; return }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "sutra-${System.nanoTime()}")
    }

    private fun toast(text: String) = handler.post {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    private fun vibrate(ms: Long = 400) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun on(rule: Rule) = SutraStore.isEnabled(rule)

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
        channel.description = getString(R.string.channel_desc)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SutraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (lastBatteryPct >= 0) getString(R.string.notif_text_battery, lastBatteryPct)
        else getString(R.string.notif_text)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun updateNotification() {
        // Without POST_NOTIFICATIONS (Android 13+) this is silently dropped; the service is unaffected.
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) { }
    }

    // ---------------------------------------------------------------- shutdown

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try { unregisterReceiver(eventReceiver) } catch (_: IllegalArgumentException) { }
            receiverRegistered = false
        }
        stopShakeDetection()
        if (torchOn) torchCameraId?.let {
            try { getSystemService(CameraManager::class.java).setTorchMode(it, false) } catch (_: Exception) { }
        }
        tts?.shutdown()
        tts = null
        SutraStore.setRunning(false)
        SutraService.updateTile(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.sutra.action.STOP"
        private const val CHANNEL_ID = "sutra_monitor"
        private const val NOTIFICATION_ID = 42
        private const val LOW_THRESHOLD = 20
        private const val SHAKE_G = 2.7f
        private const val SHAKE_COOLDOWN_MS = 1200L
        private const val HEARTBEAT_MS = 15 * 60 * 1000L

        fun start(context: Context) {
            SutraStore.init(context)
            SutraStore.serviceWanted = true
            ContextCompat.startForegroundService(context, Intent(context, SutraService::class.java))
            updateTile(context)
        }

        fun stop(context: Context) {
            SutraStore.init(context)
            SutraStore.serviceWanted = false
            context.startService(Intent(context, SutraService::class.java).setAction(ACTION_STOP))
            updateTile(context)
        }

        /** Re-applies rule changes (e.g. shake on/off) to a running service. */
        fun refresh(context: Context) {
            if (SutraStore.running.value) {
                ContextCompat.startForegroundService(
                    context, Intent(context, SutraService::class.java).setAction(ACTION_REFRESH),
                )
            }
        }

        const val ACTION_REFRESH = "app.sutra.action.REFRESH"

        fun updateTile(context: Context) {
            android.service.quicksettings.TileService.requestListeningState(
                context, android.content.ComponentName(context, SutraTileService::class.java),
            )
        }
    }
}

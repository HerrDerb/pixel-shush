package com.herrderb.pssst

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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Watches the sensors and switches the Pssst DND rule.
 *
 * Screen on: the accelerometer runs (the CPU is awake anyway). Like Google, no proximity.
 * Screen off: most phones (Pixel 5 included) have no wake-up accelerometer, so the
 * accelerometer only keeps running for a short grace period. After that the wake-up
 * proximity sensor is the trigger: NEAR opens a short accelerometer check to confirm
 * face down, FAR while flipped opens one to confirm the phone was picked up.
 */
class PssstService : Service(), SensorEventListener {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sensors: SensorManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var zen: ZenRule
    private lateinit var haptics: Haptics
    private lateinit var prefs: Prefs

    private var accel: Sensor? = null
    private var proximity: Sensor? = null
    private val detector = FlipDetector()

    private var accelOn = false
    private var proximityOn = false
    private var screenOn = true
    private var lastLogNs = 0L
    private var graceEndsAtMs = 0L

    /** Start of a NEAR check that may end early (elapsed-realtime ns, like sensor timestamps), or -1. */
    private var nearCheckStartNs = -1L

    private val endAccelWindow = Runnable { if (!screenOn) stopAccelWindow() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            applyScreenState()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        zen = ZenRule(this)
        haptics = Haptics(this)
        sensors = getSystemService(SensorManager::class.java)
        accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pssst:check").apply {
            setReferenceCounted(false)
        }

        createChannels()
        startInForeground()

        thread = HandlerThread("pssst-sensors").apply { start() }
        handler = Handler(thread.looper)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this, screenReceiver, filter, null, handler, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenOn = power.isInteractive
        Log.i(TAG, "Started. Wake-up accelerometer: ${sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) != null}, proximity: ${proximity?.name}")
        handler.post { applyScreenState() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        handler.post {
            handler.removeCallbacksAndMessages(null)
            sensors.unregisterListener(this)
            if (detector.isFlipped) zen.setActive(false)
            if (wakeLock.isHeld) wakeLock.release()
        }
        thread.quitSafely()
        super.onDestroy()
    }

    // --- Sensor modes (all on the sensor thread) ---

    private fun applyScreenState() {
        nearCheckStartNs = -1L
        if (screenOn) {
            handler.removeCallbacks(endAccelWindow)
            stopProximity()
            detector.proximityBacked = false
            startAccel()
            if (wakeLock.isHeld) wakeLock.release()
        } else {
            detector.proximityBacked = true
            startProximity()
            graceEndsAtMs = SystemClock.uptimeMillis() + SCREEN_OFF_GRACE_MS
            openAccelWindow(SCREEN_OFF_GRACE_MS)
        }
    }

    private fun openAccelWindow(millis: Long) {
        wakeLock.acquire(millis + 500)
        startAccel()
        handler.removeCallbacks(endAccelWindow)
        handler.postDelayed(endAccelWindow, millis)
    }

    private fun stopAccelWindow() {
        handler.removeCallbacks(endAccelWindow)
        nearCheckStartNs = -1L
        stopAccel()
        if (detector.isFlipped) detector.resetFilter() else detector.reset()
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun startAccel() {
        val sensor = accel ?: return
        if (accelOn) return
        detector.resetFilter()
        accelOn = sensors.registerListener(this, sensor, SAMPLING_US, handler)
    }

    private fun stopAccel() {
        if (!accelOn) return
        accel?.let { sensors.unregisterListener(this, it) }
        accelOn = false
    }

    private fun startProximity() {
        val sensor = proximity ?: return
        if (proximityOn) return
        proximityOn = sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
    }

    private fun stopProximity() {
        if (!proximityOn) return
        proximity?.let { sensors.unregisterListener(this, it) }
        proximityOn = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccel(event)
            Sensor.TYPE_PROXIMITY -> onProximity(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onAccel(event: SensorEvent) {
        val v = event.values
        val result = detector.onAccel(v[0], v[1], v[2], event.timestamp)
        if (Log.isLoggable(TAG, Log.DEBUG) && event.timestamp - lastLogNs > 500_000_000L) {
            lastLogNs = event.timestamp
            Log.d(TAG, "angle=%.3f rad variation=%.3f flipped=%b".format(detector.angle, detector.variation, detector.isFlipped))
        }
        when (result) {
            FlipDetector.Event.FLIPPED -> onFlipped()
            FlipDetector.Event.UNFLIPPED -> onUnflipped()
            null -> Unit
        }

        // Covered but clearly not lying flat (pocket, hand): stop the check early to save battery.
        if (nearCheckStartNs >= 0 && !screenOn && !detector.isFlipped &&
            event.timestamp - nearCheckStartNs > EARLY_END_AFTER_NS &&
            detector.angle > EARLY_END_ANGLE_RAD
        ) {
            Log.d(TAG, "Not lying flat (angle=%.2f rad), ending check early".format(detector.angle))
            stopAccelWindow()
        }
    }

    private fun onProximity(event: SensorEvent) {
        val sensor = proximity ?: return
        val near = event.values[0] < sensor.maximumRange
        Log.d(TAG, "proximity near=$near (${event.values[0]})")
        if (screenOn) return
        if (near && !detector.isFlipped) {
            openAccelWindow(NEAR_CHECK_MS)
            // Never cut the grace period short: the phone may still be on its way down.
            if (SystemClock.uptimeMillis() >= graceEndsAtMs) nearCheckStartNs = SystemClock.elapsedRealtimeNanos()
        }
        if (!near && detector.isFlipped) openAccelWindow(FAR_CHECK_MS)
    }

    private fun onFlipped() {
        Log.i(TAG, "Face down -> DND on")
        haptics.on()
        zen.setActive(true)
        if (!prefs.firstFlipShown) {
            prefs.firstFlipShown = true
            showFirstFlipNotice()
        }
    }

    private fun onUnflipped() {
        Log.i(TAG, "Picked up -> DND off")
        zen.setActive(false)
        handler.postDelayed({ haptics.off() }, OFF_VIBRATION_DELAY_MS)
    }

    // --- Notifications ---

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_MIN).apply {
                description = getString(R.string.channel_status_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INFO, getString(R.string.channel_info), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_pssst)
            .setContentTitle(getString(R.string.status_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_STATUS, notification)
        }
    }

    private fun showFirstFlipNotice() {
        val settings = PendingIntent.getActivity(
            this, 1,
            Intent(ZEN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_pssst)
            .setContentTitle(getString(R.string.first_flip_title))
            .setContentText(getString(R.string.first_flip_body))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.first_flip_body)))
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.first_flip_action), settings).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_FIRST_FLIP, notification)
    }

    companion object {
        private const val TAG = "Pssst"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_INFO = "info"
        private const val NOTIFICATION_STATUS = 1
        private const val NOTIFICATION_FIRST_FLIP = 2

        /** Hidden but stable action that opens the Do Not Disturb page. */
        private const val ZEN_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"

        /** shush_sensor_batching_delay_second = 0.1 s. */
        private const val SAMPLING_US = 100_000

        /** Keep the accelerometer alive after screen off to catch "power button, then flip". */
        private const val SCREEN_OFF_GRACE_MS = 5_000L
        private const val NEAR_CHECK_MS = 4_000L
        private const val FAR_CHECK_MS = 2_000L

        /** A NEAR check ends early once the phone is still tilted this much after this long. */
        private const val EARLY_END_AFTER_NS = 500_000_000L
        private const val EARLY_END_ANGLE_RAD = 0.5f

        /** Digital Wellbeing delays the "off" tick by 100 ms. */
        private const val OFF_VIBRATION_DELAY_MS = 100L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PssstService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PssstService::class.java))
        }

        fun batteryOptimizationIntent(context: Context): Intent? {
            val power = context.getSystemService(PowerManager::class.java)
            if (power.isIgnoringBatteryOptimizations(context.packageName)) return null
            @Suppress("BatteryLife")
            return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        }
    }
}

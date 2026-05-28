/**
 * ========================================
 * LocationForegroundService.kt - 位置情報フォアグラウンドサービス（設定モード対応版）
 * ========================================
 *
 * バックグラウンドで位置情報を取得し続けるためのサービスです。
 * 2ポイント/3ポイントモードに対応した音量制御を行います。
 */

package com.example.speedvolumecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import android.media.AudioManager
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import kotlin.math.pow
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LocationForegroundService - 位置情報取得フォアグラウンドサービス
 */
class LocationForegroundService : Service() {

    // ========================================
    // プロパティ定義
    // ========================================

    private lateinit var locationManager: LocationManager
    private var locationListener: LocationListener? = null
    private val logDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var currentGpsState = GpsState.INACTIVE
    private var lastLocationTime = 0L
    private lateinit var gpsTimeoutHandler: Handler
    private lateinit var gpsTimeoutRunnable: Runnable
    private var currentSpeed = 0f

    // ========================================
    // 音量制御関連プロパティ
    // ========================================

    private lateinit var audioManager: AudioManager
    private var isVolumeAutoAdjustEnabled = false

    // 設定モード対応
    private var settingMode = SettingMode.TWO_POINT
    private var speeds = floatArrayOf(30f, 80f)
    private var volumes = floatArrayOf(40f, 100f)
    private var curves = doubleArrayOf(1.0)

    // GPS信号を一度でも受信したかどうかのフラグ
    private var hasReceivedGpsSignal = false

    // GPS更新間隔
    private var gpsInterval = 500L

    // 音量スムージング関連
    private var volumeChangeRate = VolumeChangeRateOptions.DEFAULT
    private var volumeAnimator: ValueAnimator? = null
    private var currentAppliedVolume: Float = 0f
    private var lastAppliedVolumeStep: Int = -1
    private var cachedMaxVolume: Int = 0

    // ========================================
    // サービスライフサイクルメソッド
    // ========================================

    override fun onCreate() {
        super.onCreate()

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        cachedMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        currentAppliedVolume = if (cachedMaxVolume > 0) currentStep * 100f / cachedMaxVolume else 0f
        lastAppliedVolumeStep = currentStep

        gpsTimeoutHandler = Handler(Looper.getMainLooper())
        gpsTimeoutRunnable = Runnable {
            if (currentGpsState == GpsState.ACTIVE) {
                currentGpsState = GpsState.INACTIVE
                sendLogBroadcast("[GPS] 信号ロスト (モード: BACKGROUND)")
                sendLocationBroadcast(currentSpeed, GpsState.INACTIVE)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 音量自動調整の状態をIntentから取得
        intent?.let {
            isVolumeAutoAdjustEnabled = it.getBooleanExtra(
                LocationServiceConstants.EXTRA_VOLUME_AUTO_ADJUST_ENABLED,
                false
            )

            // 設定モードを取得
            val modeOrdinal = it.getIntExtra(LocationServiceConstants.EXTRA_SETTING_MODE, 0)
            settingMode = SettingMode.entries.getOrElse(modeOrdinal) { SettingMode.TWO_POINT }

            // 速度・音量・カーブ配列を取得
            it.getFloatArrayExtra(LocationServiceConstants.EXTRA_SPEEDS)?.let { arr ->
                speeds = arr
            }
            it.getFloatArrayExtra(LocationServiceConstants.EXTRA_VOLUMES)?.let { arr ->
                volumes = arr
            }
            it.getDoubleArrayExtra(LocationServiceConstants.EXTRA_CURVES)?.let { arr ->
                curves = arr
            }

            // GPS受信フラグを取得
            hasReceivedGpsSignal = it.getBooleanExtra(
                LocationServiceConstants.EXTRA_HAS_RECEIVED_GPS,
                false
            )

            // GPS更新間隔を取得
            gpsInterval = it.getLongExtra(LocationServiceConstants.EXTRA_GPS_INTERVAL, 500L)

            // 音量変化速度を取得
            volumeChangeRate = it.getIntExtra(LocationServiceConstants.EXTRA_VOLUME_CHANGE_RATE, VolumeChangeRateOptions.DEFAULT)
        }

        createNotificationChannel()
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                LocationServiceConstants.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(LocationServiceConstants.NOTIFICATION_ID, notification)
        }

        startLocationUpdates()

        val modeLabel = if (settingMode == SettingMode.TWO_POINT) "2ポイント" else "3ポイント"
        sendLogBroadcast("[SERVICE] フォアグラウンドサービス開始 (音量自動調整: ${if (isVolumeAutoAdjustEnabled) "ON" else "OFF"}, モード: $modeLabel)")

        // サービス開始時、GPS未受信かつ自動調整ONなら0km/hで音量設定
        if (isVolumeAutoAdjustEnabled && !hasReceivedGpsSignal) {
            sendLogBroadcast("[SERVICE] GPS未受信のため 0 km/h として音量計算")
            calculateAndSetVolume(0f)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        volumeAnimator?.cancel()
        gpsTimeoutHandler.removeCallbacks(gpsTimeoutRunnable)
        sendLogBroadcast("[SERVICE] フォアグラウンドサービス停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========================================
    // 通知関連メソッド
    // ========================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LocationServiceConstants.NOTIFICATION_CHANNEL_ID,
                "位置情報サービス",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "バックグラウンドでの位置情報取得に使用します"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, LocationServiceConstants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("車速モニター実行中")
            .setContentText("バックグラウンドで速度を監視しています")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    // ========================================
    // 位置情報関連メソッド
    // ========================================

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            sendLogBroadcast("[SERVICE] 位置情報パーミッションがありません")
            return
        }

        locationListener?.let {
            locationManager.removeUpdates(it)
        }

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                updateLocation(location)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            gpsInterval,
            0f,
            locationListener!!
        )
    }

    @Suppress("MissingPermission")
    private fun stopLocationUpdates() {
        locationListener?.let {
            locationManager.removeUpdates(it)
        }
    }

    private fun updateLocation(location: Location) {
        lastLocationTime = System.currentTimeMillis()
        currentSpeed = location.speed * 3.6f

        // GPS信号を初めて受信した場合、フラグを立てる
        if (!hasReceivedGpsSignal) {
            hasReceivedGpsSignal = true
            sendLogBroadcast("[GPS] 初回信号受信 (SERVICE)")
        }

        if (currentGpsState == GpsState.INACTIVE) {
            currentGpsState = GpsState.ACTIVE
            sendLogBroadcast("[GPS] 信号取得開始 (モード: BACKGROUND, Speed: ${String.format(Locale.US, "%.1f", currentSpeed)} km/h)")
        }

        if (isVolumeAutoAdjustEnabled) {
            calculateAndSetVolume(currentSpeed)
        }

        sendLocationBroadcast(currentSpeed, currentGpsState)
        startGpsTimeoutCheck()
    }

    private fun startGpsTimeoutCheck() {
        gpsTimeoutHandler.removeCallbacks(gpsTimeoutRunnable)
        gpsTimeoutHandler.postDelayed(
            gpsTimeoutRunnable,
            LocationServiceConstants.GPS_TIMEOUT_MS
        )
    }

    // ========================================
    // Broadcast送信メソッド
    // ========================================

    private fun sendLocationBroadcast(speed: Float, gpsState: GpsState) {
        val intent = Intent(LocationServiceConstants.ACTION_LOCATION_UPDATE).apply {
            setPackage(packageName)
            putExtra(LocationServiceConstants.EXTRA_SPEED, speed)
            putExtra(LocationServiceConstants.EXTRA_GPS_STATE, gpsState.name)
        }
        sendBroadcast(intent)
    }

    private fun sendLogBroadcast(message: String) {
        val timestamp = logDateFormat.format(Date())
        val fullMessage = "$timestamp - $message"

        val intent = Intent(LocationServiceConstants.ACTION_LOG_MESSAGE).apply {
            setPackage(packageName)
            putExtra(LocationServiceConstants.EXTRA_LOG_MESSAGE, fullMessage)
        }
        sendBroadcast(intent)
    }

    // ========================================
    // 音量制御関連メソッド（設定モード対応）
    // ========================================

    /**
     * 速度から音量（%）を計算する（設定モード対応版）
     */
    private fun calculateVolumePercent(speedKmh: Float): Int {
        return when (settingMode) {
            SettingMode.TWO_POINT -> calculateTwoPointVolume(speedKmh)
            SettingMode.THREE_POINT -> calculateThreePointVolume(speedKmh)
        }
    }

    /**
     * 2ポイントモードの音量計算
     */
    private fun calculateTwoPointVolume(speedKmh: Float): Int {
        val minSpeed = speeds.getOrElse(0) { 30f }
        val maxSpeed = speeds.getOrElse(1) { 80f }
        val minVolume = volumes.getOrElse(0) { 40f }
        val maxVolume = volumes.getOrElse(1) { 100f }
        val curvePower = curves.getOrElse(0) { 1.0 }

        return when {
            speedKmh <= minSpeed -> minVolume.roundToInt()
            speedKmh >= maxSpeed -> maxVolume.roundToInt()
            else -> {
                val normalized = (speedKmh - minSpeed) / (maxSpeed - minSpeed)
                val curved = normalized.toDouble().pow(curvePower)
                (minVolume + (maxVolume - minVolume) * curved).roundToInt()
            }
        }
    }

    /**
     * 3ポイントモードの音量計算
     */
    private fun calculateThreePointVolume(speedKmh: Float): Int {
        val speed1 = speeds.getOrElse(0) { 30f }
        val speed2 = speeds.getOrElse(1) { 55f }
        val speed3 = speeds.getOrElse(2) { 80f }
        val volume1 = volumes.getOrElse(0) { 40f }
        val volume2 = volumes.getOrElse(1) { 70f }
        val volume3 = volumes.getOrElse(2) { 100f }
        val curve1 = curves.getOrElse(0) { 1.0 }
        val curve2 = curves.getOrElse(1) { 1.0 }

        return when {
            speedKmh <= speed1 -> volume1.roundToInt()
            speedKmh >= speed3 -> volume3.roundToInt()
            speedKmh <= speed2 -> {
                // セグメント1: V1 → V2
                val normalized = (speedKmh - speed1) / (speed2 - speed1)
                val curved = normalized.toDouble().pow(curve1)
                (volume1 + (volume2 - volume1) * curved).roundToInt()
            }
            else -> {
                // セグメント2: V2 → V3
                val normalized = (speedKmh - speed2) / (speed3 - speed2)
                val curved = normalized.toDouble().pow(curve2)
                (volume2 + (volume3 - volume2) * curved).roundToInt()
            }
        }
    }

    private fun applyVolumeImmediate(volumePercent: Float) {
        val step = (cachedMaxVolume * volumePercent / 100f).roundToInt()
            .coerceIn(0, cachedMaxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        lastAppliedVolumeStep = step
        currentAppliedVolume = volumePercent
    }

    private fun calculateAndSetVolume(speedKmh: Float) {
        val targetPercent = calculateVolumePercent(speedKmh).toFloat()

        if (volumeChangeRate >= 100) {
            volumeAnimator?.cancel()
            applyVolumeImmediate(targetPercent)
            return
        }

        val distance = Math.abs(targetPercent - currentAppliedVolume)
        if (distance < 0.5f) {
            volumeAnimator?.cancel()
            applyVolumeImmediate(targetPercent)
            return
        }

        val durationMs = (distance / volumeChangeRate * 1000f).toLong().coerceIn(50L, 5000L)

        volumeAnimator?.cancel()
        volumeAnimator = ValueAnimator.ofFloat(currentAppliedVolume, targetPercent).apply {
            this.duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val animatedValue = animation.animatedValue as Float
                currentAppliedVolume = animatedValue
                val step = (cachedMaxVolume * animatedValue / 100f).roundToInt()
                    .coerceIn(0, cachedMaxVolume)
                if (step != lastAppliedVolumeStep) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
                    lastAppliedVolumeStep = step
                }
            }
            start()
        }
    }

}
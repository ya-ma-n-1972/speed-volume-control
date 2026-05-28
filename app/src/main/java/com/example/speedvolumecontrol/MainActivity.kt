/**
 * ========================================
 * 車速連動音量制御アプリ - MainActivity.kt（設定モード対応版）
 * ========================================
 *
 * 2ポイント/3ポイントモード切り替え対応のメイン画面
 */

package com.example.speedvolumecontrol

import android.Manifest
import android.content.pm.PackageManager
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.*
import android.media.AudioManager
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    // ========================================
    // 表示用プロパティ
    // ========================================

    private var currentSpeed by mutableStateOf(0f)
    private var isGpsActive by mutableStateOf(false)
    private var currentTime by mutableStateOf("")
    private var logText by mutableStateOf("")
    private var isInPipMode by mutableStateOf(false)
    private var currentAppState = AppState.FOREGROUND
    private var currentGpsState = GpsState.INACTIVE

    // ========================================
    // 音量制御関連プロパティ
    // ========================================

    private var isVolumeAutoAdjustEnabled by mutableStateOf(false)
    private var currentVolumePercent by mutableStateOf(0)
    private lateinit var audioManager: AudioManager

    // ========================================
    // 設定モード関連プロパティ
    // ========================================

    private var settingMode by mutableStateOf(SettingMode.TWO_POINT)

    // 2ポイント設定
    private var twoPointMinSpeed by mutableStateOf(30)
    private var twoPointMaxSpeed by mutableStateOf(80)
    private var twoPointMinVolume by mutableStateOf(40)
    private var twoPointMaxVolume by mutableStateOf(100)
    private var twoPointCurve by mutableStateOf(1.0)

    // 3ポイント設定
    private var threePointSpeed1 by mutableStateOf(30)
    private var threePointSpeed2 by mutableStateOf(55)
    private var threePointSpeed3 by mutableStateOf(80)
    private var threePointVolume1 by mutableStateOf(40)
    private var threePointVolume2 by mutableStateOf(70)
    private var threePointVolume3 by mutableStateOf(100)
    private var threePointCurve1 by mutableStateOf(1.0)
    private var threePointCurve2 by mutableStateOf(1.0)

    // GPS設定
    private var gpsInterval by mutableStateOf(GpsIntervalOptions.DEFAULT)

    // 音量スムージング関連
    private var volumeChangeRate by mutableStateOf(VolumeChangeRateOptions.DEFAULT)
    private var volumeAnimator: ValueAnimator? = null
    private var currentAppliedVolume: Float = 0f
    private var lastAppliedVolumeStep: Int = -1
    private var cachedMaxVolume: Int = 0

    // ========================================
    // 位置情報関連
    // ========================================

    private lateinit var locationManager: LocationManager
    private var locationListener: LocationListener? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private lateinit var gpsTimeoutHandler: Handler
    private lateinit var gpsTimeoutRunnable: Runnable
    private lateinit var timeUpdateHandler: Handler
    private lateinit var timeUpdateRunnable: Runnable

    // GPS信号を一度でも受信したかどうかのフラグ
    // - false: 起動後まだ一度もGPS信号を受信していない → 0km/hとして扱う
    // - true: 一度でも受信した → 信号ロスト時は音量維持
    private var hasReceivedGpsSignal = false

    // 設定の永続化用
    private lateinit var sharedPreferences: SharedPreferences

    // ========================================
    // BroadcastReceiver
    // ========================================

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LocationServiceConstants.ACTION_LOCATION_UPDATE -> {
                    val speed = intent.getFloatExtra(LocationServiceConstants.EXTRA_SPEED, 0f)
                    val gpsStateName = intent.getStringExtra(LocationServiceConstants.EXTRA_GPS_STATE)

                    currentSpeed = speed

                    gpsStateName?.let {
                        val newGpsState = GpsState.valueOf(it)
                        if (newGpsState == GpsState.ACTIVE && currentGpsState == GpsState.INACTIVE) {
                            isGpsActive = true
                        } else if (newGpsState == GpsState.INACTIVE && currentGpsState == GpsState.ACTIVE) {
                            isGpsActive = false
                        }
                        currentGpsState = newGpsState
                    }

                    if (isVolumeAutoAdjustEnabled) {
                        calculateAndSetVolume(speed)
                    }
                }

                LocationServiceConstants.ACTION_LOG_MESSAGE -> {
                    val message = intent.getStringExtra(LocationServiceConstants.EXTRA_LOG_MESSAGE)
                    message?.let {
                        logText = "$it\n$logText"
                        trimLogIfNeeded()
                    }
                }
            }
        }
    }

    // ========================================
    // パーミッション関連
    // ========================================

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineLocationGranted) {
            startLocationUpdates()
            checkAndRequestNotificationPermission()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            addLog("[PERMISSION] 通知パーミッションが拒否されました")
        }
    }

    // ========================================
    // ライフサイクル
    // ========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        cachedMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        currentAppliedVolume = if (cachedMaxVolume > 0) currentStep * 100f / cachedMaxVolume else 0f
        lastAppliedVolumeStep = currentStep
        currentVolumePercent = currentAppliedVolume.roundToInt()

        // SharedPreferencesの初期化と設定復元
        sharedPreferences = getSharedPreferences(PreferenceKeys.PREF_NAME, Context.MODE_PRIVATE)
        loadSettings()

        gpsTimeoutHandler = Handler(Looper.getMainLooper())
        gpsTimeoutRunnable = Runnable {
            if (currentGpsState == GpsState.ACTIVE) {
                currentGpsState = GpsState.INACTIVE
                isGpsActive = false
                addLog("[GPS] 信号ロスト (モード: ${currentAppState.name})")
            }
        }

        registerLocationReceiver()

        setContent {
            MaterialTheme {
                if (isInPipMode) {
                    PipScreen(
                        speed = currentSpeed,
                        isGpsActive = isGpsActive,
                        volumePercent = currentVolumePercent,
                        isVolumeAutoAdjustEnabled = isVolumeAutoAdjustEnabled
                    )
                } else {
                    MainScreen(
                        speed = currentSpeed,
                        isGpsActive = isGpsActive,
                        time = currentTime,
                        logText = logText,
                        volumePercent = currentVolumePercent,
                        isVolumeAutoAdjustEnabled = isVolumeAutoAdjustEnabled,
                        settingMode = settingMode,
                        twoPointMinSpeed = twoPointMinSpeed,
                        twoPointMaxSpeed = twoPointMaxSpeed,
                        twoPointMinVolume = twoPointMinVolume,
                        twoPointMaxVolume = twoPointMaxVolume,
                        twoPointCurve = twoPointCurve,
                        threePointSpeed1 = threePointSpeed1,
                        threePointSpeed2 = threePointSpeed2,
                        threePointSpeed3 = threePointSpeed3,
                        threePointVolume1 = threePointVolume1,
                        threePointVolume2 = threePointVolume2,
                        threePointVolume3 = threePointVolume3,
                        threePointCurve1 = threePointCurve1,
                        threePointCurve2 = threePointCurve2,
                        onEnterPip = { enterPipMode() },
                        onCopyLog = { copyLogToClipboard() },
                        onClearLog = { clearLog() },
                        onToggleVolumeAutoAdjust = { toggleVolumeAutoAdjust() },
                        onToggleSettingMode = { toggleSettingMode() },
                        onTwoPointMinSpeedChange = { updateTwoPointMinSpeed(it) },
                        onTwoPointMaxSpeedChange = { updateTwoPointMaxSpeed(it) },
                        onTwoPointMinVolumeChange = { updateTwoPointMinVolume(it) },
                        onTwoPointMaxVolumeChange = { updateTwoPointMaxVolume(it) },
                        onTwoPointCurveChange = { updateTwoPointCurve(it) },
                        onThreePointSpeed1Change = { updateThreePointSpeed1(it) },
                        onThreePointSpeed2Change = { updateThreePointSpeed2(it) },
                        onThreePointSpeed3Change = { updateThreePointSpeed3(it) },
                        onThreePointVolume1Change = { updateThreePointVolume1(it) },
                        onThreePointVolume2Change = { updateThreePointVolume2(it) },
                        onThreePointVolume3Change = { updateThreePointVolume3(it) },
                        onThreePointCurve1Change = { updateThreePointCurve1(it) },
                        onThreePointCurve2Change = { updateThreePointCurve2(it) },
                        gpsInterval = gpsInterval,
                        onGpsIntervalChange = { updateGpsInterval(it) },
                        volumeChangeRate = volumeChangeRate,
                        onVolumeChangeRateChange = { updateVolumeChangeRate(it) }
                    )
                }
            }
        }

        checkAndRequestPermissions()
        startTimeUpdates()
    }

    override fun onResume() {
        super.onResume()

        if (currentAppState == AppState.BACKGROUND) {
            stopLocationService()
        }

        if (!isInPipMode) {
            val previousState = currentAppState
            currentAppState = AppState.FOREGROUND

            if (previousState != AppState.FOREGROUND) {
                addLog("[MODE] 前面モードに移行 (GPS: ${currentGpsState.name}, Speed: ${String.format(Locale.US, "%.1f", currentSpeed)} km/h)")
            }
        }

        val resumeStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        currentAppliedVolume = if (cachedMaxVolume > 0) resumeStep * 100f / cachedMaxVolume else 0f
        lastAppliedVolumeStep = resumeStep

        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()

        if (isInPipMode) {
            if (currentAppState != AppState.PIP) {
                currentAppState = AppState.PIP
                addLog("[MODE] PiPモードに移行")
            }
        } else {
            val previousState = currentAppState
            currentAppState = AppState.BACKGROUND

            if (previousState != AppState.BACKGROUND) {
                addLog("[MODE] バックグラウンドモードに移行")
            }

            volumeAnimator?.cancel()
            stopLocationUpdates()
            startLocationService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gpsTimeoutHandler.removeCallbacks(gpsTimeoutRunnable)
        volumeAnimator?.cancel()
        stopTimeUpdates()
        unregisterReceiver(locationReceiver)
        stopLocationService()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            currentAppState = AppState.PIP
            startLocationUpdates()
        }
    }

    // ========================================
    // BroadcastReceiver登録
    // ========================================

    private fun registerLocationReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(LocationServiceConstants.ACTION_LOCATION_UPDATE)
            addAction(LocationServiceConstants.ACTION_LOG_MESSAGE)
        }

        ContextCompat.registerReceiver(
            this,
            locationReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // ========================================
    // パーミッション関連
    // ========================================

    private fun checkAndRequestPermissions() {
        when {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                startLocationUpdates()
                checkAndRequestNotificationPermission()
            }
            else -> {
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ========================================
    // 位置情報関連
    // ========================================

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
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
        currentSpeed = location.speed * 3.6f

        // GPS信号を初めて受信した場合、フラグを立てる
        if (!hasReceivedGpsSignal) {
            hasReceivedGpsSignal = true
            addLog("[GPS] 初回信号受信")
        }

        if (currentGpsState == GpsState.INACTIVE) {
            currentGpsState = GpsState.ACTIVE
            isGpsActive = true
            addLog("[GPS] 信号取得開始 (モード: ${currentAppState.name}, Speed: ${String.format(Locale.US, "%.1f", currentSpeed)} km/h)")
        }

        if (isVolumeAutoAdjustEnabled) {
            calculateAndSetVolume(currentSpeed)
        }

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
    // サービス制御
    // ========================================

    private fun startLocationService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                addLog("[SERVICE] 通知パーミッションがないため、サービスを開始できません")
                return
            }
        }

        val serviceIntent = Intent(this, LocationForegroundService::class.java).apply {
            putExtra(LocationServiceConstants.EXTRA_VOLUME_AUTO_ADJUST_ENABLED, isVolumeAutoAdjustEnabled)
            putExtra(LocationServiceConstants.EXTRA_SETTING_MODE, settingMode.ordinal)
            putExtra(LocationServiceConstants.EXTRA_SPEEDS, getCurrentSpeeds())
            putExtra(LocationServiceConstants.EXTRA_VOLUMES, getCurrentVolumes())
            putExtra(LocationServiceConstants.EXTRA_CURVES, getCurrentCurves())
            putExtra(LocationServiceConstants.EXTRA_HAS_RECEIVED_GPS, hasReceivedGpsSignal)
            putExtra(LocationServiceConstants.EXTRA_GPS_INTERVAL, gpsInterval)
            putExtra(LocationServiceConstants.EXTRA_VOLUME_CHANGE_RATE, volumeChangeRate)
        }

        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopLocationService() {
        val serviceIntent = Intent(this, LocationForegroundService::class.java)
        stopService(serviceIntent)
    }

    // ========================================
    // 現在の設定値取得
    // ========================================

    private fun getCurrentSpeeds(): FloatArray {
        return when (settingMode) {
            SettingMode.TWO_POINT -> floatArrayOf(
                twoPointMinSpeed.toFloat(),
                twoPointMaxSpeed.toFloat()
            )
            SettingMode.THREE_POINT -> floatArrayOf(
                threePointSpeed1.toFloat(),
                threePointSpeed2.toFloat(),
                threePointSpeed3.toFloat()
            )
        }
    }

    private fun getCurrentVolumes(): FloatArray {
        return when (settingMode) {
            SettingMode.TWO_POINT -> floatArrayOf(
                twoPointMinVolume.toFloat(),
                twoPointMaxVolume.toFloat()
            )
            SettingMode.THREE_POINT -> floatArrayOf(
                threePointVolume1.toFloat(),
                threePointVolume2.toFloat(),
                threePointVolume3.toFloat()
            )
        }
    }

    private fun getCurrentCurves(): DoubleArray {
        return when (settingMode) {
            SettingMode.TWO_POINT -> doubleArrayOf(twoPointCurve)
            SettingMode.THREE_POINT -> doubleArrayOf(threePointCurve1, threePointCurve2)
        }
    }

    // ========================================
    // 時刻更新
    // ========================================

    private fun startTimeUpdates() {
        timeUpdateHandler = Handler(Looper.getMainLooper())
        timeUpdateRunnable = object : Runnable {
            override fun run() {
                currentTime = dateFormat.format(Date())
                timeUpdateHandler.postDelayed(this, 1000)
            }
        }
        timeUpdateHandler.post(timeUpdateRunnable)
    }

    private fun stopTimeUpdates() {
        if (::timeUpdateHandler.isInitialized) {
            timeUpdateHandler.removeCallbacks(timeUpdateRunnable)
        }
    }

    // ========================================
    // PiP関連
    // ========================================

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(2, 3))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    // ========================================
    // ログ関連
    // ========================================

    private fun addLog(message: String) {
        val timestamp = logDateFormat.format(Date())
        logText = "$timestamp - $message\n$logText"
        trimLogIfNeeded()
    }

    private fun trimLogIfNeeded() {
        if (logText.length > 10000) {
            logText = logText.take(10000)
        }
    }

    @SuppressLint("NewApi")
    private fun copyLogToClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Speed Log", logText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "ログをクリップボードにコピーしました", Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        logText = ""
        Toast.makeText(this, "ログを消去しました", Toast.LENGTH_SHORT).show()
    }

    // ========================================
    // 音量制御関連
    // ========================================

    private fun calculateVolumePercent(speedKmh: Float): Int {
        return when (settingMode) {
            SettingMode.TWO_POINT -> calculateTwoPointVolume(speedKmh)
            SettingMode.THREE_POINT -> calculateThreePointVolume(speedKmh)
        }
    }

    private fun calculateTwoPointVolume(speedKmh: Float): Int {
        return when {
            speedKmh <= twoPointMinSpeed -> twoPointMinVolume
            speedKmh >= twoPointMaxSpeed -> twoPointMaxVolume
            else -> {
                val normalized = (speedKmh - twoPointMinSpeed) / (twoPointMaxSpeed - twoPointMinSpeed)
                val curved = normalized.toDouble().pow(twoPointCurve)
                (twoPointMinVolume + (twoPointMaxVolume - twoPointMinVolume) * curved).roundToInt()
            }
        }
    }

    private fun calculateThreePointVolume(speedKmh: Float): Int {
        return when {
            speedKmh <= threePointSpeed1 -> threePointVolume1
            speedKmh >= threePointSpeed3 -> threePointVolume3
            speedKmh <= threePointSpeed2 -> {
                val normalized = (speedKmh - threePointSpeed1) / (threePointSpeed2 - threePointSpeed1)
                val curved = normalized.toDouble().pow(threePointCurve1)
                (threePointVolume1 + (threePointVolume2 - threePointVolume1) * curved).roundToInt()
            }
            else -> {
                val normalized = (speedKmh - threePointSpeed2) / (threePointSpeed3 - threePointSpeed2)
                val curved = normalized.toDouble().pow(threePointCurve2)
                (threePointVolume2 + (threePointVolume3 - threePointVolume2) * curved).roundToInt()
            }
        }
    }

    private fun applyVolumeImmediate(volumePercent: Float) {
        val step = (cachedMaxVolume * volumePercent / 100f).roundToInt()
            .coerceIn(0, cachedMaxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        lastAppliedVolumeStep = step
        currentAppliedVolume = volumePercent
        currentVolumePercent = volumePercent.roundToInt()
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
                currentVolumePercent = animatedValue.roundToInt()
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

    private fun toggleVolumeAutoAdjust() {
        isVolumeAutoAdjustEnabled = !isVolumeAutoAdjustEnabled

        if (isVolumeAutoAdjustEnabled) {
            val modeLabel = if (settingMode == SettingMode.TWO_POINT) "2ポイント" else "3ポイント"

            // GPS信号を一度も受信していない場合は0km/hとして扱う
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f

            if (!hasReceivedGpsSignal) {
                addLog("[VOLUME] 自動調整ON ($modeLabel, GPS未受信のため 0 km/h として計算)")
            } else {
                addLog("[VOLUME] 自動調整ON ($modeLabel, Speed: ${String.format(Locale.US, "%.1f", effectiveSpeed)} km/h)")
            }

            calculateAndSetVolume(effectiveSpeed)
        } else {
            volumeAnimator?.cancel()
            addLog("[VOLUME] 自動調整OFF (Volume: ${currentVolumePercent}%)")
        }

        Toast.makeText(
            this,
            if (isVolumeAutoAdjustEnabled) "音量自動調整: ON" else "音量自動調整: OFF",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ========================================
    // GPS設定
    // ========================================

    private fun updateGpsInterval(value: Long) {
        gpsInterval = value
        saveSettings()
        stopLocationUpdates()
        startLocationUpdates()
        addLog("[GPS] 更新間隔変更: ${value}ms")
    }

    private fun updateVolumeChangeRate(value: Int) {
        volumeChangeRate = value
        saveSettings()
        addLog("[SETTING] 音量変化速度変更: ${VolumeChangeRateOptions.getLabel(value)}")
    }

    // ========================================
    // 設定モード切り替え
    // ========================================

    private fun toggleSettingMode() {
        settingMode = if (settingMode == SettingMode.TWO_POINT) {
            // 2ポイント → 3ポイント: 中間値を計算
            val midSpeed = ((twoPointMinSpeed + twoPointMaxSpeed) / 2 / 5) * 5
            val normalized = (midSpeed - twoPointMinSpeed).toFloat() / (twoPointMaxSpeed - twoPointMinSpeed)
            val curved = normalized.toDouble().pow(twoPointCurve)
            val midVolume = ((twoPointMinVolume + (twoPointMaxVolume - twoPointMinVolume) * curved).roundToInt() / 5) * 5

            threePointSpeed1 = twoPointMinSpeed
            threePointSpeed2 = midSpeed
            threePointSpeed3 = twoPointMaxSpeed
            threePointVolume1 = twoPointMinVolume
            threePointVolume2 = midVolume
            threePointVolume3 = twoPointMaxVolume
            threePointCurve1 = twoPointCurve
            threePointCurve2 = twoPointCurve

            addLog("[SETTING] 3ポイントモードに切り替え")
            SettingMode.THREE_POINT
        } else {
            // 3ポイント → 2ポイント
            twoPointMinSpeed = threePointSpeed1
            twoPointMaxSpeed = threePointSpeed3
            twoPointMinVolume = threePointVolume1
            twoPointMaxVolume = threePointVolume3
            twoPointCurve = threePointCurve1

            addLog("[SETTING] 2ポイントモードに切り替え")
            SettingMode.TWO_POINT
        }

        // 設定を保存
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    // ========================================
    // 2ポイント設定バリデーション
    // ========================================

    private fun updateTwoPointMinSpeed(value: Int) {
        twoPointMinSpeed = value
        if (twoPointMaxSpeed < value + ValidationConstants.MIN_SPEED_DIFF) {
            twoPointMaxSpeed = value + ValidationConstants.MIN_SPEED_DIFF
        }
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    private fun updateTwoPointMaxSpeed(value: Int) {
        twoPointMaxSpeed = value
        if (twoPointMinSpeed > value - ValidationConstants.MIN_SPEED_DIFF) {
            twoPointMinSpeed = value - ValidationConstants.MIN_SPEED_DIFF
        }
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    private fun updateTwoPointMinVolume(value: Int) {
        twoPointMinVolume = value
        if (twoPointMaxVolume < value + ValidationConstants.MIN_VOLUME_DIFF) {
            twoPointMaxVolume = value + ValidationConstants.MIN_VOLUME_DIFF
        }
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    private fun updateTwoPointMaxVolume(value: Int) {
        twoPointMaxVolume = value
        if (twoPointMinVolume > value - ValidationConstants.MIN_VOLUME_DIFF) {
            twoPointMinVolume = value - ValidationConstants.MIN_VOLUME_DIFF
        }
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    private fun updateTwoPointCurve(value: Double) {
        twoPointCurve = value
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    // ========================================
    // 3ポイント設定バリデーション
    // ========================================

    private fun updateThreePointSpeed1(value: Int) {
        threePointSpeed1 = value
        if (threePointSpeed2 < value + ValidationConstants.MIN_SPEED_DIFF) {
            threePointSpeed2 = value + ValidationConstants.MIN_SPEED_DIFF
        }
        if (threePointSpeed3 < threePointSpeed2 + ValidationConstants.MIN_SPEED_DIFF) {
            threePointSpeed3 = threePointSpeed2 + ValidationConstants.MIN_SPEED_DIFF
        }
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    private fun updateThreePointSpeed2(value: Int) {
        threePointSpeed2 = value
        if (threePointSpeed1 > value - ValidationConstants.MIN_SPEED_DIFF) {
            threePointSpeed1 = value - ValidationConstants.MIN_SPEED_DIFF
        }
        if (threePointSpeed3 < value + ValidationConstants.MIN_SPEED_DIFF) {
            threePointSpeed3 = value + ValidationConstants.MIN_SPEED_DIFF
        }
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    private fun updateThreePointSpeed3(value: Int) {
        threePointSpeed3 = value
        if (threePointSpeed2 > value - ValidationConstants.MIN_SPEED_DIFF) {
            threePointSpeed2 = value - ValidationConstants.MIN_SPEED_DIFF
        }
        if (threePointSpeed1 > threePointSpeed2 - ValidationConstants.MIN_SPEED_DIFF) {
            threePointSpeed1 = threePointSpeed2 - ValidationConstants.MIN_SPEED_DIFF
        }
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    private fun updateThreePointVolume1(value: Int) {
        threePointVolume1 = value
        if (threePointVolume2 < value + ValidationConstants.MIN_VOLUME_DIFF) {
            threePointVolume2 = value + ValidationConstants.MIN_VOLUME_DIFF
        }
        if (threePointVolume3 < threePointVolume2 + ValidationConstants.MIN_VOLUME_DIFF) {
            threePointVolume3 = threePointVolume2 + ValidationConstants.MIN_VOLUME_DIFF
        }
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    private fun updateThreePointVolume2(value: Int) {
        threePointVolume2 = value
        if (threePointVolume1 > value - ValidationConstants.MIN_VOLUME_DIFF) {
            threePointVolume1 = value - ValidationConstants.MIN_VOLUME_DIFF
        }
        if (threePointVolume3 < value + ValidationConstants.MIN_VOLUME_DIFF) {
            threePointVolume3 = value + ValidationConstants.MIN_VOLUME_DIFF
        }
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    private fun updateThreePointVolume3(value: Int) {
        threePointVolume3 = value
        if (threePointVolume2 > value - ValidationConstants.MIN_VOLUME_DIFF) {
            threePointVolume2 = value - ValidationConstants.MIN_VOLUME_DIFF
        }
        if (threePointVolume1 > threePointVolume2 - ValidationConstants.MIN_VOLUME_DIFF) {
            threePointVolume1 = threePointVolume2 - ValidationConstants.MIN_VOLUME_DIFF
        }
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    private fun updateThreePointCurve1(value: Double) {
        threePointCurve1 = value
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    private fun updateThreePointCurve2(value: Double) {
        threePointCurve2 = value
        saveSettings()
        if (isVolumeAutoAdjustEnabled) {
            val effectiveSpeed = if (hasReceivedGpsSignal) currentSpeed else 0f
            calculateAndSetVolume(effectiveSpeed)
        }
    }

    // ========================================
    // 設定永続化（SharedPreferences）
    // ========================================

    /**
     * 設定をSharedPreferencesに保存する
     */
    private fun saveSettings() {
        sharedPreferences.edit().apply {
            // 設定モード
            putString(PreferenceKeys.SETTING_MODE, settingMode.name)

            // 2ポイント設定
            putInt(PreferenceKeys.TWO_POINT_MIN_SPEED, twoPointMinSpeed)
            putInt(PreferenceKeys.TWO_POINT_MAX_SPEED, twoPointMaxSpeed)
            putInt(PreferenceKeys.TWO_POINT_MIN_VOLUME, twoPointMinVolume)
            putInt(PreferenceKeys.TWO_POINT_MAX_VOLUME, twoPointMaxVolume)
            putFloat(PreferenceKeys.TWO_POINT_CURVE, twoPointCurve.toFloat())

            // 3ポイント設定
            putInt(PreferenceKeys.THREE_POINT_SPEED_1, threePointSpeed1)
            putInt(PreferenceKeys.THREE_POINT_SPEED_2, threePointSpeed2)
            putInt(PreferenceKeys.THREE_POINT_SPEED_3, threePointSpeed3)
            putInt(PreferenceKeys.THREE_POINT_VOLUME_1, threePointVolume1)
            putInt(PreferenceKeys.THREE_POINT_VOLUME_2, threePointVolume2)
            putInt(PreferenceKeys.THREE_POINT_VOLUME_3, threePointVolume3)
            putFloat(PreferenceKeys.THREE_POINT_CURVE_1, threePointCurve1.toFloat())
            putFloat(PreferenceKeys.THREE_POINT_CURVE_2, threePointCurve2.toFloat())

            // GPS設定
            putLong(PreferenceKeys.GPS_INTERVAL, gpsInterval)

            // 音量変化速度
            putInt(PreferenceKeys.VOLUME_CHANGE_RATE, volumeChangeRate)

            apply()
        }
    }

    /**
     * 設定をSharedPreferencesから復元する
     */
    private fun loadSettings() {
        // 設定モード
        val modeName = sharedPreferences.getString(PreferenceKeys.SETTING_MODE, SettingMode.TWO_POINT.name)
        settingMode = try {
            SettingMode.valueOf(modeName ?: SettingMode.TWO_POINT.name)
        } catch (e: IllegalArgumentException) {
            SettingMode.TWO_POINT
        }

        // 2ポイント設定（デフォルト値はTwoPointSettingsから）
        val defaultTwoPoint = TwoPointSettings()
        twoPointMinSpeed = sharedPreferences.getInt(PreferenceKeys.TWO_POINT_MIN_SPEED, defaultTwoPoint.minSpeed)
        twoPointMaxSpeed = sharedPreferences.getInt(PreferenceKeys.TWO_POINT_MAX_SPEED, defaultTwoPoint.maxSpeed)
        twoPointMinVolume = sharedPreferences.getInt(PreferenceKeys.TWO_POINT_MIN_VOLUME, defaultTwoPoint.minVolume)
        twoPointMaxVolume = sharedPreferences.getInt(PreferenceKeys.TWO_POINT_MAX_VOLUME, defaultTwoPoint.maxVolume)
        twoPointCurve = sharedPreferences.getFloat(PreferenceKeys.TWO_POINT_CURVE, defaultTwoPoint.curvePower.toFloat()).toDouble()

        // 3ポイント設定（デフォルト値はThreePointSettingsから）
        val defaultThreePoint = ThreePointSettings()
        threePointSpeed1 = sharedPreferences.getInt(PreferenceKeys.THREE_POINT_SPEED_1, defaultThreePoint.speed1)
        threePointSpeed2 = sharedPreferences.getInt(PreferenceKeys.THREE_POINT_SPEED_2, defaultThreePoint.speed2)
        threePointSpeed3 = sharedPreferences.getInt(PreferenceKeys.THREE_POINT_SPEED_3, defaultThreePoint.speed3)
        threePointVolume1 = sharedPreferences.getInt(PreferenceKeys.THREE_POINT_VOLUME_1, defaultThreePoint.volume1)
        threePointVolume2 = sharedPreferences.getInt(PreferenceKeys.THREE_POINT_VOLUME_2, defaultThreePoint.volume2)
        threePointVolume3 = sharedPreferences.getInt(PreferenceKeys.THREE_POINT_VOLUME_3, defaultThreePoint.volume3)
        threePointCurve1 = sharedPreferences.getFloat(PreferenceKeys.THREE_POINT_CURVE_1, defaultThreePoint.curve1.toFloat()).toDouble()
        threePointCurve2 = sharedPreferences.getFloat(PreferenceKeys.THREE_POINT_CURVE_2, defaultThreePoint.curve2.toFloat()).toDouble()

        // GPS設定
        gpsInterval = sharedPreferences.getLong(PreferenceKeys.GPS_INTERVAL, GpsIntervalOptions.DEFAULT)

        // 音量変化速度
        volumeChangeRate = sharedPreferences.getInt(PreferenceKeys.VOLUME_CHANGE_RATE, VolumeChangeRateOptions.DEFAULT)

        addLog("[SETTING] 設定を復元 (モード: ${settingMode.name})")
    }
}

// ========================================
// Composable関数
// ========================================

@Composable
fun MainScreen(
    speed: Float,
    isGpsActive: Boolean,
    time: String,
    logText: String,
    volumePercent: Int,
    isVolumeAutoAdjustEnabled: Boolean,
    settingMode: SettingMode,
    twoPointMinSpeed: Int,
    twoPointMaxSpeed: Int,
    twoPointMinVolume: Int,
    twoPointMaxVolume: Int,
    twoPointCurve: Double,
    threePointSpeed1: Int,
    threePointSpeed2: Int,
    threePointSpeed3: Int,
    threePointVolume1: Int,
    threePointVolume2: Int,
    threePointVolume3: Int,
    threePointCurve1: Double,
    threePointCurve2: Double,
    onEnterPip: () -> Unit,
    onCopyLog: () -> Unit,
    onClearLog: () -> Unit,
    onToggleVolumeAutoAdjust: () -> Unit,
    onToggleSettingMode: () -> Unit,
    onTwoPointMinSpeedChange: (Int) -> Unit,
    onTwoPointMaxSpeedChange: (Int) -> Unit,
    onTwoPointMinVolumeChange: (Int) -> Unit,
    onTwoPointMaxVolumeChange: (Int) -> Unit,
    onTwoPointCurveChange: (Double) -> Unit,
    onThreePointSpeed1Change: (Int) -> Unit,
    onThreePointSpeed2Change: (Int) -> Unit,
    onThreePointSpeed3Change: (Int) -> Unit,
    onThreePointVolume1Change: (Int) -> Unit,
    onThreePointVolume2Change: (Int) -> Unit,
    onThreePointVolume3Change: (Int) -> Unit,
    onThreePointCurve1Change: (Double) -> Unit,
    onThreePointCurve2Change: (Double) -> Unit,
    gpsInterval: Long,
    onGpsIntervalChange: (Long) -> Unit,
    volumeChangeRate: Int,
    onVolumeChangeRateChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 時刻表示
        Text(
            text = time,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // GPSシグナルと速度計
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GpsSignalIndicator(isActive = isGpsActive)

            Text(
                text = "${speed.toInt()}",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.Green
            )

            Text(
                text = "km/h",
                fontSize = 20.sp,
                color = Color.Gray
            )
        }

        // 音量表示
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Vol: ${volumePercent}%",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (isVolumeAutoAdjustEnabled) Color.Cyan else Color.Gray
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ボタン類
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onEnterPip, modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text("PiP", fontSize = 12.sp)
            }
            Button(
                onClick = onToggleVolumeAutoAdjust,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isVolumeAutoAdjustEnabled) Color(0xFF00AA00) else Color.DarkGray
                )
            ) {
                Text(if (isVolumeAutoAdjustEnabled) "自動ON" else "自動OFF", fontSize = 12.sp)
            }
            Button(onClick = onCopyLog, modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text("コピー", fontSize = 12.sp)
            }
            Button(onClick = onClearLog, modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text("消去", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 設定セクション
        SettingsSection(
            settingMode = settingMode,
            twoPointMinSpeed = twoPointMinSpeed,
            twoPointMaxSpeed = twoPointMaxSpeed,
            twoPointMinVolume = twoPointMinVolume,
            twoPointMaxVolume = twoPointMaxVolume,
            twoPointCurve = twoPointCurve,
            threePointSpeed1 = threePointSpeed1,
            threePointSpeed2 = threePointSpeed2,
            threePointSpeed3 = threePointSpeed3,
            threePointVolume1 = threePointVolume1,
            threePointVolume2 = threePointVolume2,
            threePointVolume3 = threePointVolume3,
            threePointCurve1 = threePointCurve1,
            threePointCurve2 = threePointCurve2,
            onToggleSettingMode = onToggleSettingMode,
            onTwoPointMinSpeedChange = onTwoPointMinSpeedChange,
            onTwoPointMaxSpeedChange = onTwoPointMaxSpeedChange,
            onTwoPointMinVolumeChange = onTwoPointMinVolumeChange,
            onTwoPointMaxVolumeChange = onTwoPointMaxVolumeChange,
            onTwoPointCurveChange = onTwoPointCurveChange,
            onThreePointSpeed1Change = onThreePointSpeed1Change,
            onThreePointSpeed2Change = onThreePointSpeed2Change,
            onThreePointSpeed3Change = onThreePointSpeed3Change,
            onThreePointVolume1Change = onThreePointVolume1Change,
            onThreePointVolume2Change = onThreePointVolume2Change,
            onThreePointVolume3Change = onThreePointVolume3Change,
            onThreePointCurve1Change = onThreePointCurve1Change,
            onThreePointCurve2Change = onThreePointCurve2Change,
            gpsInterval = gpsInterval,
            onGpsIntervalChange = onGpsIntervalChange,
            volumeChangeRate = volumeChangeRate,
            onVolumeChangeRateChange = onVolumeChangeRateChange
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ログ表示エリア
        Text(text = "ログ:", fontSize = 12.sp, color = Color.Gray)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Color.DarkGray)
                .padding(8.dp)
        ) {
            Text(
                text = logText,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.LightGray,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
fun SettingsSection(
    settingMode: SettingMode,
    twoPointMinSpeed: Int,
    twoPointMaxSpeed: Int,
    twoPointMinVolume: Int,
    twoPointMaxVolume: Int,
    twoPointCurve: Double,
    threePointSpeed1: Int,
    threePointSpeed2: Int,
    threePointSpeed3: Int,
    threePointVolume1: Int,
    threePointVolume2: Int,
    threePointVolume3: Int,
    threePointCurve1: Double,
    threePointCurve2: Double,
    onToggleSettingMode: () -> Unit,
    onTwoPointMinSpeedChange: (Int) -> Unit,
    onTwoPointMaxSpeedChange: (Int) -> Unit,
    onTwoPointMinVolumeChange: (Int) -> Unit,
    onTwoPointMaxVolumeChange: (Int) -> Unit,
    onTwoPointCurveChange: (Double) -> Unit,
    onThreePointSpeed1Change: (Int) -> Unit,
    onThreePointSpeed2Change: (Int) -> Unit,
    onThreePointSpeed3Change: (Int) -> Unit,
    onThreePointVolume1Change: (Int) -> Unit,
    onThreePointVolume2Change: (Int) -> Unit,
    onThreePointVolume3Change: (Int) -> Unit,
    onThreePointCurve1Change: (Double) -> Unit,
    onThreePointCurve2Change: (Double) -> Unit,
    gpsInterval: Long,
    onGpsIntervalChange: (Long) -> Unit,
    volumeChangeRate: Int,
    onVolumeChangeRateChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(12.dp)
    ) {
        Text(
            text = "⚙️ 調整設定",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        // GPS更新間隔
        SettingRow("GPS更新間隔:", GpsIntervalOptions.getLabel(gpsInterval)) {
            GpsIntervalDropdownSelector(
                selectedValue = gpsInterval,
                onValueChange = onGpsIntervalChange
            )
        }

        // 音量変化速度
        SettingRow("音量変化速度:", VolumeChangeRateOptions.getLabel(volumeChangeRate)) {
            VolumeChangeRateDropdownSelector(
                selectedValue = volumeChangeRate,
                onValueChange = onVolumeChangeRateChange
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // モード切り替えボタン
        Button(
            onClick = onToggleSettingMode,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (settingMode == SettingMode.TWO_POINT)
                    Color(0xFFFF9800) else Color(0xFF9C27B0)
            )
        ) {
            Text(
                text = if (settingMode == SettingMode.TWO_POINT)
                    "設計ポイントを増やす (2点 → 3点)"
                else
                    "設計ポイントを減らす (3点 → 2点)",
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 設定内容（モードに応じて切り替え）
        if (settingMode == SettingMode.TWO_POINT) {
            TwoPointSettings(
                minSpeed = twoPointMinSpeed,
                maxSpeed = twoPointMaxSpeed,
                minVolume = twoPointMinVolume,
                maxVolume = twoPointMaxVolume,
                curve = twoPointCurve,
                onMinSpeedChange = onTwoPointMinSpeedChange,
                onMaxSpeedChange = onTwoPointMaxSpeedChange,
                onMinVolumeChange = onTwoPointMinVolumeChange,
                onMaxVolumeChange = onTwoPointMaxVolumeChange,
                onCurveChange = onTwoPointCurveChange
            )
        } else {
            ThreePointSettings(
                speed1 = threePointSpeed1,
                speed2 = threePointSpeed2,
                speed3 = threePointSpeed3,
                volume1 = threePointVolume1,
                volume2 = threePointVolume2,
                volume3 = threePointVolume3,
                curve1 = threePointCurve1,
                curve2 = threePointCurve2,
                onSpeed1Change = onThreePointSpeed1Change,
                onSpeed2Change = onThreePointSpeed2Change,
                onSpeed3Change = onThreePointSpeed3Change,
                onVolume1Change = onThreePointVolume1Change,
                onVolume2Change = onThreePointVolume2Change,
                onVolume3Change = onThreePointVolume3Change,
                onCurve1Change = onThreePointCurve1Change,
                onCurve2Change = onThreePointCurve2Change
            )
        }
    }
}

@Composable
fun TwoPointSettings(
    minSpeed: Int,
    maxSpeed: Int,
    minVolume: Int,
    maxVolume: Int,
    curve: Double,
    onMinSpeedChange: (Int) -> Unit,
    onMaxSpeedChange: (Int) -> Unit,
    onMinVolumeChange: (Int) -> Unit,
    onMaxVolumeChange: (Int) -> Unit,
    onCurveChange: (Double) -> Unit
) {
    val speedOptions = (5..100 step 5).toList()
    val volumeOptions = (0..100 step 5).toList()

    Column {
        SettingRow("最低音量:", "$minVolume%") {
            DropdownSelector(
                options = volumeOptions,
                selectedValue = minVolume,
                onValueChange = onMinVolumeChange,
                labelFormatter = { "$it%" }
            )
        }

        SettingRow("最高音量:", "$maxVolume%") {
            DropdownSelector(
                options = volumeOptions,
                selectedValue = maxVolume,
                onValueChange = onMaxVolumeChange,
                labelFormatter = { "$it%" }
            )
        }

        SettingRow("開始速度:", "$minSpeed km/h") {
            DropdownSelector(
                options = speedOptions,
                selectedValue = minSpeed,
                onValueChange = onMinSpeedChange,
                labelFormatter = { "$it km/h" }
            )
        }

        SettingRow("上限速度:", "$maxSpeed km/h") {
            DropdownSelector(
                options = speedOptions,
                selectedValue = maxSpeed,
                onValueChange = onMaxSpeedChange,
                labelFormatter = { "$it km/h" }
            )
        }

        SettingRow("カーブ:", CurveOptions.getLabel(curve)) {
            CurveDropdownSelector(
                selectedValue = curve,
                onValueChange = onCurveChange
            )
        }
    }
}

@Composable
fun ThreePointSettings(
    speed1: Int,
    speed2: Int,
    speed3: Int,
    volume1: Int,
    volume2: Int,
    volume3: Int,
    curve1: Double,
    curve2: Double,
    onSpeed1Change: (Int) -> Unit,
    onSpeed2Change: (Int) -> Unit,
    onSpeed3Change: (Int) -> Unit,
    onVolume1Change: (Int) -> Unit,
    onVolume2Change: (Int) -> Unit,
    onVolume3Change: (Int) -> Unit,
    onCurve1Change: (Double) -> Unit,
    onCurve2Change: (Double) -> Unit
) {
    val speedOptions = (5..100 step 5).toList()
    val volumeOptions = (0..100 step 5).toList()

    Column {
        // ポイント1
        SettingRow("速度1:", "$speed1 km/h") {
            DropdownSelector(speedOptions, speed1, onSpeed1Change) { "$it km/h" }
        }
        SettingRow("音量1:", "$volume1%") {
            DropdownSelector(volumeOptions, volume1, onVolume1Change) { "$it%" }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ポイント2
        SettingRow("速度2:", "$speed2 km/h") {
            DropdownSelector(speedOptions, speed2, onSpeed2Change) { "$it km/h" }
        }
        SettingRow("音量2:", "$volume2%") {
            DropdownSelector(volumeOptions, volume2, onVolume2Change) { "$it%" }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ポイント3
        SettingRow("速度3:", "$speed3 km/h") {
            DropdownSelector(speedOptions, speed3, onSpeed3Change) { "$it km/h" }
        }
        SettingRow("音量3:", "$volume3%") {
            DropdownSelector(volumeOptions, volume3, onVolume3Change) { "$it%" }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // カーブ設定
        SettingRow("カーブ1 (V1→V2):", "") {
            CurveDropdownSelector(curve1, onCurve1Change)
        }
        SettingRow("カーブ2 (V2→V3):", "") {
            CurveDropdownSelector(curve2, onCurve2Change)
        }
    }
}

@Composable
fun SettingRow(
    label: String,
    currentValue: String,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier.width(100.dp)
        )
        content()
    }
}

@Composable
fun <T> DropdownSelector(
    options: List<T>,
    selectedValue: T,
    onValueChange: (T) -> Unit,
    labelFormatter: (T) -> String
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Text(
            text = labelFormatter(selectedValue),
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier
                .background(Color(0xFF333333), RoundedCornerShape(4.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelFormatter(option), fontSize = 12.sp) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun CurveDropdownSelector(
    selectedValue: Double,
    onValueChange: (Double) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Text(
            text = CurveOptions.getLabel(selectedValue),
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier
                .background(Color(0xFF333333), RoundedCornerShape(4.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            CurveOptions.values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(CurveOptions.labels[index], fontSize = 11.sp) },
                    onClick = {
                        onValueChange(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun GpsIntervalDropdownSelector(
    selectedValue: Long,
    onValueChange: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Text(
            text = GpsIntervalOptions.getLabel(selectedValue),
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier
                .background(Color(0xFF333333), RoundedCornerShape(4.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            GpsIntervalOptions.values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(GpsIntervalOptions.labels[index], fontSize = 11.sp) },
                    onClick = {
                        onValueChange(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun VolumeChangeRateDropdownSelector(
    selectedValue: Int,
    onValueChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Text(
            text = VolumeChangeRateOptions.getLabel(selectedValue),
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier
                .background(Color(0xFF333333), RoundedCornerShape(4.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            VolumeChangeRateOptions.values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(VolumeChangeRateOptions.labels[index], fontSize = 11.sp) },
                    onClick = {
                        onValueChange(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun PipScreen(
    speed: Float,
    isGpsActive: Boolean,
    volumePercent: Int,
    isVolumeAutoAdjustEnabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isGpsActive) "${speed.toInt()}" else "--",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Color.Green
        )

        Text(
            text = "${volumePercent}%",
            fontSize = 36.sp,
            fontFamily = FontFamily.Monospace,
            color = if (isVolumeAutoAdjustEnabled) Color.Cyan else Color.Gray
        )
    }
}

@Composable
fun GpsSignalIndicator(isActive: Boolean, size: Int = 24) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(
                color = if (isActive) Color.Green else Color.Red,
                shape = CircleShape
            )
    )
}
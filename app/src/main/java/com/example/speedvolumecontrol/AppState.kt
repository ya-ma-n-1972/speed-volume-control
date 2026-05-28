/**
 * ========================================
 * AppState.kt - アプリ状態定義（設定モード対応版）
 * ========================================
 *
 * アプリ全体で共有する列挙型、定数、設定データクラスを定義します。
 *
 * 【変更点】
 * - 2ポイント/3ポイントモード切り替え対応
 * - ユーザー設定可能な音量カーブ設定
 * - バリデーション定数の追加
 */

package com.example.speedvolumecontrol

/**
 * アプリの表示状態を表す列挙型
 */
enum class AppState {
    /** 前面表示中 */
    FOREGROUND,
    /** PiPモード中 */
    PIP,
    /** バックグラウンド */
    BACKGROUND
}

/**
 * GPS信号の状態を表す列挙型
 */
enum class GpsState {
    /** GPS信号取得中 */
    ACTIVE,
    /** GPS信号なし */
    INACTIVE
}

/**
 * 設定モード（2ポイント/3ポイント）を表す列挙型
 */
enum class SettingMode {
    /** 2ポイントモード（シンプル） */
    TWO_POINT,
    /** 3ポイントモード（詳細） */
    THREE_POINT
}

/**
 * 位置情報サービス関連の定数
 */
object LocationServiceConstants {

    // Broadcastアクション名
    const val ACTION_LOCATION_UPDATE = "com.example.speedvolumecontrol.LOCATION_UPDATE"
    const val ACTION_LOG_MESSAGE = "com.example.speedvolumecontrol.LOG_MESSAGE"

    // Intent Extraキー名
    const val EXTRA_SPEED = "extra_speed"
    const val EXTRA_GPS_STATE = "extra_gps_state"
    const val EXTRA_LOG_MESSAGE = "extra_log_message"
    const val EXTRA_VOLUME_AUTO_ADJUST_ENABLED = "extra_volume_auto_adjust_enabled"

    // 設定値のExtraキー（サービスへの受け渡し用）
    const val EXTRA_SETTING_MODE = "extra_setting_mode"
    const val EXTRA_SPEEDS = "extra_speeds"
    const val EXTRA_VOLUMES = "extra_volumes"
    const val EXTRA_CURVES = "extra_curves"
    const val EXTRA_HAS_RECEIVED_GPS = "extra_has_received_gps"
    const val EXTRA_GPS_INTERVAL = "extra_gps_interval"
    const val EXTRA_VOLUME_CHANGE_RATE = "extra_volume_change_rate"

    // 通知関連
    const val NOTIFICATION_CHANNEL_ID = "location_service_channel"
    const val NOTIFICATION_ID = 1001

    // タイムアウト設定
    const val GPS_TIMEOUT_MS = 5000L
}

/**
 * バリデーション定数
 */
object ValidationConstants {
    /** 音量の最小差分（%） */
    const val MIN_VOLUME_DIFF = 5

    /** 速度の最小差分（km/h） */
    const val MIN_SPEED_DIFF = 5
}

/**
 * SharedPreferencesのキー名定数
 */
object PreferenceKeys {
    const val PREF_NAME = "speed_volume_control_settings"

    // 設定モード
    const val SETTING_MODE = "setting_mode"

    // 2ポイント設定
    const val TWO_POINT_MIN_SPEED = "two_point_min_speed"
    const val TWO_POINT_MAX_SPEED = "two_point_max_speed"
    const val TWO_POINT_MIN_VOLUME = "two_point_min_volume"
    const val TWO_POINT_MAX_VOLUME = "two_point_max_volume"
    const val TWO_POINT_CURVE = "two_point_curve"

    // 3ポイント設定
    const val THREE_POINT_SPEED_1 = "three_point_speed_1"
    const val THREE_POINT_SPEED_2 = "three_point_speed_2"
    const val THREE_POINT_SPEED_3 = "three_point_speed_3"
    const val THREE_POINT_VOLUME_1 = "three_point_volume_1"
    const val THREE_POINT_VOLUME_2 = "three_point_volume_2"
    const val THREE_POINT_VOLUME_3 = "three_point_volume_3"
    const val THREE_POINT_CURVE_1 = "three_point_curve_1"
    const val THREE_POINT_CURVE_2 = "three_point_curve_2"

    // GPS設定
    const val GPS_INTERVAL = "gps_interval"

    // 音量変化速度
    const val VOLUME_CHANGE_RATE = "volume_change_rate"
}

/**
 * 選択可能なカーブ値
 */
object CurveOptions {
    val values = listOf(0.5, 0.7, 1.0, 1.3, 1.5, 2.0)

    val labels = listOf(
        "非常に緩やか(低速重視)",
        "緩やか",
        "線形(標準)",
        "やや急",
        "急",
        "非常に急(高速重視)"
    )

    fun getLabel(value: Double): String {
        val index = values.indexOf(value)
        return if (index >= 0) labels[index] else "線形(標準)"
    }
}

/**
 * GPS更新間隔の選択肢
 */
object GpsIntervalOptions {
    val values = listOf(500L, 1000L, 2000L, 4000L)

    val labels = listOf(
        "高速（0.5s）",
        "標準（1s）",
        "省電力（2s）",
        "最小（4s）"
    )

    const val DEFAULT = 500L

    fun getLabel(value: Long): String {
        val index = values.indexOf(value)
        return if (index >= 0) labels[index] else "標準（1s）"
    }
}

/**
 * 音量変化速度の選択肢
 */
object VolumeChangeRateOptions {
    val values = listOf(100, 80, 70, 60, 50)

    val labels = listOf(
        "100%",
        "80%",
        "70%",
        "60%",
        "50%"
    )

    const val DEFAULT = 70

    fun getLabel(value: Int): String {
        val index = values.indexOf(value)
        return if (index >= 0) labels[index] else "70%"
    }
}

/**
 * 2ポイントモードの設定データクラス
 *
 * @param minSpeed 開始速度（km/h）
 * @param maxSpeed 上限速度（km/h）
 * @param minVolume 最低音量（%）
 * @param maxVolume 最高音量（%）
 * @param curvePower カーブ強度
 */
data class TwoPointSettings(
    val minSpeed: Int = 30,
    val maxSpeed: Int = 80,
    val minVolume: Int = 40,
    val maxVolume: Int = 100,
    val curvePower: Double = 1.0
)

/**
 * 3ポイントモードの設定データクラス
 *
 * @param speed1 速度ポイント1（km/h）
 * @param speed2 速度ポイント2（km/h）
 * @param speed3 速度ポイント3（km/h）
 * @param volume1 音量ポイント1（%）
 * @param volume2 音量ポイント2（%）
 * @param volume3 音量ポイント3（%）
 * @param curve1 セグメント1のカーブ（V1→V2）
 * @param curve2 セグメント2のカーブ（V2→V3）
 */
data class ThreePointSettings(
    val speed1: Int = 30,
    val speed2: Int = 55,
    val speed3: Int = 80,
    val volume1: Int = 40,
    val volume2: Int = 70,
    val volume3: Int = 100,
    val curve1: Double = 1.0,
    val curve2: Double = 1.0
)

/**
 * アプリ全体の音量制御設定
 *
 * @param mode 現在の設定モード
 * @param twoPointSettings 2ポイントモードの設定
 * @param threePointSettings 3ポイントモードの設定
 */
data class VolumeControlSettings(
    val mode: SettingMode = SettingMode.TWO_POINT,
    val twoPointSettings: TwoPointSettings = TwoPointSettings(),
    val threePointSettings: ThreePointSettings = ThreePointSettings()
) {
    /**
     * 現在のモードに基づいて速度配列を取得
     */
    fun getSpeeds(): FloatArray {
        return when (mode) {
            SettingMode.TWO_POINT -> floatArrayOf(
                twoPointSettings.minSpeed.toFloat(),
                twoPointSettings.maxSpeed.toFloat()
            )
            SettingMode.THREE_POINT -> floatArrayOf(
                threePointSettings.speed1.toFloat(),
                threePointSettings.speed2.toFloat(),
                threePointSettings.speed3.toFloat()
            )
        }
    }

    /**
     * 現在のモードに基づいて音量配列を取得
     */
    fun getVolumes(): FloatArray {
        return when (mode) {
            SettingMode.TWO_POINT -> floatArrayOf(
                twoPointSettings.minVolume.toFloat(),
                twoPointSettings.maxVolume.toFloat()
            )
            SettingMode.THREE_POINT -> floatArrayOf(
                threePointSettings.volume1.toFloat(),
                threePointSettings.volume2.toFloat(),
                threePointSettings.volume3.toFloat()
            )
        }
    }

    /**
     * 現在のモードに基づいてカーブ配列を取得
     */
    fun getCurves(): DoubleArray {
        return when (mode) {
            SettingMode.TWO_POINT -> doubleArrayOf(
                twoPointSettings.curvePower
            )
            SettingMode.THREE_POINT -> doubleArrayOf(
                threePointSettings.curve1,
                threePointSettings.curve2
            )
        }
    }
}

/**
 * 旧バージョン互換用の定数（参考用に残す）
 * 新しいコードではVolumeControlSettingsを使用してください
 */
@Deprecated("Use VolumeControlSettings instead")
object VolumeControlConstants {
    const val SPEED_POINT_1 = 10f
    const val SPEED_POINT_2 = 65f
    const val SPEED_POINT_3 = 80f
    const val VOLUME_POINT_1 = 45f
    const val VOLUME_POINT_2 = 85f
    const val VOLUME_POINT_3 = 100f
    const val CURVE_SEGMENT_1 = 0.7
    const val CURVE_SEGMENT_2 = 1.3
}
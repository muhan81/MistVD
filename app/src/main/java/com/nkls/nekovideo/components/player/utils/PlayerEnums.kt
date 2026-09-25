package com.nkls.nekovideo.components.player

/**
 * Modos de repetição do player
 *
 * 第 5 轮由三态扩为**四态**。
 *
 * ⚠️ 第 6 轮修复后，[SHUFFLE] 在播放器侧**既不是** `shuffleModeEnabled`、**也不是**任何 repeat 常量。
 * 它映射为 `shuffleModeEnabled = false` + `REPEAT_MODE_OFF`，随机顺序**完全由**
 * `PlaylistManager` 的「洗牌袋 + 历史栈」抽签决定（见 `VideoPlayerOverlay.applyRepeatMode`）。
 * 原因：ExoPlayer 自带的 shuffle 会洗出第二套互不相干的顺序，与洗牌袋打架。
 * 之所以仍放进同一个枚举：界面上只有一个按钮在循环切换，
 * 用同一个枚举才能保证"按钮显示的状态"与"播放器实际状态"严格一一对应。
 */
enum class RepeatMode {
    NONE,
    REPEAT_ALL,
    REPEAT_ONE,
    SHUFFLE
}

/**
 * Modos de rotação da tela
 */
enum class RotationMode {
    AUTO,      // Adaptar ao vídeo
    PORTRAIT,  // Sempre vertical
    LANDSCAPE  // Sempre horizontal
}

/**
 * Velocidades de reprodução suportadas
 *
 * 第 5 轮由 8 档（0.25~2.0）扩为 **14 档**（上限提到 8x）。
 * 档位在 0.25~2.0 之间是细密的（0.25 步进），再往上是粗跳 —— 因为高速区间
 * 人耳已听不出 3.5 与 4 的差别，密档只会让面板变长。
 * ⚠️ 新增/删除档位必须同步改 `CustomVideoControls.formatSpeedLabel()`
 * （那里是穷尽 `when`，不改会直接编译不过）。
 */
enum class PlaybackSpeed(val value: Float) {
    SPEED_0_25(0.25f),
    SPEED_0_50(0.50f),
    SPEED_0_75(0.75f),
    SPEED_1_00(1.00f),
    SPEED_1_25(1.25f),
    SPEED_1_50(1.50f),
    SPEED_1_75(1.75f),
    SPEED_2_00(2.00f),
    SPEED_2_50(2.50f),
    SPEED_3_00(3.00f),
    SPEED_4_00(4.00f),
    SPEED_5_00(5.00f),
    SPEED_6_00(6.00f),
    SPEED_8_00(8.00f);

    companion object {
        /**
         * 超过这个速度就自动静音（第 5 轮）。
         * 变速是保音调的，但 3x 往上人声已经是噪声，主流播放器都在高速时静音。
         */
        const val AUTO_MUTE_ABOVE = 2.00f
    }
}

/**
 * Constantes para controles de Picture-in-Picture
 */
object PiPConstants {
    const val REQUEST_CODE_PLAY_PAUSE = 1
    const val REQUEST_CODE_NEXT = 2
    const val REQUEST_CODE_PREVIOUS = 3
}

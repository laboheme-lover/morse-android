package jp.daizo.morse.ui

import android.content.Context

/** 送信・受信の設定(アプリを閉じても残す) */
data class MorseSettings(
    val soundUnitMs: Int = 100,     // 音の短点の長さ(100ms ≒ 12 文字/分の欧文の速さ)
    val lightUnitMs: Int = 300,     // 光の短点の長さ(テスト動画と同じ 300ms)
    val toneHz: Int = 700,          // ブザー音の高さ
    val engine: Engine = Engine.RULE,
) {
    enum class Engine(val label: String) { RULE("ルール方式"), CNN("CNN(PCのモデル)") }

    companion object {
        private const val NAME = "morse_settings"

        fun load(context: Context): MorseSettings {
            val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            return MorseSettings(
                soundUnitMs = p.getInt("soundUnitMs", 100),
                lightUnitMs = p.getInt("lightUnitMs", 300),
                toneHz = p.getInt("toneHz", 700),
                engine = runCatching { Engine.valueOf(p.getString("engine", "RULE")!!) }.getOrDefault(Engine.RULE),
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt("soundUnitMs", soundUnitMs).putInt("lightUnitMs", lightUnitMs).putInt("toneHz", toneHz)
            .putString("engine", engine.name).apply()
    }
}

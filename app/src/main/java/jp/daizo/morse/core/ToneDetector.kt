package jp.daizo.morse.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * マイクの音(モノラル PCM)から、モールスのブザー音の大きさを 5ms ごとに求める。
 *
 * (1) ブザー音の高さ(周波数)を自動で見つける: 300〜1500Hz を 20Hz おきに Goertzel 法で調べ、
 *     強い音が続いている周波数を選ぶ(送信側の高さを知らなくても受信できる)
 * (2) 見つけた周波数の強さだけを 5ms ごとに測る(ほかの高さの音・雑音の影響を受けにくい)
 * 測った強さは OnOffDecoder に渡して、点灯(音あり)/消灯(音なし)を判定する。
 */
class ToneDetector(
    private val sampleRate: Int = 16000,
    private val frameMs: Int = 5,
    private val minHz: Int = 300,
    private val maxHz: Int = 1500,
    private val stepHz: Int = 20,
) {
    val frameSize: Int = sampleRate * frameMs / 1000
    /** いま追いかけている周波数(見つかる前は null) */
    var frequencyHz: Float? = null
        private set

    private val searchWindow = sampleRate / 10          // 周波数を探すのに使う長さ(0.1秒)
    private val searchBuffer = FloatArray(searchWindow)
    private var searchFill = 0
    private val scores = FloatArray((maxHz - minHz) / stepHz + 1)
    private val pending = FloatArray(frameSize)
    private var pendingFill = 0
    private var frames = 0L

    /** 周波数を固定する(設定で手動にしたとき)。null で自動に戻す */
    fun lockFrequency(hz: Float?) {
        frequencyHz = hz
        locked = hz != null
    }

    private var locked = false

    /**
     * 音のかたまりを受け取り、5ms ごとの強さを onFrame(時刻[ms], 強さ) で返す。
     */
    fun process(samples: FloatArray, count: Int = samples.size, onFrame: (Long, Float) -> Unit) {
        for (i in 0 until count) {
            val s = samples[i]
            pending[pendingFill++] = s
            if (!locked) {
                searchBuffer[searchFill++] = s
                if (searchFill == searchWindow) {
                    updateFrequency()
                    searchFill = 0
                }
            }
            if (pendingFill == frameSize) {
                val hz = frequencyHz
                val level = if (hz == null) 0f else sqrt(goertzel(pending, frameSize, hz))
                onFrame(frames * frameMs, level)
                frames++
                pendingFill = 0
            }
        }
    }

    /** 0.1 秒ごとに、各周波数の強さを少しずつ積み上げ(古いものは減らし)、一番強い周波数を選ぶ */
    private fun updateFrequency() {
        var best = -1
        for (k in scores.indices) {
            val hz = (minHz + k * stepHz).toFloat()
            scores[k] = scores[k] * 0.8f + goertzel(searchBuffer, searchWindow, hz)
            if (best < 0 || scores[k] > scores[best]) best = k
        }
        // 周りの周波数より十分強いときだけ採用する(雑音だけのときに周波数が飛び回らないように)
        val mean = scores.average().toFloat()
        if (best >= 0 && scores[best] > mean * 8f) frequencyHz = (minHz + best * stepHz).toFloat()
    }

    /** Goertzel 法: 1 つの周波数の強さ(パワー)だけを効率よく計算する */
    private fun goertzel(x: FloatArray, n: Int, hz: Float): Float {
        val w = 2.0 * PI * hz / sampleRate
        val c = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val s0 = x[i] + c * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - c * s1 * s2
        return (power / n).toFloat()
    }
}

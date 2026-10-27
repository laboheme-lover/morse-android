package jp.daizo.morse.core

import kotlin.math.max
import kotlin.math.min

/**
 * 「信号の強さ」の並び(音なら 5ms ごとのブザー音の大きさ、光ならコマごとの光源の明るさ)から、
 * ルール方式でモールス信号を読む。リアルタイムでも、ファイルの読み込みでも同じものを使う。
 *
 * PC のアプリ(light-morse-ai/light_sequence.py)と同じ考え方で、読むたびに最初から判定し直す:
 *   (1) これまでの強さ全体から、背景(下位 10%)と信号(上位 0.5%)の強さを決め、0〜1 にそろえる。
 *       待ち時間が長くて信号が少しだけでも、上位 0.5% なら信号の強さになる
 *   (2) ヒステリシス(上がるとき 0.6・下がるとき 0.4)で点灯/消灯を判定し、minRunMs 未満の点灯・消灯は雑音として消す
 *   (3) 短点の長さ(1 単位)を、点灯・消灯の長さの短いほうから 15% の値で測る
 *       (符号の中の間はいつも 1 単位なので、長点しかない「ヨ」などでも測れる)
 *   (4) 点灯が 2 単位以上なら長点。消灯が 2 単位以上で文字の区切り、5 単位以上で語の区切り
 * 信号が届く前の雑音を一時的に読み違えても、信号が届けば背景と信号の強さが決まり、読み直しで正しくなる。
 */
class OnOffDecoder(
    private val minRunMs: Long = 15,
    /** 信号と背景の差がこれより小さいときは「信号なし」(強さの単位のまま)。光と音で単位が違うので外から変えられる */
    var minContrast: Float = 1e-4f,
    /** 短点の長さを決められないときの仮の値 */
    private val defaultUnitMs: Float = 100f,
    /** 消灯がこの長さ(単位)を超えたら、送信が終わったとみなす */
    private val endOfMessageUnits: Float = 15f,
) {
    data class Run(val on: Boolean, val startMs: Long, var durationMs: Long)

    data class State(
        val text: String,            // 読み取った文章(濁音は 1 文字にまとめたもの)
        val rawText: String,         // 区切ったままの文字(濁点は別の文字)
        val currentCode: String,     // 読み途中の文字の符号
        val unitMs: Float,           // 測った短点の長さ
        val signalLevel: Float,      // いまの強さ(0〜1)
        val receiving: Boolean,      // 信号を受けている途中か
        val finished: Boolean,       // 送信の終わり(長い消灯)を見つけたか
        val unknownCodes: Int,       // 符号表に無い符号の数
    )

    private var times = LongArray(4096)
    private var values = FloatArray(4096)
    private var count = 0
    private var cachedRuns: List<Run> = emptyList()
    private var cachedCount = -1
    private var lastLevel = 0f

    fun reset() {
        count = 0; cachedRuns = emptyList(); cachedCount = -1; lastLevel = 0f
    }

    /** 強さの値を 1 つ受け取る(時刻はミリ秒、増えていく順) */
    fun feed(timeMs: Long, value: Float) {
        if (count == times.size) {
            times = times.copyOf(count * 2); values = values.copyOf(count * 2)
        }
        times[count] = timeMs; values[count] = value; count++
    }

    /** これまでの強さから、点灯・消灯の区間を求める(同じ件数なら前回の結果を使う) */
    fun runs(): List<Run> {
        if (count == cachedCount) return cachedRuns
        cachedCount = count
        if (count < 2) { cachedRuns = emptyList(); return cachedRuns }
        // (1) 背景(下位 10%)と信号(上位 0.5%)の強さ。件数が多いときは間引いて求める(速さのため)
        val step = max(1, count / 50000)
        val sample = FloatArray((count + step - 1) / step) { values[it * step] }
        sample.sort()
        val lo = sample[sample.size / 10]
        val hi = sample[min(sample.lastIndex, (sample.size * 0.995f).toInt())]
        val span = hi - lo
        val out = mutableListOf<Run>()
        var on = false
        for (i in 0 until count) {
            val x = if (span > minContrast) (values[i] - lo) / span else 0f
            if (i == count - 1) lastLevel = x.coerceIn(0f, 1f)
            val next = if (on) x > 0.4f else x > 0.6f
            val dt = if (i + 1 < count) times[i + 1] - times[i] else (if (i > 0) times[i] - times[i - 1] else 0)
            if (out.isEmpty() || next != on) {
                // (2) 直前の区間が短すぎれば雑音とみなし、ひとつ前の区間につなげる
                val last = out.lastOrNull()
                if (last != null && last.durationMs < minRunMs && out.size >= 2) {
                    out.removeAt(out.lastIndex)
                    out.last().durationMs += last.durationMs
                    on = out.last().on
                    if (next == on) { out.last().durationMs += dt; continue }
                }
                out += Run(next, times[i], dt)
                on = next
            } else {
                out.last().durationMs += dt
            }
        }
        cachedRuns = out
        return out
    }

    /** 最初と最後の消灯(信号の外)を除いた区間 */
    private fun innerRuns(all: List<Run>): List<Run> {
        var a = 0
        var b = all.size
        while (a < b && !all[a].on) a++
        while (b > a && !all[b - 1].on) b--
        return all.subList(a, b)
    }

    /** (3) 短点の長さ(ミリ秒)。点灯・消灯(信号の外の消灯は除く)の長さの短いほうから 15% の値 */
    fun estimateUnitMs(): Float {
        val inner = innerRuns(runs())
        if (inner.size < 3) return defaultUnitMs
        val lengths = inner.map { it.durationMs.toFloat() }.sorted()
        return max(lengths[min(lengths.lastIndex, (lengths.size * 0.15f).toInt())], minRunMs.toFloat())
    }

    /** いままでに受け取った信号を読み直して、読み取り結果を返す */
    fun state(): State {
        val all = runs()
        val unit = estimateUnitMs()
        val raw = StringBuilder()
        val code = StringBuilder()
        var unknown = 0
        fun flush() {
            if (code.isEmpty()) return
            val c = MorseTable.charFor(code.toString())
            if (c != null) raw.append(c) else { raw.append('?'); unknown++ }
            code.clear()
        }
        val inner = innerRuns(all)
        for (r in inner) {
            val units = r.durationMs / unit
            if (r.on) {
                code.append(if (units >= 2f) '-' else '.')
            } else if (units >= 2f) {
                flush()
                if (units >= 5f) raw.append(' ')
            }
        }
        // 最後の消灯が十分長ければ、読み途中の文字も確定する
        val tail = all.lastOrNull()
        val tailUnits = if (tail != null && !tail.on) tail.durationMs / unit else 0f
        val pending = if (tailUnits >= 2f) { flush(); "" } else code.toString()
        val text = MorseTable.composeDakuten(raw.toString().trim())
        return State(
            text = text, rawText = raw.toString().trim(), currentCode = pending, unitMs = unit,
            signalLevel = lastLevel, receiving = inner.isNotEmpty() && tailUnits < endOfMessageUnits,
            finished = inner.isNotEmpty() && tailUnits >= endOfMessageUnits, unknownCodes = unknown,
        )
    }

    /** 文字ごとの区間(開始・終了の時刻)。CNN で 1 文字ずつ判定するときに使う */
    fun charSpans(): List<LongRange> {
        val unit = estimateUnitMs()
        val spans = mutableListOf<LongRange>()
        var start = -1L
        var end = -1L
        for (r in innerRuns(runs())) {
            if (r.on) {
                if (start < 0) start = r.startMs
                end = r.startMs + r.durationMs
            } else if (r.durationMs / unit >= 2f && start >= 0) {
                spans += start..end; start = -1
            }
        }
        if (start >= 0) spans += start..end
        return spans
    }
}

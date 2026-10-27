package jp.daizo.morse.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * カメラの映像(明るさの画像)から、モールス信号で点滅している光源を自動で絞り込み、その明るさを返す。
 * PC のアプリ(light-morse-ai/light_roi.py)と同じ考え方を、スマホでリアルタイムに動くよう軽くしたもの。
 *
 * (1) 画面を GRID_W × GRID_H のマスに分け、マスごとの「一番明るい点」をそのマスの明るさとする
 *     (ランプが小さく写っていても見落とさない)
 * (2) マスごとに明るさの揺れ(標準偏差)を追いかける。止まった明かりは揺れが小さいので候補にならない
 * (3) 揺れの大きいマス(候補)の明るさの並びに「点灯/消灯の 2 つに分かれる度合い × 揺れ」の点数を付ける。
 *     同じ長さの点灯・消灯を規則正しく繰り返し続ける光(警告灯など)は点数を下げる
 * (4) 点数の一番高い候補を選ぶ。利用者が画面で別の候補を選んだら、それに固定する
 */
class LightRoiTracker(
    private val historyFrames: Int = 300,  // 候補の明るさを覚えておくコマ数(30コマ/秒で約 10 秒)
    private val candidates: Int = 5,
) {
    companion object {
        const val GRID_W = 64
        const val GRID_H = 48
    }

    data class Candidate(val gx: Int, val gy: Int, val score: Float) {
        /** 画面の中の位置(0〜1)。枠の表示に使う */
        val centerX: Float get() = (gx + 0.5f) / GRID_W
        val centerY: Float get() = (gy + 0.5f) / GRID_H
    }

    private val mean = FloatArray(GRID_W * GRID_H)
    private val meanSq = FloatArray(GRID_W * GRID_H)
    private val cell = FloatArray(GRID_W * GRID_H)
    private var frames = 0
    private val traces = HashMap<Int, ArrayDeque<Float>>()
    var ranked: List<Candidate> = emptyList()
        private set
    /** 利用者が選んだ候補(マスの番号)。null なら自動 */
    var lockedCell: Int? = null

    /** いま読み取りに使っている候補 */
    val selected: Candidate?
        get() = lockedCell?.let { c -> ranked.firstOrNull { it.gy * GRID_W + it.gx == c } ?: Candidate(c % GRID_W, c / GRID_W, 0f) }
            ?: ranked.firstOrNull()

    fun reset() {
        mean.fill(0f); meanSq.fill(0f); frames = 0; traces.clear(); ranked = emptyList(); lockedCell = null
    }

    /**
     * 1 コマ分の明るさの画像(Y 平面、0〜255)を受け取り、選んだ光源の明るさを返す(まだ決まっていなければ null)。
     * rotation などは気にしなくてよい(マスの位置は画像の座標のまま)。
     */
    fun process(y: ByteArray, width: Int, height: Int, rowStride: Int = width): Float? {
        // (1) マスごとの一番明るい点
        cell.fill(0f)
        for (yy in 0 until height step 2) {          // 速さのため 1 行おき・1 列おきに見る
            val gy = yy * GRID_H / height
            val row = yy * rowStride
            for (xx in 0 until width step 2) {
                val v = (y[row + xx].toInt() and 0xFF).toFloat()
                val k = gy * GRID_W + xx * GRID_W / width
                if (v > cell[k]) cell[k] = v
            }
        }
        // (2) 揺れを追いかける(最初の 1 秒は速く慣らし、その後は約 2 秒分の重みで更新)
        frames++
        val a = max(1f / frames, 1f / 60f)
        for (k in cell.indices) {
            mean[k] += a * (cell[k] - mean[k])
            meanSq[k] += a * (cell[k] * cell[k] - meanSq[k])
        }
        for ((k, q) in traces) {
            q.addLast(cell[k]); if (q.size > historyFrames) q.removeFirst()
        }
        // 0.5 秒ごとに候補を選び直す
        if (frames % 15 == 0) rerank()
        val sel = selected ?: return null
        return cell[sel.gy * GRID_W + sel.gx]
    }

    private fun std(k: Int): Float = sqrt(max(0f, meanSq[k] - mean[k] * mean[k]))

    private fun rerank() {
        // 揺れの大きいマスを、周り(±2 マス)で一番のものだけ選んで候補にする
        val order = cell.indices.sortedByDescending { std(it) }
        val chosen = mutableListOf<Int>()
        for (k in order) {
            if (chosen.size >= candidates || std(k) < 2f) break
            val gx = k % GRID_W
            val gy = k / GRID_W
            if (chosen.none { kotlin.math.abs(it % GRID_W - gx) <= 2 && kotlin.math.abs(it / GRID_W - gy) <= 2 }) chosen += k
        }
        lockedCell?.let { if (it !in chosen) chosen += it }
        // 候補でなくなったマスの記録は捨て、新しい候補は記録を始める
        traces.keys.retainAll(chosen.toSet())
        for (k in chosen) traces.getOrPut(k) { ArrayDeque() }
        ranked = chosen.map { k ->
            val t = traces.getValue(k).toFloatArray()
            Candidate(k % GRID_W, k / GRID_W, signalScore(t))
        }.sortedByDescending { it.score }
    }

    /** 点滅の信号らしさの点数(2 値に分かれる度合い × 揺れ。規則正しく点滅し続ける光は下げる) */
    fun signalScore(trace: FloatArray): Float {
        if (trace.size < 10) return 0f
        val m = trace.average().toFloat()
        val sd = sqrt(trace.fold(0f) { s, v -> s + (v - m) * (v - m) } / trace.size)
        if (sd < 1e-3f) return 0f
        var score = twoLevel(trace) * sd
        if (periodicBlinker(trace)) score *= 0.2f
        return score
    }

    /** 大津の方法で 2 つに分けたとき、クラス間の分散が全体のどれだけか(0〜1) */
    fun twoLevel(trace: FloatArray): Float {
        val s = trace.sorted()
        val n = s.size
        val total = s.sum()
        val m = total / n
        val variance = s.fold(0f) { acc, v -> acc + (v - m) * (v - m) } / n
        if (variance < 1e-6f) return 0f
        var best = 0f
        var cum = 0f
        for (k in 1 until n) {
            cum += s[k - 1]
            val w0 = k.toFloat() / n
            val m0 = cum / k
            val m1 = (total - cum) / (n - k)
            best = max(best, w0 * (1 - w0) * (m0 - m1) * (m0 - m1))
        }
        return min(1f, best / variance)
    }

    /** 同じ長さの点灯・消灯を 6 回以上、規則正しく繰り返しているか */
    fun periodicBlinker(trace: FloatArray, minCycles: Int = 6, maxCv: Float = 0.2f): Boolean {
        val lo = trace.min()
        val hi = trace.max()
        val mid = (lo + hi) / 2
        val runs = mutableListOf<Pair<Boolean, Int>>()
        var cur = trace[0] > mid
        var len = 0
        for (v in trace) {
            val b = v > mid
            if (b == cur) len++ else { runs += cur to len; cur = b; len = 1 }
        }
        runs += cur to len
        val inner = runs.drop(1).dropLast(1)
        val on = inner.filter { it.first }.map { it.second.toFloat() }
        val off = inner.filter { !it.first }.map { it.second.toFloat() }
        if (on.size < minCycles || off.size < minCycles - 1) return false
        fun cv(a: List<Float>): Float {
            val mm = a.average().toFloat()
            return sqrt(a.fold(0f) { s, v -> s + (v - mm) * (v - mm) } / a.size) / mm
        }
        return cv(on) < maxCv && cv(off) < maxCv
    }
}

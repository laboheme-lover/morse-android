package jp.daizo.morse.core

/**
 * 送信する文章を「点灯(音を出す)/消灯(音を止める)の区間の並び」にする。音でも光でも同じ並びを使う。
 *
 * 長さの決まり(単位 = 短点の長さ):
 *   短点 1、長点 3、符号の中の間 1、文字の間 3(濁音の基本の仮名と濁点の間も 3)、語の間(空白)7
 */
data class Segment(
    val on: Boolean,
    val units: Int,
    /** この区間が属する文字の番号(送信する文章の何文字目か。語の間は直前の文字) */
    val charIndex: Int,
)

data class MorseSchedule(val text: String, val segments: List<Segment>) {
    val totalUnits: Int = segments.sumOf { it.units }

    fun totalMillis(unitMs: Int): Long = totalUnits.toLong() * unitMs

    companion object {
        fun build(text: String): MorseSchedule {
            val segs = mutableListOf<Segment>()
            var first = true
            for ((index, c) in text.withIndex()) {
                if (c == ' ') {
                    // 語の間は 7 単位。直前の文字の間 3 単位をすでに入れていれば、残り 4 単位を足す
                    if (segs.isNotEmpty()) segs += Segment(false, 4, index)
                    continue
                }
                val units = MorseTable.unitsFor(c)
                if (units.isEmpty()) continue
                for (code in units) {
                    if (!first) segs += Segment(false, 3, index)  // 文字の間(濁点の前も 3)
                    first = false
                    for ((j, e) in code.withIndex()) {
                        if (j > 0) segs += Segment(false, 1, index)
                        segs += Segment(true, if (e == '-') 3 else 1, index)
                    }
                }
            }
            return MorseSchedule(text, segs)
        }
    }
}

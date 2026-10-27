package jp.daizo.morse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class MorseTableTest {
    @Test fun has65UniqueCodes() {
        assertEquals(65, MorseTable.CODES.size)
        assertEquals(65, MorseTable.CODES.values.toSet().size)
    }

    @Test fun normalizeConvertsAndReportsSkipped() {
        val (text, skipped) = MorseTable.normalize("こんにちは、ガッコウ 123「本」")
        assertEquals("コンニチハ、ガツコウ 123。", text.replace("「", ""))
        assertTrue('本' in skipped)
    }

    @Test fun dakutenIsTwoCodesAndComposesBack() {
        assertEquals(listOf(".-..", ".."), MorseTable.unitsFor('ガ'))
        assertEquals("ガパ", MorseTable.composeDakuten("カ゛ハ゜"))
    }
}

class MorseScheduleTest {
    @Test fun unitLengthsFollowRules() {
        val s = MorseSchedule.build("イ ヘ")
        // イ: 短1 間1 長3、語の間 7、ヘ: 短1
        assertEquals(listOf(true to 1, false to 1, true to 3, false to 7, true to 1), merge(s))
        assertEquals(13, s.totalUnits)
    }

    @Test fun dakutenGapIsThreeUnits() {
        val s = MorseSchedule.build("ガ")
        assertEquals(listOf(true to 1, false to 1, true to 3, false to 1, true to 1, false to 1, true to 1, false to 3, true to 1, false to 1, true to 1), merge(s))
    }

    private fun merge(s: MorseSchedule): List<Pair<Boolean, Int>> {
        val out = mutableListOf<Pair<Boolean, Int>>()
        for (seg in s.segments) {
            if (out.isNotEmpty() && out.last().first == seg.on) out[out.lastIndex] = seg.on to out.last().second + seg.units
            else out += seg.on to seg.units
        }
        return out
    }
}

/** 送信の並びを、指定した速さ・揺らぎ・雑音で「強さの並び」にして復号器に入れる */
fun decode(text: String, unitMs: Int, frameMs: Int = 5, jitter: Float = 0f, noise: Float = 0f, seed: Int = 1): OnOffDecoder.State {
    val rnd = Random(seed)
    val dec = OnOffDecoder()
    var t = 0L
    fun emit(on: Boolean, ms: Int) {
        var left = ms
        while (left > 0) {
            dec.feed(t, (if (on) 1f else 0.05f) + noise * (rnd.nextFloat() - 0.5f))
            t += frameMs; left -= frameMs
        }
    }
    emit(false, 500)
    for (seg in MorseSchedule.build(text).segments) {
        val ms = (seg.units * unitMs * (1 + jitter * (rnd.nextFloat() * 2 - 1))).toInt()
        emit(seg.on, ms)
    }
    emit(false, unitMs * 20)
    return dec.state()
}

class OnOffDecoderTest {
    /** 光の明るさ(0〜255)で、揺れ程度の小さな変化は信号とみなさず、ランプの点滅は読む */
    @Test fun lightContrastGate() {
        val rnd = Random(3)
        val shake = OnOffDecoder(minContrast = 30f)
        for (i in 0 until 300) shake.feed(i * 33L, 120f + 20f * rnd.nextFloat())  // 縁が揺れて ±10 変わる程度
        val s = shake.state()
        assertFalse(s.receiving)
        assertEquals("", s.text)

        val lamp = OnOffDecoder(minContrast = 30f)
        var t = 0L
        fun emit(on: Boolean, units: Int) { repeat(units * 9) { lamp.feed(t, if (on) 250f else 110f); t += 33 } }
        emit(false, 5)
        for (seg in MorseSchedule.build("ヨ").segments) emit(seg.on, seg.units)
        emit(false, 20)
        assertEquals("ヨ", lamp.state().text)
    }

    @Test fun decodesSentenceAtDifferentSpeeds() {
        val text = "コンニチハ、ガツコウ 123。"
        for (unit in listOf(60, 100, 300)) {
            val st = decode(text, unit, frameMs = if (unit >= 300) 33 else 5)
            assertEquals("unit=$unit", text, st.text)
            assertTrue(abs(st.unitMs - unit) < unit * 0.25f)
            assertTrue(st.finished)
        }
    }

    @Test fun dashOnlyCharsUseIntraGapForUnit() {
        assertEquals("ヨレコ", decode("ヨレコ", 100).text)
    }

    @Test fun toleratesTimingJitterAndNoise() {
        val st = decode("セイフハツカツテ", 120, jitter = 0.1f, noise = 0.3f)
        assertEquals("セイフハツカツテ", st.text)
    }

    @Test fun reportsProgressWhileReceiving() {
        val dec = OnOffDecoder()
        var t = 0L
        repeat(100) { dec.feed(t, 0.05f); t += 5 }
        repeat(20) { dec.feed(t, 1f); t += 5 }  // 点灯の途中
        val st = dec.state()
        assertTrue(st.receiving)
        assertFalse(st.finished)
    }
}

class ToneDetectorTest {
    @Test fun findsToneFrequencyAndLevel() {
        val sr = 16000
        val det = ToneDetector(sr)
        val n = sr  // 1 秒
        val pcm = FloatArray(n) { i ->
            val on = (i / (sr / 10)) % 2 == 0  // 0.1 秒おきに鳴る/止まる
            (if (on) 0.5f * sin(2 * PI * 760 * i / sr).toFloat() else 0f) + 0.01f * (Random.nextFloat() - 0.5f)
        }
        val levels = mutableListOf<Float>()
        det.process(pcm) { _, v -> levels += v }
        val hz = det.frequencyHz
        assertNotNull(hz)
        assertTrue("hz=$hz", abs(hz!! - 760f) <= 20f)
        // 周波数が見つかったあと(後半 0.5 秒)の、音ありの区間と音なしの区間の強さを比べる
        val late = levels.subList(levels.size / 2, levels.size)
        val onLevel = late.maxOrNull() ?: 0f
        val offLevel = late.minOrNull() ?: 0f
        assertTrue("on=$onLevel off=$offLevel", onLevel > offLevel * 5)
    }
}

class LightRoiTrackerTest {
    private val w = 320
    private val h = 240

    /** 暗い背景に、モールスで点滅するランプ・止まった明かり・規則正しく点滅する警告灯を置いた映像 */
    private fun frame(i: Int, lampOn: Boolean): ByteArray {
        val img = ByteArray(w * h) { 20 }
        fun dot(cx: Int, cy: Int, v: Int) {
            for (y in cy - 4..cy + 4) for (x in cx - 4..cx + 4) img[y * w + x] = v.toByte()
        }
        dot(50, 40, 230)                                  // 止まった明かり
        if ((i % 12) < 6) dot(270, 60, 250)                // 警告灯(0.4 秒周期)
        if (lampOn) dot(200, 180, 250)                     // モールスのランプ
        return img
    }

    @Test fun choosesMorseLampOverStaticAndPeriodicLights() {
        val tracker = LightRoiTracker()
        val schedule = MorseSchedule.build("キヨ イロハ")
        val onFrames = mutableListOf<Boolean>()
        repeat(15) { onFrames += false }
        for (seg in schedule.segments) repeat(seg.units * 9) { onFrames += seg.on }  // 短点 9 コマ(300ms)
        for ((i, on) in onFrames.withIndex()) tracker.process(frame(i, on), w, h)
        val sel = tracker.selected
        assertNotNull(sel)
        assertTrue("x=${sel!!.centerX}", abs(sel.centerX - 200f / w) < 0.05f)
        assertTrue("y=${sel.centerY}", abs(sel.centerY - 180f / h) < 0.05f)
    }

    @Test fun periodicBlinkerIsRecognized() {
        val t = LightRoiTracker()
        val blink = FloatArray(150) { if ((it % 12) < 6) 250f else 20f }
        assertTrue(t.periodicBlinker(blink))
        val morse = mutableListOf<Float>()
        for (seg in MorseSchedule.build("キヨ イロハ").segments) repeat(seg.units * 9) { morse += if (seg.on) 250f else 20f }
        assertFalse(t.periodicBlinker(morse.toFloatArray()))
    }
}

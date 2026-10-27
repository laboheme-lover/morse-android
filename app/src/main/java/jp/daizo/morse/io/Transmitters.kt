package jp.daizo.morse.io

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import jp.daizo.morse.core.MorseSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** 送信の進み具合(送信画面の表示用) */
data class SendProgress(
    val charIndex: Int,        // いま送っている文字の番号(文章の何文字目か)
    val elapsedMs: Long,
    val totalMs: Long,
    val lightOn: Boolean,      // いま点灯(音を出している)か
) {
    val fraction: Float get() = if (totalMs <= 0) 0f else (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
}

/**
 * 送信の並び(MorseSchedule)に合わせて、音(ブザー音)・光(ライト)を出す。
 * 時刻は「送信を始めた時刻からの経過」で決め、区間ごとに目標の時刻まで待つ(待ち時間のずれがたまらない)。
 */
class MorseTransmitter(private val context: Context) {

    /** ライトのあるカメラ(背面)の ID。無ければ null */
    fun torchCameraId(): String? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }

    /**
     * 送信する。useSound / useLight で音・光(両方も可)を選ぶ。onProgress は 20 回/秒ほど呼ばれる。
     * 取り消し(コルーチンのキャンセル)で止まり、ライトは必ず消す。
     */
    suspend fun send(
        schedule: MorseSchedule,
        unitMs: Int,
        toneHz: Int,
        useSound: Boolean,
        useLight: Boolean,
        onProgress: (SendProgress) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val torchId = if (useLight) torchCameraId() else null
        val tone = if (useSound) ToneOutput(toneHz) else null
        val totalMs = schedule.totalMillis(unitMs)
        val start = System.nanoTime()
        fun nowMs() = (System.nanoTime() - start) / 1_000_000
        try {
            tone?.start()
            var t = 0L
            for (seg in schedule.segments) {
                ensureActive()
                torchId?.let { cm.setTorchMode(it, seg.on) }
                tone?.setOn(seg.on)
                t += seg.units.toLong() * unitMs
                // 区間の終わりまで、少しずつ待ちながら進み具合を知らせる
                while (true) {
                    val left = t - nowMs()
                    if (left <= 0) break
                    onProgress(SendProgress(seg.charIndex, nowMs(), totalMs, seg.on))
                    delay(min(left, 50))
                }
            }
            onProgress(SendProgress(schedule.text.length, totalMs, totalMs, false))
        } finally {
            torchId?.let { runCatching { cm.setTorchMode(it, false) } }
            tone?.stop()
        }
    }
}

/**
 * ブザー音を出す。AudioTrack に正弦波を流し続け、点灯/消灯に合わせて音量を 5ms かけて上げ下げする
 * (急に切り替えると「プチッ」という雑音になるため)。
 */
private class ToneOutput(private val hz: Int, private val sampleRate: Int = 44100) {
    @Volatile private var on = false
    @Volatile private var running = false
    private var thread: Thread? = null

    fun setOn(value: Boolean) { on = value }

    fun start() {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        running = true
        thread = Thread {
            val chunk = ShortArray(256)
            var phase = 0.0
            var gain = 0.0
            val step = 1.0 / (sampleRate * 0.005)  // 5ms で 0→1
            val dphi = 2 * PI * hz / sampleRate
            track.play()
            while (running) {
                for (i in chunk.indices) {
                    gain = if (on) minOf(1.0, gain + step) else maxOf(0.0, gain - step)
                    chunk[i] = (sin(phase) * gain * 0.8 * Short.MAX_VALUE).toInt().toShort()
                    phase += dphi
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                track.write(chunk, 0, chunk.size)
            }
            track.stop()
            track.release()
        }.also { it.start() }
    }

    fun stop() {
        on = false
        running = false
        thread?.join(500)
    }
}

/** コルーチンの外から送信を始める・止めるための入れ物(ViewModel で使う) */
class TransmitJob(private val scope: CoroutineScope) {
    private var job: kotlinx.coroutines.Job? = null
    val active: Boolean get() = job?.isActive == true
    fun start(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }
    fun stop() { job?.cancel() }
}

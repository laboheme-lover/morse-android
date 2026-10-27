package jp.daizo.morse.io

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import jp.daizo.morse.core.LightRoiTracker
import jp.daizo.morse.core.OnOffDecoder
import jp.daizo.morse.core.ToneDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * マイクから音を受け取り、ブザー音の強さを 5ms ごとに復号器に渡す。止めるまで続ける(コルーチンのキャンセルで止まる)。
 */
@SuppressLint("MissingPermission")  // 権限は画面側で確認してから呼ぶ
suspend fun receiveFromMicrophone(detector: ToneDetector, decoder: OnOffDecoder, sampleRate: Int = 16000) =
    withContext(Dispatchers.Default) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // 雑音除去や音量の自動調整がかからない入力(UNPROCESSED)を優先する。使えない機種では普通のマイクにする
        fun open(source: Int) = AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sampleRate / 5))
        val rec = open(MediaRecorder.AudioSource.UNPROCESSED).let {
            if (it.state == AudioRecord.STATE_INITIALIZED) it else { it.release(); open(MediaRecorder.AudioSource.MIC) }
        }
        val buf = ShortArray(sampleRate / 50)  // 20ms ずつ読む
        val f = FloatArray(buf.size)
        try {
            rec.startRecording()
            while (true) {
                ensureActive()
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                for (i in 0 until n) f[i] = buf[i] / 32768f
                synchronized(decoder) { detector.process(f, n) { t, v -> decoder.feed(t, v) } }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

/**
 * カメラの映像を 1 コマずつ受け取り(CameraX の ImageAnalysis)、光源を絞り込んで、その明るさを復号器に渡す。
 * 映像の Y 平面(明るさ)だけを使う。
 */
class LightAnalyzer(
    private val tracker: LightRoiTracker,
    private val decoder: OnOffDecoder,
    private val onFrame: (width: Int, height: Int) -> Unit = { _, _ -> },
) : ImageAnalysis.Analyzer {
    private var buffer = ByteArray(0)
    private var startNs = -1L

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val w = it.width
            val h = it.height
            val stride = plane.rowStride
            val bytes = plane.buffer
            if (buffer.size < bytes.remaining()) buffer = ByteArray(bytes.remaining())
            val n = bytes.remaining()
            bytes.get(buffer, 0, n)
            val ts = it.imageInfo.timestamp
            if (startNs < 0) startNs = ts
            val value = synchronized(tracker) { tracker.process(buffer, w, h, stride) }
            if (value != null) synchronized(decoder) { decoder.feed((ts - startNs) / 1_000_000, value) }
            onFrame(w, h)
        }
    }
}

/**
 * 録音ファイル(m4a・wav・mp3 など、Android で再生できる形式)を読み、ブザー音の強さを復号器に渡す。
 * どのサンプリング周波数でも 16kHz・モノラルにそろえてから処理する。onProgress は 0〜1。
 */
suspend fun decodeAudioFile(context: Context, uri: Uri, detector: ToneDetector, decoder: OnOffDecoder, onProgress: (Float) -> Unit) =
    withContext(Dispatchers.Default) {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var pos = 0.0            // 16kHz にそろえるための、元の音の位置
        val ratio = { inRate / 16000.0 }
        val out = FloatArray(4096)
        try {
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            if (durationUs > 0) onProgress((ex.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.let { inRate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                } else if (o >= 0) {
                    val bb = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = bb.remaining() / channels
                    var k = 0
                    // 間引きながら 16kHz・モノラルにする(各チャンネルの平均)
                    var idx = pos
                    while (idx < frames) {
                        val base = idx.toInt() * channels
                        var s = 0f
                        for (c in 0 until channels) s += bb.get(base + c) / 32768f
                        out[k++] = s / channels
                        if (k == out.size) { detector.process(out, k) { t, v -> decoder.feed(t, v) }; k = 0 }
                        idx += ratio()
                    }
                    pos = idx - frames
                    if (k > 0) detector.process(out, k) { t, v -> decoder.feed(t, v) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            onProgress(1f)
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
    }

/**
 * 録画ファイル(mp4 など)を 1 コマずつ読み、明るさの画像(Y 平面)を onFrame に渡す。
 * maxSeconds を指定すると、その秒数で読むのをやめる(光源を探す 1 回目の読み込み用)。onProgress は 0〜1。
 */
suspend fun decodeVideoFrames(
    context: Context,
    uri: Uri,
    maxSeconds: Double = Double.MAX_VALUE,
    onProgress: (Float) -> Unit,
    onFrame: (y: ByteArray, width: Int, height: Int, rowStride: Int, timeMs: Long) -> Unit,
) = withContext(Dispatchers.Default) {
    val ex = MediaExtractor()
    ex.setDataSource(context, uri, null)
    val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
    ex.selectTrack(track)
    val fmt = ex.getTrackFormat(track)
    val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
    val limitUs = minOf(if (durationUs > 0) durationUs else Long.MAX_VALUE, (maxSeconds * 1e6).toLong().coerceAtLeast(0))
    val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(fmt, null, null, 0)
    codec.start()
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    var y = ByteArray(0)
    try {
        while (!outputDone) {
            coroutineContext.ensureActive()
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0 || ex.sampleTime > limitUs) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val image = codec.getOutputImage(o)
                if (image != null && info.size > 0) {
                    val plane = image.planes[0]
                    val w = image.width
                    val h = image.height
                    val stride = plane.rowStride
                    val buf = plane.buffer
                    if (y.size < buf.remaining()) y = ByteArray(buf.remaining())
                    buf.get(y, 0, buf.remaining())
                    onFrame(y, w, h, stride, info.presentationTimeUs / 1000)
                    if (limitUs > 0) onProgress((info.presentationTimeUs.toFloat() / limitUs).coerceIn(0f, 1f))
                    image.close()
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        onProgress(1f)
    } finally {
        codec.stop(); codec.release(); ex.release()
    }
}

/** テキストファイルを読む(UTF-8。読めなければ Shift_JIS として読む) */
fun readTextFile(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    val utf8 = bytes.toString(Charsets.UTF_8)
    return if ('�' in utf8) String(bytes, charset("Shift_JIS")) else utf8.removePrefix("﻿")
}

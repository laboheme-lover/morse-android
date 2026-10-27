package jp.daizo.morse.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.daizo.morse.core.LightRoiTracker
import jp.daizo.morse.core.OnOffDecoder
import jp.daizo.morse.core.ToneDetector
import jp.daizo.morse.io.LightAnalyzer
import jp.daizo.morse.io.decodeAudioFile
import jp.daizo.morse.io.decodeVideoFrames
import jp.daizo.morse.io.receiveFromMicrophone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

enum class Source(val label: String) { MIC("音(マイク)"), CAMERA("光(カメラ)"), AUDIO_FILE("録音ファイル"), VIDEO_FILE("録画ファイル") }

class ReceiveViewModel(app: Application) : AndroidViewModel(app) {
    var source by mutableStateOf(Source.MIC)
    var running by mutableStateOf(false)
        private set
    var state by mutableStateOf<OnOffDecoder.State?>(null)
        private set
    var fileProgress by mutableStateOf<Float?>(null)
        private set
    var phase by mutableStateOf("")
        private set
    var toneHz by mutableStateOf<Float?>(null)
        private set
    var candidates by mutableStateOf<List<LightRoiTracker.Candidate>>(emptyList())
        private set
    var selected by mutableStateOf<LightRoiTracker.Candidate?>(null)
        private set
    /** カメラの画像の向き(0/90/180/270)。枠を画面の向きに合わせて描くのに使う */
    var rotation by mutableStateOf(90)

    val decoder = OnOffDecoder()
    val tracker = LightRoiTracker()
    private var detector = ToneDetector()
    private var job: Job? = null
    private var poller: Job? = null

    private fun beginPolling() {
        poller?.cancel()
        poller = viewModelScope.launch {
            while (isActive) {
                state = synchronized(decoder) { decoder.state() }
                toneHz = detector.frequencyHz
                synchronized(tracker) { candidates = tracker.ranked; selected = tracker.selected }
                delay(200)
            }
        }
    }

    private fun reset() {
        synchronized(decoder) {
            decoder.reset()
            // 光は明るさ(0〜255)の差が小さければ信号とみなさない。
            // 手持ちのカメラの揺れで、物の縁の明るさが少し変わるのを信号と読み違えないため(実機で確認)
            decoder.minContrast = if (source == Source.CAMERA || source == Source.VIDEO_FILE) LIGHT_MIN_CONTRAST else 1e-4f
        }
        synchronized(tracker) { tracker.reset() }
        detector = ToneDetector()
        state = null; fileProgress = null; candidates = emptyList(); selected = null
    }

    fun startMic() {
        reset(); running = true; phase = "マイクで受信中(ブザー音の高さを自動で探します)"
        beginPolling()
        job = viewModelScope.launch {
            try { receiveFromMicrophone(detector, decoder) } catch (_: CancellationException) {} finally { running = false }
        }
    }

    /** カメラは画面側で起動する(ここでは状態だけ用意する) */
    fun startCamera() {
        reset(); running = true; phase = "カメラで受信中(点滅している光源を自動で探します)"
        beginPolling()
    }

    fun startAudioFile(uri: Uri) {
        reset(); running = true; phase = "録音ファイルを読んでいます"
        beginPolling()
        job = viewModelScope.launch {
            try {
                decodeAudioFile(getApplication(), uri, detector, decoder) { fileProgress = it }
                phase = "読み終わりました"
            } catch (_: CancellationException) { phase = "止めました" } catch (e: Exception) { phase = "読めませんでした: ${e.message}" }
            finally { stopPolling(); running = false }
        }
    }

    /**
     * 録画ファイル: 1 回目(最初の 60 秒)で光源を探して固定し、2 回目で全体の明るさを読む(PC のアプリと同じ流れ)。
     * 進み具合は 1 回目を 0〜20%、2 回目を 20〜100% として表示する。
     */
    fun startVideoFile(uri: Uri) {
        reset(); running = true
        beginPolling()
        job = viewModelScope.launch {
            try {
                phase = "1/2 光源を探しています(最初の 60 秒)"
                decodeVideoFrames(getApplication(), uri, 60.0, { fileProgress = it * 0.2f }) { y, w, h, stride, _ ->
                    synchronized(tracker) { tracker.process(y, w, h, stride) }
                }
                val chosen = synchronized(tracker) { tracker.selected }
                if (chosen == null) { phase = "点滅している光源が見つかりませんでした"; return@launch }
                synchronized(tracker) { tracker.lockedCell = chosen.gy * LightRoiTracker.GRID_W + chosen.gx }
                phase = "2/2 選んだ光源の明るさを読んでいます"
                decodeVideoFrames(getApplication(), uri, Double.MAX_VALUE, { fileProgress = 0.2f + it * 0.8f }) { y, w, h, stride, t ->
                    val v = synchronized(tracker) { tracker.process(y, w, h, stride) }
                    if (v != null) synchronized(decoder) { decoder.feed(t, v) }
                }
                phase = "読み終わりました"
            } catch (_: CancellationException) { phase = "止めました" } catch (e: Exception) { phase = "読めませんでした: ${e.message}" }
            finally { stopPolling(); running = false }
        }
    }

    private fun stopPolling() {
        state = synchronized(decoder) { decoder.state() }
        synchronized(tracker) { candidates = tracker.ranked; selected = tracker.selected }
        poller?.cancel()
    }

    fun stop() {
        job?.cancel()
        if (source == Source.MIC || source == Source.CAMERA) { stopPolling(); running = false; phase = "止めました" }
    }

    /** 画面で選んだ候補に固定する(フォーカス) */
    fun lock(candidate: LightRoiTracker.Candidate) {
        synchronized(tracker) { tracker.lockedCell = candidate.gy * LightRoiTracker.GRID_W + candidate.gx }
        synchronized(decoder) { decoder.reset() }  // 別の光源の明るさが混ざらないよう、読み直す
    }

    fun unlock() {
        synchronized(tracker) { tracker.lockedCell = null }
        synchronized(decoder) { decoder.reset() }
    }

    private companion object {
        const val LIGHT_MIN_CONTRAST = 30f
    }
}

@Composable
fun ReceiveScreen(settings: MorseSettings, vm: ReceiveViewModel = viewModel()) {
    val context = LocalContext.current
    var permissionMessage by remember { mutableStateOf("") }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permissionMessage = if (ok) "" else "権限が必要です(設定アプリから許可してください)"
        if (ok) when (vm.source) { Source.MIC -> vm.startMic(); Source.CAMERA -> vm.startCamera(); else -> Unit }
    }
    val openAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::startAudioFile) }
    val openVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::startVideoFile) }
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use { o -> o.write((vm.state?.text ?: "").toByteArray()) } }
    }

    fun start() {
        when (vm.source) {
            Source.MIC, Source.CAMERA -> {
                val perm = if (vm.source == Source.MIC) Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA
                if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                    if (vm.source == Source.MIC) vm.startMic() else vm.startCamera()
                } else askPermission.launch(perm)
            }
            Source.AUDIO_FILE -> openAudio.launch(arrayOf("audio/*"))
            Source.VIDEO_FILE -> openVideo.launch(arrayOf("video/*"))
        }
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("受信", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Source.entries.forEachIndexed { i, s ->
                SegmentedButton(selected = vm.source == s, onClick = { vm.source = s }, enabled = !vm.running,
                    shape = SegmentedButtonDefaults.itemShape(i, Source.entries.size)) { Text(s.label, fontSize = 11.sp) }
            }
        }
        Text("読み取り方: ${settings.engine.label}", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { if (vm.running) vm.stop() else start() },
            colors = if (vm.running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(
                when {
                    vm.running -> "受信を止める"
                    vm.source == Source.AUDIO_FILE || vm.source == Source.VIDEO_FILE -> "ファイルを選んで読む"
                    else -> "受信を始める"
                }, fontSize = 18.sp,
            )
        }
        if (permissionMessage.isNotEmpty()) Text(permissionMessage, color = MaterialTheme.colorScheme.error)

        // ---- カメラの映像と光源の候補(枠をタップするとその光源に固定)
        if (vm.source == Source.CAMERA && vm.running) CameraPanel(vm)
        if (vm.source == Source.VIDEO_FILE && vm.selected != null) {
            Text("選んだ光源: 画面の左から ${"%.0f".format(vm.selected!!.centerX * 100)}%、上から ${"%.0f".format(vm.selected!!.centerY * 100)}% の位置",
                style = MaterialTheme.typography.bodySmall)
        }

        // ---- 処理の進み具合
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val st = vm.state
                Text(vm.phase.ifEmpty { "待機中" }, fontWeight = FontWeight.Bold)
                vm.fileProgress?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                    Text("${"%.0f".format(it * 100)}%")
                }
                if (st != null) {
                    Text("信号の強さ")
                    LinearProgressIndicator(progress = { st.signalLevel }, modifier = Modifier.fillMaxWidth())
                    val status = when { st.finished -> "受信終了(長い無音)"; st.receiving -> "受信中"; else -> "信号を待っています" }
                    Text("$status ・ 短点 ${"%.0f".format(st.unitMs)}ms" +
                        (vm.toneHz?.let { if (vm.source == Source.MIC || vm.source == Source.AUDIO_FILE) " ・ 音の高さ ${"%.0f".format(it)}Hz" else "" } ?: "") +
                        (if (st.currentCode.isNotEmpty()) " ・ 読み途中 ${st.currentCode}" else ""))
                    if (st.unknownCodes > 0) Text("読めなかった符号: ${st.unknownCodes} 個(?で表示)", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // ---- 読み取った文章
        Text("読み取った文章", fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            SelectionContainer { Text(vm.state?.text?.ifEmpty { "(まだありません)" } ?: "(まだありません)", fontSize = 22.sp, modifier = Modifier.padding(12.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { saveText.launch("受信_${System.currentTimeMillis() / 1000}.txt") }, enabled = !vm.state?.text.isNullOrEmpty()) { Text("テキストで保存") }
            if (vm.source == Source.CAMERA && vm.running) OutlinedButton(onClick = vm::unlock) { Text("光源を自動で選び直す") }
        }
    }
}

/** カメラの映像に、光源の候補(黄色)と選んだ光源(緑)の枠を重ねて表示する */
@Composable
private fun CameraPanel(vm: ReceiveViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            // 解析の部品は 1 つだけ作る(コマの時刻の基準を最初のコマに固定するため)
            val lightAnalyzer = LightAnalyzer(vm.tracker, vm.decoder)
            analysis.setAnalyzer(executor) { image ->
                val r = image.imageInfo.rotationDegrees
                if (vm.rotation != r) vm.rotation = r
                lightAnalyzer.analyze(image)
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
    // 映像は高さを抑え、下の進み具合と読み取った文章が同じ画面に収まるようにする
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.height(300.dp).aspectRatio(3f / 4f)) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().pointerInput(vm.candidates) {
                detectTapGestures { tap ->
                    // タップした位置に一番近い候補に固定する
                    val best = vm.candidates.minByOrNull {
                        val (x, y) = toScreen(it.centerX, it.centerY, vm.rotation)
                        val dx = x * size.width - tap.x
                        val dy = y * size.height - tap.y
                        dx * dx + dy * dy
                    }
                    best?.let(vm::lock)
                }
            }) {
                val boxW = size.width / LightRoiTracker.GRID_H * 2.5f
                for (c in vm.candidates) {
                    val (x, y) = toScreen(c.centerX, c.centerY, vm.rotation)
                    val isSel = vm.selected?.let { it.gx == c.gx && it.gy == c.gy } == true
                    drawRect(
                        color = if (isSel) Color(0xFF00E676) else Color(0xFFFFD600),
                        topLeft = Offset(x * size.width - boxW / 2, y * size.height - boxW / 2),
                        size = GSize(boxW, boxW), style = Stroke(width = if (isSel) 6f else 3f),
                    )
                }
            }
        }
    }
    Text("緑の枠 = 読み取っている光源、黄色の枠 = ほかの候補(タップで切り替え)", style = MaterialTheme.typography.bodySmall)
}

/** カメラの画像の座標(0〜1)を、画面(縦向き)の座標に直す */
private fun toScreen(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
    90 -> (1 - y) to x
    180 -> (1 - x) to (1 - y)
    270 -> y to (1 - x)
    else -> x to y
}

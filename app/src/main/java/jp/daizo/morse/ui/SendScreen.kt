package jp.daizo.morse.ui

import android.app.Application
import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.daizo.morse.core.MorseSchedule
import jp.daizo.morse.core.MorseTable
import jp.daizo.morse.io.MorseTransmitter
import jp.daizo.morse.io.SendProgress
import jp.daizo.morse.io.TransmitJob
import jp.daizo.morse.io.readTextFile
import kotlinx.coroutines.CancellationException

enum class SendMode(val label: String, val sound: Boolean, val light: Boolean) {
    SOUND("音", true, false), LIGHT("光", false, true), BOTH("音と光", true, true)
}

class SendViewModel(app: Application) : AndroidViewModel(app) {
    var text by mutableStateOf("")
    var mode by mutableStateOf(SendMode.SOUND)
    var progress by mutableStateOf<SendProgress?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
    private var sentText = ""
    val sendingText: String get() = sentText

    private val transmitter = MorseTransmitter(app)
    private val job = TransmitJob(viewModelScope)

    fun loadFile(uri: Uri) {
        runCatching { readTextFile(getApplication(), uri) }
            .onSuccess { text = it; message = "ファイルを読み込みました(${it.length}文字)" }
            .onFailure { message = "読み込めませんでした: ${it.message}" }
    }

    fun start(settings: MorseSettings) {
        val (normalized, skipped) = MorseTable.normalize(text)
        if (normalized.isBlank()) { message = "送れる文字がありません"; return }
        if (mode.light && transmitter.torchCameraId() == null) { message = "この端末にはライトがありません"; return }
        sentText = normalized
        message = if (skipped.isEmpty()) "" else "送れない文字を除きました: ${skipped.joinToString("")}"
        // 音はメディアの音量で鳴るので、0 だと何も聞こえない(実機で確認した落とし穴)
        val audio = getApplication<Application>().getSystemService(AudioManager::class.java)
        val muted = mode.sound && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        if (muted) message = listOf(message, MUTED).filter { it.isNotEmpty() }.joinToString("\n")
        val unitMs = if (mode.light) settings.lightUnitMs else settings.soundUnitMs
        val schedule = MorseSchedule.build(normalized)
        sending = true
        job.start {
            try {
                transmitter.send(schedule, unitMs, settings.toneHz, mode.sound, mode.light) { progress = it }
                message = if (muted) "送信が終わりました\n$MUTED" else "送信が終わりました"
            } catch (e: CancellationException) {
                message = "送信を止めました"
            } catch (e: Exception) {
                message = "送信できませんでした: ${e.message}"
            } finally {
                sending = false
            }
        }
    }

    fun stop() = job.stop()

    private companion object {
        const val MUTED = "音量(メディア)が 0 なので音が出ません。音量ボタンで上げてください"
    }
}

@Composable
fun SendScreen(settings: MorseSettings, onSettingsChange: (MorseSettings) -> Unit, vm: SendViewModel = viewModel()) {
    val openText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::loadFile) }
    val unitMs = if (vm.mode.light) settings.lightUnitMs else settings.soundUnitMs
    val preview = MorseTable.normalize(vm.text).first
    val estimateSec = MorseSchedule.build(preview).totalMillis(unitMs) / 1000.0

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("送信", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = vm.text, onValueChange = { vm.text = it }, enabled = !vm.sending,
            label = { Text("送信する文章(ひらがな・カタカナ・数字・、。（）)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openText.launch(arrayOf("text/*")) }, enabled = !vm.sending) { Text("テキストファイルを読み込む") }
            OutlinedButton(onClick = { vm.text = "" }, enabled = !vm.sending) { Text("消す") }
        }

        Text("送り方", fontWeight = FontWeight.Bold)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SendMode.entries.forEachIndexed { i, m ->
                SegmentedButton(selected = vm.mode == m, onClick = { vm.mode = m }, enabled = !vm.sending,
                    shape = SegmentedButtonDefaults.itemShape(i, SendMode.entries.size)) { Text(m.label) }
            }
        }
        Text("速さ: 短点 ${unitMs}ms(1 文字の平均 約 ${"%.1f".format(unitMs * 10 / 1000.0)} 秒)")
        Slider(
            value = unitMs.toFloat(), enabled = !vm.sending,
            valueRange = if (vm.mode.light) 150f..800f else 40f..300f,
            onValueChange = {
                val v = (it / 10).toInt() * 10
                onSettingsChange(if (vm.mode.light) settings.copy(lightUnitMs = v) else settings.copy(soundUnitMs = v))
            },
        )
        if (vm.mode.sound) {
            Text("音の高さ: ${settings.toneHz}Hz")
            Slider(value = settings.toneHz.toFloat(), valueRange = 400f..1200f, enabled = !vm.sending,
                onValueChange = { onSettingsChange(settings.copy(toneHz = (it / 10).toInt() * 10)) })
        }
        if (vm.mode.light) Text("光は背面のライトを使います。受信側のカメラにライトが写るように置いてください。", style = MaterialTheme.typography.bodySmall)

        Button(
            onClick = { if (vm.sending) vm.stop() else vm.start(settings) },
            colors = if (vm.sending) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (vm.sending) "送信を止める" else "送信を始める(約 ${"%.0f".format(estimateSec)} 秒)", fontSize = 18.sp) }
        if (vm.message.isNotEmpty()) Text(vm.message, color = MaterialTheme.colorScheme.secondary)

        // ---- 送信の進み具合
        val p = vm.progress
        if (p != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp).background(if (p.lightOn && vm.sending) Color(0xFFFFC107) else Color.LightGray, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        val chars = vm.sendingText.length
                        Text("${minOf(p.charIndex + 1, chars)} / $chars 文字 ・ 残り ${"%.0f".format((p.totalMs - p.elapsedMs) / 1000.0)} 秒")
                    }
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    // 送信中の文字に色を付けて、文章全体のどこを送っているかを示す
                    Text(buildAnnotatedString {
                        vm.sendingText.forEachIndexed { i, c ->
                            when {
                                i < p.charIndex -> withStyle(SpanStyle(color = Color.Gray)) { append(c) }
                                i == p.charIndex -> withStyle(SpanStyle(background = Color(0xFFFFE082), fontWeight = FontWeight.Bold)) { append(c) }
                                else -> append(c)
                            }
                        }
                    }, fontSize = 20.sp)
                }
            }
        }
    }
}

package jp.daizo.morse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(settings: MorseSettings, onChange: (MorseSettings) -> Unit) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("設定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Text("送信の速さ(短点の長さ)", fontWeight = FontWeight.Bold)
        Text("音: ${settings.soundUnitMs}ms")
        Slider(settings.soundUnitMs.toFloat(), { onChange(settings.copy(soundUnitMs = (it / 10).toInt() * 10)) }, valueRange = 40f..300f)
        Text("光: ${settings.lightUnitMs}ms(カメラは 1 秒に約 30 コマなので、150ms 以上を目安に)")
        Slider(settings.lightUnitMs.toFloat(), { onChange(settings.copy(lightUnitMs = (it / 10).toInt() * 10)) }, valueRange = 150f..800f)
        Text("音の高さ: ${settings.toneHz}Hz")
        Slider(settings.toneHz.toFloat(), { onChange(settings.copy(toneHz = (it / 10).toInt() * 10)) }, valueRange = 400f..1200f)
        Text("受信側は速さ・音の高さを自動で測るので、送信側と合わせる必要はありません。", style = MaterialTheme.typography.bodySmall)

        Text("読み取り方", fontWeight = FontWeight.Bold)
        MorseSettings.Engine.entries.forEach { e ->
            val available = e == MorseSettings.Engine.RULE
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.engine == e, onClick = { onChange(settings.copy(engine = e)) }, enabled = available)
                Text(e.label + if (available) "" else "(準備中)")
            }
        }
        Text(
            "ルール方式: 点灯・消灯の長さから符号を読む。同じアプリどうしの通信なら、これで十分に読めます。\n" +
                "CNN: PC で学習したモデルで 1 文字ずつ判定する方式。次の段階でモデルをスマホ用に変換して追加します。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

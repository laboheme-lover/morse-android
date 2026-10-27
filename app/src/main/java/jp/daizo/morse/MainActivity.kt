package jp.daizo.morse

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import jp.daizo.morse.theme.MorseTheme
import jp.daizo.morse.ui.MorseSettings
import jp.daizo.morse.ui.ReceiveScreen
import jp.daizo.morse.ui.SendScreen
import jp.daizo.morse.ui.SettingsScreen

/**
 * アプリの入口。画面の下のタブで「送信」「受信」「設定」を切り替える。
 * 送信と受信は別の画面にし、それぞれの状態(送信中・受信中)はタブを切り替えても続く。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 送受信の途中で画面が消えないようにする
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MorseTheme {
                val context = LocalContext.current
                var settings by remember { mutableStateOf(MorseSettings.load(context)) }
                val update: (MorseSettings) -> Unit = { settings = it; it.save(context) }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val tabs = listOf("送信" to "↑", "受信" to "↓", "設定" to "⚙")
                Scaffold(bottomBar = {
                    NavigationBar {
                        tabs.forEachIndexed { i, (label, mark) ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i },
                                icon = { Text(mark, fontSize = 20.sp) }, label = { Text(label) })
                        }
                    }
                }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (tab) {
                            0 -> SendScreen(settings, update)
                            1 -> ReceiveScreen(settings)
                            else -> SettingsScreen(settings, update)
                        }
                    }
                }
            }
        }
    }
}

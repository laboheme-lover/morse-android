# モールス通信(Android)

Android 端末どうしで、音と光のモールス信号(和文)を送受信するアプリ。
PC で作った音のアプリ(morse-ai)・光のアプリ(light-morse-ai)と同じ符号表(65文字)を使う。

## できること

| 画面 | 機能 |
|---|---|
| 送信 | 文章の入力・テキストファイルの読み込み(UTF-8 / Shift_JIS)、音・光(背面ライト)・音と光、速さと音の高さの調整、送信中の文字と残り時間の表示 |
| 受信 | マイク・カメラでのリアルタイム受信、録音・録画ファイルの読み込み。点滅している光源を自動で絞り込み、枠で表示(タップで切り替え) |
| 設定 | 音・光の速さ、音の高さ、読み取り方(ルール方式。PC のモデルを使う CNN は準備中) |

読み取りはルール方式(点灯・消灯の長さから符号を読む)。中身は `app/src/main/java/jp/daizo/morse/core/`。

## 動作を確かめた端末

- AQUOS R8(SH-R80, Android 15): 画面表示、送信の進み具合、ライトの点滅の長さ(±35ms)、マイク・カメラの起動
- SH-51F(Android 16): インストールと起動
- 2台での送受信は、まだ確かめていない

## 組み立てとテスト

Android Studio に付いている JDK を使う。プロジェクトは日本語を含まない場所に置くこと(Gradle が失敗する)。

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug testDebugUnitTest
# できた APK: app\build\outputs\apk\debug\app-debug.apk
```

`local.properties`(Android SDK の場所)は端末ごとに違うので、リポジトリには入れない。

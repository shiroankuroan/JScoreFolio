# JScoreFolio（邦楽用楽譜表示アプリ）

## English abstract

JScoreFolio is an offline viewer for traditional Japanese vertical-notation sheet
music (*tategaku* / 縦譜）. It opens PDF scores and lets you annotate directly
on the pages — vertical-text comments, snapped straight lines, and freehand
pen — with automatic margin cropping and page turning via a Bluetooth foot
pedal. The app is a single self-contained HTML file (`app/gakufu.html`) that
runs entirely offline in a browser; an optional Android WebView shell
(`android/`) packages it as an APK, and a ready-to-install build
(`JScoreFolio-1.3.apk`) is included.

## このアプリについて

尺八の譜面、箏曲の譜面等の邦楽の縦譜（右から左へ、上から下へ読む縦書きの楽譜）
用のPDF ビューアです。PDF の楽譜を開き、楽譜にコメントや線を引くことが出来ます。
アプリ本体は単一の HTML ファイルで、ネットワークなしで動作します。
LineageOS上でBluetooth フットペダルを使用するのを前提で作成していますが、
Androidであれば概ね動作すると思います。
HTMLを開けばiOSでも動作するはずですが、未確認です。

サンプルとしてPDFが入っています。

## 機能

- **PDF ライブラリ / インポート**: PDF を取り込んで一覧から開く
- **余白カット**: ページごとにインクのある範囲を検出し、余白を除いて
  拡大表示（ON/OFF 切替可）
- **縦書きコメント**: 置きたい場所に縦書きで文字を入れる
  - 文字サイズはポイント指定のスライダー（8〜64 pt）
  - 入力中は、文字が入る位置と大きさが四角いガイドで表示される
  - 「動かす」モードで、置いた文字を四角い枠ごとドラッグして移動できる
- **線とペン**: 縦横にスナップする直線と、自由なフリーハンドペン。
  消しゴム、元に戻す（アンドゥ）あり
- **表示操作**: 画面中央のタップで全ツールバーの表示/非表示を切り替え。
  ドラッグによるパン（スクロール移動）は廃止し、位置は固定。拡大/縮小
  はピンチと＋/－ボタン
- **フットペダル対応**: ペダルのキー入力（矢印キー / PageUp / PageDown /
  スペース / Enter）でページ送り。進む/戻るの向きは設定で入れ替え可能。
  受信したキーは画面上のバッジで確認できる
- **書き込み入り PDF の書き出し**: 全ページを描画し、書き込みを合成した
  新しい PDF「元の楽譜名_書き込み入り.pdf」を生成する

## 使い方（Web 版）

`app/gakufu.html` をブラウザで開くだけです。静的ファイルとして配信し
てもかまいません。実行時に外部リソースへは一切アクセスしません（PDF
描画ライブラリもサンプル楽譜もファイル内に同梱されています）。

## データの取り扱い方について
- **元の PDF ファイルは変更しません**。書き込みは元の PDF とは別の
  データとして保持します
- 取り込んだ PDF と、ページごとの書き込みは、ブラウザの **IndexedDB**
  に保存され、再起動しても残ります
- 書き込みの座標はページ基準の相対座標で保持するため、余白カットの
  ON/OFF で書き込みの位置がずれることはありません
- 書き込みを端末の外へ出すには「書き込み入り PDF の書き出し」を使い
  ます（描画したページ画像に書き込みを合成した新しい PDF ができます）

## Android APK
`android/` には、Web 版を Android アプリとして包むための薄い WebView
シェルが入っています。できあがった APK の例が `JScoreFolio-1.3.apk` です
（そのままインストールできます）。

### シェルの仕様
- パッケージ名 `jp.oka.gakufu`、アプリ名（ラベル）「JScoreFolio」
- minSdk 29 / targetSdk 34、**権限なし**（マニフェストに
  uses-permission を宣言していません）
- 全画面（没入型、スワイプで一時表示）＋画面 ON 維持
- WebView の設定: JavaScript / DOM ストレージ / データベース有効、
  ファイル・コンテンツアクセス許可。読み込むのは
  `file:///android_asset/www/index.html` のみ

素の WebView では動かない部分を、シェル側で橋渡ししています:

- **ペダルのキー転送**: `dispatchKeyEvent` で PageUp / PageDown、
  方向キー（DPAD）、スペース、Enter を横取りし（キーリピートは抑止）、
  ページへは**どちらか1経路だけ**で渡します。第1候補はページ側の
  `window.__pedalKey(key)`、無い場合のみ合成 keydown イベントに
  フォールバックします（両方送ると1回で2ページ進んでしまうため）。
  対象キー: PageUp, PageDown, ArrowLeft, ArrowRight, ArrowUp,
  ArrowDown, Space（" "）, Enter
- **ファイル選択**: `WebChromeClient.onShowFileChooser` を実装し、
  ページの `<input type="file">`（PDF インポート）が実際にファイル
  選択ダイアログを開けるようにしています
- **ダウンロード保存**: `DownloadListener` と JavaScript ブリッジ
  `GakufuDownload`（`begin(name, mime)` / `chunk(base64)` /
  `finish()` / `failed(msg)`）で、ページが生成したダウンロードを
  端末の公開 Downloads フォルダへ保存します。blob: / data: URL は
  DownloadManager では取得できないため、ページ内で fetch して
  256 KiB ごとの base64 チャンクでブリッジへ流し、MediaStore 経由で
  書き込みます。http(s) URL は DownloadManager に任せます。
  ファイル名は非 ASCII（日本語）を保持し、パス区切りや制御文字だけを
  除去します

### ビルド方法

ビルドは Gradle を使わない手組みのパイプラインです
（aapt2 → javac → d8 → zipalign → apksigner）。

必要なもの:

- JDK 17 以降
- Android SDK: platform 34 と build-tools 34.0.0
- 署名用のキーストア（**自前で用意してください。下記参照**）

`android/build-apk.sh` は、自分の置かれたディレクトリを基準に
`sdk/`、`jdk/jdk-*`、`shell/`、`keystore.jks`、
`keystore-password.txt` を探します。リリース内のシェル一式は
`android/` 直下にあるので、まず次の配置にしてください:

```
android/
  build-apk.sh
  sdk/                      # Android SDK をここへ
  jdk/jdk-<version>/        # JDK をここへ
  shell/
    AndroidManifest.xml     # android/ 直下のものを shell/ へ移す
    res/                    # 同上
    src/                    # 同上
  keystore.jks
  keystore-password.txt
```

ビルド:

```
cd android
./build-apk.sh ../app/gakufu.html [出力先.apk]
```

出力先を省略すると `android/gakufu.apk` ができます。

注意:

- javac の `-parameters` フラグは**必須**です。これがないと、無名
  内部クラスのコンストラクタに名前なしの MethodParameters 属性が
  書かれ、build-tools 34.0.0 の d8 が NullPointerException で
  クラッシュします（スクリプト内にコメントで経緯を残してあります）
- スクリプト内の versionCode / versionName は固定値（1 / "1.0"）です。
  変えたい場合は aapt2 link の引数を変更してください

### 署名鍵について

署名鍵はリリースに**含まれていません**。各自で作成してください:

```
keytool -genkeypair -v -keystore keystore.jks -alias gakufu \
    -keyalg RSA -keysize 2048 -validity 10000
```

鍵のパスワードはキーストアのパスワードと同じにし、そのパスワードを
`keystore-password.txt` に1行で書いて `android/` に置いてください
（スクリプトが `--ks-key-alias gakufu` と
`--ks-pass file:keystore-password.txt` で読みます）。

## バージョン履歴

- **1.3（名称変更）**

- **1.0**: 初版 APK
- **1.1**: 文字サイズのスライダー化、書き込み入り PDF の書き出し、
  シェルのダウンロード保存対応
- **1.2**: 文字コメントのドラッグ移動、入力位置の四角ガイド
- **1.3**: 書き出しファイル名の修正（本来のファイル名をブリッジ経由で
  器へ渡す）

このほか初期段階で次の修正を取り込み済みです: ペダルで2ページ飛ぶ
問題の修正、WebView での PDF インポート対応、ツールバーが楽譜に
重なる問題（中央タップで表示/非表示）、ドラッグで楽譜が動く問題
（パン廃止）。

## 既知の制限・注意

- このアプリはMuse(muse.ai)で作成されています。
- 線・ペンのドラッグ描画の感触とピンチ操作は、ロジックとしては
  検証済みですが、最終的な感触の確認は作者が実機タブレットで行った
  ものです
- 書き込みは端末内（IndexedDB）にのみ保存されます。書き込み入り
  PDF として書き出さない限り、端末の外へは出ません
- Web 版をブラウザで使う場合、保存先はそのブラウザのプロファイルに
  ひもづきます（別のブラウザ・別端末とは共有されません）

## 同梱物について

- **pdf.js**: `app/gakufu.html` には pdfjs-dist 5.4.624 を同梱
  （vendored）しています。文書由来の動的コード生成（PostScript
  関数の JIT）は除去し、インタプリタによる代替に落としたビルドです。
  pdf.js 本体は Apache License 2.0 です
- **サンプル楽譜「二葉のかおり」**: アプリ内に同梱され、初回起動時に
  自動で取り込まれます

## ライセンス

このソフトウェア本体（Web アプリおよび Android シェルのコード）は
**MIT License** のもとで公開します。全文は同梱の `LICENSE` ファイル
をご覧ください。

Copyright (c) 2026 ker

ただし、同梱物にはそれぞれ別の条件があります:

- **pdf.js** は Apache License 2.0 です

## リリースの内容

```
JScoreFolio/
  README.md            # この文書
  LICENSE              # MIT License
  app/
    gakufu.html        # アプリ本体（単一ファイル、オフライン動作）
  android/
    AndroidManifest.xml
    build-apk.sh       # 手組みビルドスクリプト
    res/               # アイコンと色定義
    src/jp/ker/gakufu/MainActivity.java
  JScoreFolio-1.3.apk  # ビルド済み APK（インストール用サンプル）
```

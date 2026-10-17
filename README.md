# Dialog Custom GUI

Minecraft Java 26.3 / Paper 向けに、**Dialog + リソースパック**でGUIを作るWebエディターです。core shader は使いません（出力時に shader ファイルが混入しないことも検査します）。

現段階はエディター・CLI・書き出しまで。Paperプラグインは次の段階で作ります（書き出したデータパックだけでも、画面表示と画面遷移は動きます）。

## 起動

Node.js 22 以降。

```powershell
cd C:\mc\dialog-custom-gui
npm install
node tools/fetch-vanilla.mjs   # 初回のみ: ゲーム内フォントの資源を data/vanilla/ に用意
node server.mjs
```

`fetch-vanilla.mjs` は client.jar（`--jar` で指定。既定は `%APPDATA%\.minecraft\versions\26.3\26.3.jar` か `C:\mc\dialog-gui\research\client-26.3.jar`）からフォント定義と英字等の画像を、Mojang公式のアセット配信から Unifont（日本語など）を取得し、SHA-1 を照合して保存します。ローカル専用で、配布物には含めません。

ブラウザーで `http://127.0.0.1:4320/` を開きます。ポートは `DCG_PORT`、データの場所は `DCG_DATA` / `DCG_EXPORTS` で変えられます。ローカル（127.0.0.1）専用です。

## エディター

- **編集**: 矩形・テキスト・画像・ボタン・クリック領域を配置。ドラッグで移動、四隅/辺でサイズ変更。矢印キーで1px（Shiftで9px）、Delete、Ctrl+D 複製、Ctrl+Z / Ctrl+Y。
- **プレビュー**: ゲーム画面の概算（タイトル・本文・入力欄・フッター）。クリックで画面遷移、ホバーでツールチップを試せます。
- **検証**: 書き出すフォント・Dialog JSON を、Minecraftクライアントの規則（折り返し・字幅計算）で解釈し直して再構成した画像。下部に検証結果が出ます。
- 画面ごとに: 本文サイズ（幅 16〜1023、高さは9の倍数）、背景、タイトル、Escで閉じる、クリック後の動作、標準フッターボタン、標準入力欄（テキスト・チェック・選択肢・スライダー）。
- 画像はPNG/JPEG/WebP/GIF。キャンバスへドロップ、または素材欄の＋から。
- CLI/APIの変更はブラウザーへ即時反映されます（同じUndo履歴を共有）。

## 文字

| 描画方式 | 用途 | 仕組み | 制約 |
|---|---|---|---|
| **ゲーム内フォント**（既定） | 本文・ラベル・日本語 | 本文の文字としてそのまま出力。フォント `<ns>:text` は `minecraft:include/{space,default,unifont}` を参照するだけで、「Unicodeフォントを強制」の影響を受けません | 8px固定。y は9の倍数、h で行数。常に最前面に描かれる |
| **画像に焼き込み** | 英語の見出し | 任意フォントを画像にしてタイルに含める。「ドット化」でにじみを2値化 | 大きさ自由。画像なので動的には変えられない |

- 字幅は26.3の規則（bitmap: `(int)(0.5+実幅×scale)+1`、Unifont: `実幅/2+1`、太字 +1 / +0.5）で求め、行幅を本文幅に合わせます。本文の左右からはみ出す文字は出力しません。
- 日本語クライアントでだけ幅が変わる文字（5字）を使うと注意が出ます。
- 登録フォントは `fonts/fonts.json`。現在は **5x5 Pixel**（Negociator / FontStruct、CC BY-SA 3.0。英数字のみで、小文字は大文字と同じ字形。8px で1ドット=1px）。ドットフォントはサーバーでドット単位のビットマップに変換し、ブラウザーとNodeで同じ描画にしています（このTTFは cmap の終端が不正で、Chromeでは直接読めないため）。使用したフォントのクレジットは `credits.txt` としてリソースパックに入ります。

## プラグインへの引き継ぎ

Paper プラグインは別プロジェクトで作る。受け取るもの・画面の移動・custom のID一覧・未実装の機能・実機で未確認のことは [docs/plugin-handoff.md](docs/plugin-handoff.md)。

## テーマ（色）

要素の色は `#RRGGBBAA` の直接指定のほかに、テーマの色の名前 `@panel.bg` を参照できます。テーマの色を変えると全画面に反映されます。プロジェクト設定の「テーマ（色）」欄、JSONの書き出し・読み込み、`node cli.mjs theme` で編集できます。デザイナー向けの説明と色の一覧は [docs/theme.md](docs/theme.md)。

## 仕組み（core shaderなし）

1. 画面の静的な見た目全体を「テクスチャ倍率」倍で1枚に描きます（文字も画像として焼き込み、日本語可）。
2. 240px 以下のタイルに分け、タイルごとに1文字の bitmap glyph として画面専用フォント `<ns>:screen/<画面>` に登録。タイルは9px行に揃え `ascent: 7` で配置します。
3. `plain_message` 1つの本文で、space フォント（±1〜1024 のadvance）でペンを動かしてタイルを置きます。
4. クリック領域は画像と独立に、空白glyph（幅=advance、高さ9）の列へ `click_event` / `hover_event` を付けて作ります。そのため**クリック領域は9px行単位**です。
5. 各行の合計advanceを本文幅（幅+1）に揃え、中央寄せや折り返しでずれないようにします。

26.3 の client.jar で、`BitmapProvider` の advance 計算 `(int)(0.5 + 実幅 × height/セル高さ) + 1`、`StringSplitter` の折り返し条件（幅 > maxWidth）、本文ウィジェット（中央寄せ・背景塗りなし）を確認し、`lib/simulate.mjs` で同じ規則を再現して、出力ごとに画素単位で一致を検査しています。

### アクション

| エディターの動作 | 出力 | 必要なもの |
|---|---|---|
| 画面へ遷移 | `show_dialog` → `<ns>:<画面>` | データパックのみ |
| カスタム | `custom`（id + payload） | プラグイン |
| 閉じる（本文内） | `custom` `<ns>:close` | プラグイン |
| コマンド / 入力欄へ / URL / コピー | `run_command` / `suggest_command` / `open_url` / `copy_to_clipboard` | — |
| 入力値を送信（フッター） | `dynamic/custom` | プラグイン |

## 書き出し

`exports/<日時>_r<revision>/` と `exports/latest/` に出力します。

- `resourcepack.zip` — `pack.mcmeta`（format 97.1）、フォント、タイル画像。標準GUIスキンを指定した場合のみ `assets/minecraft/textures/gui/sprites/widget/` を含みます。
- `datapack.zip` — `data/<ns>/dialog/<画面>.json`（format 121）、`/function <ns>:open/<画面>`。
- `manifest.json` — 画面・アクション一覧・SHA-1（プラグイン用）。`report.json` — 検証結果。

**試し方（プラグインなし）**: リソースパックを有効にし、データパックをワールドの `datapacks/` に入れて `/reload`、`/dialog show @s dcg:main_menu`。

## CLI

エディターの起動中に使います。

```powershell
node cli.mjs help
node cli.mjs status
node cli.mjs ls main_menu
node cli.mjs add main_menu button id=play x=9 y=54 w=100 h=18 label=遊ぶ action.type=screen action.value=shop
node cli.mjs set main_menu to_shop label=買い物 y=63
node cli.mjs set - - textureScale=3
node cli.mjs upload .\icon.png
node cli.mjs compile shop
node cli.mjs preview shop --mode compiled --clicks -o shop.png
node cli.mjs batch ops.json
node cli.mjs undo
node cli.mjs audit
node cli.mjs export
```

値はJSONとして解釈し、失敗したら文字列として扱います。`batch` は操作（op）の配列で、1件でも検証に失敗すると全件保存しません。操作の一覧は `node cli.mjs schema`。

### API

`GET /api/project`, `POST /api/ops {revision, operations}`（古いrevisionは409）, `POST /api/undo|redo`, `POST /api/assets`（本体=画像、`X-Filename`）, `GET /api/compile/<画面>[?full=1]`, `GET /api/preview/<画面>.png?mode=game|body|compiled&clicks=1&zoom=2`, `POST /api/export`, `GET /api/events`（SSE）。他サイトのOriginからの書き込みは拒否します。

## 制限

- ホバーで画像を変えることはできません（ツールチップのみ）。クリックした本文には標準のフォーカス枠が出ます（shaderなしでは消せません）。
- 標準のタイトル・フッターボタン（最低1つ必須）・スクロールバーは残ります。ボタン/スクロールバーの見た目は「標準GUIスキン」で変えられますが、Minecraft全体に影響します。
- ゲーム内フォントの文字は動的な差し替え（プレイヤー名など）に使える形ですが、差し替え自体はプラグインと合わせて対応予定です。
- プレビューは概算です。実機での描画・クリック位置の確認はまだ行っていません。

## テスト

```powershell
npm test
```

モデル操作、advance計算、ランダム配置のファズ（行幅・画素一致）、PNG/ZIP、API（競合・Origin・Undo）とCLIを確認します。

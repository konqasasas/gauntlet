# メニュー（Dialog）

メニューの画面は別プロジェクトのエディター（`C:\mc\dialog-custom-gui`）で作る。このプラグインは、エディターが書き出した雛形からプレイヤーごとの画面を組み立てて送り、画面のクリック（custom）を受けて処理する。エディターからの引き継ぎ資料は `C:\mc\dialog-custom-gui\docs\plugin-handoff.md`。

状態（2026-10-18）：接続済みは「画面の移動・閉じる・ロビーへ戻る・エモート・設定」。設定画面は選択中の項目・ON/OFF・音量・翻訳先がプレイヤーの値で表示される。ほかの画面の中身は固定の見本。

## 置くもの

エディターで書き出した `exports\latest\` から:

| ファイル | 置き場所 | 用途 |
|---|---|---|
| `templates\*.json` | `plugins\ParkourGauntlet\menu\` | 画面の雛形（全状態入り）。プラグインが読む。`/gauntlet reload menu` で読み直す |
| `resourcepack.zip` | クライアント（配布の仕組みはまだない） | 画像タイルとフォント |
| `datapack.zip` | ワールドの `datapacks\`（任意） | 雛形から組み立てられない画面（入力欄のある `course_search`）だけに使う。ほかの画面は不要 |

- 名前空間 `dcg` は `ui/DialogMenu.NAMESPACE`。エディターの設定で変えたら合わせる。

## 画面の組み立て（`ui/DialogTemplates`・`ui/DialogMenu`）

- 雛形は Dialog の JSON で、状態ごとの部品に `insertion` の目印 `dcg:state:<条件>` が付いている（条件は `名前=値` か `名前!=値`）。各部品は「描いて元の位置に戻る」形なので、合わない部品を取り除いても行の幅は変わらない。
- 状態の値はプレイヤーの個人設定（`Settings.ALL`）。真偽値は `on` / `off`、数値は `70` のような文字列。
- 取り除いた後の JSON を Adventure の `GsonComponentSerializer` で読み、Paper の Dialog API（`Dialog.create`）で組み立てて送る。データパックへの登録は使わない。
- Adventure は custom クリックの payload を SNBT の文字列としてしか読まないため、JSON の payload は SNBT に直してから読む。
- 組み立てられるのはフッターなしの `dialog_list` だけ。それ以外（入力欄のある画面）は、データパックで登録された同じ ID の画面を開く。どちらもなければチャットで知らせ、ログに警告を出す。

## 入口

- `/menu` で `top_menu` を開く。ホットバー9番は未定のまま。

## 受け取るクリック

| ID | 処理 |
|---|---|
| `dcg:open` | `{"screen":"settings"}` の画面を開く（画面の移動はすべてこれ。`show_dialog` だとサーバーに届かず、プレイヤーごとの状態を出せない） |
| `dcg:close` | 画面を閉じる |
| `dcg:lobby` | 画面を閉じ、`lobby.world` のスポーンへ移動。コース中ならコースを抜けてから。ロビーが無効なら案内のみ |
| `dcg:emote_play` | 画面を閉じ、BetterModel のプレイヤーアニメーション（`model` / `id`）を1回再生。`/bettermodel play` と同じく、再生中はバニラの体を隠す。コース中は使えない。所持の概念はまだなく、全員が全部使える |
| `dcg:emote_page` | 何もしない（全カードが1ページに収まる見本のため） |
| `dcg:settings_set` | 値を設定 → `settings` を送り直す（翻訳先の選択画面からも設定に戻る）。翻訳先の「UI言語に合わせる」は `ui` |
| `dcg:settings_toggle` | ON/OFF を反転 → 送り直し |
| `dcg:settings_step` | 音量を増減（0〜100で止まる） → 送り直し |
| `dcg:settings_reset` | 一般タブの設定（`Settings.ALL` の `menu=true`）を初期値へ → 送り直し |
| それ以外の `dcg:*` | 「準備中」と案内（ランクアップ、お知らせ、コース選択の各操作） |

- 設定を変えるとシステム音（クリック音）を鳴らす。変更後の音量で鳴るので、音量の確認にもなる。本文の文字のクリックではクライアントが音を鳴らさない。
- payload はクライアントから SNBT で届く。`ui/DialogPayload` が最上位の値を文字列にする（数値、真偽値は true/false）。

## エモートと BetterModel

- `softdepend: [BetterModel]`。BetterModel がないときは「使えません」と案内してログに警告を出す。モデル・アニメーションが見つからないときも同じ。
- API は `bettermodel-bukkit-api` / `bettermodel-api`（3.5.1、compileOnly）。呼び出しは `ui/Emotes` にまとめた。

## 実機で分かったこと

- `plain_message` は左右に4pxずつ余白を取る。エディターは `width` を本文幅 + 8 で出す（以前は全行が折り返して崩れた）。
- クリックの判定（26.3 の `ActiveTextCollector`）: 行の中で実際に描かれる文字の範囲（bounds）の外は見ない（空白は範囲に入らない）。範囲の中では、カーソルの下のクリック付きの文字のうち最後のものが選ばれる。エディターは各行の左右の端にほぼ透明な1pxの点を置いて範囲を行の幅いっぱいにする（以前は文字のある所しか反応せず、音量の行は全く反応しなかった）。
- payload に null があるとデータパックの読み込みに失敗してサーバーが起動しない。エディターは null を出さない。

## 参考

- [resourcepack-ai/dialog-engine](https://github.com/resourcepack-ai/dialog-engine) と [rp-engine](https://github.com/resourcepack-ai/rp-engine)（GPL-3.0。コードは使わず、考え方だけ参考にした）: 状態ごとの見た目を全部描いて目印で選ぶ、行の幅を変えない「描いて戻る」、画面の移動をサーバー経由にする、レジストリを使わず直接送る。

## 未対応（エディター側の機能待ち）

- 差し込み文字（名前・ランク・数値）、リスト（コース・エモート・お知らせ・ランキング）、コースごとの画像。
- コース選択（並べ替え・絞り込み・ページ・検索・お気に入り・開始）：コースIDが仮の値で、一覧の差し込みもまだない。
- リソースパックの配布（URL・SHA-1、読み込み完了を待ってから開く）。

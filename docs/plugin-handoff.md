# Paperプラグインへの引き継ぎ

Gauntlet サーバーの GUI（Minecraft Java 26.3 / Paper）を、このエディター（`C:\mc\dialog-custom-gui`）で作っています。この資料は、別プロジェクトで作る **Paper プラグイン**に必要なことをまとめたものです。エディター自体はこのプロジェクトで引き続き開発します。

作成日: 2026-10-17。エディターの revision 42 時点。

## 1. 前提と決まりごと

- 対象: Minecraft Java **26.3**、Paper（調査に使った API は `paper-api 26.3.build.178-stable`）。クライアントMODなし。
- **core shader は使わない**（禁止）。リソースパックはフォントと画像だけ。
- GUI は Dialog（バニラの `dialog` 画面）を使う。見た目は、リソースパックのフォントに登録した画像タイルと、ゲーム内フォントの文字で描いている。
- 全画面の外枠は **452×216 GUI px**（1080p・GUIスケール4でスクロールなしに収まる最大）。例外はコース検索のみ。
- 画面は**フッターなし**（`minecraft:dialog_list`、`exit_action` なし・`dialogs: []`）。閉じるのは右上の✕か Esc。
- 名前空間は `dcg`（エディターのプロジェクト設定で変更可能）。

## 2. エディターから受け取るもの

エディターで「書き出し」（または `node cli.mjs export`）すると `exports/latest/` に出る。

| ファイル | 中身 | プラグインでの使い方 |
|---|---|---|
| `resourcepack.zip` | `pack.mcmeta`（format 97.1）、`assets/dcg/font/*`、`assets/dcg/textures/font/screen/*`、`credits.txt` | プレイヤーへ配布する。SHA-1 は `manifest.json` にある |
| `datapack.zip` | `data/dcg/dialog/<画面>.json`（format 121）と `data/dcg/function/open/<画面>.mcfunction` | ワールドの `datapacks/` に入れれば Dialog が登録される。プラグインで同じ JSON を読み込んで登録してもよい |
| `manifest.json` | 画面一覧・Dialog ID・各画面のクリック動作の一覧・パックの SHA-1 | プラグインが動作の対応表として読む |
| `report.json` | 検証結果（エラー・注意） | 書き出しが正しいかの確認用 |
| `templates/<画面>.json` | 全状態入りの Dialog JSON（状態の部品に `insertion` の目印） | プラグインが読み、プレイヤーの値に合う状態だけ残して送る（7.6） |

- Dialog の ID は `dcg:<画面ID>`（例: `dcg:top_menu`）。
- フォント: `dcg:space`（位置合わせの空白）、`dcg:text`（ゲーム内フォント。`minecraft:include/space`・`include/default`・`include/unifont` を参照するだけ）、`dcg:screen/<画面ID>`（画像タイル）。
- 書き出しは同じ内容なら同じ SHA-1 になる（ZIP の日時を固定している）。

## 3. 画面と移動

```
top_menu ─┬─ ランクアップ（custom dcg:rankup_start）
          ├─ course_select ─── course_search（入力欄つき）
          ├─ ranking（仮）
          ├─ profile（仮） ⇄ profile_settings（仮）
          ├─ emote ⇄ emote_motion ⇄ emote_pose（タグで絞り込み）
          ├─ settings ⇄ settings_friend（仮） ⇄ settings_chat（仮）
          │       ├─ settings_translate
          │       └─ settings_reset_confirm
          ├─ 🔔 news
          ├─ help（仮）
          └─ ロビーへ戻る（custom dcg:lobby）
```

- 画面どうしの移動は custom `dcg:open`（payload `{"screen":"<画面ID>"}`）。プラグインが画面を組み立てて送る（7.6）。（仮）は「準備中」の画面。
- 全画面の `after_action` は **`none`**。クリックしても画面は閉じない。プラグインは custom を受けたら、画面を送り直すか、閉じるか、別の画面を開く必要がある。

## 4. プラグインが受け取るクリック（custom）

Paper では `io.papermc.paper.event.player.PlayerCustomClickEvent` で受け取る。`getIdentifier()` が ID、`getTag()` が payload（JSON を NBT にしたもの）。入力欄つきの送信は `getDialogResponseView()` の `getText("query")` などで値を読む。プレイヤーは `getCommonConnection()` から取得する（`PlayerGameConnection` のときの取り方は要確認）。

| ID | payload の例 | 画面 | 期待する処理 |
|---|---|---|---|
| `dcg:close` | `{"screen":"top_menu","element":"close_btn_hs"}` | 全画面の✕ | 画面を閉じる（`after_action: none` のため、プラグインが閉じる必要がある） |
| `dcg:rankup_start` | `{}` | top_menu | ランクアップの続きのステージを開始 |
| `dcg:lobby` | `{}` | top_menu | ロビーのスポーンへ移動 |
| `dcg:news_open` | `{"id":0}` | news | お知らせの詳細（表示方法は未定） |
| `dcg:course_start` | `{"course":"c01"}` | course_select | コースを開始 |
| `dcg:course_favorite` | `{"course":"c01","on":true}` | course_select | お気に入りの登録・解除 → 送り直し |
| `dcg:course_sort` | `{"by":"diff"}`（diff / new / name） | course_select | 並べ替え → 送り直し |
| `dcg:course_filter` | `{"filter":"all"}`（all / fav / open / done / easy / hard） | course_select | 絞り込み → 送り直し（1ページ目へ） |
| `dcg:course_page` | `{"delta":-1}` | course_select | ページ送り（ページはプラグインが覚える） |
| `dcg:course_search_clear` | `{}` | course_select | 検索条件を消す → 送り直し |
| `dcg:course_search`（dynamic/custom） | 入力値 `query` | course_search の [検索] | 名前で絞り込み、course_select を送り直し（空欄なら解除） |
| `dcg:settings_set` | `{"key":"ui_lang","value":"auto"}`（翻訳先の「UI言語に合わせる」は `"value":"ui"`） | settings / settings_translate | 設定を変更 → 送り直し（翻訳先の選択画面からは settings に戻す） |
| `dcg:settings_toggle` | `{"key":"translate_auto"}` | settings | ON/OFF を反転 → 送り直し |
| `dcg:settings_step` | `{"key":"sound.system","delta":-10}` | settings | 音量を10%増減（0〜100） → 送り直し |
| `dcg:settings_reset` | `{}` | settings_reset_confirm | 一般タブの設定を初期値へ → settings を送り直し |
| `dcg:emote_play` | `{"model":"social_emotes","id":"cheer"}` | emote 系 | 再生（未所持のカードは何も送らない）。対応は下の表 |
| `dcg:emote_page` | `{"view":"emote","delta":1}` | emote 系 | ページ送り（view は表示中の画面） |

payload の ID（`c01` や `emote_01`）は見本の仮の値。実際のコースIDやエモートIDに置き換える前提。

### エモート

ポーズもエモートとして扱い、タグ（動き / ポーズ）で絞り込む。payload の `model` は BetterModel のモデル名、`id` はアニメーション名。プラグインは、クリックしたプレイヤーに対して `/bettermodel play <model> <id>` に当たる処理を行う（プレイヤーに `/bettermodel` の権限を渡さないよう、Dialog から直接コマンドは実行させていない）。

| 表示名 | タグ | model | id |
|---|---|---|---|
| 喜ぶ | 動き | `social_emotes` | `cheer` |
| 怒る | 動き | `social_emotes` | `stomp` |
| 落ち込む | 動き | `social_emotes` | `slump` |
| 拍手する | 動き | `social_emotes` | `clap` |
| 手を振る | 動き | `social_emotes` | `wave` |
| 突き上げる | ポーズ | `photo_poses` | `fist_pump` |
| カメラに向ける | ポーズ | `photo_poses` | `point_block` |
| 掲げる | ポーズ | `photo_poses` | `raise_block` |

画面は `emote`（すべて）・`emote_motion`（動き）・`emote_pose`（ポーズ）。左のタグ一覧で移動（dcg:open）。

### 設定のキー（一般タブ）

| キー | 種類 | 値 | 初期値 |
|---|---|---|---|
| `ui_lang` | 選択 | `auto` / `ja` / `en` | `auto`（クライアントの言語。ja・en 以外なら英語） |
| `translate_lang` | 選択 | `ui`（UI言語に合わせる）/ ja, en, ko, zh-CN, zh-TW, es, pt, fr, de, ru, it, th, vi, id | `ui` |
| `translate_auto` | on/off | true / false | false |
| `fail.title` | on/off | true / false | true |
| `sound.system` | 数値 | 0〜100（10刻み） | 100 |
| `sound.course` | 数値 | 0〜100（10刻み） | 100 |

設定画面に出さないもの（`players.visible` はホットバー8番のアイテムで切り替え、など）は企画メモのとおり。

## 5. リソースパックの配布

- Paper の API でパックを送り（URL と SHA-1）、**読み込み完了（SUCCESSFULLY_LOADED）を待ってから**画面を開く。読み込み前に開くと画像タイルや空白フォントが無く、レイアウトが崩れる。
- 既存のサーバーパックがある場合は、追加のパックとして送るか、統合する。統合するときは `assets/dcg/` と `credits.txt` をそのまま入れる。
- `credits.txt` は使ったフォントのクレジット（5x5 Pixel、CC BY-SA 3.0）。パックに残すこと。

## 6. まだできていないこと（エディター側）

今の書き出しは**すべて固定の見本**です。プレイヤーごとの値を出すには、次の機能が必要です。どれもエディター側の作業で、プラグインと合わせて形を決める必要があります。

| 機能 | 使う場所 | 内容 |
|---|---|---|
| 差し込み文字 | 名前・ランク・クリア数・ベストタイム・ページ番号・未読数 | 文字の枠を書き出し、プラグインが値を入れる。左寄せは「同じ文字を負の幅で打ち消す」方式なら計測不要。中央・右寄せは字幅表が必要（ゲーム内フォントの字幅はエディターの `data/vanilla/` から出せる） |
| 状態の切り替え | 選択中のタブ・並べ替え、クリア済み・お気に入り・未所持の印 | **仕組みはできた（7.6）**。設定画面は対応済み。ほかの画面は見本に状態を付けていない |
| リスト | コースのカード、エモートのカード、お知らせ、ランキング | 雛形を件数分並べ、件数とページを変えられるようにする |
| コースごとの画像 | コースのサムネイル、エモートのアイコン | IDで差し替える画像 |

この4つの形が決まるまでは、プラグインは「画面を開く・移動する・custom を受けて処理する」部分から作るのがよい。

## 7. 制約（Dialog と shader なしによるもの）

- クリック領域は **9px 行単位**。ホバーで見た目は変えられない（ツールチップのみ）。
- 本文をクリックすると標準のフォーカス枠が出る（shader なしでは消せない）。画面を送り直すと消える。
- 画面を送り直すとスクロールが先頭に戻り、サーバーからスクロール位置は指定できない。そのため全画面スクロールなしの大きさにしてあり、一覧はページ送り（ページ位置はプラグインが覚える）。
- ゲーム内フォントの文字は 8px 固定・常に最前面。文字色の不透明度は使えない。
- 標準の入力欄は本文の下にしか置けないため、検索は別ダイアログ（course_search）。
- 日本語クライアントでだけ字幅が変わる文字が5字ある（使うとエディターが注意を出す）。

## 7.5 実機で分かったこと（2026-10-18、revision 72）

Paper 26.3 のサーバーと 26.3 のクライアントで確かめ、エディターを直した。

- **本文の幅**: `plain_message` は左右に4pxずつ余白を取る（FocusableTextWidget）。文字を置ける幅は `width - 8`。以前は `width` = 453 で出していたため、すべての行が折り返し、縦にずれて左にはみ出していた。今は `width` = 本文幅 + 8（461）で出している。
- **クリックの判定**（クライアントの `ActiveTextCollector` を読んで確認）: 行の中で実際に描かれる文字（bitmap・ゲーム内フォント）の範囲の外は判定しない。空白（space プロバイダー）は範囲に入らない。範囲の中では、カーソルの下にあるクリック付きの文字のうち**最後のもの**が選ばれる。以前は文字のある所しか反応しなかった（音量の行は文字が「100%」だけで、◀▶やバーが全く反応しなかった）。今は各行の左右の端にほぼ透明な1pxの点（`dcg:space` の U+E020、`textures/font/edge.png`）を置いて範囲を行の幅いっぱいにし、文字にはクリックを付けない。書き出しのときに、この規則で配置どおりに当たるかを検証している。
- **payload の null**: NBT に null がないため、データパックの読み込みに失敗してサーバーが起動しなかった。null のキーは出力しない（注意を出す）。翻訳先の「UI言語に合わせる」は `"value":"ui"` にした。
- `dialog_list`（`dialogs: []`、`exit_action` なし）は受け付けられ、フッターは消えた。

## 7.6 状態とプラグインからの送信（2026-10-18、revision 73）

- 要素（rect / text / image）とグループに `state` を付けられる。`名前=値` はその値のときだけ、`名前!=値` はそれ以外のときだけ描く。`&` でつなぐと、すべてを満たすときだけ（グループの条件と中の要素の条件はつながる）。プラグインの目印の判定も `&` に対応すること。画面の `stateDefaults` が既定の値（プレビュー・データパック・検証に使う）。クリック領域には付けられない。
- 状態の付いた要素は背景に焼き込まず、状態ごとの重ね絵（画像タイル・ゲーム内フォントの文字）として書き出す。本文の各行で「描いて元の位置に戻る」形で出し、`insertion` に `dcg:state:<条件>` を付ける。合わない部品を取り除いても行の幅は変わらない。
- 書き出しは `templates/<画面>.json`（全状態入り）と、データパック（既定の状態だけ）。検証は既定の状態で画素単位に照合し、全状態入りでも各行の幅が合うことを確かめる。
- 画面の移動（action: screen）は custom `dcg:open` で出す。`show_dialog` だとクライアントが登録済みの画面を開くだけでサーバーに届かず、プレイヤーごとの状態を出せないため。
- 設定画面の状態: `ui_lang`（auto / ja / en）、`translate_lang`（ui / 言語コード）、`translate_auto`（on / off）、`sound.system`・`sound.course`（0〜100の10刻み）。値はプラグインの個人設定と同じ表記。
- 参考にしたもの: [resourcepack-ai/dialog-engine](https://github.com/resourcepack-ai/dialog-engine)（GPL-3.0。コードは使わず考え方だけ）。

## 8. 実機で未確認のこと（優先順）

エディターの検証は、Minecraft クライアントの規則（字幅計算・折り返し）を再現した画素単位の照合まで。**実機での表示はまだ一度も確認していない。** 最初に次を確かめてほしい。

1. `dialog_list`（`dialogs: []`、`exit_action` なし）が受け付けられ、フッターが消えること。
2. 画像タイルの位置・大きさ（GUIスケール 2〜4）と、クリック領域が見た目と合うこと。
3. ゲーム内フォントの文字の位置と幅（日本語・英語、太字、影）。
4. `show_dialog` での画面移動と、`after_action: none` での送り直しの体感（ちらつき）。
5. ツールチップの表示、ページ送りなどの送り直しの速さ。
6. 「Unicodeフォントを強制」をオンにしても崩れないこと。

プラグインなしで試す方法: リソースパックを有効にし、データパックをワールドに入れて `/reload`、`/dialog show @s dcg:top_menu`。

## 9. エディターの使い方（引き継ぎ先から触る場合）

- 起動: `node server.mjs`（`http://127.0.0.1:4320/`）。初回は `node tools/fetch-vanilla.mjs`。
- CLI: `node cli.mjs help`。画面の一覧は `node cli.mjs status`、書き出しは `node cli.mjs export`。
- 見本の作り直し: `examples/*_mock.mjs`。例: `node examples/top_menu_mock.mjs --replace > ops.json` の後に `node cli.mjs batch ops.json`。
- 配色: テーマの色の名前（`@panel.bg` など）で参照している。デザイナー向けの説明は `docs/theme.md`。
- 自作アイコンの候補: `docs/icon-candidates.md`。

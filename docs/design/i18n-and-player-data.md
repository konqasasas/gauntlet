# 多言語メッセージ基盤・プレイヤーデータ

## 1. 多言語メッセージ基盤

### 現状

UIの文言は日本語でJavaに直書きされている（約300行。編集GUI 約190、参加者モード 約60、チャット翻訳 約17）。`Icons.tell` などでプレーンテキストを送っている。

### 方針

- 対応言語は **日本語・英語**。他の言語は言語ファイルを足せば追加できる。
- 全文言を言語ファイルのキーへ移す。**参加者向けは英訳まで行い、運営の編集GUIはキー化のみ**（当面は日本語だけ。英語の表示では日本語に戻して出す）。プレイヤー制作を解禁するときに英訳する。
- Adventureの `translatable`（クライアント側の翻訳）は使わない。GUIのアイテム名・説明文が訳されないことと、言語がクライアント設定に固定されることが理由。サーバー側で、プレイヤーごとに組み立てて送る。

### 言語ファイル

- `src/main/resources/lang/ja.yml`、`en.yml`。値はMiniMessage形式（Paper同梱の5.2）。装飾と名前付き引数を使う。

  ```yaml
  play.goal.clear: "<green>クリア！</green> <time>（自己ベスト <diff>）"
  ```

- キーは `機能.画面.項目`（例：`play.fail.lava`）。
- `plugins/ParkourGauntlet/lang/` に同名ファイルを置くと同梱分を上書きする。`/gauntlet reload lang` で再読み込みする（再ビルド不要）。

### API（案）

| メソッド | 用途 |
|---|---|
| `Msg.send(player, key, args...)` | チャットへ送る |
| `Msg.text(player, key, args...)` | Componentを返す（GUIのアイテム名など） |
| `Msg.lore(player, key, args...)` | 複数行の説明文 |

- タイム（`12.35s`）などの書式は共通部品にする。
- 文言が見つからないとき：選んだ言語 → 英語 → 日本語 → キー名の順に探す。足りないキーは起動ログに1回だけ警告する。

### 検査

JUnitで、ja / en のキーの集合と、各キーの引数名が一致することを確認する（編集GUIのキーは英語なしを許可する）。

### 言語設定

UI言語と翻訳先は**別々の設定**にする。

| 設定 | 初期値 | 変更 |
|---|---|---|
| UI言語 | クライアントの言語（ja・en以外なら英語） | `/lang <ja\|en\|auto>`（設定GUIができたらそちらへ） |
| 翻訳先 | 未設定ならUI言語に合わせる | `/translate lang …`（現行どおり） |

設定していない人は「1つの設定」と同じように動く。

## 2. プレイヤーデータ

### データの種類

| 種類 | 中身 | 保存 |
|---|---|---|
| プロフィール | UUID、最後の名前、初参加、最終ログイン、累計プレイ時間。将来はランク・解放状況・称号・フレンド | SQLite（永続） |
| 個人設定 | UI言語、翻訳先、自動翻訳、表示のオン／オフなど | SQLite（永続） |
| 試行ログ | 本番の挑戦1回ごとの結果 | SQLite（追記のみ） |
| 一時状態 | CP・足場の履歴（`state.json`）、参加中の復帰用（`participants.json`） | 今のままのJSONファイル |
| 集計 | ベスト、到達率、挑戦回数、ランキング | 試行ログから作る。保存しない |

### 保存方式：SQLite

- ドライバは `plugin.yml` の `libraries:` に `org.xerial:sqlite-jdbc` を書き、Paperに起動時に取得させる（JARに同梱しない）。Paperには同梱されていない（MySQLドライバは同梱）。
- `plugins/ParkourGauntlet/gauntlet.db`。WALモード。書き込みは専用の1スレッドで行い、メインスレッドを止めない。
- 保存処理は `PlayerDataStore` インターフェースの裏に置く。サーバーを複数台にする場合はMySQLへ差し替える。
- `AsyncPlayerPreLoginEvent` で読み込み、オンライン中はメモリに置く。

### テーブル（案）

```
schema_version(version)
players(uuid PK, name, first_seen, last_seen, playtime_ms)
player_settings(uuid, key, value, PK(uuid, key))
runs(id PK, course, rev, player, result, reason, ticks, wall_ms, progress,
     started_at, ended_at, x, y, z, lane, plugin)
  INDEX(course, rev, result, ticks), INDEX(player)
```

- `result`：`CLEAR` / `FAIL` / `RETIRE` / `QUIT`（途中のログアウト・退出）/ `INVALID`（計測不能）。
- `reason`：`LAVA` / `OUT` / `TIMEOUT` / `DEATH`（FAILのとき）。
- `progress`：その挑戦での到達率の最高値（0〜1）。ルート線のないコースでは null。→ [scoreboard.md](scoreboard.md)
- `lane`：レーン方式の導入後に使う。
- 練習は記録しない（現行どおり）。

### 個人設定

- key-value 形式なので、項目はテーブルを変えずに追加できる。
- コード側では項目ごとに型・初期値・選べる値を `Setting` として定義し、読み書きはそれを通す。設定GUIは定義の一覧から組み立てる。
- 項目の一覧は [hotbar-and-settings.md](hotbar-and-settings.md)。
- 設定以外の小さな記録（操作案内の表示回数など）もプレイヤーデータに持つ。

### コースの版番号（rev）

- コースに `rev` を持たせ、遊び方が変わる編集で1つ上げる。対象は範囲、スタート、ゴール、トリガー、初期装備、制限時間、失敗条件、ルート線。名前の変更や状態の切り替えでは上げない。
- 記録・ベスト・ランキングは版ごとに分ける。今はコースを作り直しても古いベストが残る。

### 移行

初回起動時に `records.jsonl` と `chat-prefs.json` をDBへ取り込み、元ファイルは `.migrated` を付けた名前に変えて残す。旧記録の `rev` は 0、`progress` は null とする。

## 実装の順番（案）

1. 未コミットのチャット翻訳をコミットする
2. `Msg` 基盤、ja / en の言語ファイル、キー照合テスト
3. 既存の文言をキーへ移す
4. SQLiteの土台（スキーマ管理・書き込みスレッド）と `PlayerDataStore`
5. `runs` への切り替え、`rev` の導入、旧ファイルからの移行
6. `/lang` の追加、翻訳先の初期値をUI言語に合わせる

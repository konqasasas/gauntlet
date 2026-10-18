# 開発・運用メモ

エディターを直す人（人でも Claude でも）向けの決まりごとと、今の状態。デザイナー向けの使い方は [designer-guide.md](designer-guide.md)、プラグインとの約束は [plugin-handoff.md](plugin-handoff.md)。

更新: 2026-10-18（gauntlet リポジトリへの移動時の引き継ぎから）

## 今の状態

- もとは別リポジトリ `C:\mc\dialog-custom-gui`。2026-10-18 に履歴ごと gauntlet の `dialog-editor/` に移した（移動前の最後のコミットは `beb3652`）。
- デザイナー向けの改修（[designer-roadmap.md](designer-roadmap.md)）は段階A〜Eまですべて完了。データの版は **schemaVersion 6**。

  | 段階 | 内容 |
  |---|---|
  | A | 基本操作（複数選択・整列・グループ・コピー・文字スタイル） |
  | B | 状態の編集 |
  | C | 9分割画像 |
  | D | 部品（マスターと使用箇所。16画面を部品で作り直し済み） |
  | E | その場の警告（`lib/lint.mjs`、キャンバス上の印） |

- 本番では VPS 上で動かし、`https://catenaonline.net/dialog/`（パスワード付き）で使う。**VPS のエディターのデータが正本**で、`deploy.ps1 -Menu -FromEditor` で本番に出すときに、リポジトリの `data/project.json` と `config/menu/` へ写す。構築と運用は gauntlet の [docs/VPS_SERVER.md](../../docs/VPS_SERVER.md) の「メニュー」。

## 作業の決まり

- **`data/project.json` は直接書き換えない。** エディターを起動した状態で `node cli.mjs ...` か `/api/ops` を使う（検証・Undo・ブラウザーへの同期が保たれる）。
- **`examples/*_mock.mjs` は気軽に流さない。** 流すと画面が作り直され、エディターでの手作業（デザイナーの作業を含む）が上書きされる。デザイナーが触り始めた今は `project.json` が正本。
- `lib/` を変えたら `server.mjs` を再起動する（古いコードのまま動き続けるため）。
- 変更したら必ず `npm test`（28件）。**テストは `data/vanilla`（ゲーム内フォントの資源）を使う**ので、ないと一部が失敗する。git には入れないので、新しく取り出した場所では先に `node tools/fetch-vanilla.mjs` を流す。client.jar が手元にない環境（GitHub Actions・VPS）では `--download-jar` で Mojang 公式の配布から取得する（SHA-1 を照合、jar は保存しない）。GitHub Actions は取得した資源をキャッシュして使う。
- 見た目に関わる変更のあとは `node cli.mjs audit`（文字のインク基準の中央ずれ・はみ出し・重なり）。
- 内部の作り（展開・書き出し）を変えたときは、変更前後の `project.json` を比べ、画面ごと・状態の値ごとに画素・文字・クリック範囲が変わっていないことを確かめる。

  ```powershell
  node tools/compare-projects.mjs <前の project.json> <後の project.json>
  ```

- Node.js は `C:\Program Files\nodejs`（PATH にないことがある）。
- ユーザーに見える文はすべて日本語（途中報告・タスクリスト・確認のボタンも）。

## Minecraft 26.3 クライアントの癖（書き出しの前提）

実機で確かめ済み。崩すと実機で壊れる。詳しい経緯は [plugin-handoff.md](plugin-handoff.md) の 7.5・7.6。

- 本文の `plain_message` は FocusableTextWidget で、左右に 4px ずつ余白がある。JSON の `width` は「本文の幅 + 8」にする。
- クリック判定の範囲は、行のうち**実際に描かれた文字**だけで決まる（空白の文字は含まれない）。範囲内では、カーソルの下で**最後に描かれた**クリックできる文字が勝つ。
  - 対策: 各行の両端に、ほぼ透明な 1px の EDGE 文字（`dcg:space` の U+E020）を置く。クリック用の空白を先に出し、文字自体にはクリックを付けない。
  - `lib/simulate.mjs` はこの規則を再現している。
- ゲーム内フォントの文字は 9px 行に置かれる（y は 9 の倍数）。クリック範囲も 9px 行単位。
- NBT には null がないので、payload に null があるとデータパックの読み込みが落ちる（`withoutNulls` で取り除いている）。
- Dialog のレジストリ（データパック）は、サーバーの起動時（と `minecraft:reload`）にしか読み直されない。
- 状態で変わる見た目は、`insertion` に `dcg:state:<条件>` の目印を付けた重ね絵で出す。描いたあと元の位置に戻るので、行の幅は変わらない。
  - 条件の書き方は `key=value` / `key!=value`。`&` でつなぐとすべて満たすとき。
  - プラグインの `DialogTemplates` が、プレイヤーの値に合わない部品を取り除く。

## 残っていること・気付いたこと

- **段階D・Eの書き出しは、まだ試験用のサーバーに入れていない。** 今の `config/menu/`（本番に出すメニュー）は、それより前の書き出し（r149）。D・E のあとの書き出しで、実機の見た目が変わらないことを確かめる必要がある。
- 設定画面で `translate_lang=vi`（ベトナム語）のとき、文字の上の記号が枠から 2px はみ出す（`audit` で検出済み・未対応）。
- **リソースパックの読み込みを待たずにメニューが開く。** [plugin-handoff.md](plugin-handoff.md) の 5 では「読み込み完了（SUCCESSFULLY_LOADED）を待ってから画面を開く」としているが、プラグインの今の作り（参加時にパックを送る `ResourcePacks` と、`/menu` で開く `DialogMenu`）は待っていない。読み込み前に開くと画像タイルや空白フォントがなく、レイアウトが崩れる。
- VPS でのログインは nginx のパスワード（`gauntlet-dialog-user`）に決まった。**同時編集**（複数人が同時に開いたとき）の扱いは決めていない。API は revision による競合検出を持っているので、片方の編集が拒否されることはあっても、黙って上書きされることはない。
- `tools/fetch-vanilla.mjs` の client.jar の候補に、CurseForge の既定の場所（`%USERPROFILE%\curseforge\minecraft\Install\versions\26.3\26.3.jar`）を足した。前の候補 `C:/mc/dialog-gui/research/` はもうない。候補にないときは `--download-jar` で公式の配布から取る。
- 移動前の作業ツリーにあった `data/project.json` の変更と `data/assets/` は、ユーザーが試しにいじったもの。**コミットしない。**

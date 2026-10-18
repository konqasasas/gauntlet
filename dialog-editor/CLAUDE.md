# Dialog Custom GUI

Minecraft Java 26.3 / Paper 向け、Dialog + リソースパックのGUIエディター。core shader は使用禁止。

- GUIを編集するときは `data/project.json` を直接書き換えず、エディター起動中に `node cli.mjs ...`（または `/api/ops`）を使う。検証・Undo履歴・ブラウザー同期が保たれる。使い方は `node cli.mjs help`、操作の一覧は `node cli.mjs schema`。
- 見た目の確認は `node cli.mjs preview <画面> --mode compiled -o <png>` でPNGを出して読む。
- 編集操作は `lib/model.mjs`（UI/API/CLI共通）、出力方式は `lib/compile.mjs`、クライアント規則の再現は `lib/simulate.mjs`。
- 変更後は `npm test`。`lib/` を変えたら `server.mjs` を再起動する（古いコードのまま動き続ける）。
- 内部の作り（展開・書き出し）を変えたら `node tools/compare-projects.mjs <前の project.json> <後の project.json>` で、画面・状態ごとに画素・文字・クリック範囲が変わっていないか確かめる。
- `examples/*_mock.mjs` は流さない（画面が作り直され、エディターでの手作業が上書きされる）。今は `data/project.json` が正本で、本番では VPS のエディターのデータが正本。
- 今の状態・残課題・クライアントの癖の要約は docs/maintenance.md。
- Node は `C:\Program Files\nodejs`（PATHに無い場合あり）。
- 文字: render=game（ゲーム内フォント、y は9の倍数）が既定。見出しは render=baked + pixel=true で焼き込む（英字だけなら 5x5 Pixel、日本語が入るなら美咲ゴシック。言語ごとに状態で出し分ける）。ゲーム内フォントの資源は `node tools/fetch-vanilla.mjs` で data/vanilla/ に用意する。
- 私用領域の文字（space フォント等）はコード中で数値（0xE000 など）で扱う。ツール入力に書いたエスケープ表記が実際の文字に変換されることがあるため。
- Gauntlet の画面の見本は examples/*_mock.mjs（共通部品 ui_kit.mjs、外枠 FRAME 452×216）。プラグイン向けの引き継ぎは docs/plugin-handoff.md。
- 見た目を変えたら `node cli.mjs audit` で点検（文字のインク基準の中央ずれ・はみ出し・重なり）。UIの記号はフォントの文字ではなく ui_kit の ICONS（ドット絵）を使う。

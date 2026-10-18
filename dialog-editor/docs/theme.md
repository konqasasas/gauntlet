# テーマ（色）の変え方

エディターの画面は、色をすべて「テーマの色の名前」（例: `@panel.bg`）で参照しています。テーマの色を変えると、その名前を使っている全画面の色が一度に変わります。配置や部品の形には触れずに、配色だけを差し替えられます。

## 変える方法

1. **エディター**: 右側の欄で画面の要素を何も選んでいない状態にし、「テーマ（色）」の欄で色を変えます。変更はすぐプレビューに反映され、Undo もできます。
2. **JSONファイル**: 「JSONを書き出す」で `theme-default.json` を保存し、色を書き換えて「JSONを読み込む」で戻します。同じ名前の色は上書き、ない名前は追加されます。
3. **CLI**:

```bash
node cli.mjs theme
node cli.mjs theme set button.on.face=#d9822bff control.on=#d9822bff
node cli.mjs theme export -o theme.json
node cli.mjs theme import theme.json
```

色は `#RRGGBB` か `#RRGGBBAA`（AA は不透明度、ff で不透明）です。

## 注意

- ゲーム内フォントの文字（本文の文字やボタンのラベル）は、Minecraft の仕様で**不透明度を使えません**。文字色の AA は無視されます。
- コースのサムネイルなど、中身そのものの色はテーマに含めていません。
- 新しい色を足したい場合は「＋ 色を追加」か `theme set` で名前を作り、要素の色欄で `@名前` を選びます。使われている色は削除できません。
- 見本の画面を作り直しても（examples/*_mock.mjs）、テーマの色は上書きされません。

## 色の一覧（初期値）

### 画面

| 名前 | 初期値 | 使っている場所 |
|---|---|---|
| `screen.bg` | `#1b1f27f0` | 本文全体の背景 |

### パネル

| 名前 | 初期値 | 使っている場所 |
|---|---|---|
| `panel.bg` | `#232834ff` | 左パネル・内容欄の背景 |
| `panel.border` | `#3b4252ff` | パネルの枠線 |

### 見出し帯

| 名前 | 初期値 | 使っている場所 |
|---|---|---|
| `section.bg` | `#3b4252ff` | 見出し帯（並べ替え・言語など）の背景 |
| `section.text` | `#f4e7b0ff` | 見出し帯の文字 |

### 英語見出し

| 名前 | 初期値 | 使っている場所 |
|---|---|---|
| `heading.text` | `#f4e7b0ff` | 画面上部の英語見出し（COURSE SELECT など） |
| `heading.shadow` | `#5a4a1aff` | 英語見出しの影 |

### 文字

| 名前 | 初期値 | 使っている場所 |
|---|---|---|
| `text.main` | `#ffffffff` | 通常の文字 |
| `text.body` | `#c9ced8ff` | 説明文 |
| `text.sub` | `#8a93a3ff` | 補足・薄い文字（条件: なし、OFF など） |
| `text.faint` | `#5c6370ff` | さらに薄い文字 |
| `text.accent` | `#f2c94cff` | 強調の数字（クリア数など） |

### ボタン

| 名前 | 初期値 | 使っている場所 |
|---|---|---|
| `button.face` | `#c6c6c6ff` | ボタンの面 |
| `button.hi` | `#ffffffff` | ボタンの上・左の明るい線 |
| `button.shadow` | `#7a7a7aff` | ボタンの下・右の暗い線、区切り線 |
| `button.text` | `#2b2b2bff` | ボタンの文字 |
| `button.on.face` | `#3b6fe0ff` | 選択中のボタン・一覧の行の面 |
| `button.on.hi` | `#7aa2ffff` | 選択中のボタンの明るい線 |
| `button.on.shadow` | `#1d3e9cff` | 選択中のボタンの暗い線 |
| `button.on.text` | `#ffffffff` | 選択中のボタンの文字 |
| `button.danger.face` | `#c25b5bff` | 危険な操作（閉じる・初期化）のボタンの面 |
| `button.danger.hi` | `#e89a9aff` | 危険なボタンの明るい線 |
| `button.danger.shadow` | `#6e2a2aff` | 危険なボタンの暗い線 |
| `button.danger.text` | `#ffffffff` | 危険なボタンの文字 |

### スイッチ・スライダー

| 名前 | 初期値 | 使っている場所 |
|---|---|---|
| `control.outline` | `#000000ff` | ボタン・スイッチ・スライダーの外枠 |
| `control.track` | `#6f6f6fff` | スイッチ（OFF）・スライダーの溝 |
| `control.track-shadow` | `#4a4a4aff` | 溝の上端の影 |
| `control.on` | `#3b6fe0ff` | スイッチ（ON）の溝・スライダーの塗り |
| `control.on-shadow` | `#1d3e9cff` | ON の溝の影 |
| `control.on-text` | `#7aa2ffff` | スイッチ横の「ON」の文字 |

### コースカード

| 名前 | 初期値 | 使っている場所 |
|---|---|---|
| `card.bg` | `#262c37ff` | コースカードの背景 |
| `card.border` | `#4a5466ff` | コースカードの枠線 |

### クリア済みの印

| 名前 | 初期値 | 使っている場所 |
|---|---|---|
| `badge.clear.bg` | `#2e7d32ff` | クリア済みの印の背景 |
| `badge.clear.border` | `#0b3d0eff` | クリア済みの印の枠 |

### お気に入り

| 名前 | 初期値 | 使っている場所 |
|---|---|---|
| `fav.on.bg` | `#8e2a4aff` | お気に入り（登録済み）の背景 |
| `fav.on.border` | `#4a0f22ff` | お気に入り（登録済み）の枠 |
| `fav.on.icon` | `#ff9ab8ff` | お気に入り（登録済み）の♥ |
| `fav.off.bg` | `#1b1f27c0` | お気に入り（未登録）の背景 |
| `fav.off.border` | `#4a5466ff` | お気に入り（未登録）の枠 |
| `fav.off.icon` | `#8a93a3ff` | お気に入り（未登録）の♡ |


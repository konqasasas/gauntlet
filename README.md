# Parkour Gauntlet

コース制作を試すための運営用プラグイン。Paper 26.3 build 166 / Java 25を使用します。
Javaパッケージは `konqasasas.gauntlet`。今回の実装にはゴースト・リプレイ・参加者システムは含みません。

## 使い方

OPで `/gauntlet kit` を実行すると、次の専用アイテムを受け取れます。

| アイテム | 操作 | 動作 |
|---|---|---|
| アメジストのかけら | Q（ドロップ） | 自分のCPを1個保存。位置・yaw・pitchを保存し、アイテム自体は落とさない |
| アメジストのかけら | 右クリック | 自分の設置用ブロックを片付け、CPへ移動。視点を戻し、速度・落下距離・スプリント・飛行状態をリセット |
| スライムボール | 右クリック | Adventure / Creative を切り替える。初期モードが別の場合はCreativeになる |
| 磨かれた安山岩 | 通常の設置操作 | 専用の実ブロックを設置。手持ちは64個を維持 |

CPは接地している通常の足場でのみ設定できます。空中・専用設置ブロックの上では設定できません。通常の磨かれた安山岩は設定可能です。
CP未設定の右クリックでは何も消しません。TPが拒否された場合も足場を残します。
CP設定成功時は `minecraft:entity.experience_orb.pickup`、ゲームモード切替時は `minecraft:block.note_block.pling` を操作した本人に鳴らします。音量・ピッチは1、音カテゴリはプレイヤーです。CP復帰時は鳴らしません。

専用ブロックはAdventureでも全ブロックを設置対象にできます。専用ブロックの側面にも連続設置できます。
設置距離、プレイヤーとの重なり、設置先の空き、チェスト等の操作ブロックに対するスニーク操作は通常のMinecraftの制約に従います。
通常アイテムとは内部タグで区別し、普通の安山岩やコース用ブロックはリトライで消しません。
`/gauntlet kit` の再実行は既存の専用アイテムを更新し、重複分を整理します。通常アイテムは保持します。

## ローカルサーバー

以前の試作プロジェクト全体は `C:\mc\test-gauntlet` に移しました。新プロジェクトは `C:\mc\gauntlet` です。
新しいサーバーには以前のワールドと設定をコピーしました。試作プラグイン・リソースパック配布機能は移植していません。
自分の接続先は **`127.0.0.1:25575`**、Minecraft Java 26.3です。既存のOP・ホワイトリストを引き継いでいます。身内向けにLAN接続を受け付ける設定にしました。公開の残りの手順は [docs/FRIENDS_SERVER.md](docs/FRIENDS_SERVER.md) を参照してください。
旧サーバーと新サーバーは同じポートを使用するため、同時起動しないでください。

PowerShellでプロジェクトディレクトリから実行します。実行ポリシーは起動するプロセスだけに指定し、システム設定は変更しません。

```powershell
cd C:\mc\gauntlet
# ソースのビルドのみ
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 build
# サーバー停止中にビルド・配置
powershell -NoProfile -ExecutionPolicy Bypass -File .\prepare-server.ps1
# サーバー起動
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-server.ps1
```

JAR: `build\libs\parkour-gauntlet-0.1.0.jar`

サーバー: `run\paper-26.3`、配置先: `run\paper-26.3\plugins\parkour-gauntlet.jar`

停止はサーバーコンソールの `stop`。この開発環境では次のローカル補助ツールでも停止できます。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\verify-server.ps1 -Action stop
```

## 保存と制約

CPと設置履歴は `run\paper-26.3\plugins\ParkourGauntlet\state.json` に保存します。
ログアウト・サーバー停止時に専用足場を片付け、起動時に残った履歴も回収します。CPは維持します。
設置前のBlockDataを記録し、リトライ時に戻します。記録後に別種類のブロックへ編集した場所は上書きしません。
同じ種類・同じBlockDataへの再編集は履歴と区別できません。専用足場を永久コースに転用する場合は、先にリトライで片付けてから普通のブロックで設置してください。

現段階では**実ワールドの足場**を使います。複数レーンの割り当てや他プレイヤーのゴースト表示は未実装です。
ピストン・爆発等で足場を移動／消去する運用は未対応です。CPの保存後に周囲の地形を変更した場合の安全なTP先の再選定も未実装です。
設置履歴はブロックごとに同期保存します。大人数・高速連続設置の負荷試験は今後の対象です。

権限は `gauntlet.admin`（既定OP）。他の保護プラグインの設置制限を強制解除する処理はありません。
サーバー全体の移動検証・飛行制限を緩める設定変更はしていません。

検証結果は [docs/VALIDATION.md](docs/VALIDATION.md)、実機テストは [docs/TESTING.md](docs/TESTING.md) を参照してください。

## Gitで保護する範囲

ソース、テスト、Gradleラッパー／依存ロック、補助スクリプト、手順書を管理します。
ワールド・サーバー設定・OP／ホワイトリスト・CPデータ等の `run/`、生成JAR・ログ等の `build/`、Gradleキャッシュは対象外です。
既存のワールドはディスクに保持しますが、Gitからは復元できません。
新しいチェックアウトでも旧試作を参照せず、`prepare-server.ps1` で指定ビルドのPaperを取得できます。Minecraft EULAへの同意と運用設定は別途必要です。

変更を保存する場合:

```powershell
git status
git diff
# 変更したファイルを明示して追加する
git add src/main/java/konqasasas/gauntlet/ParkourGauntletPlugin.java
git commit -m "変更内容"
```

ローカルGitの履歴は同じPC上にあります。PCの故障にも備えるには、後で別ディスクへのバックアップか非公開リモートへの保存が必要です。

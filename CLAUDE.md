# Catena Online（gauntlet リポジトリ）

Minecraft Java 26.3 / Paper のパルクールサーバー「Catena Online」。プラグイン本体（`src/`、Java・Gradle）、メニューを作る Dialog エディター（`dialog-editor/`、Node.js）、Web サイト（`web/`）、本番の構築・反映スクリプト（`deploy/`、`deploy.ps1`）が入っている。

- ユーザーに見える文はすべて日本語（途中報告・タスクリスト・確認のボタンも）。
- ビルドとテスト: `powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 build`。Dialog エディターは `dialog-editor/` で `npm test`（`data/vanilla` が必要。dialog-editor/CLAUDE.md）。
- 人向けの詳しい手順は [docs/VPS_SERVER.md](docs/VPS_SERVER.md)。この節はその要約と、AI が作業するときの決まり。

## 本番サーバー

| 項目 | 値 |
|---|---|
| VPS | XServer VPS クラウド、Ubuntu 24.04、4GB、`153.115.7.32` |
| 接続先 | Minecraft `mc.catenaonline.net:25565`、Web `https://catenaonline.net`（`/editor/` 表示エディター、`/dialog/` Dialog エディター〔パスワード付き〕、`/packs/` リソースパック） |
| SSH | Windows の `~/.ssh/config` の `gauntlet-vps`（ユーザー `konqa`、鍵のみ。root は SSH 不可、管理画面のコンソールのみ） |
| サーバー | `/opt/gauntlet/server`、systemd の `gauntlet.service`（ユーザー `minecraft`）。コンソールは `gauntlet-cmd <コマンド>`、ログは `journalctl -u gauntlet -f` |
| Dialog エディター | `/opt/dialog-editor`、`gauntlet-dialog.service`（127.0.0.1:4320）。**本番のメニューの正本は VPS のエディターのデータ** |

## AI が本番を触るときの決まり

- **AI は VPS に SSH できない**（鍵にパスフレーズがある）。VPS 上の操作と `deploy.ps1` の実行はユーザーに頼み、コマンドを1つずつ案内する。AI が自分で確かめられるのは外からの確認だけ（`curl`、ポートの到達、Minecraft のサーバー一覧の問い合わせ〔Server List Ping〕で説明文や人数を見る）。
- **VPS へ `deploy/` を送るときは `git archive` の写しを使う。** 作業ツリーは改行が CRLF になっていることがあり、そのまま scp すると Linux で動かない。

  ```powershell
  git archive --format=tar -o $env:TEMP\deploy.tar HEAD deploy web
  scp $env:TEMP\deploy.tar gauntlet-vps:~/
  ```

  VPS では `tar -xf ~/deploy.tar -C ~/setup && rm ~/deploy.tar`（`~/setup/deploy` に展開される）。

- **作業ツリーにほかの作業の未コミットの変更があるときは、それを本番に混ぜない。** `deploy.ps1` は作業ツリーをそのままビルドするので、`git worktree add --detach <一時フォルダ> HEAD` でコミット済みの状態を取り出してビルドし、`build\libs\parkour-gauntlet-0.1.0.jar` に置いてから `deploy.ps1 -SkipBuild` を頼む。反映の前に `git status` を必ず見る。
- 反映の前にテストを通す。本番の VPS エディターのデータを変える前は、`/api/project` をバックアップする。
- `deploy/bin/` のスクリプトを直したら、VPS の `/usr/local/bin` にも入れ直す必要がある（setup.sh を流し直すか、`sudo install -m 0755 ~/setup/deploy/bin/<名前> /usr/local/bin/<名前>`）。

## 何を変えたら、どう反映するか

| 変えたもの | 反映のしかた（Windows で、リポジトリ直下から） | 再起動 |
|---|---|---|
| プラグイン（`src/`） | `deploy.ps1`（ビルド→転送→差し替え）。または GitHub Actions の Run workflow で `plugin` | あり（遊んでいる人は切断される） |
| 表示設定（`config/display.yml`、`config/lang/*.yml`。Web の表示エディターで作る） | `deploy.ps1 -Display`。手元のサーバーは `-Display -Local` | なし |
| 同梱の `display.yml` や言語ファイル（`src/main/resources/`） | プラグインと同じ（`deploy.ps1`） | あり |
| メニュー（VPS の Dialog エディターで編集・書き出し済み） | `deploy.ps1 -Menu -FromEditor`（本番に入れ、`config/menu/` と `dialog-editor/data/` に写す。差分をコミット） | なし |
| メニューを git の `config/menu/` から戻す | `deploy.ps1 -Menu`、または Run workflow で `menu`。手元は `-Menu -Local` | なし |
| Web サイト（`web/`） | `deploy.ps1 -Web`、または Run workflow で `web` | なし |
| Dialog エディターのコード（`dialog-editor/`） | `deploy.ps1 -DialogEditor`（最後のコミットを送る。編集データは消えない） | エディターのみ |
| DiscordSRV の設定 | VPS で `sudo bash ~/setup/deploy/discord.sh configure` / `plain-joins` など | なし〜あり |
| リソースパック | メニューのパックは `-Menu` で、BetterModel のパックはサーバーが作る。プラグインが10秒ごとに変化を見て、参加中の人にも送り直す | なし |

- `deploy.ps1` は SSH を何度も使う。パスフレーズを何度も聞かれるときは ssh-agent（`Start-Service ssh-agent`、`ssh-add $HOME\.ssh\gauntlet-vps`）を勧める。
- GitHub Actions は main への push でビルドとテストだけ行う。反映は Run workflow のボタン（`plugin` / `web` / `both` / `display` / `menu`）で、ユーザーが押す。

## VPS の Dialog エディターを AI が操作するとき

ユーザーに SSH の通り道を開いてもらうと、手元の `http://127.0.0.1:4330` から VPS のエディターの API を使える（Origin を付けない Node の fetch なら編集もできる）。

```powershell
ssh -N -L 4330:127.0.0.1:4320 gauntlet-vps
```

- 台帳風メニューの作り直し: `node dialog-editor/proto/make-art.mjs --url http://127.0.0.1:4330`、`build-parts.mjs` も同じ、`build-screens.mjs --url http://127.0.0.1:4330 --live`。**同じ名前の画面は作り直される**ので、デザイナーが直した画面を上書きしないか先に確かめる。
- 書き出し: `GET /api/project` の `revision` を付けて `POST /api/export`。結果は `GET /api/exports/latest/report.json`（`errors` が空なら書き出せている）。
- そのあと、ユーザーに `deploy.ps1 -Menu -FromEditor -Force` を頼む（手元の `dialog-editor/data` を上書きするので、手元に大事な変更がないか先に確かめる）。

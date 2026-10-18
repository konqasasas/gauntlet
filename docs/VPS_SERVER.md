# XServer VPS での運用

今は運営だけが入れるホワイトリスト運用にして、後から一般公開へ切り替える前提の構成です。ファイルは `deploy/` と `deploy.ps1` にあります。

## 構成

| 項目 | 内容 |
|---|---|
| OS | Ubuntu 24.04 LTS（XServer の Minecraft 用アプリイメージは使わない） |
| Java | Eclipse Temurin 25（apt で自動更新） |
| サーバー | `/opt/gauntlet/server`、専用ユーザー `minecraft`、systemd の `gauntlet.service` |
| ポート | TCP `25565`（ポート変換は不要）。SSH は 22（鍵認証のみ） |
| 守り | XServer のパケットフィルター、ufw、fail2ban、OS のセキュリティ更新の自動適用 |
| バックアップ | 毎日 05:00 に `/opt/gauntlet/backups` へ保存し、14日分を保持 |
| 反映 | Windows で `deploy.ps1` を実行（ビルド → 転送 → 再起動） |

メモリは `-Xmx` に OS と JVM 自体の分（約1.5GB）を足した量が必要です。運営だけなら 4GB プラン、一般公開時は人数を見て 8GB 以上が目安です。LibreTranslate を同じ VPS で動かす場合は、さらに 2〜4GB を見込みます。

## 1. SSH 鍵を作る（Windows・初回のみ）

```powershell
ssh-keygen -t ed25519 -f $HOME\.ssh\gauntlet-vps
```

パスフレーズを付けます。公開鍵は `$HOME\.ssh\gauntlet-vps.pub` です。XServer のパネルで鍵を作成して秘密鍵をダウンロードする方法でも構いません。

## 2. XServer で VPS を作る

1. OS に **Ubuntu 24.04** を選び、「SSH Key」に上の公開鍵を登録して作成します。
2. パネルの **パケットフィルター設定** で、SSH（22）・**TCP 25565**（Minecraft）・**TCP 80 / 443**（Web サイト）だけを許可します。Minecraft 用や Web 用の項目があればそれを選び、なければ手動で追加します。
3. VPS の IPv4 アドレスを控えます。

SSH が通らなくなったときは、パネルの「コンソール」からログインできます。

## 3. Windows の SSH 設定

`$HOME\.ssh\config` に次を追加します（`konqa` は VPS 上に作る管理用ユーザー名。任意）。

```text
Host gauntlet-vps-root
    HostName <VPSのIPv4>
    User root
    IdentityFile ~/.ssh/gauntlet-vps

Host gauntlet-vps
    HostName <VPSのIPv4>
    User konqa
    IdentityFile ~/.ssh/gauntlet-vps
```

## 4. 初回構築

```powershell
cd C:\mc\gauntlet
scp -r deploy web gauntlet-vps-root:/root/
ssh gauntlet-vps-root
```

VPS 上で次を実行します。

```bash
cd /root/deploy && ADMIN_USER=konqa bash setup.sh
```

途中で `konqa` の sudo 用パスワードを聞かれます（SSH ログインには使いません）。このスクリプトは root ログインとパスワードログインを無効にします。**root のウィンドウを閉じる前に**、別のウィンドウで `ssh gauntlet-vps` が通ることを確認してください。

setup.sh は何度実行しても、ワールド・`server.properties`・`/etc/default/gauntlet` を上書きしません。

## 5. 手元のワールドと設定を移す

手元のサーバーを停止してから、Windows で固めて送ります。

```powershell
cd C:\mc\gauntlet\run\paper-26.3
tar -czf $env:TEMP\gauntlet-migrate.tgz world plugins config bukkit.yml spigot.yml commands.yml whitelist.json ops.json banned-players.json banned-ips.json
scp $env:TEMP\gauntlet-migrate.tgz gauntlet-vps:/tmp/
```

VPS 上で展開します（`server.properties` は送らず、setup.sh が作った VPS 用のものを使います）。

```bash
sudo systemctl stop gauntlet
sudo tar -xzf /tmp/gauntlet-migrate.tgz -C /opt/gauntlet/server
sudo chown -R minecraft:minecraft /opt/gauntlet/server
rm /tmp/gauntlet-migrate.tgz
```

`plugins` には FastAsyncWorldEdit・Multiverse-Core・BetterModel・spark の JAR と設定も含まれます。

## 6. EULA に同意して起動

<https://aka.ms/MinecraftEULA> を読み、同意する場合は次を実行します。

```bash
sudo -u minecraft sed -i 's/^eula=false$/eula=true/' /opt/gauntlet/server/eula.txt
sudo systemctl start gauntlet
journalctl -u gauntlet -f
```

`Done` が出たら、Minecraft から `mc.catenaonline.net`（ポート省略で 25565）に接続します。

## 日常の操作

| やりたいこと | コマンド（VPS 上） |
|---|---|
| コンソールコマンド | `gauntlet-cmd whitelist add <名前>`（出力されたログも表示） |
| ログを見る | `journalctl -u gauntlet -f` |
| 起動・停止・再起動 | `sudo systemctl start / stop / restart gauntlet` |
| 状態 | `systemctl status gauntlet` |
| メモリ割り当ての変更 | `sudo nano /etc/default/gauntlet` の `JAVA_MEM` を変えて再起動 |
| 今すぐバックアップ | `sudo systemctl start gauntlet-backup` |
| バックアップの予定 | `systemctl list-timers gauntlet-backup` |

`server.properties` などサーバーのファイルを編集するときは `sudo -u minecraft nano /opt/gauntlet/server/server.properties` のように `minecraft` ユーザーで編集します。

### プラグインの更新（Windows）

```powershell
cd C:\mc\gauntlet
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1
```

ビルド（テストを含む）→ VPS へ転送 → サーバーを停止 → JAR を差し替え → 起動、の順に行います。差し替え前の JAR は `/opt/gauntlet/backups/jars` に残ります。接続先は `-Target` か環境変数 `GAUNTLET_VPS` で変えられます（既定は `gauntlet-vps`）。

### GitHub Actions から反映する

`.github/workflows/build.yml` は、main への push と Pull Request のたびにビルドとテストを行います。VPS への反映は、GitHub の **Actions → Build and deploy → Run workflow** で、反映するもの（`plugin` / `web` / `both`）を選んで実行したときだけ行います。プラグインの反映はサーバーを再起動するので、遊んでいる人がいない時間に実行します。

GitHub に預けるのは、デプロイ専用の鍵です。VPS 側で `gauntlet-ci` に制限されるため、この鍵では JAR とサイトの受け取り以外（ログインやコマンド実行）はできません。

1. Windows でデプロイ専用の鍵を作ります（GitHub が使うのでパスフレーズは付けません）。

   ```powershell
   ssh-keygen -t ed25519 -f $HOME\.ssh\gauntlet-ci -N '""' -C gauntlet-ci
   ```

2. 公開鍵を VPS に登録します。

   ```powershell
   $pub = (Get-Content $HOME\.ssh\gauntlet-ci.pub -Raw).Trim()
   ssh gauntlet-vps "gauntlet-ci --authorize '$pub'"
   ```

3. VPS のホスト鍵を控えます（`ssh gauntlet-vps` で一度接続したあとに実行します）。

   ```powershell
   ssh-keygen -F 153.115.7.32 -f $HOME\.ssh\known_hosts | Select-String -NotMatch '^#'
   ```

4. GitHub のリポジトリの **Settings → Secrets and variables → Actions → New repository secret** で、次の4つを登録します。

   | 名前 | 値 |
   |---|---|
   | `VPS_HOST` | `153.115.7.32` |
   | `VPS_USER` | `konqa` |
   | `VPS_SSH_KEY` | `$HOME\.ssh\gauntlet-ci`（`.pub` ではない方）の中身をすべて |
   | `VPS_KNOWN_HOSTS` | 手順3で表示された行をすべて |

`VPS_SSH_KEY` は秘密鍵です。GitHub の Secrets 以外には貼らないでください。鍵を取り消すときは、VPS の `~/.ssh/authorized_keys` から `gauntlet-ci` の行を消します。

## 表示設定（チャット・TAB・サイドバー・アクションバー）

名前・ランクの色・チャットの書式・TAB・サイドバーは `display.yml`、アクションバーやシステムメッセージの文言は言語ファイルで決まります。どちらも **Web エディター**（`web/editor/index.html`、公開後は `https://catenaonline.net/editor/`）で、プレビューを見ながら編集して書き出せます。

1. エディターで編集し、「書き出す」でファイルを保存します（編集内容はブラウザに自動保存されます。既存のファイルは「読み込む」で開けます）。
2. リポジトリの `config/` に置きます。

   | ファイル | 置き場所 | 中身 |
   |---|---|---|
   | `display.yml` | `config/display.yml` | 表示設定（全項目） |
   | `ja.yml` / `en.yml` | `config/lang/ja.yml` / `config/lang/en.yml` | 言語ファイルのうち変えた項目だけ。変えていない言語は置かない |

3. 手元のサーバーで試します。ゲーム内（またはサーバーのコンソール）で再読み込みすると、再起動なしで変わります。

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -Display -Local
   ```

   ```text
   /gauntlet reload display
   /gauntlet reload lang
   ```

4. 本番に反映します（サーバーは再起動しません）。

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -Display
   ```

   GitHub Actions の Run workflow で `display` を選んでも同じです。

- サーバーには `config/` にあるファイルだけが置かれます。`config/` から消したファイルはサーバーからも消え、プラグイン同梱の既定値に戻ります。
- 書式の誤り（ランクの色が13個ない、`<text>` がない など）があると、再読み込みは拒否されて前の見た目のまま残ります。その状態で再起動しても、プラグインは既定の見た目で起動します（ログに `Ignoring display.yml` が出ます）。
- エディターに埋め込んだ既定値は、テスト（`DisplayEditorDefaultsTest`）でプラグイン同梱のファイルと一致するか確かめています。同梱の `display.yml` や言語ファイルを変えたら、エディター側の既定値も合わせてください。

## Web サイト（`catenaonline.net`）

`web/` の静的ページを nginx で配信し、Let's Encrypt で HTTPS にします。証明書は certbot が自動で更新します。

1. XServer ドメインの DNS レコード設定に、A レコードを2件追加します（`mc` の A レコードはそのまま）。

   | ホスト名 | 種別 | 内容 |
   |---|---|---|
   | `catenaonline.net`（空欄） | A | VPS の IPv4 |
   | `www` | A | VPS の IPv4 |

2. パケットフィルターで TCP 80 / 443 が許可されていることを確認します。
3. VPS 上で実行します。`LE_EMAIL` には証明書の期限切れ通知を受け取るアドレスを指定します。実行すると、そのアドレスで Let's Encrypt の利用規約に同意したことになります。

   ```bash
   cd /root/deploy && sudo ADMIN_USER=konqa LE_EMAIL=<連絡先メール> bash web-setup.sh
   ```

ページを更新したら、Windows から反映します（ビルドやサーバーの再起動はしません）。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -Web
```

## Discord 連携（DiscordSRV）

ゲーム内チャットを Discord に流し（Discord からゲームへは流さない）、ボットのステータスとチャンネルトピックにオンライン人数を出し、運営用チャンネルからコンソールを操作します。

### Discord 側の準備

1. <https://discord.com/developers/applications> でアプリケーションを作り、**Bot** を開きます。
   - **Reset Token** でトークンを表示して控えます。トークンはチャットや Git に貼らず、下の `configure` で直接入力します。
   - **Privileged Gateway Intents** の **SERVER MEMBERS INTENT** と **MESSAGE CONTENT INTENT** を ON にします。
2. **OAuth2 → URL Generator** で、スコープに `bot` と `applications.commands` を選びます。権限には「チャンネルを見る」「メッセージを送信」「埋め込みリンク」「ファイルを添付」「メッセージ履歴を読む」「メッセージの管理」「チャンネルの管理」（トピック更新用）を選び、生成された URL からボットをサーバーに招待します。
3. チャンネルを3つ作ります。
   - **サーバーチャット**：全員が閲覧でき、一般メンバーは送信できない（ボットだけ送信できる）。
   - **コンソール**：運営ロールとボットだけが閲覧・送信できる。**ここに書いた内容はサーバーのコマンドとして実行されます。** 運営以外に見せないでください。
   - **サーバー監視**：運営ロールとボットが閲覧できる。チャンネルの設定 → 連携サービス → **ウェブフック** を作り、URL をコピーして控えます（下の「監視と通知」で使います。URL はトークンと同じく秘密です）。
4. Discord の設定 → 詳細設定 → **開発者モード** を ON にし、3つのチャンネルを右クリック →「ID をコピー」で ID を控えます。

### VPS 側

```bash
cd /root/deploy        # または deploy/ を送り直した場所
sudo bash discord.sh install      # DiscordSRV を検証付きで導入し、再起動（設定ファイルが作られる）
sudo bash discord.sh configure    # トークンとチャンネル ID を入力 → 設定を反映して再起動
```

`configure` で変える設定は次のとおりです（元の設定は `config.yml.bak` / `messages.yml.bak` に残ります）。

| 設定 | 値 |
|---|---|
| Discord → ゲーム | 無効（`DiscordChatChannelDiscordToMinecraft: false`） |
| チャットのイベント | Paper の `AsyncChatEvent`（コース ID などの入力待ちのチャットを Discord に流さない） |
| メンション | ゲーム内チャットから Discord のユーザー・ロールを通知しない |
| ボットのステータス | `○人がプレイ中` と `mc.catenaonline.net` を交互に表示 |
| チャンネルトピック | 人数と接続先（10分ごと。停止中は「サーバー停止中」） |
| 起動・停止の通知 | サーバーチャットに日本語で通知 |
| コンソール | `op` / `deop` / `execute` / `?` は DiscordSRV の既定どおり実行不可 |

設定ファイルは `/opt/gauntlet/server/plugins/DiscordSRV/` にあります。手で変えたときは `gauntlet-cmd discord reload`、または再起動で反映します。

## 監視と通知

| 起きたこと | 検知するもの | 通知先 |
|---|---|---|
| サーバーが異常終了した（15秒後に自動再起動） | systemd（`gauntlet-notify --exited`） | サーバー監視（Webhook） |
| 自動再起動を5回繰り返しても起動しない | systemd（`OnFailure`） | サーバー監視（Webhook） |
| サーバーが30秒以上応答しない（フリーズ） | DiscordSRV の watchdog | サーバー監視（`configure` で ID を入れた場合） |
| 毎日のバックアップが失敗した | systemd（`OnFailure`） | サーバー監視（Webhook） |
| VPS ごと止まった・ネットワークが切れた | 外部の監視サービス（下記） | サーバー監視（Webhook） |
| 通常の起動・停止 | DiscordSRV | サーバーチャット |

Webhook の URL は VPS で一度だけ登録します（入力は表示されません。テスト通知が届けば成功です）。

```bash
sudo gauntlet-notify --setup
```

### VPS ごと止まったとき（外部監視）

VPS の中の仕組みは、VPS が止まると通知できません。外から監視するサービスを1つ使います。たとえば [UptimeRobot](https://uptimerobot.com/) の無料プランでは、5分ごとのポート監視と Discord への通知が使えます（個人・非商用向け。プランの条件は登録時に確認してください）。

- 監視1：**Port** 監視、`mc.catenaonline.net`、ポート `25565`
- 監視2：**HTTP(s)** 監視、`https://catenaonline.net/`
- 通知先：Discord の連携に、サーバー監視チャンネルの Webhook URL を登録

## バックアップ

`gauntlet-backup` は稼働中なら `save-off` → `save-all flush` でワールドの書き込みを止め、`gauntlet.db` は `sqlite3 .backup` で整合性を保ったまま写します。`libraries`・`cache`・`versions`・`logs` は含めません。

VPS が壊れると中のバックアップも失われるので、ときどき手元に取得します。

```powershell
scp "gauntlet-vps:/opt/gauntlet/backups/gauntlet-*.tar.zst" C:\mc\vps-backups\
```

XServer の自動バックアップ（有料オプション）を併用すると、OS ごと戻せます。

### 復元

```bash
sudo systemctl stop gauntlet
sudo mv /opt/gauntlet/server /opt/gauntlet/server.broken
sudo install -d -o minecraft -g minecraft -m 2770 /opt/gauntlet/server
sudo tar --zstd -xf /opt/gauntlet/backups/gauntlet-<日時>.tar.zst -C /opt/gauntlet/server
sudo systemctl start gauntlet
```

Paper の `libraries` は起動時に取り直されます。動作を確認したら `server.broken` を消します。

## 一般公開に切り替えるとき

設定を変える前に、その時点のバックアップを取ります。

- **プラン**：spark（`/spark profiler`・`/spark health`）で負荷を確認し、必要ならメモリを増やします。
- **入場**：`server.properties` の `white-list=false` / `enforce-whitelist=false`、`max-players` を決めます。`online-mode=true`・`enforce-secure-profile=true` はそのままにします。
- **権限**：`ops.json` を見直し、運営以外に OP を付けないようにします。コース編集（`gauntlet.admin`）は OP 限定です。役割を分けるなら LuckPerms の導入を検討します。
- **荒らし対策**：BAN・ミュート・チャットのログ確認の手順を決めます。FAWE と Multiverse のコマンドは OP 限定であることを確認します。
- **翻訳**：MyMemory の無料枠（連絡用メール設定済みで1日50,000文字）は全員で共有します。足りなければ LibreTranslate を検討します。
- **表示**：サーバー名（`Catena Online`）と接続先（`mc.catenaonline.net`）は設定済みです。`server.properties` の `motd`（サーバー一覧の説明文）を公開向けの文にします。
- **Web サイト**：`web/index.html` の「現在は運営メンバーのみ参加できます」を書き換え、Discord の招待リンクなどを載せます。
- **Discord**：サーバーチャットの閲覧範囲と、コンソールチャンネルの権限を見直します。
- **規約**：課金要素を入れる場合は Minecraft のコミュニティ基準・利用規約を確認します。`log-ips=true` のままなら、IP を記録することを告知します。
- **バックアップ**：手元への取得を定期的に行う（または外部ストレージへ自動転送する）運用にします。

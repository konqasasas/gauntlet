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
2. パネルの **パケットフィルター設定** で、SSH（22）と **TCP 25565** だけを許可します。Minecraft 用の項目があればそれを選び、なければ手動で追加します。
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
scp -r deploy gauntlet-vps-root:/root/
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

`Done` が出たら、Minecraft から `<VPSのIPv4>`（ポート省略で 25565）に接続します。

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
- **翻訳**：MyMemory の無料枠は全員で共有します。`mymemory.email` を設定するか、LibreTranslate を検討します。
- **表示**：`config.yml` の `server.address`（スコアボード）と `motd` を正式な値にします。
- **接続先**：独自ドメインの A レコード（例：`mc.example.com`）を VPS に向けると、IP を変えても参加者の設定が変わりません。
- **規約**：課金要素を入れる場合は Minecraft のコミュニティ基準・利用規約を確認します。`log-ips=true` のままなら、IP を記録することを告知します。
- **バックアップ**：手元への取得を定期的に行う（または外部ストレージへ自動転送する）運用にします。

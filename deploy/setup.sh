#!/usr/bin/env bash
# First-time setup of a fresh Ubuntu 24.04 VPS for the Parkour Gauntlet server.
# Run as root from this directory:  ADMIN_USER=<login name> bash setup.sh
# Safe to re-run: existing server files, server.properties and /etc/default/gauntlet are kept.
set -euo pipefail

ADMIN_USER="${ADMIN_USER:?Set ADMIN_USER to the login name to create (e.g. ADMIN_USER=konqa bash setup.sh)}"
MC_PORT="${MC_PORT:-25565}"
PAPER_JAR='paper-26.3-166.jar'
PAPER_SHA256='dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd'
PAPER_URL="https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/$PAPER_JAR"
BASE=/opt/gauntlet
SERVER="$BASE/server"
HERE="$(cd "$(dirname "$0")" && pwd)"

[[ $EUID -eq 0 ]] || { echo 'Run as root.' >&2; exit 1; }
. /etc/os-release
[[ $ID == ubuntu ]] || { echo "Ubuntu only (found $ID)." >&2; exit 1; }
[[ $ADMIN_USER =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || { echo "Invalid ADMIN_USER: $ADMIN_USER" >&2; exit 1; }
# Root login is disabled below, so the admin account must be reachable by key first.
if [[ ! -s /home/$ADMIN_USER/.ssh/authorized_keys && ! -s /root/.ssh/authorized_keys ]]; then
  echo 'No SSH public key in /root/.ssh/authorized_keys. Register one before running this.' >&2
  exit 1
fi

echo '== Packages'
export DEBIAN_FRONTEND=noninteractive
apt-get update
# Keep locally modified config files (e.g. cloud-init's sshd settings) instead of prompting.
apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade
apt-get -y install ca-certificates curl gnupg ufw fail2ban unattended-upgrades sqlite3 zstd unzip

echo '== Java 25 (Eclipse Temurin)'
install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $VERSION_CODENAME main" > /etc/apt/sources.list.d/adoptium.list
apt-get update
apt-get -y install temurin-25-jdk
java -version 2>&1 | grep -q 'version "25' || { echo '/usr/bin/java is not Java 25. Check update-alternatives --config java.' >&2; exit 1; }

echo '== Accounts'
id minecraft &>/dev/null || useradd --system --user-group --home-dir "$BASE" --shell /usr/sbin/nologin minecraft
id "$ADMIN_USER" &>/dev/null || useradd --create-home --shell /bin/bash "$ADMIN_USER"
# adm: read the journal / sudo: administration / minecraft: console, logs and uploads.
usermod -aG adm,sudo,minecraft "$ADMIN_USER"
install -d -m 0700 -o "$ADMIN_USER" -g "$ADMIN_USER" "/home/$ADMIN_USER/.ssh"
if [[ ! -s /home/$ADMIN_USER/.ssh/authorized_keys ]]; then
  install -m 0600 -o "$ADMIN_USER" -g "$ADMIN_USER" /root/.ssh/authorized_keys "/home/$ADMIN_USER/.ssh/authorized_keys"
fi
if [[ $(passwd -S "$ADMIN_USER" | awk '{print $2}') != P ]]; then
  echo "Set the sudo password for $ADMIN_USER (not used for SSH login):"
  passwd "$ADMIN_USER"
fi

echo '== SSH (key only, no root login)'
cat > /etc/ssh/sshd_config.d/10-gauntlet.conf <<'EOF'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
EOF
# Ubuntu 24.04 starts sshd per connection via ssh.socket, so /run/sshd may not exist yet
# and ssh.service may be inactive; new connections read the new config either way.
install -d -m 0755 /run/sshd
sshd -t
systemctl try-reload-or-restart ssh.service

echo '== Firewall'
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw allow "$MC_PORT/tcp" comment 'Minecraft'
ufw --force enable

cat > /etc/fail2ban/jail.d/gauntlet.local <<'EOF'
[sshd]
enabled = true
maxretry = 5
findtime = 10m
bantime = 1h
EOF
systemctl enable --now fail2ban
systemctl restart fail2ban

cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

echo '== Server files'
install -d -o minecraft -g minecraft -m 2770 "$BASE" "$SERVER" "$SERVER/plugins" "$BASE/incoming"
install -d -o minecraft -g minecraft -m 2750 "$BASE/backups" "$BASE/backups/jars"
if [[ ! -f $SERVER/$PAPER_JAR ]]; then
  tmp="$(mktemp)"
  curl -fL -o "$tmp" "$PAPER_URL"
  echo "$PAPER_SHA256  $tmp" | sha256sum -c -
  install -o minecraft -g minecraft -m 0640 "$tmp" "$SERVER/$PAPER_JAR"
  rm -f "$tmp"
fi
if [[ ! -f $SERVER/server.properties ]]; then
  install -o minecraft -g minecraft -m 0660 /dev/null "$SERVER/server.properties"
  cat > "$SERVER/server.properties" <<EOF
server-ip=
server-port=$MC_PORT
online-mode=true
enforce-secure-profile=true
white-list=true
enforce-whitelist=true
enable-rcon=false
enable-query=false
management-server-enabled=false
gamemode=adventure
difficulty=peaceful
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
allow-flight=false
max-players=20
view-distance=8
simulation-distance=4
motd=Catena Online
EOF
fi
if [[ ! -f $SERVER/eula.txt ]]; then
  install -o minecraft -g minecraft -m 0660 /dev/null "$SERVER/eula.txt"
  printf '# Review https://aka.ms/MinecraftEULA\neula=false\n' > "$SERVER/eula.txt"
fi

if [[ ! -f /etc/default/gauntlet ]]; then
  # Leave about 1.5 GB for the OS, the JVM's own overhead and backups.
  total_mb=$(awk '/MemTotal/ {print int($2 / 1024)}' /proc/meminfo)
  heap_mb=$(( (total_mb - 1536) / 512 * 512 ))
  (( heap_mb < 1024 )) && heap_mb=1024
  cat > /etc/default/gauntlet <<EOF
# Heap size for the server (Xms = Xmx). Restart after changing: sudo systemctl restart gauntlet
JAVA_MEM=${heap_mb}M
PAPER_JAR=$PAPER_JAR
# G1 settings recommended for Paper (Aikar's flags)
JAVA_FLAGS=-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1
EOF
fi

echo '== Services and helper commands'
install -m 0644 "$HERE"/systemd/gauntlet.service "$HERE"/systemd/gauntlet.socket \
  "$HERE"/systemd/gauntlet-backup.service "$HERE"/systemd/gauntlet-backup.timer \
  "$HERE"/systemd/gauntlet-alert@.service /etc/systemd/system/
install -m 0755 "$HERE"/bin/gauntlet-cmd "$HERE"/bin/gauntlet-backup "$HERE"/bin/gauntlet-deploy \
  "$HERE"/bin/gauntlet-notify "$HERE"/bin/gauntlet-ci "$HERE"/bin/gauntlet-display "$HERE"/bin/gauntlet-menu \
  "$HERE"/bin/gauntlet-dialog-update "$HERE"/bin/gauntlet-dialog-user /usr/local/bin/
# Webhook for alerts; filled in by: sudo gauntlet-notify --setup
if [[ ! -f /etc/default/gauntlet-notify ]]; then
  install -o root -g minecraft -m 0640 /dev/null /etc/default/gauntlet-notify
  echo 'DISCORD_WEBHOOK_URL=' > /etc/default/gauntlet-notify
fi
sudoers="/etc/sudoers.d/gauntlet"
echo "$ADMIN_USER ALL=(root) NOPASSWD: /usr/local/bin/gauntlet-deploy, /usr/local/bin/gauntlet-display, /usr/local/bin/gauntlet-menu, /usr/local/bin/gauntlet-dialog-update" > "$sudoers.tmp"
visudo -cf "$sudoers.tmp"
install -m 0440 "$sudoers.tmp" "$sudoers"
rm -f "$sudoers.tmp"
systemctl daemon-reload
systemctl enable gauntlet.service gauntlet-backup.timer
systemctl start gauntlet-backup.timer

cat <<EOF

Done. Before closing this root session, open a NEW terminal and check:
  ssh $ADMIN_USER@<this server>      (root login is now disabled)
Then accept the EULA in $SERVER/eula.txt and run: sudo systemctl start gauntlet
EOF
if [[ -f /var/run/reboot-required ]]; then
  echo 'Updates need a reboot. After checking the new SSH login, run: sudo reboot'
fi

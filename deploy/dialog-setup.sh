#!/usr/bin/env bash
# Run the Dialog editor on the VPS at https://catenaonline.net/dialog/ (password protected).
# Run once as root after setup.sh and web-setup.sh:
#   ADMIN_USER=<login name> bash dialog-setup.sh
# Then upload the code from Windows (deploy.ps1 -DialogEditor) and add users (sudo gauntlet-dialog-user add <name>).
set -euo pipefail

ADMIN_USER="${ADMIN_USER:?Set ADMIN_USER (the login that uploads code and copies edits back into git)}"
BASE=/opt/dialog-editor
HERE="$(cd "$(dirname "$0")" && pwd)"

[[ $EUID -eq 0 ]] || { echo 'Run as root.' >&2; exit 1; }
id "$ADMIN_USER" &>/dev/null || { echo "Unknown user $ADMIN_USER. Run setup.sh first." >&2; exit 1; }
command -v nginx >/dev/null || { echo 'nginx not found. Run web-setup.sh first.' >&2; exit 1; }

echo '== Node.js 22 (NodeSource)'
export DEBIAN_FRONTEND=noninteractive
install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor --yes -o /etc/apt/keyrings/nodesource.gpg
echo 'deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_22.x nodistro main' > /etc/apt/sources.list.d/nodesource.list
apt-get update
apt-get -y install nodejs
node --version | grep -q '^v2[2-9]\.' || { echo "Node.js 22 or later is needed (found $(node --version))." >&2; exit 1; }

echo '== Account and folders'
id dialog &>/dev/null || useradd --system --user-group --home-dir "$BASE" --shell /usr/sbin/nologin dialog
# The admin reads the editor's data and exports to copy them back into git (deploy.ps1 -Menu -FromEditor).
usermod -aG dialog "$ADMIN_USER"
install -d -o dialog -g dialog -m 2750 "$BASE" "$BASE/data" "$BASE/exports"

echo '== Service'
install -m 0644 "$HERE/systemd/gauntlet-dialog.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable gauntlet-dialog.service

echo '== nginx (/dialog/ with a password)'
install -m 0644 "$HERE/nginx/gauntlet-dialog.conf" /etc/nginx/snippets/gauntlet-dialog.conf
[[ -f /etc/nginx/gauntlet-dialog.htpasswd ]] || install -o root -g www-data -m 0640 /dev/null /etc/nginx/gauntlet-dialog.htpasswd
if ! grep -rqs 'snippets/gauntlet-\*\.conf' /etc/nginx/sites-enabled/; then
  echo 'Warning: the site does not include /etc/nginx/snippets/gauntlet-*.conf; add that line to the HTTPS server block.' >&2
fi
nginx -t
systemctl reload nginx

# Ban repeated wrong passwords (fail2ban ships the nginx-http-auth filter).
cat > /etc/fail2ban/jail.d/gauntlet-dialog.local <<'EOF'
[nginx-http-auth]
enabled = true
maxretry = 5
findtime = 10m
bantime = 1h
EOF
systemctl restart fail2ban

cat <<EOF

Done. Next:
  1. Windows: powershell -File .\\deploy.ps1 -DialogEditor   (uploads the code and starts the editor)
  2. VPS:     sudo gauntlet-dialog-user add <name>            (one account per person)
  3. Open https://catenaonline.net/dialog/
$ADMIN_USER joined the dialog group; log in again for it to apply.
EOF

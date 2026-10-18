#!/usr/bin/env bash
# Serve the static site in web/ with nginx and HTTPS (Let's Encrypt). Run once as root after setup.sh:
#   ADMIN_USER=<login name> LE_EMAIL=<contact address> bash web-setup.sh
# The A records for DOMAIN and www.DOMAIN must already point to this VPS, and TCP 80/443
# must be open in the provider's packet filter. Running this agrees to the Let's Encrypt
# Subscriber Agreement on behalf of LE_EMAIL.
set -euo pipefail

ADMIN_USER="${ADMIN_USER:?Set ADMIN_USER (the login that uploads the site)}"
LE_EMAIL="${LE_EMAIL:?Set LE_EMAIL (certificate expiry notices go there)}"
DOMAIN="${DOMAIN:-catenaonline.net}"
ROOT="/var/www/$DOMAIN"
HERE="$(cd "$(dirname "$0")" && pwd)"

[[ $EUID -eq 0 ]] || { echo 'Run as root.' >&2; exit 1; }
id "$ADMIN_USER" &>/dev/null || { echo "Unknown user $ADMIN_USER. Run setup.sh first." >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get -y install nginx certbot python3-certbot-nginx

# The admin user uploads the site directly (deploy.ps1 -Web); nginx only reads it.
install -d -o "$ADMIN_USER" -g www-data -m 2755 "$ROOT"
if [[ ! -f $ROOT/index.html && -f $HERE/../web/index.html ]]; then
  install -o "$ADMIN_USER" -g www-data -m 0644 "$HERE/../web/index.html" "$ROOT/index.html"
fi

cat > "/etc/nginx/sites-available/$DOMAIN" <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN www.$DOMAIN;
    root $ROOT;
    index index.html;
    server_tokens off;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;
    location / { try_files \$uri \$uri/ =404; }
    # Extra locations, e.g. the Dialog editor at /dialog/ (dialog-setup.sh). certbot keeps this in the HTTPS block.
    include /etc/nginx/snippets/gauntlet-*.conf;
}
EOF
# Resource packs: the plugin (minecraft) writes them, nginx (www-data) serves them at /packs/.
id minecraft &>/dev/null || { echo 'User minecraft not found. Run setup.sh first.' >&2; exit 1; }
install -d -o minecraft -g www-data -m 2750 /var/lib/gauntlet-packs
install -d -m 0755 /etc/nginx/snippets
install -m 0644 "$HERE/nginx/gauntlet-packs.conf" /etc/nginx/snippets/gauntlet-packs.conf
ln -sf "/etc/nginx/sites-available/$DOMAIN" "/etc/nginx/sites-enabled/$DOMAIN"
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable --now nginx
systemctl reload nginx

ufw allow 'Nginx Full'

# Adds the 443 server, redirects HTTP to HTTPS and installs the renewal timer.
certbot --nginx --non-interactive --agree-tos -m "$LE_EMAIL" \
  -d "$DOMAIN" -d "www.$DOMAIN" --redirect
systemctl list-timers certbot.timer --no-pager || true
echo "Done: https://$DOMAIN/"

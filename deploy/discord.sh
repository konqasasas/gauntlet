#!/usr/bin/env bash
# DiscordSRV for the Gauntlet server: one-way chat (Minecraft -> Discord), bot status and a
# staff console channel. Run on the VPS with sudo:
#   sudo bash discord.sh install     download and verify the plugin, then restart once to create its config
#   sudo bash discord.sh configure   enter the bot token and channel IDs, apply the settings, restart
#   sudo bash discord.sh plain-joins join and leave notices as plain text instead of embeds (no restart)
set -euo pipefail

VERSION='1.30.5'
URL="https://cdn.modrinth.com/data/UmLGoGij/versions/ATlquwiT/DiscordSRV-Build-$VERSION.jar"
SHA512='ef31c7dd8962c7f907bc37b6d762c0ebd1b52f20ff72bb2dd35edb0e354010c03d3eb4cafa8f7acec42aa5e91988814bd0c338a5bced06095c07a6b9b366505c'
PLUGINS=/opt/gauntlet/server/plugins
DIR="$PLUGINS/DiscordSRV"
ADDRESS="${ADDRESS:-mc.catenaonline.net}"

[[ $EUID -eq 0 ]] || { echo 'Run with sudo.' >&2; exit 1; }

# set_key FILE KEY VALUE: replace the top-level "KEY: ..." line, keeping the rest of the file.
set_key() {
  python3 - "$1" "$2" "$3" <<'PY'
import re, sys
path, key, value = sys.argv[1:]
text = open(path, encoding='utf-8', newline='').read()
pattern = re.compile(rf'^{re.escape(key)}:.*$', re.M)
if not pattern.search(text):
    sys.exit(f'{key} not found in {path}')
text = pattern.sub(lambda m: f'{key}: {value}', text, count=1)
open(path, 'w', encoding='utf-8', newline='').write(text)
PY
}
quote() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1], ensure_ascii=False))' "$1"; }

# set_message FILE BLOCK TEXT: in the top-level BLOCK of messages.yml (MinecraftPlayerJoinMessage etc.),
# send TEXT as the message content and turn the embed off.
set_message() {
  python3 - "$1" "$2" "$3" <<'PY'
import json, re, sys
path, block, content = sys.argv[1:]
lines = open(path, encoding='utf-8', newline='').read().split('\n')
start = next((i for i, l in enumerate(lines) if l.rstrip('\r') == f'{block}:'), None)
if start is None:
    sys.exit(f'{block} not found in {path}')
end = next((i for i in range(start + 1, len(lines)) if lines[i] and not lines[i].startswith((' ', '#'))), len(lines))
done = {'content': False, 'embed': False}
in_embed = False
for i in range(start + 1, end):
    l = lines[i]
    eol = '\r' if l.endswith('\r') else ''
    if re.match(r'^  Content:', l):
        lines[i] = '  Content: ' + json.dumps(content, ensure_ascii=False) + eol
        done['content'] = True
    elif re.match(r'^  Embed:', l):
        in_embed = True
    elif re.match(r'^  \S', l):
        in_embed = False
    elif in_embed and re.match(r'^    Enabled:', l):
        lines[i] = '    Enabled: false' + eol
        done['embed'] = True
if not all(done.values()):
    sys.exit(f'{block}: Content or Embed.Enabled not found')
open(path, 'w', encoding='utf-8', newline='').write('\n'.join(lines))
PY
}

plain_joins() {
  local m="$DIR/messages.yml"
  [[ -f $m ]] || { echo "$m not found. Run install first." >&2; exit 1; }
  cp -p "$m" "$m.bak"
  set_message "$m" MinecraftPlayerJoinMessage '➕ **%username%** が参加しました'
  set_message "$m" MinecraftPlayerFirstJoinMessage '🎉 **%username%** が初めて参加しました'
  set_message "$m" MinecraftPlayerLeaveMessage '➖ **%username%** が退出しました'
  chown minecraft:minecraft "$m" "$m.bak"
  if systemctl is-active --quiet gauntlet.service; then printf 'discord reload\n' > /run/gauntlet.stdin; fi
  echo 'Join and leave notices are plain text now (previous file: messages.yml.bak).'
}

install_plugin() {
  tmp="$(mktemp)"
  curl -fL -o "$tmp" "$URL"
  echo "$SHA512  $tmp" | sha512sum -c -
  rm -f "$PLUGINS"/DiscordSRV-Build-*.jar
  install -o minecraft -g minecraft -m 0640 "$tmp" "$PLUGINS/DiscordSRV-Build-$VERSION.jar"
  rm -f "$tmp"
  systemctl restart gauntlet.service
  echo "Installed DiscordSRV $VERSION. Wait for the server to start, then run: sudo bash discord.sh configure"
}

configure() {
  [[ -f $DIR/config.yml && -f $DIR/messages.yml ]] \
    || { echo "$DIR/config.yml not found. Run install and let the server start once." >&2; exit 1; }
  read -rsp 'Bot token (not shown): ' token; echo
  read -rp 'Chat channel ID (Minecraft -> Discord): ' chat
  read -rp 'Console channel ID (staff only): ' console
  read -rp 'Monitoring channel ID for freeze alerts (Enter to use the chat channel): ' monitor
  [[ -n $token && $chat =~ ^[0-9]{17,20}$ && $console =~ ^[0-9]{17,20}$ && ( -z $monitor || $monitor =~ ^[0-9]{17,20}$ ) ]] \
    || { echo 'Token is empty or a channel ID is not a 17-20 digit number.' >&2; exit 1; }
  channels="{\"global\": \"$chat\"}"
  # DiscordSRV's watchdog reports a frozen main thread to the "watchdog" channel.
  [[ -n $monitor ]] && channels="{\"global\": \"$chat\", \"watchdog\": \"$monitor\"}"

  cp -p "$DIR/config.yml" "$DIR/config.yml.bak"
  cp -p "$DIR/messages.yml" "$DIR/messages.yml.bak"
  local c="$DIR/config.yml" m="$DIR/messages.yml"
  set_key "$c" BotToken "$(quote "$token")"
  set_key "$c" Channels "$channels"
  set_key "$c" DiscordConsoleChannelId "\"$console\""
  # Our chat prompts cancel Paper's AsyncChatEvent; listen to it so those inputs never reach Discord.
  set_key "$c" UseModernPaperChatEvent true
  set_key "$c" DiscordChatChannelDiscordToMinecraft false
  set_key "$c" DiscordChatChannelConsoleCommandEnabled false
  # Game chat must not ping Discord users or roles.
  set_key "$c" DiscordChatChannelTranslateMentions false
  set_key "$c" DiscordChatChannelAllowedMentions '[]'
  set_key "$c" DiscordGameStatus "[$(quote '%online%人がプレイ中'), $(quote "$ADDRESS")]"

  set_key "$m" DiscordChatChannelServerStartupMessage "$(quote ':white_check_mark: **サーバーが起動しました**')"
  set_key "$m" DiscordChatChannelServerShutdownMessage "$(quote ':octagonal_sign: **サーバーが停止しました**')"
  set_key "$m" ChannelTopicUpdaterChatChannelTopicFormat "$(quote "%playercount%/%playermax%人がオンライン | 接続先 $ADDRESS | 更新 %date%")"
  set_key "$m" ChannelTopicUpdaterChatChannelTopicAtServerShutdownFormat "$(quote "サーバー停止中 | 接続先 $ADDRESS")"
  set_key "$m" ServerWatchdogMessage "$(quote ':fire: <t:%timestamp%:R> %guildowner% サーバーが %timeout% 秒間応答していません（フリーズの可能性）')"

  chown minecraft:minecraft "$c" "$m" "$c.bak" "$m.bak"
  chmod 0640 "$c" "$c.bak"
  systemctl restart gauntlet.service
  echo 'Applied. Check the Discord channels after the server starts (journalctl -u gauntlet -f).'
}

case "${1:-}" in
  install) install_plugin ;;
  configure) configure ;;
  plain-joins) plain_joins ;;
  *) echo 'Usage: sudo bash discord.sh install|configure|plain-joins' >&2; exit 2 ;;
esac

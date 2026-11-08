#!/bin/sh
# ServerControl agent installer for Debian 13
# Usage: sudo sh install.sh servercontrol-agent-linux-amd64
set -eu
BIN="${1:-servercontrol-agent-linux-amd64}"
[ "$(id -u)" = 0 ] || { echo "Bitte als root ausführen"; exit 1; }
[ -f "$BIN" ] || { echo "Binärdatei $BIN nicht gefunden"; exit 1; }

install -m 0755 "$BIN" /usr/local/bin/servercontrol-agent
install -d -m 0700 /etc/servercontrol
install -m 0644 "$(dirname "$0")/servercontrol-agent.service" /etc/systemd/system/servercontrol-agent.service

# Optional helpers used by the agent
apt-get install -y --no-install-recommends smartmontools wireguard-tools >/dev/null || true

if [ ! -f /etc/servercontrol/agent.json ]; then
  /usr/local/bin/servercontrol-agent -init
fi

systemctl daemon-reload
systemctl enable --now servercontrol-agent
echo
echo "Agent läuft. Fingerprint zum Vergleich in der App:"
/usr/local/bin/servercontrol-agent -fingerprint
echo
echo "Nächste Schritte: /etc/servercontrol/agent.json anpassen (listen, kopia, nextcloud, immich),"
echo "dann: systemctl restart servercontrol-agent"

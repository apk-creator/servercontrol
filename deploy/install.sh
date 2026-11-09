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
apt-get install -y --no-install-recommends smartmontools >/dev/null || true

if [ ! -f /etc/servercontrol/agent.json ]; then
  /usr/local/bin/servercontrol-agent -init
fi

systemctl daemon-reload
systemctl enable servercontrol-agent
systemctl restart servercontrol-agent   # also picks up a new binary on upgrades
echo
echo "Agent läuft. Fingerprint zum Vergleich in der App:"
/usr/local/bin/servercontrol-agent -fingerprint
echo
echo "Version: $(/usr/local/bin/servercontrol-agent -version)"
echo "Hinweis: Port 8443 muss in der Firewall für das LAN offen sein, z. B. in nftables:"
echo "  ip saddr 192.168.178.0/24 tcp dport 8443 accept"

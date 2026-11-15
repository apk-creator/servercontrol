#!/bin/sh
# Non-interactive agent installation, run by the app over SSH (with sudo) from a private
# temp folder that also holds:  servercontrol-agent-linux-*  servercontrol-agent.service
#   pw      – agent password for a new installation (deleted right away)
#   pubkey  – optional: the app's SSH public key for the terminal (added to authorized_keys)
# Output lines KEY=VALUE are read by the app.
set -eu
cd "$(dirname "$0")"

case "$(uname -m)" in
  x86_64|amd64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) echo "ERROR=Architektur $(uname -m) wird nicht unterstützt"; rm -f pw; exit 3 ;;
esac

install -m 0755 "servercontrol-agent-linux-$ARCH" /usr/local/bin/servercontrol-agent
install -d -m 0700 /etc/servercontrol
install -m 0644 servercontrol-agent.service /etc/systemd/system/servercontrol-agent.service
command -v smartctl >/dev/null 2>&1 || apt-get install -y --no-install-recommends smartmontools >/dev/null 2>&1 || true

if [ -f /etc/servercontrol/agent.json ]; then
  echo "EXISTING=1"
elif [ -s pw ]; then
  P="$(cat pw)"
  printf '%s\n%s\n' "$P" "$P" | /usr/local/bin/servercontrol-agent -init >/dev/null 2>&1 || { echo "ERROR=Einrichtung fehlgeschlagen"; rm -f pw; exit 4; }
  unset P
fi
rm -f pw

# the app's key for the SSH terminal
if [ -s pubkey ] && [ -n "${SUDO_USER:-}" ]; then
  H="$(getent passwd "$SUDO_USER" | cut -d: -f6)"
  G="$(id -gn "$SUDO_USER")"
  install -d -m 0700 -o "$SUDO_USER" -g "$G" "$H/.ssh"
  touch "$H/.ssh/authorized_keys"
  grep -qxF "$(cat pubkey)" "$H/.ssh/authorized_keys" || cat pubkey >> "$H/.ssh/authorized_keys"
  chown "$SUDO_USER:$G" "$H/.ssh/authorized_keys"
  chmod 0600 "$H/.ssh/authorized_keys"
  echo "KEY=added"
fi

systemctl daemon-reload
systemctl enable servercontrol-agent >/dev/null 2>&1
systemctl restart servercontrol-agent
sleep 1
systemctl is-active --quiet servercontrol-agent && echo "ACTIVE=1" || echo "ACTIVE=0"

echo "FP=$(/usr/local/bin/servercontrol-agent -fingerprint)"
echo "VER=$(/usr/local/bin/servercontrol-agent -version)"
echo "AGENTUSER=$(sed -n 's/.*"user": *"\([^"]*\)".*/\1/p' /etc/servercontrol/agent.json | head -n 1)"
# firewall that drops by default and has no rule for 8443 → the app cannot connect yet
if command -v nft >/dev/null 2>&1 && nft list ruleset 2>/dev/null | grep -q 'policy drop' && ! nft list ruleset 2>/dev/null | grep -q 'dport 8443'; then
  echo "FIREWALL=closed"
fi

#!/usr/bin/env bash
# Cài tự động deploy (chạy MỘT LẦN trên server):
#   bash ~/mb-finance-repo/mb-finance/deploy/install.sh
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/auto-deploy.sh"
USER_NAME="$(id -un)"
chmod +x "$SCRIPT"

sudo tee /etc/systemd/system/mb-finance-deploy.service >/dev/null <<EOF
[Unit]
Description=MB Finance: tự động deploy từ GitHub
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=oneshot
User=$USER_NAME
Environment=HOME=$HOME
ExecStart=$SCRIPT
TimeoutStartSec=15min
EOF

sudo tee /etc/systemd/system/mb-finance-deploy.timer >/dev/null <<EOF
[Unit]
Description=MB Finance: kiểm tra code mới mỗi 2 phút

[Timer]
OnBootSec=2min
OnUnitActiveSec=2min
Persistent=true

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now mb-finance-deploy.timer
echo "Đã cài. Kiểm tra:  systemctl list-timers mb-finance-deploy.timer"
echo "Xem log deploy:    journalctl -u mb-finance-deploy -n 50"

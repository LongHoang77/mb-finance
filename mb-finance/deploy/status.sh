#!/usr/bin/env bash
# Xem nhanh toàn bộ tình trạng server:  bash ~/mb-finance-repo/mb-finance/deploy/status.sh
REPO_DIR="${REPO_DIR:-$HOME/mb-finance-repo}"
cd "$REPO_DIR/mb-finance" 2>/dev/null || { echo "Không thấy $REPO_DIR/mb-finance"; exit 1; }

h() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

h "Code đang chạy"
git -C "$REPO_DIR" log -1 --format='%h  %s  (%cr)'
f="$HOME/.local/state/mb-finance-deploy/failed"
[ -f "$f" ] && echo "⚠️  Bản lỗi bị bỏ qua: $(cut -c1-7 "$f")"

h "Container"
docker compose ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'

h "Tài nguyên"
docker stats --no-stream --format 'table {{.Name}}\tCPU {{.CPUPerc}}\tRAM {{.MemUsage}}' mb-finance
df -h / | tail -1 | awk '{print "Ổ đĩa: đã dùng " $3 " / " $2 " (" $5 ")"}'
docker system df --format '{{.Type}}: {{.Size}}' 2>/dev/null | head -3

h "Tự động deploy"
systemctl list-timers mb-finance-deploy.timer --no-pager 2>/dev/null | head -2
journalctl -u mb-finance-deploy -n 5 --no-pager -o cat 2>/dev/null | grep '\[deploy\]' | tail -3

h "Tailscale"
tailscale serve status 2>/dev/null | head -3

h "Ứng dụng"
curl -fsS -m 5 http://127.0.0.1:8080/api/v1/status | python3 -c '
import json,sys
s=json.load(sys.stdin)
hb=s.get("lastHeartbeat") or {}
print("Phiên bản:", s.get("version"), "| chạy từ", s.get("startedAt"))
print("Điện thoại báo lần cuối:", hb.get("at","chưa có"), "| chờ gửi:", hb.get("pendingCount","-"))
print("Thông báo chưa đọc được:", s.get("unparsedCount"), "| chỗ lệch số dư:", len(s.get("balanceGaps",[])))
' 2>/dev/null || echo "❌ App không phản hồi"

h "Log app gần nhất"
docker compose logs --tail 5 --no-log-prefix app 2>/dev/null

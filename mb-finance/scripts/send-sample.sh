#!/usr/bin/env bash
# Giả lập điện thoại gửi vài thông báo mẫu lên server để thử dashboard.
# Cách dùng:  TOKEN=<token> ./scripts/send-sample.sh [URL]
# URL mặc định: http://127.0.0.1:8080
set -euo pipefail
URL="${1:-http://127.0.0.1:8080}"
: "${TOKEN:?Hãy đặt biến TOKEN, vd: TOKEN=abc ./scripts/send-sample.sh}"

now() { date -u +%Y-%m-%dT%H:%M:%S.000Z; }
uuid() { cat /proc/sys/kernel/random/uuid; }
D=$(TZ=Asia/Ho_Chi_Minh date +%d/%m/%y)

read -r -d '' BODY <<EOF || true
{"items":[
 {"clientUuid":"$(uuid)","package":"com.mbmobile","title":"MB Bank","postedAt":"$(now)",
  "text":"TK 0123xxx789|GD: +2,000,000VND $D 08:15 |SD: 2,000,000VND|ND: MAU THU chuyen tien"},
 {"clientUuid":"$(uuid)","package":"com.mbmobile","title":"MB Bank","postedAt":"$(now)",
  "text":"TK 0123xxx789|GD: -45,000VND $D 12:30 |SD: 1,955,000VND|ND: MAU THU thanh toan QR"},
 {"clientUuid":"$(uuid)","package":"com.mbmobile","title":"MB Bank","postedAt":"$(now)",
  "text":"Day la thong bao mau khong phai giao dich"}
]}
EOF

curl -sS -X POST "$URL/api/v1/notifications" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "$BODY"
echo
curl -sS -X POST "$URL/api/v1/heartbeat" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"pendingCount":0,"appVersion":"script"}'
echo

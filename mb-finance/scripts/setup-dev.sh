#!/usr/bin/env bash
# Cài môi trường code trên máy mới (Mac hoặc Linux):
#   ./mb-finance/scripts/setup-dev.sh
# Chạy lại bao nhiêu lần cũng được. Không cài gì ngoài thư mục repo.
set -euo pipefail

NODE_MAJOR=22
SERVER="${MB_SERVER:-mb-oracle}"

cd "$(dirname "$0")/.."
APP_DIR=$(pwd)
ok()   { echo "  ✅ $*"; }
warn() { echo "  ⚠️  $*"; WARN=1; }
WARN=0

echo "1. Kiểm tra công cụ"
command -v git >/dev/null && ok "git $(git --version | awk '{print $3}')" || { echo "  ❌ Chưa có git. Mac: xcode-select --install"; exit 1; }

if ! command -v node >/dev/null; then
  echo "  ❌ Chưa có Node.js. Cài Node $NODE_MAJOR:"
  echo "     Mac (Homebrew):  brew install node@$NODE_MAJOR && brew link --overwrite node@$NODE_MAJOR"
  echo "     Hoặc dùng nvm:   nvm install $NODE_MAJOR"
  exit 1
fi
have=$(node -p 'process.versions.node.split(".")[0]')
if [ "$have" != "$NODE_MAJOR" ]; then
  echo "  ❌ Đang dùng Node $(node -v), cần Node $NODE_MAJOR (giống server)."
  echo "     Có nvm:  cd $APP_DIR && nvm install && nvm use"
  echo "     Homebrew: brew install node@$NODE_MAJOR && brew link --overwrite node@$NODE_MAJOR"
  exit 1
fi
ok "node $(node -v), npm $(npm -v)"

echo "2. Cài thư viện (npm ci theo package-lock.json)"
(cd "$APP_DIR/server" && npm ci --no-audit --no-fund) && ok "server"
(cd "$APP_DIR/web" && npm ci --no-audit --no-fund) && ok "web"

echo "3. Kết nối server (để dùng deploy/ship.sh)"
if command -v tailscale >/dev/null || [ -d /Applications/Tailscale.app ]; then
  ok "Đã có Tailscale (nhớ bật và đăng nhập đúng tài khoản)"
else
  warn "Chưa có Tailscale: tải ở https://tailscale.com/download rồi đăng nhập"
fi
if ssh -o BatchMode=yes -o ConnectTimeout=8 "$SERVER" true 2>/dev/null; then
  ok "ssh $SERVER chạy được"
else
  warn "Chưa ssh được vào '$SERVER'. Thêm vào ~/.ssh/config (sửa đường dẫn khoá):"
  cat <<EOF
       Host $SERVER
         HostName instance-20261026-0916.tailac458b.ts.net
         User ubuntu
         IdentityFile ~/.ssh/<khoá-oracle-của-bạn>
EOF
  echo "     Khoá SSH vào Oracle phải được copy từ máy cũ sang (giữ quyền: chmod 600)."
fi

echo
[ "$WARN" = 0 ] && echo "Xong. Mọi thứ đã sẵn sàng." || echo "Xong phần cài thư viện. Xử lý các mục ⚠️ ở trên để deploy được."
cat <<EOF
Chạy thử trên máy:
  cd $APP_DIR/server && npm test && npm run dev     # API ở http://localhost:8080
  cd $APP_DIR/web && npm run dev                    # dashboard ở http://localhost:5173
Deploy: ./mb-finance/deploy/ship.sh (ở thư mục gốc repo)
EOF

#!/usr/bin/env bash
# Đẩy code lên GitHub rồi deploy lên server (chạy trên máy tính của bạn):
#   ./mb-finance/deploy/ship.sh [--force]
# Server lấy từ biến MB_SERVER (mặc định: mb-oracle, alias trong ~/.ssh/config).
set -euo pipefail

SERVER="${MB_SERVER:-mb-oracle}"
BRANCH="main"

ARGS=""
for arg in "$@"; do
  case "$arg" in
    --force) ARGS="--force" ;;
    *) echo "Tham số không hợp lệ: $arg (chỉ có --force)"; exit 2 ;;
  esac
done

cd "$(dirname "$0")"
cd "$(git rev-parse --show-toplevel)"

current=$(git rev-parse --abbrev-ref HEAD)
if [ "$current" != "$BRANCH" ]; then
  echo "❌ Đang ở nhánh '$current'. Server chỉ deploy nhánh '$BRANCH':  git checkout $BRANCH"
  exit 1
fi

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "❌ Còn thay đổi chưa commit:"
  git status --short --untracked-files=no
  echo "Commit (hoặc bỏ) các thay đổi này rồi chạy lại."
  exit 1
fi

untracked=$(git ls-files --others --exclude-standard)
if [ -n "$untracked" ]; then
  echo "ℹ️  Các file sau chưa được git theo dõi, sẽ KHÔNG được deploy:"
  printf '%s\n' "$untracked" | sed 's/^/   /'
fi

echo "→ Đẩy code lên GitHub..."
git push origin "$BRANCH"

echo "→ Deploy trên $SERVER..."
ssh "$SERVER" "bash ~/mb-finance-repo/mb-finance/deploy/deploy.sh $ARGS"

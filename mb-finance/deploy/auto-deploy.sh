#!/usr/bin/env bash
# Tự động deploy: kiểm tra GitHub, có code mới thì build và chạy lại container.
# Được systemd timer gọi mỗi 2 phút (xem install.sh). Chạy tay: ./auto-deploy.sh [--force]
#
# - Chỉ build lại khi phần server/web thay đổi (sửa app Android thì bỏ qua).
# - Build lỗi → container cũ vẫn chạy nguyên.
# - Container mới không "healthy" → tự quay về bản cũ, và không thử lại bản lỗi đó nữa
#   cho tới khi có commit mới.
# - Tuỳ chọn: đặt NTFY_TOPIC trong ~/.config/mb-finance-deploy.env để nhận thông báo
#   trên điện thoại qua app ntfy (https://ntfy.sh).
set -euo pipefail

REPO_DIR="${REPO_DIR:-$HOME/mb-finance-repo}"
APP_SUBDIR="${APP_SUBDIR:-mb-finance}"   # thư mục chứa docker-compose.yml trong repo
BRANCH="${BRANCH:-main}"
CONTAINER="${CONTAINER:-mb-finance}"
STATE_DIR="${STATE_DIR:-$HOME/.local/state/mb-finance-deploy}"
ENV_FILE="$HOME/.config/mb-finance-deploy.env"

[ -f "$ENV_FILE" ] && . "$ENV_FILE"
mkdir -p "$STATE_DIR"

# Không cho 2 lần deploy chạy chồng lên nhau
exec 9>"$STATE_DIR/lock"
flock -n 9 || exit 0

log() { echo "[deploy] $*"; }
notify() {
  log "$1"
  if [ -n "${NTFY_TOPIC:-}" ]; then
    curl -fsS -m 10 -H "Title: MB Finance" -d "$1" "https://ntfy.sh/$NTFY_TOPIC" >/dev/null || true
  fi
}

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

cd "$REPO_DIR"
git fetch --quiet origin "$BRANCH"
OLD=$(git rev-parse HEAD)
NEW=$(git rev-parse "origin/$BRANCH")

if [ "$OLD" = "$NEW" ] && [ "$FORCE" = 0 ]; then
  exit 0
fi
if [ "$FORCE" = 0 ] && [ -f "$STATE_DIR/failed" ] && [ "$(cat "$STATE_DIR/failed")" = "$NEW" ]; then
  exit 0   # bản này đã lỗi trước đó; chờ commit mới
fi

CHANGED=$(git diff --name-only "$OLD" "$NEW" || true)
git reset --hard --quiet "$NEW"

# Chỉ thay đổi app Android / workflow → không cần build lại server
if [ "$FORCE" = 0 ] && ! printf '%s\n' "$CHANGED" | grep -v -e "^$APP_SUBDIR/android/" -e '^\.github/' -e '^$' -q; then
  log "Chỉ thay đổi app Android, bỏ qua ($(git rev-parse --short "$NEW"))."
  exit 0
fi

# Lưu ý: hàm được gọi trong `if`, nơi `set -e` không có tác dụng → phải tự `|| return 1`
build_and_start() {
  cd "$REPO_DIR/$APP_SUBDIR" || return 1
  GIT_SHA=$(git rev-parse --short HEAD) || return 1
  export GIT_SHA
  docker compose build --pull app || return 1
  docker compose up -d || return 1
}

wait_healthy() {
  for _ in $(seq 1 40); do
    status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$CONTAINER" 2>/dev/null || echo missing)
    [ "$status" = "healthy" ] && return 0
    [ "$status" = "unhealthy" ] && return 1
    sleep 3
  done
  return 1
}

SHORT=$(git rev-parse --short "$NEW")
MSG=$(git log -1 --format=%s "$NEW")
log "Deploy $SHORT: $MSG"

if ! build_and_start; then
  # Lỗi lúc build: container cũ chưa bị thay, vẫn đang chạy
  echo "$NEW" > "$STATE_DIR/failed"
  cd "$REPO_DIR" && git reset --hard --quiet "$OLD"
  notify "❌ Build lỗi bản $SHORT ($MSG). Bản cũ vẫn chạy. Xem: journalctl -u mb-finance-deploy -n 100"
  exit 1
fi

if wait_healthy; then
  rm -f "$STATE_DIR/failed"
  docker image prune -f >/dev/null || true
  notify "✅ Đã deploy $SHORT: $MSG"
else
  echo "$NEW" > "$STATE_DIR/failed"
  log "Container mới không healthy, quay về bản cũ..."
  docker logs --tail 30 "$CONTAINER" 2>&1 | sed 's/^/[app] /' || true
  cd "$REPO_DIR" && git reset --hard --quiet "$OLD"
  if build_and_start && wait_healthy; then
    notify "⚠️ Bản $SHORT không chạy được, đã quay về $(git rev-parse --short "$OLD"). Xem: journalctl -u mb-finance-deploy -n 100"
  else
    notify "🔥 Bản $SHORT lỗi và quay về bản cũ cũng lỗi. Cần vào server kiểm tra ngay."
  fi
  exit 1
fi

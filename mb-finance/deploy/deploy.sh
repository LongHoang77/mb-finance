#!/usr/bin/env bash
# Deploy bản mới nhất từ GitHub (chạy trên server, thường được gọi qua ship.sh):
#   bash ~/mb-finance-repo/mb-finance/deploy/deploy.sh [--force]
#
# - Không có commit mới → báo "đã là bản mới nhất". --force: build lại dù không có gì mới.
# - Chỉ sửa android/ hoặc .github/ → cập nhật code, không build lại.
# - Build lỗi → bản cũ vẫn chạy. Bản mới không healthy → quay về commit cũ và build lại.
set -euo pipefail

# Bọc cả script trong { } để bash đọc hết trước khi chạy: `git reset` bên dưới có thể ghi đè chính file này
{
REPO_DIR="${REPO_DIR:-$HOME/mb-finance-repo}"
APP_SUBDIR="${APP_SUBDIR:-mb-finance}"   # thư mục chứa docker-compose.yml trong repo
BRANCH="${BRANCH:-main}"
CONTAINER="${CONTAINER:-mb-finance}"
STATE_DIR="${STATE_DIR:-$HOME/.local/state/mb-finance-deploy}"
HEALTH_TRIES="${HEALTH_TRIES:-40}"       # 40 x 3 giây ≈ 2 phút
HEALTH_INTERVAL="${HEALTH_INTERVAL:-3}"

FORCE=0
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    *) echo "Tham số không hợp lệ: $arg (chỉ có --force)"; exit 2 ;;
  esac
done

mkdir -p "$STATE_DIR"
HISTORY="$STATE_DIR/deploy.log"

# Lịch sử deploy chỉ ghi mã commit và tiêu đề, không có dữ liệu tài chính
log() { echo "[deploy] $*"; }
record() { log "$*"; echo "$(date '+%F %T') $*" >> "$HISTORY"; }

exec 9>"$STATE_DIR/lock"
flock -n 9 || { log "Đang có một lần deploy khác chạy, thử lại sau ít phút."; exit 1; }

container_status() {
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$CONTAINER" 2>/dev/null || echo "không có"
}

# Lưu ý: các hàm dưới được gọi trong `if`, nơi `set -e` không có tác dụng → phải tự `|| return 1`
build() {
  cd "$REPO_DIR/$APP_SUBDIR" || return 1
  GIT_SHA=$(git rev-parse --short HEAD) || return 1
  export GIT_SHA
  log "Đang build $GIT_SHA (vài phút)..."
  # Output build dài → ghi ra file, chỉ in phần cuối khi lỗi
  if ! docker compose build app > "$STATE_DIR/build.log" 2>&1; then
    tail -n 40 "$STATE_DIR/build.log" | sed 's/^/[build] /'
    return 1
  fi
}

start() {
  cd "$REPO_DIR/$APP_SUBDIR" || return 1
  docker compose up -d || return 1
}

wait_healthy() {
  local status i
  log "Chờ container healthy (tối đa ~$((HEALTH_TRIES * HEALTH_INTERVAL)) giây)..."
  for ((i = 0; i < HEALTH_TRIES; i++)); do
    status=$(container_status)
    [ "$status" = "healthy" ] && return 0
    [ "$status" = "unhealthy" ] && break
    sleep "$HEALTH_INTERVAL"
  done
  log "Container không healthy (trạng thái: $status). Log gần nhất:"
  docker logs --tail 30 "$CONTAINER" 2>&1 | sed 's/^/[app] /' || true
  return 1
}

cd "$REPO_DIR"
git fetch --quiet origin "$BRANCH" || { log "Không lấy được code từ GitHub (kiểm tra mạng / deploy key)."; exit 1; }
OLD=$(git rev-parse HEAD)
NEW=$(git rev-parse "origin/$BRANCH")
SHORT=$(git rev-parse --short "$NEW")
OLD_SHORT=$(git rev-parse --short "$OLD")
MSG=$(git log -1 --format=%s "$NEW")

if [ "$OLD" = "$NEW" ] && [ "$FORCE" = 0 ]; then
  log "Đã là bản mới nhất ($SHORT: $MSG)."
  status=$(container_status)
  [ "$status" = "healthy" ] || log "⚠️  Nhưng container đang ở trạng thái: $status. Chạy lại với --force để build lại."
  exit 0
fi

CHANGED=$(git diff --name-only "$OLD" "$NEW")
git reset --hard --quiet "$NEW"

if [ "$FORCE" = 0 ] && ! printf '%s\n' "$CHANGED" | grep -q -v -e "^$APP_SUBDIR/android/" -e '^\.github/' -e '^$'; then
  record "Đã cập nhật code tới $SHORT ($MSG). Chỉ thay đổi app Android, không cần build lại server."
  exit 0
fi

log "Deploy $SHORT: $MSG"

if ! build; then
  cd "$REPO_DIR" && git reset --hard --quiet "$OLD"
  record "❌ Build lỗi bản $SHORT ($MSG). Bản cũ $OLD_SHORT vẫn đang chạy. Log đầy đủ: $STATE_DIR/build.log"
  exit 1
fi

if start && wait_healthy; then
  docker image prune -f >/dev/null || true
  record "✅ Đã deploy $SHORT: $MSG"
  exit 0
fi

if [ "$OLD" = "$NEW" ]; then
  record "🔥 Bản $SHORT (build lại bằng --force) không chạy được, không có bản cũ hơn để quay về. Cần vào server kiểm tra."
  exit 1
fi

log "Quay về bản cũ $OLD_SHORT..."
cd "$REPO_DIR" && git reset --hard --quiet "$OLD"
if build && start && wait_healthy; then
  record "⚠️  Bản $SHORT không chạy được, đã quay về $OLD_SHORT."
else
  record "🔥 Bản $SHORT lỗi và quay về $OLD_SHORT cũng lỗi. Cần vào server kiểm tra ngay."
fi
exit 1
}

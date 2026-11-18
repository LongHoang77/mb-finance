# MB Finance Tracker

Theo dõi thu chi từ thông báo của app MB Bank (dự án cá nhân, 1 người dùng):

```
Điện thoại (app Android)  ──HTTPS qua Tailscale──▶  Server Oracle (Docker)  ──▶  Dashboard web
đọc thông báo MB Bank,                              API + SQLite,                tổng quan, giao dịch,
xếp hàng đợi, gửi lên                               phân tích thông báo           lọc, xuất CSV
```

Server là một container duy nhất: API (Node.js 22 + TypeScript + Express) + SQLite + dashboard (React).
Chỉ lắng nghe trên `127.0.0.1:8080`; truy cập từ ngoài **chỉ qua `tailscale serve`**, không có đăng nhập —
bảo mật dựa vào mạng Tailscale. App điện thoại gửi dữ liệu bằng Bearer token.

## Cấu trúc repo

Code nằm trong thư mục con `mb-finance/`, không nằm ở gốc repo.

```
.github/workflows/android.yml   build APK bằng GitHub Actions
.gitignore
mb-finance/
├── android/                    app Android (Kotlin)
├── server/                     API, parser, CLI, migrations, test
├── web/                        dashboard React (Vite)
├── deploy/
│   ├── ship.sh                 (máy tính) push + deploy
│   ├── deploy.sh               (server) lấy code mới, build, chạy, tự quay về nếu lỗi
│   └── status.sh               (server) xem nhanh tình trạng
├── scripts/
│   ├── setup-dev.sh            cài môi trường code trên máy mới
│   └── send-sample.sh          giả lập điện thoại gửi dữ liệu
├── Dockerfile, docker-compose.yml
└── .nvmrc                      phiên bản Node (22)
```

## Quy tắc an toàn

- **Không bao giờ** xoá hoặc đổi tên Docker volume `mb-finance_mb-data` — đó là dữ liệu tài chính thật.
  Không chạy `docker volume rm`, `docker compose down -v`, `docker system prune --volumes`.
- Không mở cổng nào ra Internet (iptables chỉ mở cổng 22), không đổi `127.0.0.1:8080` trong `docker-compose.yml`.
- Không ghi token hay nội dung thông báo (`raw_text`) vào log.
- Không sửa code trực tiếp trên server. Mọi thay đổi đi qua GitHub rồi deploy bằng `ship.sh`.
- Khoá ký APK (`android/app/debug.keystore`) nằm trong repo. Repo để **Public** thì ai cũng ký được APK cài đè lên app
  (đọc được dữ liệu tài chính trên máy) → chỉ cài APK từ Releases / Actions của chính repo này.

## Phát triển trên máy tính

### Máy mới

Cài Node 22 (Mac: `brew install node@22 && brew link --overwrite node@22`, hoặc `nvm install 22`), rồi:

```bash
git clone https://github.com/LongHoang77/mb-finance.git ~/mb-finance-1
cd ~/mb-finance-1
./mb-finance/scripts/setup-dev.sh
```

Script kiểm tra phiên bản Node, cài thư viện cho `server/` và `web/` (`npm ci`), kiểm tra Tailscale và `ssh mb-oracle`.
Chạy lại bao nhiêu lần cũng được. Khoá SSH vào Oracle không nằm trong repo — copy từ máy cũ sang `~/.ssh/`
(rồi `chmod 600 ~/.ssh/<khoá>`), và tạo alias `mb-oracle` (xem [mục Deploy](#tạo-alias-ssh-mb-oracle-một-lần-trên-máy-tính)).

### Chạy thử trên máy

Mở 2 cửa sổ terminal, từ thư mục `mb-finance/`:

```bash
cd server && HOST=127.0.0.1 npm run dev    # API ở http://127.0.0.1:8080
cd web && npm run dev                      # dashboard ở http://localhost:5173, gọi API qua proxy
```

Database khi chạy thử nằm ở `server/data/finance.db` (đã nằm trong `.gitignore`, không bị commit).
Xoá thư mục `server/data/` để làm lại từ đầu — chỉ trên máy tính, không liên quan dữ liệu thật trên server.

### Thử với dữ liệu giả (trên máy)

```bash
cd server && npx tsx src/cli.ts device:add phone    # in ra token
cd .. && TOKEN=<token> ./scripts/send-sample.sh
```

Dashboard sẽ có 2 giao dịch "MAU THU" và 1 thông báo chưa đọc được.
**Không** gửi dữ liệu giả lên server thật.

### Quy trình sửa code

1. Sửa code, chạy thử trên máy.
2. Kiểm tra trước khi commit:
   ```bash
   cd mb-finance/server && npm test
   cd ../web && npm run build
   ```
3. Commit, rồi deploy: `./mb-finance/deploy/ship.sh` (xem mục Deploy).

## Deploy

Deploy chỉ chạy khi bạn ra lệnh. Trên máy tính, ở thư mục gốc repo, sau khi commit:

```bash
./mb-finance/deploy/ship.sh            # push + deploy
./mb-finance/deploy/ship.sh --force    # build lại dù không có code mới
```

`ship.sh` kiểm tra đang ở nhánh `main` và không còn thay đổi chưa commit, `git push`, rồi SSH vào server chạy
`deploy/deploy.sh` và in kết quả. Server mặc định là `mb-oracle`; đổi bằng `MB_SERVER=<host> ./mb-finance/deploy/ship.sh`.

`deploy.sh` trên server (`~/mb-finance-repo`):

- Không có commit mới → báo "Đã là bản mới nhất".
- Chỉ sửa `android/` hoặc `.github/` → cập nhật code, không build lại.
- Còn lại → build image, chạy container mới, chờ `healthy` (tối đa ~2 phút).
- Build lỗi → bản cũ vẫn chạy, repo quay về commit cũ. Lần `ship.sh` sau sẽ thử lại.
- Bản mới chạy nhưng không healthy → tự quay về commit cũ và build lại bản cũ.
- Hai lần deploy không chạy chồng nhau (lần sau báo "đang có một lần deploy khác chạy").
- Thư mục repo trên server bị đặt lại đúng như GitHub mỗi lần deploy.
- Sửa `deploy.sh` thì lần deploy *kế tiếp* mới dùng bản mới.

Phiên bản đang chạy (mã commit) hiện ở chân trang dashboard.

**Muốn quay về bản trước** (bản mới chạy được nhưng có lỗi logic): trên máy tính
`git revert <mã-commit-lỗi>` rồi `./mb-finance/deploy/ship.sh`.

### Tạo alias SSH `mb-oracle` (một lần, trên máy tính)

Thêm vào cuối file `~/.ssh/config` (tạo file nếu chưa có), sửa đường dẫn khoá cho đúng khoá bạn đang dùng để vào Oracle:

```
Host mb-oracle
  HostName instance-20261026-0916.tailac458b.ts.net
  User ubuntu
  IdentityFile ~/.ssh/<khoá-oracle-của-bạn>
```

Thử: `ssh mb-oracle 'echo ok'` phải in `ok`. Cần bật Tailscale trên máy tính.

### Lệnh hay dùng

| Việc | Lệnh (trên máy tính) |
|---|---|
| Deploy | `./mb-finance/deploy/ship.sh` |
| Xem toàn bộ tình trạng | `ssh mb-oracle 'bash ~/mb-finance-repo/mb-finance/deploy/status.sh'` |
| Lịch sử deploy | `ssh mb-oracle 'tail -20 ~/.local/state/mb-finance-deploy/deploy.log'` |
| Log build lần gần nhất | `ssh mb-oracle 'cat ~/.local/state/mb-finance-deploy/build.log'` |
| Log app | `ssh mb-oracle 'cd ~/mb-finance-repo/mb-finance && docker compose logs --tail 50 app'` |

## Server Oracle

Oracle Cloud Always Free, ARM64, Ubuntu 24.04, user `ubuntu`. Cần có sẵn Docker (kèm Compose) và Tailscale
(`tailscale serve --bg 8080`, kiểm tra: `tailscale serve status`).

### Cài lần đầu

Server lấy code từ GitHub bằng một **deploy key chỉ đọc**:

```bash
# 1. Tạo khoá
ssh-keygen -t ed25519 -f ~/.ssh/mb_deploy -C mb-finance-server -N ""
cat >> ~/.ssh/config <<'EOF'
Host github.com
  IdentityFile ~/.ssh/mb_deploy
  IdentitiesOnly yes
EOF
cat ~/.ssh/mb_deploy.pub     # copy dòng này
```

2. GitHub → repo `mb-finance` → **Settings → Deploy keys → Add deploy key**: dán khoá,
   **không** tick "Allow write access".

```bash
# 3. Lấy code và chạy (lần đầu build mất vài phút)
git clone git@github.com:LongHoang77/mb-finance.git ~/mb-finance-repo
cd ~/mb-finance-repo/mb-finance
GIT_SHA=$(git rev-parse --short HEAD) docker compose up -d --build
docker compose ps            # STATUS phải là "healthy" sau ~1 phút
```

Mở `https://instance-20261026-0916.tailac458b.ts.net` trên điện thoại (đang bật Tailscale) để xem dashboard.
Từ đây về sau, deploy bằng `ship.sh` trên máy tính.

**Chuyển sang server mới:** volume trên server mới sẽ trống — dữ liệu cũ không tự đi theo.
Phải khôi phục từ bản sao lưu trước khi cho điện thoại gửi dữ liệu vào.

### Thiết bị và token

Chạy trên server, trong `~/mb-finance-repo/mb-finance`:

```bash
docker compose exec app node dist/cli.js device:add phone
```

Token chỉ hiện **một lần**. Lưu vào trình quản lý mật khẩu rồi nhập vào app Android.
Trên server chỉ lưu mã băm sha256 của token.

| Lệnh (`docker compose exec app node dist/cli.js <lệnh>`) | Việc |
|---|---|
| `device:list` | Xem thiết bị, lần cuối gửi dữ liệu |
| `device:rotate phone` | Cấp token mới, token cũ hết hiệu lực |
| `device:revoke phone` | Chặn thiết bị gửi dữ liệu |
| `reparse` | Phân tích lại toàn bộ thông báo (sau khi sửa parser) |

Chạy thẳng từ máy tính, ví dụ:
`ssh mb-oracle 'cd ~/mb-finance-repo/mb-finance && docker compose exec -T app node dist/cli.js device:list'`

## Dữ liệu và sao lưu

Dữ liệu nằm trong Docker volume `mb-finance_mb-data` (file `/data/finance.db` trong container),
không mất khi build lại hay deploy. Migration mới (file `server/migrations/00X_*.sql`) tự chạy khi container khởi động.

Chưa có sao lưu tự động (dự định: `sqlite3 .backup` + `restic` mã hoá, lên kho ngoài Oracle).
Trong lúc chờ, sao lưu tay khi cần (dừng app vài giây):

```bash
cd ~/mb-finance-repo/mb-finance && docker compose stop app
sudo cp -a /var/lib/docker/volumes/mb-finance_mb-data/_data ~/mb-data-backup-$(date +%Y%m%d)
docker compose start app
```

## Parser

`server/src/parser.ts` viết theo **định dạng phỏng đoán**, đã đọc đúng thông báo thật.
Thông báo không khớp không bao giờ bị đoán bừa — chúng được đánh dấu `unparsed` và hiện ở mục
"thông báo chưa đọc được" trên dashboard. Khi gặp mẫu mới:

1. Thêm mẫu (đã che số TK) vào `server/src/parser.test.ts`.
2. Sửa regex cho khớp, chạy `cd mb-finance/server && npm test`.
3. Commit, chạy `ship.sh`, rồi phân tích lại dữ liệu cũ:
   `ssh mb-oracle 'cd ~/mb-finance-repo/mb-finance && docker compose exec -T app node dist/cli.js reparse'`

## App Android (thư mục `android/`)

Build tự động bằng GitHub Actions (`.github/workflows/android.yml`) mỗi khi thư mục `android/` thay đổi.
APK nằm ở tab **Actions** → lần chạy mới nhất → mục **Artifacts** → `thu-chi-mb-apk`.

**Tự cập nhật (không cần tải APK tay):** push lên `main` → CI build và đăng bản phát hành `build-<versionCode>`
(GitHub Releases, giữ 5 bản gần nhất). Mỗi lần mở app (tối đa 3 giờ/lần) app tự kiểm tra, hỏi "Cập nhật", tải và
cài đè (dữ liệu giữ nguyên). Xem / kiểm tra tay ở Cài đặt → **Cập nhật app**.
- Repo công khai: không cần token. Nếu đổi repo về riêng tư: nhập token ở mục đó — GitHub → Settings → Developer
  settings → **Fine-grained tokens** → chỉ repo `mb-finance`, quyền **Contents: Read-only** (lưu mã hoá trên máy,
  chỉ gửi tới `api.github.com`).
- Lần đầu cập nhật Android hỏi "Cho phép cài từ nguồn này" → bật cho Thu chi MB. Android 12+ từ lần thứ 2 cài không cần bấm xác nhận.
- Bản đầu tiên có tính năng này vẫn phải cài tay 1 lần.

- Không dùng thư viện ngoài nào trừ WorkManager; hàng đợi là SQLite gốc của Android. minSdk 26.
- `android/app/debug.keystore` là khoá ký cố định để bản build mới cài đè được bản cũ.
- Mở được bằng Android Studio nếu muốn sửa trực tiếp.

Cài lần đầu trên điện thoại (Xiaomi):

1. Mở app → mục 1: cấp quyền đọc thông báo, cho phép chạy nền không giới hạn.
2. Mục 2: nhập `https://instance-20261026-0916.tailac458b.ts.net` và token → **Lưu** → **Thử kết nối**.
3. Chờ MB Bank có một thông báo bất kỳ → mục 3 sẽ hiện app MB và package ID của nó (đã xác nhận: `com.mbmobile`).
   Nếu sau này package đổi: bấm chọn nó trong app, sửa `MB_PACKAGES` trong `docker-compose.yml`
   (trên máy tính), chạy `ship.sh`, rồi `reparse` trên server.

## API

Điện thoại gửi kèm header `Authorization: Bearer <token>`.

`POST /api/v1/notifications`
```json
{ "items": [ { "clientUuid": "uuid-v4 tạo lúc bắt thông báo",
               "package": "com.mbmobile",
               "title": "MB Bank",
               "text": "toàn bộ nội dung thông báo",
               "postedAt": "2026-10-26T06:54:10.000Z" } ] }
```
Tối đa 100 item/lần. Trả về `accepted[]` với `status` là `parsed | unparsed | ignored | duplicate`.
**Mọi item có trong `accepted` đều đã lưu an toàn** → app đánh dấu `synced`. Lỗi mạng hoặc HTTP 5xx → giữ lại và thử sau.

`POST /api/v1/heartbeat` — `{ "pendingCount": 0, "appVersion": "0.1" }`, gửi mỗi vài giờ.

Dashboard dùng (không cần token, chỉ vào được trong tailnet):

| Endpoint | Việc |
|---|---|
| `GET /api/v1/health` | Kiểm tra sống (Docker healthcheck dùng) |
| `GET /api/v1/status` | Phiên bản, thời điểm khởi động, heartbeat cuối, thông báo chưa đọc được, chỗ lệch số dư |
| `GET /api/v1/transactions` | Danh sách giao dịch (có lọc) |
| `GET /api/v1/transactions/export` | Xuất CSV |
| `GET /api/v1/stats/summary`, `/stats/monthly` | Số liệu tổng quan, theo tháng |

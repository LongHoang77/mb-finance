# MB Finance Tracker — bản đồ code cho AI

Đọc file này trước khi đọc code. Hướng dẫn vận hành (deploy, server, token, sao lưu) nằm ở [mb-finance/README.md](mb-finance/README.md) — chỉ mở khi cần.

Dự án cá nhân, 1 người dùng: app Android đọc thông báo MB Bank → gửi nguyên văn lên server → server phân tích thành giao dịch → dashboard web.

```
Android (Kotlin)                Server (Node 22 + TS + Express + SQLite)        Web (React + Vite)
NotificationListener            POST /api/v1/notifications (Bearer token)       gọi GET /api/v1/* (không token,
 → SQLite queue (pending)  ──▶   → raw_notifications → parser → transactions ──▶ chỉ vào được qua Tailscale)
 → WorkManager gửi lô 100        POST /api/v1/heartbeat
```

Toàn bộ code nằm trong `mb-finance/` (không phải gốc repo). Server + web build chung 1 Docker image, chạy trên Oracle (ARM64), chỉ nghe `127.0.0.1:8080`, ra ngoài qua `tailscale serve`.

## Android — `mb-finance/android/app/src/main/java/vn/mbfinance/tracker/`

Không dùng thư viện ngoài trừ WorkManager. minSdk 26. UI dựng bằng code, không có XML layout.

| File | Vai trò |
|---|---|
| `MbNotificationListener.kt` | `NotificationListenerService`. Mọi app: chỉ ghi tên gói + đếm vào bảng `sources` (để chọn package MB). Chỉ package = `Settings.mbPackage` (mặc định `com.mbmobile`) mới được lưu nội dung; bỏ qua `FLAG_GROUP_SUMMARY`. `extractText` lấy chuỗi dài nhất trong `bigText` / `text` / `textLines`. Lưu xong gọi `Sync.kick`. |
| `Store.kt` | SQLite `store.db` (singleton, **DB version 2**, migration viết tay trong `onUpgrade`). Bảng `queue` (thông báo nguyên văn: uuid, package, title ≤500, text ≤4000, posted_at, status `pending`/`synced`, server_status; `UNIQUE(package,text,posted_at)` chống trùng), `transactions` (1-1 với queue qua `queue_id`: amount, direction, balance_after, account, description, `tx_time` epoch ms, `tx_date` yyyy-MM-dd, `tx_year`/`tx_month`/`tx_day` giờ VN, `time_from_text` = giờ lấy từ nội dung hay giờ máy nhận) và `sources`. `enqueue` ghi queue + transactions trong 1 transaction. **Không xoá bản nào** (kể cả đã gửi). |
| `Parser.kt` | Bản Kotlin của `server/src/parser.ts` — **sửa regex phải sửa cả 2 bên và tăng `Parser.VERSION`** (app sẽ `reparseAll` lại bảng transactions). |
| `Ledger.kt` | Đọc `transactions` + thông báo không phân tích được của package MB; tổng theo tháng, số dư gần nhất. `load()` tự `reparseAll` khi `Settings.parserVersion != Parser.VERSION`. |
| `Ui.kt` | Bảng màu sáng/tối + hàm dựng view (card, button, chip, input…) và `MonthBars` (biểu đồ cột vẽ bằng Canvas). |
| `BalanceWidget.kt` | Widget màn hình chính (`res/layout/widget_balance.xml`, `res/xml/widget_balance_info.xml`): thu/chi/chênh lệch tháng này + số dư. Listener gọi `refresh` khi có thông báo mới; hệ thống tự cập nhật mỗi 30 phút. |
| `Sync.kt` | `Api` (HttpURLConnection, Bearer). `kick()`: OneTimeWork cần mạng, backoff mũ 30s, `APPEND_OR_REPLACE`. `pushAll()`: gửi lô 100 tới `/api/v1/notifications`, đánh dấu `synced` theo `accepted[]`. Lỗi 400/401/413 → `failure` (chờ user sửa); lỗi khác/mạng → `retry`. `schedulePeriodic()`: HeartbeatWorker mỗi 6h (gửi tồn + heartbeat). |
| `Settings.kt` | SharedPreferences: `serverUrl`, `token` (mã hoá AES-GCM bằng Android Keystore), `mbPackage`, `lastOk` / `lastError`. |
| `MainActivity.kt` | 3 tab dựng bằng code: **Tổng quan** (chọn tháng, chênh lệch, vào/ra, biểu đồ 6 tháng, chi nhiều nhất, gần đây), **Giao dịch** (lọc vào/ra, nhóm theo ngày, thông báo không đọc được), **Cài đặt** (quyền, URL/token, chọn package MB, đồng bộ, nhật ký). Tick 3s: cập nhật trạng thái, `dataKey` đổi thì load lại `Ledger` ở luồng nền. |

App tự phân tích để hiển thị thống kê trên máy, đồng thời vẫn gửi nguyên văn lên server (server phân tích riêng cho dashboard web).

APK build bằng GitHub Actions (`.github/workflows/android.yml`) khi `android/` thay đổi. `android/app/debug.keystore` là khoá ký cố định, cố ý commit vào repo.

## Server — `mb-finance/server/src/`

| File | Vai trò |
|---|---|
| `index.ts` | Entry: mở DB, `createApp`, listen (`PORT` 8080, `HOST`). |
| `db.ts` | better-sqlite3, WAL, foreign_keys. Tự chạy `migrations/*.sql` theo thứ tự tên, ghi vào bảng `schema_migrations`. DB mặc định `data/finance.db`, container dùng `DB_FILE=/data/finance.db`. |
| `app.ts` | Mọi route + schema zod. Lọc theo ngày **giờ VN** (`+7 hours`, cố định). Export CSV có BOM + chặn CSV injection. `/status` trả heartbeat cuối, thông báo `unparsed`, và `balanceGaps` (đối soát số dư trước + biến động ≠ số dư sau → dấu hiệu sót giao dịch). |
| `ingest.ts` | `ingest()` idempotent: trùng `client_uuid` hoặc trùng `content_hash` (sha256 của package+text+postedAt) → `duplicate`. `applyParse()`: package ∉ `MB_PACKAGES` (env) → `ignored`; parser trả null → `unparsed`; ngược lại upsert `transactions` → `parsed`. `reparseAll()` cho lệnh `reparse`. |
| `parser.ts` | Regex trên text MB: `GD: ±số VND` (bắt buộc), `SD:` số dư, `dd/mm/yy hh:mm`, `TK`, `ND:`. Nguyên tắc: **không chắc thì trả null, không đoán bừa**. Không có giờ trong text → dùng `postedAt`. |
| `auth.ts` | `requireDevice`: so sha256(token) với `devices.token_hash`, bỏ qua thiết bị đã revoke, cập nhật `last_seen_at`. |
| `cli.ts` | `device:add|rotate|revoke|list`, `reparse`. |
| `*.test.ts` | vitest: `parser.test.ts` (mẫu thông báo thật đã che số TK), `app.test.ts`. |

Schema (`migrations/001_init.sql`): `devices`, `raw_notifications` (luôn giữ text gốc để phân tích lại; `parse_status` ∈ parsed/unparsed/ignored), `transactions` (1-1 với raw, `amount` > 0 VND, `direction` in/out, thời gian ISO UTC), `heartbeats`.

API phản hồi `accepted[].status` ∈ `parsed | unparsed | ignored | duplicate`. Item nào có trong `accepted` nghĩa là đã lưu an toàn → app đánh dấu `synced`.

## Web — `mb-finance/web/src/`

- `api.ts`: kiểu dữ liệu (`Tx`, `Summary`, `Month`, `Status`), `get()`, định dạng tiền/giờ theo VN.
- `App.tsx`: 2 tab — **Tổng quan** (KPI theo tháng, cột 12 tháng, giao dịch gần nhất, `StatusPanel` cảnh báo mất heartbeat / thông báo chưa đọc được / lệch số dư) và **Giao dịch** (lọc ngày, chiều, tìm nội dung, phân trang, xuất CSV).
- Dev: Vite proxy `/api` sang `127.0.0.1:8080`.

## Deploy / scripts — `mb-finance/deploy/`, `mb-finance/scripts/`

- `ship.sh` (máy tính): yêu cầu nhánh `main` sạch → push → SSH `mb-oracle` chạy `deploy.sh`.
- `deploy.sh` (server): reset repo theo GitHub, chỉ đổi `android/` hoặc `.github/` thì không build lại; build lỗi hoặc không healthy thì tự quay về bản cũ.
- `status.sh`, `setup-dev.sh`, `send-sample.sh` (gửi dữ liệu giả, **chỉ dùng ở máy local**).

## Những chỗ dễ sai khi sửa

- Package MB có 2 nơi phải khớp: `Settings.mbPackage` trên app và env `MB_PACKAGES` trong `docker-compose.yml`. Đổi package thì sửa cả hai, rồi chạy `reparse`.
- Sửa parser → sửa cả `parser.ts` và `android/.../Parser.kt`, thêm mẫu vào `parser.test.ts` → deploy → chạy `reparse` trên server (app tự áp dụng khi cài bản mới).
- Đổi schema server: thêm file `migrations/00X_*.sql` mới, không sửa `001_init.sql`.
- Giới hạn độ dài phải khớp giữa app và server: title 500, text 4000, tối đa 100 item mỗi lô.
- Không xoá volume `mb-finance_mb-data` (dữ liệu tài chính thật), không mở cổng ra Internet, không log token hay `raw_text`.

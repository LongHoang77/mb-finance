# MB Finance Tracker — bản đồ code cho AI

Đọc file này trước khi đọc code. Hướng dẫn vận hành (deploy, server, token, sao lưu) nằm ở [mb-finance/README.md](mb-finance/README.md) — chỉ mở khi cần.

Dự án cá nhân, 1 người dùng: app Android đọc thông báo MB Bank → tự phân tích thành giao dịch, thống kê và gắn tag **hoàn toàn trên máy** (không gửi đi đâu, không có quyền INTERNET). Server + web bên dưới là phần cũ, app không còn gửi dữ liệu lên.

```
Android (Kotlin)                Server (Node 22 + TS + Express + SQLite)        Web (React + Vite)
NotificationListener            POST /api/v1/notifications (Bearer token)       gọi GET /api/v1/* (không token,
 → SQLite queue (pending)  ──▶   → raw_notifications → parser → transactions ──▶ chỉ vào được qua Tailscale)
 → WorkManager gửi lô 100        POST /api/v1/heartbeat
```

Toàn bộ code nằm trong `mb-finance/` (không phải gốc repo). Server + web build chung 1 Docker image, chạy trên Oracle (ARM64), chỉ nghe `127.0.0.1:8080`, ra ngoài qua `tailscale serve`.

## Android — `mb-finance/android/app/src/main/java/vn/mbfinance/tracker/`

Không dùng thư viện ngoài. minSdk 26. UI dựng bằng code, chỉ widget có XML layout.

| File | Vai trò |
|---|---|
| `MbNotificationListener.kt` | `NotificationListenerService`. Mọi app: chỉ ghi tên gói + đếm vào bảng `sources` (để chọn package MB). Chỉ package = `Settings.mbPackage` (mặc định `com.mbmobile`) mới được lưu nội dung (lưu hết, lọc khi hiển thị); bỏ qua `FLAG_GROUP_SUMMARY`. `extractText` lấy chuỗi dài nhất trong `bigText` / `text` / `textLines`. Lưu xong gọi `BalanceWidget.refresh`. `instance` (static) khác null khi listener đang kết nối; `scanActive()` đọc lại thông báo còn trên thanh thông báo. `catchUp()` = quét bù (listener đang chạy) hoặc tắt/bật component để ép hệ thống bind lại (ROM hay giết app → listener ngắt, `onNotificationPosted` không được gọi); gọi khi mở app, `onListenerConnected`, widget cập nhật/bấm nút quét. |
| `Store.kt` | SQLite `store.db` (singleton, **DB version 4**, migration viết tay trong `onUpgrade`). Bảng `queue` (thông báo nguyên văn: uuid, package, title ≤500, text ≤4000, posted_at; `UNIQUE(package,text,posted_at)` chống trùng thông báo; `isDuplicate` chống trùng giao dịch khi MB gửi lại thông báo (khớp TK + chiều + số tiền + số dư sau + ND + giờ trong text, thiếu số dư/giờ thì không coi là trùng), v4 + `reparseAll` gọi `dropDuplicates` dọn bản trùng cũ; cột `status`/`server_status` còn lại từ thời gửi server, không dùng), `transactions` (1-1 với queue qua `queue_id`: amount, direction, balance_after, account, description, `tx_time` epoch ms, `tx_date` yyyy-MM-dd, `tx_year`/`tx_month`/`tx_day` giờ VN, `time_from_text` = giờ lấy từ nội dung hay giờ máy nhận), `sources`, `tags` (name UNIQUE NOCASE, color ARGB; v3 seed sẵn 8 tag) và `tx_tags` (queue_id → tag_id, 1 tag/giao dịch — gắn theo queue_id vì `transactions` bị xoá/điền lại khi reparse). `enqueue` ghi queue + transactions trong 1 transaction. Giao dịch giữ mãi; `purgeNonTransactions` xoá thông báo không có trong transactions và không `looksLikeTransaction` (OTP, quảng cáo) sau 7 ngày. `brief()` = tổng tháng + số dư bằng SQL cho widget. |
| `Parser.kt` | Bản Kotlin của `server/src/parser.ts` — **sửa regex phải sửa cả 2 bên và tăng `Parser.VERSION`** (app sẽ `reparseAll` lại bảng transactions). |
| `Ledger.kt` | `Tx` (kèm `tag`), `Tag`. Đọc `transactions` + thông báo không phân tích được của package MB; tổng theo tháng, theo tag (`byTag`), số dư gần nhất. `prepare()` tự `reparseAll` khi `Settings.parserVersion != Parser.VERSION` và dọn thông báo không phải giao dịch quá 7 ngày; `load()` gọi `prepare` rồi đọc. Danh sách "chưa đọc được" chỉ gồm thông báo `looksLikeTransaction` (có số tiền kèm dấu +/−). |
| `Ui.kt` | Bảng màu sáng/tối + hàm dựng view (card, button, chip, `tagChip`, `bar`, `sheet`, input…), `Flow` (xếp chip xuống dòng) và `MonthBars` (biểu đồ cột vẽ bằng Canvas). |
| `BalanceWidget.kt` | Widget màn hình chính (`res/layout/widget_balance.xml`, `res/xml/widget_balance_info.xml`): thu/chi/chênh lệch tháng này + số dư (`Store.brief`, không load cả Ledger) + trạng thái listener + nút quét lại (`ACTION_SYNC` → `catchUp`). Listener gọi `refresh`; hệ thống tự cập nhật mỗi 30 phút (kèm `catchUp`). |
| `Settings.kt` | SharedPreferences: `mbPackage`, `parserVersion`, `lastError` (lỗi đọc thông báo). `dropServerConfig()` xoá URL/token + khoá Keystore còn sót từ bản có gửi server. |
| `MainActivity.kt` | 3 tab dựng bằng code: **Tổng quan** (chọn tháng, chênh lệch, vào/ra, biểu đồ 6 tháng, thu/chi theo tag — chạm để lọc, chi nhiều nhất, gần đây), **Giao dịch** (lọc vào/ra + tag, nhóm theo ngày, thông báo không đọc được), **Cài đặt** (quyền, quản lý tag, chọn package MB, quét lại thông báo, nhật ký). Chạm giao dịch → bottom sheet chi tiết (gắn/bỏ tag, tạo tag mới, thông báo gốc thu gọn). Tick 3s: cập nhật trạng thái, `dataKey` đổi thì load lại `Ledger` ở luồng nền; đổi tag thì `reload()`. |

App tự phân tích và lưu mọi thứ trên máy; không còn gửi lên server.

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

- Đổi schema app: tăng version trong `Store` và thêm nhánh `if (oldVersion < N)` trong `onUpgrade`, không sửa bảng cũ trong `onCreate` mà quên migration.
- Sửa parser → sửa cả `parser.ts` và `android/.../Parser.kt`, thêm mẫu vào `parser.test.ts` → deploy → chạy `reparse` trên server (app tự áp dụng khi cài bản mới).
- Đổi schema server: thêm file `migrations/00X_*.sql` mới, không sửa `001_init.sql`.
- Giới hạn độ dài phải khớp giữa app và server: title 500, text 4000, tối đa 100 item mỗi lô.
- Không xoá volume `mb-finance_mb-data` (dữ liệu tài chính thật), không mở cổng ra Internet, không log token hay `raw_text`.

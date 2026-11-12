# MB Finance Tracker — bản đồ code cho AI

Đọc file này trước khi đọc code. Hướng dẫn vận hành (deploy, server, token, sao lưu) nằm ở [mb-finance/README.md](mb-finance/README.md) — chỉ mở khi cần.

Dự án cá nhân, 1 người dùng: app Android gồm 2 module tách biệt, chuyển bằng nút ở header (`MainActivity.selectModule`):
- **Tài chính**: đọc thông báo MB Bank → tự phân tích thành giao dịch, thống kê và gắn tag **hoàn toàn trên máy** — code tài chính không bao giờ gọi mạng.
- **Game** (package `game/`): Honkai: Star Rail qua HoYoLAB — tự điểm danh, sức mạnh khai phá, nhiệm vụ, ủy thác. Là phần DUY NHẤT dùng quyền INTERNET, mọi request đi qua `game/Net.kt` (chỉ HTTPS, allowlist host HoYoLAB, không theo redirect).

Server + web bên dưới là phần cũ, app không còn gửi dữ liệu lên.

```
Android (Kotlin)                Server (Node 22 + TS + Express + SQLite)        Web (React + Vite)
NotificationListener            POST /api/v1/notifications (Bearer token)       gọi GET /api/v1/* (không token,
 → SQLite queue (pending)  ──▶   → raw_notifications → parser → transactions ──▶ chỉ vào được qua Tailscale)
 → WorkManager gửi lô 100        POST /api/v1/heartbeat
```

Toàn bộ code nằm trong `mb-finance/` (không phải gốc repo). Server + web build chung 1 Docker image, chạy trên Oracle (ARM64), chỉ nghe `127.0.0.1:8080`, ra ngoài qua `tailscale serve`.

## Android — `mb-finance/android/app/src/main/java/vn/mbfinance/tracker/`

Không dùng thư viện ngoài (mạng = `HttpsURLConnection`, JSON = `org.json`, chạy nền = `JobScheduler`/`AlarmManager`). minSdk 26. Bản build (kể cả debug của CI) đặt `isDebuggable = false`; `network_security_config.xml` chặn cleartext và chỉ tin CA hệ thống. UI dựng bằng code, chỉ widget có XML layout; icon là vector drawable `res/drawable/ic_*.xml` (Material Icons, tô màu trong code).

| File | Vai trò |
|---|---|
| `MbNotificationListener.kt` | `NotificationListenerService`. Mọi app: chỉ ghi tên gói + đếm vào bảng `sources` (để chọn package MB). Chỉ package = `Settings.mbPackage` (mặc định `com.mbmobile`) mới được lưu nội dung (lưu hết, lọc khi hiển thị); bỏ qua `FLAG_GROUP_SUMMARY`. `extractText` lấy chuỗi dài nhất trong `bigText` / `text` / `textLines`. Lưu xong gọi `BalanceWidget.refresh`. `instance` (static) khác null khi listener đang kết nối; `scanActive()` đọc lại thông báo còn trên thanh thông báo. `catchUp()` = quét bù (listener đang chạy) hoặc tắt/bật component để ép hệ thống bind lại (ROM hay giết app → listener ngắt, `onNotificationPosted` không được gọi); gọi khi mở app, `onListenerConnected`, widget cập nhật/bấm nút quét. |
| `Store.kt` | SQLite `store.db` (singleton, **DB version 7**, migration viết tay trong `onUpgrade`). Bảng `queue` (thông báo nguyên văn: uuid, package, title ≤500, text ≤4000, posted_at; `UNIQUE(package,text,posted_at)` chống trùng thông báo; `findSame`/`sameTx` chống trùng giao dịch khi MB gửi lại thông báo: `Parser.sameTx` (khớp TK + chiều + số tiền GD đọc lại từ text + số dư sau + giờ trong text + ND so theo phần đầu) hoặc bản bị cắt `Parser.partialSame` (thiếu ND/SD/giờ/TK, trường cả 2 cùng có thì khớp) nhận cách nhau ≤ `NEAR_MS` (10 phút) hoặc cùng giờ trong text; bản đầy đủ hơn (`richer`: nhiều trường hơn rồi dài hơn) thì `replaceText` thay nội dung bản cũ, giữ queue_id/tag), `reparseAll` gọi `dropDuplicates` dọn bản trùng cũ theo cùng tiêu chí (bỏ qua dòng deleted); `status = 'deleted'` = người dùng đã xoá giao dịch (`deleteTransaction` giữ dòng queue làm dấu để quét lại / MB gửi lại không thêm lại — trừ khi bản mới đầy đủ hơn bản đã xoá, `reparseAll` và `unparsed` bỏ qua); `server_status` còn lại từ thời gửi server, không dùng), `transactions` (1-1 với queue qua `queue_id`: amount, direction, balance_after, account, description, `tx_time` epoch ms, `tx_date` yyyy-MM-dd, `tx_year`/`tx_month`/`tx_day` giờ VN, `time_from_text` = giờ lấy từ nội dung hay giờ máy nhận), `sources`, `tags` (name UNIQUE NOCASE, color ARGB lấy từ `TAG_COLORS` — bảng màu đã kiểm tra tương phản/mù màu, v7 đổi màu tag cũ từ `OLD_TAG_COLORS`; v3 seed sẵn 8 tag) và `tx_tags` (queue_id → tag_id, 1 tag/giao dịch — gắn theo queue_id vì `transactions` bị xoá/điền lại khi reparse). `enqueue` ghi queue + transactions trong 1 transaction. **Đối soát số dư** (v5): `chain` (giao dịch MB có số dư + mốc số dư nhập tay `balance_marks` cùng TK, theo thời gian; cùng phút thì xếp theo số dư nối tiếp) + `settle` → `amount` = GD + phí (`confirmed_fees` người dùng xác nhận nếu còn khớp đúng khoản lệch, không thì `Parser.fee`), `transactions.fee` = phần phí, `transactions.gap` / `balance_marks.gap` = phần lệch còn lại so với mắt xích liền trước (> 0 thiếu, < 0 thừa; lệch đúng bằng GD = số dư không đổi = có thể là bản trùng); `resettle` chạy quanh 1 thời điểm ở `enqueue`/`deleteTransaction`/`confirmFee`/`addMark`/`deleteMark`, toàn bộ ở `reparseAll` (trả `Recheck`). **Khoản lớn** (v6): `plans` (name, kind `save` = mục tiêu tiết kiệm / `pay` = khoản cọc, target = số tiền mục tiêu / tổng giá trị sản phẩm, done — khoản cọc tự `done` khi `setPlan` thấy đã trả đủ), v7 `plan_dismissed` (queue_id, plan_id: gợi ý "trả nốt" người dùng bấm Không phải) + `plan_tx` (queue_id → plan_id, 1 khoản/giao dịch, chỉ tiền ra; xoá giao dịch / gộp bản trùng chuyển hoặc gỡ như tag); giao dịch gắn khoản `save` không tính vào thu/chi (`brief` loại bằng `SAVING_TX`), đối soát số dư vẫn tính. `monthChecks` = đối soát theo tháng mỗi TK (số dư đầu tháng = mắt xích cuối trước tháng, + vào − ra so với số dư cuối tháng). `revision` cộng vào `dataKey` khi dữ liệu đổi mà số dòng queue không đổi. **Giao dịch nhập tay** (`saveManual`): dòng queue `package = 'manual'`, text rỗng (chỉ làm khoá cho tag/xoá), dữ liệu nằm ở transactions; `reparseAll` không xoá, `transactions()`/`brief()` gộp cùng package MB, `recent()` bỏ qua. Giao dịch giữ mãi; `purgeNonTransactions` xoá thông báo không có trong transactions và không `looksLikeTransaction` (OTP, quảng cáo) sau 7 ngày. `brief()` = tổng tháng + số dư bằng SQL cho widget. |
| `Parser.kt` | Bản Kotlin của `server/src/parser.ts` — **sửa regex phải sửa cả 2 bên và tăng `Parser.VERSION`** (app sẽ `reparseAll` lại bảng transactions). Riêng app: `extras` (mọi số tiền VND khác GD/SD, không theo chữ đứng trước) `fee()` (số dư giảm hơn GD đúng bằng 1 số / tổng vài số trong `extras`, tối đa max(50k, GD/10)) `sameTx()` / `partialSame()` / `completeness()` (tiêu chí trùng, bản bị cắt). `VERSION` tăng cả khi đổi cách đối soát trùng/phí. |
| `Ledger.kt` | `Tx` (kèm `tag`, `manual`, `fee`, `gap`, `feeConfirmed`, `plan`; `saving` = tiền ra gắn mục tiêu tiết kiệm), `Tag`, `Plan`; `spending(m)` = giao dịch tháng bỏ tiền tiết kiệm (dùng cho `sum`/`byTag`, `MonthSum.saved` = tổng tiết kiệm), `paid(plan)`/`txsOf(plan)`, `depositIds` (lần trả đầu của khoản cọc = "Cọc", sau đó "Trả cọc"), `suggestions` (tx id → khoản cọc: tiền ra chưa gắn, sau lần cọc, đúng bằng số còn phải trả, trừ cặp đã dismiss — tính khi load, không ghi DB); `checks` = `Store.monthChecks`. Đọc `transactions` + thông báo không phân tích được của package MB; tổng theo tháng (gộp ngân hàng + tiền mặt), theo tag (`byTag`), số dư ngân hàng gần nhất (`latestBalance`), tiền mặt (`cashBalance` = vào − ra nhập tay), mẫu nhập nhanh (`templates`), `lastManualTag`. `prepare()` tự `reparseAll` khi `Settings.parserVersion != Parser.VERSION` và dọn thông báo không phải giao dịch quá 7 ngày; `load()` gọi `prepare` rồi đọc. Danh sách "chưa đọc được" chỉ gồm thông báo `looksLikeTransaction` (có số tiền kèm dấu +/−). |
| `Ui.kt` | Hệ thống giao diện kiểu fintech: bảng màu sáng/tối (gradient `hero`/`cash` cùng tông icon app), chữ `medium`, số tiền `amount` (chữ số tabular) + hàm dựng view (card viền mảnh, `hero`, `header`, button có icon/`danger`, chip, `tagChip`, `segmented`, `callout`, `pill`, `iconCircle`/`letterCircle`, `listRow`, `iconText` = icon trong chữ, `bar`, `sheet`, input…), `Flow` (xếp chip xuống dòng), `MonthBars` (biểu đồ cột vẽ bằng Canvas) và `DonutChart` (vành khuyên, chạm chọn phần). Không dùng emoji làm icon. |
| `BalanceWidget.kt` | Widget màn hình chính (`res/layout/widget_balance.xml`, `res/xml/widget_balance_info.xml`, nền gradient `w_bg_start`/`w_bg_end`, chữ trắng): thu/chi/chênh lệch tháng này (gộp tiền mặt) + dòng "Tổng · Bank · Money" số rút gọn `Ui.short` (`Store.brief`, không load cả Ledger) + trạng thái listener + nút quét lại (`ACTION_SYNC` → `catchUp`). Listener gọi `refresh`; hệ thống tự cập nhật mỗi 30 phút (kèm `catchUp`). |
| `Settings.kt` | SharedPreferences: `mbPackage`, `parserVersion`, `lastError` (lỗi đọc thông báo). `dropServerConfig()` xoá URL/token + khoá Keystore còn sót từ bản có gửi server. |
| `MainActivity.kt` | 3 tab dựng bằng code: **Tổng quan** (gọn, `renderOverview`: chọn tháng; thẻ chính = số dư tổng / ngân hàng / tiền mặt + vào / ra / chênh lệch tháng — chạm để sang Giao dịch lọc sẵn — + 1 dòng `checkSummary` đối soát, chạm → `showChecks` (bảng `monthCheckCard`, chạm callout xem giao dịch lệch); thẻ "Mục tiêu & khoản cọc" (`plansCard`: callout gợi ý trả nốt chạm → chi tiết giao dịch, tối đa 3 khoản, "Xem tất cả" → `showPlanList`, "+ Thêm" → `showPlanForm`, chạm → `showPlan`); theo tag (`DonutChart` vành khuyên + chú thích tên/số tiền/%, top 5 + "Khác", `tagsExpanded` để xem hết; chạm phần biểu đồ để xem nhanh, chạm dòng chú thích để lọc); biểu đồ 6 tháng; 5 giao dịch gần đây), **Giao dịch** (ngân hàng + tiền mặt chung 1 danh sách: lọc nguồn `Source` + vào/ra + tag, nhóm theo ngày, lọc Tiền mặt thì hiện tiền mặt hiện có, thông báo không đọc được), nút nổi "+" (ở Tổng quan và Giao dịch) → `showManualForm` thêm/sửa giao dịch tay (mẫu gần đây, số tiền nhanh, Hôm nay/Hôm qua, chọn ngày giờ, mặc định cuối tháng đang xem nếu là tháng cũ, tag lần trước), **Cài đặt** (quyền, bảng tag `renderTagSettings`: tag · số GD · chi/thu tháng đang xem + chưa gắn + tổng, chạm → `showTag` thống kê / đổi màu / đổi tên / xem giao dịch / xoá; mục Thông báo gồm 2 dòng `navRow`: "App ngân hàng" → `showSources` chọn package, "Nhật ký thông báo" → `showNotifLog` toàn màn hình (thanh tiêu đề + dải 14 ngày kèm số thông báo cố định ở trên, nút lịch chọn ngày xa hơn, danh sách cuộn bên dưới, `Store.notifications`); số dư thực tế (`showMarkForm`/`renderMarks`, chạm để xoá), quét lại thông báo, kiểm tra lại dữ liệu đã lưu = `catchUp` + `reparseAll` + báo số bản trùng đã xoá / giao dịch có phí / lệch số dư). Chạm giao dịch → bottom sheet chi tiết (gắn/bỏ tag, tạo tag mới, tách GD + phí/VAT nếu có, giao dịch tiền ra: "Đánh dấu là cọc" → `showPlanForm(deposit=…)` nhập tên + tổng giá, "Chuyển tiết kiệm" / "Trả tiếp cho khoản cọc…" → `showPlanPicker`, gợi ý trả nốt → "Đúng, trả nốt" / "Không phải", gỡ khỏi khoản, lệch số dư + nút ghi nhận khoản lệch là phí / bỏ ghi nhận, thông báo gốc thu gọn, sửa giao dịch tay, xoá giao dịch có hỏi lại). Tick 3s: cập nhật trạng thái, `dataKey` đổi thì load lại `Ledger` ở luồng nền; đổi tag thì `reload()`. |

App tự phân tích và lưu mọi thứ trên máy; không còn gửi lên server.

### Module Game — `.../tracker/game/`

| File | Vai trò |
|---|---|
| `Net.kt` | Điểm gọi mạng duy nhất: HTTPS tới `sg-public-api` / `bbs-api-os` / `api-account-os` `.hoyolab.com`, không redirect, giới hạn 1 MB. Thêm host mới phải sửa `ALLOWED`. |
| `Hoyolab.kt` | API HoYoLAB không chính thức (theo hoyolab-auto-sign, genshin.py): cookie `ltoken_v2`/`ltuid_v2` lấy từ `CookieManager` của WebView (không lưu chỗ khác, không log); `roles` (getUserGameRolesByCookie, game_biz `hkrpg_global`), `note` (game_record/hkrpg/api/note + header `DS` = md5 salt overseas), `signInfo`/`sign`/`reward` (event/luna/os, act_id `e202303301540311`; retcode -5003 = đã điểm danh, `gt_result.is_risk` = captcha, -100/10001 = hết phiên). `serverOf(uid)` theo chữ số đầu UID. |
| `GameStore.kt` | SharedPreferences `game`: nhân vật, JSON ghi chú + giờ đọc, trạng thái điểm danh (ngày theo UTC+8), cài đặt (tự điểm danh, nhắc trước X phút), `staminaAt` ước tính (1 điểm / 6 phút tính ngược từ giờ đầy). |
| `GameSync.kt` | `run(force, forceSign)` (synchronized, luồng nền): lấy nhân vật → điểm danh nếu hôm nay (UTC+8) chưa → đọc ghi chú (cache 5 phút) → `GameAlarm.schedule`; lỗi lưu `lastError`, hết phiên/captcha nhắc tối đa 1 lần/ngày. Gọi khi mở app, mở module Game, `GameJob`. |
| `GameJob.kt` | `JobService` định kỳ 3 giờ khi có mạng, persisted (`RECEIVE_BOOT_COMPLETED`), điều kiện có mạng cần `ACCESS_NETWORK_STATE` (thiếu là SecurityException); `ensure` chỉ đặt nếu chưa có, bọc `runCatching` để không làm crash app. |
| `GameAlarm.kt` | `BroadcastReceiver` + `AlarmManager.setAndAllowWhileIdle`: nhắc "sắp đầy" theo `notifyBefore`, 1 lần mỗi lần đầy (`notified`). |
| `GameNotify.kt` | Kênh thông báo "game" (cần `POST_NOTIFICATIONS` từ Android 13), chạm → `MainActivity` với `EXTRA_MODULE = MODULE_GAME`. |
| `GameScreen.kt` | UI module Game: đăng nhập (WebView toàn màn hình, chỉ điều hướng HTTPS trong `hoyolab.com`/`hoyoverse.com`, không JS bridge, không file access), thẻ tài khoản + làm mới, sức mạnh khai phá (giờ đầy, dự trữ), nhiệm vụ ngày/tuần, ủy thác, điểm danh (captcha → mở trang điểm danh), cài đặt, đăng xuất (xoá cookie + WebStorage + `GameStore`, huỷ job/alarm). |

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

- Không gọi mạng từ code tài chính; mọi request phải qua `game/Net.kt` (allowlist). Không log cookie HoYoLAB hay nội dung giao dịch.

- Đổi schema app: tăng version trong `Store` và thêm nhánh `if (oldVersion < N)` trong `onUpgrade`, không sửa bảng cũ trong `onCreate` mà quên migration.
- Sửa parser → sửa cả `parser.ts` và `android/.../Parser.kt`, thêm mẫu vào `parser.test.ts` → deploy → chạy `reparse` trên server (app tự áp dụng khi cài bản mới).
- Đổi schema server: thêm file `migrations/00X_*.sql` mới, không sửa `001_init.sql`.
- Giới hạn độ dài phải khớp giữa app và server: title 500, text 4000, tối đa 100 item mỗi lô.
- Không xoá volume `mb-finance_mb-data` (dữ liệu tài chính thật), không mở cổng ra Internet, không log token hay `raw_text`.

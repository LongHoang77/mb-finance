# MB Finance Tracker — server + dashboard

Một container duy nhất: API (Node.js + TypeScript + Express) + SQLite + dashboard (React).
Chỉ lắng nghe trên `127.0.0.1:8080`; truy cập từ ngoài **chỉ qua `tailscale serve`**.

```
mb-finance/
├── Dockerfile, docker-compose.yml
├── server/            API, parser, CLI, migrations, test
├── web/               dashboard React
└── scripts/send-sample.sh   giả lập điện thoại gửi dữ liệu
```

## Triển khai lần đầu trên Oracle

```bash
# 1. Bỏ container nginx thử nghiệm (đang chiếm cổng 8080)
docker rm -f hello

# 2. Giải nén và build (lần đầu mất vài phút)
mkdir -p ~/mb-finance && tar -xzf ~/mb-finance.tar.gz -C ~/mb-finance
cd ~/mb-finance
docker compose up -d --build
docker compose ps            # STATUS phải là "healthy" sau ~1 phút
docker compose logs --tail 20

# 3. tailscale serve đã trỏ vào 8080 từ trước, không cần làm lại.
#    Kiểm tra:  tailscale serve status
```

Mở `https://<tên-máy>.<tailnet>.ts.net` trên điện thoại (đang bật Tailscale) → thấy dashboard trống.

## Tạo token cho điện thoại

```bash
docker compose exec app node dist/cli.js device:add phone
```

Token chỉ hiện **một lần**. Lưu vào trình quản lý mật khẩu; sau này nhập vào app Android.

Các lệnh khác:

| Lệnh | Việc |
|---|---|
| `device:list` | Xem thiết bị, lần cuối gửi dữ liệu |
| `device:rotate phone` | Cấp token mới, token cũ hết hiệu lực |
| `device:revoke phone` | Chặn thiết bị gửi dữ liệu |
| `reparse` | Phân tích lại toàn bộ thông báo (sau khi sửa parser) |

(Đều chạy dạng `docker compose exec app node dist/cli.js <lệnh>`.)

## Thử với dữ liệu giả

```bash
TOKEN=<token> ./scripts/send-sample.sh
```

Dashboard sẽ có 2 giao dịch "MAU THU" và 1 thông báo chưa đọc được. Dữ liệu giả có thể xoá bằng cách
xoá toàn bộ database (chỉ làm khi chưa có dữ liệu thật):

```bash
docker compose down && docker volume rm mb-finance_mb-data && docker compose up -d
# rồi tạo lại token: device:add phone
```

## Cập nhật code

```bash
cd ~/mb-finance
tar -xzf ~/mb-finance.tar.gz -C ~/mb-finance   # ghi đè bằng bản mới
docker compose up -d --build
```

Dữ liệu nằm trong Docker volume `mb-finance_mb-data`, không mất khi build lại.
Migration mới (file `server/migrations/00X_*.sql`) tự chạy khi container khởi động.

## Parser

`server/src/parser.ts` hiện viết theo **định dạng phỏng đoán**. Khi app Android thu được mẫu thật:

1. Thêm mẫu (đã che số TK) vào `server/src/parser.test.ts`.
2. Sửa regex cho khớp, chạy `cd server && npm test`.
3. Build lại container, chạy `reparse`.

Thông báo không khớp không bao giờ bị đoán bừa — chúng hiện ở mục "thông báo chưa đọc được" trên dashboard.

## API cho app Android

Header: `Authorization: Bearer <token>`.

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

## Phát triển trên máy tính

```bash
cd server && npm install && npm test && npm run dev    # API ở :8080
cd web && npm install && npm run dev                   # dashboard ở :5173, gọi API qua proxy
```

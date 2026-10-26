-- Thiết bị được phép gửi dữ liệu (điện thoại)
CREATE TABLE devices (
  id           INTEGER PRIMARY KEY,
  name         TEXT NOT NULL UNIQUE,
  token_hash   TEXT NOT NULL UNIQUE,
  created_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  last_seen_at TEXT,
  revoked_at   TEXT
);

-- Thông báo thô đúng như điện thoại bắt được; luôn giữ để phân tích lại
CREATE TABLE raw_notifications (
  id           INTEGER PRIMARY KEY,
  device_id    INTEGER NOT NULL REFERENCES devices(id),
  client_uuid  TEXT NOT NULL UNIQUE,          -- chống gửi lại
  content_hash TEXT NOT NULL UNIQUE,          -- chống thông báo bị post lại
  package      TEXT NOT NULL,
  title        TEXT,
  raw_text     TEXT NOT NULL,
  posted_at    TEXT NOT NULL,                 -- ISO UTC, giờ điện thoại nhận
  received_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  parse_status TEXT NOT NULL DEFAULT 'unparsed'
               CHECK (parse_status IN ('parsed','unparsed','ignored'))
);
CREATE INDEX idx_raw_status ON raw_notifications(parse_status);

-- Giao dịch phân tích được từ thông báo (nguồn: thông báo Android, không phải sao kê)
CREATE TABLE transactions (
  id               INTEGER PRIMARY KEY,
  raw_id           INTEGER NOT NULL UNIQUE REFERENCES raw_notifications(id) ON DELETE CASCADE,
  amount           INTEGER NOT NULL CHECK (amount > 0),   -- VND, luôn dương
  direction        TEXT NOT NULL CHECK (direction IN ('in','out')),
  balance_after    INTEGER,                               -- số dư sau GD nếu thông báo có
  account          TEXT,                                  -- số TK đã che, nếu có
  description      TEXT,
  transaction_time TEXT NOT NULL                          -- ISO UTC
);
CREATE INDEX idx_tx_time ON transactions(transaction_time);
CREATE INDEX idx_tx_direction ON transactions(direction, transaction_time);

-- Nhịp tim từ điện thoại để phát hiện app bị tắt
CREATE TABLE heartbeats (
  id            INTEGER PRIMARY KEY,
  device_id     INTEGER NOT NULL REFERENCES devices(id),
  pending_count INTEGER NOT NULL DEFAULT 0,
  app_version   TEXT,
  at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX idx_hb_at ON heartbeats(at);

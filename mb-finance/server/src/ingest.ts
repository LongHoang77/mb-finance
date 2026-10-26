import crypto from 'node:crypto';
import type { DB } from './db.js';
import { parseNotification } from './parser.js';

export const MB_PACKAGES = (process.env.MB_PACKAGES ?? 'com.mbmobile')
  .split(',').map((s) => s.trim()).filter(Boolean);

export type IncomingNotification = {
  clientUuid: string;
  package: string;
  title?: string | null;
  text: string;
  postedAt: string; // ISO
};

export function sha256(s: string) {
  return crypto.createHash('sha256').update(s).digest('hex');
}

/** Phân tích 1 bản ghi thô và ghi/cập nhật giao dịch tương ứng. */
export function applyParse(db: DB, rawId: number, pkg: string, text: string, postedAt: string) {
  if (!MB_PACKAGES.includes(pkg)) {
    db.prepare(`UPDATE raw_notifications SET parse_status='ignored' WHERE id=?`).run(rawId);
    db.prepare(`DELETE FROM transactions WHERE raw_id=?`).run(rawId);
    return 'ignored' as const;
  }
  const p = parseNotification(text);
  if (!p) {
    db.prepare(`UPDATE raw_notifications SET parse_status='unparsed' WHERE id=?`).run(rawId);
    db.prepare(`DELETE FROM transactions WHERE raw_id=?`).run(rawId);
    return 'unparsed' as const;
  }
  db.prepare(`
    INSERT INTO transactions (raw_id, amount, direction, balance_after, account, description, transaction_time)
    VALUES (@rawId, @amount, @direction, @balanceAfter, @account, @description, @time)
    ON CONFLICT(raw_id) DO UPDATE SET
      amount=excluded.amount, direction=excluded.direction, balance_after=excluded.balance_after,
      account=excluded.account, description=excluded.description, transaction_time=excluded.transaction_time
  `).run({
    rawId, amount: p.amount, direction: p.direction, balanceAfter: p.balanceAfter,
    account: p.account, description: p.description, time: p.transactionTime ?? postedAt,
  });
  db.prepare(`UPDATE raw_notifications SET parse_status='parsed' WHERE id=?`).run(rawId);
  return 'parsed' as const;
}

/**
 * Nhận 1 thông báo từ điện thoại. Idempotent:
 * - cùng clientUuid gửi lại → trả về 'duplicate', không tạo bản ghi mới
 * - cùng nội dung + cùng thời điểm (thông báo bị post lại) → 'duplicate'
 */
export function ingest(db: DB, deviceId: number, n: IncomingNotification) {
  const postedAt = new Date(n.postedAt).toISOString();
  const contentHash = sha256(`${n.package}\n${n.text}\n${postedAt}`);
  return db.transaction(() => {
    const res = db.prepare(`
      INSERT INTO raw_notifications (device_id, client_uuid, content_hash, package, title, raw_text, posted_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT DO NOTHING
    `).run(deviceId, n.clientUuid, contentHash, n.package, n.title ?? null, n.text, postedAt);
    if (res.changes === 0) return 'duplicate' as const;
    return applyParse(db, Number(res.lastInsertRowid), n.package, n.text, postedAt);
  })();
}

/** Phân tích lại toàn bộ thông báo thô (sau khi sửa parser). */
export function reparseAll(db: DB) {
  const rows = db.prepare(`SELECT id, package, raw_text, posted_at FROM raw_notifications`).all() as any[];
  const counts = { parsed: 0, unparsed: 0, ignored: 0 };
  db.transaction(() => {
    for (const r of rows) counts[applyParse(db, r.id, r.package, r.raw_text, r.posted_at)]++;
  })();
  return counts;
}

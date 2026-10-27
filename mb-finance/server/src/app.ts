import express, { type Request, type Response } from 'express';
import path from 'node:path';
import fs from 'node:fs';
import { z } from 'zod';
import type { DB } from './db.js';
import { requireDevice } from './auth.js';
import { ingest } from './ingest.js';

// Giờ Việt Nam cố định UTC+7 (không có giờ mùa hè)
const VN = "'+7 hours'";
const STARTED_AT = new Date().toISOString();
const VERSION = process.env.APP_VERSION ?? 'dev';

const NotificationSchema = z.object({
  clientUuid: z.string().uuid(),
  package: z.string().min(1).max(200),
  title: z.string().max(500).nullish(),
  text: z.string().min(1).max(4000),
  postedAt: z.string().datetime({ offset: true }),
});
const IngestSchema = z.object({ items: z.array(NotificationSchema).min(1).max(100) });
const HeartbeatSchema = z.object({
  pendingCount: z.number().int().min(0).max(1_000_000),
  appVersion: z.string().max(50).optional(),
});

const RangeSchema = z.object({
  from: z.string().date().optional(), // YYYY-MM-DD giờ VN, tính cả ngày
  to: z.string().date().optional(),
});
const ListSchema = RangeSchema.extend({
  direction: z.enum(['in', 'out']).optional(),
  q: z.string().max(100).optional(),
  page: z.coerce.number().int().min(1).default(1),
  pageSize: z.coerce.number().int().min(1).max(200).default(50),
});

/** Điều kiện lọc chung, so sánh theo ngày giờ VN. */
function buildWhere(f: { from?: string; to?: string; direction?: string; q?: string }) {
  const clauses: string[] = [];
  const params: Record<string, unknown> = {};
  if (f.from) { clauses.push(`date(t.transaction_time, ${VN}) >= @from`); params.from = f.from; }
  if (f.to) { clauses.push(`date(t.transaction_time, ${VN}) <= @to`); params.to = f.to; }
  if (f.direction) { clauses.push(`t.direction = @direction`); params.direction = f.direction; }
  if (f.q) { clauses.push(`t.description LIKE @q ESCAPE '\\'`); params.q = `%${f.q.replace(/[\\%_]/g, (c) => '\\' + c)}%`; }
  return { where: clauses.length ? `WHERE ${clauses.join(' AND ')}` : '', params };
}

function bad(res: Response, err: z.ZodError) {
  return res.status(400).json({ error: 'invalid_request', issues: err.issues.map((i) => ({ path: i.path, message: i.message })) });
}

export function createApp(db: DB, opts: { webDir?: string } = {}) {
  const app = express();
  app.disable('x-powered-by');
  app.use(express.json({ limit: '256kb' }));

  // ---- Điện thoại (cần token) ----
  const device = requireDevice(db);

  app.post('/api/v1/notifications', device, (req, res) => {
    const parsed = IngestSchema.safeParse(req.body);
    if (!parsed.success) return bad(res, parsed.error);
    const deviceId = res.locals.deviceId as number;
    const results = parsed.data.items.map((n) => ({ clientUuid: n.clientUuid, status: ingest(db, deviceId, n) }));
    // Mọi item trong danh sách này đều đã an toàn trên server → điện thoại đánh dấu 'synced'
    res.json({ accepted: results });
  });

  app.post('/api/v1/heartbeat', device, (req, res) => {
    const parsed = HeartbeatSchema.safeParse(req.body);
    if (!parsed.success) return bad(res, parsed.error);
    db.prepare(`INSERT INTO heartbeats (device_id, pending_count, app_version) VALUES (?, ?, ?)`)
      .run(res.locals.deviceId, parsed.data.pendingCount, parsed.data.appVersion ?? null);
    res.json({ ok: true });
  });

  // ---- Dashboard (bảo vệ bằng mạng Tailscale) ----
  app.get('/api/v1/health', (_req, res) => {
    db.prepare('SELECT 1').get();
    res.json({ ok: true });
  });

  app.get('/api/v1/transactions', (req, res) => {
    const parsed = ListSchema.safeParse(req.query);
    if (!parsed.success) return bad(res, parsed.error);
    const f = parsed.data;
    const { where, params } = buildWhere(f);
    const total = (db.prepare(`SELECT COUNT(*) AS n FROM transactions t ${where}`).get(params) as any).n;
    const items = db.prepare(`
      SELECT t.id, t.amount, t.direction, t.balance_after AS balanceAfter, t.account,
             t.description, t.transaction_time AS transactionTime
      FROM transactions t ${where}
      ORDER BY t.transaction_time DESC, t.id DESC
      LIMIT @limit OFFSET @offset
    `).all({ ...params, limit: f.pageSize, offset: (f.page - 1) * f.pageSize });
    res.json({ items, total, page: f.page, pageSize: f.pageSize });
  });

  app.get('/api/v1/transactions/export', (req, res) => {
    const parsed = ListSchema.omit({ page: true, pageSize: true }).safeParse(req.query);
    if (!parsed.success) return bad(res, parsed.error);
    const { where, params } = buildWhere(parsed.data);
    const rows = db.prepare(`
      SELECT strftime('%Y-%m-%d %H:%M:%S', t.transaction_time, ${VN}) AS time,
             t.direction, t.amount, t.balance_after, t.description
      FROM transactions t ${where} ORDER BY t.transaction_time
    `).all(params) as any[];
    // Chặn CSV injection: ô bắt đầu bằng = + - @ bị Excel hiểu là công thức
    const cell = (v: unknown) => {
      let s = v == null ? '' : String(v);
      if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
      return `"${s.replace(/"/g, '""')}"`;
    };
    const lines = ['thoi_gian_vn,chieu,so_tien_vnd,so_du_sau,noi_dung',
      ...rows.map((r) => [r.time, r.direction === 'in' ? 'vao' : 'ra', r.amount, r.balance_after, r.description].map(cell).join(','))];
    res.setHeader('Content-Type', 'text/csv; charset=utf-8');
    res.setHeader('Content-Disposition', `attachment; filename="giao-dich.csv"`);
    res.send('﻿' + lines.join('\r\n')); // BOM để Excel đọc đúng tiếng Việt
  });

  app.get('/api/v1/stats/summary', (req, res) => {
    const parsed = RangeSchema.safeParse(req.query);
    if (!parsed.success) return bad(res, parsed.error);
    const { where, params } = buildWhere(parsed.data);
    const r = db.prepare(`
      SELECT COALESCE(SUM(CASE WHEN direction='in'  THEN amount END), 0) AS totalIn,
             COALESCE(SUM(CASE WHEN direction='out' THEN amount END), 0) AS totalOut,
             COUNT(*) AS count
      FROM transactions t ${where}
    `).get(params) as any;
    res.json({ ...r, net: r.totalIn - r.totalOut });
  });

  app.get('/api/v1/stats/monthly', (req, res) => {
    const parsed = RangeSchema.safeParse(req.query);
    if (!parsed.success) return bad(res, parsed.error);
    const { where, params } = buildWhere(parsed.data);
    const rows = db.prepare(`
      SELECT strftime('%Y-%m', t.transaction_time, ${VN}) AS month,
             COALESCE(SUM(CASE WHEN direction='in'  THEN amount END), 0) AS totalIn,
             COALESCE(SUM(CASE WHEN direction='out' THEN amount END), 0) AS totalOut,
             COUNT(*) AS count
      FROM transactions t ${where}
      GROUP BY month ORDER BY month
    `).all(params);
    res.json({ months: rows });
  });

  /** Tình trạng đồng bộ + các dấu hiệu bị sót dữ liệu. */
  app.get('/api/v1/status', (_req, res) => {
    const hb = db.prepare(`SELECT at, pending_count AS pendingCount, app_version AS appVersion FROM heartbeats ORDER BY at DESC LIMIT 1`).get();
    const lastReceived = (db.prepare(`SELECT MAX(received_at) AS t FROM raw_notifications`).get() as any).t;
    const unparsed = db.prepare(`
      SELECT id, raw_text AS text, posted_at AS postedAt FROM raw_notifications
      WHERE parse_status='unparsed' ORDER BY posted_at DESC LIMIT 20`).all();
    const unparsedCount = (db.prepare(`SELECT COUNT(*) AS n FROM raw_notifications WHERE parse_status='unparsed'`).get() as any).n;
    // Đối soát số dư: số dư trước + biến động phải bằng số dư sau
    const gaps = db.prepare(`
      WITH seq AS (
        SELECT id, transaction_time, account, balance_after,
               CASE direction WHEN 'in' THEN amount ELSE -amount END AS delta,
               LAG(balance_after) OVER (PARTITION BY account ORDER BY transaction_time, id) AS prev_balance
        FROM transactions WHERE balance_after IS NOT NULL
      )
      SELECT id, transaction_time AS transactionTime, prev_balance + delta - balance_after AS missing
      FROM seq WHERE prev_balance IS NOT NULL AND prev_balance + delta <> balance_after
      ORDER BY transaction_time DESC LIMIT 20
    `).all();
    res.json({ version: VERSION, startedAt: STARTED_AT, lastHeartbeat: hb ?? null, lastReceivedAt: lastReceived ?? null, unparsedCount, unparsed, balanceGaps: gaps });
  });

  app.use('/api', (_req, res) => res.status(404).json({ error: 'not_found' }));

  // ---- Giao diện web đã build ----
  const webDir = opts.webDir && path.resolve(opts.webDir); // sendFile cần đường dẫn tuyệt đối
  if (webDir && fs.existsSync(path.join(webDir, 'index.html'))) {
    app.use(express.static(webDir, { index: false, maxAge: '1h' }));
    app.get(/.*/, (_req: Request, res: Response) => res.sendFile(path.join(webDir, 'index.html')));
  }

  // Lỗi không lường trước: không lộ chi tiết ra ngoài
  app.use((err: any, _req: Request, res: Response, _next: unknown) => {
    if (err?.type === 'entity.too.large') return res.status(413).json({ error: 'too_large' });
    if (err?.type === 'entity.parse.failed') return res.status(400).json({ error: 'invalid_json' });
    console.error('[error]', err?.message ?? err);
    res.status(500).json({ error: 'internal' });
  });

  return app;
}

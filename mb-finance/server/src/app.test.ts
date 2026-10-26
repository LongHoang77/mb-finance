import { beforeEach, describe, expect, it } from 'vitest';
import type { AddressInfo } from 'node:net';
import { randomUUID } from 'node:crypto';
import { openDb, type DB } from './db.js';
import { createApp } from './app.js';
import { newToken } from './auth.js';
import { sha256 } from './ingest.js';

let db: DB;
let base: string;
let token: string;

async function call(method: string, path: string, body?: unknown, auth = true) {
  const res = await fetch(base + path, {
    method,
    headers: { 'content-type': 'application/json', ...(auth ? { authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: res.headers.get('content-type')?.includes('json') ? await res.json() : await res.text() };
}

const note = (text: string, extra: Partial<Record<string, string>> = {}) => ({
  clientUuid: randomUUID(), package: 'com.mbmobile', title: 'MB Bank', text,
  postedAt: '2026-10-26T06:54:10.000Z', ...extra,
});

beforeEach(async () => {
  db = openDb(':memory:');
  token = newToken();
  db.prepare(`INSERT INTO devices (name, token_hash) VALUES ('phone', ?)`).run(sha256(token));
  const server = createApp(db).listen(0);
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return () => server.close();
});

describe('API', () => {
  it('từ chối token sai hoặc đã thu hồi', async () => {
    expect((await call('POST', '/api/v1/notifications', { items: [note('x')] }, false)).status).toBe(401);
    db.prepare(`UPDATE devices SET revoked_at='2026-01-01'`).run();
    expect((await call('POST', '/api/v1/notifications', { items: [note('x')] })).status).toBe(401);
  });

  it('từ chối dữ liệu không hợp lệ', async () => {
    const r = await call('POST', '/api/v1/notifications', { items: [{ text: 'thiếu trường' }] });
    expect(r.status).toBe(400);
  });

  it('nhận giao dịch, gửi lại không tạo trùng', async () => {
    const n = note('TK 0123xxx789|GD: +5,000,000VND 26/10/26 13:54 |SD: 5,000,000VND|ND: luong');
    const a = await call('POST', '/api/v1/notifications', { items: [n] });
    expect(a.body.accepted[0].status).toBe('parsed');
    const b = await call('POST', '/api/v1/notifications', { items: [n] });
    expect(b.body.accepted[0].status).toBe('duplicate');
    // cùng nội dung nhưng UUID khác (thông báo bị post lại) → cũng trùng
    const c = await call('POST', '/api/v1/notifications', { items: [{ ...n, clientUuid: randomUUID() }] });
    expect(c.body.accepted[0].status).toBe('duplicate');
    const list = await call('GET', '/api/v1/transactions');
    expect(list.body.total).toBe(1);
  });

  it('hai giao dịch giống hệt nhưng khác thời điểm vẫn được giữ cả hai', async () => {
    const text = 'TK 0123xxx789|GD: -30,000VND |ND: cafe';
    await call('POST', '/api/v1/notifications', { items: [
      note(text, { postedAt: '2026-10-26T01:00:00.000Z' }),
      note(text, { postedAt: '2026-10-26T01:00:05.000Z' }),
    ] });
    expect((await call('GET', '/api/v1/transactions')).body.total).toBe(2);
  });

  it('thông báo lạ thành unparsed, app khác bị bỏ qua', async () => {
    const r = await call('POST', '/api/v1/notifications', { items: [
      note('Ma OTP cua quy khach la 123456'),
      note('GD: +1,000VND', { package: 'com.other.app' }),
    ] });
    expect(r.body.accepted.map((x: any) => x.status)).toEqual(['unparsed', 'ignored']);
    const s = await call('GET', '/api/v1/status');
    expect(s.body.unparsedCount).toBe(1);
  });

  it('thống kê theo tháng giờ VN và phát hiện lệch số dư', async () => {
    await call('POST', '/api/v1/notifications', { items: [
      note('TK 0123xxx789|GD: +1,000,000VND 31/10/26 23:30 |SD: 1,000,000VND|ND: a'),   // 31/10 giờ VN
      note('TK 0123xxx789|GD: -200,000VND 01/11/26 08:00 |SD: 800,000VND|ND: b'),
      // thiếu một giao dịch -100,000 ở giữa → số dư nhảy
      note('TK 0123xxx789|GD: -50,000VND 02/11/26 09:00 |SD: 650,000VND|ND: c'),
    ] });
    const m = await call('GET', '/api/v1/stats/monthly');
    expect(m.body.months).toEqual([
      { month: '2026-10', totalIn: 1_000_000, totalOut: 0, count: 1 },
      { month: '2026-11', totalIn: 0, totalOut: 250_000, count: 2 },
    ]);
    const sum = await call('GET', '/api/v1/stats/summary?from=2026-11-01&to=2026-11-30');
    expect(sum.body).toEqual({ totalIn: 0, totalOut: 250_000, count: 2, net: -250_000 });
    const s = await call('GET', '/api/v1/status');
    expect(s.body.balanceGaps).toHaveLength(1);
    expect(s.body.balanceGaps[0].missing).toBe(100_000);
  });

  it('xuất CSV chặn công thức', async () => {
    await call('POST', '/api/v1/notifications', { items: [note('TK 0123xxx789|GD: +1,000VND |ND: =HYPERLINK("x")')] });
    const r = await call('GET', '/api/v1/transactions/export');
    expect(r.body).toContain(`"'=HYPERLINK(""x"")"`);
  });

  it('heartbeat', async () => {
    expect((await call('POST', '/api/v1/heartbeat', { pendingCount: 3, appVersion: '0.1' })).status).toBe(200);
    expect((await call('GET', '/api/v1/status')).body.lastHeartbeat.pendingCount).toBe(3);
  });
});

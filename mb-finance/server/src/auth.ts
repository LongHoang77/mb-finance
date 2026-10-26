import type { NextFunction, Request, Response } from 'express';
import crypto from 'node:crypto';
import type { DB } from './db.js';
import { sha256 } from './ingest.js';

export function newToken() {
  return crypto.randomBytes(32).toString('base64url');
}

/** Chỉ cho thiết bị có token hợp lệ, chưa bị thu hồi. */
export function requireDevice(db: DB) {
  const find = db.prepare(`SELECT id FROM devices WHERE token_hash=? AND revoked_at IS NULL`);
  const touch = db.prepare(`UPDATE devices SET last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`);
  return (req: Request, res: Response, next: NextFunction) => {
    const m = (req.header('authorization') ?? '').match(/^Bearer\s+(\S+)$/);
    const row = m ? (find.get(sha256(m[1])) as { id: number } | undefined) : undefined;
    if (!row) return res.status(401).json({ error: 'unauthorized' });
    touch.run(row.id);
    res.locals.deviceId = row.id;
    next();
  };
}

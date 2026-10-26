import { openDb } from './db.js';
import { newToken } from './auth.js';
import { reparseAll, sha256 } from './ingest.js';

const [cmd, arg] = process.argv.slice(2);
const db = openDb();

switch (cmd) {
  case 'device:add': {
    if (!arg) throw new Error('Cách dùng: device:add <tên-thiết-bị>');
    const token = newToken();
    db.prepare(`INSERT INTO devices (name, token_hash) VALUES (?, ?)`).run(arg, sha256(token));
    console.log(`Đã tạo thiết bị "${arg}".`);
    console.log(`TOKEN (chỉ hiện 1 lần, nhập vào app Android):\n\n  ${token}\n`);
    break;
  }
  case 'device:rotate': {
    if (!arg) throw new Error('Cách dùng: device:rotate <tên-thiết-bị>');
    const token = newToken();
    const r = db.prepare(`UPDATE devices SET token_hash=?, revoked_at=NULL WHERE name=?`).run(sha256(token), arg);
    if (!r.changes) throw new Error(`Không có thiết bị "${arg}"`);
    console.log(`Token mới cho "${arg}" (token cũ hết hiệu lực):\n\n  ${token}\n`);
    break;
  }
  case 'device:revoke': {
    if (!arg) throw new Error('Cách dùng: device:revoke <tên-thiết-bị>');
    const r = db.prepare(`UPDATE devices SET revoked_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE name=?`).run(arg);
    console.log(r.changes ? `Đã thu hồi "${arg}".` : `Không có thiết bị "${arg}".`);
    break;
  }
  case 'device:list': {
    console.table(db.prepare(`SELECT name, created_at, last_seen_at, revoked_at FROM devices`).all());
    break;
  }
  case 'reparse': {
    console.log('Kết quả phân tích lại:', reparseAll(db));
    break;
  }
  default:
    console.log('Lệnh: device:add <tên> | device:rotate <tên> | device:revoke <tên> | device:list | reparse');
}
db.close();

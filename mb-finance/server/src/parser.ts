/**
 * Phân tích nội dung thông báo MB Bank.
 *
 * QUAN TRỌNG: định dạng dưới đây là PHỎNG ĐOÁN ban đầu, chưa kiểm chứng.
 * Sau khi app Android thu được mẫu thật, hãy thêm mẫu vào parser.test.ts,
 * sửa regex cho khớp, rồi chạy `npm run cli -- reparse` để phân tích lại toàn bộ.
 *
 * Nguyên tắc: không chắc thì trả về null (→ 'unparsed'), KHÔNG đoán bừa.
 */

export type ParsedTx = {
  amount: number; // VND, luôn dương
  direction: 'in' | 'out';
  balanceAfter: number | null;
  account: string | null;
  description: string | null;
  transactionTime: string | null; // ISO UTC nếu thông báo có ghi giờ
};

const VN_OFFSET_HOURS = 7;

/** "1,234,567" | "1.234.567" | "1234567" → 1234567 */
function toVnd(s: string): number | null {
  const digits = s.replace(/[.,\s]/g, '');
  if (!/^\d+$/.test(digits)) return null;
  const n = Number(digits);
  return Number.isSafeInteger(n) ? n : null;
}

/** "26/10/26 13:54" hoặc "26/10/2026 13:54[:ss]" (giờ VN) → ISO UTC */
function toIsoUtc(date: string, time: string): string | null {
  const d = date.match(/^(\d{1,2})\/(\d{1,2})\/(\d{2}|\d{4})$/);
  const t = time.match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if (!d || !t) return null;
  const year = d[3].length === 2 ? 2000 + Number(d[3]) : Number(d[3]);
  const ms = Date.UTC(year, Number(d[2]) - 1, Number(d[1]),
    Number(t[1]) - VN_OFFSET_HOURS, Number(t[2]), Number(t[3] ?? 0));
  return Number.isNaN(ms) ? null : new Date(ms).toISOString();
}

// Biến động: "GD: +5,000,000VND" / "GD: -150,000 VND"
const AMOUNT_RE = /GD\s*:?\s*([+-])\s*([\d.,]+)\s*(?:VND|đ)/i;
// Số dư: "SD: 12,345,678VND"
const BALANCE_RE = /SD\s*:?\s*([\d.,]+)\s*(?:VND|đ)/i;
// Thời gian: "26/10/26 13:54"
const TIME_RE = /(\d{1,2}\/\d{1,2}\/(?:\d{4}|\d{2}))\s+(\d{1,2}:\d{2}(?::\d{2})?)/;
// Tài khoản: "TK 0123xxx456" / "TK: 0123xxx456"
const ACCOUNT_RE = /TK\s*:?\s*([0-9xX*]{4,})/;
// Nội dung: "ND: ..." tới hết chuỗi
// (phải có dấu ":" và đứng sau "|" hoặc khoảng trắng, để không khớp nhầm "ND" trong "VND")
const DESC_RE = /(?:^|[|\s])ND\s*:\s*(.+)$/is;

export function parseNotification(text: string): ParsedTx | null {
  const a = text.match(AMOUNT_RE);
  if (!a) return null;
  const amount = toVnd(a[2]);
  if (!amount || amount <= 0) return null;

  const b = text.match(BALANCE_RE);
  const t = text.match(TIME_RE);
  const acc = text.match(ACCOUNT_RE);
  const desc = text.match(DESC_RE);

  return {
    amount,
    direction: a[1] === '+' ? 'in' : 'out',
    balanceAfter: b ? toVnd(b[1]) : null,
    account: acc ? acc[1] : null,
    // ND chỉ có khoảng trắng → coi như không có
    description: desc ? desc[1].trim().replace(/\s+/g, ' ') || null : null,
    transactionTime: t ? toIsoUtc(t[1], t[2]) : null,
  };
}

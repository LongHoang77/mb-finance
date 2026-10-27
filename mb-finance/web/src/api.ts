export type Tx = {
  id: number;
  amount: number;
  direction: 'in' | 'out';
  balanceAfter: number | null;
  account: string | null;
  description: string | null;
  transactionTime: string;
};
export type Summary = { totalIn: number; totalOut: number; net: number; count: number };
export type Month = { month: string; totalIn: number; totalOut: number; count: number };
export type Status = {
  version: string;
  startedAt: string;
  lastHeartbeat: { at: string; pendingCount: number; appVersion: string | null } | null;
  lastReceivedAt: string | null;
  unparsedCount: number;
  unparsed: { id: number; text: string; postedAt: string }[];
  balanceGaps: { id: number; transactionTime: string; missing: number }[];
};
export type Filters = { from?: string; to?: string; direction?: 'in' | 'out' | ''; q?: string };

export function qs(params: Record<string, string | number | undefined>) {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') p.set(k, String(v));
  const s = p.toString();
  return s ? `?${s}` : '';
}

export async function get<T>(path: string): Promise<T> {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`Lỗi ${res.status}`);
  return res.json();
}

const vnd = new Intl.NumberFormat('vi-VN');
export const money = (n: number) => `${vnd.format(n)} ₫`;

const dtf = new Intl.DateTimeFormat('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh', day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
});
export const when = (iso: string) => dtf.format(new Date(iso));

/** Ngày hôm nay theo giờ VN, dạng YYYY-MM-DD */
export function todayVN() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' }).format(new Date());
}

export function ago(iso: string) {
  const min = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (min < 1) return 'vừa xong';
  if (min < 60) return `${min} phút trước`;
  const h = Math.round(min / 60);
  if (h < 48) return `${h} giờ trước`;
  return `${Math.round(h / 24)} ngày trước`;
}

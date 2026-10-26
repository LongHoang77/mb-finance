import { useEffect, useMemo, useState } from 'react';
import { ago, get, money, qs, todayVN, when, type Filters, type Month, type Status, type Summary, type Tx } from './api';

type Tab = 'overview' | 'transactions';

export default function App() {
  const [tab, setTab] = useState<Tab>('overview');
  return (
    <div className="shell">
      <header className="top">
        <h1>Thu chi</h1>
        <nav>
          <button className={tab === 'overview' ? 'on' : ''} onClick={() => setTab('overview')}>Tổng quan</button>
          <button className={tab === 'transactions' ? 'on' : ''} onClick={() => setTab('transactions')}>Giao dịch</button>
        </nav>
      </header>
      <main>{tab === 'overview' ? <Overview /> : <Transactions />}</main>
      <footer>Số liệu lấy từ thông báo MB Bank trên điện thoại, không phải sao kê chính thức.</footer>
    </div>
  );
}

/* ------------------------------------------------------------------ */

function useLoad<T>(url: string) {
  const [state, setState] = useState<{ data?: T; error?: string; loading: boolean }>({ loading: true });
  useEffect(() => {
    let alive = true;
    setState((s) => ({ ...s, loading: true }));
    get<T>(url)
      .then((data) => alive && setState({ data, loading: false }))
      .catch((e) => alive && setState({ error: String(e.message ?? e), loading: false }));
    return () => { alive = false; };
  }, [url]);
  return state;
}

function monthRange(ym: string) {
  const [y, m] = ym.split('-').map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return { from: `${ym}-01`, to: `${ym}-${String(last).padStart(2, '0')}` };
}

function shiftMonth(ym: string, delta: number) {
  const [y, m] = ym.split('-').map(Number);
  const d = new Date(Date.UTC(y, m - 1 + delta, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

const monthLabel = (ym: string) => `Tháng ${Number(ym.slice(5))}/${ym.slice(0, 4)}`;

/* ------------------------------------------------------------------ */

function Overview() {
  const thisMonth = todayVN().slice(0, 7);
  const [ym, setYm] = useState(thisMonth);
  const range = monthRange(ym);
  const summary = useLoad<Summary>(`/api/v1/stats/summary${qs(range)}`);
  const yearAgo = monthRange(shiftMonth(thisMonth, -11)).from;
  const monthly = useLoad<{ months: Month[] }>(`/api/v1/stats/monthly${qs({ from: yearAgo })}`);
  const status = useLoad<Status>('/api/v1/status');
  const recent = useLoad<{ items: Tx[] }>(`/api/v1/transactions${qs({ pageSize: 8 })}`);

  return (
    <>
      <StatusPanel s={status} />

      <section className="card">
        <div className="row between">
          <button className="ghost" onClick={() => setYm(shiftMonth(ym, -1))} aria-label="Tháng trước">‹</button>
          <h2>{monthLabel(ym)}</h2>
          <button className="ghost" onClick={() => setYm(shiftMonth(ym, 1))} disabled={ym >= thisMonth} aria-label="Tháng sau">›</button>
        </div>
        {summary.error ? <p className="err">{summary.error}</p> : (
          <div className="kpis">
            <Kpi label="Tiền vào" value={summary.data?.totalIn} tone="in" />
            <Kpi label="Tiền ra" value={summary.data?.totalOut} tone="out" />
            <Kpi label="Chênh lệch" value={summary.data?.net} tone={(summary.data?.net ?? 0) >= 0 ? 'in' : 'out'} signed />
          </div>
        )}
        <p className="muted small">{summary.data ? `${summary.data.count} giao dịch` : ' '}</p>
      </section>

      <section className="card">
        <h2>12 tháng gần nhất</h2>
        {monthly.error ? <p className="err">{monthly.error}</p>
          : monthly.loading ? <p className="muted">Đang tải…</p>
          : <MonthlyBars months={monthly.data!.months} from={shiftMonth(thisMonth, -11)} to={thisMonth} onPick={setYm} />}
      </section>

      <section className="card">
        <h2>Giao dịch gần nhất</h2>
        {recent.error ? <p className="err">{recent.error}</p>
          : recent.loading ? <p className="muted">Đang tải…</p>
          : recent.data!.items.length === 0 ? <p className="muted">Chưa có giao dịch nào.</p>
          : <TxList items={recent.data!.items} />}
      </section>
    </>
  );
}

function Kpi({ label, value, tone, signed }: { label: string; value?: number; tone: 'in' | 'out'; signed?: boolean }) {
  return (
    <div className="kpi">
      <span className="muted small">{label}</span>
      <strong className={tone}>{value === undefined ? '…' : `${signed && value > 0 ? '+' : ''}${money(value)}`}</strong>
    </div>
  );
}

function StatusPanel({ s }: { s: { data?: Status; error?: string } }) {
  const [open, setOpen] = useState(false);
  if (s.error) return <section className="alert bad">Không kết nối được server: {s.error}</section>;
  if (!s.data) return null;
  const { lastHeartbeat: hb, lastReceivedAt, unparsedCount, unparsed, balanceGaps } = s.data;
  const stale = !hb || Date.now() - new Date(hb.at).getTime() > 24 * 3600 * 1000;
  return (
    <>
      <section className={`alert ${stale ? 'bad' : 'ok'}`}>
        {hb
          ? <>Điện thoại báo lần cuối <b>{ago(hb.at)}</b>{hb.pendingCount > 0 && <> · {hb.pendingCount} bản ghi đang chờ gửi</>}</>
          : <>Chưa nhận được tín hiệu nào từ điện thoại.</>}
        {lastReceivedAt && <> · thông báo mới nhất {ago(lastReceivedAt)}</>}
        {stale && hb && <div className="small">Quá 24 giờ không có tín hiệu — kiểm tra app và Tailscale trên điện thoại.</div>}
      </section>
      {balanceGaps.length > 0 && (
        <section className="alert warn">
          Số dư không liên tục ở {balanceGaps.length} chỗ — có thể đã sót giao dịch:
          <ul>{balanceGaps.map((g) => (
            <li key={g.id}>{when(g.transactionTime)}: lệch {money(Math.abs(g.missing))} ({g.missing > 0 ? 'thiếu khoản ra' : 'thiếu khoản vào'})</li>
          ))}</ul>
        </section>
      )}
      {unparsedCount > 0 && (
        <section className="alert warn">
          <button className="link" onClick={() => setOpen(!open)}>{unparsedCount} thông báo chưa đọc được {open ? '▾' : '▸'}</button>
          {open && <ul className="raw">{unparsed.map((u) => <li key={u.id}><span className="muted small">{when(u.postedAt)}</span><code>{u.text}</code></li>)}</ul>}
        </section>
      )}
    </>
  );
}

function MonthlyBars({ months, from, to, onPick }: { months: Month[]; from: string; to: string; onPick: (ym: string) => void }) {
  const series = useMemo(() => {
    const byKey = new Map(months.map((m) => [m.month, m]));
    const out: Month[] = [];
    for (let k = from; k <= to; k = shiftMonth(k, 1)) out.push(byKey.get(k) ?? { month: k, totalIn: 0, totalOut: 0, count: 0 });
    return out;
  }, [months, from, to]);
  const max = Math.max(1, ...series.flatMap((m) => [m.totalIn, m.totalOut]));
  return (
    <>
      <div className="bars" role="img" aria-label="Tiền vào và tiền ra theo tháng">
        {series.map((m) => (
          <button key={m.month} className="bar" onClick={() => onPick(m.month)}
            title={`${monthLabel(m.month)}\nVào: ${money(m.totalIn)}\nRa: ${money(m.totalOut)}`}>
            <div className="pair">
              <span className="b in" style={{ height: `${(m.totalIn / max) * 100}%` }} />
              <span className="b out" style={{ height: `${(m.totalOut / max) * 100}%` }} />
            </div>
            <span className="small muted">{Number(m.month.slice(5))}</span>
          </button>
        ))}
      </div>
      <div className="legend small muted"><span><i className="dot in" /> Tiền vào</span><span><i className="dot out" /> Tiền ra</span><span>Bấm một cột để xem tháng đó</span></div>
    </>
  );
}

function TxList({ items }: { items: Tx[] }) {
  return (
    <ul className="tx">
      {items.map((t) => (
        <li key={t.id}>
          <div className="desc">
            <span>{t.description || '(không có nội dung)'}</span>
            <span className="muted small meta"><span>{when(t.transactionTime)}</span>{t.balanceAfter != null && <span>SD {money(t.balanceAfter)}</span>}</span>
          </div>
          <strong className={t.direction}>{t.direction === 'in' ? '+' : '−'}{money(t.amount)}</strong>
        </li>
      ))}
    </ul>
  );
}

/* ------------------------------------------------------------------ */

function Transactions() {
  const [f, setF] = useState<Filters>({});
  const [q, setQ] = useState('');
  const [page, setPage] = useState(1);
  const pageSize = 50;

  // Gõ từ khóa: chờ 300ms mới tìm
  useEffect(() => {
    const t = setTimeout(() => { setF((x) => ({ ...x, q })); setPage(1); }, 300);
    return () => clearTimeout(t);
  }, [q]);

  const params = { from: f.from, to: f.to, direction: f.direction, q: f.q };
  const list = useLoad<{ items: Tx[]; total: number }>(`/api/v1/transactions${qs({ ...params, page, pageSize })}`);
  const pages = list.data ? Math.max(1, Math.ceil(list.data.total / pageSize)) : 1;
  const set = (patch: Filters) => { setF((x) => ({ ...x, ...patch })); setPage(1); };

  return (
    <section className="card">
      <div className="filters">
        <label>Từ ngày<input type="date" value={f.from ?? ''} onChange={(e) => set({ from: e.target.value || undefined })} /></label>
        <label>Đến ngày<input type="date" value={f.to ?? ''} onChange={(e) => set({ to: e.target.value || undefined })} /></label>
        <label>Loại
          <select value={f.direction ?? ''} onChange={(e) => set({ direction: e.target.value as Filters['direction'] })}>
            <option value="">Tất cả</option><option value="in">Tiền vào</option><option value="out">Tiền ra</option>
          </select>
        </label>
        <label className="grow">Tìm nội dung<input type="search" value={q} placeholder="vd: luong, grab…" onChange={(e) => setQ(e.target.value)} /></label>
      </div>
      <div className="row between">
        <span className="muted small">{list.data ? `${list.data.total} giao dịch` : ' '}</span>
        <a className="btn" href={`/api/v1/transactions/export${qs(params)}`}>Xuất CSV</a>
      </div>
      {list.error ? <p className="err">{list.error}</p>
        : list.loading && !list.data ? <p className="muted">Đang tải…</p>
        : list.data!.items.length === 0 ? <p className="muted">Không có giao dịch phù hợp.</p>
        : <TxList items={list.data!.items} />}
      {pages > 1 && (
        <div className="row center">
          <button className="ghost" disabled={page <= 1} onClick={() => setPage(page - 1)}>‹ Trước</button>
          <span className="small">Trang {page}/{pages}</span>
          <button className="ghost" disabled={page >= pages} onClick={() => setPage(page + 1)}>Sau ›</button>
        </div>
      )}
    </section>
  );
}

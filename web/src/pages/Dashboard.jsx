import { useEffect, useState } from 'react';
import { api, fmtINR } from '../lib/api.js';
import { StatCard, Spinner } from '../components/ui.jsx';

export default function Dashboard() {
  const [period, setPeriod] = useState('month');
  const [summary, setSummary] = useState(null);
  const [perf, setPerf] = useState(null);
  const [progress, setProgress] = useState(null);

  useEffect(() => {
    Promise.all([
      api.get(`/dashboard/summary?period=${period}`).then(setSummary),
      api.get('/dashboard/performance').then(setPerf),
      api.get('/targets/progress').then(setProgress),
    ]);
  }, [period]);

  if (!summary || !perf || !progress) return <Spinner label="Loading dashboard…" />;

  const pct = (n) => (n >= 100 ? 'bg-emerald-500' : n >= 50 ? 'bg-amber-500' : 'bg-rose-500');

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-xl font-bold text-slate-800">Sales Dashboard</h1>
        <div className="flex gap-2">
          {[['today', 'Today'], ['month', 'This Month']].map(([k, v]) => (
            <button key={k} onClick={() => setPeriod(k)} className={`btn ${period === k ? 'btn-primary' : 'btn-outline'}`}>{v}</button>
          ))}
        </div>
      </div>

      {/* Module 4 cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <StatCard label="Total Leads" value={summary.total_leads} tone="indigo" sub={`From ${summary.from}`} />
        <StatCard label="New Leads" value={summary.new_leads} tone="sky" sub="In period" />
        <StatCard label="Follow-up Due" value={summary.follow_up_due} tone="amber" sub="Right now" />
        <StatCard label="Interest" value={summary.interested} tone="violet" />
        <StatCard label="Quotation Sent" value={summary.quotation_sent} tone="teal" />
        <StatCard label="Bookings" value={summary.booking_confirmed} tone="emerald" sub={`${summary.new_bookings} new in period`} />
        <StatCard label="Lost" value={summary.lost} tone="rose" />
        <StatCard label="Revenue" value={fmtINR(summary.revenue)} tone="slate" sub="Confirmed bookings" />
      </div>

      {/* Module 6 overall target */}
      <div className="card">
        <div className="flex items-center justify-between mb-2">
          <h2 className="font-semibold text-slate-800">Monthly Booking Target</h2>
          <span className="text-sm text-slate-500">{progress.month}</span>
        </div>
        {progress.overall.target_bookings > 0 ? (
          <>
            <div className="flex flex-wrap items-center gap-x-6 gap-y-1 text-sm">
              <span>Target <b>{progress.overall.target_bookings}</b></span>
              <span>Achieved <b className="text-emerald-600">{progress.overall.achieved}</b></span>
              <span>Remaining <b className="text-amber-600">{progress.overall.remaining}</b></span>
              <span>Achievement <b>{progress.overall.pct}%</b></span>
            </div>
            <div className="mt-2 h-2.5 bg-slate-100 rounded-full overflow-hidden">
              <div className={`h-full rounded-full ${pct(progress.overall.pct)}`} style={{ width: `${Math.min(100, progress.overall.pct)}%` }} />
            </div>
          </>
        ) : (
          <div className="text-sm text-slate-400">No company target set for this month.</div>
        )}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Module 5 employee performance */}
        <div className="card !p-0 overflow-x-auto">
          <div className="p-4 pb-2">
            <h2 className="font-semibold text-slate-800">Employee Performance</h2>
            <div className="text-xs text-slate-400">This month · side-by-side view</div>
          </div>
          <table className="w-full min-w-[480px]">
            <thead className="bg-slate-50 border-y">
              <tr>
                <th className="table-th">Sales exec</th>
                <th className="table-th">Leads</th>
                <th className="table-th">Follow-ups done</th>
                <th className="table-th">Bookings</th>
                <th className="table-th">Revenue</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {perf.employees.map((e) => (
                <tr key={e.user_id}>
                  <td className="table-td font-medium">{e.full_name}</td>
                  <td className="table-td">{e.leads_assigned}</td>
                  <td className="table-td">{e.followups_completed}</td>
                  <td className="table-td font-semibold text-emerald-600">{e.bookings_closed}</td>
                  <td className="table-td">{fmtINR(e.revenue)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot className="bg-slate-50">
              <tr>
                <td className="table-td font-semibold">Total</td>
                <td className="table-td font-semibold">{perf.totals.leads_assigned}</td>
                <td className="table-td font-semibold">{perf.totals.followups_completed}</td>
                <td className="table-td font-semibold">{perf.totals.bookings_closed}</td>
                <td className="table-td font-semibold">{fmtINR(perf.totals.revenue)}</td>
              </tr>
            </tfoot>
          </table>
        </div>

        {/* Module 6 per-employee targets */}
        <div className="card !p-0 overflow-x-auto">
          <div className="p-4 pb-2">
            <h2 className="font-semibold text-slate-800">Per-Employee Targets</h2>
            <div className="text-xs text-slate-400">Achieved / Remaining / %</div>
          </div>
          {progress.employees.length === 0 ? (
            <div className="p-4 text-sm text-slate-400">No per-employee targets set.</div>
          ) : (
            <table className="w-full min-w-[420px]">
              <thead className="bg-slate-50 border-y">
                <tr>
                  <th className="table-th">Sales exec</th>
                  <th className="table-th">Target</th>
                  <th className="table-th">Achieved</th>
                  <th className="table-th">Remaining</th>
                  <th className="table-th">%</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {progress.employees.map((e) => (
                  <tr key={e.user_id}>
                    <td className="table-td font-medium">{e.full_name}</td>
                    <td className="table-td">{e.target_bookings}</td>
                    <td className="table-td text-emerald-600 font-medium">{e.achieved}</td>
                    <td className="table-td">{e.remaining}</td>
                    <td className="table-td">
                      <div className="flex items-center gap-2">
                        <div className="w-14 h-1.5 bg-slate-100 rounded-full overflow-hidden">
                          <div className={`h-full ${pct(e.pct)}`} style={{ width: `${Math.min(100, e.pct)}%` }} />
                        </div>
                        <span className="text-xs">{e.pct}%</span>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}
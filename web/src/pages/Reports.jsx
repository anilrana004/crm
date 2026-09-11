import { useEffect, useState } from 'react';
import { api, SOURCE_LABELS, fmtINR } from '../lib/api.js';
import { Spinner, Empty } from '../components/ui.jsx';

export default function Reports() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [data, setData] = useState(null);

  async function load() {
    const q = new URLSearchParams();
    if (from) q.set('from', from);
    if (to) q.set('to', to);
    const res = await api.get(`/reports/marketing?${q.toString()}`);
    setData(res);
  }
  useEffect(() => { load(); }, []);

  function apply(e) {
    e.preventDefault();
    load();
  }

  const maxLeads = data ? Math.max(1, ...data.data.map((d) => d.leads)) : 1;

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-slate-800">Marketing Report</h1>
      <p className="text-sm text-slate-500">Leads → Bookings conversion, grouped by source.</p>

      <form onSubmit={apply} className="card flex flex-col sm:flex-row gap-2 items-end">
        <div>
          <label className="label">From</label>
          <input className="input" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div>
          <label className="label">To</label>
          <input className="input" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
        <button className="btn-primary">Apply</button>
      </form>

      {!data ? <Spinner /> : data.data.length === 0 ? <Empty message="No leads in the selected range" /> : (
        <>
          <div className="card grid grid-cols-3 gap-4">
            <div>
              <div className="text-xs uppercase text-slate-500 font-semibold">Total Leads</div>
              <div className="text-2xl font-bold">{data.totals.leads}</div>
            </div>
            <div>
              <div className="text-xs uppercase text-slate-500 font-semibold">Bookings</div>
              <div className="text-2xl font-bold text-emerald-600">{data.totals.bookings}</div>
            </div>
            <div>
              <div className="text-xs uppercase text-slate-500 font-semibold">Conversion</div>
              <div className="text-2xl font-bold">{data.totals.conversion}%</div>
            </div>
          </div>

          <div className="card !p-0 overflow-x-auto">
            <table className="w-full">
              <thead className="bg-slate-50 border-b">
                <tr>
                  <th className="table-th">Source</th>
                  <th className="table-th">Leads</th>
                  <th className="table-th">Bookings</th>
                  <th className="table-th">Lost</th>
                  <th className="table-th">Revenue</th>
                  <th className="table-th">Conversion</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {data.data.map((d) => (
                  <tr key={d.source} className="hover:bg-slate-50">
                    <td className="table-td font-medium">{SOURCE_LABELS[d.source] || d.source}</td>
                    <td className="table-td">
                      {d.leads}
                      <div className="w-24 h-1.5 bg-slate-100 rounded-full mt-1 overflow-hidden">
                        <div className="h-full bg-indigo-500" style={{ width: `${(d.leads / maxLeads) * 100}%` }} />
                      </div>
                    </td>
                    <td className="table-td font-semibold text-emerald-600">{d.bookings}</td>
                    <td className="table-td text-rose-500">{d.lost}</td>
                    <td className="table-td">{fmtINR(d.revenue)}</td>
                    <td className="table-td">
                      <span className={`badge ${d.conversion >= 15 ? 'bg-emerald-100 text-emerald-700' : d.conversion >= 5 ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-600'}`}>
                        {d.conversion}%
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
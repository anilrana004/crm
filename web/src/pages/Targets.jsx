import { useEffect, useState } from 'react';
import { api, fmtINR } from '../lib/api.js';
import { useAuth } from '../lib/AuthContext.jsx';
import { Badge, Modal, Spinner, Empty } from '../components/ui.jsx';

export default function Targets() {
  const { user } = useAuth();
  const canEdit = ['admin', 'manager'].includes(user?.role);
  const [users, setUsers] = useState([]);
  const [progress, setProgress] = useState(null);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ user_id: '', target_bookings: '', target_revenue: '' });

  async function load() {
    const p = await api.get('/targets/progress');
    setProgress(p);
    const u = await api.get('/auth/users');
    setUsers(u.users.filter((x) => x.role === 'sales'));
  }
  useEffect(() => { load(); }, []);

  async function save(e) {
    e.preventDefault();
    await api.post('/targets', {
      user_id: form.user_id || null,
      target_bookings: Number(form.target_bookings) || 0,
      target_revenue: Number(form.target_revenue) || 0,
    });
    setOpen(false);
    setForm({ user_id: '', target_bookings: '', target_revenue: '' });
    load();
  }

  if (!progress) return <Spinner />;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-slate-800">Sales Targets</h1>
        {canEdit && <button className="btn-primary" onClick={() => setOpen(true)}>+ Set target</button>}
      </div>
      <p className="text-sm text-slate-500">Monthly targets ({progress.month}). Achieved is computed automatically from confirmed bookings.</p>

      {/* Company-wide */}
      <div className="card">
        <div className="flex items-center justify-between mb-2">
          <h2 className="font-semibold text-slate-800">Company-wide</h2>
          <Badge cls="bg-indigo-100 text-indigo-700">{progress.overall.pct}% achieved</Badge>
        </div>
        {progress.overall.target_bookings > 0 ? (
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
            <div><span className="text-slate-500">Target</span><div className="font-bold">{progress.overall.target_bookings} bookings</div></div>
            <div><span className="text-slate-500">Achieved</span><div className="font-bold text-emerald-600">{progress.overall.achieved}</div></div>
            <div><span className="text-slate-500">Remaining</span><div className="font-bold text-amber-600">{progress.overall.remaining}</div></div>
            <div><span className="text-slate-500">Achievement</span><div className="font-bold">{progress.overall.pct}%</div></div>
          </div>
        ) : (
          <Empty message="No company target set. Use '+ Set target' with no employee selected." />
        )}
      </div>

      {/* Per employee */}
      {progress.employees.length === 0 ? (
        <Empty message="No per-employee targets set yet" />
      ) : (
        <div className="card !p-0 overflow-x-auto">
          <table className="w-full">
            <thead className="bg-slate-50 border-b">
              <tr>
                <th className="table-th">Sales exec</th>
                <th className="table-th">Target bookings</th>
                <th className="table-th">Achieved</th>
                <th className="table-th">Remaining</th>
                <th className="table-th">Achievement</th>
                <th className="table-th">Revenue target</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {progress.employees.map((e) => (
                <tr key={e.user_id} className="hover:bg-slate-50">
                  <td className="table-td font-medium">{e.full_name}</td>
                  <td className="table-td">{e.target_bookings}</td>
                  <td className="table-td text-emerald-600 font-semibold">{e.achieved}</td>
                  <td className="table-td">{e.remaining}</td>
                  <td className="table-td">
                    <div className="flex items-center gap-2">
                      <div className="w-24 h-2 bg-slate-100 rounded-full overflow-hidden">
                        <div className={`h-full ${e.pct >= 100 ? 'bg-emerald-500' : e.pct >= 50 ? 'bg-amber-500' : 'bg-rose-500'}`} style={{ width: `${Math.min(100, e.pct)}%` }} />
                      </div>
                      <span className="text-sm font-medium">{e.pct}%</span>
                    </div>
                  </td>
                  <td className="table-td">{e.target_revenue > 0 ? fmtINR(e.target_revenue) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Modal open={open} onClose={() => setOpen(false)} title="Set monthly target">
        <form onSubmit={save} className="space-y-3">
          <div>
            <label className="label">Sales employee (blank = company-wide)</label>
            <select className="input" value={form.user_id} onChange={(e) => setForm({ ...form, user_id: e.target.value })}>
              <option value="">Company-wide</option>
              {users.map((u) => <option key={u.id} value={u.id}>{u.full_name}</option>)}
            </select>
          </div>
          <div>
            <label className="label">Target bookings (e.g. 50)</label>
            <input className="input" type="number" min="0" value={form.target_bookings} onChange={(e) => setForm({ ...form, target_bookings: e.target.value })} />
          </div>
          <div>
            <label className="label">Target revenue (₹)</label>
            <input className="input" type="number" min="0" value={form.target_revenue} onChange={(e) => setForm({ ...form, target_revenue: e.target.value })} />
          </div>
          <div className="text-xs text-slate-500">Saves for {progress.month}. Re-saving overwrites.</div>
          <button className="btn-primary w-full">Save target</button>
        </form>
      </Modal>
    </div>
  );
}
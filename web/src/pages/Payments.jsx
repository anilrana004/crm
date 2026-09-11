import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, fmtINR, fmtDate } from '../lib/api.js';
import { Badge, Spinner, Empty } from '../components/ui.jsx';

const STATUS_CLS = {
  pending: 'bg-amber-100 text-amber-700',
  partial: 'bg-sky-100 text-sky-700',
  completed: 'bg-emerald-100 text-emerald-700',
  overdue: 'bg-rose-100 text-rose-700',
  cancelled: 'bg-slate-100 text-slate-500',
};

export default function Payments() {
  const [payments, setPayments] = useState([]);
  const [users, setUsers] = useState([]);
  const [filter, setFilter] = useState('');
  const [loading, setLoading] = useState(true);

  async function load() {
    setLoading(true);
    try {
      const res = await api.get(`/payments${filter ? `?status=${filter}` : ''}`);
      setPayments(res.payments);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => { load(); api.get('/auth/users').then((r) => setUsers(r.users)); }, [filter]);

  async function markCompleted(p) {
    await api.patch(`/payments/${p.id}`, { payment_status: 'completed' });
    load();
  }

  async function markOverdue(p) {
    await api.patch(`/payments/${p.id}`, { payment_status: 'overdue' });
    load();
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-slate-800">Payments</h1>

      <div className="flex gap-2 overflow-x-auto">
        {['', 'pending', 'partial', 'completed', 'overdue'].map((s) => (
          <button key={s || 'all'} onClick={() => setFilter(s)} className={`btn ${filter === s ? 'btn-primary' : 'btn-outline'}`}>
            {s === '' ? 'All' : s}
          </button>
        ))}
      </div>

      {loading ? <Spinner /> : payments.length === 0 ? <Empty message="No payment records" /> : (
        <div className="hid md:block card !p-0 overflow-x-auto">
          <table className="w-full min-w-[700px]">
            <thead className="bg-slate-50 border-b">
              <tr>
                <th className="table-th">Customer</th>
                <th className="table-th">Booking</th>
                <th className="table-th">Advance</th>
                <th className="table-th">Balance</th>
                <th className="table-th">Due date</th>
                <th className="table-th">Status</th>
                <th className="table-th">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {payments.map((p) => {
                const daysLeft = Math.ceil((new Date(p.due_date) - new Date()) / 86400000);
                return (
                  <tr key={p.id} className="hover:bg-slate-50">
                    <td className="table-td">
                      <Link to={`/leads/${p.lead_id}`} className="text-indigo-600 hover:underline font-medium">{p.customer_name}</Link>
                      <div className="text-xs text-slate-400">{p.mobile_number} · {p.owner_name}</div>
                    </td>
                    <td className="table-td font-medium">{fmtINR(p.booking_amount)}</td>
                    <td className="table-td">{fmtINR(p.advance_amount)}</td>
                    <td className="table-td font-bold">{fmtINR(p.balance_amount)}</td>
                    <td className="table-td">
                      {fmtDate(p.due_date)}
                      {p.payment_status !== 'completed' && p.balance_amount > 0 && daysLeft >= 0 && daysLeft <= 3 && (
                        <div className="text-[11px] text-rose-600">due in {daysLeft}d ⏰</div>
                      )}
                    </td>
                    <td className="table-td"><Badge cls={STATUS_CLS[p.payment_status]}>{p.payment_status}</Badge></td>
                    <td className="table-td">
                      {p.payment_status === 'partial' && (
                        <button className="btn-success btn-sm" onClick={() => markCompleted(p)}>Full paid</button>
                      )}
                      {p.payment_status === 'pending' && (
                        <button className="btn-outline btn-sm" onClick={() => markOverdue(p)}>Mark overdue</button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Mobile cards */}
      <div className="md:hidden space-y-2">
        {payments.map((p) => (
          <div key={p.id} className="card">
            <div className="flex justify-between items-start">
              <Link to={`/leads/${p.lead_id}`} className="font-semibold text-indigo-600">{p.customer_name}</Link>
              <Badge cls={STATUS_CLS[p.payment_status]}>{p.payment_status}</Badge>
            </div>
            <div className="grid grid-cols-2 gap-1 mt-2 text-sm">
              <span className="text-slate-500">Booking</span><span className="text-right">{fmtINR(p.booking_amount)}</span>
              <span className="text-slate-500">Advance</span><span className="text-right">{fmtINR(p.advance_amount)}</span>
              <span className="text-slate-500">Balance</span><span className="text-right font-bold">{fmtINR(p.balance_amount)}</span>
              <span className="text-slate-500">Due</span><span className="text-right">{fmtDate(p.due_date)}</span>
            </div>
            {p.payment_status === 'partial' && (
              <button className="btn-success btn-sm w-full mt-2" onClick={() => markCompleted(p)}>Record full payment</button>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
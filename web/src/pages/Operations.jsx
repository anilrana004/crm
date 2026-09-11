import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, fmtDate } from '../lib/api.js';
import { Badge, Modal, Spinner, Empty } from '../components/ui.jsx';

const HOTEL = ['pending', 'confirmed', 'cancelled'];
const TRANSPORT = ['pending', 'assigned', 'confirmed', 'cancelled'];

export default function Operations() {
  const [ops, setOps] = useState([]);
  const [drivers, setDrivers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(null);

  async function load() {
    setLoading(true);
    try {
      const [o, d] = await Promise.all([api.get('/operations'), api.get('/operations/meta/drivers')]);
      setOps(o.operations);
      setDrivers(d.drivers);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => { load(); }, []);

  async function save(e) {
    e.preventDefault();
    const fd = new FormData(e.target);
    const body = {
      hotel_status: fd.get('hotel_status'),
      transport: fd.get('transport'),
      driver_assigned_id: fd.get('driver_assigned_id') || null,
      ops_notes: fd.get('ops_notes') || null,
    };
    await api.patch(`/operations/${editing.id}`, body);
    setEditing(null);
    load();
  }

  const statusCls = (s) =>
    s === 'confirmed' || s === 'cleared' || s === 'assigned'
      ? 'bg-emerald-100 text-emerald-700'
      : s === 'cancelled' ? 'bg-rose-100 text-rose-700'
      : s === 'partial' ? 'bg-amber-100 text-amber-700'
      : 'bg-slate-100 text-slate-600';

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-slate-800">Operations</h1>
      <p className="text-sm text-slate-500">Auto-created from confirmed bookings. Booking IDs follow the TOH-YYYY-XXXX format.</p>

      {loading ? <Spinner /> : ops.length === 0 ? <Empty message="No operations yet" /> : (
        <div className="card !p-0 overflow-x-auto">
          <table className="w-full min-w-[800px]">
            <thead className="bg-slate-50 border-b">
              <tr>
                <th className="table-th">Booking ID</th>
                <th className="table-th">Customer</th>
                <th className="table-th">Package</th>
                <th className="table-th">Pax</th>
                <th className="table-th">Travel date</th>
                <th className="table-th">Hotel</th>
                <th className="table-th">Transport</th>
                <th className="table-th">Driver</th>
                <th className="table-th">Payment</th>
                <th className="table-th"></th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {ops.map((o) => (
                <tr key={o.id} className="hover:bg-slate-50">
                  <td className="table-td font-mono font-bold text-slate-800">{o.booking_id}</td>
                  <td className="table-td">
                    <Link to={`/leads/${o.lead_id}`} className="text-indigo-600 hover:underline">{o.customer_name}</Link>
                    <div className="text-xs text-slate-400">{o.mobile_number}</div>
                  </td>
                  <td className="table-td">{o.package_name || '—'}</td>
                  <td className="table-td">{o.pax}</td>
                  <td className="table-td">{fmtDate(o.travel_date)}</td>
                  <td className="table-td"><Badge cls={statusCls(o.hotel_status)}>{o.hotel_status}</Badge></td>
                  <td className="table-td"><Badge cls={statusCls(o.transport)}>{o.transport}</Badge></td>
                  <td className="table-td text-sm">{o.driver_name || '—'}</td>
                  <td className="table-td">
                    <div className="text-[11px]"><Badge cls={statusCls(o.advance_status)}>{o.advance_status}</Badge> / <Badge cls={statusCls(o.balance_status)}>{o.balance_status}</Badge></div>
                  </td>
                  <td className="table-td">
                    <button className="btn-outline btn-sm" onClick={() => setEditing(o)}>Operate</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Mobile cards */}
      <div className="md:hidden space-y-2">
        {ops.map((o) => (
          <div key={o.id} className="card">
            <div className="flex justify-between items-center">
              <span className="font-mono font-bold">{o.booking_id}</span>
              <button className="btn-outline btn-sm" onClick={() => setEditing(o)}>Operate</button>
            </div>
            <div className="mt-1 font-semibold">{o.customer_name}</div>
            <div className="text-xs text-slate-500">{o.package_name} · {o.pax} pax · {fmtDate(o.travel_date)}</div>
            <div className="flex flex-wrap gap-1 mt-2 text-[11px]">
              <Badge cls={statusCls(o.hotel_status)}>Hotel {o.hotel_status}</Badge>
              <Badge cls={statusCls(o.transport)}>{o.transport}</Badge>
              <Badge cls={statusCls(o.advance_status)}>Adv {o.advance_status}</Badge>
              <Badge cls={statusCls(o.balance_status)}>Bal {o.balance_status}</Badge>
            </div>
            <div className="text-xs text-slate-500 mt-1">Driver: {o.driver_name || '—'}</div>
          </div>
        ))}
      </div>

      <Modal open={!!editing} onClose={() => setEditing(null)} title={`Operate · ${editing?.booking_id || ''}`}>
        {editing && (
          <form onSubmit={save} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="label">Hotel status</label>
                <select className="input capitalize" name="hotel_status" defaultValue={editing.hotel_status}>
                  {HOTEL.map((h) => <option key={h} value={h}>{h}</option>)}
                </select>
              </div>
              <div>
                <label className="label">Transport</label>
                <select className="input capitalize" name="transport" defaultValue={editing.transport}>
                  {TRANSPORT.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <div className="col-span-2">
                <label className="label">Assign driver</label>
                <select className="input" name="driver_assigned_id" defaultValue={editing.driver_assigned_id || ''}>
                  <option value="">— No driver —</option>
                  {drivers.map((d) => <option key={d.id} value={d.id}>{d.full_name} ({d.vehicle_type}{d.vehicle_number ? ` · ${d.vehicle_number}` : ''})</option>)}
                </select>
              </div>
              <div className="col-span-2">
                <label className="label">Ops notes</label>
                <textarea className="input capitalize" name="ops_notes" rows="3" defaultValue={editing.ops_notes || ''} />
              </div>
            </div>
            <button className="btn-primary w-full">Save</button>
          </form>
        )}
      </Modal>
    </div>
  );
}
import { useEffect, useState, useCallback } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, STATUS_LABELS, STATUS_COLORS, SOURCE_LABELS, TASK_STATUS, fmtINR, fmtDate, fmtDateTime } from '../lib/api.js';
import { Badge, Modal, Spinner, Empty } from '../components/ui.jsx';

const FLOW = ['new', 'interested', 'quotation_sent', 'booking_confirmed', 'lost'];

export default function LeadDetail() {
  const { id } = useParams();
  const [data, setData] = useState(null);
  const [templates, setTemplates] = useState([]);
  const [drivers, setDrivers] = useState([]);
  const [users, setUsers] = useState([]);
  const [waPreview, setWaPreview] = useState(null);
  const [waKey, setWaKey] = useState('');
  const [showCall, setShowCall] = useState(false);
  const [showPayment, setShowPayment] = useState(false);
  const [statusFlow, setStatusFlow] = useState([]);

  const load = useCallback(async () => {
    const res = await api.get(`/leads/${id}`);
    setData(res);
    setStatusFlow(FLOW.slice(0, FLOW.indexOf(res.lead.status) + 1));
  }, [id]);

  useEffect(() => {
    load();
    api.get('/whatsapp/templates').then((r) => setTemplates(r.templates));
    api.get('/operations/meta/drivers').then((r) => setDrivers(r.drivers));
    if (window) api.get('/auth/users').then((r) => setUsers(r.users));
  }, [load]);

  if (!data) return <Spinner label="Loading lead…" />;
  const { lead, tasks, payments, callLogs, operations } = data;

  async function transition(next) {
    await api.post(`/leads/${id}/status`, { status: next });
    await load();
  }

  async function completeTask(taskId) {
    await api.patch(`/tasks/${taskId}`, { status: 'completed' });
    await load();
  }

  async function readTemplate(key) {
    setWaKey(key);
    const res = await api.get(`/whatsapp/preview?key=${key}&lead_id=${id}`);
    setWaPreview(res);
  }

  async function saveCall(e) {
    e.preventDefault();
    const fd = new FormData(e.target);
    await api.post(`/leads/${id}/call-log`, {
      call_type: fd.get('call_type'),
      notes: fd.get('notes'),
      outcome: fd.get('outcome'),
      duration_secs: parseInt(fd.get('duration_secs') || '0', 10) || null,
    });
    setShowCall(false);
    await load();
  }

  async function savePayment(e) {
    e.preventDefault();
    const fd = new FormData(e.target);
    await api.post('/payments', {
      lead_id: id,
      booking_amount: Number(fd.get('booking_amount')),
      advance_amount: Number(fd.get('advance_amount') || 0),
      due_date: fd.get('due_date'),
      notes: fd.get('notes') || null,
    });
    setShowPayment(false);
    await load();
  }

  async function updatePayment(payId, advance) {
    await api.patch(`/payments/${payId}`, { advance_amount: Number(advance) });
    await load();
  }

  async function markPaymentCompleted(payId) {
    await api.patch(`/payments/${payId}`, { payment_status: 'completed' });
    await load();
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Link to="/leads" className="text-indigo-600 text-sm hover:underline">← Leads</Link>
      </div>

      {/* Header */}
      <div className="card">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-xl font-bold text-slate-800">{lead.customer_name}</h1>
            <div className="text-sm text-slate-500 mt-0.5">
              📞 {lead.mobile_number}{lead.whatsapp_number && lead.whatsapp_number !== lead.mobile_number ? ` · WA ${lead.whatsapp_number}` : ''}
              {lead.email ? ` · ${lead.email}` : ''}
            </div>
            <div className="text-xs text-slate-400 mt-1">
              <Badge cls={STATUS_COLORS[lead.status]}>{STATUS_LABELS[lead.status]}</Badge>
              {' '}<Badge cls="bg-slate-100 text-slate-600">{SOURCE_LABELS[lead.source]}</Badge>
              <span className="ml-1">· Owner: {lead.owner_name} · Package: {lead.package_name || lead.destination || '—'} · {lead.num_persons} pax · {fmtINR(lead.budget)}</span>
            </div>
            {lead.remarks && <div className="mt-2 text-sm text-slate-600 bg-slate-50 rounded-lg px-3 py-2">{lead.remarks}</div>}
          </div>
          <div className="flex flex-wrap gap-2">
            {lead.status === 'booking_confirmed' && operations && (
              <Badge cls="bg-emerald-600 text-white">Ops: {operations.booking_id}</Badge>
            )}
          </div>
        </div>

        {/* Status stepper */}
        <div className="mt-4 border-t pt-4">
          <div className="text-xs font-semibold text-slate-500 mb-2 uppercase">Lead status</div>
          <div className="flex flex-wrap items-center gap-2">
            {FLOW.map((s, i) => {
              const reached = statusFlow.includes(s);
              const isCurrent = lead.status === s;
              return (
                <div key={s} className="flex items-center gap-2">
                  <button
                    onClick={() => transition(s)}
                    disabled={isCurrent}
                    className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors ${
                      isCurrent
                        ? STATUS_COLORS[s]
                        : reached
                          ? 'border-slate-300 text-slate-500 hover:bg-slate-100'
                          : 'border-dashed border-slate-300 text-slate-400 hover:border-indigo-400 hover:text-indigo-600'
                    }`}
                  >
                    {i + 1}. {STATUS_LABELS[s]}
                  </button>
                  {i < FLOW.length - 1 && <span className="text-slate-300">→</span>}
                </div>
              );
            })}
          </div>
          {lead.status === 'booking_confirmed' && (
            <div className="mt-2 text-xs text-emerald-600">Booking confirmed — Ops team has been notified and {operations ? `Ops record ${operations.booking_id} created` : 'an Ops record is being created'}.</div>
          )}
          {lead.status === 'lost' && (
            <div className="mt-2 text-xs text-rose-500">Lead marked Lost — pending follow-ups cancelled.</div>
          )}
        </div>
      </div>

      {/* WhatsApp (Module 8) */}
      <div className="card">
        <h2 className="font-semibold text-slate-800 mb-3">Send on WhatsApp</h2>
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-3 gap-2">
          {templates.map((t) => (
            <button key={t.id} onClick={() => readTemplate(t.template_key)} className="btn-wa btn-sm justify-start text-left">
              💬 {t.name}
            </button>
          ))}
        </div>
        {waPreview && (
          <div className="mt-3 bg-green-50 rounded-xl p-4 border border-green-200">
            <div className="flex items-start justify-between gap-3">
              <div>
                <div className="text-xs font-semibold text-green-800 mb-1">{waPreview.name}</div>
                <pre className="whitespace-pre-wrap text-sm text-slate-700 leading-relaxed max-h-64 overflow-y-auto">{waPreview.message}</pre>
              </div>
              <button onClick={() => setWaPreview(null)} className="text-slate-400 hover:text-slate-600">×</button>
            </div>
            <a className="btn-wa w-full mt-3 justify-center" href={waPreview.wa_link} target="_blank" rel="noreferrer">
              📤 Open WhatsApp (send to {waPreview.mobile})
            </a>
          </div>
        )}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Follow-ups */}
        <div className="card">
          <h2 className="font-semibold text-slate-800 mb-3">Follow-ups &amp; Tasks ({tasks.length})</h2>
          {tasks.length === 0 ? <Empty message="No follow-ups scheduled" /> : (
            <ul className="space-y-2">
              {tasks.map((t) => (
                <li key={t.id} className="flex items-center justify-between gap-3 border border-slate-100 rounded-lg px-3 py-2">
                  <div className="min-w-0">
                    <div className={`text-sm font-medium ${t.status === 'overdue' ? 'text-rose-600' : 'text-slate-800'}`}>{t.title}</div>
                    <div className="text-xs text-slate-500">
                      {t.type} · due {fmtDateTime(t.due_at)}
                      {t.completed_at && ` · done ${fmtDateTime(t.completed_at)}`}
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <Badge cls={TASK_STATUS[t.status]}>{t.status}</Badge>
                    {t.status === 'pending' && (
                      <button className="btn-success btn-sm" onClick={() => completeTask(t.id)}>✓</button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>

        {/* Payments (Module 7) */}
        <div className="card">
          <div className="flex items-center justify-between mb-3">
            <h2 className="font-semibold text-slate-800">Payments</h2>
            <button className="btn-outline btn-sm" onClick={() => setShowPayment(true)}>+ Add payment</button>
          </div>
          {payments.length === 0 ? <Empty message="No payment record yet" /> : (
            <ul className="space-y-2">
              {payments.map((p) => {
                const daysLeft = Math.ceil((new Date(p.due_date) - new Date()) / 86400000);
                return (
                  <li key={p.id} className="border border-slate-100 rounded-lg px-3 py-2 space-y-1">
                    <div className="flex items-center justify-between text-sm">
                      <span className="font-medium">Total {fmtINR(p.booking_amount)}</span>
                      <Badge cls={p.payment_status === 'completed' ? 'bg-emerald-100 text-emerald-700' : p.payment_status === 'partial' ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-600'}>{p.payment_status}</Badge>
                    </div>
                    <div className="text-xs text-slate-500 grid grid-cols-3">
                      <span>Advance {fmtINR(p.advance_amount)}</span>
                      <span>Balance <b className="text-slate-700">{fmtINR(p.balance_amount)}</b></span>
                      <span>Due {fmtDate(p.due_date)}</span>
                    </div>
                    {p.payment_status !== 'completed' && p.balance_amount > 0 && (
                      <div className="flex items-center gap-2 pt-1">
                        {daysLeft >= 0 && daysLeft <= 3 && (
                          <span className="text-[11px] text-rose-600 font-medium">⏰ Balance due in {daysLeft} day(s)</span>
                        )}
                        <input type="number" min="0" className="input !py-1 !px-2 !text-xs w-28" placeholder="Advance ₹" onBlur={(e) => e.target.value && updatePayment(p.id, e.target.value)} defaultValue="" />
                        <button className="btn-success btn-sm" onClick={() => markPaymentCompleted(p.id)}>Full paid</button>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Call logs (Module 9) */}
        <div className="card">
          <div className="flex items-center justify-between mb-3">
            <h2 className="font-semibold text-slate-800">Call Logs</h2>
            <button className="btn-outline btn-sm" onClick={() => setShowCall(true)}>+ Add call</button>
          </div>
          {callLogs.length === 0 ? <Empty message="No calls logged" /> : (
            <ul className="space-y-2">
              {callLogs.map((c) => (
                <li key={c.id} className="border border-slate-100 rounded-lg px-3 py-2">
                  <div className="flex justify-between text-xs text-slate-500">
                    <span>{c.call_type} · {fmtDateTime(c.called_at)} · {c.user_name}</span>
                    {c.duration_secs != null && <span>{c.duration_secs}s</span>}
                  </div>
                  {c.outcome && <Badge cls="bg-slate-100 text-slate-600">{c.outcome}</Badge>}
                  {c.notes && <div className="text-sm text-slate-600 mt-1">{c.notes}</div>}
                </li>
              ))}
            </ul>
          )}
        </div>

        {/* Operations info */}
        <div className="card">
          <h2 className="font-semibold text-slate-800 mb-3">Operations</h2>
          {!operations ? (
            <Empty message={lead.status === 'booking_confirmed' ? 'Showing…' : 'Ops record is auto-created when the booking is confirmed'} />
          ) : (
            <div className="space-y-2 text-sm">
              <div className="flex justify-between"><span className="text-slate-500">Booking ID</span><b>{operations.booking_id}</b></div>
              <div className="flex justify-between"><span className="text-slate-500">Travel date</span><span>{fmtDate(operations.travel_date)}</span></div>
              <div className="flex justify-between"><span className="text-slate-500">Pax</span><span>{operations.pax}</span></div>
              <div className="flex justify-between"><span className="text-slate-500">Hotel status</span><span className="capitalize">{operations.hotel_status}</span></div>
              <div className="flex justify-between"><span className="text-slate-500">Transport</span><span className="capitalize">{operations.transport}</span></div>
              <div className="flex justify-between"><span className="text-slate-500">Advance</span><span className="capitalize">{operations.advance_status}</span></div>
              <div className="flex justify-between"><span className="text-slate-500">Balance</span><span className="capitalize">{operations.balance_status}</span></div>
              <Link to="/operations" className="btn-outline w-full mt-2">Open Operations module →</Link>
            </div>
          )}
        </div>
      </div>

      {/* Call modal */}
      <Modal open={showCall} onClose={() => setShowCall(false)} title="Log call">
        <form onSubmit={saveCall} className="space-y-3">
          <div>
            <label className="label">Type</label>
            <select className="input" name="call_type" defaultValue="follow_up">
              <option value="inbound">Inbound</option>
              <option value="outbound">Outbound</option>
              <option value="follow_up">Follow-up</option>
            </select>
          </div>
          <div>
            <label className="label">Outcome</label>
            <input className="input" name="outcome" placeholder="Interested, callback requested…" />
          </div>
          <div>
            <label className="label">Duration (seconds)</label>
            <input className="input" name="duration_secs" type="number" min="0" />
          </div>
          <div>
            <label className="label">Notes</label>
            <textarea className="input" name="notes" rows="3" />
          </div>
          <button className="btn-primary w-full">Save call log</button>
        </form>
      </Modal>

      {/* Payment modal */}
      <Modal open={showPayment} onClose={() => setShowPayment(false)} title="Add payment / booking record">
        <form onSubmit={savePayment} className="space-y-3">
          <div>
            <label className="label">Booking amount (₹) *</label>
            <input className="input" name="booking_amount" type="number" min="0" required />
          </div>
          <div>
            <label className="label">Advance received (₹)</label>
            <input className="input" name="advance_amount" type="number" min="0" />
          </div>
          <div>
            <label className="label">Balance due date *</label>
            <input className="input" name="due_date" type="date" required />
          </div>
          <div>
            <label className="label">Notes</label>
            <input className="input" name="notes" />
          </div>
          <div className="text-xs text-slate-500">Reminder is auto-created for the balance from 3 days before the due date.</div>
          <button className="btn-primary w-full">Save payment</button>
        </form>
      </Modal>
    </div>
  );
}
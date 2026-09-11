import { useEffect, useState } from 'react';
import { api, SOURCE_LABELS } from '../lib/api.js';

const EMPTY = {
  customer_name: '', mobile_number: '', whatsapp_number: '', email: '',
  source: 'website', destination: '', package_id: '', travel_date: '',
  num_persons: 1, budget: '', lead_owner_id: '', remarks: '',
};

export default function LeadForm({ onSaved, lead = null, users = [], packages = [] }) {
  const [form, setForm] = useState({ ...EMPTY, ...(lead ? {
    customer_name: lead.customer_name, mobile_number: lead.mobile_number,
    whatsapp_number: lead.whatsapp_number, email: lead.email || '', source: lead.source,
    destination: lead.destination || '', package_id: lead.package_id || '',
    travel_date: lead.travel_date ? lead.travel_date.slice(0, 10) : '',
    num_persons: lead.num_persons, budget: lead.budget || '', lead_owner_id: lead.lead_owner_id, remarks: lead.remarks || '',
  } : {}) });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  function set(field, value) {
    setForm((f) => ({ ...f, [field]: value }));
  }

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setErr('');
    try {
      const body = {
        ...form,
        budget: form.budget ? Number(form.budget) : null,
        num_persons: Number(form.num_persons) || 1,
        package_id: form.package_id || null,
        lead_owner_id: form.lead_owner_id || undefined,
      };
      if (lead) await api.patch(`/leads/${lead.id}`, body);
      else await api.post('/leads', body);
      onSaved();
    } catch (e2) {
      setErr(e2.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      {err && <div className="text-sm text-rose-600 bg-rose-50 rounded-lg px-3 py-2">{err}</div>}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div>
          <label className="label">Customer name *</label>
          <input className="input" value={form.customer_name} onChange={(e) => set('customer_name', e.target.value)} required />
        </div>
        <div>
          <label className="label">Mobile number *</label>
          <input className="input" value={form.mobile_number} onChange={(e) => set('mobile_number', e.target.value)} required />
        </div>
        <div>
          <label className="label">WhatsApp number</label>
          <input className="input" value={form.whatsapp_number} onChange={(e) => set('whatsapp_number', e.target.value)} />
        </div>
        <div>
          <label className="label">Email</label>
          <input className="input" type="email" value={form.email} onChange={(e) => set('email', e.target.value)} />
        </div>
        <div>
          <label className="label">Source (auto-tag)</label>
          <select className="input" value={form.source} onChange={(e) => set('source', e.target.value)}>
            {Object.entries(SOURCE_LABELS).map(([k, v]) => (
              <option key={k} value={k}>{v}</option>
            ))}
          </select>
        </div>
        <div>
          <label className="label">Owner</label>
          <select className="input" value={form.lead_owner_id} onChange={(e) => set('lead_owner_id', e.target.value)}>
            <option value="">Leave unassigned (I'm owner)</option>
            {users.map((u) => <option key={u.id} value={u.id}>{u.full_name}</option>)}
          </select>
        </div>
        <div>
          <label className="label">Destination</label>
          <input className="input" value={form.destination} onChange={(e) => set('destination', e.target.value)} placeholder="Kedarnath, Ladakh, Spiti…" />
        </div>
        <div>
          <label className="label">Package</label>
          <select className="input" value={form.package_id} onChange={(e) => set('package_id', e.target.value)}>
            <option value="">— Select —</option>
            {packages.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div>
          <label className="label">Travel date</label>
          <input className="input" type="date" value={form.travel_date} onChange={(e) => set('travel_date', e.target.value)} />
        </div>
        <div>
          <label className="label">No. of persons</label>
          <input className="input" type="number" min="1" value={form.num_persons} onChange={(e) => set('num_persons', e.target.value)} />
        </div>
        <div>
          <label className="label">Budget (₹)</label>
          <input className="input" type="number" min="0" value={form.budget} onChange={(e) => set('budget', e.target.value)} />
        </div>
      </div>
      <div>
        <label className="label">Remarks</label>
        <textarea className="input" rows="2" value={form.remarks} onChange={(e) => set('remarks', e.target.value)} />
      </div>
      <div className="flex justify-end gap-2 pt-2">
        <button type="button" className="btn-outline" onClick={() => {}}>Cancel</button>
        <button className="btn-primary" disabled={busy}>{busy ? 'Saving…' : lead ? 'Update lead' : 'Create lead'}</button>
      </div>
    </form>
  );
}
import { useEffect, useState } from 'react';
import { api, fmtINR } from '../lib/api.js';
import { Badge, Modal, Spinner, Empty } from '../components/ui.jsx';

const EMPTY = { name: '', cost: '', duration_days: '', itinerary: '', inclusions: '', exclusions: '', departure_date: '' };

export default function Packages() {
  const [packages, setPackages] = useState([]);
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState(EMPTY);
  const [editing, setEditing] = useState(null);
  const [open, setOpen] = useState(false);

  async function load() {
    setLoading(true);
    try {
      const res = await api.get('/packages');
      setPackages(res.packages);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => { load(); }, []);

  function openNew() { setEditing(null); setForm(EMPTY); setOpen(true); }
  function openEdit(p) {
    setEditing(p);
    setForm({
      name: p.name, cost: p.cost, duration_days: p.duration_days, itinerary: p.itinerary || '',
      inclusions: p.inclusions || '', exclusions: p.exclusions || '',
      departure_date: p.departure_date ? p.departure_date.slice(0, 10) : '',
    });
    setOpen(true);
  }

  async function save(e) {
    e.preventDefault();
    const body = { ...form, cost: Number(form.cost), duration_days: Number(form.duration_days), departure_date: form.departure_date || null };
    if (editing) await api.put(`/packages/${editing.id}`, body);
    else await api.post('/packages', body);
    setOpen(false);
    load();
  }

  async function remove(p) {
    if (!confirm(`Delete package "${p.name}"?`)) return;
    await api.del(`/packages/${p.id}`);
    load();
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-slate-800">Packages &amp; Trips</h1>
        <button className="btn-primary" onClick={openNew}>+ New package</button>
      </div>

      {loading ? <Spinner /> : packages.length === 0 ? <Empty message="No packages yet" /> : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {packages.map((p) => (
            <div key={p.id} className="card flex flex-col">
              <div className="flex items-start justify-between">
                <h3 className="font-semibold text-slate-800">{p.name}</h3>
                <Badge cls={p.is_active ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-200 text-slate-500'}>
                  {p.is_active ? 'Active' : 'Inactive'}
                </Badge>
              </div>
              <div className="text-sm text-slate-500 mt-1">
                <span className="font-bold text-slate-800">{fmtINR(p.cost)}</span> / person · {p.duration_days} days
                {p.departure_date && <div className="mt-0.5">📅 {p.departure_date.slice(0, 10)}</div>}
              </div>
              {p.itinerary && (
                <div className="text-xs text-slate-500 mt-2 line-clamp-3" dangerouslySetInnerHTML={{ __html: p.itinerary }} />
              )}
              <div className="mt-auto pt-3 flex gap-2">
                <button className="btn-outline btn-sm flex-1" onClick={() => openEdit(p)}>Edit</button>
                <button className="btn-danger btn-sm" onClick={() => remove(p)}>Delete</button>
              </div>
            </div>
          ))}
        </div>
      )}

      <Modal open={open} onClose={() => setOpen(false)} title={editing ? 'Edit package' : 'New package'} wide>
        <form onSubmit={save} className="space-y-3">
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <div className="sm:col-span-2">
              <label className="label">Name *</label>
              <input className="input" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required placeholder="Kedarnath, Char Dham…" />
            </div>
            <div>
              <label className="label">Duration (days) *</label>
              <input className="input" type="number" min="1" value={form.duration_days} onChange={(e) => setForm({ ...form, duration_days: e.target.value })} required />
            </div>
            <div>
              <label className="label">Cost / person (₹) *</label>
              <input className="input" type="number" min="0" value={form.cost} onChange={(e) => setForm({ ...form, cost: e.target.value })} required />
            </div>
            <div>
              <label className="label">Departure date</label>
              <input className="input" type="date" value={form.departure_date} onChange={(e) => setForm({ ...form, departure_date: e.target.value })} />
            </div>
          </div>
          <div>
            <label className="label">Itinerary (rich text / HTML)</label>
            <textarea className="input" rows="3" value={form.itinerary} onChange={(e) => setForm({ ...form, itinerary: e.target.value })} />
          </div>
          <div>
            <label className="label">Inclusions</label>
            <textarea className="input" rows="2" value={form.inclusions} onChange={(e) => setForm({ ...form, inclusions: e.target.value })} />
          </div>
          <div>
            <label className="label">Exclusions</label>
            <textarea className="input" rows="2" value={form.exclusions} onChange={(e) => setForm({ ...form, exclusions: e.target.value })} />
          </div>
          <button className="btn-primary w-full">{editing ? 'Save changes' : 'Create package'}</button>
        </form>
      </Modal>
    </div>
  );
}
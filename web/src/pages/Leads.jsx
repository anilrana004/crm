import { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { api, STATUS_LABELS, STATUS_COLORS, SOURCE_LABELS, fmtDateTime } from '../lib/api.js';
import { Badge, Modal, Spinner, Empty } from '../components/ui.jsx';
import LeadForm from '../components/LeadForm.jsx';

export default function Leads() {
  const [leads, setLeads] = useState([]);
  const [users, setUsers] = useState([]);
  const [packages, setPackages] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [filters, setFilters] = useState({ status: '', owner: '', source: '', from: '', to: '', q: '' });
  const [counts, setCounts] = useState({});

  const load = useCallback(async () => {
    setLoading(true);
    const params = new URLSearchParams();
    Object.entries(filters).forEach(([k, v]) => v && params.set(k, v));
    try {
      const res = await api.get(`/leads?${params.toString()}`);
      setLeads(res.leads);
      setCounts(
        res.counts.reduce((a, c) => ({ ...a, [c.status]: c.count }), {})
      );
    } finally {
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    load();
    api.get('/auth/users').then((r) => setUsers(r.users));
    api.get('/packages').then((r) => setPackages(r.packages));
  }, [load]);

  function setFilter(k, v) {
    setFilters((f) => ({ ...f, [k]: v }));
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-slate-800">Leads</h1>
        <button className="btn-primary" onClick={() => setShowForm(true)}>+ New Lead</button>
      </div>

      {/* Status quick counts */}
      <div className="grid grid-cols-5 sm:grid-cols-5 gap-2 overflow-x-auto">
        {Object.keys(STATUS_LABELS).map((s) => (
          <button
            key={s}
            onClick={() => setFilter('status', filters.status === s ? '' : s)}
            className={`card !p-2.5 text-center ${filters.status === s ? 'ring-2 ring-indigo-500' : ''}`}
          >
            <Badge cls={STATUS_COLORS[s]}>{STATUS_LABELS[s]}</Badge>
            <div className="mt-1 text-lg font-bold text-slate-800">{counts[s] ?? 0}</div>
          </button>
        ))}
      </div>

      {/* Filters */}
      <div className="card grid grid-cols-2 md:grid-cols-6 gap-2">
        <input className="input col-span-2" placeholder="Search name / mobile…" value={filters.q} onChange={(e) => setFilter('q', e.target.value)} />
        <select className="input" value={filters.owner} onChange={(e) => setFilter('owner', e.target.value)}>
          <option value="">All owners</option>
          {users.map((u) => <option key={u.id} value={u.id}>{u.full_name}</option>)}
        </select>
        <select className="input" value={filters.source} onChange={(e) => setFilter('source', e.target.value)}>
          <option value="">All sources</option>
          {Object.entries(SOURCE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <input className="input" type="date" value={filters.from} onChange={(e) => setFilter('from', e.target.value)} title="From" />
        <input className="input" type="date" value={filters.to} onChange={(e) => setFilter('to', e.target.value)} title="To" />
      </div>

      {/* List — cards on mobile, table on desktop */}
      {loading ? <Spinner /> : leads.length === 0 ? <Empty message="No leads match these filters" /> : (
        <>
          <div className="hidden md:block card !p-0 overflow-x-auto">
            <table className="w-full">
              <thead className="bg-slate-50 border-b">
                <tr>
                  <th className="table-th">Customer</th>
                  <th className="table-th">Contact</th>
                  <th className="table-th">Source</th>
                  <th className="table-th">Package</th>
                  <th className="table-th">Travel date</th>
                  <th className="table-th">Owner</th>
                  <th className="table-th">Status</th>
                  <th className="table-th">Follow-up</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {leads.map((l) => (
                  <tr key={l.id} className="hover:bg-slate-50">
                    <td className="table-td font-medium"><Link className="text-indigo-600 hover:underline" to={`/leads/${l.id}`}>{l.customer_name}</Link></td>
                    <td className="table-td">{l.mobile_number}</td>
                    <td className="table-td"><Badge cls="bg-slate-100 text-slate-600">{SOURCE_LABELS[l.source]}</Badge></td>
                    <td className="table-td">{l.package_name || l.destination || '—'}</td>
                    <td className="table-td">{l.travel_date ? l.travel_date.slice(0, 10) : '—'}</td>
                    <td className="table-td">{l.owner_name}</td>
                    <td className="table-td"><Badge cls={STATUS_COLORS[l.status]}>{STATUS_LABELS[l.status]}</Badge></td>
                    <td className="table-td">{fmtDateTime(l.follow_up_date)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="md:hidden space-y-3">
            {leads.map((l) => (
              <Link key={l.id} to={`/leads/${l.id}`} className="card block">
                <div className="flex items-start justify-between gap-2">
                  <div className="font-semibold text-slate-800">{l.customer_name}</div>
                  <Badge cls={STATUS_COLORS[l.status]}>{STATUS_LABELS[l.status]}</Badge>
                </div>
                <div className="text-sm text-slate-500 mt-1">📞 {l.mobile_number}</div>
                <div className="text-xs text-slate-400 mt-1 flex flex-wrap gap-2">
                  <span>{SOURCE_LABELS[l.source]}</span>
                  <span>· {l.package_name || l.destination || 'No package'}</span>
                  <span>· {l.owner_name}</span>
                </div>
                {l.follow_up_date && <div className="text-xs text-amber-600 mt-1">⏰ Follow-up {fmtDateTime(l.follow_up_date)}</div>}
              </Link>
            ))}
          </div>
        </>
      )}

      <Modal open={showForm} onClose={() => setShowForm(false)} title="New Lead">
        <LeadForm
          onSaved={() => {
            setShowForm(false);
            load();
          }}
          users={users}
          packages={packages}
        />
      </Modal>
    </div>
  );
}
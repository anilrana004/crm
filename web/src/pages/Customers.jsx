import { useEffect, useState } from 'react';
import { api, fmtINR, fmtDate } from '../lib/api.js';
import { Badge, Modal, Spinner, Empty } from '../components/ui.jsx';

const OFFER_TAGS = {
  kashmir: '🏔️ Kashmir', char_dham: '🛕 Char Dham', family_tour: '👨‍👩‍👧 Family', anniversary_package: '💍 Anniversary',
};

export default function Customers() {
  const [customers, setCustomers] = useState([]);
  const [detail, setDetail] = useState(null);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [suggestOnly, setSuggestOnly] = useState(false);

  async function load() {
    setLoading(true);
    try {
      const q = new URLSearchParams();
      if (search) q.set('q', search);
      if (suggestOnly) q.set('suggest', 'true');
      const res = await api.get(`/customers?${q.toString()}`);
      setCustomers(res.customers);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => { load(); }, [search, suggestOnly]);

  async function openDetail(c) {
    const res = await api.get(`/customers/${c.id}`);
    setDetail(res);
    setOpen(true);
  }

  async function toggleSuggest(c) {
    await api.patch(`/customers/${c.id}`, { suggest_offer: !c.suggest_offer });
    load();
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-slate-800">Customer Database</h1>
      <p className="text-sm text-slate-500">Permanently retained after trip completion, with full trip history.</p>

      <div className="card flex flex-col sm:flex-row gap-2">
        <input className="input flex-1" placeholder="Search name / mobile…" value={search} onChange={(e) => setSearch(e.target.value)} />
        <label className="flex items-center gap-2 text-sm text-slate-600 whitespace-nowrap">
          <input type="checkbox" checked={suggestOnly} onChange={(e) => setSuggestOnly(e.target.checked)} />
          Suggest-offer only
        </label>
      </div>

      {loading ? <Spinner /> : customers.length === 0 ? <Empty message="No customers" /> : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {customers.map((c) => (
            <div key={c.id} className="card">
              <div className="flex items-start justify-between">
                <button onClick={() => openDetail(c)} className="font-semibold text-indigo-600 hover:underline text-left">{c.full_name}</button>
                <label className="flex items-center gap-1 text-[11px] text-slate-500 cursor-pointer">
                  <input type="checkbox" checked={!!c.suggest_offer} onChange={() => toggleSuggest(c)} />
                  Offer
                </label>
              </div>
              <div className="text-sm text-slate-500 mt-1">📞 {c.mobile_number}</div>
              <div className="text-xs text-slate-400 mt-1">
                {c.total_trips} trip(s) · last {c.last_trip_date ? fmtDate(c.last_trip_date) : '—'} · {fmtINR(c.total_spent)}
              </div>
              {c.offer_tags && c.offer_tags.length > 0 && (
                <div className="flex flex-wrap gap-1 mt-2">
                  {c.offer_tags.map((t) => <Badge key={t} cls="bg-indigo-100 text-indigo-700">{OFFER_TAGS[t] || t}</Badge>)}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      <Modal open={open} onClose={() => setOpen(false)} title={detail?.customer?.full_name || 'Customer'}>
        {detail && (
          <div className="space-y-4">
            <div className="text-sm text-slate-600">
              📞 {detail.customer.mobile_number} · {detail.customer.email || 'no email'}
            </div>
            <div>
              <h4 className="font-semibold text-slate-800 mb-2">Trip History</h4>
              {detail.trips.length === 0 ? <Empty message="No trips" /> : (
                <div className="space-y-2">
                  {detail.trips.map((t) => (
                    <div key={t.id} className="border border-slate-100 rounded-lg px-3 py-2 flex justify-between text-sm">
                      <span>{t.package_name} · {t.pax} pax</span>
                      <span className="text-slate-500">{fmtDate(t.travel_date)} · {fmtINR(t.booking_amount)}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div>
              <h4 className="font-semibold text-slate-800 mb-2">Remarketing tags</h4>
              <div className="flex flex-wrap gap-2">
                {Object.entries(OFFER_TAGS).map(([k, v]) => (
                  <button
                    key={k}
                    className={`btn btn-sm ${detail.customer.offer_tags?.includes(k) ? 'btn-primary' : 'btn-outline'}`}
                    onClick={async () => {
                      const tags = detail.customer.offer_tags?.includes(k)
                        ? detail.customer.offer_tags.filter((t) => t !== k)
                        : [...(detail.customer.offer_tags || []), k];
                      await api.patch(`/customers/${detail.customer.id}`, { offer_tags: tags });
                      openDetail(detail.customer);
                    }}
                  >
                    {v}
                  </button>
                ))}
              </div>
            </div>
            {detail.customer.notes && <div className="text-sm text-slate-600 bg-slate-50 rounded-lg p-3">{detail.customer.notes}</div>}
          </div>
        )}
      </Modal>
    </div>
  );
}
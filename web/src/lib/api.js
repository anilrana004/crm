const BASE = '/api';

async function request(path, options = {}) {
  const token = localStorage.getItem('st_token');
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch(`${BASE}${path}`, { ...options, headers });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.error || `Request failed (${res.status})`);
    err.status = res.status;
    throw err;
  }
  return data;
}

export const api = {
  get: (p) => request(p),
  post: (p, body) => request(p, { method: 'POST', body: JSON.stringify(body) }),
  patch: (p, body) => request(p, { method: 'PATCH', body: JSON.stringify(body) }),
  put: (p, body) => request(p, { method: 'PUT', body: JSON.stringify(body) }),
  del: (p) => request(p, { method: 'DELETE' }),
};

export const STATUS_COLORS = {
  new: 'bg-sky-100 text-sky-700',
  interested: 'bg-amber-100 text-amber-700',
  quotation_sent: 'bg-violet-100 text-violet-700',
  booking_confirmed: 'bg-emerald-100 text-emerald-700',
  lost: 'bg-rose-100 text-rose-700',
};

export const STATUS_LABELS = {
  new: 'New',
  interested: 'Interested',
  quotation_sent: 'Quotation Sent',
  booking_confirmed: 'Booking Confirmed',
  lost: 'Lost',
};

export const SOURCE_LABELS = {
  google_ads: 'Google Ads',
  facebook_ads: 'Facebook Ads',
  instagram: 'Instagram',
  website: 'Website',
  whatsapp: 'WhatsApp',
  referral: 'Referral',
  justdial: 'Justdial',
  walk_in: 'Walk-in',
  b2b: 'B2B',
  existing_customer: 'Existing Customer',
};

export const TASK_STATUS = {
  pending: 'bg-amber-100 text-amber-700',
  completed: 'bg-emerald-100 text-emerald-700',
  overdue: 'bg-rose-100 text-rose-700',
  cancelled: 'bg-slate-100 text-slate-500',
};

export const fmtINR = (n) =>
  n == null ? '—' : `₹${Number(n).toLocaleString('en-IN', { maximumFractionDigits: 0 })}`;
export const fmtDate = (d) => (d ? new Date(d).toISOString().slice(0, 10) : '—');
export const fmtDateTime = (d) => {
  if (!d) return '—';
  const dt = new Date(d);
  return `${dt.toLocaleDateString('en-IN')} ${dt.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' })}`;
};
export function StatCard({ label, value, sub, tone = 'indigo' }) {
  const tones = {
    indigo: 'bg-indigo-600', sky: 'bg-sky-500', amber: 'bg-amber-500',
    emerald: 'bg-emerald-500', rose: 'bg-rose-500', violet: 'bg-violet-500',
    slate: 'bg-slate-600', teal: 'bg-teal-500',
  };
  return (
    <div className="card flex items-center gap-3">
      <div className={`${tones[tone]} w-10 h-10 rounded-lg flex items-center justify-center text-white text-lg shrink-0`}>
        {value}
      </div>
      <div className="min-w-0">
        <div className="text-[11px] uppercase tracking-wide text-slate-500 font-semibold truncate">{label}</div>
        <div className="text-lg font-bold text-slate-800 leading-tight">{value}</div>
        {sub && <div className="text-[11px] text-slate-400 truncate">{sub}</div>}
      </div>
    </div>
  );
}

export function Badge({ children, cls }) {
  return <span className={`badge ${cls}`}>{children}</span>;
}

export function Modal({ open, onClose, title, children, wide }) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 bg-black/50 flex items-end sm:items-center justify-center" onClick={onClose}>
      <div
        className={`bg-white w-full ${wide ? 'max-w-2xl' : 'max-w-lg'} max-h-[92vh] overflow-y-auto rounded-t-2xl sm:rounded-2xl shadow-xl`}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-3 border-b sticky top-0 bg-white rounded-t-2xl">
          <h3 className="font-semibold text-slate-800">{title}</h3>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-xl leading-none">×</button>
        </div>
        <div className="p-5">{children}</div>
      </div>
    </div>
  );
}

export function Spinner({ label = 'Loading…' }) {
  return (
    <div className="flex items-center justify-center py-16 text-slate-500 text-sm gap-2">
      <span className="animate-spin inline-block w-4 h-4 border-2 border-slate-300 border-t-indigo-600 rounded-full" />
      {label}
    </div>
  );
}

export function Empty({ message = 'Nothing here yet' }) {
  return <div className="card text-center py-10 text-slate-400 text-sm">{message}</div>;
}
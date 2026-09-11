"use client";

import type { LeadListParams, Trip } from "@/lib/api";

const STATUSES = ["NEW", "INTERESTED", "QUOTATION_SENT", "BOOKING_CONFIRMED", "LOST"];
const SOURCES = [
  "GOOGLE_ADS", "FACEBOOK_ADS", "INSTAGRAM", "WEBSITE", "WHATSAPP",
  "REFERRAL", "JUSTDIAL", "WALK_IN", "B2B", "EXISTING_CUSTOMER", "OTHER",
];
const HEATS = ["HOT", "WARM", "COLD"];

type LeadFiltersProps = {
  value: LeadListParams;
  trips: Trip[];
  onChange: (next: LeadListParams) => void;
};

/** Shared filter bar fed to BOTH the Kanban board and the table list —
 *  both views hit the same filtered /api/leads endpoint. */
export function LeadFilters({ value, trips, onChange }: LeadFiltersProps) {
  const set = (patch: Partial<LeadListParams>) => onChange({ ...value, ...patch });

  const select =
    "rounded-md border border-slate-300 px-3 py-1.5 text-sm outline-none focus:border-slate-900";
  const date =
    "rounded-md border border-slate-300 px-3 py-1.5 text-sm outline-none focus:border-slate-900";

  return (
    <div className="mb-4 flex flex-wrap items-center gap-2">
      <label className="text-xs font-medium text-slate-500">Search</label>
      <input
        value={value.search ?? ""}
        onChange={(e) => set({ search: e.target.value })}
        placeholder="Name or mobile"
        className="w-48 rounded-md border border-slate-300 px-3 py-1.5 text-sm outline-none focus:border-slate-900"
      />

      <label className="text-xs font-medium text-slate-500">Status</label>
      <select value={value.status ?? ""} onChange={(e) => set({ status: e.target.value })} className={select}>
        <option value="">All</option>
        {STATUSES.map((s) => (
          <option key={s} value={s}>{s}</option>
        ))}
      </select>

      <label className="text-xs font-medium text-slate-500">Source</label>
      <select value={value.source ?? ""} onChange={(e) => set({ source: e.target.value })} className={select}>
        <option value="">All</option>
        {SOURCES.map((s) => (
          <option key={s} value={s}>{s}</option>
        ))}
      </select>

      <label className="text-xs font-medium text-slate-500">Heat</label>
      <select value={value.heat ?? ""} onChange={(e) => set({ heat: e.target.value })} className={select}>
        <option value="">All</option>
        {HEATS.map((h) => (
          <option key={h} value={h}>{h}</option>
        ))}
      </select>

      <label className="text-xs font-medium text-slate-500">Trip</label>
      <select value={value.tripId ?? ""} onChange={(e) => set({ tripId: e.target.value })} className={select}>
        <option value="">All</option>
        {trips.map((t) => (
          <option key={t.id} value={t.id as string}>{t.name}</option>
        ))}
      </select>

      <label className="text-xs font-medium text-slate-500">Travel from</label>
      <input type="date" value={value.travelFrom ?? ""} onChange={(e) => set({ travelFrom: e.target.value })} className={date} />
      <label className="text-xs font-medium text-slate-500">to</label>
      <input type="date" value={value.travelTo ?? ""} onChange={(e) => set({ travelTo: e.target.value })} className={date} />

      {(value.search || value.status || value.source || value.heat || value.tripId || value.travelFrom || value.travelTo) && (
        <button
          onClick={() => onChange({})}
          className="text-xs font-medium text-slate-500 underline hover:text-slate-800"
        >
          Reset
        </button>
      )}
    </div>
  );
}
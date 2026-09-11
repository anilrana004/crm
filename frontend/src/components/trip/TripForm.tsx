"use client";

import { FormEvent, useState } from "react";
import { api, type TripDetail, type TripCreatePayload, type TripUpdatePayload, ApiClientError } from "@/lib/api";

const CATEGORIES = ["TREK", "PILGRIMAGE", "LEISURE", "CUSTOM"] as const;
const BOOKING_TYPES = ["FIXED_BATCH", "CUSTOM_FIT"] as const;

type TripFormProps = {
  trip?: TripDetail | null;
  onSaved: () => void;
  onClose: () => void;
};

export function TripForm({ trip, onSaved, onClose }: TripFormProps) {
  const [form, setForm] = useState({
    name: trip?.name ?? "",
    category: (trip?.category ?? "LEISURE") as TripCreatePayload["category"],
    bookingType: (trip?.bookingType ?? "FIXED_BATCH") as TripCreatePayload["bookingType"],
    baseCost: trip?.baseCost != null ? String(trip.baseCost) : "",
    durationDays: trip ? String(trip.durationDays) : "",
    itinerary: trip?.itinerary ?? "",
    inclusions: trip?.inclusions ?? "",
    exclusions: trip?.exclusions ?? "",
    active: trip?.active ?? true,
  });
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  function set<K extends keyof typeof form>(key: K, value: string | boolean) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSaving(true);
    try {
      const base = {
        name: form.name,
        category: form.category,
        bookingType: form.bookingType,
        baseCost: Number(form.baseCost),
        durationDays: Number(form.durationDays),
        itinerary: form.itinerary || undefined,
        inclusions: form.inclusions || undefined,
        exclusions: form.exclusions || undefined,
      };
      if (trip?.id) {
        const payload: TripUpdatePayload = { ...base, active: form.active };
        await api.updateTrip(trip.id, payload);
      } else {
        const payload: TripCreatePayload = base as TripCreatePayload;
        await api.createTrip(payload);
      }
      onSaved();
      onClose();
    } catch (err) {
      if (err instanceof ApiClientError && err.body.fieldErrors?.length) {
        setError(err.body.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(" · "));
      } else {
        setError(err instanceof Error ? err.message : "Could not save trip");
      }
    } finally {
      setSaving(false);
    }
  }

  const input =
    "mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900";
  const label = "block text-sm font-medium text-slate-700";

  return (
    <div className="fixed inset-0 z-20 flex items-start justify-center bg-slate-900/50 p-6 pt-16 overflow-y-auto">
      <form onSubmit={onSubmit} className="w-full max-w-lg rounded-xl bg-white p-6 shadow-xl">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-900">{trip ? `Edit trip — ${trip.name}` : "New trip"}</h2>
          <button type="button" onClick={onClose} className="text-slate-400 hover:text-slate-700">
            ✕
          </button>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <label className="col-span-2 block text-sm font-medium text-slate-700">
            Name
            <input required value={form.name} onChange={(e) => set("name", e.target.value)} className={input} />
          </label>
          <label className={`${label} col-span-2`}>
            Category
            <select
              value={form.category}
              onChange={(e) => set("category", e.target.value as TripCreatePayload["category"])}
              className={input}
            >
              {CATEGORIES.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </label>
          <label className={`${label} col-span-2`}>
            Booking type
            <select
              value={form.bookingType}
              onChange={(e) => set("bookingType", e.target.value as TripCreatePayload["bookingType"])}
              className={input}
            >
              {BOOKING_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </label>
          <label className={label}>
            Base cost (₹)
            <input
              required
              type="number"
              min="0"
              value={form.baseCost}
              onChange={(e) => set("baseCost", e.target.value)}
              className={input}
            />
          </label>
          <label className={label}>
            Duration (days)
            <input
              required
              type="number"
              min="1"
              value={form.durationDays}
              onChange={(e) => set("durationDays", e.target.value)}
              className={input}
            />
          </label>
          <label className={`${label} col-span-2`}>
            Itinerary
            <textarea rows={3} value={form.itinerary} onChange={(e) => set("itinerary", e.target.value)} className={input} />
          </label>
          <label className={`${label} col-span-2`}>
            Inclusions
            <textarea rows={2} value={form.inclusions} onChange={(e) => set("inclusions", e.target.value)} className={input} />
          </label>
          <label className={`${label} col-span-2`}>
            Exclusions
            <textarea rows={2} value={form.exclusions} onChange={(e) => set("exclusions", e.target.value)} className={input} />
          </label>
          {trip?.id && (
            <label className={`${label} col-span-2 flex items-center gap-2`}>
              <input
                type="checkbox"
                checked={form.active}
                onChange={(e) => set("active", e.target.checked)}
                className="h-4 w-4 rounded border-slate-300"
              />
              Active in catalogue
            </label>
          )}
        </div>

        {error && <p className="mt-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-600 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            disabled={saving}
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
          >
            {saving ? "Saving…" : trip ? "Save changes" : "Create trip"}
          </button>
        </div>
      </form>
    </div>
  );
}
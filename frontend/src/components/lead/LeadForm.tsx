"use client";

import { FormEvent, useEffect, useState } from "react";
import { api, type Lead, type LeadCreatePayload, type LeadUpdatePayload, type Trip, ApiClientError } from "@/lib/api";

const SOURCES = [
  "GOOGLE_ADS", "FACEBOOK_ADS", "INSTAGRAM", "WEBSITE", "WHATSAPP",
  "REFERRAL", "JUSTDIAL", "WALK_IN", "B2B", "EXISTING_CUSTOMER", "OTHER",
];

type LeadFormProps = {
  lead?: Lead | null;
  trips?: Trip[];
  onCreated?: () => void;
  onUpdated?: () => void;
  onClose: () => void;
};

export function LeadForm({ lead, trips = [], onCreated, onUpdated, onClose }: LeadFormProps) {
  const [tripsLoaded, setTripsLoaded] = useState<Trip[]>(trips);
  const [form, setForm] = useState({
    customerName: lead?.customerName ?? "",
    mobileNumber: lead?.mobileNumber ?? "",
    whatsappNumber: lead?.whatsappNumber ?? "",
    email: lead?.email ?? "",
    source: (lead?.source ?? "WEBSITE") as LeadCreatePayload["source"],
    destination: lead?.destination ?? "",
    tripId: lead?.tripId ?? "",
    travelDate: lead?.travelDate ?? "",
    numPersons: String(lead?.numPersons ?? "1"),
    budget: lead?.budget != null ? String(lead.budget) : "",
    followUpDate: lead?.followUpDate ?? "",
    remarks: lead?.remarks ?? "",
  });
  const [consent, setConsent] = useState(lead?.consentGiven ?? false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (tripsLoaded.length > 0) return;
    api.getTrips().then(setTripsLoaded).catch(() => setTripsLoaded([]));
  }, [tripsLoaded.length]);

  function set<K extends keyof typeof form>(key: K, value: string) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSaving(true);
    try {
      if (lead?.id) {
        const payload: LeadUpdatePayload = {
          customerName: form.customerName,
          email: form.email || undefined,
          whatsappNumber: form.whatsappNumber || undefined,
          destination: form.destination || undefined,
          tripId: form.tripId || undefined,
          travelDate: form.travelDate || undefined,
          numPersons: Number(form.numPersons),
          budget: form.budget ? Number(form.budget) : undefined,
          followUpDate: form.followUpDate || undefined,
          remarks: form.remarks || undefined,
        };
        await api.updateLead(lead.id, payload);
        onUpdated?.();
        onClose();
      } else {
        const payload: LeadCreatePayload = {
          customerName: form.customerName,
          mobileNumber: form.mobileNumber,
          whatsappNumber: form.whatsappNumber || undefined,
          email: form.email || undefined,
          source: form.source,
          destination: form.destination || undefined,
          tripId: form.tripId || undefined,
          travelDate: form.travelDate || undefined,
          numPersons: Number(form.numPersons),
          budget: form.budget ? Number(form.budget) : undefined,
          followUpDate: form.followUpDate || undefined,
          remarks: form.remarks || undefined,
          consentGiven: consent,
          consentScope: consent ? "contact for travel enquiry and follow-up" : undefined,
        };
        await api.createLead(payload);
        onCreated?.();
        onClose();
      }
    } catch (err) {
      if (err instanceof ApiClientError && err.body.fieldErrors?.length) {
        setError(err.body.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(" · "));
      } else {
        setError(err instanceof Error ? err.message : "Could not save lead");
      }
    } finally {
      setSaving(false);
    }
  }

  const input =
    "mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900";
  const readOnly =
    "mt-1 block w-full rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-500";

  return (
    <div className="fixed inset-0 z-20 flex items-start justify-center overflow-y-auto bg-slate-900/50 p-3 pt-safe sm:p-6 sm:pt-16">
      <form onSubmit={onSubmit} className="w-full max-w-lg rounded-xl bg-white p-4 shadow-xl sm:p-6">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-900">
            {lead ? `Edit lead — ${lead.customerName}` : "New lead"}
          </h2>
          <button type="button" onClick={onClose} className="text-slate-400 hover:text-slate-700">
            ✕
          </button>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <label className="block text-sm font-medium text-slate-700 sm:col-span-2">
            Customer name
            <input required value={form.customerName} onChange={(e) => set("customerName", e.target.value)} className={input} />
          </label>
          <label className="block text-sm font-medium text-slate-700">
            Mobile
            {lead ? (
              <span className={readOnly}>{lead.mobileNumber}</span>
            ) : (
              <input required placeholder="+91 98765 43210" value={form.mobileNumber} onChange={(e) => set("mobileNumber", e.target.value)} className={input} />
            )}
          </label>
          <label className="block text-sm font-medium text-slate-700">
            WhatsApp
            <input placeholder="optional" value={form.whatsappNumber} onChange={(e) => set("whatsappNumber", e.target.value)} className={input} />
          </label>
          <label className="block text-sm font-medium text-slate-700 sm:col-span-2">
            Email
            <input type="email" value={form.email} onChange={(e) => set("email", e.target.value)} className={input} />
          </label>
          <label className="block text-sm font-medium text-slate-700">
            Source
            {lead ? (
              <span className={readOnly}>{lead.source}</span>
            ) : (
              <select value={form.source} onChange={(e) => set("source", e.target.value)} className={input}>
                {SOURCES.map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            )}
          </label>
          <label className="block text-sm font-medium text-slate-700">
            Destination
            <input placeholder="Kedarnath" value={form.destination} onChange={(e) => set("destination", e.target.value)} className={input} />
          </label>
          <label className="block text-sm font-medium text-slate-700 sm:col-span-2">
            Trip (catalogue)
            <select value={form.tripId} onChange={(e) => set("tripId", e.target.value)} className={input}>
              <option value="">No catalogue trip</option>
              {tripsLoaded.map((t) => (
                <option key={t.id} value={t.id as string}>
                  {t.name} — {t.category}/{t.bookingType}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm font-medium text-slate-700">
            Travel date
            <input type="date" value={form.travelDate} onChange={(e) => set("travelDate", e.target.value)} className={input} />
          </label>
          <label className="block text-sm font-medium text-slate-700">
            Travelers
            <input type="number" min={1} max={50} value={form.numPersons} onChange={(e) => set("numPersons", e.target.value)} className={input} />
          </label>
          <label className="block text-sm font-medium text-slate-700">
            Budget (₹)
            <input type="number" min={0} value={form.budget} onChange={(e) => set("budget", e.target.value)} className={input} />
          </label>
          <label className="block text-sm font-medium text-slate-700">
            Follow-up date
            <input type="date" value={form.followUpDate} onChange={(e) => set("followUpDate", e.target.value)} className={input} />
          </label>
          <label className="block text-sm font-medium text-slate-700 sm:col-span-2">
            Remarks
            <textarea rows={2} value={form.remarks} onChange={(e) => set("remarks", e.target.value)} className={input} />
          </label>
        </div>

        {!lead && (
          <label className="mt-4 flex items-start gap-2 text-sm text-slate-600">
            <input
              type="checkbox"
              checked={consent}
              onChange={(e) => setConsent(e.target.checked)}
              className="mt-0.5"
            />
            <span>
              I have obtained the customer&apos;s consent to store their details and contact them about this enquiry
              (DPDPA).
            </span>
          </label>
        )}

        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}

        <div className="mt-5 flex justify-end gap-2">
          <button type="button" onClick={onClose} className="rounded-md px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100">
            Cancel
          </button>
          <button
            type="submit"
            disabled={saving || (!lead && !consent)}
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
          >
            {saving ? "Saving…" : lead ? "Save changes" : "Create lead"}
          </button>
        </div>
      </form>
    </div>
  );
}
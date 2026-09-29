"use client";

import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  api,
  ApiClientError,
  type Batch,
  type BookingCreatePayload,
  type Lead,
  type TripDetail,
} from "@/lib/api";
import { useAuth } from "@/lib/auth";

type BookingFormProps = {
  onSaved: () => void;
  onClose: () => void;
};

type TravellerRow = {
  fullName: string;
  age: string;
  gender: string;
  medical: boolean;
};

const GENDERS = ["M", "F", "Other"];

export function BookingForm({ onSaved, onClose }: BookingFormProps) {
  const { user } = useAuth();
  const [leads, setLeads] = useState<Lead[]>([]);
  const [trips, setTrips] = useState<TripDetail[]>([]);
  const [selectedLead, setSelectedLead] = useState("");
  const [selectedTrip, setSelectedTrip] = useState("");
  const [tripDetail, setTripDetail] = useState<TripDetail | null>(null);
  const [selectedBatch, setSelectedBatch] = useState("");
  const [travelDate, setTravelDate] = useState("");
  const [discount, setDiscount] = useState("");
  const [notes, setNotes] = useState("");
  const [rows, setRows] = useState<TravellerRow[]>([{ fullName: "", age: "", gender: "M", medical: false }]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api
      .getLeads({ size: 100 })
      .then((page) => {
        const candidates = page.content.filter(
          (l) =>
            (l.status === "QUOTATION_SENT" || l.status === "INTERESTED") &&
            (user?.role === "SALES" ? l.ownerId === user.id : true)
        );
        setLeads(candidates);
      })
      .catch(() => setError("Could not load leads"));
  }, [user]);

  useEffect(() => {
    api
      .getTrips(true)
      .then((list) => {
        setTrips(list);
        setSelectedTrip(list[0]?.id ?? "");
      })
      .catch(() => setError("Could not load trips"));
  }, []);

  useEffect(() => {
    if (!selectedTrip) {
      setTripDetail(null);
      return;
    }
    api
      .getTrip(selectedTrip)
      .then((detail) => {
        setTripDetail(detail);
        setSelectedBatch(detail.batches?.find((b) => b.status === "OPEN")?.id ?? "");
      })
      .catch(() => setError("Could not load trip detail"));
  }, [selectedTrip]);

  const isFixed = tripDetail?.bookingType === "FIXED_BATCH";
  const openBatches = useMemo(
    () => (tripDetail?.batches ?? []).filter((b) => b.status === "OPEN"),
    [tripDetail]
  );
  const chosenBatch: Batch | undefined = openBatches.find((b) => b.id === selectedBatch);
  const maxTravellers = chosenBatch ? Math.max(0, chosenBatch.available ?? 0) : 0;

  function setRow(index: number, patch: Partial<TravellerRow>) {
    setRows((rs) => rs.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  }

  function addRow() {
    if (chosenBatch && rows.length >= maxTravellers) return;
    setRows((rs) => [...rs, { fullName: "", age: "", gender: "M", medical: false }]);
  }

  function removeRow(index: number) {
    setRows((rs) => (rs.length > 1 ? rs.filter((_, i) => i !== index) : rs));
  }

  function canSubmit() {
    if (!selectedLead || !selectedTrip) return false;
    if (isFixed && !selectedBatch) return false;
    if (!isFixed && !travelDate) return false;
    if (rows.some((r) => !r.fullName.trim())) return false;
    if (chosenBatch && rows.length > maxTravellers) return false;
    return true;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSaving(true);
    try {
      const payload: BookingCreatePayload = {
        leadId: selectedLead,
        tripId: selectedTrip,
        batchId: isFixed ? selectedBatch : undefined,
        travelDate: isFixed ? undefined : travelDate || undefined,
        numTravellers: rows.length,
        discountAmount: discount ? Number(discount) : undefined,
        notes: notes || undefined,
        travellers: rows.map((r) => ({
          fullName: r.fullName.trim(),
          age: r.age ? Number(r.age) : undefined,
          gender: r.gender && r.gender !== "Other" ? r.gender : undefined,
          medicalCertRequired: r.medical,
        })),
      };
      await api.createBooking(payload);
      onSaved();
      onClose();
    } catch (err) {
      if (err instanceof ApiClientError && err.body.fieldErrors?.length) {
        setError(err.body.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(" · "));
      } else {
        setError(err instanceof Error ? err.message : "Could not create booking");
      }
    } finally {
      setSaving(false);
    }
  }

  const input =
    "mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900";
  const label = "block text-sm font-medium text-slate-700";

  return (
    <div className="fixed inset-0 z-20 flex items-start justify-center overflow-y-auto bg-slate-900/50 p-3 pt-safe sm:p-6 sm:pt-16">
      <form onSubmit={onSubmit} className="w-full max-w-2xl rounded-xl bg-white p-4 shadow-xl sm:p-6">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-900">New booking</h2>
          <button type="button" onClick={onClose} className="text-slate-400 hover:text-slate-700">
            ✕
          </button>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <label className={`${label} sm:col-span-2`}>
            Lead
            <select
              required
              value={selectedLead}
              onChange={(e) => setSelectedLead(e.target.value)}
              className={input}
            >
              <option value="">Select a hot lead…</option>
              {leads.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.customerName} · {l.mobileNumber} · {l.status}
                </option>
              ))}
            </select>
            {leads.length === 0 && (
              <span className="mt-1 block text-xs text-slate-400">
                {user?.role === "SALES"
                  ? "You have no interested / quotation-sent leads assigned to you."
                  : "No interested / quotation-sent leads to book yet."}
              </span>
            )}
          </label>

          <label className={`${label} sm:col-span-2`}>
            Trip
            <select
              required
              value={selectedTrip}
              onChange={(e) => setSelectedTrip(e.target.value)}
              className={input}
            >
              {trips.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name} · {t.bookingType === "FIXED_BATCH" ? "Fixed batch" : "Custom fit"}
                </option>
              ))}
            </select>
          </label>

          {isFixed && tripDetail ? (
            <label className={`${label} sm:col-span-2`}>
              Departure batch
              <select
                required
                value={selectedBatch}
                onChange={(e) => setSelectedBatch(e.target.value)}
                className={input}
              >
                <option value="">Select a departure…</option>
                {openBatches.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.departureDate} · {b.available} seats left · {b.status}
                  </option>
                ))}
              </select>
              {chosenBatch && (
                <span className="mt-1 block text-xs text-slate-400">
                  Price per traveller ₹{tripDetail.baseCost}; seats are held 2 hours until the booking is confirmed.
                </span>
              )}
            </label>
          ) : (
            <label className={`${label} sm:col-span-2`}>
              Travel date
              <input
                required
                type="date"
                value={travelDate}
                onChange={(e) => setTravelDate(e.target.value)}
                className={input}
              />
            </label>
          )}

          <label className={label}>
            Discount (₹)
            <input
              type="number"
              min="0"
              value={discount}
              onChange={(e) => setDiscount(e.target.value)}
              className={input}
              placeholder="0"
            />
          </label>
          <label className={label}>
            Notes
            <input value={notes} onChange={(e) => setNotes(e.target.value)} className={input} />
          </label>

          <div className="sm:col-span-2">
            <div className="flex items-center justify-between">
              <h4 className="text-sm font-medium text-slate-700">Travellers ({rows.length})</h4>
              <button
                type="button"
                onClick={addRow}
                disabled={!!chosenBatch && rows.length >= maxTravellers}
                className="rounded-md border border-slate-300 px-2 py-1 text-xs font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-40"
              >
                + Add traveller
              </button>
            </div>
            {rows.map((r, i) => (
              <div key={i} className="mt-2 grid grid-cols-1 items-center gap-2 sm:grid-cols-[1fr_80px_90px_auto_auto]">
                <input
                  required
                  placeholder="Full name"
                  value={r.fullName}
                  onChange={(e) => setRow(i, { fullName: e.target.value })}
                  className="mt-0 rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
                />
                <input
                  type="number"
                  min="1"
                  max="110"
                  placeholder="Age"
                  value={r.age}
                  onChange={(e) => setRow(i, { age: e.target.value })}
                  className="mt-0 rounded-md border border-slate-300 px-2 py-2 text-sm outline-none focus:border-slate-900"
                />
                <select
                  value={r.gender}
                  onChange={(e) => setRow(i, { gender: e.target.value })}
                  className="mt-0 rounded-md border border-slate-300 px-2 py-2 text-sm outline-none focus:border-slate-900"
                >
                  {GENDERS.map((g) => (
                    <option key={g} value={g}>
                      {g}
                    </option>
                  ))}
                </select>
                <label className="flex items-center gap-1 text-xs text-slate-600">
                  <input
                    type="checkbox"
                    checked={r.medical}
                    onChange={(e) => setRow(i, { medical: e.target.checked })}
                    className="h-3.5 w-3.5 rounded border-slate-300"
                  />
                  Med cert
                </label>
                <button
                  type="button"
                  onClick={() => removeRow(i)}
                  disabled={rows.length <= 1}
                  className="text-sm text-red-500 hover:text-red-700 disabled:opacity-30"
                >
                  ✕
                </button>
              </div>
            ))}
            {chosenBatch && rows.length > maxTravellers && (
              <p className="mt-1 text-xs text-red-600">
                Only {maxTravellers} seats left on this batch.
              </p>
            )}
          </div>
        </div>

        <p className="mt-3 text-xs text-slate-400">
          Discount confirms are manager-only. Confirming a booking closes the lead&apos;s follow-ups and drives
            the stepper to Booking Confirmed.
        </p>

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
            disabled={saving || !canSubmit()}
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
          >
            {saving ? "Creating…" : "Create booking"}
          </button>
        </div>
      </form>
    </div>
  );
}
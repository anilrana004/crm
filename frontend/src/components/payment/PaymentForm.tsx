"use client";

import { useCallback, useEffect, useState } from "react";
import { api, type Booking, type PaymentCreatePayload } from "@/lib/api";

type Props = {
  bookingId?: string;
  onSaved: () => void;
  onClose: () => void;
};

export function PaymentForm({ bookingId, onSaved, onClose }: Props) {
  const [bookings, setBookings] = useState<Booking[]>([]);
  const [optionsLoaded, setOptionsLoaded] = useState(false);
  const [selBooking, setSelBooking] = useState(bookingId ?? "");
  const [amount, setAmount] = useState("");
  const [amountType, setAmountType] = useState<"ADVANCE" | "BALANCE" | "FULL">("ADVANCE");
  const [dueDate, setDueDate] = useState("");
  const [gatewayRef, setGatewayRef] = useState("");
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .getBookings()
      .then((all) => {
        setBookings(all.filter((b) => b.status !== "CANCELLED"));
        if (!bookingId) setSelBooking("");
        setOptionsLoaded(true);
      })
      .catch(() => setOptionsLoaded(true));
  }, [bookingId]);

  const save = useCallback(async () => {
    setError(null);
    const booking = bookings.find((b) => b.id === selBooking);
    const value = Number(amount);
    if (!booking) {
      setError("Select a booking");
      return;
    }
    if (!Number.isFinite(value) || value <= 0) {
      setError("Enter an amount greater than zero");
      return;
    }
    const payload: PaymentCreatePayload = {
      bookingId: booking.id as string,
      amount: value,
      amountType,
      dueDate: dueDate || undefined,
      gatewayRef: gatewayRef || undefined,
      notes: notes || undefined,
    };
    setSaving(true);
    try {
      await api.createPayment(payload);
      onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not record payment");
      setSaving(false);
    }
  }, [bookings, selBooking, amount, amountType, dueDate, gatewayRef, notes, onSaved]);

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-slate-900/40 p-3 pt-safe sm:p-6">
      <div className="mt-4 w-full max-w-lg rounded-xl bg-white p-4 shadow-xl sm:mt-10 sm:p-6">
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-lg font-semibold text-slate-900">Record payment</h3>
          <button onClick={onClose} className="text-sm text-slate-400 hover:text-slate-600">
            ✕
          </button>
        </div>

        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-slate-600">Booking</label>
            <select
              value={selBooking}
              onChange={(e) => setSelBooking(e.target.value)}
              disabled={!!bookingId}
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-800"
            >
              {!optionsLoaded && <option value="">Loading bookings…</option>}
              {optionsLoaded && <option value="">Select a booking</option>}
              {bookings.map((b) => (
                <option key={b.id} value={b.id as string}>
                  {b.bookingRef} · {b.customerName || "—"} · ₹
                  {Number(b.netAmount ?? 0).toLocaleString("en-IN")}
                </option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-600">Amount (₹)</label>
              <input
                type="number"
                min="1"
                step="any"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                placeholder="e.g. 25000"
                className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-800"
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-600">Type</label>
              <select
                value={amountType}
                onChange={(e) => setAmountType(e.target.value as "ADVANCE" | "BALANCE" | "FULL")}
                className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-800"
              >
                <option value="ADVANCE">Advance</option>
                <option value="BALANCE">Balance</option>
                <option value="FULL">Full amount</option>
              </select>
            </div>
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-slate-600">Due date</label>
            <input
              type="date"
              value={dueDate}
              onChange={(e) => setDueDate(e.target.value)}
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-800"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-slate-600">Gateway / reference</label>
            <input
              value={gatewayRef}
              onChange={(e) => setGatewayRef(e.target.value)}
              placeholder="Optional — e.g. UPI Txn ID"
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-800"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-slate-600">Notes</label>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={2}
              placeholder="Optional"
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-800"
            />
          </div>

          {error && <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}

          <div className="flex justify-end gap-2 border-t border-slate-100 pt-4">
            <button
              onClick={onClose}
              className="rounded-md border border-slate-200 px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50"
            >
              Cancel
            </button>
            <button
              onClick={save}
              disabled={saving}
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
            >
              {saving ? "Saving…" : "Record payment"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
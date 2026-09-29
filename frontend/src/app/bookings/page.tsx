"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api, ApiClientError, type Booking, type PaymentSummary } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Protected } from "@/components/Protected";
import { AppShell } from "@/components/AppShell";
import { BookingForm } from "@/components/booking/BookingForm";
const MANAGER_ROLES = ["MANAGER", "ADMIN", "CEO"];
const BOOKING_CAN_WRITE = ["SALES", "MANAGER", "ADMIN", "CEO"];

const STATUS_STYLE: Record<string, string> = {
  QUOTATION: "bg-amber-50 text-amber-700",
  CONFIRMED: "bg-emerald-50 text-emerald-700",
  COMPLETED: "bg-sky-50 text-sky-700",
  CANCELLED: "bg-slate-100 text-slate-500",
};

function fmtMoney(n: number | undefined) {
  return n == null ? "—" : "₹" + Number(n).toLocaleString("en-IN");
}

function fmtDate(d: string | undefined) {
  return d ? new Date(d + (d.length === 10 ? "T00:00:00" : "")).toLocaleDateString("en-IN") : "—";
}

export default function BookingsPage() {
  const { user } = useAuth();
  const canManage = !!user?.role && MANAGER_ROLES.includes(user.role);
  const canWrite = !!user?.role && BOOKING_CAN_WRITE.includes(user.role);

  const [bookings, setBookings] = useState<Booking[]>([]);
  const [selected, setSelected] = useState<Booking | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [summary, setSummary] = useState<PaymentSummary | null>(null);

  const reload = useCallback(() => {
    setLoading(true);
    setError(null);
    api
      .getBookings()
      .then(setBookings)
      .catch((err) => setError(err instanceof Error ? err.message : "Could not load bookings"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  const open = useCallback(async (listItem: Booking) => {
    setActionError(null);
    try {
      const detail = await api.getBooking(listItem.id as string);
      setSelected(detail);
      setSummary(null);
      api.getPaymentSummary(detail.id as string).then(setSummary).catch(() => setSummary(null));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load booking");
    }
  }, []);

  async function transition(b: Booking, status: "CONFIRMED" | "COMPLETED" | "CANCELLED") {
    setActionError(null);
    try {
      await api.updateBookingStatus(b.id as string, { status });
      const detail = await api.getBooking(b.id as string);
      setSelected(detail);
      setSummary(null);
      api.getPaymentSummary(detail.id as string).then(setSummary).catch(() => setSummary(null));
      const fresh = await api.getBookings();
      setBookings(fresh);
    } catch (err) {
      const msg =
        err instanceof ApiClientError
          ? err.body.message || `Could not ${status.toLowerCase()} booking`
          : err instanceof Error
            ? err.message
            : "Action failed";
      setActionError(msg);
    }
  }

  const discountNeedsManager = (b: Booking) =>
    (b.discountAmount ?? 0) > 0 && !canManage;

  return (
    <Protected>
      <AppShell mainClassName="p-4 sm:p-6">
          <div className="mb-6 flex flex-col items-stretch gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h1 className="text-xl font-semibold text-slate-900">Bookings</h1>
              <p className="text-sm text-slate-500">
                {bookings.length} bookings · seats are held 2 hours from creation until confirmed
              </p>
            </div>
            {canWrite && (
              <button
                onClick={() => setShowForm(true)}
                className="w-full rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800 sm:w-auto"
              >
                + New booking
              </button>
            )}
          </div>

          {error && <p className="mb-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}

          <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[320px_1fr]">
            <div className="space-y-2">
              {loading ? (
                <p className="py-6 text-center text-sm text-slate-400">Loading bookings…</p>
              ) : bookings.length === 0 ? (
                <p className="py-6 text-center text-sm text-slate-400">No bookings yet.</p>
              ) : (
                bookings.map((b) => (
                  <button
                    key={b.id}
                    onClick={() => open(b)}
                    className={`w-full rounded-xl border p-4 text-left transition ${
                      selected?.id === b.id
                        ? "border-slate-900 bg-slate-900 text-white"
                        : "border-slate-200 bg-white hover:border-slate-300"
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-semibold">{b.bookingRef}</span>
                      <span
                        className={`rounded-md px-1.5 py-0.5 text-[11px] font-semibold ${
                          selected?.id === b.id
                            ? "bg-slate-700 text-slate-200"
                            : STATUS_STYLE[b.status ?? "QUOTATION"]
                        }`}
                      >
                        {b.status}
                      </span>
                    </div>
                    <div className={`mt-1 text-xs ${selected?.id === b.id ? "text-slate-300" : "text-slate-500"}`}>
                      {b.tripName} · {fmtDate(b.departureDate ?? b.travelDate)}
                    </div>
                    <div className={`mt-1 text-xs ${selected?.id === b.id ? "text-slate-300" : "text-slate-500"}`}>
                      {b.customerName || "—"} · {b.numTravellers} pax · {fmtMoney(b.netAmount)}
                    </div>
                  </button>
                ))
              )}
            </div>

            <div>
              {selected ? (
                <div className="space-y-4">
                  <section className="rounded-xl border border-slate-200 bg-white p-5">
                    <div className="flex items-start justify-between gap-4">
                      <div>
                        <div className="flex items-center gap-2">
                          <h2 className="text-lg font-semibold text-slate-900">{selected.bookingRef}</h2>
                          <span className={`rounded-md px-2 py-0.5 text-xs font-semibold ${STATUS_STYLE[selected.status ?? "QUOTATION"]}`}>
                            {selected.status}
                          </span>
                        </div>
                        <p className="mt-1 text-xs text-slate-400">
                          {selected.tripName} · {selected.bookingType} · {selected.numTravellers} travellers
                        </p>
                      </div>
                    </div>

                    <div className="mt-4 grid grid-cols-2 gap-x-8 gap-y-2 text-sm">
                      <div className="flex justify-between">
                        <span className="text-slate-500">Customer</span>
                        <span className="font-medium text-slate-800">
                          {selected.customerName || "—"}
                          {selected.customerPhone ? ` · ${selected.customerPhone}` : ""}
                        </span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-slate-500">Lead</span>
                        <span className="font-medium text-slate-800">
                          {selected.leadId ? (
                            <Link href={`/leads/${selected.leadId}`} className="text-slate-900 underline">
                              View lead →
                            </Link>
                          ) : (
                            "—"
                          )}
                        </span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-slate-500">Departure</span>
                        <span className="font-medium text-slate-800">{fmtDate(selected.departureDate ?? selected.travelDate)}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-slate-500">Created by</span>
                        <span className="font-medium text-slate-800">{selected.createdByName || "—"}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-slate-500">Total</span>
                        <span className="font-medium text-slate-800">{fmtMoney(selected.totalAmount)}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-slate-500">Discount</span>
                        <span className="font-medium text-slate-800">
                          {fmtMoney(selected.discountAmount)}
                          {(selected.discountAmount ?? 0) > 0 &&
                            (selected.discountApprovedBy ? " (approved)" : " (needs manager)" )}
                        </span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-slate-500">Net</span>
                        <span className="font-semibold text-slate-900">{fmtMoney(selected.netAmount)}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-slate-500">Booked at</span>
                        <span className="font-medium text-slate-800">{fmtDate(selected.createdAt)}</span>
                      </div>
                    </div>

                    {selected.notes && (
                      <p className="mt-3 rounded-md bg-slate-50 px-3 py-2 text-xs text-slate-600">{selected.notes}</p>
                    )}

                    {(selected.travellers?.length ?? 0) > 0 && (
                      <div className="mt-4">
                        <h4 className="text-xs font-semibold uppercase tracking-wide text-slate-400">Travellers</h4>
                        <div className="table-scroll overflow-x-auto">
                          <table className="mt-2 w-full text-sm">
                            <thead>
                              <tr className="text-left text-xs text-slate-400">
                                <th className="py-1 pr-2 font-medium">Name</th>
                                <th className="py-1 pr-2 font-medium">Age</th>
                                <th className="py-1 pr-2 font-medium">Gender</th>
                                <th className="py-1 font-medium">Med cert</th>
                              </tr>
                            </thead>
                            <tbody>
                              {selected.travellers?.map((t) => (
                                <tr key={t.id} className="border-t border-slate-100">
                                  <td className="py-1.5 pr-2 font-medium text-slate-800">{t.fullName}</td>
                                  <td className="py-1.5 pr-2 text-slate-500">{t.age ?? "—"}</td>
                                  <td className="py-1.5 pr-2 text-slate-500">{t.gender ?? "—"}</td>
                                  <td className="py-1.5 text-slate-500">{t.medicalCertRequired ? "Yes" : "No"}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    )}

                    {actionError && (
                      <p className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{actionError}</p>
                    )}

                    {selected && summary && (
                      <div className="mt-4 flex flex-col gap-3 rounded-lg border border-slate-200 bg-slate-50 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
                        <div className="text-sm text-slate-600">
                          <span className="font-medium text-slate-800">Receivable</span>
                          {" · "}net {fmtMoney(summary.netAmount)}
                          {" · "}applied {fmtMoney(summary.appliedAmount)}
                          {summary.hasOverdueLine && (
                            <span className="ml-2 rounded-md bg-red-50 px-1.5 py-0.5 text-[11px] font-semibold text-red-700">
                              overdue
                            </span>
                          )}
                        </div>
                        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-3">
                          <span className="text-sm font-semibold text-slate-900">
                            Balance due {fmtMoney(summary.balanceAmount)}
                          </span>
                          <Link
                            href={`/payments?bookingId=${selected.id}`}
                            className="rounded-md bg-slate-900 px-3 py-1.5 text-center text-xs font-semibold text-white hover:bg-slate-800"
                          >
                            Payments →
                          </Link>
                        </div>
                      </div>
                    )}

                    {canWrite && (selected.status === "QUOTATION" || selected.status === "CONFIRMED") && (
                      <div className="mt-5 flex flex-wrap gap-2 border-t border-slate-100 pt-4">
                        {selected.status === "QUOTATION" && (
                          <button
                            onClick={() => transition(selected, "CONFIRMED")}
                            disabled={discountNeedsManager(selected)}
                            title={discountNeedsManager(selected) ? "Discounted bookings must be confirmed by a manager" : ""}
                            className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-500 disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            Confirm booking
                          </button>
                        )}
                        {selected.status === "CONFIRMED" && (
                          <button
                            onClick={() => transition(selected, "COMPLETED")}
                            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800"
                          >
                            Mark completed
                          </button>
                        )}
                        <button
                          onClick={() => transition(selected, "CANCELLED")}
                          className="rounded-md border border-red-200 px-4 py-2 text-sm font-semibold text-red-600 hover:bg-red-50"
                        >
                          Cancel booking
                        </button>
                      </div>
                    )}
                    {selected.status === "QUOTATION" && discountNeedsManager(selected) && (
                      <p className="mt-3 text-xs text-amber-600">
                        This booking has a discount — a manager must confirm it.
                      </p>
                    )}
                    {selected.status === "QUOTATION" && !discountNeedsManager(selected) && (
                      <p className="mt-3 text-xs text-slate-400">
                        Seats are held for 2 hours from creation. Confirm before the hold expires.
                      </p>
                    )}
                  </section>
                </div>
              ) : (
                <p className="rounded-xl border border-dashed border-slate-300 py-16 text-center text-sm text-slate-400">
                  Select a booking to see its travellers, schedule and actions.
                </p>
              )}
            </div>
          </div>
      </AppShell>

      {showForm && (
        <BookingForm
          onSaved={async () => {
            setSelected(null);
            reload();
          }}
          onClose={() => setShowForm(false)}
        />
      )}
    </Protected>
  );
}
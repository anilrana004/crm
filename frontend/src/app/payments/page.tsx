"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState, Suspense } from "react";
import { api, ApiClientError, type Payment, type PaymentSummary } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Protected } from "@/components/Protected";
import { Sidebar } from "@/components/Sidebar";
import { PaymentForm } from "@/components/payment/PaymentForm";

const CAN_WRITE = ["SALES", "MANAGER", "ADMIN", "CEO"];

const STATUS_STYLE: Record<string, string> = {
  PENDING: "bg-amber-50 text-amber-700",
  PARTIAL: "bg-sky-50 text-sky-700",
  COMPLETED: "bg-emerald-50 text-emerald-700",
  OVERDUE: "bg-red-50 text-red-700",
  CANCELLED: "bg-slate-100 text-slate-500",
  REFUNDED: "bg-purple-50 text-purple-700",
};

function fmtMoney(n: number | undefined) {
  return n == null ? "—" : "₹" + Number(n).toLocaleString("en-IN");
}

function fmtDate(d: string | undefined) {
  return d ? new Date(d + (d.length === 10 ? "T00:00:00" : "")).toLocaleDateString("en-IN") : "—";
}

function PaymentsView() {
  const { user } = useAuth();
  const canWrite = !!user?.role && CAN_WRITE.includes(user.role);
  const searchParams = useSearchParams();
  const bookingFilter = searchParams.get("bookingId") ?? "";

  const [payments, setPayments] = useState<Payment[]>([]);
  const [summary, setSummary] = useState<PaymentSummary | null>(null);
  const [statusFilter, setStatusFilter] = useState(searchParams.get("status") ?? "");
  const [typeFilter, setTypeFilter] = useState(searchParams.get("amountType") ?? "");
  const [selected, setSelected] = useState<Payment | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState(false);

  const reload = useCallback(() => {
    setLoading(true);
    setError(null);
    api
      .getPayments({ bookingId: bookingFilter || undefined, status: statusFilter || undefined, amountType: typeFilter || undefined })
      .then((rows) => {
        setPayments(rows);
        setSelected(null);
        if (bookingFilter) {
          return api.getPaymentSummary(bookingFilter).then(setSummary);
        }
        setSummary(null);
      })
      .catch((err) => setError(err instanceof Error ? err.message : "Could not load payments"))
      .finally(() => setLoading(false));
  }, [bookingFilter, statusFilter, typeFilter]);

  useEffect(() => {
    reload();
  }, [reload]);

  const change = useCallback(async (p: Payment, status: "COMPLETED" | "PARTIAL" | "CANCELLED" | "REFUNDED") => {
    setActionError(null);
    setActionLoading(true);
    try {
      await api.updatePaymentStatus(p.id as string, {
        status,
        paidAt: status === "COMPLETED" || status === "PARTIAL" ? new Date().toISOString() : undefined,
      });
      await reload();
    } catch (err) {
      const msg =
        err instanceof ApiClientError
          ? err.body.message || `Could not update payment`
          : err instanceof Error
            ? err.message
            : "Action failed";
      setActionError(msg);
    } finally {
      setActionLoading(false);
    }
  }, [reload]);

  return (
    <>
      <div className="flex min-h-screen">
        <Sidebar />
        <main className="flex-1 p-6">
          <div className="mb-6 flex items-center justify-between gap-4">
            <div>
              <h1 className="text-xl font-semibold text-slate-900">Payments</h1>
              <p className="text-sm text-slate-500">
                {payments.length} line{payments.length === 1 ? "" : "s"} · balance is auto-computed from bookings net amount
              </p>
            </div>
            {canWrite && (
              <button
                onClick={() => setShowForm(true)}
                className="rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800"
              >
                + Record payment
              </button>
            )}
          </div>

          {bookingFilter && (
            <section className="mb-5 grid grid-cols-4 gap-4 rounded-xl border border-slate-200 bg-white p-4">
              {[
                { label: "Booking total", value: fmtMoney(summary?.grossAmount) },
                { label: "Net (after discount)", value: fmtMoney(summary?.netAmount) },
                { label: "Applied", value: fmtMoney(summary?.appliedAmount) },
                { label: "Balance due", value: fmtMoney(summary?.balanceAmount) },
              ].map((c) => (
                <div key={c.label} className="rounded-lg bg-slate-50 px-4 py-3">
                  <div className="text-xs uppercase tracking-wide text-slate-400">{c.label}</div>
                  <div className="mt-1 text-lg font-semibold text-slate-900">{c.value}</div>
                </div>
              ))}
              <div className="rounded-lg bg-slate-50 px-4 py-3 col-span-2">
                <div className="text-xs uppercase tracking-wide text-slate-400">Status</div>
                <div className="mt-1 text-sm font-medium text-slate-800">
                  {summary?.hasOverdueLine ? (
                    <span className="rounded-md bg-red-50 px-2 py-0.5 text-xs font-semibold text-red-700">Has overdue line</span>
                  ) : (
                    <span className="rounded-md bg-emerald-50 px-2 py-0.5 text-xs font-semibold text-emerald-700">No overdue</span>
                  )}
                  <span className="ml-2 text-slate-500">· next due {fmtDate(summary?.nextDueDate)}</span>
                </div>
              </div>
            </section>
          )}

          <div className="mb-4 flex flex-wrap items-center gap-2">
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
            >
              <option value="">All statuses</option>
              {Object.keys(STATUS_STYLE).map((s) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
            <select
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value)}
              className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
            >
              <option value="">All types</option>
              <option value="ADVANCE">Advance</option>
              <option value="BALANCE">Balance</option>
              <option value="FULL">Full</option>
            </select>
            {bookingFilter && (
              <>
                <span className="rounded-md bg-slate-100 px-2 py-1 text-xs text-slate-600">
                  Booking {summary?.bookingRef}
                </span>
                <Link href="/payments" className="text-xs font-medium text-slate-500 underline">
                  Clear booking filter
                </Link>
              </>
            )}
          </div>

          {error && <p className="mb-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
          {actionError && <p className="mb-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{actionError}</p>}

          {loading ? (
            <p className="py-10 text-center text-sm text-slate-400">Loading payments…</p>
          ) : payments.length === 0 ? (
            <p className="rounded-xl border border-dashed border-slate-300 py-16 text-center text-sm text-slate-400">
              No payment lines{bookingFilter ? " for this booking" : ""} yet.
            </p>
          ) : (
            <div className="rounded-xl border border-slate-200 bg-white">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-200 text-left text-xs text-slate-400">
                    <th className="px-4 py-2.5 font-medium">Booking</th>
                    <th className="px-4 py-2.5 font-medium">Type</th>
                    <th className="px-4 py-2.5 font-medium">Amount</th>
                    <th className="px-4 py-2.5 font-medium">Status</th>
                    <th className="px-4 py-2.5 font-medium">Due date</th>
                    <th className="px-4 py-2.5 font-medium">Paid at</th>
                    <th className="px-4 py-2.5 font-medium">Recorded by</th>
                    <th className="px-4 py-2.5" />
                  </tr>
                </thead>
                <tbody>
                  {payments.map((p) => (
                    <tr
                      key={p.id}
                      onClick={() => setSelected(selected?.id === p.id ? null : p)}
                      className={`border-b border-slate-100 last:border-0 ${
                        selected?.id === p.id ? "bg-slate-50" : "hover:bg-slate-50"
                      }`}
                    >
                      <td className="px-4 py-2.5">
                        <Link
                          href={`/payments?bookingId=${p.bookingId}`}
                          onClick={(e) => e.stopPropagation()}
                          className="font-medium text-slate-800 underline"
                        >
                          {p.bookingRef}
                        </Link>
                        <div className="text-xs text-slate-400">{p.customerName}</div>
                      </td>
                      <td className="px-4 py-2.5 text-slate-600">{p.amountType}</td>
                      <td className="px-4 py-2.5 font-medium text-slate-900">{fmtMoney(Number(p.amount))}</td>
                      <td className="px-4 py-2.5">
                        <span className={`rounded-md px-1.5 py-0.5 text-[11px] font-semibold ${STATUS_STYLE[p.status ?? "PENDING"]}`}>
                          {p.status}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 text-slate-500">{fmtDate(p.dueDate)}</td>
                      <td className="px-4 py-2.5 text-slate-500">{fmtDate(p.paidAt)}</td>
                      <td className="px-4 py-2.5 text-slate-500">{p.recordedByName || "—"}</td>
                      <td className="px-4 py-2.5 text-right">
                        {selected?.id === p.id ? (
                          <span className="text-xs font-semibold text-slate-400">selected</span>
                        ) : (
                          <span className="text-xs text-slate-300">open</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>

              {selected && (
                <div className="border-t border-slate-200 px-4 py-4">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div className="text-sm text-slate-600">
                      <span className="font-semibold text-slate-800">
                        {selected.bookingRef} · {selected.amountType} · {fmtMoney(Number(selected.amount))}
                      </span>
                      {selected.gatewayRef && <span className="ml-2 text-xs text-slate-400">ref {selected.gatewayRef}</span>}
                      {selected.notes && <span className="ml-2 text-xs text-slate-400">· {selected.notes}</span>}
                    </div>
                    <div className="flex gap-2">
                      {canWrite && (selected.status === "PENDING" || selected.status === "PARTIAL") && (
                        <button
                          onClick={() => change(selected, "COMPLETED")}
                          disabled={actionLoading}
                          className="rounded-md bg-emerald-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-emerald-500 disabled:opacity-50"
                        >
                          Mark paid
                        </button>
                      )}
                      {canWrite && selected.status === "PENDING" && (
                        <button
                          onClick={() => change(selected, "PARTIAL")}
                          disabled={actionLoading}
                          className="rounded-md border border-sky-200 px-3 py-1.5 text-sm font-semibold text-sky-700 hover:bg-sky-50 disabled:opacity-50"
                        >
                          Mark partial
                        </button>
                      )}
                      {canWrite && (selected.status === "COMPLETED" || selected.status === "PARTIAL") && (
                        <button
                          onClick={() => change(selected, "REFUNDED")}
                          disabled={actionLoading}
                          className="rounded-md border border-purple-200 px-3 py-1.5 text-sm font-semibold text-purple-700 hover:bg-purple-50 disabled:opacity-50"
                        >
                          Refund
                        </button>
                      )}
                      {canWrite && (selected.status === "PENDING" || selected.status === "PARTIAL") && (
                        <button
                          onClick={() => change(selected, "CANCELLED")}
                          disabled={actionLoading}
                          className="rounded-md border border-red-200 px-3 py-1.5 text-sm font-semibold text-red-600 hover:bg-red-50 disabled:opacity-50"
                        >
                          Cancel
                        </button>
                      )}
                    </div>
                  </div>
                  {actionLoading && <p className="mt-2 text-xs text-slate-400">Updating…</p>}
                </div>
              )}
            </div>
          )}
        </main>
      </div>

      {showForm && (
        <PaymentForm
          bookingId={bookingFilter || undefined}
          onSaved={async () => {
            setShowForm(false);
            await reload();
          }}
          onClose={() => setShowForm(false)}
        />
      )}
    </>
  );
}

export default function PaymentsPage() {
  return (
    <Protected>
      <Suspense>
        <PaymentsView />
      </Suspense>
    </Protected>
  );
}
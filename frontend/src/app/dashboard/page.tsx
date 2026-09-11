"use client";

import { useCallback, useEffect, useState } from "react";
import {
  api,
  ApiClientError,
  type DashboardPerformance,
  type DashboardSummary,
  type SalesUser,
  type TargetsList,
} from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Protected } from "@/components/Protected";
import { Sidebar } from "@/components/Sidebar";

const CAN_WRITE = ["MANAGER", "ADMIN", "CEO"];
const CARD_FIELDS: { key: string; label: string }[] = [
  { key: "totalLeads", label: "Total leads" },
  { key: "newLeads", label: "New leads" },
  { key: "followUpDue", label: "Follow-up due" },
  { key: "interested", label: "Interested" },
  { key: "quotationSent", label: "Quotation sent" },
  { key: "bookingConfirmed", label: "Bookings confirmed" },
  { key: "lost", label: "Lost" },
  { key: "newBookings", label: "New bookings" },
  { key: "confirmedBookings", label: "Confirmed bookings" },
  { key: "revenue", label: "Revenue collected" },
];

function currentMonth() {
  return new Date().toISOString().slice(0, 7);
}

function monthLabel(month: string) {
  const [y, m] = month.split("-").map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString("en-IN", { month: "long", year: "numeric" });
}

function n(v: number | undefined) {
  return (v ?? 0).toLocaleString("en-IN");
}

function inr(v: number | undefined) {
  return "₹" + (v ?? 0).toLocaleString("en-IN", { maximumFractionDigits: 0 });
}

function Progress({ pct }: { pct: number | undefined }) {
  const p = Math.max(0, Math.min(100, pct ?? 0));
  return (
    <div className="h-1.5 w-24 overflow-hidden rounded-full bg-slate-100">
      <div
        className={`h-full rounded-full ${p >= 100 ? "bg-green-500" : p >= 50 ? "bg-amber-400" : "bg-slate-400"}`}
        style={{ width: `${p}%` }}
      />
    </div>
  );
}

function Card({ label, value, big }: { label: string; value: string; big?: boolean }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white px-4 py-3">
      <div className="text-[11px] font-medium uppercase tracking-wide text-slate-400">{label}</div>
      <div className={`mt-1 font-semibold tabular-nums text-slate-900 ${big ? "text-xl" : "text-base"}`}>
        {value}
      </div>
    </div>
  );
}

function DashboardView() {
  const { user } = useAuth();
  const canWrite = !!user?.role && CAN_WRITE.includes(user.role);

  const [period, setPeriod] = useState<"today" | "month">("month");
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [perf, setPerf] = useState<DashboardPerformance | null>(null);
  const [targets, setTargets] = useState<TargetsList | null>(null);
  const [month, setMonth] = useState(currentMonth());
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const reload = useCallback(() => {
    setError(null);
    api.getDashboardSummary(period).then(setSummary).catch((e: Error) => setError(e.message));
    api.getDashboardPerformance(month || undefined).then(setPerf).catch(() => undefined);
    api.getTargets(month || undefined).then(setTargets).catch(() => undefined);
  }, [period, month]);

  useEffect(() => {
    reload();
  }, [reload]);

  // target editor state (managers only)
  const [salesUsers, setSalesUsers] = useState<SalesUser[]>([]);
  const [editUser, setEditUser] = useState<string>("__company__");
  const [editBookings, setEditBookings] = useState("");
  const [editRevenue, setEditRevenue] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (canWrite) {
      api.getSalesUsers().then(setSalesUsers).catch(() => undefined);
    }
  }, [canWrite]);

  const saveTarget = useCallback(async () => {
    setActionError(null);
    setSaving(true);
    try {
      await api.upsertTarget({
        userId: editUser === "__company__" ? null : editUser,
        month,
        targetBookings: editBookings ? Number(editBookings) : 0,
        targetRevenue: editRevenue ? Number(editRevenue) : 0,
      });
      setEditBookings("");
      setEditRevenue("");
      reload();
    } catch (err) {
      setActionError(
        err instanceof ApiClientError
          ? err.body.message || "Could not save target"
          : err instanceof Error
            ? err.message
            : "Save failed",
      );
    } finally {
      setSaving(false);
    }
  }, [editUser, editBookings, editRevenue, month, reload]);

  const removeTarget = useCallback(
    async (id: string) => {
      setActionError(null);
      try {
        await api.deleteTarget(id);
        reload();
      } catch (err) {
        setActionError(err instanceof Error ? err.message : "Delete failed");
      }
    },
    [reload],
  );

  const employees = perf?.employees ?? [];
  const targetRows = targets?.targets ?? [];
  const overall = targets?.overall;

  return (
    <>
      <div className="flex h-screen bg-slate-50">
        <Sidebar />
        <main className="flex min-w-0 flex-1 flex-col overflow-hidden">
          <header className="flex items-center justify-between border-b border-slate-200 bg-white px-6 py-4">
            <div>
              <h1 className="text-lg font-semibold text-slate-900">Sales dashboard</h1>
              <p className="text-xs text-slate-400">
                Pipeline cards, per-executive performance and monthly targets.
              </p>
            </div>
            <div className="flex items-center gap-2">
              <div className="flex overflow-hidden rounded-md border border-slate-200 text-sm">
                <button
                  onClick={() => setPeriod("today")}
                  className={`px-3 py-1.5 ${period === "today" ? "bg-slate-900 text-white" : "bg-white text-slate-500"}`}
                >
                  Today
                </button>
                <button
                  onClick={() => setPeriod("month")}
                  className={`px-3 py-1.5 ${period === "month" ? "bg-slate-900 text-white" : "bg-white text-slate-500"}`}
                >
                  Month
                </button>
              </div>
              <input
                type="month"
                value={month}
                onChange={(e) => e.target.value && setMonth(e.target.value)}
                className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
              />
            </div>
          </header>

          <div className="min-h-0 flex-1 overflow-y-auto">
            {error && <p className="m-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}

            <section className="p-6 pb-2">
              <div className="mb-3 text-sm font-medium text-slate-700">
                Pipeline & revenue — {summary ? summary.period : period}
              </div>
              {summary ? (
                <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-5">
                  <Card
                    label="Revenue collected"
                    value={inr(summary.revenue)}
                    big
                  />
                  {CARD_FIELDS.filter((f) => f.key !== "revenue").map((f) => (
                    <Card key={f.key} label={f.label} value={n(summary[f.key as keyof DashboardSummary] as number)} />
                  ))}
                </div>
              ) : (
                <p className="text-sm text-slate-400">Loading…</p>
              )}
            </section>

            <section className="p-6 pt-4">
              <div className="mb-2 text-sm font-medium text-slate-700">
                Per-executive performance · {monthLabel(month)}
              </div>
              <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
                <table className="w-full min-w-[720px] text-left text-sm">
                  <thead>
                    <tr className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-400">
                      <th className="px-4 py-2.5 font-medium">Executive</th>
                      <th className="px-3 py-2.5 text-right font-medium">Leads</th>
                      <th className="px-3 py-2.5 text-right font-medium">Follow-ups done</th>
                      <th className="px-3 py-2.5 text-right font-medium">Bookings closed</th>
                      <th className="px-3 py-2.5 text-right font-medium">Payments</th>
                      <th className="px-4 py-2.5 text-right font-medium">Revenue</th>
                    </tr>
                  </thead>
                  <tbody>
                    {employees.length === 0 ? (
                      <tr>
                        <td className="px-4 py-4 text-slate-400" colSpan={6}>
                          No sales performance for this month.
                        </td>
                      </tr>
                    ) : (
                      employees.map((e) => (
                        <tr key={e.userId} className="border-b border-slate-100">
                          <td className="px-4 py-2.5">
                            <div className="font-medium text-slate-900">{e.fullName}</div>
                            <div className="text-xs text-slate-400">{e.email}</div>
                          </td>
                          <td className="px-3 py-2.5 text-right tabular-nums">{n(e.leadsAssigned)}</td>
                          <td className="px-3 py-2.5 text-right tabular-nums">{n(e.followUpsCompleted)}</td>
                          <td className="px-3 py-2.5 text-right tabular-nums">{n(e.bookingsClosed)}</td>
                          <td className="px-3 py-2.5 text-right tabular-nums">{n(e.paymentsCount)}</td>
                          <td className="px-4 py-2.5 text-right tabular-nums font-medium text-slate-900">
                            {inr(e.revenue)}
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                  {employees.length > 0 && (
                    <tfoot>
                      <tr className="bg-slate-50 text-xs font-semibold uppercase tracking-wide text-slate-500">
                        <td className="px-4 py-2.5">Team totals ({employees.length})</td>
                        <td className="px-3 py-2.5 text-right tabular-nums">{n(perf?.totals?.leadsAssigned)}</td>
                        <td className="px-3 py-2.5 text-right tabular-nums">{n(perf?.totals?.followUpsCompleted)}</td>
                        <td className="px-3 py-2.5 text-right tabular-nums">{n(perf?.totals?.bookingsClosed)}</td>
                        <td className="px-3 py-2.5" />
                        <td className="px-4 py-2.5 text-right tabular-nums">{inr(perf?.totals?.revenue)}</td>
                      </tr>
                    </tfoot>
                  )}
                </table>
              </div>
            </section>

            <section className="p-6 pt-4">
              <div className="mb-2 flex items-baseline justify-between">
                <div className="text-sm font-medium text-slate-700">
                  Monthly targets · {monthLabel(month)}
                </div>
                {overall && (
                  <div className="text-xs text-slate-500">
                    Bookings {n(overall.achievedBookings)}/{n(overall.targetBookings)} · Revenue{" "}
                    {inr(overall.achievedRevenue)}/{inr(overall.targetRevenue)}
                  </div>
                )}
              </div>

              {actionError && (
                <div className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{actionError}</div>
              )}

              {canWrite && (
                <div className="mb-3 flex flex-wrap items-end gap-2 rounded-lg border border-slate-200 bg-white p-3">
                  <div className="flex flex-col">
                    <span className="mb-1 text-[11px] font-medium uppercase tracking-wide text-slate-400">
                      Target for
                    </span>
                    <select
                      value={editUser}
                      onChange={(e) => setEditUser(e.target.value)}
                      className="rounded-md border border-slate-300 px-2 py-1.5 text-sm text-slate-700"
                    >
                      <option value="__company__">Company-wide</option>
                      {salesUsers.map((u) => (
                        <option key={u.id} value={u.id}>
                          {u.fullName}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="flex flex-col">
                    <span className="mb-1 text-[11px] font-medium uppercase tracking-wide text-slate-400">
                      Bookings
                    </span>
                    <input
                      type="number"
                      min={0}
                      value={editBookings}
                      onChange={(e) => setEditBookings(e.target.value)}
                      placeholder="0"
                      className="w-28 rounded-md border border-slate-300 px-2 py-1.5 text-sm text-slate-700"
                    />
                  </div>
                  <div className="flex flex-col">
                    <span className="mb-1 text-[11px] font-medium uppercase tracking-wide text-slate-400">
                      Revenue (₹)
                    </span>
                    <input
                      type="number"
                      min={0}
                      value={editRevenue}
                      onChange={(e) => setEditRevenue(e.target.value)}
                      placeholder="0"
                      className="w-32 rounded-md border border-slate-300 px-2 py-1.5 text-sm text-slate-700"
                    />
                  </div>
                  <button
                    onClick={saveTarget}
                    disabled={saving}
                    className="rounded-md bg-slate-900 px-4 py-2 text-xs font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
                  >
                    {saving ? "Saving…" : "Set target"}
                  </button>
                </div>
              )}

              {targetRows.length === 0 ? (
                <div className="rounded-lg border border-slate-200 bg-white px-4 py-6 text-center text-sm text-slate-400">
                  No targets set for this month.
                </div>
              ) : (
                <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
                  <table className="w-full min-w-[720px] text-left text-sm">
                    <thead>
                      <tr className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-400">
                        <th className="px-4 py-2.5 font-medium">Executive</th>
                        <th className="px-3 py-2.5 text-right font-medium">Target bookings</th>
                        <th className="px-3 py-2.5 text-right font-medium">Achieved</th>
                        <th className="px-3 py-2.5 font-medium">Bookings %</th>
                        <th className="px-3 py-2.5 text-right font-medium">Target revenue</th>
                        <th className="px-3 py-2.5 text-right font-medium">Achieved</th>
                        <th className="px-3 py-2.5 font-medium">Revenue %</th>
                        {canWrite && <th className="px-4 py-2.5" />}
                      </tr>
                    </thead>
                    <tbody>
                      {targetRows.map((t) => (
                        <tr key={t.id} className="border-b border-slate-100">
                          <td className="px-4 py-2.5 font-medium text-slate-900">{t.userFullName}</td>
                          <td className="px-3 py-2.5 text-right tabular-nums">{n(t.targetBookings)}</td>
                          <td className="px-3 py-2.5 text-right tabular-nums">{n(t.achievedBookings)}</td>
                          <td className="px-3 py-2.5">
                            <div className="flex items-center gap-2">
                              <Progress pct={t.bookingsPct} />
                              <span className="text-xs tabular-nums text-slate-500">{t.bookingsPct}%</span>
                            </div>
                          </td>
                          <td className="px-3 py-2.5 text-right tabular-nums">{inr(t.targetRevenue)}</td>
                          <td className="px-3 py-2.5 text-right tabular-nums">{inr(t.achievedRevenue)}</td>
                          <td className="px-3 py-2.5">
                            <div className="flex items-center gap-2">
                              <Progress pct={t.revenuePct} />
                              <span className="text-xs tabular-nums text-slate-500">{t.revenuePct}%</span>
                            </div>
                          </td>
                          {canWrite && (
                            <td className="px-4 py-2.5 text-right">
                              <button
                                onClick={() => removeTarget(t.id as string)}
                                className="text-xs font-medium text-red-600 hover:text-red-700"
                              >
                                Delete
                              </button>
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </div>
        </main>
      </div>
    </>
  );
}

export default function DashboardPage() {
  return (
    <Protected>
      <DashboardView />
    </Protected>
  );
}
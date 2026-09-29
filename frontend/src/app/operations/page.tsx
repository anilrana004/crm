"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import { api, ApiClientError, type OpsHandoff } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Protected } from "@/components/Protected";
import { AppShell } from "@/components/AppShell";

const CAN_WRITE = ["OPS", "MANAGER", "ADMIN", "CEO"];

const HOTEL_STYLE: Record<string, string> = {
  NOT_ARRANGED: "bg-slate-100 text-slate-500",
  PENDING: "bg-amber-50 text-amber-700",
  CONFIRMED: "bg-emerald-50 text-emerald-700",
};

const PAY_STYLE: Record<string, string> = {
  PENDING: "bg-amber-50 text-amber-700",
  PARTIAL: "bg-sky-50 text-sky-700",
  COMPLETED: "bg-emerald-50 text-emerald-700",
  OVERDUE: "bg-red-50 text-red-700",
  CANCELLED: "bg-slate-100 text-slate-500",
  REFUNDED: "bg-purple-50 text-purple-700",
};

function fmtDate(d: string | undefined) {
  return d ? new Date(d + (d.length === 10 ? "T00:00:00" : "")).toLocaleDateString("en-IN") : "—";
}

function fmtDateTime(d: string | undefined) {
  return d ? new Date(d).toLocaleString("en-IN") : "—";
}

function OperationsView() {
  const { user } = useAuth();
  const canWrite = !!user?.role && CAN_WRITE.includes(user.role);

  const [handoffs, setHandoffs] = useState<OpsHandoff[]>([]);
  const [hotelFilter, setHotelFilter] = useState("");
  const [transportFilter, setTransportFilter] = useState("");
  const [payFilter, setPayFilter] = useState("");
  const [selected, setSelected] = useState<OpsHandoff | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [noteText, setNoteText] = useState("");
  const [savingNote, setSavingNote] = useState(false);

  const reload = useCallback(() => {
    setLoading(true);
    setError(null);
    api
      .getOperations({
        hotelStatus: hotelFilter || undefined,
        transportStatus: transportFilter || undefined,
        paymentStatus: payFilter || undefined,
      })
      .then((rows) => {
        setHandoffs(rows);
        setSelected(null);
      })
      .catch((err) => setError(err instanceof Error ? err.message : "Could not load ops handoffs"))
      .finally(() => setLoading(false));
  }, [hotelFilter, transportFilter, payFilter]);

  useEffect(() => {
    reload();
  }, [reload]);

  const updateArrangements = useCallback(
    async (patch: { hotelStatus?: "PENDING" | "NOT_ARRANGED" | "CONFIRMED"; transportStatus?: "PENDING" | "NOT_ARRANGED" | "CONFIRMED" }) => {
      if (!selected) return;
      setActionError(null);
      try {
        const fresh = await api.updateOperationArrangements(selected.id as string, patch);
        setSelected(fresh);
        await reload();
      } catch (err) {
        const msg =
          err instanceof ApiClientError
            ? err.body.message || "Could not update arrangements"
            : err instanceof Error
              ? err.message
              : "Action failed";
        setActionError(msg);
      }
    },
    [selected, reload],
  );

  const postNote = useCallback(async () => {
    if (!selected || !noteText.trim()) return;
    setActionError(null);
    setSavingNote(true);
    try {
      const fresh = await api.addOperationNote(selected.id as string, { note: noteText.trim() });
      setSelected(fresh);
      setNoteText("");
    } catch (err) {
      const msg =
        err instanceof ApiClientError
          ? err.body.message || "Could not save note"
          : err instanceof Error
            ? err.message
            : "Action failed";
      setActionError(msg);
    } finally {
      setSavingNote(false);
    }
  }, [selected, noteText]);

  const generateSheet = useCallback(async () => {
    if (!selected) return;
    setActionError(null);
    try {
      const fresh = await api.generateTripSheet(selected.id as string);
      setSelected(fresh);
    } catch (err) {
      const msg =
        err instanceof ApiClientError
          ? err.body.message || "Could not generate trip sheet"
          : err instanceof Error
            ? err.message
            : "Action failed";
      setActionError(msg);
    }
  }, [selected]);

  return (
    <>
      <AppShell mainClassName="p-4 sm:p-6">
          <div className="mb-6">
            <h1 className="text-xl font-semibold text-slate-900">Operations</h1>
            <p className="text-sm text-slate-500">
              {handoffs.length} handoff{handoffs.length === 1 ? "" : "s"} · auto-created when a booking is confirmed
            </p>
          </div>

          <div className="mb-4 flex flex-wrap items-center gap-2">
            <select
              value={hotelFilter}
              onChange={(e) => setHotelFilter(e.target.value)}
              className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
            >
              <option value="">Hotel: all</option>
              {Object.keys(HOTEL_STYLE).map((s) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
            <select
              value={transportFilter}
              onChange={(e) => setTransportFilter(e.target.value)}
              className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
            >
              <option value="">Transport: all</option>
              {Object.keys(HOTEL_STYLE).map((s) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
            <select
              value={payFilter}
              onChange={(e) => setPayFilter(e.target.value)}
              className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
            >
              <option value="">Payment: all</option>
              {["PENDING", "PARTIAL", "COMPLETED", "OVERDUE"].map((s) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
          </div>

          {error && <p className="mb-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
          {actionError && <p className="mb-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{actionError}</p>}

          {loading ? (
            <p className="py-10 text-center text-sm text-slate-400">Loading handoffs…</p>
          ) : handoffs.length === 0 ? (
            <p className="rounded-xl border border-dashed border-slate-300 py-16 text-center text-sm text-slate-400">
              No ops handoffs yet. Confirm a booking to create one.
            </p>
          ) : (
            <div className="table-scroll overflow-x-auto rounded-xl border border-slate-200 bg-white">
              <table className="w-full min-w-[800px] text-sm">
                <thead>
                  <tr className="border-b border-slate-200 text-left text-xs text-slate-400">
                    <th className="px-4 py-2.5 font-medium">Ops ref</th>
                    <th className="px-4 py-2.5 font-medium">Booking</th>
                    <th className="px-4 py-2.5 font-medium">Customer / trip</th>
                    <th className="px-4 py-2.5 font-medium">Travel</th>
                    <th className="px-4 py-2.5 font-medium">Pax</th>
                    <th className="px-4 py-2.5 font-medium">Hotel</th>
                    <th className="px-4 py-2.5 font-medium">Transport</th>
                    <th className="px-4 py-2.5 font-medium">Payment</th>
                    <th className="px-4 py-2.5" />
                  </tr>
                </thead>
                <tbody>
                  {handoffs.map((h) => (
                    <tr
                      key={h.id}
                      onClick={() => setSelected(selected?.id === h.id ? null : h)}
                      className={`border-b border-slate-100 last:border-0 ${
                        selected?.id === h.id ? "bg-slate-50" : "hover:bg-slate-50"
                      }`}
                    >
                      <td className="px-4 py-2.5 font-semibold text-slate-800">{h.opsRef}</td>
                      <td className="px-4 py-2.5">
                        <Link
                          href={`/bookings`}
                          onClick={(e) => e.stopPropagation()}
                          className="font-medium text-slate-800 underline"
                        >
                          {h.bookingRef}
                        </Link>
                      </td>
                      <td className="px-4 py-2.5">
                        <div className="text-slate-800">{h.customerName}</div>
                        <div className="text-xs text-slate-400">{h.tripName}</div>
                      </td>
                      <td className="px-4 py-2.5 text-slate-600">{fmtDate(h.travelDate)}</td>
                      <td className="px-4 py-2.5 text-slate-600">{h.pax}</td>
                      <td className="px-4 py-2.5">
                        <span className={`rounded-md px-1.5 py-0.5 text-[11px] font-semibold ${HOTEL_STYLE[h.hotelStatus ?? "NOT_ARRANGED"]}`}>
                          {h.hotelStatus}
                        </span>
                      </td>
                      <td className="px-4 py-2.5">
                        <span className={`rounded-md px-1.5 py-0.5 text-[11px] font-semibold ${HOTEL_STYLE[h.transportStatus ?? "NOT_ARRANGED"]}`}>
                          {h.transportStatus}
                        </span>
                      </td>
                      <td className="px-4 py-2.5">
                        <span className={`rounded-md px-1.5 py-0.5 text-[11px] font-semibold ${PAY_STYLE[h.paymentStatus ?? "PENDING"]}`}>
                          {h.paymentStatus}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 text-right">
                        <span className="text-xs text-slate-300">{selected?.id === h.id ? "selected" : "open"}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>

              {selected && (
                <div className="border-t border-slate-200 px-4 py-4">
                  <div className="mb-4 flex flex-wrap items-center gap-3">
                    <span className="text-sm font-semibold text-slate-800">
                      {selected.opsRef} · {selected.bookingRef} · {selected.customerName}
                    </span>
                    {selected.tripSheetGeneratedAt && (
                      <span className="rounded-md bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">
                        Trip sheet generated {fmtDateTime(selected.tripSheetGeneratedAt)}
                      </span>
                    )}
                    {canWrite && (
                      <button
                        onClick={generateSheet}
                        className="rounded-md bg-slate-900 px-3 py-1.5 text-xs font-semibold text-white hover:bg-slate-800"
                      >
                        {selected.tripSheetGeneratedAt ? "Regenerate trip sheet" : "Generate trip sheet"}
                      </button>
                    )}
                  </div>

                  <div className="mb-4 grid gap-4 sm:grid-cols-2">
                    <div>
                      <Label>Hotel status</Label>
                      <div className="flex gap-2">
                        {Object.keys(HOTEL_STYLE).map((s) => (
                          <button
                            key={s}
                            disabled={!canWrite}
                            onClick={() => updateArrangements({ hotelStatus: s as "PENDING" | "NOT_ARRANGED" | "CONFIRMED" })}
                            className={`rounded-md border px-2.5 py-1 text-xs font-semibold ${
                              selected.hotelStatus === s
                                ? "border-slate-900 bg-slate-900 text-white"
                                : canWrite
                                  ? "border-slate-200 text-slate-600 hover:bg-slate-50"
                                  : "border-slate-100 text-slate-400"
                            }`}
                          >
                            {s}
                          </button>
                        ))}
                      </div>
                    </div>
                    <div>
                      <Label>Transport status</Label>
                      <div className="flex gap-2">
                        {Object.keys(HOTEL_STYLE).map((s) => (
                          <button
                            key={s}
                            disabled={!canWrite}
                            onClick={() => updateArrangements({ transportStatus: s as "PENDING" | "NOT_ARRANGED" | "CONFIRMED" })}
                            className={`rounded-md border px-2.5 py-1 text-xs font-semibold ${
                              selected.transportStatus === s
                                ? "border-slate-900 bg-slate-900 text-white"
                                : canWrite
                                  ? "border-slate-200 text-slate-600 hover:bg-slate-50"
                                  : "border-slate-100 text-slate-400"
                            }`}
                          >
                            {s}
                          </button>
                        ))}
                      </div>
                    </div>
                    <div>
                      <Label>Guide</Label>
                      <div className="text-sm text-slate-700">{selected.guideName || "—"}</div>
                      <div className="text-xs text-slate-400">(assign via API for now)</div>
                    </div>
                    <div>
                      <Label>Driver</Label>
                      <div className="text-sm text-slate-700">
                        {selected.driverId ? String(selected.driverId).slice(0, 8) : "—"}
                      </div>
                      <div className="text-xs text-slate-400">Vendor id (Phase 2)</div>
                    </div>
                  </div>

                  <div className="mb-2 flex items-center justify-between">
                    <Label>Notes</Label>
                    <span className="text-xs text-slate-400">travel {fmtDate(selected.travelDate)} · {selected.pax} pax</span>
                  </div>
                  {selected.notes ? (
                    <pre className="mb-3 whitespace-pre-wrap rounded-lg bg-slate-50 px-3 py-2 text-xs leading-relaxed text-slate-600">
                      {selected.notes}
                    </pre>
                  ) : (
                    <p className="mb-3 text-sm text-slate-400">No notes yet.</p>
                  )}
                  {canWrite && (
                    <div className="flex gap-2">
                      <input
                        value={noteText}
                        onChange={(e) => setNoteText(e.target.value)}
                        placeholder="Add a note…"
                        className="flex-1 rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
                      />
                      <button
                        onClick={postNote}
                        disabled={savingNote || !noteText.trim()}
                        className="rounded-md bg-slate-900 px-3 py-1.5 text-xs font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
                      >
                        Add note
                      </button>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
      </AppShell>
    </>
  );
}

function Label({ children }: { children: ReactNode }) {
  return <div className="mb-1 text-xs font-medium uppercase tracking-wide text-slate-400">{children}</div>;
}

export default function OperationsPage() {
  return (
    <Protected>
      <OperationsView />
    </Protected>
  );
}

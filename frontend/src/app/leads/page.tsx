"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { api, type Lead, type LeadListParams, type LeadStatusPayload, type Trip } from "@/lib/api";
import { pickLostReason } from "@/lib/lostReasons";
import { Protected } from "@/components/Protected";
import { Sidebar } from "@/components/Sidebar";
import { LeadTable } from "@/components/lead/LeadTable";
import { LeadBoard } from "@/components/lead/LeadBoard";
import { LeadFilters } from "@/components/lead/LeadFilters";
import { LeadForm } from "@/components/lead/LeadForm";

const TAB_STYLE = (active: boolean) =>
  `rounded-md px-4 py-1.5 text-sm font-semibold ${
    active ? "bg-slate-900 text-white" : "text-slate-600 hover:bg-slate-100"
  }`;

export default function LeadsPage() {
  const router = useRouter();
  const [view, setView] = useState<"board" | "table">("board");
  const [filters, setFilters] = useState<LeadListParams>({});
  const [leads, setLeads] = useState<Lead[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [trips, setTrips] = useState<Trip[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);

  const tripsById = useMemo(
    () => new Map((trips as (Trip & { id?: string })[]).filter((t) => t.id).map((t) => [t.id as string, t])),
    [trips],
  );

  useEffect(() => {
    api.getTrips().then(setTrips).catch(() => setTrips([]));
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // Board groups by status client-side, so it never filters by status;
      // the table applies the status filter the same way via the endpoint.
      const params: LeadListParams =
        view === "board"
          ? { ...filters, status: undefined, size: 100 }
          : { ...filters, page, size: 20 };
      const res = await api.getLeads(params);
      setLeads(res.content);
      setTotal(res.totalElements);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load leads");
    } finally {
      setLoading(false);
    }
  }, [view, filters, page]);

  useEffect(() => {
    load();
  }, [load]);

  const handleStatusChange = useCallback(
    async (id: string, payload: LeadStatusPayload) => {
      try {
        let finalPayload = payload;
        if (payload.status === "LOST" && !payload.lostReason) {
          const reason = await pickLostReason();
          if (!reason) return;
          finalPayload = { status: "LOST", lostReason: reason };
        }
        await api.updateLeadStatus(id, finalPayload);
        await load();
      } catch (err) {
        window.alert(err instanceof Error ? err.message : "Could not update status");
      }
    },
    [load],
  );

  function changeFilters(next: LeadListParams) {
    setFilters(next);
    setPage(0);
  }

  return (
    <Protected>
      <div className="flex min-h-screen">
        <Sidebar />
        <main className="flex-1 p-6">
          <div className="mb-6 flex items-center justify-between gap-4">
            <div>
              <h1 className="text-xl font-semibold text-slate-900">Leads</h1>
              <p className="text-sm text-slate-500">{total} total</p>
            </div>
            <button
              onClick={() => setShowForm(true)}
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800"
            >
              + New lead
            </button>
          </div>

          <div className="mb-4 flex items-center gap-1">
            <button className={TAB_STYLE(view === "board")} onClick={() => setView("board")}>
              Board
            </button>
            <button className={TAB_STYLE(view === "table")} onClick={() => setView("table")}>
              Table
            </button>
          </div>

          <LeadFilters value={filters} trips={trips} onChange={changeFilters} />

          {error && <p className="mb-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
          {loading ? (
            <p className="py-10 text-center text-sm text-slate-400">Loading leads…</p>
          ) : view === "board" ? (
            <LeadBoard
              leads={leads}
              tripsById={tripsById}
              onStatusChange={handleStatusChange}
              onOpen={(id) => router.push(`/leads/${id}`)}
            />
          ) : (
            <>
              <LeadTable
                leads={leads}
                tripsById={tripsById}
                onStatusChange={handleStatusChange}
                onOpen={(id) => router.push(`/leads/${id}`)}
              />
              {total > 20 && (
                <div className="mt-4 flex items-center gap-3 text-sm">
                  <button
                    disabled={page === 0}
                    onClick={() => setPage((p) => p - 1)}
                    className="rounded-md border border-slate-300 px-3 py-1.5 disabled:opacity-40"
                  >
                    ← Prev
                  </button>
                  <span className="text-slate-500">Page {page + 1}</span>
                  <button
                    disabled={(page + 1) * 20 >= total}
                    onClick={() => setPage((p) => p + 1)}
                    className="rounded-md border border-slate-300 px-3 py-1.5 disabled:opacity-40"
                  >
                    Next →
                  </button>
                </div>
              )}
            </>
          )}
        </main>
      </div>

      {showForm && (
        <LeadForm
          trips={trips}
          onCreated={() => {
            setPage(0);
            load();
          }}
          onClose={() => setShowForm(false)}
        />
      )}
    </Protected>
  );
}
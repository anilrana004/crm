"use client";

import { useCallback, useState } from "react";
import { api, type AuditSearchData, type AuditSearchParams } from "@/lib/api";
import { ErrorBox, Section, fmtDateTime, useReport } from "@/components/report/common";

const TH = "bg-slate-50 px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wide text-slate-400";
const TD = "px-3 py-2";
const inputCls =
  "rounded-md border border-slate-300 px-2.5 py-1.5 text-sm text-slate-700 focus:border-slate-500 focus:outline-none";

/**
 * Full-text + trigram search over the audit trail. Read is ADMIN/CEO only —
 * the audit log contains the before/after value of every mutation, including
 * customer contact details.
 */
export function AuditLog() {
  const [query, setQuery] = useState<AuditSearchParams>({ page: 0, size: 25 });
  const [draft, setDraft] = useState({ q: "", entity: "" });

  const { data, loading, error } = useReport<AuditSearchData>(
    () => api.getAuditLog(query),
    [query],
  );

  const apply = useCallback((patch: Partial<AuditSearchParams>) => {
    setQuery((q) => ({ ...q, ...patch, page: 0 }));
  }, []);

  const total = data?.total ?? 0;
  const page = data?.page ?? 0;
  const size = data?.size ?? 25;
  const pages = Math.max(1, Math.ceil(total / size));

  return (
    <div className="space-y-4">
      <Section title="Audit log search">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-1 flex-col gap-1 text-xs text-slate-500">
            Search (name, customer, amount, id, anything)
            <input
              value={draft.q}
              onChange={(e) => setDraft((d) => ({ ...d, q: e.target.value }))}
              placeholder="Try a partial email or an amount…"
              className={inputCls}
            />
          </label>
          <label className="flex w-52 flex-col gap-1 text-xs text-slate-500">
            Entity
            <input
              value={draft.entity}
              onChange={(e) => setDraft((d) => ({ ...d, entity: e.target.value }))}
              placeholder="e.g. LEAD, BOOKING"
              className={inputCls}
            />
          </label>
          <button
            onClick={() => apply({ q: draft.q || undefined, entity: draft.entity || undefined })}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-sm font-semibold text-white hover:bg-slate-800"
          >
            Search
          </button>
        </div>
        <p className="mt-2 text-xs text-slate-400">
          {total.toLocaleString("en-IN")} matching rows · mode {data?.mode ?? "—"} · results are ranked by
          full-text relevance and typo tolerance, not just creation time.
        </p>
      </Section>

      {loading ? (
        <div className="py-8 text-center text-sm text-slate-400">Searching…</div>
      ) : error ? (
        <ErrorBox message={error} />
      ) : (
        data && (
          <Section title={`Results (page ${page + 1} of ${pages})`}>
            <table className="w-full text-sm">
              <thead>
                <tr>
                  <th className={TH}>When</th>
                  <th className={TH}>Actor</th>
                  <th className={TH}>Change</th>
                  <th className={TH}>From</th>
                  <th className={TH}>To</th>
                  <th className={TH}>Why it matched</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-slate-700">
                {data.hits?.length === 0 && (
                  <tr>
                    <td colSpan={6} className="py-6 text-center text-slate-400">
                      No audit rows match.
                    </td>
                  </tr>
                )}
                {(data.hits ?? []).map((h) => (
                  <tr key={h.id}>
                    <td className={`${TD} whitespace-nowrap text-xs`}>{fmtDateTime(h.createdAt)}</td>
                    <td className={TD}>
                      <div className="font-medium text-slate-800">{h.actorName}</div>
                      <div className="text-xs text-slate-400">{String(h.actorId).slice(0, 8)}</div>
                    </td>
                    <td className={TD}>
                      <span className="font-medium text-slate-800">{h.entity}</span>
                      <span className="text-slate-400"> · {h.action}</span>
                      {h.field ? (
                        <div className="text-xs text-slate-400">field: {h.field}</div>
                      ) : null}
                    </td>
                    <td className={`${TD} max-w-52 truncate text-xs text-slate-500`} title={h.oldValue ?? ""}>
                      {h.oldValue || "—"}
                    </td>
                    <td className={`${TD} max-w-52 truncate text-xs`} title={h.newValue ?? ""}>
                      {h.newValue || "—"}
                    </td>
                    <td className={`${TD} text-xs`}>
                      <span className="rounded bg-slate-100 px-1.5 py-0.5 text-slate-500">{h.matchedBy}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <div className="mt-3 flex items-center justify-between">
              <span className="text-xs text-slate-400">
                {total === 0 ? "0 rows" : `Rows ${page * size + 1}–${Math.min((page + 1) * size, total)} of ${total.toLocaleString("en-IN")}`}
              </span>
              <div className="flex gap-2">
                <button
                  onClick={() => setQuery((q) => ({ ...q, page: Math.max(0, page - 1) }))}
                  disabled={page <= 0}
                  className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50 disabled:opacity-40"
                >
                  ← Prev
                </button>
                <button
                  onClick={() => setQuery((q) => ({ ...q, page: page + 1 }))}
                  disabled={page + 1 >= pages}
                  className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50 disabled:opacity-40"
                >
                  Next →
                </button>
              </div>
            </div>
          </Section>
        )
      )}
    </div>
  );
}
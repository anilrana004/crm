"use client";

import { api, type ReportFilterParams, type OperationsReadinessData } from "@/lib/api";
import { ErrorBox, ScopeBadge, Section, fmtDateTime, num, pct, useReport } from "@/components/report/common";

const TH = "bg-slate-50 px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wide text-slate-400";
const TD = "px-3 py-2";

const STATUS_STYLE: Record<string, string> = {
  NOT_ARRANGED: "bg-slate-100 text-slate-500",
  PENDING: "bg-amber-50 text-amber-700",
  CONFIRMED: "bg-emerald-50 text-emerald-700",
  PARTIAL: "bg-sky-50 text-sky-700",
  COMPLETED: "bg-emerald-50 text-emerald-700",
  OVERDUE: "bg-red-50 text-red-700",
  CANCELLED: "bg-slate-100 text-slate-500",
  REFUNDED: "bg-purple-50 text-purple-700",
};

export function OperationsReadiness({ filter }: { filter: ReportFilterParams }) {
  const { data, loading, error } = useReport<OperationsReadinessData>(
    () => api.getOperationsReadiness(filter),
    [filter],
  );

  if (loading) return <div className="py-8 text-center text-sm text-slate-400">Loading operations readiness…</div>;
  if (error) return <ErrorBox message={error} />;
  if (!data) return null;

  const inc = data.incidentSummary;

  return (
    <div className="space-y-4">
      {inc && !inc.available && (
        <div className="rounded-xl border border-slate-200 bg-white p-4">
          <div className="text-sm font-semibold text-slate-800">Incidents</div>
          <p className="mt-1 text-sm text-slate-600">
            Incident tracking is not active yet. This is reported as <b>unavailable</b> rather than zero so
            the dashboard never implies “no incidents”.
            {inc.reason ? <span className="text-slate-500"> {inc.reason}</span> : null}
          </p>
          {inc.openIncidents !== undefined && (
            <div className="mt-1 text-sm text-slate-500">Open incidents: {num(inc.openIncidents)}</div>
          )}
        </div>
      )}

      <Section title="Batch readiness">
        <div className="mb-3 flex items-center justify-between">
          <span className="text-xs text-slate-500">
            Gaps are concrete: an unconirmed hotel, transport or a missing trip sheet.
          </span>
          <ScopeBadge scope={data.scope} />
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr>
              <th className={TH}>Departure</th>
              <th className={TH}>Batch</th>
              <th className={`${TH} text-right`}>Seats</th>
              <th className={`${TH} text-right`}>Fill</th>
              <th className={TH}>Hotel</th>
              <th className={TH}>Transport</th>
              <th className={TH}>Payment</th>
              <th className={TH}>Sheet</th>
              <th className={TH}>Gaps</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 text-slate-700">
            {data.batches?.length === 0 && (
              <tr>
                <td colSpan={9} className="py-6 text-center text-slate-400">
                  No batches in this window.
                </td>
              </tr>
            )}
            {(data.batches ?? []).map((b) => (
              <tr key={b.batchId}>
                <td className={`${TD} whitespace-nowrap`}>{fmtDateTime(b.departureDate as string)}</td>
                <td className={TD}>
                  <div className="font-medium text-slate-800">{b.tripName}</div>
                  <div className="text-xs text-slate-400">{b.batchRef}</div>
                </td>
                <td className={`${TD} text-right`}>{num(b.seatsBooked)} / {num(b.maxCapacity)}</td>
                <td className={`${TD} text-right`}>{pct(b.fillRatePct)}</td>
                <td className={TD}>{statusChip(b.hotelStatus)}</td>
                <td className={TD}>{statusChip(b.transportStatus)}</td>
                <td className={TD}>{statusChip(b.paymentStatus)}</td>
                <td className={TD}>{b.tripSheetGenerated ? "✓" : "—"}</td>
                <td className={TD}>
                  {b.gaps && b.gaps.length > 0 ? (
                    <div className="flex flex-wrap gap-1">
                      {b.gaps.map((g) => (
                        <span key={g} className="rounded bg-red-50 px-1.5 py-0.5 text-xs text-red-700">
                          {g}
                        </span>
                      ))}
                    </div>
                  ) : (
                    <span className="text-slate-300">—</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>

      {data.vendors && data.vendors.length > 0 && (
        <Section title="Vendor scorecards">
          <p className="mb-3 text-xs text-slate-500">
            These score completion of assigned handoffs, not vendor reliability. There is no uptime or
            incident feed to score reliability against yet.
          </p>
          <table className="w-full text-sm">
            <thead>
              <tr>
                <th className={TH}>Vendor</th>
                <th className={TH}>Category</th>
                <th className={`${TH} text-right`}>Assigned</th>
                <th className={`${TH} text-right`}>Confirmed</th>
                <th className={`${TH} text-right`}>Completion</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 text-slate-700">
              {(data.vendors ?? []).map((v) => (
                <tr key={v.vendorId}>
                  <td className={`${TD} font-medium text-slate-800`}>{v.vendorName}</td>
                  <td className={TD}>{v.category}</td>
                  <td className={`${TD} text-right`}>{num(v.handoffsAssigned)}</td>
                  <td className={`${TD} text-right`}>{num(v.handoffsConfirmed)}</td>
                  <td className={`${TD} text-right`}>{pct(v.completionPct)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}
    </div>
  );
}

function statusChip(status?: string) {
  if (!status) return <span className="text-slate-300">—</span>;
  const cls = STATUS_STYLE[status] ?? "bg-slate-100 text-slate-500";
  return <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${cls}`}>{status}</span>;
}
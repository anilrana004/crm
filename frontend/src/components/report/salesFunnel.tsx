"use client";

import { api, type ReportFilterParams, type SalesFunnelData } from "@/lib/api";
import { ErrorBox, ScopeBadge, Section, money, num, pct, useReport } from "@/components/report/common";

const TH = "bg-slate-50 px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wide text-slate-400";
const TD = "px-3 py-2";

export function SalesFunnel({ filter }: { filter: ReportFilterParams }) {
  const { data, loading, error } = useReport<SalesFunnelData>(
    () => api.getSalesFunnel(filter),
    [filter],
  );

  if (loading) return <div className="py-8 text-center text-sm text-slate-400">Loading funnel…</div>;
  if (error) return <ErrorBox message={error} />;
  if (!data) return null;

  const stages = data.stages ?? [];
  const maxReached = Math.max(1, ...stages.map((s) => s.reached ?? 0));

  return (
    <div className="space-y-4">
      <Section title="Funnel by stage">
        <div className="mb-3 flex items-center justify-between">
          <span className="text-xs text-slate-500">
            Reached = status today or any higher stage seen in the audit trail.
          </span>
          <ScopeBadge scope={data.scope} />
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr>
              <th className={TH}>Stage</th>
              <th className={TH}>Currently here</th>
              <th className={TH}>Reached</th>
              <th className={TH}>Conversion</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 text-slate-700">
            {stages.length === 0 && (
              <tr>
                <td colSpan={4} className="py-6 text-center text-slate-400">
                  No leads match this filter.
                </td>
              </tr>
            )}
            {stages.map((s) => (
              <tr key={s.code}>
                <td className={`${TD} font-medium text-slate-800`}>
                  {s.label} <span className="text-xs text-slate-400">{s.code}</span>
                </td>
                <td className={TD}>{num(s.currentlyHere)}</td>
                <td className={TD}>
                  <div className="flex items-center gap-2">
                    <div className="h-2 w-40 overflow-hidden rounded-full bg-slate-100">
                      <div
                        className="h-full rounded-full bg-slate-700"
                        style={{ width: `${Math.round(((s.reached ?? 0) / maxReached) * 100)}%` }}
                      />
                    </div>
                    <span>{num(s.reached)}</span>
                  </div>
                </td>
                <td className={TD}>{pct(s.conversionPct)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>

      {data.bySource && data.bySource.length > 0 && (
        <Section title="Where leads come from">
          <table className="w-full text-sm">
            <thead>
              <tr>
                <th className={TH}>Source</th>
                <th className={TH}>Leads</th>
                <th className={TH}>Reached quotation</th>
                <th className={TH}>Booked</th>
                <th className={TH}>Lead → booking</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 text-slate-700">
              {data.bySource.map((src) => (
                <tr key={src.source}>
                  <td className={`${TD} font-medium text-slate-800`}>{src.source}</td>
                  <td className={TD}>{num(src.leads)}</td>
                  <td className={TD}>{num(src.reachedQuotation)}</td>
                  <td className={TD}>{num(src.booked)}</td>
                  <td className={TD}>{pct(src.conversionPct)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}

      <Section title="Totals">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <Stat label="Leads assigned" value={num(data.totals?.leadsAssigned)} />
          <Stat label="Follow-ups completed" value={num(data.totals?.followUpsCompleted)} />
          <Stat label="Bookings closed" value={num(data.totals?.bookingsClosed)} />
          <Stat label="Revenue" value={money(data.totals?.revenue)} />
        </div>
      </Section>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg bg-slate-50 px-3 py-2">
      <div className="text-[11px] font-medium uppercase tracking-wide text-slate-400">{label}</div>
      <div className="mt-0.5 text-lg font-semibold text-slate-800">{value}</div>
    </div>
  );
}
"use client";

import { api, type ReportFilterParams, type TeamPerformanceData } from "@/lib/api";
import { BasisCallout, ErrorBox, ScopeBadge, Section, money, num, pct, useReport } from "@/components/report/common";

const TH = "bg-slate-50 px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wide text-slate-400";
const TD = "px-3 py-2";

export function TeamPerformance({ filter }: { filter: ReportFilterParams }) {
  const { data, loading, error } = useReport<TeamPerformanceData>(
    () => api.getTeamPerformance(filter),
    [filter],
  );

  if (loading) return <div className="py-8 text-center text-sm text-slate-400">Loading team performance…</div>;
  if (error) return <ErrorBox message={error} />;
  if (!data) return null;

  const consultants = data.consultants ?? [];

  return (
    <div className="space-y-4">
      <Section title="Team performance">
        <div className="mb-3 flex items-center justify-between">
          <span className="text-xs text-slate-500">
            Revenue is credited revenue from the sales ledger; SLA counts only tasks with a deadline.
          </span>
          <ScopeBadge scope={data.scope} />
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr>
              <th className={TH}>Consultant</th>
              <th className={`${TH} text-right`}>Leads owned</th>
              <th className={`${TH} text-right`}>Booked</th>
              <th className={`${TH} text-right`}>Conversion</th>
              <th className={`${TH} text-right`}>Credits</th>
              <th className={`${TH} text-right`}>Revoked</th>
              <th className={`${TH} text-right`}>Ledger revenue</th>
              <th className={`${TH} text-right`}>SLA due / met</th>
              <th className={`${TH} text-right`}>SLA compliance</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 text-slate-700">
            {consultants.length === 0 && (
              <tr>
                <td colSpan={9} className="py-6 text-center text-slate-400">
                  No consultants match this filter.
                </td>
              </tr>
            )}
            {consultants.map((c) => (
              <tr key={c.consultantId}>
                <td className={`${TD} font-medium text-slate-800`}>
                  {c.fullName}
                  <div className="text-xs text-slate-400">{c.email}</div>
                </td>
                <td className={`${TD} text-right`}>{num(c.leadsOwned)}</td>
                <td className={`${TD} text-right`}>{num(c.leadsBooked)}</td>
                <td className={`${TD} text-right`}>{pct(c.conversionPct)}</td>
                <td className={`${TD} text-right`}>{num(c.bookingsCredited)}</td>
                <td className={`${TD} text-right`}>{num(c.creditsRevoked)}</td>
                <td className={`${TD} text-right font-medium text-slate-800`}>{money(c.revenue)}</td>
                <td className={`${TD} text-right`}>{num(c.slaTasksMet)} / {num(c.slaTasksDue)}</td>
                <td className={`${TD} text-right`}>{pct(c.slaCompliancePct)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>

      {data.slaBasis && (
        <BasisCallout title="SLA basis" note={data.slaBasis.note} />
      )}
    </div>
  );
}
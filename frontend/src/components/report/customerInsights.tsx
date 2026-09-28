"use client";

import { api, type CustomerInsightsData, type ReportFilterParams } from "@/lib/api";
import { BasisCallout, ErrorBox, Section, money, num, useReport } from "@/components/report/common";

const TH = "bg-slate-50 px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wide text-slate-400";
const TD = "px-3 py-2";

export function CustomerInsights({ filter }: { filter: ReportFilterParams }) {
  const { data, loading, error } = useReport<CustomerInsightsData>(
    () => api.getCustomerInsights(filter),
    [filter],
  );

  if (loading) return <div className="py-8 text-center text-sm text-slate-400">Loading customer insights…</div>;
  if (error) return <ErrorBox message={error} />;
  if (!data) return null;

  return (
    <div className="space-y-4">
      {data.valueBasis && (
        <BasisCallout title="Value basis" note={data.valueBasis.note} />
      )}

      {data.topDestinations && data.topDestinations.length > 0 && (
        <Section title="Top destinations">
          <div className="flex flex-wrap gap-2">
            {data.topDestinations.map((d) => (
              <span key={d} className="rounded-full bg-slate-100 px-3 py-1 text-sm text-slate-700">
                {d}
              </span>
            ))}
          </div>
        </Section>
      )}

      <Section title="Customers">
        <table className="w-full text-sm">
          <thead>
            <tr>
              <th className={TH}>Customer</th>
              <th className={`${TH} text-right`}>Bookings</th>
              <th className={`${TH} text-right`}>Last booking</th>
              <th className={`${TH} text-right`}>Lifetime value</th>
              <th className={TH}>Repeat</th>
              <th className={TH}>Churn risk</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 text-slate-700">
            {data.customers?.length === 0 && (
              <tr>
                <td colSpan={6} className="py-6 text-center text-slate-400">
                  No customers match this filter.
                </td>
              </tr>
            )}
            {(data.customers ?? []).map((c) => (
              <tr key={c.customerId}>
                <td className={`${TD} font-medium text-slate-800`}>
                  {c.fullName}
                  <div className="text-xs text-slate-400">
                    {c.email || (c.mobileNumber ? `+${c.mobileNumber}` : "")}
                  </div>
                </td>
                <td className={`${TD} text-right`}>{num(c.bookings)}</td>
                <td className={`${TD} text-right`}>{c.lastBookingYear ?? "—"}</td>
                <td className={`${TD} text-right font-medium text-slate-800`}>{money(c.lifetimeValue)}</td>
                <td className={TD}>
                  {c.repeat ? (
                    <span className="rounded bg-emerald-50 px-1.5 py-0.5 text-xs font-medium text-emerald-700">
                      Repeat
                    </span>
                  ) : (
                    <span className="text-slate-300">—</span>
                  )}
                </td>
                <td className={TD}>
                  {c.atChurnRisk ? (
                    <span>
                      <span className="rounded bg-red-50 px-1.5 py-0.5 text-xs font-medium text-red-700">
                        At risk
                      </span>
                      {c.churnReason ? (
                        <span className="ml-1 text-xs text-slate-400">{c.churnReason}</span>
                      ) : null}
                    </span>
                  ) : (
                    <span className="text-slate-300">—</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>
    </div>
  );
}
"use client";

import { api, type ReportFilterParams, type TripPerformanceData } from "@/lib/api";
import { BasisCallout, ErrorBox, ScopeBadge, Section, money, num, pct, useReport } from "@/components/report/common";

const TH = "bg-slate-50 px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wide text-slate-400";
const TD = "px-3 py-2";

export function TripPerformance({ filter }: { filter: ReportFilterParams }) {
  const { data, loading, error } = useReport<TripPerformanceData>(
    () => api.getTripPerformance(filter),
    [filter],
  );

  if (loading) return <div className="py-8 text-center text-sm text-slate-400">Loading trip performance…</div>;
  if (error) return <ErrorBox message={error} />;
  if (!data) return null;

  return (
    <div className="space-y-4">
      <Section title="Trips">
        <div className="mb-3 flex items-center justify-between">
          <span className="text-xs text-slate-500">
            Booked revenue is attributed from the sales ledger, not re-derived from today&apos;s lead owner.
          </span>
          <ScopeBadge scope={data.scope} />
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr>
              <th className={TH}>Trip</th>
              <th className={`${TH} text-right`}>Batches</th>
              <th className={`${TH} text-right`}>Bookings</th>
              <th className={`${TH} text-right`}>Pax / seats</th>
              <th className={`${TH} text-right`}>Capacity</th>
              <th className={`${TH} text-right`}>Revenue</th>
              <th className={`${TH} text-right`}>Avg booking</th>
              <th className={`${TH} text-right`}>Revenue − cost</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 text-slate-700">
            {data.trips?.length === 0 && (
              <tr>
                <td colSpan={8} className="py-6 text-center text-slate-400">
                  No batches match this filter.
                </td>
              </tr>
            )}
            {(data.trips ?? []).map((t) => (
              <tr key={t.tripId}>
                <td className={`${TD} font-medium text-slate-800`}>{t.name}</td>
                <td className={`${TD} text-right`}>{num(t.batches)}</td>
                <td className={`${TD} text-right`}>{num(t.bookings)}</td>
                <td className={`${TD} text-right`}>
                  {num(t.pax)} / {num(t.seatsBooked)}
                </td>
                <td className={`${TD} text-right`}>{pct(t.fillRatePct)}</td>
                <td className={`${TD} text-right`}>{money(t.revenue)}</td>
                <td className={`${TD} text-right`}>{money(t.avgBookingValue)}</td>
                <td className={`${TD} text-right font-medium text-slate-800`}>{money(t.revenueLessBaseCost)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>

      {data.costBasis && (
        <BasisCallout
          title="Cost basis"
          note={`${data.costBasis.note ?? ""}${data.costBasis.isPartialCost ? " · not a margin." : ""}${data.costBasis.treatedAsPerPerson ? " Base cost treated per person." : ""}`.trim()}
        />
      )}

      <StatRow
        label="Totals"
        cells={[
          ["Bookings", num(data.totals?.bookingsClosed)],
          ["Revenue", money(data.totals?.revenue)],
        ]}
      />
    </div>
  );
}

function StatRow({ label, cells }: { label: string; cells: [string, string][] }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white px-4 py-3">
      <div className="text-[11px] font-semibold uppercase tracking-wide text-slate-400">{label}</div>
      <div className="mt-2 grid grid-cols-2 gap-3 md:grid-cols-4">
        {cells.map(([k, v]) => (
          <div key={k} className="text-sm">
            <span className="text-slate-400">{k}: </span>
            <span className="font-semibold text-slate-800">{v}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
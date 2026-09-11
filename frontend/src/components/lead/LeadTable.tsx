"use client";

import { type Lead, type LeadStatusPayload, type Trip } from "@/lib/api";

const STATUSES: string[] = ["NEW", "INTERESTED", "QUOTATION_SENT", "BOOKING_CONFIRMED", "LOST"];
const HEAT_STYLE: Record<string, string> = {
  HOT: "bg-red-100 text-red-700",
  WARM: "bg-amber-100 text-amber-700",
  COLD: "bg-slate-100 text-slate-500",
};
const STATUS_STYLE: Record<string, string> = {
  NEW: "bg-blue-100 text-blue-700",
  INTERESTED: "bg-violet-100 text-violet-700",
  QUOTATION_SENT: "bg-cyan-100 text-cyan-700",
  BOOKING_CONFIRMED: "bg-emerald-100 text-emerald-700",
  LOST: "bg-rose-100 text-rose-700",
};

type LeadTableProps = {
  leads: Lead[];
  tripsById: Map<string, Trip>;
  onStatusChange: (id: string, status: LeadStatusPayload) => void;
  onOpen: (id: string) => void;
};

export function LeadTable({ leads, tripsById, onStatusChange, onOpen }: LeadTableProps) {
  if (leads.length === 0) {
    return <p className="py-10 text-center text-sm text-slate-400">No leads match the current filters.</p>;
  }

  return (
    <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <th className="px-4 py-3">Customer</th>
            <th className="px-4 py-3">Mobile</th>
            <th className="px-4 py-3">Source</th>
            <th className="px-4 py-3">Trip</th>
            <th className="px-4 py-3">Heat</th>
            <th className="px-4 py-3">Travel</th>
            <th className="px-4 py-3">Budget</th>
            <th className="px-4 py-3">Owner</th>
            <th className="px-4 py-3">Status</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {leads.map((lead) => {
            const heat = lead.heat ?? "COLD";
            const status = lead.status ?? "NEW";
            const trip = lead.tripId ? tripsById.get(lead.tripId) : undefined;
            return (
            <tr key={lead.id} className="cursor-pointer hover:bg-slate-50" onClick={() => lead.id && onOpen(lead.id)}>
              <td className="px-4 py-3 font-medium text-slate-900">{lead.customerName}</td>
              <td className="px-4 py-3 text-slate-600">{lead.mobileNumber}</td>
              <td className="px-4 py-3 text-slate-600">{lead.source}</td>
              <td className="px-4 py-3 text-slate-600">{trip ? trip.name : lead.destination || "—"}</td>
              <td className="px-4 py-3">
                <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${HEAT_STYLE[heat] || ""}`}>
                  {heat}
                </span>
              </td>
              <td className="px-4 py-3 text-slate-600">{lead.travelDate ?? "—"}</td>
              <td className="px-4 py-3 text-slate-600">
                {lead.budget != null ? `₹${Number(lead.budget).toLocaleString("en-IN")}` : "—"}
              </td>
              <td className="px-4 py-3 text-slate-600">{lead.ownerName ?? "Unassigned"}</td>
              <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                <select
                  value={status}
                  onChange={(e) =>
                    lead.id && onStatusChange(lead.id, { status: e.target.value as LeadStatusPayload["status"] })
                  }
                  className={`rounded-full border-0 px-2 py-1 text-xs font-semibold outline-none ${STATUS_STYLE[status] || ""}`}
                >
                  {STATUSES.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </td>
            </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
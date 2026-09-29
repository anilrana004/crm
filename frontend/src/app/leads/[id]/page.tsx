"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiClientError, type Lead, type LeadActivity, type LeadStatusPayload, type Trip } from "@/lib/api";
import { LOST_REASON_LABEL, pickLostReason } from "@/lib/lostReasons";
import { Protected } from "@/components/Protected";
import { AppShell } from "@/components/AppShell";
import { LeadForm } from "@/components/lead/LeadForm";
import { FollowUpPanel } from "@/components/lead/FollowUpPanel";

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
const STATUSES = ["NEW", "INTERESTED", "QUOTATION_SENT", "BOOKING_CONFIRMED", "LOST"];

function ActionDot({ action }: { action?: LeadActivity["action"] }) {
  const cls =
    action === "STATUS_CHANGE"
      ? "bg-violet-500"
      : action === "CREATE"
        ? "bg-emerald-500"
        : "bg-slate-400";
  return <span className={`mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full ${cls}`} />;
}

function TimelineEntry({ entry }: { entry: LeadActivity }) {
  const desc =
    entry.field === "note"
      ? `Note: ${entry.newValue}`
      : entry.field === "lost_reason"
        ? `Lost reason: ${entry.oldValue ?? "—"} → ${entry.newValue ?? "—"}`
        : entry.field === "new_trip_interest" || entry.field === "lead"
          ? `Lead created (${entry.newValue})`
          : entry.field
            ? `${entry.field}: ${entry.oldValue ?? "—"} → ${entry.newValue ?? "—"}`
            : entry.action ?? "";

  return (
    <li className="flex gap-3">
      <div className="flex flex-col items-center">
        <ActionDot action={entry.action} />
      </div>
      <div className="min-w-0 flex-1 pb-4">
        <div className="text-sm text-slate-800">{desc}</div>
        <div className="mt-0.5 flex items-center gap-2 text-xs text-slate-400">
          <span>{entry.actorName ?? "System"}</span>
          <span>·</span>
          <span>{entry.createdAt ? new Date(entry.createdAt).toLocaleString() : ""}</span>
          <span className="rounded bg-slate-100 px-1">{entry.action}</span>
        </div>
      </div>
    </li>
  );
}

export default function LeadDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;

  const [lead, setLead] = useState<Lead | null>(null);
  const [activity, setActivity] = useState<LeadActivity[]>([]);
  const [tripsById, setTripsById] = useState<Map<string, Trip>>(new Map());
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [leadData, activityData, trips] = await Promise.all([
        api.getLead(id),
        api.getLeadActivity(id),
        api.getTrips(),
      ]);
      setLead(leadData);
      setActivity(activityData);
      setTripsById(new Map((trips as (Trip & { id?: string })[]).filter((t) => t.id).map((t) => [t.id as string, t])));
    } catch (err) {
      if (err instanceof ApiClientError && err.status === 404) {
        setError("Lead not found.");
      } else {
        setError(err instanceof Error ? err.message : "Could not load lead");
      }
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    if (id) load();
  }, [id, load]);

  const trip = useMemo(() => (lead?.tripId ? tripsById.get(lead.tripId) : undefined), [lead, tripsById]);

  const handleStatusChange = useCallback(
    async (status: string) => {
      if (!lead?.id) return;
      try {
        const payload: LeadStatusPayload = { status: status as LeadStatusPayload["status"] };
        if (status === "LOST") {
          const reason = await pickLostReason(lead.lostReason ?? null);
          if (!reason) return;
          payload.lostReason = reason;
        }
        await api.updateLeadStatus(lead.id, payload);
        await load();
      } catch (err) {
        window.alert(err instanceof Error ? err.message : "Could not update status");
      }
    },
    [lead, load],
  );

  if (loading && !lead) return <Shell>Loading lead…</Shell>;
  if (error && !lead) return <Shell>{error}</Shell>;
  if (!lead) return <Shell>Lead not found.</Shell>;

  const heat = lead.heat ?? "COLD";
  const status = lead.status ?? "NEW";

  return (
    <Protected>
      <AppShell mainClassName="p-4 sm:p-6">
        <Link href="/leads" className="text-sm text-slate-500 hover:text-slate-800">
          ← Back to leads
        </Link>

        <div className="mt-3 mb-6 flex flex-col items-stretch gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">{lead.customerName}</h1>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${STATUS_STYLE[status] || ""}`}>
                {status}
              </span>
              <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${HEAT_STYLE[heat] || ""}`}>
                {heat}
              </span>
              {lead.customer360Id && (
                <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-semibold text-emerald-700">
                  Returning customer
                </span>
              )}
              {status === "LOST" && lead.lostReason && (
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600">
                  {LOST_REASON_LABEL[lead.lostReason] ?? lead.lostReason}
                </span>
              )}
            </div>
          </div>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <select
              value={status}
              onChange={(e) => handleStatusChange(e.target.value)}
              className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900 sm:w-auto"
            >
              {STATUSES.map((s) => (
                <option key={s} value={s}>{s}</option>
              ))}
            </select>
            <button
              onClick={() => setEditing(true)}
              className="w-full rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800 sm:w-auto"
            >
              Edit
            </button>
          </div>
        </div>

        {error && <p className="mb-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}

        <div className="grid gap-6 lg:grid-cols-3">
          <div className="lg:col-span-1 space-y-4">
            <section className="rounded-xl border border-slate-200 bg-white p-5">
              <h2 className="mb-4 text-sm font-semibold text-slate-900">Enquiry details</h2>
              <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
                <Field label="Mobile" value={lead.mobileNumber ?? "—"} />
                <Field label="WhatsApp" value={lead.whatsappNumber ?? "—"} />
                <Field label="Email" value={lead.email ?? "—"} />
                <Field label="Source" value={lead.source ?? "—"} />
                <Field label="Destination" value={lead.destination ?? "—"} />
                <Field label="Trip" value={trip?.name ?? "—"} />
                <Field label="Travel date" value={lead.travelDate ?? "—"} />
                <Field label="Travelers" value={String(lead.numPersons ?? "—")} />
                <Field label="Budget" value={lead.budget != null ? `₹${Number(lead.budget).toLocaleString("en-IN")}` : "—"} />
                <Field label="Follow-up" value={lead.followUpDate ?? "—"} />
                <Field label="Owner" value={lead.ownerName ?? "Unassigned"} />
                <Field label="Consent" value={lead.consentGiven ? "Given ✓" : "Not given"} />
              </dl>
              {lead.remarks && (
                <div className="mt-4 border-t border-slate-100 pt-3 text-sm text-slate-600">
                  <span className="text-xs font-medium text-slate-500">Remarks</span>
                  <p className="mt-1 whitespace-pre-wrap">{lead.remarks}</p>
                </div>
              )}
              <div className="mt-4 border-t border-slate-100 pt-3 text-xs text-slate-400">
                <div>Created {lead.createdAt ? new Date(lead.createdAt).toLocaleString() : "—"}</div>
                <div>Updated {lead.updatedAt ? new Date(lead.updatedAt).toLocaleString() : "—"}</div>
              </div>
            </section>
          </div>

          <section className="lg:col-span-2 space-y-6">
            <FollowUpPanel leadId={lead.id ?? id} />

            <section className="rounded-xl border border-slate-200 bg-white p-5">
              <h2 className="mb-4 text-sm font-semibold text-slate-900">Activity</h2>
            {activity.length === 0 ? (
              <p className="py-8 text-center text-sm text-slate-400">No activity recorded yet.</p>
            ) : (
              <ul className="divide-y divide-slate-100">
                {activity.map((entry) => (
                  <TimelineEntry key={entry.id} entry={entry} />
                ))}
              </ul>
            )}
            </section>
          </section>
        </div>
      </AppShell>

      {editing && lead && (
        <LeadForm
          lead={lead}
          trips={[...tripsById.values()]}
          onUpdated={load}
          onClose={() => setEditing(false)}
        />
      )}
    </Protected>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs font-medium text-slate-500">{label}</dt>
      <dd className="mt-0.5 break-words text-slate-800">{value}</dd>
    </div>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <Protected>
      <AppShell mainClassName="p-4 sm:p-6">
        <Link href="/leads" className="text-sm text-slate-500 hover:text-slate-800">
          ← Back to leads
        </Link>
        <p className="mt-6 text-sm text-slate-500">{children}</p>
      </AppShell>
    </Protected>
  );
}

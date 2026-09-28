"use client";

import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { Protected } from "@/components/Protected";
import { Sidebar } from "@/components/Sidebar";
import { FilterBar, type AppliedFilter } from "@/components/report/filterBar";
import { SalesFunnel } from "@/components/report/salesFunnel";
import { TripPerformance } from "@/components/report/tripPerformance";
import { TeamPerformance } from "@/components/report/teamPerformance";
import { OperationsReadiness } from "@/components/report/operationsReadiness";
import { CustomerInsights } from "@/components/report/customerInsights";
import { AuditLog } from "@/components/report/auditLog";

type TabId = "funnel" | "trips" | "team" | "operations" | "customers" | "audit";

const TABS: { id: TabId; label: string; roles: string[]; blurb: string }[] = [
  { id: "funnel", label: "Sales funnel", roles: ["SALES", "OPS", "MANAGER", "ADMIN", "CEO"], blurb: "Lead → booked, by stage and source." },
  { id: "trips", label: "Trip performance", roles: ["SALES", "OPS", "MANAGER", "ADMIN", "CEO"], blurb: "Bookings, capacity and revenue per trip." },
  { id: "team", label: "Team performance", roles: ["SALES", "OPS", "MANAGER", "ADMIN", "CEO"], blurb: "Consultant revenue and SLA compliance." },
  { id: "operations", label: "Operations readiness", roles: ["OPS", "MANAGER", "ADMIN", "CEO"], blurb: "Batch gaps and vendor completion." },
  { id: "customers", label: "Customer insights", roles: ["SALES", "OPS", "MANAGER", "ADMIN", "CEO"], blurb: "Loyalty and churn risk." },
  { id: "audit", label: "Audit log", roles: ["ADMIN", "CEO"], blurb: "Full-text search over every mutation." },
];

function ReportsView() {
  const { user } = useAuth();
  const role = user?.role ?? "";
  const visibleTabs = TABS.filter((t) => t.roles.includes(role));

  const [tab, setTab] = useState<TabId>(visibleTabs[0]?.id ?? "funnel");
  const [filter, setFilter] = useState<AppliedFilter>({});

  const current = TABS.find((t) => t.id === tab);

  return (
    <div className="flex min-h-screen bg-slate-50">
      <Sidebar />
      <main className="flex-1 overflow-y-auto p-6">
        <div className="mb-4">
          <h1 className="text-xl font-semibold text-slate-900">Reports</h1>
          <p className="text-sm text-slate-500">{current?.blurb}</p>
        </div>

        <div className="mb-4 flex flex-wrap gap-1 border-b border-slate-200">
          {visibleTabs.map((t) => (
            <button
              key={t.id}
              onClick={() => setTab(t.id)}
              className={`-mb-px rounded-t-lg border-b-2 px-3 py-2 text-sm font-medium ${
                tab === t.id
                  ? "border-slate-900 text-slate-900"
                  : "border-transparent text-slate-500 hover:text-slate-700"
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>

        {tab !== "audit" && (
          <div className="mb-4 rounded-xl border border-slate-200 bg-white px-4 py-3">
            <FilterBar filter={filter} onApply={setFilter} />
          </div>
        )}

        {tab === "funnel" && <SalesFunnel filter={filter} />}
        {tab === "trips" && <TripPerformance filter={filter} />}
        {tab === "team" && <TeamPerformance filter={filter} />}
        {tab === "operations" && <OperationsReadiness filter={filter} />}
        {tab === "customers" && <CustomerInsights filter={filter} />}
        {tab === "audit" && <AuditLog />}
      </main>
    </div>
  );
}

export default function ReportsPage() {
  return (
    <Protected>
      <ReportsView />
    </Protected>
  );
}
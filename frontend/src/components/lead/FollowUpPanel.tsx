"use client";

import { useCallback, useEffect, useState } from "react";
import { api, type Task } from "@/lib/api";

const TYPE_LABEL: Record<string, string> = {
  INITIAL_CALL: "First call (within 5 min)",
  FOLLOW_UP_1D: "Follow-up · Day +1",
  FOLLOW_UP_3D: "Follow-up · Day +3",
  FOLLOW_UP_8D: "Follow-up · Day +8",
  FOLLOW_UP_15D: "Follow-up · Day +15",
  QUOTATION: "Quotation follow-up",
  PAYMENT_REMINDER: "Payment reminder",
  OPS: "Ops handoff",
  REVIEW: "Review",
  CUSTOM: "Custom",
};

const STATUS_STYLE: Record<string, string> = {
  PENDING: "bg-amber-100 text-amber-700",
  OVERDUE: "bg-red-100 text-red-700",
  COMPLETED: "bg-emerald-100 text-emerald-700",
  CANCELLED: "bg-slate-100 text-slate-500",
};

export function FollowUpPanel({ leadId }: { leadId: string }) {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.getTasks({ leadId });
      setTasks(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load follow-ups");
    } finally {
      setLoading(false);
    }
  }, [leadId]);

  useEffect(() => {
    if (leadId) load();
  }, [leadId, load]);

  const complete = async (taskId: string) => {
    try {
      await api.completeTask(taskId);
      await load();
    } catch (err) {
      window.alert(err instanceof Error ? err.message : "Could not complete task");
    }
  };

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-5">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-900">Follow-ups &amp; tasks</h2>
        <button onClick={load} className="text-xs text-slate-500 hover:text-slate-800">
          Refresh
        </button>
      </div>

      {error && <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-xs text-red-700">{error}</p>}
      {loading ? (
        <p className="py-6 text-center text-sm text-slate-400">Loading tasks…</p>
      ) : tasks.length === 0 ? (
        <p className="py-6 text-center text-sm text-slate-400">
          No follow-up tasks yet. Changing status to <b>Interested</b> or{" "}
          <b>Quotation sent</b> auto-schedules them.
        </p>
      ) : (
        <ul className="space-y-2">
          {tasks.map((t) => (
            <li
              key={t.id}
              className="flex items-start justify-between gap-3 rounded-lg border border-slate-100 px-3 py-2.5"
            >
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-slate-800">
                    {TYPE_LABEL[t.type ?? ""] ?? t.type}
                  </span>
                  <span
                    className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${
                      STATUS_STYLE[t.status ?? ""] || ""
                    }`}
                  >
                    {t.status}
                  </span>
                </div>
                <div className="mt-0.5 text-xs text-slate-400">
                  {t.dueAt ? `Due ${new Date(t.dueAt).toLocaleString()}` : ""}
                  {t.slaDeadline ? ` · SLA ${new Date(t.slaDeadline).toLocaleString()}` : ""}
                </div>
              </div>
              {(t.status === "PENDING" || t.status === "OVERDUE") && t.id && (
                <button
                  onClick={() => complete(t.id as string)}
                  className="shrink-0 rounded-md bg-slate-900 px-2.5 py-1 text-xs font-semibold text-white hover:bg-slate-800"
                >
                  Complete
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
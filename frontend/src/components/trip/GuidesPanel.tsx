"use client";

import { useState, type FormEvent } from "react";
import { api, type Guide, type GuideCreatePayload, ApiClientError } from "@/lib/api";

type GuidesPanelProps = {
  guides: Guide[];
  canManage: boolean;
  onChanged: () => void;
};

export function GuidesPanel({ guides, canManage, onChanged }: GuidesPanelProps) {
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState({ fullName: "", phone: "", dailyRate: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function addGuide(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const payload: GuideCreatePayload = {
        category: "GUIDE",
        name: form.fullName,
        phone: form.phone || undefined,
        dailyRate: form.dailyRate ? Number(form.dailyRate) : undefined,
      };
      await api.createGuide(payload);
      setAdding(false);
      setForm({ fullName: "", phone: "", dailyRate: "" });
      onChanged();
    } catch (err) {
      setError(err instanceof ApiClientError && err.body.message ? err.body.message : "Could not add guide");
    } finally {
      setBusy(false);
    }
  }

  async function toggle(guide: Guide) {
    try {
      await api.updateGuide(guide.id as string, { active: !guide.active });
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not update guide");
    }
  }

  const input =
    "w-full rounded-md border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-slate-900";

  return (
    <section className="rounded-xl border border-slate-200 bg-white">
      <div className="flex items-center justify-between border-b border-slate-200 px-5 py-3">
        <div>
          <h3 className="text-sm font-semibold text-slate-900">Guides</h3>
          <p className="text-xs text-slate-400">{guides.filter((g) => g.active).length} active · {guides.length} total</p>
        </div>
        {canManage && !adding && (
          <button
            onClick={() => setAdding(true)}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-sm font-semibold text-white hover:bg-slate-800"
          >
            + Add guide
          </button>
        )}
      </div>

      <div className="px-5 py-3">
        {adding && (
          <form onSubmit={addGuide} className="mb-4 grid grid-cols-1 items-end gap-3 sm:grid-cols-3">
            <label className="block text-xs font-medium text-slate-600">
              Full name
              <input
                required
                value={form.fullName}
                onChange={(e) => setForm((f) => ({ ...f, fullName: e.target.value }))}
                className={`${input} mt-1`}
              />
            </label>
            <label className="block text-xs font-medium text-slate-600">
              Phone
              <input
                placeholder="+91 …"
                value={form.phone}
                onChange={(e) => setForm((f) => ({ ...f, phone: e.target.value }))}
                className={`${input} mt-1`}
              />
            </label>
            <div className="flex items-center gap-2">
              <label className="block flex-1 text-xs font-medium text-slate-600">
                Day rate ₹
                <input
                  type="number"
                  min="0"
                  value={form.dailyRate}
                  onChange={(e) => setForm((f) => ({ ...f, dailyRate: e.target.value }))}
                  className={`${input} mt-1`}
                />
              </label>
              <button
                disabled={busy}
                className="mt-4 rounded-md bg-slate-900 px-3 py-1.5 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
              >
                Add
              </button>
            </div>
          </form>
        )}

        {error && <p className="mb-3 text-sm text-red-600">{error}</p>}

        {guides.length === 0 && <p className="py-2 text-sm text-slate-500">No guides yet.</p>}

        <ul className="divide-y divide-slate-100">
          {guides.map((g) => (
            <li key={g.id} className="flex items-center gap-3 py-2">
              <div className="flex-1">
                <div className="text-sm font-medium text-slate-800">{g.name}</div>
                <div className="text-xs text-slate-400">
                  {g.phone || "no phone"} · ₹{g.dailyRate ?? 0}/day
                </div>
              </div>
              <span
                className={`rounded-md px-2 py-0.5 text-xs font-semibold ${
                  g.active ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-500"
                }`}
              >
                {g.active ? "Active" : "Inactive"}
              </span>
              {canManage && (
                <button
                  onClick={() => toggle(g)}
                  className="rounded-md border border-slate-300 px-2 py-1 text-xs font-semibold text-slate-600 hover:bg-slate-50"
                >
                  {g.active ? "Deactivate" : "Activate"}
                </button>
              )}
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
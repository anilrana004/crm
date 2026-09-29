"use client";

import { useState } from "react";
import { api, type Batch, type BatchCreatePayload, type BatchUpdatePayload, type Guide, type TripDetail, ApiClientError } from "@/lib/api";

const STATUS_STYLE: Record<NonNullable<Batch["status"]>, string> = {
  OPEN: "bg-emerald-50 text-emerald-700",
  CLOSED: "bg-amber-50 text-amber-700",
  CANCELLED: "bg-red-50 text-red-700",
  READY_FOR_DEPARTURE: "bg-sky-50 text-sky-700",
};

type BatchesPanelProps = {
  trip: TripDetail;
  guides: Guide[];
  canManage: boolean;
  onChanged: () => void;
};

export function BatchesPanel({ trip, guides, canManage, onChanged }: BatchesPanelProps) {
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState({ departureDate: "", maxCapacity: "24", guideId: "", transportPlan: "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const fixedBatch = trip.bookingType === "FIXED_BATCH";

  async function addBatch() {
    setError(null);
    setBusy(true);
    try {
      const payload: BatchCreatePayload = {
        departureDate: draft.departureDate,
        maxCapacity: Number(draft.maxCapacity),
        guideId: draft.guideId || undefined,
        transportPlan: draft.transportPlan || undefined,
      };
      await api.createBatch(trip.id as string, payload);
      setAdding(false);
      setDraft({ departureDate: "", maxCapacity: "24", guideId: "", transportPlan: "" });
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not add batch");
    } finally {
      setBusy(false);
    }
  }

  const input =
    "rounded-md border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-slate-900";

  return (
    <section className="rounded-xl border border-slate-200 bg-white">
      <div className="flex items-center justify-between border-b border-slate-200 px-5 py-3">
        <div>
          <h3 className="text-sm font-semibold text-slate-900">Departure batches</h3>
          <p className="text-xs text-slate-400">{(trip.batches ?? []).length} scheduled</p>
        </div>
        {canManage && fixedBatch && !adding && (
          <button
            onClick={() => setAdding(true)}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-sm font-semibold text-white hover:bg-slate-800"
          >
            + Add batch
          </button>
        )}
      </div>

      {!fixedBatch ? (
        <p className="px-5 py-4 text-sm text-slate-500">
          CUSTOM_FIT trips are private bookings and have no shared departure batches.
        </p>
      ) : (
        <div className="divide-y divide-slate-100 px-5">
          {adding && (
            <div className="grid grid-cols-1 items-end gap-3 py-3 sm:grid-cols-2">
              <label className="block text-xs font-medium text-slate-600">
                Departure date
                <input
                  type="date"
                  required
                  value={draft.departureDate}
                  onChange={(e) => setDraft((d) => ({ ...d, departureDate: e.target.value }))}
                  className={`${input} mt-1 w-full`}
                />
              </label>
              <label className="block text-xs font-medium text-slate-600">
                Capacity
                <input
                  type="number"
                  min="1"
                  value={draft.maxCapacity}
                  onChange={(e) => setDraft((d) => ({ ...d, maxCapacity: e.target.value }))}
                  className={`${input} mt-1 w-full`}
                />
              </label>
              <label className="block text-xs font-medium text-slate-600">
                Guide
                <select
                  value={draft.guideId}
                  onChange={(e) => setDraft((d) => ({ ...d, guideId: e.target.value }))}
                  className={`${input} mt-1 w-full`}
                >
                  <option value="">Unassigned</option>
                  {guides.map((g) => (
                    <option key={g.id} value={g.id}>
                      {g.name}
                    </option>
                  ))}
                </select>
              </label>
              <div className="flex items-center gap-2">
                <button
                  onClick={addBatch}
                  disabled={busy || !draft.departureDate}
                  className="rounded-md bg-slate-900 px-3 py-1.5 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
                >
                  Add
                </button>
                <button
                  onClick={() => setAdding(false)}
                  className="rounded-md border border-slate-300 px-3 py-1.5 text-sm font-semibold text-slate-600"
                >
                  Cancel
                </button>
              </div>
              {error && <p className="text-sm text-red-600 sm:col-span-2">{error}</p>}
            </div>
          )}

          {((trip.batches ?? []).length === 0) && !adding && (
            <p className="py-4 text-sm text-slate-500">No batches yet.</p>
          )}

          {(trip.batches ?? []).map((b) => (
            <BatchRow key={b.id} batch={b} guides={guides} canManage={canManage} onChanged={onChanged} />
          ))}
        </div>
      )}
    </section>
  );
}

function BatchRow({
  batch,
  guides,
  canManage,
  onChanged,
}: {
  batch: Batch;
  guides: Guide[];
  canManage: boolean;
  onChanged: () => void;
}) {
  const [form, setForm] = useState({
    departureDate: batch.departureDate,
    maxCapacity: String(batch.maxCapacity),
    guideId: batch.guideId ?? "",
    transportPlan: batch.transportPlan ?? "",
    status: batch.status,
  });
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  const dirty =
    form.departureDate !== batch.departureDate ||
    form.maxCapacity !== String(batch.maxCapacity) ||
    form.guideId !== (batch.guideId ?? "") ||
    form.transportPlan !== (batch.transportPlan ?? "") ||
    form.status !== batch.status;

  async function save() {
    setMsg(null);
    setBusy(true);
    try {
      const payload: BatchUpdatePayload = {
        departureDate: form.departureDate,
        maxCapacity: Number(form.maxCapacity),
        guideId: form.guideId || undefined,
        unassignGuide: !form.guideId,
        transportPlan: form.transportPlan || undefined,
        status: form.status,
      };
      await api.updateBatch(batch.id as string, payload);
      setMsg("Saved");
      onChanged();
    } catch (err) {
      setMsg(err instanceof ApiClientError ? err.body.message ?? "Save failed" : "Save failed");
    } finally {
      setBusy(false);
    }
  }

  const input =
    "w-full rounded-md border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-slate-900 disabled:bg-slate-50 disabled:text-slate-500";

  return (
    <div className="py-3">
      <div className="grid grid-cols-1 items-center gap-3 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_repeat(3,90px)_1fr]">
        <label className="block text-xs font-medium text-slate-600">
          Departure
          <input
            type="date"
            value={form.departureDate}
            disabled={!canManage || batch.status === "CANCELLED"}
            onChange={(e) => setForm((f) => ({ ...f, departureDate: e.target.value }))}
            className={`${input} mt-1`}
          />
        </label>
        <label className="block text-xs font-medium text-slate-600">
          Guide
          {canManage && batch.status !== "CANCELLED" ? (
            <select
              value={form.guideId}
              onChange={(e) => setForm((f) => ({ ...f, guideId: e.target.value }))}
              className={`${input} mt-1`}
            >
              <option value="">Unassigned</option>
              {guides.map((g) => (
                <option key={g.id} value={g.id}>
                  {g.name}
                </option>
              ))}
            </select>
          ) : (
            <span className={`${input} mt-1 block border-slate-200 bg-slate-50`}>{batch.guideName ?? "Unassigned"}</span>
          )}
        </label>
        <label className="block text-xs font-medium text-slate-600">
          Capacity
          <input
            type="number"
            min="1"
            value={form.maxCapacity}
            disabled={!canManage || batch.status === "CANCELLED"}
            onChange={(e) => setForm((f) => ({ ...f, maxCapacity: e.target.value }))}
            className={`${input} mt-1`}
          />
        </label>
        <label className="block text-xs font-medium text-slate-600">
          Booked
          <span className={`${input} mt-1 block border-slate-200 bg-slate-50`}>{batch.seatsBooked}</span>
        </label>
        <label className="block text-xs font-medium text-slate-600">
          Available
          <span className={`${input} mt-1 block border-slate-200 bg-slate-50`}>{batch.available}</span>
        </label>
        <label className="block text-xs font-medium text-slate-600">
          Status
          {canManage && batch.status !== "CANCELLED" ? (
            <select
              value={form.status}
              onChange={(e) => setForm((f) => ({ ...f, status: e.target.value as Batch["status"] }))}
              className={`${input} mt-1`}
            >
              {["OPEN", "CLOSED", "CANCELLED"].map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          ) : (
            <span
              className={`${input} mt-1 block border-slate-200 ${STATUS_STYLE[batch.status!]} bg-opacity-20 text-center`}
            >
              {batch.status}
            </span>
          )}
        </label>
      </div>

      <div className="mt-2 flex items-center gap-3">
        {batch.seatsBooked! < batch.maxCapacity! && batch.status === "OPEN" && (
          <span className="text-xs text-emerald-600">{batch.available} seats open</span>
        )}
        {batch.status === "CLOSED" && <span className="text-xs text-amber-600">Closed to new bookings</span>}
        {batch.status === "CANCELLED" && <span className="text-xs text-red-600">This departure was cancelled</span>}
        {canManage && batch.status !== "CANCELLED" && (
          <button
            onClick={save}
            disabled={busy || !dirty}
            className="ml-auto rounded-md border border-slate-300 px-3 py-1 text-xs font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-40"
          >
            {busy ? "Saving…" : "Save"}
          </button>
        )}
        {msg && <span className="ml-auto text-xs text-slate-500">{msg}</span>}
      </div>
    </div>
  );
}
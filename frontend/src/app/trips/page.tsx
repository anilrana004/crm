"use client";

import { useCallback, useEffect, useState } from "react";
import { api, type Guide, type Trip, type TripDetail } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Protected } from "@/components/Protected";
import { AppShell } from "@/components/AppShell";
import { TripForm } from "@/components/trip/TripForm";
import { BatchesPanel } from "@/components/trip/BatchesPanel";
import { GuidesPanel } from "@/components/trip/GuidesPanel";

const MANAGER_ROLES = ["MANAGER", "ADMIN", "CEO"];
const CATEGORY_STYLE: Record<string, string> = {
  TREK: "bg-emerald-50 text-emerald-700",
  PILGRIMAGE: "bg-amber-50 text-amber-700",
  LEISURE: "bg-sky-50 text-sky-700",
  CUSTOM: "bg-violet-50 text-violet-700",
};

export default function TripsPage() {
  const { user } = useAuth();
  const canManage = !!user?.role && MANAGER_ROLES.includes(user.role);

  const [trips, setTrips] = useState<Trip[]>([]);
  const [guides, setGuides] = useState<Guide[]>([]);
  const [selected, setSelected] = useState<TripDetail | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(() => {
    setLoading(true);
    setError(null);
    Promise.all([
      api.getTrips(canManage ? false : true),
      api.getGuides(), // active guides for the assignment dropdowns
    ])
      .then(([tripList, guideList]) => {
        setTrips(tripList);
        setGuides(guideList);
      })
      .catch((err) => setError(err instanceof Error ? err.message : "Could not load trips"))
      .finally(() => setLoading(false));
  }, [canManage]);

  useEffect(() => {
    // GuidesPanel wants all guides for managers so it can toggle the inactive ones.
    if (canManage) api.getGuides(false).then(setGuides).catch(() => undefined);
  }, [canManage]);

  useEffect(() => {
    reload();
  }, [reload]);

  const open = useCallback(async (listItem: Trip) => {
    try {
      const detail = await api.getTrip(listItem.id as string);
      setSelected(detail);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load trip");
    }
  }, []);

  const refreshSelected = useCallback(async () => {
    if (selected?.id) {
      try {
        setSelected(await api.getTrip(selected.id));
      } catch (err) {
        setError(err instanceof Error ? err.message : "Could not reload trip");
      }
    }
  }, [selected?.id]);

  return (
    <Protected>
      <AppShell mainClassName="p-4 sm:p-6">
          <div className="mb-6 flex flex-col items-stretch gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h1 className="text-xl font-semibold text-slate-900">Trips &amp; Batches</h1>
              <p className="text-sm text-slate-500">{trips.length} trips in catalogue</p>
            </div>
            {canManage && (
              <button
                onClick={() => setShowForm(true)}
                className="w-full rounded-md bg-slate-900 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800 sm:w-auto"
              >
                + New trip
              </button>
            )}
          </div>

          {error && <p className="mb-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}

          <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[300px_1fr]">
            <div className="space-y-2">
              {loading ? (
                <p className="py-6 text-center text-sm text-slate-400">Loading trips…</p>
              ) : trips.length === 0 ? (
                <p className="py-6 text-center text-sm text-slate-400">No trips yet.</p>
              ) : (
                trips.map((t) => (
                  <button
                    key={t.id}
                    onClick={() => open(t)}
                    className={`w-full rounded-xl border p-4 text-left transition ${
                      selected?.id === t.id
                        ? "border-slate-900 bg-slate-900 text-white"
                        : "border-slate-200 bg-white hover:border-slate-300"
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-semibold">{t.name}</span>
                      {!t.active && (
                        <span
                          className={`rounded-md px-1.5 py-0.5 text-[11px] font-semibold ${
                            selected?.id === t.id
                              ? "bg-slate-700 text-slate-200"
                              : "bg-slate-100 text-slate-500"
                          }`}
                        >
                          Inactive
                        </span>
                      )}
                    </div>
                    <div className={`mt-1 flex items-center gap-2 text-xs ${selected?.id === t.id ? "text-slate-300" : "text-slate-500"}`}>
                      <span>{t.durationDays} days</span>
                      <span>·</span>
                      <span>₹{t.baseCost}</span>
                      <span>·</span>
                      <span>{t.bookingType === "FIXED_BATCH" ? "Fixed batch" : "Custom fit"}</span>
                    </div>
                  </button>
                ))
              )}
            </div>

            <div className="space-y-4">
              {selected ? (
                <>
                  <section className="rounded-xl border border-slate-200 bg-white p-5">
                    <div className="flex flex-col items-stretch gap-4 sm:flex-row sm:items-start sm:justify-between">
                      <div>
                        <div className="flex items-center gap-2">
                          <h2 className="text-lg font-semibold text-slate-900">{selected.name}</h2>
                          <span className={`rounded-md px-2 py-0.5 text-xs font-semibold ${CATEGORY_STYLE[selected.category ?? "LEISURE"]}`}>
                            {selected.category}
                          </span>
                        </div>
                        <p className="mt-1 text-xs text-slate-400">
                          {selected.slug} · {selected.bookingType} · {selected.durationDays} days · ₹{selected.baseCost}
                          {!selected.active && " · deactivated"}
                        </p>
                      </div>
                      {canManage && (
                        <button
                          onClick={() => setShowForm(true)}
                          className="w-full shrink-0 rounded-md border border-slate-300 px-3 py-1.5 text-sm font-semibold text-slate-700 hover:bg-slate-50 sm:w-auto"
                        >
                          Edit
                        </button>
                      )}
                    </div>

                    <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
                      {selected.itinerary && (
                        <div>
                          <h4 className="text-xs font-semibold uppercase tracking-wide text-slate-400">Itinerary</h4>
                          <p className="mt-1 whitespace-pre-wrap text-sm text-slate-700">{selected.itinerary}</p>
                        </div>
                      )}
                      {selected.inclusions && (
                        <div>
                          <h4 className="text-xs font-semibold uppercase tracking-wide text-slate-400">Inclusions</h4>
                          <p className="mt-1 whitespace-pre-wrap text-sm text-slate-700">{selected.inclusions}</p>
                        </div>
                      )}
                      {selected.exclusions && (
                        <div className="sm:col-span-2">
                          <h4 className="text-xs font-semibold uppercase tracking-wide text-slate-400">Exclusions</h4>
                          <p className="mt-1 whitespace-pre-wrap text-sm text-slate-700">{selected.exclusions}</p>
                        </div>
                      )}
                    </div>
                  </section>

                  <BatchesPanel trip={selected} guides={guides} canManage={canManage} onChanged={refreshSelected} />
                  <GuidesPanel guides={guides} canManage={canManage} onChanged={() => api.getGuides(canManage ? false : true).then(setGuides).catch(() => undefined)} />
                </>
              ) : (
                <p className="rounded-xl border border-dashed border-slate-300 py-16 text-center text-sm text-slate-400">
                  Select a trip to manage its departure batches and guides.
                </p>
              )}
            </div>
          </div>
      </AppShell>

      {showForm && (
        <TripForm
          trip={selected ?? null}
          onSaved={async () => {
            await Promise.all([reload(), refreshSelected()]);
          }}
          onClose={() => setShowForm(false)}
        />
      )}
    </Protected>
  );
}
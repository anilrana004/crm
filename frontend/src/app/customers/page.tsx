"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import { api, ApiClientError, type CustomerDetail, type CustomerListItem } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Protected } from "@/components/Protected";
import { Sidebar } from "@/components/Sidebar";

const CAN_WRITE = ["MANAGER", "ADMIN", "CEO"];
const ALLOWED_TAGS = [
  "Kashmir",
  "Char Dham",
  "Family tour",
  "Anniversary",
  "Honeymoon",
  "Kedarnath",
  "Adventure",
  "Group",
  "Seniors",
  "High value",
];

function fmtDate(d: string | undefined) {
  return d ? new Date(d + (d.length === 10 ? "T00:00:00" : "")).toLocaleDateString("en-IN") : "—";
}

function Tag({ children }: { children: ReactNode }) {
  return (
    <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-600">
      {children}
    </span>
  );
}

function CustomersView() {
  const { user } = useAuth();
  const canWrite = !!user?.role && CAN_WRITE.includes(user.role);

  const [rows, setRows] = useState<CustomerListItem[]>([]);
  const [search, setSearch] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<CustomerDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [suggestOffer, setSuggestOffer] = useState("");
  const [offerTags, setOfferTags] = useState<string[]>([]);
  const [marketingOptIn, setMarketingOptIn] = useState(false);
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);

  const reload = useCallback(
    (keepSelection = false) => {
      setLoading(true);
      setError(null);
      api
        .getCustomers(search || undefined)
        .then((data) => {
          setRows(data);
          if (!keepSelection) setSelectedId(null);
        })
        .catch((err) =>
          setError(err instanceof Error ? err.message : "Could not load customers")
        )
        .finally(() => setLoading(false));
    },
    [search],
  );

  useEffect(() => {
    const t = setTimeout(() => reload(false), search ? 300 : 0);
    return () => clearTimeout(t);
  }, [search, reload]);

  const openDetail = useCallback(
    async (id: string) => {
      if (id === selectedId && detail) return;
      setSelectedId(id);
      setDetailLoading(true);
      setActionError(null);
      try {
        const d = await api.getCustomer(id);
        setDetail(d);
        setSuggestOffer(d.suggestOffer ?? "");
        setOfferTags(d.offerTags ?? []);
        setMarketingOptIn(!!d.marketingOptIn);
        setNotes(d.notes ?? "");
      } catch (err) {
        setActionError(err instanceof Error ? err.message : "Could not load customer detail");
      } finally {
        setDetailLoading(false);
      }
    },
    [selectedId, detail],
  );

  const toggleTag = (tag: string) => {
    setOfferTags((prev) =>
      prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag],
    );
  };

  const save = useCallback(async () => {
    if (!selectedId) return;
    setActionError(null);
    setSaving(true);
    try {
      const fresh = await api.updateCustomer(selectedId, {
        suggestOffer: suggestOffer || undefined,
        offerTags: offerTags.length ? offerTags : undefined,
        marketingOptIn,
        notes: notes || undefined,
      });
      setDetail(fresh);
      reload(true);
    } catch (err) {
      const msg =
        err instanceof ApiClientError
          ? err.body.message || "Could not save customer"
          : err instanceof Error
            ? err.message
            : "Save failed";
      setActionError(msg);
    } finally {
      setSaving(false);
    }
  }, [selectedId, suggestOffer, offerTags, marketingOptIn, notes, reload]);

  return (
    <>
      <div className="flex h-screen bg-slate-50">
        <Sidebar />
        <main className="flex min-w-0 flex-1 flex-col overflow-hidden">
          <header className="border-b border-slate-200 bg-white px-6 py-4">
            <h1 className="text-lg font-semibold text-slate-900">Customers</h1>
            <p className="text-xs text-slate-400">
              Trip counts, spend and remarketing flags. Writes: Manager / Admin / CEO.
            </p>
          </header>

          <div className="flex min-h-0 flex-1">
            <div className="flex w-[46%] min-w-0 flex-col border-r border-slate-200 bg-white">
              <div className="border-b border-slate-200 p-3">
                <input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search name, mobile or email…"
                  className="w-full rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
                />
              </div>
              <div className="min-h-0 flex-1 overflow-y-auto">
                {loading ? (
                  <p className="p-4 text-sm text-slate-400">Loading…</p>
                ) : error ? (
                  <p className="p-4 text-sm text-red-600">{error}</p>
                ) : rows.length === 0 ? (
                  <p className="p-4 text-sm text-slate-400">No customers yet.</p>
                ) : (
                  <ul className="divide-y divide-slate-100">
                    {rows.map((c) => (
                      <li key={c.id}>
                        <button
                          onClick={() => openDetail(c.id as string)}
                          className={`w-full px-4 py-3 text-left hover:bg-slate-50 ${
                            selectedId === c.id ? "bg-slate-50" : ""
                          }`}
                        >
                          <div className="flex items-baseline justify-between">
                            <span className="text-sm font-medium text-slate-900">{c.fullName}</span>
                            <span className="text-xs text-slate-400">{fmtDate(c.lastTripDate)}</span>
                          </div>
                          <div className="text-xs text-slate-500">
                            {c.mobileNumber}
                            {c.email ? ` · ${c.email}` : ""}
                          </div>
                          <div className="mt-1 flex items-center gap-2">
                            <span className="text-xs text-slate-500">
                              {c.totalTrips} trip{c.totalTrips === 1 ? "" : "s"} · ₹
                              {(c.totalSpent ?? 0).toLocaleString("en-IN")}
                            </span>
                            {c.suggestOffer && (
                              <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[11px] font-medium text-amber-700">
                                offer
                              </span>
                            )}
                            {(c.offerTags ?? []).slice(0, 2).map((t) => (
                              <Tag key={t}>{t}</Tag>
                            ))}
                            {(c.offerTags ?? []).length > 2 && (
                              <span className="text-[11px] text-slate-400">
                                +{c.offerTags!.length - 2}
                              </span>
                            )}
                          </div>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>

            <div className="min-w-0 flex-1 overflow-y-auto bg-white">
              {detailLoading ? (
                <p className="p-6 text-sm text-slate-400">Loading…</p>
              ) : !detail ? (
                <p className="p-6 text-sm text-slate-400">
                  Select a customer to see the 360 view.
                </p>
              ) : (
                <div className="p-6">
                  <div className="mb-4 flex items-start justify-between">
                    <div>
                      <h2 className="text-lg font-semibold text-slate-900">{detail.fullName}</h2>
                      <div className="text-sm text-slate-500">
                        {detail.mobileNumber}
                        {detail.whatsappNumber ? ` · WhatsApp ${detail.whatsappNumber}` : ""}
                      </div>
                      {detail.email && <div className="text-sm text-slate-500">{detail.email}</div>}
                      <div className="mt-1 text-xs text-slate-400">
                        consent {detail.consentGiven ? "on" : "off"}
                        {detail.consentScope ? ` · ${detail.consentScope}` : ""}
                      </div>
                    </div>
                    <div className="text-right text-sm">
                      <div className="text-xs text-slate-400">Lifetime</div>
                      <div className="text-lg font-semibold text-slate-900">
                        ₹{(detail.totalSpent ?? 0).toLocaleString("en-IN")}
                      </div>
                      <div className="text-xs text-slate-500">
                        {detail.totalTrips} trip{detail.totalTrips === 1 ? "" : "s"} · last{" "}
                        {fmtDate(detail.lastTripDate)}
                      </div>
                    </div>
                  </div>

                  {actionError && (
                    <div className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
                      {actionError}
                    </div>
                  )}

                  {canWrite && (
                    <div className="mb-6 rounded-lg border border-slate-200 p-4">
                      <div className="mb-3">
                        <Label>Suggest offer</Label>
                        <input
                          value={suggestOffer}
                          onChange={(e) => setSuggestOffer(e.target.value)}
                          placeholder="e.g. Kashmir family tour package — Dec offer"
                          className="w-full rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
                        />
                      </div>
                      <div className="mb-3">
                        <Label>Remarketing tags</Label>
                        <div className="flex flex-wrap gap-1.5">
                          {ALLOWED_TAGS.map((tag) => (
                            <button
                              key={tag}
                              onClick={() => toggleTag(tag)}
                              className={`rounded-full px-2.5 py-1 text-xs font-medium ${
                                offerTags.includes(tag)
                                  ? "bg-slate-900 text-white"
                                  : "border border-slate-200 text-slate-500 hover:bg-slate-50"
                              }`}
                            >
                              {tag}
                            </button>
                          ))}
                        </div>
                      </div>
                      <div className="mb-3">
                        <Label>Notes</Label>
                        <textarea
                          value={notes}
                          onChange={(e) => setNotes(e.target.value)}
                          rows={2}
                          placeholder="Anything worth remembering…"
                          className="w-full rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
                        />
                      </div>
                      <div className="flex items-center justify-between">
                        <label className="flex cursor-pointer items-center gap-2 text-sm text-slate-700">
                          <input
                            type="checkbox"
                            checked={marketingOptIn}
                            onChange={(e) => setMarketingOptIn(e.target.checked)}
                            className="h-4 w-4 rounded border-slate-300"
                          />
                          Marketing opt-in
                        </label>
                        <button
                          onClick={save}
                          disabled={saving}
                          className="rounded-md bg-slate-900 px-4 py-1.5 text-xs font-semibold text-white hover:bg-slate-800 disabled:opacity-50"
                        >
                          {saving ? "Saving…" : "Save flags"}
                        </button>
                      </div>
                    </div>
                  )}

                  <div>
                    <div className="mb-2">
                      <Label>Trip history</Label>
                    </div>
                    {detail.tripHistory?.length ? (
                      <table className="w-full text-left text-sm">
                        <thead>
                          <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-400">
                            <th className="pb-2 pr-3 font-medium">Booking</th>
                            <th className="pb-2 pr-3 font-medium">Trip</th>
                            <th className="pb-2 pr-3 font-medium">Travel date</th>
                            <th className="pb-2 pr-3 font-medium">Status</th>
                            <th className="pb-2 pr-3 text-right font-medium">Net</th>
                            <th className="pb-2 text-right font-medium">Paid</th>
                          </tr>
                        </thead>
                        <tbody>
                          {detail.tripHistory.map((t) => (
                            <tr key={t.id} className="border-b border-slate-100">
                              <td className="py-2 pr-3 text-slate-700">{t.bookingRef}</td>
                              <td className="py-2 pr-3 text-slate-700">{t.tripName}</td>
                              <td className="py-2 pr-3 text-slate-500">{fmtDate(t.travelDate)}</td>
                              <td className="py-2 pr-3">
                                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-600">
                                  {t.status}
                                </span>
                              </td>
                              <td className="py-2 pr-3 text-right tabular-nums text-slate-700">
                                ₹{(t.netAmount ?? 0).toLocaleString("en-IN")}
                              </td>
                              <td className="py-2 text-right tabular-nums text-slate-700">
                                ₹{(t.appliedAmount ?? 0).toLocaleString("en-IN")}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ) : (
                      <p className="text-sm text-slate-400">No confirmed/completed trips yet.</p>
                    )}
                  </div>

                  {canWrite && detail.suggestOffer && (
                    <p className="mt-4 rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-700">
                      Suggested offer: {detail.suggestOffer}
                    </p>
                  )}
                </div>
              )}
            </div>
          </div>
        </main>
      </div>
    </>
  );
}

function Label({ children }: { children: ReactNode }) {
  return <div className="mb-1 text-xs font-medium uppercase tracking-wide text-slate-400">{children}</div>;
}

export default function CustomersPage() {
  return (
    <Protected>
      <CustomersView />
    </Protected>
  );
}
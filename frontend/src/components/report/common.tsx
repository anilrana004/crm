"use client";

import { useEffect, useState, type ReactNode } from "react";

/** Small fetch-state hook shared by every report section. */
export function useReport<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    fn()
      .then((d) => alive && setData(d))
      .catch((err) =>
        alive && setError(err instanceof Error ? err.message : "Request failed"),
      )
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { data, loading, error };
}

export function money(v: number | undefined | null): string {
  if (v === undefined || v === null) return "—";
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(v);
}

export function num(v: number | undefined | null): string {
  if (v === undefined || v === null) return "—";
  return new Intl.NumberFormat("en-IN").format(v);
}

export function pct(v: number | undefined | null): string {
  if (v === undefined || v === null || !Number.isFinite(v)) return "—";
  return `${v.toFixed(1)}%`;
}

export function fmtDate(d: string | undefined | null): string {
  if (!d) return "—";
  return new Date(d + (d.length === 10 ? "T00:00:00" : "")).toLocaleDateString("en-IN");
}

export function fmtDateTime(d: string | undefined | null): string {
  if (!d) return "—";
  return new Date(d).toLocaleString("en-IN");
}

const SCOPE_LABEL: Record<string, string> = {
  SELF: "Scoped to you",
  ALL: "All consultants",
  NONE: "No matching records",
};

export function ScopeBadge({ scope }: { scope?: string }) {
  if (!scope) return null;
  return (
    <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium uppercase tracking-wide text-slate-500">
      {SCOPE_LABEL[scope] ?? scope}
    </span>
  );
}

/**
 * Every Module 2 report carries an honest "how this number is derived" note
 * (cost basis, SLA basis, value basis). Rendering it keeps the estimate honest
 * instead of letting a figure like "revenue less base cost" read as a margin.
 */
export function BasisCallout({ title, note }: { title: string; note?: string }) {
  if (!note) return null;
  return (
    <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-900">
      <span className="font-semibold">{title}: </span>
      {note}
    </div>
  );
}

export function ErrorBox({ message }: { message: string }) {
  return (
    <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
      Could not load report — {message}
    </div>
  );
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white">
      <div className="border-b border-slate-100 px-4 py-3 text-sm font-semibold text-slate-800">
        {title}
      </div>
      <div className="px-4 py-3">{children}</div>
    </div>
  );
}
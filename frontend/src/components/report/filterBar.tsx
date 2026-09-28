"use client";

import { useState, type Dispatch, type SetStateAction } from "react";
import type { ReportFilterParams, SeasonValue } from "@/lib/api";

const SEASONS: { value: SeasonValue; label: string; months: string }[] = [
  { value: "SPRING", label: "Spring", months: "Mar–May" },
  { value: "MONSOON", label: "Monsoon", months: "Jun–Aug" },
  { value: "AUTUMN", label: "Autumn", months: "Sep–Nov" },
  { value: "WINTER", label: "Winter", months: "Dec–Feb" },
];

/** Stable filter the sections actually fetch with (changes only on Apply). */
export type AppliedFilter = ReportFilterParams;

const inputCls =
  "rounded-md border border-slate-300 px-2.5 py-1.5 text-sm text-slate-700 focus:border-slate-500 focus:outline-none";

/**
 * Shared filter control for the five report tabs. The draft only becomes the
 * applied filter when Apply is pressed, so a section does not refetch on
 * every keystroke.
 */
export function FilterBar({
  filter,
  onApply,
}: {
  filter: AppliedFilter;
  onApply: Dispatch<SetStateAction<AppliedFilter>>;
}) {
  const [draft, setDraft] = useState<ReportFilterParams>(filter);
  const dirty =
    draft.season !== filter.season ||
    draft.from !== filter.from ||
    draft.to !== filter.to ||
    draft.consultantId !== filter.consultantId ||
    draft.source !== filter.source;

  return (
    <div className="flex flex-wrap items-end gap-3">
      <label className="flex flex-col gap-1 text-xs text-slate-500">
        Season
        <select
          value={draft.season ?? ""}
          onChange={(e) =>
            setDraft((d) => ({ ...d, season: (e.target.value || undefined) as SeasonValue | undefined }))
          }
          className={inputCls}
        >
          <option value="">All</option>
          {SEASONS.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label} ({s.months})
            </option>
          ))}
        </select>
      </label>

      <label className="flex flex-col gap-1 text-xs text-slate-500">
        From
        <input
          type="date"
          value={draft.from ?? ""}
          onChange={(e) => setDraft((d) => ({ ...d, from: e.target.value || undefined }))}
          className={inputCls}
        />
      </label>

      <label className="flex flex-col gap-1 text-xs text-slate-500">
        To
        <input
          type="date"
          value={draft.to ?? ""}
          onChange={(e) => setDraft((d) => ({ ...d, to: e.target.value || undefined }))}
          className={inputCls}
        />
      </label>

      <label className="flex flex-col gap-1 text-xs text-slate-500">
        Consultant (user id)
        <input
          value={draft.consultantId ?? ""}
          onChange={(e) => setDraft((d) => ({ ...d, consultantId: e.target.value || undefined }))}
          placeholder="uuid…"
          className={inputCls}
        />
      </label>

      <label className="flex flex-col gap-1 text-xs text-slate-500">
        Lead source
        <input
          value={draft.source ?? ""}
          onChange={(e) => setDraft((d) => ({ ...d, source: e.target.value || undefined }))}
          placeholder="e.g. Web"
          className={inputCls}
        />
      </label>

      <button
        onClick={() => onApply({ ...draft })}
        disabled={!dirty}
        className="rounded-md bg-slate-900 px-3 py-1.5 text-sm font-semibold text-white hover:bg-slate-800 disabled:opacity-40"
      >
        Apply
      </button>
      <button
        onClick={() => {
          setDraft({});
          onApply({});
        }}
        className="rounded-md px-3 py-1.5 text-sm font-medium text-slate-500 hover:bg-slate-100"
      >
        Reset
      </button>
    </div>
  );
}
import type { LeadStatusPayload } from "@/lib/api";

/** Module-1 lost-reason taxonomy (mirrors the backend CHECK constraint). */
export const LOST_REASONS = [
  { value: "PRICE_TOO_HIGH", label: "Price too high" },
  { value: "DATES_UNAVAILABLE", label: "Dates unavailable" },
  { value: "CHOSE_COMPETITOR", label: "Chose a competitor" },
  { value: "WENT_SILENT", label: "Went silent / no response" },
  { value: "NOT_GENUINE", label: "Not a genuine enquiry" },
  { value: "POSTPONED", label: "Postponed the trip" },
] as const;

export const LOST_REASON_LABEL: Record<string, string> = Object.fromEntries(
  LOST_REASONS.map((r) => [r.value, r.label]),
);

export type LostReason = (typeof LOST_REASONS)[number]["value"];

/** Select a lost reason when moving a lead to LOST; null if cancelled. */
export async function pickLostReason(
  currentReason?: string | null,
): Promise<LeadStatusPayload["lostReason"] | null> {
  const options = LOST_REASONS.map(
    (r) => `${r.value}${r.value === currentReason ? " (current)" : ""}`,
  ).join(" / ");
  const answer = window.prompt(
    `Mark as LOST — choose a reason:\n${options}\n\nEnter one of the values above, or Cancel to abort.`,
    (currentReason as string) || "PRICE_TOO_HIGH",
  );
  if (!answer) return null;
  const value = answer.trim().toUpperCase() as LostReason;
  if (!LOST_REASONS.some((r) => r.value === value)) {
    window.alert("That is not a valid lost reason.");
    return pickLostReason(currentReason);
  }
  return value;
}
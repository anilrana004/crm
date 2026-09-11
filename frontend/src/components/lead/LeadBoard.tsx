"use client";

import { useMemo, useState } from "react";
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  closestCorners,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import type { Lead, LeadStatusPayload, Trip } from "@/lib/api";
import { pickLostReason } from "@/lib/lostReasons";

const COLUMNS = ["NEW", "INTERESTED", "QUOTATION_SENT", "BOOKING_CONFIRMED", "LOST"] as const;
const STATUS_STYLE: Record<string, string> = {
  NEW: "bg-blue-100 text-blue-700",
  INTERESTED: "bg-violet-100 text-violet-700",
  QUOTATION_SENT: "bg-cyan-100 text-cyan-700",
  BOOKING_CONFIRMED: "bg-emerald-100 text-emerald-700",
  LOST: "bg-rose-100 text-rose-700",
};
const HEAT_STYLE: Record<string, string> = {
  HOT: "bg-red-100 text-red-700",
  WARM: "bg-amber-100 text-amber-700",
  COLD: "bg-slate-100 text-slate-500",
};

type LeadBoardProps = {
  leads: Lead[];
  tripsById: Map<string, Trip>;
  onStatusChange: (id: string, payload: LeadStatusPayload) => Promise<void>;
  onOpen: (id: string) => void;
};

function LeadCard({ lead, tripsById, onClick }: { lead: Lead; tripsById: Map<string, Trip>; onClick: () => void }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: lead.id ?? "" });
  const trip = lead.tripId ? tripsById.get(lead.tripId) : undefined;
  const heat = lead.heat ?? "COLD";

  return (
    <div
      ref={setNodeRef}
      {...listeners}
      {...attributes}
      onClick={onClick}
      className={`mb-2 cursor-grab rounded-lg border border-slate-200 bg-white p-3 shadow-sm hover:shadow-md active:cursor-grabbing ${
        isDragging ? "opacity-40" : ""
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="text-sm font-semibold text-slate-900">{lead.customerName}</div>
        <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${HEAT_STYLE[heat] || ""}`}>{heat}</span>
      </div>
      <div className="mt-1 text-xs text-slate-500">{lead.destination || "No destination"}</div>
      <div className="mt-2 flex flex-wrap items-center gap-1.5">
        {trip && (
          <span className="rounded-md bg-slate-100 px-1.5 py-0.5 text-xs text-slate-600">{trip.name}</span>
        )}
        <span className="text-xs text-slate-400">{lead.source}</span>
      </div>
      <div className="mt-2 flex items-center justify-between text-xs text-slate-400">
        <span>{lead.budget != null ? `₹${Number(lead.budget).toLocaleString("en-IN")}` : "—"}</span>
        {lead.followUpDate && <span>↑ {lead.followUpDate}</span>}
      </div>
    </div>
  );
}

function Column({
  status,
  count,
  children,
}: {
  status: string;
  count: number;
  children: React.ReactNode;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: status });
  return (
    <div
      ref={setNodeRef}
      className={`flex min-h-[160px] w-64 shrink-0 flex-col rounded-xl border bg-slate-50 p-2 ${
        isOver ? "border-slate-500 ring-2 ring-slate-300" : "border-slate-200"
      }`}
    >
      <div className="mb-2 flex items-center justify-between px-1">
        <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${STATUS_STYLE[status] || ""}`}>
          {status}
        </span>
        <span className="text-xs text-slate-400">{count}</span>
      </div>
      <div className="flex-1">{children}</div>
    </div>
  );
}

export function LeadBoard({ leads, tripsById, onStatusChange, onOpen }: LeadBoardProps) {
  const [active, setActive] = useState<Lead | null>(null);
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
  );

  const byStatus = useMemo(() => {
    const groups: Record<string, Lead[]> = { NEW: [], INTERESTED: [], QUOTATION_SENT: [], BOOKING_CONFIRMED: [], LOST: [] };
    for (const lead of leads) {
      const status = lead.status ?? "NEW";
      (groups[status] ??= []).push(lead);
    }
    return groups;
  }, [leads]);

  async function handleDragStart(event: DragStartEvent) {
    setActive(leads.find((l) => l.id === event.active.id) ?? null);
  }

  async function handleDragEnd(event: DragEndEvent) {
    setActive(null);
    const { active, over } = event;
    const lead = leads.find((l) => l.id === active.id);
    if (!over || !lead || over.id === active.id) return;
    const target = String(over.id);
    if (target === lead.status) return;

    try {
      const payload: LeadStatusPayload = { status: target as LeadStatusPayload["status"] };
      if (target === "LOST") {
        const reason = await pickLostReason(lead.lostReason ?? null);
        if (!reason) return;
        payload.lostReason = reason;
        payload.note = "Moved to LOST on the board";
      }
      await onStatusChange(lead.id ?? "", payload);
    } catch (err) {
      window.alert(err instanceof Error ? err.message : "Could not move lead");
    }
  }

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCorners}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
      onDragCancel={() => setActive(null)}
    >
      <div className="flex gap-3 overflow-x-auto pb-2">
        {COLUMNS.map((status) => (
          <Column key={status} status={status} count={(byStatus[status] ?? []).length}>
            {(byStatus[status] ?? []).map((lead) => (
              <LeadCard
                key={lead.id}
                lead={lead}
                tripsById={tripsById}
                onClick={() => lead.id && onOpen(lead.id)}
              />
            ))}
          </Column>
        ))}
      </div>
      <DragOverlay>
        {active ? (
          <div className="w-64">
            <LeadCard lead={active} tripsById={tripsById} onClick={() => {}} />
          </div>
        ) : null}
      </DragOverlay>
    </DndContext>
  );
}
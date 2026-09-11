import { many, oneOrNull, q } from '../db.js';
import { notifyAllChannels, notifyRole } from './notifications.js';
import cfg from '../config.js';

// ---------------------------------------------------------------
// Task helpers
// ---------------------------------------------------------------

export async function createTask({
  leadId,
  assignedTo,
  type,
  title,
  description = null,
  dueAt,
}) {
  const row = await oneOrNull(
    `INSERT INTO tasks (lead_id, assigned_to, type, title, description, due_at)
     VALUES ($1,$2,$3,$4,$5,$6) RETURNING *`,
    [leadId, assignedTo, type, title, description, dueAt]
  );
  return row;
}

export async function cancelPendingTasks(leadId, exceptType = null) {
  const params = exceptType ? [leadId, exceptType] : [leadId];
  const filter = exceptType ? `AND type <> $2` : '';
  await q(
    `UPDATE tasks SET status='cancelled', updated_at=now()
     WHERE lead_id=$1 AND status='pending' ${filter}`,
    params
  );
}

function minutesFromNow(mins) {
  return new Date(Date.now() + mins * 60000);
}

function daysFromNow(days) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d;
}

// ---------------------------------------------------------------
// Booking ID: TOH-2026-0001
// ---------------------------------------------------------------

export async function nextBookingId() {
  const res = await q(`SELECT nextval('ops_booking_seq') AS n`);
  const n = res.rows[0].n;
  const year = new Date().getFullYear();
  return `TOH-${year}-${String(n).padStart(4, '0')}`;
}

// ---------------------------------------------------------------
// Lead lifecycle automation  (Modules 3, 11, 14)
// ---------------------------------------------------------------

export async function onLeadCreated(lead, client) {
  const db = client || { oneOrNull, q, many };
  // Module 3: "call within 5 minutes" task
  const task = await createTask({
    leadId: lead.id,
    assignedTo: lead.lead_owner_id,
    type: 'initial_call',
    title: `Call ${lead.customer_name} within 5 minutes`,
    description: `New lead from ${lead.source}. Mobile: ${lead.mobile_number}`,
    dueAt: minutesFromNow(5),
  });

  // Notify the sales exec (in-app + email)
  await notifyAllChannels({
    userId: lead.lead_owner_id,
    leadId: lead.id,
    taskId: task.id,
    subject: `New lead assigned: ${lead.customer_name}`,
    message: `New ${lead.source} lead "${lead.customer_name}" (${lead.mobile_number}) assigned to you. Call within 5 minutes.`,
  });
  return task;
}

export async function onLeadInterested(leadId, ownerId) {
  const lead = await oneOrNull(`SELECT * FROM leads WHERE id=$1`, [leadId]);
  if (!lead) return;
  const schedule = [
    { type: 'follow_up_1d', days: 1 },
    { type: 'follow_up_2d', days: 2 },
    { type: 'follow_up_5d', days: 5 },
    { type: 'follow_up_7d', days: 7 },
  ];
  for (const s of schedule) {
    const task = await createTask({
      leadId,
      assignedTo: ownerId,
      type: s.type,
      title: `Follow-up (+${s.days}d): ${lead.customer_name}`,
      description: `Lead marked Interested on ${new Date().toLocaleDateString()}. Package: ${lead.destination || '—'}`,
      dueAt: daysFromNow(s.days),
    });
    await notifyAllChannels({
      userId: ownerId,
      leadId,
      taskId: task.id,
      subject: `Follow-up scheduled: ${lead.customer_name}`,
      message: `Follow-up in ${s.days} day(s) for ${lead.customer_name}.`,
    });
  }
  await q(`UPDATE leads SET follow_up_date=$1, updated_at=now() WHERE id=$2`, [
    daysFromNow(7),
    leadId,
  ]);
}

export async function onLeadQuotationSent(leadId, ownerId) {
  const lead = await oneOrNull(`SELECT * FROM leads WHERE id=$1`, [leadId]);
  if (!lead) return;
  const task = await createTask({
    leadId,
    assignedTo: ownerId,
    type: 'quotation',
    title: `Follow up on quotation for ${lead.customer_name}`,
    description: `Quotation sent. Re-confirm interest and move towards booking.`,
    dueAt: daysFromNow(2),
  });
  await notifyAllChannels({
    userId: ownerId,
    leadId,
    taskId: task.id,
    subject: `Quotation follow-up: ${lead.customer_name}`,
    message: `Quotation sent to ${lead.customer_name}. Follow up in 2 days.`,
  });
  await q(`UPDATE leads SET follow_up_date=$1, updated_at=now() WHERE id=$2`, [
    daysFromNow(2),
    leadId,
  ]);
}

// Module 11: when booking confirmed -> create ops record + notify ops team
export async function onLeadBookingConfirmed(leadId) {
  const lead = await oneOrNull(`SELECT * FROM leads WHERE id=$1`, [leadId]);
  if (!lead) return;

  const existing = await oneOrNull(`SELECT id FROM operations WHERE lead_id=$1`, [leadId]);
  if (!existing) {
    const pkg = lead.package_id
      ? await oneOrNull(`SELECT name FROM packages WHERE id=$1`, [lead.package_id])
      : null;
    const bookingId = await nextBookingId();
    await q(
      `INSERT INTO operations
         (booking_id, lead_id, customer_name, package_name, pax, travel_date)
       VALUES ($1,$2,$3,$4,$5,$6)`,
      [
        bookingId,
        lead.id,
        lead.customer_name,
        pkg ? pkg.name : null,
        lead.num_persons || 1,
        lead.travel_date || new Date(),
      ]
    );
  }

  // Notify Ops team
  const opsRow = await oneOrNull(`SELECT booking_id FROM operations WHERE lead_id=$1`, [leadId]);
  await notifyRole('ops', {
    leadId,
    subject: `New booking for Ops: ${lead.customer_name}`,
    message: `Booking ${opsRow ? opsRow.booking_id : ''} confirmed for ${lead.customer_name}. Check package, pax and travel details in the Operations module.`,
  });
  await q(`UPDATE leads SET follow_up_date = NULL, updated_at = now() WHERE id=$1`, [leadId]);
}

// ---------------------------------------------------------------
// Module 14: no status update in 24h -> auto-create next follow-up
// ---------------------------------------------------------------

export async function autoFollowUpStaleLeads() {
  const stale = await many(
    `SELECT l.id, l.customer_name, l.lead_owner_id, l.status, l.updated_at
     FROM leads l
     WHERE l.status IN ('new','interested','quotation_sent')
       AND l.updated_at < now() - interval '24 hours'
       AND NOT EXISTS (
         SELECT 1 FROM tasks t
         WHERE t.lead_id = l.id
           AND t.type = 'no_activity'
           AND t.created_at >= now() - interval '24 hours'
       )
     LIMIT 50`
  );

  let created = 0;
  for (const lead of stale) {
    await createTask({
      leadId: lead.id,
      assignedTo: lead.lead_owner_id,
      type: 'no_activity',
      title: `No activity - follow up ${lead.customer_name}`,
      description: `Lead in "${lead.status}" state with no status update for 24h+.`,
      dueAt: new Date(),
    });
    await notifyAllChannels({
      userId: lead.lead_owner_id,
      leadId: lead.id,
      subject: `Follow-up needed: ${lead.customer_name}`,
      message: `No status update on ${lead.customer_name} in 24 hours. Follow up now.`,
    });
    await q(`UPDATE leads SET updated_at = now() WHERE id=$1`, [lead.id]);
    created++;
  }
  return created;
}

// ---------------------------------------------------------------
// Module 7: balance payment reminder (due in N days, 3 days before)
// ---------------------------------------------------------------

export async function ensurePaymentReminder() {
  const upcoming = await many(
    `SELECT p.id, p.lead_id, p.due_date, p.booking_amount, p.advance_amount, p.balance_amount,
            l.customer_name, l.lead_owner_id
     FROM payments p JOIN leads l ON l.id = p.lead_id
     WHERE p.payment_status IN ('pending','partial')
       AND p.due_date BETWEEN CURRENT_DATE AND CURRENT_DATE + 3
       AND p.balance_amount > 0
       AND NOT EXISTS (
         SELECT 1 FROM tasks t
         WHERE t.lead_id = p.lead_id AND t.type = 'payment_reminder' AND t.status = 'pending'
       )`
  );

  let created = 0;
  for (const p of upcoming) {
    const days = Math.max(1, Math.round((new Date(p.due_date) - new Date()) / 86400000));
    const task = await createTask({
      leadId: p.lead_id,
      assignedTo: p.lead_owner_id,
      type: 'payment_reminder',
      title: `Balance payment due in ${days} day(s) — ${p.customer_name}`,
      description: `Balance ₹${Number(p.balance_amount).toLocaleString('en-IN')} due on ${p.due_date.toISOString().slice(0, 10)}.`,
      dueAt: daysFromNow(0),
    });
    await notifyAllChannels({
      userId: p.lead_owner_id,
      leadId: p.lead_id,
      taskId: task.id,
      subject: `Payment reminder: ${p.customer_name}`,
      message: `Balance payment of ₹${Number(p.balance_amount).toLocaleString('en-IN')} is due in ${days} day(s) (${p.due_date.toISOString().slice(0, 10)}).`,
    });
    created++;
  }
  return created;
}

// ---------------------------------------------------------------
// Housekeeping: mark overdue pending tasks
// ---------------------------------------------------------------

export async function markOverdueTasks() {
  const res = await q(
    `UPDATE tasks SET status='overdue', updated_at=now()
     WHERE status='pending' AND due_at < now() - interval '1 hour'
     RETURNING id`
  );
  return res.rowCount || 0;
}

const company = `${cfg.companyName} Team`;

export { company };
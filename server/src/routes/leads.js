import { many, oneOrNull, q, tx, isUuid } from '../db.js';
import {
  onLeadCreated,
  onLeadInterested,
  onLeadQuotationSent,
  onLeadBookingConfirmed,
  cancelPendingTasks,
} from '../services/automation.js';

const LEAD_SELECT = `
  SELECT l.*, u.full_name AS owner_name, p.name AS package_name
  FROM leads l
  JOIN users u ON u.id = l.lead_owner_id
  LEFT JOIN packages p ON p.id = l.package_id
`;

export default async function leadRoutes(app) {
  // ---------- List with filters (Module 1) ----------
  app.get('/', { preHandler: [app.requireAuth] }, async (request) => {
    const { status, owner, source, from, to, q: search } = request.query || {};
    const where = [];
    const params = [];

    if (status) { params.push(status); where.push(`l.status = $${params.length}`); }
    if (owner) { params.push(owner); where.push(`l.lead_owner_id = $${params.length}`); }
    if (source) { params.push(source); where.push(`l.source = $${params.length}`); }
    if (from) { params.push(from); where.push(`l.created_at::date >= $${params.length}`); }
    if (to) { params.push(to); where.push(`l.created_at::date <= $${params.length}`); }
    if (search) {
      params.push(`%${search}%`);
      where.push(`(l.customer_name ILIKE $${params.length} OR l.mobile_number ILIKE $${params.length})`);
    }

    const whereSql = where.length ? `WHERE ${where.join(' AND ')}` : '';
    const rows = await many(
      `${LEAD_SELECT} ${whereSql} ORDER BY l.updated_at DESC LIMIT 200`,
      params
    );
    const counts = await q(`SELECT status, count(*) FROM leads GROUP BY status`);
    return { leads: rows, counts: counts.rows };
  });

  // ---------- Detail (leads + tasks + payments + call logs + ops) ----------
app.get('/:id', { preHandler: [app.requireAuth] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Lead not found' });
    const lead = await oneOrNull(`${LEAD_SELECT} WHERE l.id = $1`, [request.params.id]);
    if (!lead) return reply.code(404).send({ error: 'Lead not found' });

    const [tasks, payments, callLogs, ops] = await Promise.all([
      many(`SELECT * FROM tasks WHERE lead_id=$1 ORDER BY due_at`, [lead.id]),
      many(`SELECT * FROM payments WHERE lead_id=$1 ORDER BY created_at`, [lead.id]),
      many(`SELECT cl.*, u.full_name AS user_name FROM call_logs cl LEFT JOIN users u ON u.id=cl.user_id WHERE cl.lead_id=$1 ORDER BY cl.called_at DESC`, [lead.id]),
      oneOrNull(`SELECT * FROM operations WHERE lead_id=$1`, [lead.id]),
    ]);
    return { lead, tasks, payments, callLogs, operations: ops };
  });

  // ---------- Create (Module 1 + 3 + 14) ----------
  app.post('/', { preHandler: [app.requireAuth] }, async (request, reply) => {
    const b = request.body || {};
    const required = ['customer_name', 'mobile_number', 'source'];
    for (const f of required) if (!b[f]) return reply.code(400).send({ error: `Missing ${f}` });

    const ownerId = b.lead_owner_id || request.user.sub;
    const createdLead = await tx(async (client) => {
      const row = await client.query(
        `INSERT INTO leads
           (customer_name, mobile_number, whatsapp_number, email, source, destination,
            package_id, travel_date, num_persons, budget, lead_owner_id, status, follow_up_date, remarks)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
         RETURNING *`,
        [
          b.customer_name, b.mobile_number, b.whatsapp_number || b.mobile_number,
          b.email || null, b.source, b.destination || null, b.package_id || null,
          b.travel_date || null, b.num_persons || 1, b.budget || null,
          ownerId, b.status || 'new', b.follow_up_date || null, b.remarks || null,
        ]
      );
      return row.rows[0];
    });

    // Module 3 automation: 5-minute call task + notify exec
    await onLeadCreated(createdLead);
    return reply.code(201).send({ lead: createdLead });
  });

  // ---------- Update ----------
app.patch('/:id', { preHandler: [app.requireAuth] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Lead not found' });
    const lead = await oneOrNull(`SELECT * FROM leads WHERE id=$1`, [request.params.id]);
    if (!lead) return reply.code(404).send({ error: 'Lead not found' });

    const b = request.body || {};
    const fields = [
      'customer_name', 'mobile_number', 'whatsapp_number', 'email', 'source',
      'destination', 'package_id', 'travel_date', 'num_persons', 'budget',
      'lead_owner_id', 'remarks', 'follow_up_date',
    ];
    const set = [];
    const params = [];
    for (const f of fields) {
      if (b[f] !== undefined) { params.push(b[f]); set.push(`${f} = $${params.length}`); }
    }
    // status handled separately so automation can react to transitions
    if (b.status !== undefined && b.status !== lead.status) {
      params.push(b.status);
      set.push(`status = $${params.length}`);
    }
    if (!set.length) return reply.code(400).send({ error: 'No fields to update' });

    params.push(lead.id);
    const updated = await oneOrNull(
      `UPDATE leads SET ${set.join(', ')}, updated_at = now() WHERE id = $${params.length} RETURNING *`,
      params
    );

    if (b.status !== undefined && b.status !== lead.status) {
      if (b.status === 'interested') {
        await onLeadInterested(lead.id, updated.lead_owner_id);
      } else if (b.status === 'quotation_sent') {
        await onLeadQuotationSent(lead.id, updated.lead_owner_id);
      } else if (b.status === 'booking_confirmed') {
        await onLeadBookingConfirmed(lead.id);
      } else if (b.status === 'lost') {
        await cancelPendingTasks(lead.id);
      }
    }
    return { lead: updated };
  });

  // ---------- Status transition endpoint (explicit, drives automation) ----------
app.post('/:id/status', { preHandler: [app.requireAuth] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Lead not found' });
    const { status } = request.body || {};
    if (!status) return reply.code(400).send({ error: 'status required' });
    const updated = await updateLeadStatus(app, request.params.id, status);
    if (!updated) return reply.code(404).send({ error: 'Lead not found' });
    return { lead: updated };
  });

  // ---------- Call log (Module 9) ----------
app.post('/:id/call-log', { preHandler: [app.requireAuth] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Lead not found' });
    const { call_type, notes, outcome, duration_secs } = request.body || {};
    const lead = await oneOrNull(`SELECT id FROM leads WHERE id=$1`, [request.params.id]);
    if (!lead) return reply.code(404).send({ error: 'Lead not found' });
    const row = await oneOrNull(
      `INSERT INTO call_logs (lead_id, user_id, call_type, notes, outcome, duration_secs)
       VALUES ($1,$2,$3,$4,$5,$6) RETURNING *`,
      [lead.id, request.user.sub, call_type || 'follow_up', notes || null, outcome || null, duration_secs || null]
    );
    await q(`UPDATE leads SET updated_at = now() WHERE id=$1`, [lead.id]);
    return reply.code(201).send({ callLog: row });
  });

  // ---------- Delete ----------
app.delete('/:id', { preHandler: [app.requireAuth, app.requireRole('admin', 'manager')] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Lead not found' });
    const res = await q(`DELETE FROM leads WHERE id=$1 RETURNING id`, [request.params.id]);
    if (!res.rowCount) return reply.code(404).send({ error: 'Lead not found' });
    return { ok: true };
  });
}

async function updateLeadStatus(app, leadId, status) {
  const lead = await oneOrNull(`SELECT * FROM leads WHERE id=$1`, [leadId]);
  if (!lead) return null;
  if (lead.status === status) return lead;

  const updated = await oneOrNull(
    `UPDATE leads SET status=$1, updated_at=now() WHERE id=$2 RETURNING *`,
    [status, leadId]
  );
  if (status === 'interested') await onLeadInterested(leadId, updated.lead_owner_id);
  else if (status === 'quotation_sent') await onLeadQuotationSent(leadId, updated.lead_owner_id);
  else if (status === 'booking_confirmed') await onLeadBookingConfirmed(leadId);
  else if (status === 'lost') await cancelPendingTasks(leadId);
  return updated;
}

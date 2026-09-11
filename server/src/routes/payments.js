import { many, oneOrNull, q, isUuid } from '../db.js';

export default async function paymentRoutes(app) {
  // ---------- List ----------
  app.get('/', { preHandler: [app.requireAuth] }, async (request) => {
    const { lead_id, status, due_before } = request.query || {};
    const where = [];
    const params = [];
    if (lead_id) { params.push(lead_id); where.push(`p.lead_id = $${params.length}`); }
    if (status) { params.push(status); where.push(`p.payment_status = $${params.length}`); }
    if (due_before) { params.push(due_before); where.push(`p.due_date <= $${params.length}`); }

    const rows = await many(
      `SELECT p.*, l.customer_name, l.lead_owner_id, u.full_name AS owner_name, l.mobile_number
       FROM payments p
       JOIN leads l ON l.id = p.lead_id
       LEFT JOIN users u ON u.id = l.lead_owner_id
       ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
       ORDER BY p.due_date ASC`,
      params
    );
    return { payments: rows };
  });

  // ---------- Create (manual entry) ----------
  app.post('/', { preHandler: [app.requireAuth, app.requireRole('admin', 'manager', 'sales')] }, async (request, reply) => {
    const b = request.body || {};
    if (!b.lead_id || b.booking_amount == null || !b.due_date)
      return reply.code(400).send({ error: 'lead_id, booking_amount, due_date required' });

    const lead = await oneOrNull(`SELECT id FROM leads WHERE id=$1`, [b.lead_id]);
    if (!lead) return reply.code(404).send({ error: 'Lead not found' });

    if (b.booking_amount < 0) return reply.code(400).send({ error: 'Invalid amount' });
    const balance = b.booking_amount - (b.advance_amount || 0);
    const advanceStatus =
      (b.advance_amount || 0) >= b.booking_amount ? 'received'
        : (b.advance_amount || 0) > 0 ? 'received' : 'pending';

const status =
      (b.advance_amount || 0) >= b.booking_amount ? 'completed'
        : balance === 0 ? 'completed'
        : (b.advance_amount || 0) > 0 ? 'partial' : 'pending';

    const receivedAt = status === 'completed' ? new Date() : null;
    const row = await oneOrNull(
      `INSERT INTO payments
         (lead_id, booking_amount, advance_amount, advance_status, due_date, payment_status, notes, created_by, received_at)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
       RETURNING *`,
      [b.lead_id, b.booking_amount, b.advance_amount || 0, advanceStatus, b.due_date, status, b.notes || null, request.user.sub, receivedAt]
    );
    return reply.code(201).send({ payment: row });
  });

  // ---------- Update (record advance receipt, balance payment, etc.) ----------
app.patch('/:id', { preHandler: [app.requireAuth, app.requireRole('admin', 'manager', 'sales')] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Payment not found' });
    const payment = await oneOrNull(`SELECT * FROM payments WHERE id=$1`, [request.params.id]);
    if (!payment) return reply.code(404).send({ error: 'Payment not found' });
    const b = request.body || {};

    let advance = payment.advance_amount;
    let status = payment.payment_status;
    let receivedAt = payment.received_at;

    if (b.advance_amount !== undefined) advance = Number(b.advance_amount);
    const balance = payment.booking_amount - advance;

    if (b.payment_status === 'completed' || balance <= 0) {
      status = 'completed';
      receivedAt = receivedAt || new Date();
    } else if (advance > 0) {
      status = 'partial';
    } else {
      status = 'pending';
    }
    if (b.payment_status === 'overdue') status = 'overdue';

    const advanceStatus = balance <= 0 ? 'received' : advance > 0 ? 'received' : 'pending';

    const updated = await oneOrNull(
      `UPDATE payments SET advance_amount=$1, advance_status=$2, payment_status=$3,
         received_at=$4, notes=COALESCE($5, notes), updated_at=now()
       WHERE id=$6 RETURNING *`,
      [advance, advanceStatus, status, receivedAt, b.notes ?? null, payment.id]
    );
    return { payment: updated };
  });

  // ---------- Delete ----------
app.delete('/:id', { preHandler: [app.requireAuth, app.requireRole('admin')] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Payment not found' });
    const res = await q(`DELETE FROM payments WHERE id=$1 RETURNING id`, [request.params.id]);
    if (!res.rowCount) return reply.code(404).send({ error: 'Payment not found' });
    return { ok: true };
  });
}

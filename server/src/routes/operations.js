import { many, oneOrNull, q, isUuid } from '../db.js';

export default async function operationRoutes(app) {
  // ---------- List with filters (Ops dashboard) ----------
  app.get('/', { preHandler: [app.requireAuth] }, async (request) => {
    const { hotel, transport, travel_from, travel_to, q: search } = request.query || {};
    const where = [];
    const params = [];

    if (hotel) { params.push(hotel); where.push(`o.hotel_status = $${params.length}`); }
    if (transport) { params.push(transport); where.push(`o.transport = $${params.length}`); }
    if (travel_from) { params.push(travel_from); where.push(`o.travel_date >= $${params.length}`); }
    if (travel_to) { params.push(travel_to); where.push(`o.travel_date <= $${params.length}`); }
    if (search) {
      params.push(`%${search}%`);
      where.push(`(o.booking_id ILIKE $${params.length} OR o.customer_name ILIKE $${params.length})`);
    }

    const rows = await many(
      `SELECT o.*, d.full_name AS driver_name, d.phone AS driver_phone, l.status AS lead_status, l.mobile_number
       FROM operations o
       LEFT JOIN drivers d ON d.id = o.driver_assigned_id
       LEFT JOIN leads l ON l.id = o.lead_id
       ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
       ORDER BY o.travel_date ASC`,
      params
    );
    return { operations: rows };
  });

  // ---------- Detail ----------
app.get('/:id', { preHandler: [app.requireAuth] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Operation not found' });
    const op = await oneOrNull(
      `SELECT o.*, d.full_name AS driver_name, d.vehicle_type, d.vehicle_number
       FROM operations o LEFT JOIN drivers d ON d.id = o.driver_assigned_id
       WHERE o.id = $1`,
      [request.params.id]
    );
    if (!op) return reply.code(404).send({ error: 'Operation not found' });
    return { operations: op };
  });

  // ---------- Update ops fields ----------
app.patch('/:id', { preHandler: [app.requireAuth, app.requireRole('admin', 'manager', 'ops')] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Operation not found' });
    const op = await oneOrNull(`SELECT id FROM operations WHERE id=$1`, [request.params.id]);
    if (!op) return reply.code(404).send({ error: 'Operation not found' });
    const b = request.body || {};

    const fields = ['hotel_status', 'transport', 'driver_assigned_id', 'advance_status', 'balance_status', 'ops_notes', 'travel_date', 'pax', 'package_name'];
    const set = [];
    const params = [];
    for (const f of fields) {
      if (b[f] !== undefined) { params.push(b[f]); set.push(`${f} = $${params.length}`); }
    }
    if (!set.length) return reply.code(400).send({ error: 'Nothing to update' });

    params.push(op.id);
    const updated = await oneOrNull(
      `UPDATE operations SET ${set.join(', ')}, updated_at = now() WHERE id = $${params.length} RETURNING *`,
      params
    );
    return { operations: updated };
  });

  // ---------- Drivers list (for assignment dropdown) ----------
  app.get('/meta/drivers', { preHandler: [app.requireAuth] }, async () => {
    const drivers = await many(`SELECT * FROM drivers WHERE is_active = true ORDER BY full_name`);
    return { drivers };
  });
}

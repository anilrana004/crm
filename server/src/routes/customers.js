import { many, oneOrNull, q, isUuid } from '../db.js';

export default async function customerRoutes(app) {
  // ---------- List ----------
  app.get('/', { preHandler: [app.requireAuth] }, async (request) => {
    const { suggest, q: search } = request.query || {};
    const where = [];
    const params = [];
    if (suggest === 'true') { where.push(`c.suggest_offer = true`); }
    if (search) {
      params.push(`%${search}%`);
      where.push(`(c.full_name ILIKE $${params.length} OR c.mobile_number ILIKE $${params.length})`);
    }

    const rows = await many(
      `SELECT c.*, COALESCE((SELECT array_agg(t.package_name) FROM customer_trips t WHERE t.customer_id = c.id), '{}') AS trips_packages
       FROM customers c
       ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
       ORDER BY c.updated_at DESC LIMIT 200`,
      params
    );
    return { customers: rows };
  });

  // ---------- Detail with full trip history ----------
app.get('/:id', { preHandler: [app.requireAuth] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Customer not found' });
    const customer = await oneOrNull(`SELECT * FROM customers WHERE id=$1`, [request.params.id]);
    if (!customer) return reply.code(404).send({ error: 'Customer not found' });
    const trips = await many(`SELECT * FROM customer_trips WHERE customer_id=$1 ORDER BY travel_date DESC`, [customer.id]);
    return { customer, trips };
  });

  // ---------- Update (suggest_offer flag, tags, notes) ----------
app.patch('/:id', { preHandler: [app.requireAuth] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Customer not found' });
    const customer = await oneOrNull(`SELECT id FROM customers WHERE id=$1`, [request.params.id]);
    if (!customer) return reply.code(404).send({ error: 'Customer not found' });
    const b = request.body || {};
    const fields = ['full_name', 'mobile_number', 'whatsapp_number', 'email', 'suggest_offer', 'offer_tags', 'notes'];
    const set = [];
    const params = [];
    for (const f of fields) {
      if (b[f] !== undefined) { params.push(b[f]); set.push(`${f} = $${params.length}`); }
    }
    if (!set.length) return reply.code(400).send({ error: 'Nothing to update' });
    params.push(customer.id);
    const updated = await oneOrNull(
      `UPDATE customers SET ${set.join(', ')}, updated_at = now() WHERE id = $${params.length} RETURNING *`,
      params
    );
    return { customer: updated };
  });

  // ---------- Manual customer creation ----------
  app.post('/', { preHandler: [app.requireAuth] }, async (request, reply) => {
    const b = request.body || {};
    if (!b.full_name || !b.mobile_number)
      return reply.code(400).send({ error: 'full_name and mobile_number required' });
    try {
      const row = await oneOrNull(
        `INSERT INTO customers (full_name, mobile_number, whatsapp_number, email, suggest_offer, offer_tags, notes)
         VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING *`,
        [b.full_name, b.mobile_number, b.whatsapp_number || b.mobile_number, b.email || null,
         b.suggest_offer === true, b.offer_tags || [], b.notes || null]
      );
      return reply.code(201).send({ customer: row });
    } catch (err) {
      if (err.code === '23505') return reply.code(409).send({ error: 'Customer already exists' });
      throw err;
    }
  });
}

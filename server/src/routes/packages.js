import { many, oneOrNull, q } from '../db.js';

export default async function packageRoutes(app) {
  app.get('/', { preHandler: [app.requireAuth] }, async () => {
    const packages = await many(`SELECT * FROM packages ORDER BY name`);
    return { packages };
  });

  app.get('/:id', { preHandler: [app.requireAuth] }, async (request, reply) => {
    const pkg = await oneOrNull(`SELECT * FROM packages WHERE id=$1`, [request.params.id]);
    if (!pkg) return reply.code(404).send({ error: 'Package not found' });
    return { package: pkg };
  });

  app.post('/', { preHandler: [app.requireAuth, app.requireRole('admin', 'manager')] }, async (request, reply) => {
    const b = request.body || {};
    if (!b.name || b.cost == null || !b.duration_days)
      return reply.code(400).send({ error: 'name, cost, duration_days required' });
    const slug = b.slug || b.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
    const pkg = await oneOrNull(
      `INSERT INTO packages (name, slug, cost, duration_days, itinerary, inclusions, exclusions, departure_date, is_active)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING *`,
      [b.name, slug, b.cost, b.duration_days, b.itinerary || null, b.inclusions || null, b.exclusions || null, b.departure_date || null, b.is_active !== false]
    );
    return reply.code(201).send({ package: pkg });
  });

  app.put('/:id', { preHandler: [app.requireAuth, app.requireRole('admin', 'manager')] }, async (request, reply) => {
    const pkg = await oneOrNull(`SELECT * FROM packages WHERE id=$1`, [request.params.id]);
    if (!pkg) return reply.code(404).send({ error: 'Package not found' });
    const b = request.body || {};
    const updated = await oneOrNull(
      `UPDATE packages SET name=$1, cost=$2, duration_days=$3, itinerary=$4, inclusions=$5, exclusions=$6,
        departure_date=$7, is_active=$8, updated_at=now() WHERE id=$9 RETURNING *`,
      [
        b.name ?? pkg.name, b.cost ?? pkg.cost, b.duration_days ?? pkg.duration_days,
        b.itinerary ?? pkg.itinerary, b.inclusions ?? pkg.inclusions, b.exclusions ?? pkg.exclusions,
        b.departure_date !== undefined ? b.departure_date : pkg.departure_date,
        b.is_active !== undefined ? b.is_active : pkg.is_active, pkg.id,
      ]
    );
    return { package: updated };
  });

  app.delete('/:id', { preHandler: [app.requireAuth, app.requireRole('admin', 'manager')] }, async (request, reply) => {
    const res = await q(`DELETE FROM packages WHERE id=$1 RETURNING id`, [request.params.id]);
    if (!res.rowCount) return reply.code(404).send({ error: 'Package not found' });
    return { ok: true };
  });
}
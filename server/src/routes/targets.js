import { many, oneOrNull, q } from '../db.js';
import { firstOfMonth } from '../services/roundRobin.js';

export default async function targetRoutes(app) {
  const canManage = [app.requireAuth, app.requireRole('admin', 'manager')];

  // ---------- List targets for a month (with achievement progress) ----------
  app.get('/', { preHandler: [app.requireAuth] }, async (request) => {
    const month = request.query.month || firstOfMonth();
    const from = new Date(month);
    const to = new Date(new Date(from).setMonth(from.getMonth() + 1));

    const rows = await many(
      `SELECT t.*, u.full_name,
         (SELECT count(*) FROM leads l WHERE l.lead_owner_id = t.user_id AND l.status='booking_confirmed') AS bookings_achieved
       FROM sales_targets t
       LEFT JOIN users u ON u.id = t.user_id
       WHERE t.month = $1
       ORDER BY t.user_id IS NULL, u.full_name`,
      [month]
    );
    const overall = await oneOrNull(
      `SELECT
         COALESCE((SELECT sum(target_bookings) FROM sales_targets WHERE month=$1 AND user_id IS NULL), 0) AS target,
         (SELECT count(*) FROM leads WHERE status='booking_confirmed') AS achieved`,
      [month]
    );
    return {
      month,
      targets: rows,
      overall: overall
        ? { target: Number(overall.target || 0), achieved: Number(overall.achieved || 0) }
        : { target: 0, achieved: 0 },
    };
  });

  // ---------- Set / update a target for a sales user or company-wide ----------
  app.post('/', { preHandler: canManage }, async (request, reply) => {
    const b = request.body || {};
    const month = b.month || firstOfMonth();
    if (b.target_bookings == null && b.target_revenue == null)
      return reply.code(400).send({ error: 'target_bookings and/or target_revenue required' });

    const row = await oneOrNull(
      `INSERT INTO sales_targets (user_id, month, target_bookings, target_revenue, created_by)
       VALUES ($1,$2,$3,$4,$5)
       ON CONFLICT (user_id, month)
       DO UPDATE SET target_bookings = EXCLUDED.target_bookings,
                     target_revenue  = EXCLUDED.target_revenue,
                     updated_at = now()
       RETURNING *`,
      [b.user_id || null, month, b.target_bookings || 0, b.target_revenue || 0, request.user.sub]
    );
    return reply.code(201).send({ target: row });
  });

  // ---------- Delete a target ----------
  app.delete('/:id', { preHandler: canManage }, async (request, reply) => {
    const res = await q(`DELETE FROM sales_targets WHERE id=$1 RETURNING id`, [request.params.id]);
    if (!res.rowCount) return reply.code(404).send({ error: 'Target not found' });
    return { ok: true };
  });

  /**
   * Module 6 computed view: achieved / remaining / achievement% — overall and per employee.
   */
  app.get('/progress', { preHandler: [app.requireAuth] }, async (request) => {
    const month = request.query.month || firstOfMonth();
    const from = new Date(month);
    const to = new Date(new Date(from).setMonth(from.getMonth() + 1));

    const targets = await many(
      `SELECT t.*, u.full_name FROM sales_targets t
       LEFT JOIN users u ON u.id = t.user_id
       WHERE t.month = $1`,
      [month]
    );
    const bookings = await many(
      `SELECT lead_owner_id, count(*) AS cnt
       FROM leads WHERE status='booking_confirmed'
       GROUP BY lead_owner_id`,
      []
    );
    const totalBookingsRow = await oneOrNull(
      `SELECT count(*)::int AS cnt FROM leads WHERE status='booking_confirmed'`
    );
    const bookingMap = new Map(bookings.map((r) => [r.lead_owner_id, Number(r.cnt)]));

    let companyTarget = 0;
    const employees = [];
    for (const t of targets) {
      const achieved = bookingMap.get(t.user_id) || 0;
      const pct = t.target_bookings > 0 ? Math.round((achieved / t.target_bookings) * 100) : 0;
      if (t.user_id) {
        employees.push({
          user_id: t.user_id,
          full_name: t.full_name || '',
          target_bookings: Number(t.target_bookings),
          achieved,
          remaining: Math.max(0, Number(t.target_bookings) - achieved),
          pct,
          target_revenue: Number(t.target_revenue || 0),
        });
      } else {
        companyTarget += Number(t.target_bookings);
      }
    }
    const companyAchieved = Number(totalBookingsRow?.cnt || 0);
    const overall = {
      target_bookings: companyTarget,
      achieved: companyAchieved,
      remaining: Math.max(0, companyTarget - companyAchieved),
      pct: companyTarget > 0 ? Math.round((companyAchieved / companyTarget) * 100) : 0,
    };
    return { month, overall, employees };
  });
}
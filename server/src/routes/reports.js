import { many } from '../db.js';
import { firstOfMonth } from '../services/roundRobin.js';

export default async function reportRoutes(app) {
  /**
   * Module 12: source-wise leads → bookings conversion, with date filter.
   */
  app.get('/marketing', { preHandler: [app.requireAuth] }, async (request) => {
    const { from, to } = request.query || {};
    const params = [];
    let dateFilter = '';
    if (from) { params.push(from); dateFilter += ` AND l.created_at::date >= $${params.length}`; }
    if (to) { params.push(to); dateFilter += ` AND l.created_at::date <= $${params.length}`; }

    const rows = await many(
      `SELECT l.source,
         count(*) AS leads,
         count(*) FILTER (WHERE l.status='booking_confirmed') AS bookings,
         count(*) FILTER (WHERE l.status='lost') AS lost,
         COALESCE(sum(p.booking_amount) FILTER (WHERE l.status='booking_confirmed' AND p.payment_status IN ('completed','partial')), 0) AS revenue
       FROM leads l
       LEFT JOIN payments p ON p.lead_id = l.id
       WHERE 1=1 ${dateFilter}
       GROUP BY l.source
       ORDER BY leads DESC`,
      params
    );
    const data = rows.map((r) => ({
      source: r.source,
      leads: Number(r.leads),
      bookings: Number(r.bookings),
      lost: Number(r.lost),
      revenue: Number(r.revenue),
      conversion: Number(r.leads) > 0 ? Math.round((Number(r.bookings) / Number(r.leads)) * 10000) / 100 : 0,
    }));
    const totals = {
      leads: data.reduce((a, r) => a + r.leads, 0),
      bookings: data.reduce((a, r) => a + r.bookings, 0),
      revenue: data.reduce((a, r) => a + r.revenue, 0),
    };
    totals.conversion = totals.leads > 0 ? Math.round((totals.bookings / totals.leads) * 10000) / 100 : 0;
    return { from: from || null, to: to || null, data, totals };
  });
}
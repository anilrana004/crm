import { many, q } from '../db.js';
import { firstOfMonth } from '../services/roundRobin.js';

export default async function dashboardRoutes(app) {
  /**
   * Module 4: sales dashboard cards.
   * period = 'today' | 'month'
   */
  app.get('/summary', { preHandler: [app.requireAuth] }, async (request) => {
    const period = request.query.period || 'month';
    const from = period === 'today' ? new Date().toISOString().slice(0, 10) : firstOfMonth();
    const row = await q(
      `SELECT
         count(*) AS total_leads,
         count(*) FILTER (WHERE l.created_at::date >= $1 AND l.status='new')                   AS new_leads,
         count(*) FILTER (WHERE l.follow_up_date IS NOT NULL AND l.follow_up_date <= now())    AS follow_up_due,
         count(*) FILTER (WHERE l.status='interested')                                         AS interested,
         count(*) FILTER (WHERE l.status='quotation_sent')                                     AS quotation_sent,
         count(*) FILTER (WHERE l.status='booking_confirmed')                                  AS booking_confirmed,
         count(*) FILTER (WHERE l.status='lost')                                               AS lost,
         count(*) FILTER (WHERE l.created_at::date >= $1 AND l.status='booking_confirmed')     AS new_bookings,
         COALESCE(sum(p.booking_amount) FILTER (WHERE p.payment_status IN ('completed','partial')), 0) AS revenue
       FROM leads l
       LEFT JOIN payments p ON p.lead_id = l.id`,
      [from]
    );
    const s = row.rows[0];
    const summary = {
      total_leads: Number(s.total_leads || 0),
      new_leads: Number(s.new_leads || 0),
      follow_up_due: Number(s.follow_up_due || 0),
      interested: Number(s.interested || 0),
      quotation_sent: Number(s.quotation_sent || 0),
      booking_confirmed: Number(s.booking_confirmed || 0),
      lost: Number(s.lost || 0),
      new_bookings: Number(s.new_bookings || 0),
      revenue: Number(s.revenue || 0),
    };
    return { period, from, ...summary };
  });

  /**
   * Module 5: per-employee performance. Manager sees all employees side by side.
   */
  app.get('/performance', { preHandler: [app.requireAuth] }, async (request) => {
    const month = request.query.month || firstOfMonth();
    const from = new Date(month);
    const to = new Date(new Date(month).setMonth(from.getMonth() + 1));

    const rows = await many(
      `SELECT
         u.id AS user_id, u.full_name, u.email,
         count(DISTINCT l.id) FILTER (WHERE l.created_at >= $1 AND l.created_at < $2)                          AS leads_assigned,
         count(DISTINCT t.id) FILTER (WHERE t.status='completed' AND t.completed_at >= $1 AND t.completed_at < $2) AS followups_completed,
         count(DISTINCT l.id) FILTER (WHERE l.status='booking_confirmed')                                      AS bookings_closed,
         count(DISTINCT p.id) AS payments_count,
         COALESCE(sum(p.booking_amount) FILTER (WHERE p.payment_status IN ('completed','partial')), 0)         AS revenue
       FROM users u
       LEFT JOIN leads l ON l.lead_owner_id = u.id
       LEFT JOIN tasks t ON t.assigned_to = u.id
       LEFT JOIN payments p ON p.lead_id = l.id
       WHERE u.role='sales'
       GROUP BY u.id, u.full_name, u.email
       ORDER BY u.full_name`,
      [from, to]
    );
    const totals = {
      leads_assigned: rows.reduce((a, r) => a + Number(r.leads_assigned || 0), 0),
      followups_completed: rows.reduce((a, r) => a + Number(r.followups_completed || 0), 0),
      bookings_closed: rows.reduce((a, r) => a + Number(r.bookings_closed || 0), 0),
      revenue: rows.reduce((a, r) => a + Number(r.revenue || 0), 0),
    };
    return { month, employees: rows, totals };
  });
}
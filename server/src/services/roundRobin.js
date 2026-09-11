import { many, oneOrNull, q } from '../db.js';

/**
 * Round-robin assignment: pick the least-recently-assigned active sales user.
 * Updates assignment_state so distribution is balanced across runs.
 */
export async function pickNextSalesUser() {
  const month = firstOfMonth();
  const sales = await many(
    `SELECT u.id, u.full_name FROM users u
     LEFT JOIN assignment_state s
       ON s.user_id = u.id AND s.month = $1
     WHERE u.role = 'sales' AND u.is_active = true
     ORDER BY COALESCE(s.last_assigned_at, '1970-01-01') ASC, u.id
     LIMIT 1`,
    [month]
  );
  if (!sales.length) return null;

  const user = sales[0];
  await q(
    `INSERT INTO assignment_state (user_id, last_assigned_at, leads_assigned_this_month, month)
     VALUES ($1, now(), 1, $2)
     ON CONFLICT (user_id, month)
     DO UPDATE SET last_assigned_at = now(),
                   leads_assigned_this_month = assignment_state.leads_assigned_this_month + 1`,
    [user.id, month]
  );
  return user;
}

export function firstOfMonth(d = new Date()) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
}

export async function resetMonthState() {
  await q(`DELETE FROM assignment_state WHERE month < $1`, [firstOfMonth()]);
}
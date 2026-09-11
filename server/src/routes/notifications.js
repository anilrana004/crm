import { many, oneOrNull, q } from '../db.js';

export default async function notificationRoutes(app) {
  app.get('/', { preHandler: [app.requireAuth] }, async (request) => {
    const { unread } = request.query || {};
    const where = unread === 'true' ? `user_id=$1 AND read_at IS NULL` : `user_id=$1`;
    const rows = await many(
      `SELECT * FROM notifications WHERE ${where} ORDER BY created_at DESC LIMIT 50`,
      [request.user.sub]
    );
    return { notifications: rows };
  });

  app.get('/unread-count', { preHandler: [app.requireAuth] }, async (request) => {
    const row = await oneOrNull(
      `SELECT count(*) FILTER (WHERE read_at IS NULL) AS unread, count(*) AS total
       FROM notifications WHERE user_id=$1`,
      [request.user.sub]
    );
    return row;
  });

  app.post('/:id/read', { preHandler: [app.requireAuth] }, async (request, reply) => {
    const res = await q(
      `UPDATE notifications SET read_at=now() WHERE id=$1 AND user_id=$2 RETURNING id`,
      [request.params.id, request.user.sub]
    );
    if (!res.rowCount) return reply.code(404).send({ error: 'Notification not found' });
    return { ok: true };
  });

  app.post('/read-all', { preHandler: [app.requireAuth] }, async (request) => {
    await q(`UPDATE notifications SET read_at=now() WHERE user_id=$1 AND read_at IS NULL`, [request.user.sub]);
    return { ok: true };
  });
}
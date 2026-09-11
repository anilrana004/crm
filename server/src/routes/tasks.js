import { many, oneOrNull, q, isUuid } from '../db.js';
import { createTask } from '../services/automation.js';
import { notifyAllChannels } from '../services/notifications.js';

export default async function taskRoutes(app) {
  // List tasks with filters. Sales users default to their own tasks; managers/admins see all.
  app.get('/', { preHandler: [app.requireAuth] }, async (request) => {
    const { status, lead_id, assignee, type } = request.query || {};
    const where = [];
    const params = [];

    const isManager = ['admin', 'manager'].includes(request.user.role);
    if (!assignee && !isManager) {
      params.push(request.user.sub);
      where.push(`t.assigned_to = $${params.length}`);
    }
    if (assignee) { params.push(assignee); where.push(`t.assigned_to = $${params.length}`); }
    if (status) { params.push(status); where.push(`t.status = $${params.length}`); }
    if (lead_id) { params.push(lead_id); where.push(`t.lead_id = $${params.length}`); }
    if (type) { params.push(type); where.push(`t.type = $${params.length}`); }
    if (!status && !type) where.push(`t.status <> 'cancelled'`);

    const rows = await many(
      `SELECT t.*, l.customer_name, l.mobile_number, u.full_name AS assignee_name
       FROM tasks t
       JOIN leads l ON l.id = t.lead_id
       JOIN users u ON u.id = t.assigned_to
       ${where.length ? `WHERE ${where.join(' AND ')}` : ''}
       ORDER BY (t.status = 'pending') DESC, t.due_at ASC
       LIMIT 200`,
      params
    );
    return { tasks: rows };
  });

  // Create a custom follow-up
  app.post('/', { preHandler: [app.requireAuth] }, async (request, reply) => {
    const b = request.body || {};
    if (!b.lead_id || (!b.due_at && !b.due_in_days))
      return reply.code(400).send({ error: 'lead_id and due_at (or due_in_days) required' });
    const lead = await oneOrNull(`SELECT customer_name FROM leads WHERE id=$1`, [b.lead_id]);
    if (!lead) return reply.code(404).send({ error: 'Lead not found' });

    let due = b.due_at ? new Date(b.due_at) : new Date();
    if (b.due_in_days) due.setDate(due.getDate() + parseInt(b.due_in_days, 10));

    const task = await createTask({
      leadId: b.lead_id,
      assignedTo: b.assigned_to || request.user.sub,
      type: b.type || 'custom',
      title: b.title || `Follow-up: ${lead.customer_name}`,
      description: b.description || null,
      dueAt: due,
    });
    await notifyAllChannels({
      userId: task.assigned_to,
      leadId: b.lead_id,
      taskId: task.id,
      subject: `New follow-up: ${lead.customer_name}`,
      message: task.title,
    });
    return reply.code(201).send({ task });
  });

  // Complete / cancel / reschedule
app.patch('/:id', { preHandler: [app.requireAuth] }, async (request, reply) => {
    if (!isUuid(request.params.id)) return reply.code(404).send({ error: 'Task not found' });
    const task = await oneOrNull(`SELECT * FROM tasks WHERE id=$1`, [request.params.id]);
    if (!task) return reply.code(404).send({ error: 'Task not found' });
    const b = request.body || {};

    const set = [];
    const params = [];
    if (b.status === 'completed') {
      set.push(`status='completed'`, `completed_at=now()`, `updated_at=now()`);
    } else if (b.status === 'cancelled') {
      set.push(`status='cancelled'`, `updated_at=now()`);
    } else if (b.due_at) {
      params.push(b.due_at);
      set.push(`due_at = $${params.length}`, `status='pending'`, `updated_at=now()`);
    }
    if (!set.length) return reply.code(400).send({ error: 'Nothing to update' });
    params.push(task.id);
    await q(`UPDATE tasks SET ${set.join(', ')} WHERE id = $${params.length}`, params);

    const updated = await oneOrNull(`SELECT * FROM tasks WHERE id=$1`, [task.id]);
    return { task: updated };
  });
}

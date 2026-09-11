import nodemailer from 'nodemailer';
import cfg from '../config.js';
import { q, oneOrNull } from '../db.js';

let transporter = null;
if (cfg.smtp.host) {
  transporter = nodemailer.createTransport({
    host: cfg.smtp.host,
    port: cfg.smtp.port,
    secure: cfg.smtp.port === 465,
    auth: cfg.smtp.user ? { user: cfg.smtp.user, pass: cfg.smtp.pass } : undefined,
  });
}

/**
 * Create an in-app notification (always works) and optionally an email/SMS row.
 */
export async function notify({
  userId,
  leadId = null,
  taskId = null,
  channel = 'in_app',
  subject = null,
  message,
}) {
  const row = await oneOrNull(
    `INSERT INTO notifications (user_id, lead_id, task_id, channel, subject, message, status, sent_at)
     VALUES ($1,$2,$3,$4,$5,$6,'pending', NULL)
     RETURNING id`,
    [userId, leadId, taskId, channel, subject, message]
  );

  if (channel === 'in_app') {
    await q(`UPDATE notifications SET status='sent', sent_at=now() WHERE id=$1`, [row.id]);
    return row;
  }

  if (channel === 'email') {
    const ok = await sendEmail(userId, subject, message);
    await q(`UPDATE notifications SET status=$1, sent_at=now() WHERE id=$2`, [
      ok ? 'sent' : 'failed',
      row.id,
    ]);
  }
  return row;
}

async function sendEmail(userId, subject, message) {
  if (!transporter) return false;
  try {
    const u = await oneOrNull(`SELECT email FROM users WHERE id=$1`, [userId]);
    if (!u) return false;
    await transporter.sendMail({
      from: cfg.smtp.from,
      to: u.email,
      subject,
      text: message,
    });
    return true;
  } catch (err) {
    console.error('[email] send failed:', err.message);
    return false;
  }
}

/**
 * Convenience: create in-app + email reminder in one call.
 */
export async function notifyAllChannels({ userId, leadId, taskId, subject, message }) {
  await notify({ userId, leadId, taskId, channel: 'in_app', subject, message });
  await notify({
    userId,
    leadId,
    taskId,
    channel: 'email',
    subject: subject || 'SecureTravels reminder',
    message,
  });
}

/** Notify all users with a given role. Used to alert the Ops team. */
export async function notifyRole(role, { leadId = null, subject, message }) {
  const rows = await q(`SELECT id FROM users WHERE role=$1 AND is_active=true`, [role]);
  for (const u of rows.rows) {
    await notifyAllChannels({ userId: u.id, leadId, subject, message });
  }
}
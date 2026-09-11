import cron from 'node-cron';
import { q } from './db.js';
import {
  autoFollowUpStaleLeads,
  ensurePaymentReminder,
  markOverdueTasks,
} from './services/automation.js';

/**
 * Guard: run a job at most once per hour (keyed by hour bucket).
 */
async function runOnce(jobKey, fn) {
  try {
    const existing = await q(
      `SELECT 1 FROM cron_runs
       WHERE job_key = $1 AND ran_at >= date_trunc('hour', now())
       LIMIT 1`,
      [jobKey]
    );
    if (existing.rowCount) return; // already ran this hour
    await q(`INSERT INTO cron_runs (job_key) VALUES ($1)`, [jobKey]);
    const count = await fn();
    console.log(`[cron] ${jobKey} -> ${count}`);
  } catch (err) {
    console.error(`[cron] ${jobKey} failed:`, err.message);
  }
}

export function startCron() {
  if (!process.env.DISABLE_CRON) {
    // Module 14: no status update in 24h → next follow-up (hourly)
    cron.schedule('0 * * * *', () => runOnce('no_activity_24h', autoFollowUpStaleLeads));
    // Module 7: balance payment reminders from 3 days before due date (every 6h)
    cron.schedule('0 */6 * * *', () => runOnce('payment_reminder_3d', ensurePaymentReminder));
    // Housekeeping: mark overdue tasks
    cron.schedule('15 * * * *', () => runOnce('mark_overdue', markOverdueTasks));
    console.log('[cron] jobs scheduled (24h follow-up, payment reminders, overdue sync)');
  }
}
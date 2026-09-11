import { oneOrNull, q } from '../db.js';
import { pickNextSalesUser } from '../services/roundRobin.js';
import { onLeadCreated } from '../services/automation.js';

const ALLOWED_SOURCES = [
  'google_ads', 'facebook_ads', 'instagram', 'website', 'whatsapp',
  'referral', 'justdial', 'walk_in', 'b2b', 'existing_customer',
];

/**
 * Module 14 end-to-end chain:
 *   website lead → auto-create lead → round-robin assign → notify exec
 *   → 5-min call task (onLeadCreated) → 24h no-update follow-up (cron)
 *   → quotation sent (manual) → booking confirmed → ops notified (cron/hook).
 *
 * Public endpoint for the website form.
 * POST /api/webhook/lead  { customer_name, mobile_number, email?, destination?, package?, message?, source? }
 */
export default async function webhookRoutes(app) {
  app.post('/lead', async (request, reply) => {
    const b = request.body || {};
    const source = ALLOWED_SOURCES.includes((b.source || '').toLowerCase())
      ? (b.source || '').toLowerCase()
      : 'website';

    const missing = [];
    if (!b.customer_name) missing.push('customer_name');
    if (!b.mobile_number) missing.push('mobile_number');
    if (missing.length) {
      await logWebhook('website', b, null, 'failed', `Missing: ${missing.join(', ')}`);
      return reply.code(400).send({ error: `Missing fields: ${missing.join(', ')}` });
    }

    // Duplicate guard: same mobile + name within last 7 days
    const dup = await oneOrNull(
      `SELECT id FROM leads
       WHERE mobile_number = $1 AND lower(customer_name) = lower($2)
         AND created_at > now() - interval '7 days'`,
      [b.mobile_number, b.customer_name]
    );
    if (dup) {
      await logWebhook(b.source || 'website', b, dup.id, 'duplicate', 'Repeated submission');
      return reply.code(200).send({ ok: true, duplicate: true, lead_id: dup.id });
    }

    // Round-robin assign to next available sales exec (Module 14)
    const owner = await pickNextSalesUser();
    let ownerId = owner ? owner.id : null;
    if (!ownerId) {
      const fallback = await oneOrNull(`SELECT id FROM users WHERE role='manager' AND is_active=true LIMIT 1`);
      ownerId = fallback ? fallback.id : null;
    }
    if (!ownerId) {
      await logWebhook(b.source || 'website', b, null, 'failed', 'No sales user available to assign');
      return reply.code(503).send({ error: 'No sales user available right now' });
    }

    // Resolve package by name if provided
    let packageId = null;
    if (b.package) {
      const pkg = await oneOrNull(
        `SELECT id FROM packages WHERE lower(name) = lower($1) OR lower(slug) = lower($1)`,
        [b.package]
      );
      packageId = pkg ? pkg.id : null;
    }

    const lead = await oneOrNull(
      `INSERT INTO leads
         (customer_name, mobile_number, whatsapp_number, email, source, destination, package_id, num_persons, lead_owner_id, remarks)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
       RETURNING *`,
      [
        b.customer_name, b.mobile_number, b.mobile_number,
        b.email || null, source, b.destination || null, packageId,
        b.num_persons || 1, ownerId, b.message || null,
      ]
    );

    // Module 3 chain: 5-minute call task + notify exec
    await onLeadCreated(lead);

    await logWebhook(b.source || 'website', b, lead.id, 'success');
    return reply.code(201).send({
      ok: true,
      lead_id: lead.id,
      owner: owner ? owner.full_name : null,
      note: 'Lead created and assigned. 5-minute call task scheduled.',
    });
  });
}

async function logWebhook(sourceValue, payload, leadId, status, errorMessage = null) {
  try {
    await q(
      `INSERT INTO webhook_logs (source, payload, lead_id, status, error_message)
       VALUES ($1,$2,$3,$4,$5)`,
      [sourceValue || 'api', payload, leadId, status, errorMessage]
    );
  } catch (err) {
    console.error('[webhook] log fail', err.message);
  }
}
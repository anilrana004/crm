import { many, oneOrNull } from '../db.js';
import cfg from '../config.js';

const COMPANY = cfg.companyName;

/**
 * Load all active templates.
 */
export async function listTemplates() {
  return many(`SELECT * FROM whatsapp_templates WHERE is_active = true ORDER BY name`);
}

/**
 * Render a template with a lead's (and optional payment's) data.
 */
export async function renderTemplate(templateKey, leadId, paymentId = null) {
  const tpl = await oneOrNull(
    `SELECT * FROM whatsapp_templates WHERE template_key = $1 AND is_active = true`,
    [templateKey]
  );
  if (!tpl) return null;

  const lead = await oneOrNull(
    `SELECT l.*, p.name AS package_name FROM leads l
     LEFT JOIN packages p ON p.id = l.package_id
     WHERE l.id = $1`,
    [leadId]
  );
  if (!lead) return { message: '' };

  let payment = null;
  if (paymentId) {
    payment = await oneOrNull(`SELECT * FROM payments WHERE id = $1`, [paymentId]);
  } else {
    payment = await oneOrNull(
      `SELECT * FROM payments WHERE lead_id = $1 ORDER BY created_at DESC LIMIT 1`,
      [leadId]
    );
  }

  const fmt = (n) => (n == null ? '—' : `₹${Number(n).toLocaleString('en-IN')}`);
  const dateStr = (d) => (d ? new Date(d).toISOString().slice(0, 10) : '—');

  const vars = {
    customer: lead.customer_name,
    package: lead.package_name || lead.destination || '—',
    destination: lead.destination || '—',
    travel_date: dateStr(lead.travel_date),
    pax: lead.num_persons || '—',
    budget: fmt(lead.budget),
    booking_amount: payment ? fmt(payment.booking_amount) : '—',
    advance: payment ? fmt(payment.advance_amount) : '—',
    balance: payment ? fmt(payment.balance_amount) : '—',
    due_date: payment ? dateStr(payment.due_date) : '—',
    mobile: lead.whatsapp_number || lead.mobile_number,
    company: COMPANY,
  };

  const message = tpl.message_template.replace(/\{\{(\w+)\}\}/g, (m, k) =>
    k in vars ? vars[k] : m
  );
  return { key: tpl.template_key, name: tpl.name, message, mobile: vars.mobile };
}

/**
 * Build a wa.me click-to-chat link for a rendered message.
 */
export function waLink(mobile, message) {
  const cleaned = String(mobile || '').replace(/[^\d]/g, '');
  return `https://wa.me/${cleaned}?text=${encodeURIComponent(message)}`;
}

export { COMPANY };
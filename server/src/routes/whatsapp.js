import { listTemplates, renderTemplate, waLink, COMPANY } from '../services/whatsapp.js';
import { isUuid } from '../db.js';

export default async function whatsappRoutes(app) {
  app.get('/templates', { preHandler: [app.requireAuth] }, async () => {
    return { templates: await listTemplates() };
  });

  /**
   * Preview a rendered template for a lead + optional payment, and build
   * the wa.me click-to-chat link (Module 8).
   * GET /api/whatsapp/preview?key=package_details&lead_id=...&payment_id=...
   */
app.get('/preview', { preHandler: [app.requireAuth] }, async (request, reply) => {
    const { key, lead_id, payment_id } = request.query || {};
    if (!key || !lead_id) return reply.code(400).send({ error: 'key and lead_id required' });
    if (!isUuid(lead_id) || (payment_id && !isUuid(payment_id)))
      return reply.code(404).send({ error: 'Lead not found' });
    const rendered = await renderTemplate(key, lead_id, payment_id || null);
    if (!rendered) return reply.code(404).send({ error: 'Template not found' });
    const mobile = request.query.mobile;
    return {
      ...rendered,
      wa_link: waLink(mobile || rendered.mobile, rendered.message),
      company: COMPANY,
    };
  });
}

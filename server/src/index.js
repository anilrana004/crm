import Fastify from 'fastify';
import cors from '@fastify/cors';
import jwt from '@fastify/jwt';
import cfg from './config.js';
import authPlugin from './plugins/auth.js';
import authRoutes from './routes/auth.js';
import leadRoutes from './routes/leads.js';
import taskRoutes from './routes/tasks.js';
import packageRoutes from './routes/packages.js';
import paymentRoutes from './routes/payments.js';
import operationRoutes from './routes/operations.js';
import customerRoutes from './routes/customers.js';
import dashboardRoutes from './routes/dashboard.js';
import targetRoutes from './routes/targets.js';
import reportRoutes from './routes/reports.js';
import whatsappRoutes from './routes/whatsapp.js';
import notificationRoutes from './routes/notifications.js';
import webhookRoutes from './routes/webhook.js';
import { startCron } from './cron.js';

const app = Fastify({ logger: true });

await app.register(cors, {
  origin: cfg.frontendUrl.split(','),
  credentials: false,
});

await app.register(jwt, { secret: cfg.jwtSecret });
await app.register(authPlugin);

app.get('/api/health', async () => ({
  ok: true,
  service: 'securetravels-crm',
  time: new Date().toISOString(),
}));

for (const prefix of [
  ['/api/auth', authRoutes],
  ['/api/leads', leadRoutes],
  ['/api/tasks', taskRoutes],
  ['/api/packages', packageRoutes],
  ['/api/payments', paymentRoutes],
  ['/api/operations', operationRoutes],
  ['/api/customers', customerRoutes],
  ['/api/dashboard', dashboardRoutes],
  ['/api/targets', targetRoutes],
  ['/api/reports', reportRoutes],
  ['/api/whatsapp', whatsappRoutes],
  ['/api/notifications', notificationRoutes],
  ['/api/webhook', webhookRoutes],
]) {
  await app.register(prefix[1], { prefix: prefix[0] });
}

app.setErrorHandler((err, request, reply) => {
  app.log.error(err);
  const statusCode = err.statusCode || 500;
  const message =
    statusCode >= 500 && process.env.NODE_ENV === 'production'
      ? 'Internal Server Error'
      : err.message || 'Something went wrong';
  reply.code(statusCode).send({ error: message, statusCode });
});

await app.listen({ port: cfg.port, host: cfg.host });
startCron();

export default app;
import fp from 'fastify-plugin';

export default fp(async function authPlugin(app, opts) {
  app.decorate('requireAuth', async (request, reply) => {
    try {
      await request.jwtVerify();
    } catch {
      return reply.code(401).send({ error: 'Unauthorized' });
    }
  });

  app.decorate('requireRole', (...allowed) => {
    return async (request, reply) => {
      const { role } = request.user;
      if (!allowed.includes(role)) {
        return reply.code(403).send({ error: 'Forbidden', required: allowed });
      }
    };
  });
});
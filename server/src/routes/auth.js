import bcrypt from 'bcryptjs';
import { oneOrNull, many } from '../db.js';
import cfg from '../config.js';

export default async function authRoutes(app) {
  app.post('/login', async (request, reply) => {
    const { email, password } = request.body || {};
    if (!email || !password) return reply.code(400).send({ error: 'Email and password required' });

    const user = await oneOrNull(
      `SELECT id, email, password_hash, full_name, role, phone, is_active FROM users WHERE email = $1`,
      [email.toLowerCase().trim()]
    );
    if (!user) return reply.code(401).send({ error: 'Invalid credentials' });
    if (!user.is_active) return reply.code(403).send({ error: 'Account disabled' });

    const ok = await bcrypt.compare(password, user.password_hash);
    if (!ok) return reply.code(401).send({ error: 'Invalid credentials' });

    const token = app.jwt.sign(
      { sub: user.id, role: user.role, name: user.full_name },
      { expiresIn: cfg.jwtExpiresIn }
    );
    return { token, user: publicUser(user) };
  });

  app.get('/me', { preHandler: [app.requireAuth] }, async (request) => {
    const user = await oneOrNull(`SELECT id, email, full_name, role, phone, is_active FROM users WHERE id = $1`, [
      request.user.sub,
    ]);
    return { user: publicUser(user) };
  });

  app.get('/users', { preHandler: [app.requireAuth] }, async () => {
    const users = await many(
      `SELECT id, email, full_name, role, phone, is_active FROM users ORDER BY full_name`
    );
    return { users };
  });

  app.post(
    '/users',
    { preHandler: [app.requireAuth, app.requireRole('admin', 'manager')] },
    async (request, reply) => {
      const { email, password, full_name, role, phone } = request.body || {};
      if (!email || !password || !full_name || !role)
        return reply.code(400).send({ error: 'Missing fields' });
      const exists = await oneOrNull(`SELECT id FROM users WHERE email=$1`, [email.toLowerCase().trim()]);
      if (exists) return reply.code(409).send({ error: 'Email already registered' });
      const hash = await bcrypt.hash(password, 10);
      const user = await oneOrNull(
        `INSERT INTO users (email, password_hash, full_name, role, phone)
         VALUES ($1,$2,$3,$4,$5) RETURNING id, email, full_name, role, phone, is_active`,
        [email.toLowerCase().trim(), hash, full_name, role, phone || null]
      );
      return reply.code(201).send({ user });
    }
  );
}

export function publicUser(u) {
  if (!u) return null;
  const { password_hash, ...rest } = u;
  return rest;
}
import pg from 'pg';
import cfg from './config.js';

export const pool = new pg.Pool({ connectionString: cfg.databaseUrl });

pool.on('error', (err) => {
  console.error('[pg] idle client error', err.message);
});

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isUuid = (s) => typeof s === 'string' && UUID_RE.test(s);

export async function q(text, params) {
  const res = await pool.query(text, params);
  return res;
}

export async function oneOrNull(text, params) {
  const res = await pool.query(text, params);
  return res.rows[0] || null;
}

export async function many(text, params) {
  const res = await pool.query(text, params);
  return res.rows;
}

export async function tx(fn) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
}

export default pool;
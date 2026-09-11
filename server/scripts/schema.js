import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { pool } from '../src/db.js';

const here = dirname(fileURLToPath(import.meta.url));
const schemaSql = readFileSync(join(here, '../src/sql/schema.sql'), 'utf8');

export async function applySchema() {
  console.log('[schema] dropping public schema (dev reset)...');
  await pool.query('DROP SCHEMA public CASCADE');
  await pool.query('CREATE SCHEMA public');
  console.log('[schema] applying schema.sql...');
  await pool.query(schemaSql);
  const tables = (await pool.query(
    `SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name`
  )).rows.map((r) => r.table_name).join(', ');
  console.log('[schema] done. tables:', tables);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await applySchema();
  await pool.end();
}
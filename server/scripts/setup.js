import { pool } from '../src/db.js';
import { applySchema } from './schema.js';
import { seed } from './seed.js';

await applySchema();
await seed();
await pool.end();
console.log('[setup] complete.');
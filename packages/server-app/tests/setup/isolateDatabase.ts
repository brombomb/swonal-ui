import fs from 'fs';
import os from 'os';
import path from 'path';
import { afterAll } from 'vitest';

// Give each test file its own copy of the seeded SQLite database so files never see each
// other's writes (e.g. the single pending_queued_workout row), whatever order or concurrency they run in.
const dbUrl = process.env.DATABASE_URL?.trim();
if (dbUrl?.startsWith('file:')) {
  const rawPath = dbUrl.slice('file:'.length).split('?')[0];
  const seededDb = path.isAbsolute(rawPath) ? rawPath : path.resolve(process.cwd(), 'prisma', rawPath);
  if (fs.existsSync(seededDb)) {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'swonal-test-db-'));
    const fileDb = path.join(tempDir, path.basename(seededDb));
    fs.copyFileSync(seededDb, fileDb);
    process.env.DATABASE_URL = `file:${fileDb}`;
    afterAll(() => {
      fs.rmSync(tempDir, { recursive: true, force: true });
    });
  }
}

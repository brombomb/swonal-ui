import fs from 'fs';
import path from 'path';
import type { PrismaClient } from '@prisma/client';

const BACKUP_PREFIX = 'swonal-backup-';
const MAX_BACKUPS = 7;

export function getDefaultBackupDir(): string {
  const custom = process.env.SWONAL_BACKUP_DIR?.trim();
  if (custom) return path.resolve(custom);
  const dbUrl = process.env.DATABASE_URL?.trim();
  if (dbUrl?.startsWith('file:')) {
    const rawPath = dbUrl.slice('file:'.length).split('?')[0];
    if (rawPath && rawPath !== ':memory:') {
      const resolvedDb = path.isAbsolute(rawPath)
        ? rawPath
        : path.resolve(process.cwd(), 'prisma', rawPath);
      return path.join(path.dirname(resolvedDb), 'backups');
    }
  }
  if (fs.existsSync('/data')) {
    return '/data/backups';
  }
  return path.resolve(__dirname, '../../../prisma/data/backups');
}

function formatUtcStamp(date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return [
    date.getUTCFullYear(),
    pad(date.getUTCMonth() + 1),
    pad(date.getUTCDate()),
    '-',
    pad(date.getUTCHours()),
    pad(date.getUTCMinutes()),
    pad(date.getUTCSeconds()),
  ].join('');
}

export async function createRotatingSqliteBackup(
  prisma: PrismaClient,
  backupDir = getDefaultBackupDir()
): Promise<string> {
  fs.mkdirSync(backupDir, { recursive: true });
  const stamp = formatUtcStamp();
  const targetPath = path.join(backupDir, `${BACKUP_PREFIX}${stamp}.db`);
  const tmpPath = path.join(
    backupDir,
    `${BACKUP_PREFIX}${stamp}.tmp.${process.pid}.${Date.now()}`
  );

  // Clean up any orphaned temporary files left by prior interrupted backups.
  for (const name of fs.readdirSync(backupDir)) {
    if (name.startsWith(BACKUP_PREFIX) && name.includes('.tmp.')) {
      try {
        fs.unlinkSync(path.join(backupDir, name));
      } catch {
        // ignore cleanup error
      }
    }
  }

  try {
    await prisma.$executeRaw`VACUUM INTO ${tmpPath}`;
    const stat = fs.statSync(tmpPath);
    if (stat.size <= 0) {
      throw new Error('SQLite backup produced an empty file');
    }
    fs.renameSync(tmpPath, targetPath);
  } catch (err) {
    try {
      if (fs.existsSync(tmpPath)) fs.unlinkSync(tmpPath);
    } catch {
      // ignore cleanup error
    }
    throw err;
  }

  const entries = fs
    .readdirSync(backupDir)
    .filter((f) => f.startsWith(BACKUP_PREFIX) && f.endsWith('.db'))
    .map((name) => {
      const fullPath = path.join(backupDir, name);
      const stat = fs.statSync(fullPath);
      return { fullPath, mtimeMs: stat.mtimeMs, size: stat.size };
    })
    .filter((entry) => {
      if (entry.size > 0) return true;
      // Remove corrupted/0-byte snapshots without letting them count toward retention.
      try {
        fs.unlinkSync(entry.fullPath);
      } catch {
        // ignore cleanup error
      }
      return false;
    })
    .sort((a, b) => b.mtimeMs - a.mtimeMs);

  for (const stale of entries.slice(MAX_BACKUPS)) {
    try {
      fs.unlinkSync(stale.fullPath);
    } catch {
      // ignore cleanup error
    }
  }
  return targetPath;
}

export function startScheduledBackupLoop(prisma: PrismaClient): () => void {
  if (process.env.SWONAL_SKIP_DOTENV === '1' || process.env.NODE_ENV === 'test') {
    return () => {};
  }

  const timer = setInterval(() => {
    void createRotatingSqliteBackup(prisma).catch((err) => {
      console.warn('[BackupService] Scheduled rotating backup failed:', err);
    });
  }, 24 * 60 * 60 * 1000);
  timer.unref();
  return () => clearInterval(timer);
}

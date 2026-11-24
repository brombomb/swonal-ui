import fs from 'fs';
import path from 'path';
import type { PrismaClient } from '@prisma/client';

const BACKUP_PREFIX = 'swonal-backup-';
const MAX_BACKUPS = 7;

export function getDefaultBackupDir(): string {
  const custom = process.env.SWONAL_BACKUP_DIR?.trim();
  if (custom) return path.resolve(custom);
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
  const targetPath = path.join(backupDir, `${BACKUP_PREFIX}${formatUtcStamp()}.db`);
  if (fs.existsSync(targetPath)) {
    fs.unlinkSync(targetPath);
  }
  const safePath = targetPath.replace(/'/g, "''");
  await prisma.$executeRawUnsafe(`VACUUM INTO '${safePath}'`);

  const entries = fs
    .readdirSync(backupDir)
    .filter((f) => f.startsWith(BACKUP_PREFIX) && f.endsWith('.db'))
    .map((name) => {
      const fullPath = path.join(backupDir, name);
      const stat = fs.statSync(fullPath);
      return { fullPath, mtimeMs: stat.mtimeMs };
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

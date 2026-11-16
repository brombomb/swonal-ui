import fs from 'fs';
import path from 'path';
import { getPublicBaseUrl } from '../config';

interface RuntimeSettingsData {
  publicUrlOverride?: string | null;
  authPasscodeHash?: string | null;
  authTrustLocalhost?: boolean;
  apiToken?: string | null;
}

function resolveSettingsFilePath(): string {
  if (process.env.SWONAL_SETTINGS_FILE) {
    return process.env.SWONAL_SETTINGS_FILE;
  }
  const baseDir = fs.existsSync('/data')
    ? '/data'
    : path.resolve(__dirname, '../../../data');
  return path.join(baseDir, 'swonal-settings.json');
}

let cachedFilePath: string | null = null;
let cachedSettings: RuntimeSettingsData | null = null;

export function invalidateRuntimeSettingsCache(): void {
  cachedFilePath = null;
  cachedSettings = null;
}

function readSettingsFile(): RuntimeSettingsData {
  const filePath = resolveSettingsFilePath();
  if (cachedSettings !== null && cachedFilePath === filePath) {
    return cachedSettings;
  }
  try {
    if (!fs.existsSync(filePath)) {
      cachedFilePath = filePath;
      cachedSettings = {};
      return cachedSettings;
    }
    const raw = fs.readFileSync(filePath, 'utf8');
    cachedFilePath = filePath;
    cachedSettings = JSON.parse(raw) as RuntimeSettingsData;
    return cachedSettings;
  } catch {
    cachedFilePath = filePath;
    cachedSettings = {};
    return cachedSettings;
  }
}

function writeSettingsFile(data: RuntimeSettingsData): void {
  const filePath = resolveSettingsFilePath();
  const dir = path.dirname(filePath);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf8');
  cachedFilePath = filePath;
  cachedSettings = data;
}

export function getPublicUrlOverride(): string | null {
  const data = readSettingsFile();
  const val = (data.publicUrlOverride ?? '').trim().replace(/\/+$/, '');
  return val || null;
}

export function setPublicUrlOverride(url: string | null): string | null {
  const cleaned = (url ?? '').trim().replace(/\/+$/, '');
  const normalized =
    cleaned && !/^https?:\/\//i.test(cleaned) ? `https://${cleaned}` : cleaned;
  const current = readSettingsFile();
  writeSettingsFile({
    ...current,
    publicUrlOverride: normalized || null,
  });
  return normalized || null;
}

export function getEffectivePublicBaseUrl(): string {
  return getPublicUrlOverride() || getPublicBaseUrl();
}

export function getRuntimeSettings(): RuntimeSettingsData {
  return readSettingsFile();
}

export function updateRuntimeSettings(patch: Partial<RuntimeSettingsData>): RuntimeSettingsData {
  const next = { ...readSettingsFile(), ...patch };
  writeSettingsFile(next);
  return next;
}

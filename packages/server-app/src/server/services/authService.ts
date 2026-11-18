import crypto from 'crypto';
import type { IncomingMessage } from 'http';
import type { Request, Response } from 'express';
import { getRuntimeSettings, updateRuntimeSettings } from './runtimeSettings';

const SESSION_COOKIE_NAME = 'swonal_session';
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const EPHEMERAL_COOKIE_SECRET = crypto.randomBytes(32).toString('hex');

function getCookieSecret(): string {
  const settings = getRuntimeSettings();
  return (
    process.env.SWONAL_SESSION_SECRET?.trim() ||
    settings.apiToken ||
    process.env.SPOTIFY_CLIENT_SECRET?.trim() ||
    EPHEMERAL_COOKIE_SECRET
  );
}

export function ensureApiToken(): string {
  const settings = getRuntimeSettings();
  if (settings.apiToken) return settings.apiToken;
  const generated = crypto.randomBytes(24).toString('hex');
  updateRuntimeSettings({ apiToken: generated });
  return generated;
}

export function hashPasscode(passcode: string): string {
  const salt = crypto.randomBytes(16).toString('hex');
  const derived = crypto.scryptSync(passcode, salt, 64).toString('hex');
  return `${salt}:${derived}`;
}

export function verifyPasscodeHash(passcode: string, stored: string): boolean {
  const [salt, expectedHex] = stored.split(':');
  if (!salt || !expectedHex) return false;
  const derived = crypto.scryptSync(passcode, salt, 64);
  const expected = Buffer.from(expectedHex, 'hex');
  if (derived.length !== expected.length) return false;
  return crypto.timingSafeEqual(derived, expected);
}

export function isAuthEnabled(): boolean {
  if (process.env.SWONAL_AUTH_PASSCODE?.trim()) return true;
  return Boolean(getRuntimeSettings().authPasscodeHash);
}

export function isLocalhostTrusted(): boolean {
  const settings = getRuntimeSettings();
  return settings.authTrustLocalhost ?? true;
}

export function verifyPasscode(candidate: string): boolean {
  const envPass = process.env.SWONAL_AUTH_PASSCODE?.trim();
  if (envPass) {
    const a = Buffer.from(candidate, 'utf8');
    const b = Buffer.from(envPass, 'utf8');
    return a.length === b.length && crypto.timingSafeEqual(a, b);
  }
  const stored = getRuntimeSettings().authPasscodeHash;
  if (!stored) return false;
  return verifyPasscodeHash(candidate, stored);
}

function isPrivateOrLoopbackIp(rawIp: string): boolean {
  const ip = rawIp.trim().replace(/^::ffff:/i, '');
  if (!ip) return false;
  if (ip === '127.0.0.1' || ip === '::1' || ip.startsWith('127.')) return true;
  if (ip.startsWith('10.') || ip.startsWith('192.168.') || ip.startsWith('169.254.')) return true;
  const m172 = /^172\.(\d+)\./.exec(ip);
  if (m172) {
    const second = Number(m172[1]);
    if (second >= 16 && second <= 31) return true;
  }
  const lower = ip.toLowerCase();
  return lower.startsWith('fe80:') || lower.startsWith('fc') || lower.startsWith('fd');
}

export function isDirectLocalhost(req: IncomingMessage): boolean {
  const cfIp = req.headers['cf-connecting-ip'];
  if (cfIp) {
    const val = Array.isArray(cfIp) ? cfIp[0] : cfIp;
    if (!isPrivateOrLoopbackIp(val)) return false;
  }
  const remoteAddr = req.socket?.remoteAddress || '';
  if (!isPrivateOrLoopbackIp(remoteAddr)) return false;

  const xff = req.headers['x-forwarded-for'];
  if (xff) {
    const firstHop = (Array.isArray(xff) ? xff[0] : xff).split(',')[0] ?? '';
    return isPrivateOrLoopbackIp(firstHop);
  }
  return true;
}

function parseCookies(cookieHeader: string | undefined): Record<string, string> {
  if (!cookieHeader) return {};
  const out: Record<string, string> = {};
  for (const part of cookieHeader.split(';')) {
    const eq = part.indexOf('=');
    if (eq <= 0) continue;
    const k = part.slice(0, eq).trim();
    const v = part.slice(eq + 1).trim();
    out[k] = decodeURIComponent(v);
  }
  return out;
}

export function createSessionCookieValue(): string {
  const payload = { iat: Date.now(), nonce: crypto.randomBytes(8).toString('hex') };
  const payloadB64 = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url');
  const sig = crypto
    .createHmac('sha256', getCookieSecret())
    .update(payloadB64)
    .digest('base64url');
  return `${payloadB64}.${sig}`;
}

export function verifySessionCookieValue(raw: string | undefined): boolean {
  if (!raw) return false;
  const dotIdx = raw.indexOf('.');
  if (dotIdx <= 0) return false;
  const payloadB64 = raw.slice(0, dotIdx);
  const sig = raw.slice(dotIdx + 1);
  const expectedSig = crypto
    .createHmac('sha256', getCookieSecret())
    .update(payloadB64)
    .digest('base64url');
  const a = Buffer.from(sig, 'utf8');
  const b = Buffer.from(expectedSig, 'utf8');
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return false;
  try {
    const parsed = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8')) as {
      iat?: number;
    };
    return typeof parsed.iat === 'number' && Date.now() - parsed.iat <= SESSION_TTL_MS;
  } catch {
    return false;
  }
}

export function isRequestAuthenticated(req: IncomingMessage): boolean {
  if (!isAuthEnabled()) return true;
  if (isLocalhostTrusted() && isDirectLocalhost(req)) return true;

  const settings = getRuntimeSettings();
  const apiToken = settings.apiToken || process.env.SWONAL_API_TOKEN?.trim();
  if (apiToken) {
    const authHeader = req.headers.authorization;
    if (authHeader?.startsWith('Bearer ')) {
      const provided = authHeader.slice(7).trim();
      const a = Buffer.from(provided, 'utf8');
      const b = Buffer.from(apiToken, 'utf8');
      if (a.length === b.length && crypto.timingSafeEqual(a, b)) return true;
    }
    if (req.url) {
      try {
        const parsedUrl = new URL(req.url, 'http://localhost');
        const tokenParam = parsedUrl.searchParams.get('token') || '';
        if (tokenParam) {
          const a = Buffer.from(tokenParam, 'utf8');
          const b = Buffer.from(apiToken, 'utf8');
          if (a.length === b.length && crypto.timingSafeEqual(a, b)) return true;
        }
      } catch {
        // ignore URL parse failure
      }
    }
  }

  const cookies = parseCookies(req.headers.cookie);
  return verifySessionCookieValue(cookies[SESSION_COOKIE_NAME]);
}

export function setSessionCookie(req: Request, res: Response): void {
  const isHttps = req.secure || req.get('x-forwarded-proto') === 'https';
  res.cookie(SESSION_COOKIE_NAME, createSessionCookieValue(), {
    httpOnly: true,
    sameSite: 'lax',
    secure: isHttps,
    maxAge: SESSION_TTL_MS,
    path: '/',
  });
}

export function clearSessionCookie(res: Response): void {
  res.clearCookie(SESSION_COOKIE_NAME, { path: '/' });
}

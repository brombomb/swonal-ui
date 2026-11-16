import { Router } from 'express';
import { z } from 'zod';
import {
  clearSessionCookie,
  ensureApiToken,
  hashPasscode,
  isAuthEnabled,
  isDirectLocalhost,
  isLocalhostTrusted,
  isRequestAuthenticated,
  setSessionCookie,
  verifyPasscode,
} from '../services/authService';
import { updateRuntimeSettings } from '../services/runtimeSettings';

const failedAttempts = new Map<string, { count: number; lockedUntil: number }>();

export function createAuthRouter(): Router {
  const router = Router();

  router.get('/status', (req, res) => {
    const enabled = isAuthEnabled();
    const authenticated = isRequestAuthenticated(req);
    const trustLocalhost = isLocalhostTrusted();
    res.json({
      enabled,
      authenticated,
      trustLocalhost,
      isLocalhostBypass: enabled && trustLocalhost && isDirectLocalhost(req),
      envLocked: Boolean(process.env.SWONAL_AUTH_PASSCODE?.trim()),
    });
  });

  router.post('/login', (req, res, next) => {
    try {
      const ip = req.ip || req.socket.remoteAddress || 'unknown';
      const record = failedAttempts.get(ip);
      if (record && record.lockedUntil > Date.now()) {
        const retrySec = Math.ceil((record.lockedUntil - Date.now()) / 1000);
        res.status(429).json({ error: `Too many failed attempts. Try again in ${retrySec}s.` });
        return;
      }

      const schema = z.object({ passcode: z.string().min(1).max(200) });
      const { passcode } = schema.parse(req.body ?? {});

      if (!isAuthEnabled()) {
        res.json({ ok: true, enabled: false });
        return;
      }

      if (!verifyPasscode(passcode)) {
        const nextCount = (record?.count ?? 0) + 1;
        failedAttempts.set(ip, {
          count: nextCount,
          lockedUntil: nextCount >= 5 ? Date.now() + 60_000 : 0,
        });
        res.status(401).json({ error: 'Invalid passcode' });
        return;
      }

      failedAttempts.delete(ip);
      ensureApiToken();
      setSessionCookie(req, res);
      res.json({ ok: true, authenticated: true });
    } catch (err) {
      next(err);
    }
  });

  router.post('/logout', (_req, res) => {
    clearSessionCookie(res);
    res.json({ ok: true });
  });

  router.get('/token', (req, res) => {
    if (!isRequestAuthenticated(req)) {
      res.status(401).json({ error: 'Authentication required' });
      return;
    }
    const apiToken = ensureApiToken();
    res.json({ apiToken });
  });

  router.post('/configure', (req, res, next) => {
    try {
      if (!isRequestAuthenticated(req)) {
        res.status(401).json({ error: 'Authentication required' });
        return;
      }

      const schema = z.object({
        enabled: z.boolean().optional(),
        passcode: z.string().min(4).max(200).optional(),
        trustLocalhost: z.boolean().optional(),
      });
      const parsed = schema.parse(req.body ?? {});

      if (parsed.enabled === false) {
        updateRuntimeSettings({
          authPasscodeHash: null,
          ...(parsed.trustLocalhost !== undefined
            ? { authTrustLocalhost: parsed.trustLocalhost }
            : {}),
        });
        clearSessionCookie(res);
        res.json({
          enabled: isAuthEnabled(),
          trustLocalhost: isLocalhostTrusted(),
        });
        return;
      }

      if (parsed.passcode) {
        ensureApiToken();
        updateRuntimeSettings({
          authPasscodeHash: hashPasscode(parsed.passcode),
          ...(parsed.trustLocalhost !== undefined
            ? { authTrustLocalhost: parsed.trustLocalhost }
            : {}),
        });
        setSessionCookie(req, res);
      } else if (parsed.trustLocalhost !== undefined) {
        updateRuntimeSettings({ authTrustLocalhost: parsed.trustLocalhost });
      }

      res.json({
        enabled: isAuthEnabled(),
        trustLocalhost: isLocalhostTrusted(),
        apiToken: ensureApiToken(),
      });
    } catch (err) {
      next(err);
    }
  });

  return router;
}

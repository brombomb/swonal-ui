import crypto from 'crypto';
import { Router } from 'express';
import { getSpotifyConfig } from '../config';
import { prisma } from '../db';
import { musicRegistry } from '../music/Registry';
import { getEffectivePublicBaseUrl } from '../services/runtimeSettings';
import { sessionCoordinator } from '../services/sessionState';

const EPHEMERAL_STATE_SECRET = crypto.randomBytes(32).toString('hex');
const OAUTH_STATE_TTL_MS = 15 * 60 * 1000;

function getOAuthStateSecret(): string {
  return process.env.SPOTIFY_CLIENT_SECRET?.trim() || EPHEMERAL_STATE_SECRET;
}

function signStatePayload(payloadB64: string): string {
  return crypto
    .createHmac('sha256', getOAuthStateSecret())
    .update(payloadB64)
    .digest('base64url');
}

export function encodeOAuthState(userId: string, redirectUri: string): string {
  const payload = {
    userId,
    redirectUri,
    nonce: crypto.randomBytes(12).toString('base64url'),
    iat: Date.now(),
  };
  const payloadB64 = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url');
  const sig = signStatePayload(payloadB64);
  return `${payloadB64}.${sig}`;
}

export function decodeOAuthState(
  rawState: string
): { userId: string; redirectUri?: string } | null {
  if (!rawState) return null;
  const dotIdx = rawState.indexOf('.');
  if (dotIdx <= 0) return null;

  const payloadB64 = rawState.slice(0, dotIdx);
  const sig = rawState.slice(dotIdx + 1);
  const expectedSig = signStatePayload(payloadB64);

  const sigBuf = Buffer.from(sig, 'utf8');
  const expectedBuf = Buffer.from(expectedSig, 'utf8');
  if (sigBuf.length !== expectedBuf.length || !crypto.timingSafeEqual(sigBuf, expectedBuf)) {
    return null;
  }

  try {
    const parsed = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8')) as {
      userId?: string;
      redirectUri?: string;
      iat?: number;
    };
    if (
      !parsed ||
      typeof parsed.userId !== 'string' ||
      !parsed.userId ||
      typeof parsed.iat !== 'number' ||
      Date.now() - parsed.iat > OAUTH_STATE_TTL_MS
    ) {
      return null;
    }
    return { userId: parsed.userId, redirectUri: parsed.redirectUri };
  } catch {
    return null;
  }
}

function isTrustedBaseUrl(candidate: string, reqHost?: string): boolean {
  try {
    const parsed = new URL(candidate);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return false;
    }
    if (reqHost && parsed.host.toLowerCase() === reqHost.toLowerCase()) {
      return true;
    }
    const publicBase = getEffectivePublicBaseUrl();
    if (publicBase) {
      try {
        if (new URL(publicBase).origin === parsed.origin) return true;
      } catch {
        // ignore invalid configured publicBase
      }
    }
    const host = parsed.hostname.toLowerCase();
    return (
      host === 'localhost' ||
      host === '127.0.0.1' ||
      host === '::1' ||
      host.endsWith('.local') ||
      /^10\.\d+\.\d+\.\d+$/.test(host) ||
      /^192\.168\.\d+\.\d+$/.test(host) ||
      /^172\.(1[6-9]|2\d|3[0-1])\.\d+\.\d+$/.test(host)
    );
  } catch {
    return false;
  }
}

export function createSpotifyRouter(
  broadcastToClients: (msg: Record<string, unknown>) => void
): Router {
  const router = Router();

  const resolveUserId = (req: { query: Record<string, unknown> }): string => {
    if (typeof req.query.userId === 'string' && req.query.userId.trim()) {
      return req.query.userId.trim();
    }
    return sessionCoordinator.getState().activeUserId;
  };

  const resolveRedirectUri = (req: {
    protocol: string;
    query: Record<string, unknown>;
    get: (name: string) => string | undefined;
  }): string => {
    const baseOverride =
      typeof req.query.baseUrl === 'string'
        ? req.query.baseUrl.trim().replace(/\/+$/, '')
        : '';
    if (baseOverride && isTrustedBaseUrl(baseOverride, req.get('host'))) {
      return `${baseOverride}/api/spotify/callback`;
    }

    const publicBase = getEffectivePublicBaseUrl();
    if (publicBase) {
      return `${publicBase}/api/spotify/callback`;
    }

    const host = req.get('host');
    const isLocalHostHeader =
      !host || /^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$/i.test(host);

    const explicitRedirect = process.env.SPOTIFY_REDIRECT_URI?.trim() || '';
    const isExplicitLocalhost = /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?\//i.test(
      explicitRedirect
    );

    if (explicitRedirect && !(isExplicitLocalhost && !isLocalHostHeader)) {
      return explicitRedirect;
    }

    if (host) {
      return `${req.protocol}://${host}/api/spotify/callback`;
    }

    return getSpotifyConfig().redirectUri;
  };

  /**
   * GET /api/spotify/login?userId=<uuid>&baseUrl=<optional_override>
   * Generates the Spotify OAuth 2.0 Authorization Code URL and redirects the user to Spotify.
   */
  router.get('/login', async (req, res, next) => {
    try {
      const userId = resolveUserId(req);
      const redirectUri = resolveRedirectUri(req);
      const spotify = musicRegistry.get('spotify') as {
        getAuthUrl: (uid: string, redirectOverride?: string) => Promise<string>;
      };
      const rawAuthUrl = await spotify.getAuthUrl(userId, redirectUri);

      // Preserve redirectUri in OAuth state so /callback uses the exact same redirect_uri
      const parsedUrl = new URL(rawAuthUrl);
      parsedUrl.searchParams.set('state', encodeOAuthState(userId, redirectUri));

      res.redirect(parsedUrl.toString());
    } catch (err) {
      next(err);
    }
  });

  /**
   * GET /api/spotify/callback
   * Exchanges the authorization code for access & refresh tokens and upserts MusicIntegration in Prisma.
   */
  router.get('/callback', async (req, res, next) => {
    try {
      const oauthError = req.query.error ? String(req.query.error) : '';
      if (oauthError) {
        res.redirect(`/?spotify_error=${encodeURIComponent(oauthError)}`);
        return;
      }

      const code = req.query.code ? String(req.query.code) : '';
      const rawState = req.query.state ? String(req.query.state) : '';
      const decoded = decodeOAuthState(rawState);
      if (!decoded) {
        res.status(400).json({ error: 'Invalid or expired OAuth state' });
        return;
      }
      const userId = decoded.userId;
      const redirectUri = decoded.redirectUri || resolveRedirectUri(req);

      if (!code) {
        res.status(400).json({ error: 'Missing authorization code' });
        return;
      }

      const spotify = musicRegistry.get('spotify') as {
        handleAuthCallback: (uid: string, code: string, redirectOverride?: string) => Promise<void>;
      };
      await spotify.handleAuthCallback(userId, code, redirectUri);

      broadcastToClients({
        type: 'music_updated',
        userId,
        provider: 'spotify',
      });

      res.redirect('/?music_connected=1&provider=spotify');
    } catch (err) {
      next(err);
    }
  });

  /**
   * GET /api/spotify/status?userId=<uuid>&baseUrl=<optional_override>
   * Returns whether Spotify env credentials are configured and whether the user has a linked record.
   */
  router.get('/status', async (req, res, next) => {
    try {
      const userId = resolveUserId(req);
      const cfg = getSpotifyConfig();
      const integration = await prisma.musicIntegration.findUnique({
        where: { userId_provider: { userId, provider: 'spotify' } },
        select: { id: true, accessToken: true, preferredDeviceId: true },
      });
      res.json({
        configured: cfg.configured,
        redirectUri: resolveRedirectUri(req),
        connected: Boolean(integration),
        hasOAuthToken: Boolean(integration?.accessToken),
      });
    } catch (err) {
      next(err);
    }
  });

  /**
   * GET /api/spotify/token?userId=<uuid>
   * Returns a valid (refreshed if needed) Spotify access token for the Web Playback SDK.
   */
  router.get('/token', async (req, res, next) => {
    try {
      const userId = resolveUserId(req);
      const spotify = musicRegistry.get('spotify') as {
        getAccessToken?: (uid: string) => Promise<string>;
      };
      if (typeof spotify.getAccessToken !== 'function') {
        res.status(501).json({ error: 'Token provider unavailable' });
        return;
      }
      const accessToken = await spotify.getAccessToken(userId);
      res.json({ accessToken });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unable to retrieve Spotify token';
      if (message.includes('must be connected')) {
        res.status(401).json({ error: message });
        return;
      }
      next(err);
    }
  });

  return router;
}

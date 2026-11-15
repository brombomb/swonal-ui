import { Router } from 'express';
import { getSpotifyConfig } from '../config';
import { prisma } from '../db';
import { musicRegistry } from '../music/Registry';
import { getEffectivePublicBaseUrl } from '../services/runtimeSettings';
import { sessionCoordinator } from '../services/sessionState';

function encodeOAuthState(userId: string, redirectUri: string): string {
  return Buffer.from(JSON.stringify({ userId, redirectUri }), 'utf8').toString('base64url');
}

function decodeOAuthState(rawState: string): { userId: string; redirectUri?: string } {
  if (!rawState) {
    return { userId: sessionCoordinator.getState().activeUserId };
  }
  try {
    const parsed = JSON.parse(Buffer.from(rawState, 'base64url').toString('utf8')) as {
      userId?: string;
      redirectUri?: string;
    };
    if (parsed && typeof parsed.userId === 'string' && parsed.userId) {
      return { userId: parsed.userId, redirectUri: parsed.redirectUri };
    }
  } catch {
    // Plain userId string (legacy / test state)
  }
  return { userId: rawState };
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
    if (baseOverride && /^https?:\/\//i.test(baseOverride)) {
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
      const decoded = decodeOAuthState(req.query.state ? String(req.query.state) : '');
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

  return router;
}

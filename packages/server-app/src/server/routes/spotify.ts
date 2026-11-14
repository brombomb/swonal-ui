import { Router } from 'express';
import { getSpotifyConfig } from '../config';
import { prisma } from '../db';
import { musicRegistry } from '../music/Registry';
import { sessionCoordinator } from '../services/sessionState';

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

  /**
   * GET /api/spotify/login?userId=<uuid>
   * Generates the Spotify OAuth 2.0 Authorization Code URL and redirects the user to Spotify.
   */
  router.get('/login', async (req, res, next) => {
    try {
      const userId = resolveUserId(req);
      const spotify = musicRegistry.get('spotify');
      const authUrl = await spotify.getAuthUrl(userId);
      res.redirect(authUrl);
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
      const userId = req.query.state
        ? String(req.query.state)
        : sessionCoordinator.getState().activeUserId;

      if (!code) {
        res.status(400).json({ error: 'Missing authorization code' });
        return;
      }

      const spotify = musicRegistry.get('spotify');
      await spotify.handleAuthCallback(userId, code);

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
   * GET /api/spotify/status?userId=<uuid>
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
        redirectUri: cfg.redirectUri,
        connected: Boolean(integration),
        hasOAuthToken: Boolean(integration?.accessToken),
      });
    } catch (err) {
      next(err);
    }
  });

  return router;
}

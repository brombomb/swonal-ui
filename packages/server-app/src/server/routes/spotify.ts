import crypto from 'crypto';
import http from 'http';
import { Router } from 'express';
import { getSpotifyConfig } from '../config';
import { prisma } from '../db';
import { musicRegistry } from '../music/Registry';
import { getEffectivePublicBaseUrl } from '../services/runtimeSettings';
import { sessionCoordinator } from '../services/sessionState';

const KEYMASTER_CLIENT_ID = '65b708073fc0480ea92a077233ca87bd';
const KEYMASTER_REDIRECT_URI = 'http://127.0.0.1:8898/login';
const KEYMASTER_SCOPES = [
  'streaming',
  'user-read-playback-state',
  'user-modify-playback-state',
  'user-read-currently-playing',
  'playlist-read-private',
  'user-read-email',
  'user-read-private',
].join(' ');

interface PkceSession {
  userId: string;
  codeVerifier: string;
  createdAt: number;
}

let latestPkceSession: PkceSession | null = null;
const pkceSessionsByUser = new Map<string, PkceSession>();
let localCallbackServer: http.Server | null = null;

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
      const own = await prisma.musicIntegration.findUnique({
        where: { userId_provider: { userId, provider: 'spotify' } },
        select: { id: true, accessToken: true, preferredDeviceId: true },
      });
      const integration =
        own?.accessToken
          ? own
          : await prisma.musicIntegration.findFirst({
              where: { provider: 'spotify', accessToken: { not: null } },
              orderBy: { updatedAt: 'desc' },
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

  /**
   * POST /api/spotify/reset-rate-limit
   * GET /api/spotify/reset-rate-limit
   * Clears any active 429 backoff timer on SpotifyProvider.
   */
  const handleResetRateLimit = (_req: any, res: any) => {
    const spotify = musicRegistry.get('spotify') as { resetRateLimit?: () => void };
    if (typeof spotify?.resetRateLimit === 'function') {
      spotify.resetRateLimit();
      res.json({ success: true, message: 'Spotify rate limit reset' });
    } else {
      res.status(501).json({ error: 'Spotify provider unavailable' });
    }
  };
  router.post('/reset-rate-limit', handleResetRateLimit);
  router.get('/reset-rate-limit', handleResetRateLimit);

  const exchangeKeymasterPkceCode = async (userId: string, rawCodeOrUrl: string) => {
    let code = rawCodeOrUrl.trim();
    if (code.includes('code=')) {
      try {
        const parsed = new URL(code.startsWith('http') ? code : `http://127.0.0.1${code}`);
        code = parsed.searchParams.get('code') || code;
      } catch {
        const match = code.match(/[?&]code=([^&#\s]+)/);
        if (match?.[1]) code = decodeURIComponent(match[1]);
      }
    }
    if (!code) {
      throw new Error('Missing Spotify authorization code');
    }

    const session = pkceSessionsByUser.get(userId) || latestPkceSession;
    if (!session) {
      throw new Error('No active speaker pairing session found. Click "Pair Tonal Speakers" first.');
    }

    const resp = await fetch('https://accounts.spotify.com/api/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        client_id: KEYMASTER_CLIENT_ID,
        code,
        redirect_uri: KEYMASTER_REDIRECT_URI,
        code_verifier: session.codeVerifier,
      }),
    });

    if (!resp.ok) {
      throw new Error(`Speaker OAuth token exchange failed (${resp.status}): ${await resp.text()}`);
    }

    const data = (await resp.json()) as {
      access_token: string;
      refresh_token?: string;
      expires_in: number;
    };

    const spotify = musicRegistry.get('spotify') as {
      provisionTonalSpeakers?: (token?: string, reset?: boolean) => Promise<Record<string, unknown> | null>;
    };
    const speakerStatus =
      typeof spotify.provisionTonalSpeakers === 'function'
        ? await spotify.provisionTonalSpeakers(data.access_token, false)
        : null;

    // If the user has not linked Web API OAuth yet, also seed their MusicIntegration
    const existing = await prisma.musicIntegration.findUnique({
      where: { userId_provider: { userId: session.userId, provider: 'spotify' } },
    });
    if (!existing?.accessToken) {
      const tokenExpiry = Math.floor(Date.now() / 1000) + (data.expires_in || 3600);
      await prisma.musicIntegration.upsert({
        where: { userId_provider: { userId: session.userId, provider: 'spotify' } },
        update: {
          accessToken: data.access_token,
          refreshToken: data.refresh_token ?? undefined,
          tokenExpiry,
          preferredDeviceId: 'tonal-gym-librespot',
        },
        create: {
          userId: session.userId,
          provider: 'spotify',
          accessToken: data.access_token,
          refreshToken: data.refresh_token ?? null,
          tokenExpiry,
          preferredDeviceId: 'tonal-gym-librespot',
        },
      });
    }

    broadcastToClients({
      type: 'music_updated',
      userId: session.userId,
      provider: 'spotify',
    });

    return { speakerStatus, accessToken: data.access_token };
  };

  const ensureLocalCallbackListener = () => {
    if (localCallbackServer) return;
    try {
      const srv = http.createServer(async (req, res) => {
        try {
          const url = new URL(req.url || '/', KEYMASTER_REDIRECT_URI);
          if (url.pathname !== '/login') {
            res.writeHead(404);
            res.end('Not Found');
            return;
          }
          const code = url.searchParams.get('code') || '';
          const stateUserId = url.searchParams.get('state') || latestPkceSession?.userId || '';
          if (!code) {
            res.writeHead(400, { 'Content-Type': 'text/html; charset=utf-8' });
            res.end('<h3>Missing Spotify authorization code.</h3>');
            return;
          }
          await exchangeKeymasterPkceCode(stateUserId, code);
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
          res.end(
            '<!DOCTYPE html><html><body style="background:#0B0E14;color:#fff;font-family:sans-serif;text-align:center;padding:48px;">' +
              '<h2 style="color:#10B981;">Tonal Speakers Paired with Spotify!</h2>' +
              '<p>You can close this window and return to Swonal.</p>' +
              '<script>setTimeout(() => { window.close(); window.location.href = "http://localhost:3000/?music_connected=1"; }, 1200);</script>' +
              '</body></html>'
          );
        } catch (err) {
          res.writeHead(500, { 'Content-Type': 'text/html; charset=utf-8' });
          res.end(`<h3>Speaker pairing error: ${err instanceof Error ? err.message : String(err)}</h3>`);
        }
      });
      srv.on('error', () => {
        // Port 8898 already in use; ignore and rely on paste flow
      });
      srv.listen(8898, '127.0.0.1');
      srv.unref();
      localCallbackServer = srv;
    } catch {
      // ignore bind errors
    }
  };

  const buildSpeakerPkceUrl = (userId: string): string => {
    ensureLocalCallbackListener();
    const codeVerifier = crypto.randomBytes(48).toString('base64url');
    const codeChallenge = crypto.createHash('sha256').update(codeVerifier).digest('base64url');
    const session: PkceSession = { userId, codeVerifier, createdAt: Date.now() };
    latestPkceSession = session;
    pkceSessionsByUser.set(userId, session);

    const params = new URLSearchParams({
      client_id: KEYMASTER_CLIENT_ID,
      response_type: 'code',
      redirect_uri: KEYMASTER_REDIRECT_URI,
      code_challenge_method: 'S256',
      code_challenge: codeChallenge,
      scope: KEYMASTER_SCOPES,
      state: userId,
    });
    return `https://accounts.spotify.com/authorize?${params.toString()}`;
  };

  router.get('/speaker-status', async (_req, res) => {
    const spotify = musicRegistry.get('spotify') as {
      getTonalSpeakerStatus?: () => Promise<Record<string, unknown> | null>;
    };
    const st =
      typeof spotify.getTonalSpeakerStatus === 'function'
        ? await spotify.getTonalSpeakerStatus()
        : null;
    if (!st) {
      res.json({
        daemonOnline: false,
        running: false,
        hasCachedCredentials: false,
        authenticatedUser: null,
        deviceName: 'Tonal Speakers',
      });
      return;
    }
    res.json({
      daemonOnline: true,
      running: Boolean(st.running),
      hasCachedCredentials: Boolean(st.has_cached_credentials),
      authenticatedUser: st.authenticated_user ?? null,
      deviceName: st.device_name ?? 'Tonal Speakers',
      activeAudioClients: st.active_audio_clients ?? 0,
      isStreamingAudio: Boolean(st.is_streaming_audio),
      wlanIp: st.wlan_ip ?? null,
    });
  });

  router.get('/speaker-auth/url', (req, res) => {
    const userId = resolveUserId(req);
    const authUrl = buildSpeakerPkceUrl(userId);
    res.json({ authUrl, redirectUri: KEYMASTER_REDIRECT_URI });
  });

  router.get('/speaker-auth/start', (req, res) => {
    const userId = resolveUserId(req);
    const authUrl = buildSpeakerPkceUrl(userId);
    res.redirect(authUrl);
  });

  router.post('/speaker-auth/complete', async (req, res) => {
    try {
      const userId =
        typeof req.body?.userId === 'string' && req.body.userId.trim()
          ? req.body.userId.trim()
          : resolveUserId(req);
      const codeOrUrl = typeof req.body?.codeOrUrl === 'string' ? req.body.codeOrUrl : '';
      const out = await exchangeKeymasterPkceCode(userId, codeOrUrl);
      res.json({
        success: true,
        speakerStatus: out.speakerStatus,
      });
    } catch (err) {
      res.status(400).json({
        error: err instanceof Error ? err.message : 'Speaker pairing failed',
      });
    }
  });

  return router;
}

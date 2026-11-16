import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { AddressInfo } from 'net';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { prisma } from '../src/server/db';
import { getSpotifyConfig } from '../src/server/config';
import { encodeOAuthState } from '../src/server/routes/spotify';
import { SpotifyProvider } from '../src/server/music/providers/SpotifyProvider';
import { DEFAULT_USERS } from '../prisma/seed';

describe('Spotify OAuth Routes & Config Validation', () => {
  let handle: SwonalServerHandle;
  let baseUrl: string;
  const origClientId = process.env.SPOTIFY_CLIENT_ID;
  const origClientSecret = process.env.SPOTIFY_CLIENT_SECRET;
  const origRedirectUri = process.env.SPOTIFY_REDIRECT_URI;

  beforeAll(async () => {
    handle = await createSwonalServer({ port: 0, connectDaemonWs: false });
    const addr = handle.server.address() as AddressInfo;
    baseUrl = `http://127.0.0.1:${addr.port}`;
  });

  afterAll(async () => {
    process.env.SPOTIFY_CLIENT_ID = origClientId;
    process.env.SPOTIFY_CLIENT_SECRET = origClientSecret;
    process.env.SPOTIFY_REDIRECT_URI = origRedirectUri;
    await prisma.musicIntegration.deleteMany({
      where: { userId: DEFAULT_USERS.ROB_ID, provider: 'spotify' },
    });
    await handle.close();
  });

  it('validates server and Spotify config with /api/spotify/callback default and PUBLIC_URL override', () => {
    delete process.env.SPOTIFY_REDIRECT_URI;
    delete process.env.PUBLIC_URL;
    expect(getSpotifyConfig().redirectUri).toBe('http://localhost:3000/api/spotify/callback');

    process.env.PUBLIC_URL = 'https://swonal.brombomb.com';
    expect(getSpotifyConfig().redirectUri).toBe('https://swonal.brombomb.com/api/spotify/callback');
    delete process.env.PUBLIC_URL;

    process.env.SPOTIFY_CLIENT_ID = 'test-client-id';
    process.env.SPOTIFY_CLIENT_SECRET = 'test-client-secret';
    const spotifyCfg = getSpotifyConfig();
    expect(spotifyCfg.configured).toBe(true);
    expect(spotifyCfg.clientId).toBe('test-client-id');
  });

  it('GET /api/spotify/login redirects to Spotify authorize URL with required scopes', async () => {
    process.env.SPOTIFY_CLIENT_ID = 'demo-spotify-client';
    process.env.SPOTIFY_REDIRECT_URI = 'http://localhost:3000/api/spotify/callback';

    const resp = await fetch(
      `${baseUrl}/api/spotify/login?userId=${encodeURIComponent(DEFAULT_USERS.ROB_ID)}`,
      { redirect: 'manual' }
    );
    expect(resp.status).toBe(302);
    const location = resp.headers.get('location') || '';
    expect(location).toContain('https://accounts.spotify.com/authorize');
    expect(location).toContain('client_id=demo-spotify-client');
    const url = new URL(location);
    const rawState = url.searchParams.get('state') || '';
    const [payloadB64, sig] = rawState.split('.');
    expect(payloadB64).toBeTruthy();
    expect(sig).toBeTruthy();
    const decodedState = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8')) as {
      userId: string;
      redirectUri: string;
    };
    expect(decodedState.userId).toBe(DEFAULT_USERS.ROB_ID);
    expect(decodedState.redirectUri).toBe('http://localhost:3000/api/spotify/callback');

    const scope = url.searchParams.get('scope') || '';
    for (const s of [
      'user-read-playback-state',
      'user-modify-playback-state',
      'user-read-currently-playing',
      'playlist-read-private',
      'streaming',
    ]) {
      expect(scope).toContain(s);
    }
  });

  it('GET /api/spotify/callback rejects tampered state and accepts valid HMAC-signed state', async () => {
    const tamperedResp = await fetch(
      `${baseUrl}/api/spotify/callback?code=mock-spotify-code&state=${encodeURIComponent(
        DEFAULT_USERS.ROB_ID
      )}`,
      { redirect: 'manual' }
    );
    expect(tamperedResp.status).toBe(400);

    const signedState = encodeOAuthState(
      DEFAULT_USERS.ROB_ID,
      'http://localhost:3000/api/spotify/callback'
    );
    const resp = await fetch(
      `${baseUrl}/api/spotify/callback?code=mock-spotify-code&state=${encodeURIComponent(
        signedState
      )}`,
      { redirect: 'manual' }
    );
    expect(resp.status).toBe(302);
    expect(resp.headers.get('location')).toBe('/?music_connected=1&provider=spotify');

    const integration = await prisma.musicIntegration.findUnique({
      where: {
        userId_provider: { userId: DEFAULT_USERS.ROB_ID, provider: 'spotify' },
      },
    });
    expect(integration).not.toBeNull();
    expect(integration?.provider).toBe('spotify');

    const statusResp = await fetch(
      `${baseUrl}/api/spotify/status?userId=${encodeURIComponent(DEFAULT_USERS.ROB_ID)}`
    );
    expect(statusResp.status).toBe(200);
    const statusJson = (await statusResp.json()) as { connected: boolean; redirectUri: string };
    expect(statusJson.connected).toBe(true);

    // Rejects untrusted third-party baseUrl override
    const evilResp = await fetch(
      `${baseUrl}/api/spotify/status?userId=${encodeURIComponent(
        DEFAULT_USERS.ROB_ID
      )}&baseUrl=${encodeURIComponent('https://evil.example.com')}`
    );
    const evilJson = (await evilResp.json()) as { redirectUri: string };
    expect(evilJson.redirectUri).not.toContain('evil.example.com');

    // Accepts configured PUBLIC_URL override
    process.env.PUBLIC_URL = 'https://swonal.brombomb.com';
    const overrideStatusResp = await fetch(
      `${baseUrl}/api/spotify/status?userId=${encodeURIComponent(
        DEFAULT_USERS.ROB_ID
      )}&baseUrl=${encodeURIComponent('https://swonal.brombomb.com')}`
    );
    const overrideJson = (await overrideStatusResp.json()) as { redirectUri: string };
    expect(overrideJson.redirectUri).toBe('https://swonal.brombomb.com/api/spotify/callback');
    delete process.env.PUBLIC_URL;
  });

  it('SpotifyProvider.play defaults to librespot ("Tonal Gym") when no device is active and loads playlist tracks', async () => {
    await prisma.musicIntegration.upsert({
      where: { userId_provider: { userId: DEFAULT_USERS.ROB_ID, provider: 'spotify' } },
      update: {
        accessToken: 'mock-access-token',
        refreshToken: 'mock-refresh-token',
        tokenExpiry: Math.floor(Date.now() / 1000) + 3600,
      },
      create: {
        userId: DEFAULT_USERS.ROB_ID,
        provider: 'spotify',
        accessToken: 'mock-access-token',
        refreshToken: 'mock-refresh-token',
        tokenExpiry: Math.floor(Date.now() / 1000) + 3600,
      },
    });

    const playCalls: string[] = [];
    const origFetch = globalThis.fetch;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.endsWith('/me/player/devices')) {
        return new Response(
          JSON.stringify({
            devices: [
              { id: 'web-1', name: 'Swonal Web Player', type: 'Computer', is_active: false, volume_percent: 70 },
              { id: 'librespot-1', name: 'Tonal Gym', type: 'Speaker', is_active: false, volume_percent: 80 },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      if (url.includes('/me/player/play')) {
        playCalls.push(url);
        return new Response(null, { status: 204 });
      }
      if (url.includes('/playlists/pl-1/tracks')) {
        return new Response(
          JSON.stringify({
            items: [
              {
                track: {
                  id: 'tr-1',
                  name: 'Heavy Iron',
                  uri: 'spotify:track:tr1',
                  duration_ms: 195000,
                  artists: [{ name: 'Swonal Beats' }],
                  album: { name: 'PR Anthems', images: [{ url: 'https://img.test/a.jpg' }] },
                },
              },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return origFetch(input, init);
    });

    try {
      const provider = new SpotifyProvider();
      await provider.play(DEFAULT_USERS.ROB_ID, 'spotify:playlist:pl-1');
      expect(playCalls[0]).toContain('device_id=librespot-1');

      const tracks = await provider.getPlaylistTracks(DEFAULT_USERS.ROB_ID, 'pl-1');
      expect(tracks).toHaveLength(1);
      expect(tracks[0].name).toBe('Heavy Iron');
      expect(tracks[0].artistName).toBe('Swonal Beats');
    } finally {
      fetchSpy.mockRestore();
    }
  });

  it('enforces passcode auth for proxied requests when SWONAL_AUTH_PASSCODE is set', async () => {
    process.env.SWONAL_AUTH_PASSCODE = 'gym-lock-9999';
    try {
      const unauthResp = await fetch(`${baseUrl}/api/state`, {
        headers: { 'x-forwarded-for': '203.0.113.10' },
      });
      expect(unauthResp.status).toBe(401);

      const loginResp = await fetch(`${baseUrl}/api/auth/login`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-forwarded-for': '203.0.113.10',
        },
        body: JSON.stringify({ passcode: 'gym-lock-9999' }),
      });
      expect(loginResp.status).toBe(200);
      const cookie = loginResp.headers.get('set-cookie') || '';
      expect(cookie).toContain('swonal_session=');

      const authedResp = await fetch(`${baseUrl}/api/state`, {
        headers: {
          'x-forwarded-for': '203.0.113.10',
          cookie: cookie.split(';')[0],
        },
      });
      expect(authedResp.status).toBe(200);
    } finally {
      delete process.env.SWONAL_AUTH_PASSCODE;
    }
  });
});

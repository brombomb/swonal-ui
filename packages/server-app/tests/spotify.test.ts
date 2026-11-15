import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AddressInfo } from 'net';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { prisma } from '../src/server/db';
import { getServerConfig, getSpotifyConfig } from '../src/server/config';
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
    expect(location).toContain(`state=${encodeURIComponent(DEFAULT_USERS.ROB_ID)}`);

    const url = new URL(location);
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

  it('GET /api/spotify/callback upserts MusicIntegration and redirects with music_connected=1', async () => {
    const resp = await fetch(
      `${baseUrl}/api/spotify/callback?code=mock-spotify-code&state=${encodeURIComponent(
        DEFAULT_USERS.ROB_ID
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
    const statusJson = (await statusResp.json()) as { connected: boolean };
    expect(statusJson.connected).toBe(true);
  });
});

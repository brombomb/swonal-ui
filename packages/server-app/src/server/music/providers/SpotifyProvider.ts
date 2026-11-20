import { prisma } from '../../db';
import { getSpotifyConfig } from '../../config';
import {
  IMusicProvider,
  NormalizedAudioDevice,
  NormalizedPlaybackState,
  NormalizedPlaylist,
  NormalizedTrack,
  ProviderCapabilities,
} from '../MusicProvider';

const FALLBACK_PLAYLISTS: NormalizedPlaylist[] = [
  {
    id: '37i9dQZF1DXdLEN7aqioXM',
    name: 'Beast Mode',
    uri: 'spotify:playlist:37i9dQZF1DXdLEN7aqioXM',
    artworkUrl: 'https://i.scdn.co/image/ab67706f0000000292496e27be37ff7b120c1840',
    trackCount: 50,
  },
  {
    id: '37i9dQZF1DX76Wlfdnj7AP',
    name: 'Beast Mode Hip-Hop',
    uri: 'spotify:playlist:37i9dQZF1DX76Wlfdnj7AP',
    artworkUrl: 'https://i.scdn.co/image/ab67706f000000027f30013b190f70a169e5d4cb',
    trackCount: 50,
  },
  {
    id: '37i9dQZF1DXe6bg6qolagv',
    name: 'Workout Motivation',
    uri: 'spotify:playlist:37i9dQZF1DXe6bg6qolagv',
    artworkUrl: 'https://i.scdn.co/image/ab67706f000000021c2c317ff6e65bb6d3fa96a1',
    trackCount: 40,
  },
  {
    id: '37i9dQZF1DX70RN3TfWWJh',
    name: 'Workout Beats',
    uri: 'spotify:playlist:37i9dQZF1DX70RN3TfWWJh',
    artworkUrl: 'https://i.scdn.co/image/ab67706f000000028a3f8516be4c86fb1ebdbd45',
    trackCount: 45,
  },
  {
    id: '37i9dQZF1DWZq9KNPUuuAf',
    name: 'Rock Workout',
    uri: 'spotify:playlist:37i9dQZF1DWZq9KNPUuuAf',
    artworkUrl: 'https://i.scdn.co/image/ab67706f000000025fa2d58066f10842db13e536',
    trackCount: 50,
  },
  {
    id: '37i9dQZF1DX35Nm1nHV5vd',
    name: 'Power Workout',
    uri: 'spotify:playlist:37i9dQZF1DX35Nm1nHV5vd',
    artworkUrl: 'https://i.scdn.co/image/ab67706f00000002497eb1df308c1d5f3032ce93',
    trackCount: 50,
  },
  {
    id: '37i9dQZF1DX0XUsuxWHRQd',
    name: 'Rap Workout',
    uri: 'spotify:playlist:37i9dQZF1DX0XUsuxWHRQd',
    artworkUrl: 'https://i.scdn.co/image/ab67706f000000028d7ba37a3c3325c27ad6e6df',
    trackCount: 50,
  },
];

export class SpotifyProvider implements IMusicProvider {
  readonly id = 'spotify' as const;
  readonly displayName = 'Spotify Connect';
  readonly capabilities: ProviderCapabilities = {
    supportsConnectDevices: true,
    supportsVolumeDucking: true,
    supportsPlaylists: true,
  };

  private readonly activeDuckTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly playlistCache = new Map<string, { timestamp: number; playlists: NormalizedPlaylist[] }>();
  private rateLimitedUntil = 0;

  private getClientId(): string {
    return (process.env.SPOTIFY_CLIENT_ID || '').trim();
  }

  private getClientSecret(): string {
    return (process.env.SPOTIFY_CLIENT_SECRET || '').trim();
  }

  private getRedirectUri(): string {
    return getSpotifyConfig().redirectUri;
  }

  async getAuthUrl(userId: string, redirectOverride?: string): Promise<string> {
    const clientId = this.getClientId();
    const redirectUri = redirectOverride || this.getRedirectUri();
    const scopes = [
      'user-read-playback-state',
      'user-modify-playback-state',
      'user-read-currently-playing',
      'playlist-read-private',
      'streaming',
      'user-read-email',
      'user-read-private',
    ].join(' ');

    if (!clientId) {
      throw new Error('Spotify OAuth is not configured. Set SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET.');
    }

    const params = new URLSearchParams({
      response_type: 'code',
      client_id: clientId,
      scope: scopes,
      redirect_uri: redirectUri,
      state: userId,
    });
    return `https://accounts.spotify.com/authorize?${params.toString()}`;
  }

  async handleAuthCallback(userId: string, code: string, redirectOverride?: string): Promise<void> {
    const clientId = this.getClientId();
    const clientSecret = this.getClientSecret();
    const redirectUri = redirectOverride || this.getRedirectUri();

    if (!clientId || !clientSecret || code === 'mock-spotify-code') {
      await prisma.musicIntegration.upsert({
        where: { userId_provider: { userId, provider: this.id } },
        update: {
          accessToken: null,
          refreshToken: null,
        },
        create: {
          userId,
          provider: this.id,
        },
      });
      return;
    }

    const basicAuth = Buffer.from(`${clientId}:${clientSecret}`).toString('base64');
    const resp = await fetch('https://accounts.spotify.com/api/token', {
      method: 'POST',
      headers: {
        Authorization: `Basic ${basicAuth}`,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        code,
        redirect_uri: redirectUri,
      }),
    });

    if (!resp.ok) {
      throw new Error(`Spotify token exchange failed: ${resp.status} ${await resp.text()}`);
    }

    const data = (await resp.json()) as {
      access_token: string;
      refresh_token?: string;
      expires_in: number;
    };
    const tokenExpiry = Math.floor(Date.now() / 1000) + data.expires_in;

    await prisma.musicIntegration.upsert({
      where: { userId_provider: { userId, provider: this.id } },
      update: {
        accessToken: data.access_token,
        refreshToken: data.refresh_token ?? undefined,
        tokenExpiry,
      },
      create: {
        userId,
        provider: this.id,
        accessToken: data.access_token,
        refreshToken: data.refresh_token ?? null,
        tokenExpiry,
      },
    });
  }

  private async findIntegration(userId: string) {
    const own = await prisma.musicIntegration.findUnique({
      where: { userId_provider: { userId, provider: this.id } },
    });
    if (own?.accessToken) {
      return own;
    }
    const userExists = await prisma.user.findUnique({
      where: { id: userId },
      select: { id: true },
    });
    if (!userExists) {
      return own;
    }
    return prisma.musicIntegration.findFirst({
      where: { provider: this.id, accessToken: { not: null } },
      orderBy: { updatedAt: 'desc' },
    });
  }

  async refreshAuthIfNeeded(userId: string): Promise<string> {
    const integration = await this.findIntegration(userId);
    if (!integration?.accessToken) {
      return '';
    }

    const nowSec = Math.floor(Date.now() / 1000);
    if (integration.tokenExpiry && integration.tokenExpiry > nowSec + 60) {
      return integration.accessToken;
    }

    const clientId = this.getClientId();
    const clientSecret = this.getClientSecret();
    if (!clientId || !clientSecret || !integration.refreshToken) {
      return integration.accessToken;
    }

    const basicAuth = Buffer.from(`${clientId}:${clientSecret}`).toString('base64');
    const resp = await fetch('https://accounts.spotify.com/api/token', {
      method: 'POST',
      headers: {
        Authorization: `Basic ${basicAuth}`,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: integration.refreshToken,
      }),
    });

    if (!resp.ok) {
      throw new Error(`Spotify token refresh failed: ${resp.status}`);
    }

    const data = (await resp.json()) as {
      access_token: string;
      refresh_token?: string;
      expires_in: number;
    };
    const tokenExpiry = nowSec + data.expires_in;

    await prisma.musicIntegration.update({
      where: { id: integration.id },
      data: {
        accessToken: data.access_token,
        refreshToken: data.refresh_token ?? integration.refreshToken,
        tokenExpiry,
      },
    });

    return data.access_token;
  }

  public resetRateLimit(): void {
    this.rateLimitedUntil = 0;
    console.log('[SpotifyProvider] Rate limit manually reset.');
  }

  private async callSpotifyApi(
    userId: string,
    path: string,
    init: RequestInit = {}
  ): Promise<Response | null> {
    if (Date.now() < this.rateLimitedUntil) {
      return null;
    }
    const token = await this.refreshAuthIfNeeded(userId);
    if (!token) {
      return null;
    }
    const resp = await fetch(`https://api.spotify.com/v1${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        ...(init.headers || {}),
      },
    });
    if (!resp.ok && resp.status !== 204) {
      if (resp.status === 429) {
        const retryHeader = resp.headers.get('retry-after');
        const rawSec = retryHeader ? parseInt(retryHeader, 10) : 60;
        // Cap to 60s max backoff, never lock up for hours
        const safeSec = Math.min(Math.max(Number.isFinite(rawSec) ? rawSec : 60, 5), 60);
        this.rateLimitedUntil = Date.now() + safeSec * 1000;
        console.warn(
          `[SpotifyProvider] 429 Rate limit on ${path}. Backing off for ${safeSec}s (remote requested ${rawSec}s).`
        );
        return null;
      }
      let detail = '';
      try {
        const errBody = (await resp.json()) as {
          error?: { message?: string; reason?: string };
        };
        const reason = errBody?.error?.reason;
        const msg = errBody?.error?.message;
        if (reason || msg) {
          detail = `: ${[reason, msg].filter(Boolean).join(' - ')}`;
        }
      } catch {
        // ignore non-JSON error body
      }
      throw new Error(`Spotify API error (${resp.status}) on ${path}${detail}`);
    }
    return resp;
  }

  async getAccessToken(userId: string): Promise<string> {
    return this.requireToken(userId);
  }

  private async requireToken(userId: string): Promise<string> {
    const token = await this.refreshAuthIfNeeded(userId);
    if (!token) {
      throw new Error('Spotify must be connected to play music.');
    }
    return token;
  }

  async getPlaybackState(userId: string): Promise<NormalizedPlaybackState> {
    const resp = await this.callSpotifyApi(userId, '/me/player');
    if (resp && resp.status === 200) {
      const json = (await resp.json()) as {
        is_playing?: boolean;
        progress_ms?: number;
        device?: { id?: string; volume_percent?: number };
        item?: {
          name?: string;
          duration_ms?: number;
          artists?: Array<{ name: string }>;
          album?: { name?: string; images?: Array<{ url: string }> };
        };
      };
      return {
        isPlaying: Boolean(json.is_playing),
        trackName: json.item?.name ?? 'Spotify Connected',
        artistName: json.item?.artists?.map((a) => a.name).join(', ') ?? 'Spotify',
        albumName: json.item?.album?.name ?? '',
        artworkUrl: json.item?.album?.images?.[0]?.url,
        progressMs: json.progress_ms ?? 0,
        durationMs: json.item?.duration_ms ?? 0,
        activeDeviceId: json.device?.id,
        volumePct: json.device?.volume_percent ?? 75,
        isDucked: this.activeDuckTimers.has(userId),
        isConnected: true,
      };
    }

    if (resp && resp.status === 204) {
      return {
        isPlaying: false,
        trackName: 'Spotify Connected',
        artistName: 'Select a playlist or device to play',
        albumName: '',
        progressMs: 0,
        durationMs: 0,
        volumePct: 75,
        isDucked: this.activeDuckTimers.has(userId),
        isConnected: true,
      };
    }

    return {
      isPlaying: false,
      trackName: 'Music Service Not Connected',
      artistName: 'Connect a music service to play',
      albumName: '',
      progressMs: 0,
      durationMs: 0,
      volumePct: 72,
      isDucked: this.activeDuckTimers.has(userId),
      isConnected: false,
    };
  }

  private async resolveTargetDeviceId(
    userId: string,
    requestedDeviceId?: string,
    fallbackDeviceId?: string
  ): Promise<string | undefined> {
    if (requestedDeviceId) {
      return requestedDeviceId;
    }

    const devices = await this.getDevices(userId);
    const active = devices.find((d) => d.isActive);
    if (active) {
      return undefined;
    }

    // Prefer real Tonal Speakers when online
    const tonalSpeaker = devices.find((d) => /tonal\s*(gym|speakers?)|librespot/i.test(d.name) && d.id !== 'tonal-gym-librespot');
    if (tonalSpeaker) {
      return tonalSpeaker.id;
    }

    const integration = await this.findIntegration(userId);
    if (integration?.preferredDeviceId && integration.preferredDeviceId !== 'tonal-gym-librespot') {
      const preferred = devices.find((d) => d.id === integration.preferredDeviceId);
      if (preferred) {
        return preferred.id;
      }
    }

    const webPlayer = devices.find((d) => /swonal/i.test(d.name));
    if (webPlayer) {
      return webPlayer.id;
    }

    const nonSynthetic = devices.find((d) => d.id !== 'tonal-gym-librespot');
    if (nonSynthetic) {
      return nonSynthetic.id;
    }

    if (fallbackDeviceId && fallbackDeviceId !== 'tonal-gym-librespot') {
      return fallbackDeviceId;
    }

    return undefined;
  }

  async play(
    userId: string,
    contextUri?: string,
    deviceId?: string,
    fallbackDeviceId?: string
  ): Promise<void> {
    await this.requireToken(userId);
    const targetDeviceId = await this.resolveTargetDeviceId(userId, deviceId, fallbackDeviceId);
    const validTargetId = targetDeviceId && targetDeviceId !== 'tonal-gym-librespot' ? targetDeviceId : undefined;
    const qs = validTargetId ? `?device_id=${encodeURIComponent(validTargetId)}` : '';
    let body: string | undefined;
    if (contextUri) {
      body = contextUri.startsWith('spotify:track:')
        ? JSON.stringify({ uris: [contextUri] })
        : JSON.stringify({ context_uri: contextUri });
    }
    try {
      await this.callSpotifyApi(userId, `/me/player/play${qs}`, {
        method: 'PUT',
        body,
      });
    } catch (err: unknown) {
      const errMsg = err instanceof Error ? err.message : String(err);
      if (errMsg.includes('404') || errMsg.includes('NO_ACTIVE_DEVICE') || errMsg.includes('Device not found')) {
        throw new Error(
          'No active Spotify Connect target ready. Open Spotify on your phone/computer and select "Tonal Speakers" to begin.'
        );
      }
      throw err;
    }
  }

  async pause(userId: string): Promise<void> {
    await this.requireToken(userId);
    await this.callSpotifyApi(userId, '/me/player/pause', { method: 'PUT' });
  }

  async nextTrack(userId: string): Promise<void> {
    await this.requireToken(userId);
    await this.callSpotifyApi(userId, '/me/player/next', { method: 'POST' });
  }

  async previousTrack(userId: string): Promise<void> {
    await this.requireToken(userId);
    await this.callSpotifyApi(userId, '/me/player/previous', { method: 'POST' });
  }

  async setVolume(userId: string, volumePct: number): Promise<void> {
    await this.requireToken(userId);
    const clamped = Math.max(0, Math.min(100, Math.round(volumePct)));
    await this.callSpotifyApi(
      userId,
      `/me/player/volume?volume_percent=${clamped}`,
      { method: 'PUT' }
    );
  }

  async getPlaylists(userId: string): Promise<NormalizedPlaylist[]> {
    const integration = await this.findIntegration(userId);
    if (!integration?.accessToken && !integration?.refreshToken) {
      return [];
    }

    const cached = this.playlistCache.get(userId);
    if (cached && Date.now() - cached.timestamp < 10 * 60 * 1000) {
      return cached.playlists;
    }

    // Check DB persistent metadata cache before making network calls
    if (!cached && integration.metadataJson) {
      try {
        const meta = JSON.parse(integration.metadataJson) as { playlists?: NormalizedPlaylist[] };
        if (Array.isArray(meta.playlists) && meta.playlists.length > 0) {
          this.playlistCache.set(userId, { timestamp: Date.now(), playlists: meta.playlists });
          return meta.playlists;
        }
      } catch {
        // ignore parse error
      }
    }

    if (Date.now() >= this.rateLimitedUntil) {
      try {
        const resp = await this.callSpotifyApi(userId, '/me/playlists?limit=25');
        if (resp && resp.status === 200) {
          const json = (await resp.json()) as {
            items?: Array<{
              id: string;
              name: string;
              uri: string;
              images?: Array<{ url: string }>;
              tracks?: { total: number };
              items?: { total: number };
            }>;
          };
          const playlists = (json.items ?? []).map((item) => ({
            id: item.id,
            name: item.name,
            uri: item.uri,
            artworkUrl: item.images?.[0]?.url,
            trackCount: item.tracks?.total ?? item.items?.total ?? 0,
          }));
          if (playlists.length > 0) {
            this.playlistCache.set(userId, { timestamp: Date.now(), playlists });
            try {
              let meta: Record<string, unknown> = {};
              if (integration.metadataJson) meta = JSON.parse(integration.metadataJson);
              meta.playlists = playlists;
              await prisma.musicIntegration.update({
                where: { id: integration.id },
                data: { metadataJson: JSON.stringify(meta) },
              });
            } catch {
              // ignore db persistence error
            }
            return playlists;
          }
        }
      } catch (err: unknown) {
        console.warn('[SpotifyProvider] Failed to fetch remote playlists, returning cached/fallback:', err);
      }
    }

    if (cached && cached.playlists.length > 0) {
      return cached.playlists;
    }
    return FALLBACK_PLAYLISTS;
  }

  async getPlaylistTracks(userId: string, playlistId: string): Promise<NormalizedTrack[]> {
    const resp = await this.callSpotifyApi(
      userId,
      `/playlists/${encodeURIComponent(playlistId)}/tracks?limit=50`
    );
    if (!resp || resp.status !== 200) {
      return [];
    }
    const json = (await resp.json()) as {
      items?: Array<{
        track?: {
          id?: string | null;
          name?: string;
          uri?: string;
          duration_ms?: number;
          artists?: Array<{ name: string }>;
          album?: { name?: string; images?: Array<{ url: string }> };
        } | null;
      }>;
    };
    const tracks: NormalizedTrack[] = [];
    for (const entry of json.items ?? []) {
      const t = entry.track;
      if (!t || !t.id || !t.uri || !t.name) continue;
      tracks.push({
        id: t.id,
        name: t.name,
        uri: t.uri,
        artistName: (t.artists ?? []).map((a) => a.name).join(', ') || 'Unknown Artist',
        albumName: t.album?.name ?? '',
        durationMs: t.duration_ms ?? 0,
        artworkUrl: t.album?.images?.[0]?.url,
      });
    }
    return tracks;
  }

  async getDevices(userId: string): Promise<NormalizedAudioDevice[]> {
    const integration = await this.findIntegration(userId);
    if (!integration?.accessToken && !integration?.refreshToken) {
      return [];
    }

    let devices: NormalizedAudioDevice[] = [];
    try {
      const resp = await this.callSpotifyApi(userId, '/me/player/devices');
      if (resp && resp.status === 200) {
        const json = (await resp.json()) as {
          devices?: Array<{
            id: string;
            name: string;
            type: string;
            is_active: boolean;
            volume_percent: number;
          }>;
        };
        devices = (json.devices ?? []).map((d) => ({
          id: d.id,
          name: d.name
            .replace(/\s*\((?:librespot|on-device)\)/gi, '')
            .replace(/\blibrespot\b/gi, 'Tonal Speakers')
            .trim(),
          type: d.type,
          isActive: d.is_active,
          volumePct: d.volume_percent ?? 75,
        }));
      }
    } catch (err: unknown) {
      console.warn('[SpotifyProvider] Remote devices query failed:', err);
    }

    const hasTonalGym = devices.some((d) => /tonal\s*(gym|speakers?)|librespot/i.test(d.name));
    if (!hasTonalGym) {
      devices.unshift({
        id: 'tonal-gym-librespot',
        name: 'Tonal Speakers',
        type: 'Speaker',
        isActive: false,
        volumePct: 80,
      });
    }

    return devices;
  }

  async transferPlayback(userId: string, deviceId: string): Promise<void> {
    await this.requireToken(userId);
    let targetId = deviceId;
    if (deviceId === 'tonal-gym-librespot') {
      try {
        const resp = await this.callSpotifyApi(userId, '/me/player/devices');
        if (resp && resp.status === 200) {
          const json = (await resp.json()) as { devices?: Array<{ id: string; name: string }> };
          const found = (json.devices ?? []).find((d) => /tonal\s*(gym|speakers?)|librespot/i.test(d.name));
          if (found) targetId = found.id;
        }
      } catch {
        // ignore
      }
    }

    if (targetId !== 'tonal-gym-librespot') {
      try {
        await this.callSpotifyApi(userId, '/me/player', {
          method: 'PUT',
          body: JSON.stringify({ device_ids: [targetId], play: true }),
        });
      } catch (err) {
        console.warn(`[SpotifyProvider] transferPlayback to ${targetId} failed:`, err);
      }
    } else {
      // Save as preferred device preference for when speaker connects
      try {
        const integration = await this.findIntegration(userId);
        if (integration) {
          await prisma.musicIntegration.update({
            where: { id: integration.id },
            data: { preferredDeviceId: 'tonal-gym-librespot' },
          });
        }
      } catch {
        // ignore
      }
    }
  }

  async setPreferredDevice(userId: string, deviceId: string): Promise<void> {
    await prisma.musicIntegration.upsert({
      where: { userId_provider: { userId, provider: this.id } },
      update: { preferredDeviceId: deviceId },
      create: {
        userId,
        provider: this.id,
        preferredDeviceId: deviceId,
      },
    });
  }

  async duckVolume(userId: string, targetPct: number, durationMs: number): Promise<void> {
    const token = await this.refreshAuthIfNeeded(userId);
    if (!token) {
      return;
    }

    const existingTimer = this.activeDuckTimers.get(userId);
    if (existingTimer) {
      clearTimeout(existingTimer);
      this.activeDuckTimers.delete(userId);
    }

    const state = await this.getPlaybackState(userId);
    const originalVolume = state.volumePct ?? 72;
    const clampedTarget = Math.max(5, Math.min(originalVolume, Math.round(targetPct)));

    try {
      await this.setVolume(userId, clampedTarget);
    } catch (err: unknown) {
      // If the target device does not support remote volume control (e.g. VOLUME_CONTROL_DISALLOW),
      // record ducked state for the requested duration without failing the workout cue.
      if (err instanceof Error && err.message.includes('VOLUME_CONTROL_DISALLOW')) {
        const timer = setTimeout(() => {
          this.activeDuckTimers.delete(userId);
        }, Math.max(100, durationMs));
        if (typeof timer.unref === 'function') {
          timer.unref();
        }
        this.activeDuckTimers.set(userId, timer);
        return;
      }
      throw err;
    }

    const timer = setTimeout(() => {
      this.activeDuckTimers.delete(userId);
      void this.setVolume(userId, originalVolume).catch(() => {});
    }, Math.max(100, durationMs));

    if (typeof timer.unref === 'function') {
      timer.unref();
    }
    this.activeDuckTimers.set(userId, timer);
  }
}

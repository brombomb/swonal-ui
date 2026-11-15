import { prisma } from '../../db';
import { getSpotifyConfig } from '../../config';
import {
  IMusicProvider,
  NormalizedAudioDevice,
  NormalizedPlaybackState,
  NormalizedPlaylist,
  ProviderCapabilities,
} from '../MusicProvider';

export class SpotifyProvider implements IMusicProvider {
  readonly id = 'spotify' as const;
  readonly displayName = 'Spotify Connect';
  readonly capabilities: ProviderCapabilities = {
    supportsConnectDevices: true,
    supportsVolumeDucking: true,
    supportsPlaylists: true,
  };

  private readonly activeDuckTimers = new Map<string, ReturnType<typeof setTimeout>>();

  private getClientId(): string {
    return process.env.SPOTIFY_CLIENT_ID || '';
  }

  private getClientSecret(): string {
    return process.env.SPOTIFY_CLIENT_SECRET || '';
  }

  private getRedirectUri(): string {
    return getSpotifyConfig().redirectUri;
  }

  async getAuthUrl(userId: string): Promise<string> {
    const clientId = this.getClientId();
    const redirectUri = this.getRedirectUri();
    const scopes = [
      'user-read-playback-state',
      'user-modify-playback-state',
      'user-read-currently-playing',
      'playlist-read-private',
      'streaming',
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

  async handleAuthCallback(userId: string, code: string): Promise<void> {
    const clientId = this.getClientId();
    const clientSecret = this.getClientSecret();
    const redirectUri = this.getRedirectUri();

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

  async refreshAuthIfNeeded(userId: string): Promise<string> {
    const integration = await prisma.musicIntegration.findUnique({
      where: { userId_provider: { userId, provider: this.id } },
    });
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

  private async callSpotifyApi(
    userId: string,
    path: string,
    init: RequestInit = {}
  ): Promise<Response | null> {
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
      throw new Error(`Spotify API error (${resp.status}) on ${path}`);
    }
    return resp;
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
      isDucked: false,
      isConnected: false,
    };
  }

  async play(userId: string, contextUri?: string, deviceId?: string): Promise<void> {
    await this.requireToken(userId);
    const qs = deviceId ? `?device_id=${encodeURIComponent(deviceId)}` : '';
    await this.callSpotifyApi(userId, `/me/player/play${qs}`, {
      method: 'PUT',
      body: contextUri ? JSON.stringify({ context_uri: contextUri }) : undefined,
    });
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
    const resp = await this.callSpotifyApi(userId, '/me/playlists?limit=25');
    if (resp && resp.status === 200) {
      const json = (await resp.json()) as {
        items?: Array<{
          id: string;
          name: string;
          uri: string;
          images?: Array<{ url: string }>;
          tracks?: { total: number };
        }>;
      };
      return (json.items ?? []).map((item) => ({
        id: item.id,
        name: item.name,
        uri: item.uri,
        artworkUrl: item.images?.[0]?.url,
        trackCount: item.tracks?.total ?? 0,
      }));
    }

    return [];
  }

  async getDevices(userId: string): Promise<NormalizedAudioDevice[]> {
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
      return (json.devices ?? []).map((d) => ({
        id: d.id,
        name: d.name,
        type: d.type,
        isActive: d.is_active,
        volumePct: d.volume_percent ?? 75,
      }));
    }

    return [];
  }

  async transferPlayback(userId: string, deviceId: string): Promise<void> {
    await this.requireToken(userId);
    await this.callSpotifyApi(userId, '/me/player', {
      method: 'PUT',
      body: JSON.stringify({ device_ids: [deviceId], play: true }),
    });

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

    await this.setVolume(userId, clampedTarget);

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

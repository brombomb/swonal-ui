import { prisma } from '../../db';
import {
  IMusicProvider,
  NormalizedAudioDevice,
  NormalizedPlaybackState,
  NormalizedPlaylist,
  ProviderCapabilities,
} from '../MusicProvider';

interface SimTrack {
  trackName: string;
  artistName: string;
  albumName: string;
  durationMs: number;
}

interface SimUserSession {
  isPlaying: boolean;
  trackIndex: number;
  playlistId: string;
  progressMs: number;
  volumePct: number;
  isDucked: boolean;
  preDuckVolumePct: number;
  activeDeviceId: string;
  lastTickMs: number;
  restoreTimer?: ReturnType<typeof setTimeout>;
}

const SIM_PLAYLISTS: Array<NormalizedPlaylist & { tracks: SimTrack[] }> = [
  {
    id: 'pl-heavy-prs',
    name: 'Heavy Workout Mix',
    uri: 'spotify:playlist:swonal-heavy-prs',
    trackCount: 4,
    tracks: [
      { trackName: 'Obsidian Iron', artistName: 'Volt Runner', albumName: 'Peak Output', durationMs: 214000 },
      { trackName: 'Torque Vector', artistName: 'Kinetic Drive', albumName: 'Heavy Cycles', durationMs: 198000 },
      { trackName: 'Maximum Eccentric', artistName: 'Cyan Pulse', albumName: 'Gym Floors', durationMs: 226000 },
      { trackName: 'Zero Gravity PR', artistName: 'Apex Audio', albumName: 'Tonal Sessions', durationMs: 189000 },
    ],
  },
  {
    id: 'pl-hypertrophy',
    name: 'Hypertrophy Tempo 130 BPM',
    uri: 'spotify:playlist:swonal-hypertrophy',
    trackCount: 3,
    tracks: [
      { trackName: 'Chrome Pistons', artistName: 'Night Shift', albumName: 'Under Load', durationMs: 205000 },
      { trackName: 'Neon Cables', artistName: 'Static Form', albumName: 'Daylight Studio', durationMs: 232000 },
      { trackName: 'Barbell Horizon', artistName: 'Metro Synth', albumName: 'Compound Sets', durationMs: 210000 },
    ],
  },
  {
    id: 'pl-conditioning',
    name: 'Metabolic Burnout Circuit',
    uri: 'spotify:playlist:swonal-conditioning',
    trackCount: 3,
    tracks: [
      { trackName: 'Overdrive Interval', artistName: 'Pulse Lab', albumName: 'Tabata Pro', durationMs: 175000 },
      { trackName: 'Redline Sprint', artistName: 'Turbo Frame', albumName: 'Cardio Core', durationMs: 184000 },
      { trackName: 'Afterburner', artistName: 'Solaris', albumName: 'High Voltage', durationMs: 196000 },
    ],
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

  private readonly simSessions = new Map<string, SimUserSession>();
  private readonly activeDuckTimers = new Map<string, ReturnType<typeof setTimeout>>();

  private getClientId(): string {
    return process.env.SPOTIFY_CLIENT_ID || '';
  }

  private getClientSecret(): string {
    return process.env.SPOTIFY_CLIENT_SECRET || '';
  }

  private getRedirectUri(): string {
    return process.env.SPOTIFY_REDIRECT_URI || 'http://localhost:3000/api/spotify/callback';
  }

  private getOrCreateSimSession(userId: string): SimUserSession {
    let session = this.simSessions.get(userId);
    if (!session) {
      session = {
        isPlaying: false,
        trackIndex: 0,
        playlistId: SIM_PLAYLISTS[0].id,
        progressMs: 42000,
        volumePct: 72,
        isDucked: false,
        preDuckVolumePct: 72,
        activeDeviceId: 'tonal-librespot-01',
        lastTickMs: Date.now(),
      };
      this.simSessions.set(userId, session);
    }
    return session;
  }

  private advanceSimProgress(session: SimUserSession): SimTrack {
    const playlist =
      SIM_PLAYLISTS.find((p) => p.id === session.playlistId) ?? SIM_PLAYLISTS[0];
    const track = playlist.tracks[session.trackIndex % playlist.tracks.length];
    const now = Date.now();
    if (session.isPlaying) {
      const elapsed = now - session.lastTickMs;
      session.progressMs += elapsed;
      if (session.progressMs >= track.durationMs) {
        session.progressMs = session.progressMs % track.durationMs;
        session.trackIndex = (session.trackIndex + 1) % playlist.tracks.length;
      }
    }
    session.lastTickMs = now;
    return playlist.tracks[session.trackIndex % playlist.tracks.length];
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
      return `${redirectUri}?code=mock-spotify-code&state=${encodeURIComponent(userId)}`;
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
          preferredDeviceId: 'tonal-librespot-01',
        },
        create: {
          userId,
          provider: this.id,
          preferredDeviceId: 'tonal-librespot-01',
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
        preferredDeviceId: 'tonal-librespot-01',
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
    if (!integration.tokenExpiry || integration.tokenExpiry - nowSec > 60) {
      return integration.accessToken;
    }

    if (!integration.refreshToken || !this.getClientId() || !this.getClientSecret()) {
      return integration.accessToken;
    }

    const basicAuth = Buffer.from(
      `${this.getClientId()}:${this.getClientSecret()}`
    ).toString('base64');

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
        trackName: json.item?.name ?? 'Ready to Lift',
        artistName: json.item?.artists?.map((a) => a.name).join(', ') ?? 'Spotify',
        albumName: json.item?.album?.name ?? 'Spotify Connect',
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
        albumName: 'Spotify Connect',
        progressMs: 0,
        durationMs: 0,
        volumePct: 75,
        isDucked: this.activeDuckTimers.has(userId),
        isConnected: true,
      };
    }

    const existing = await prisma.musicIntegration.findUnique({
      where: { userId_provider: { userId, provider: this.id } },
      select: { id: true, preferredDeviceId: true },
    });

    const session = this.getOrCreateSimSession(userId);
    const track = this.advanceSimProgress(session);
    return {
      isPlaying: session.isPlaying,
      trackName: track.trackName,
      artistName: track.artistName,
      albumName: track.albumName,
      progressMs: session.progressMs,
      durationMs: track.durationMs,
      activeDeviceId: existing?.preferredDeviceId ?? session.activeDeviceId,
      volumePct: session.volumePct,
      isDucked: session.isDucked,
      isConnected: Boolean(existing),
    };
  }

  async play(userId: string, contextUri?: string, deviceId?: string): Promise<void> {
    const token = await this.refreshAuthIfNeeded(userId);
    if (token) {
      const qs = deviceId ? `?device_id=${encodeURIComponent(deviceId)}` : '';
      await this.callSpotifyApi(userId, `/me/player/play${qs}`, {
        method: 'PUT',
        body: contextUri ? JSON.stringify({ context_uri: contextUri }) : undefined,
      });
      return;
    }

    const session = this.getOrCreateSimSession(userId);
    if (contextUri) {
      const matched = SIM_PLAYLISTS.find((p) => p.uri === contextUri || p.id === contextUri);
      if (matched) {
        session.playlistId = matched.id;
        session.trackIndex = 0;
        session.progressMs = 0;
      }
    }
    if (deviceId) {
      session.activeDeviceId = deviceId;
    }
    session.isPlaying = true;
    session.lastTickMs = Date.now();
  }

  async pause(userId: string): Promise<void> {
    const token = await this.refreshAuthIfNeeded(userId);
    if (token) {
      await this.callSpotifyApi(userId, '/me/player/pause', { method: 'PUT' });
      return;
    }

    const session = this.getOrCreateSimSession(userId);
    this.advanceSimProgress(session);
    session.isPlaying = false;
  }

  async nextTrack(userId: string): Promise<void> {
    const token = await this.refreshAuthIfNeeded(userId);
    if (token) {
      await this.callSpotifyApi(userId, '/me/player/next', { method: 'POST' });
      return;
    }

    const session = this.getOrCreateSimSession(userId);
    const playlist =
      SIM_PLAYLISTS.find((p) => p.id === session.playlistId) ?? SIM_PLAYLISTS[0];
    session.trackIndex = (session.trackIndex + 1) % playlist.tracks.length;
    session.progressMs = 0;
    session.lastTickMs = Date.now();
  }

  async previousTrack(userId: string): Promise<void> {
    const token = await this.refreshAuthIfNeeded(userId);
    if (token) {
      await this.callSpotifyApi(userId, '/me/player/previous', { method: 'POST' });
      return;
    }

    const session = this.getOrCreateSimSession(userId);
    const playlist =
      SIM_PLAYLISTS.find((p) => p.id === session.playlistId) ?? SIM_PLAYLISTS[0];
    session.trackIndex =
      (session.trackIndex - 1 + playlist.tracks.length) % playlist.tracks.length;
    session.progressMs = 0;
    session.lastTickMs = Date.now();
  }

  async setVolume(userId: string, volumePct: number): Promise<void> {
    const clamped = Math.max(0, Math.min(100, Math.round(volumePct)));
    const token = await this.refreshAuthIfNeeded(userId);
    if (token) {
      await this.callSpotifyApi(
        userId,
        `/me/player/volume?volume_percent=${clamped}`,
        { method: 'PUT' }
      );
      return;
    }

    const session = this.getOrCreateSimSession(userId);
    session.volumePct = clamped;
    if (!session.isDucked) {
      session.preDuckVolumePct = clamped;
    }
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

    return SIM_PLAYLISTS.map(({ tracks: _tracks, ...meta }) => meta);
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

    const session = this.getOrCreateSimSession(userId);
    return [
      {
        id: 'tonal-librespot-01',
        name: 'Tonal Gym (librespot)',
        type: 'Speaker',
        isActive: session.activeDeviceId === 'tonal-librespot-01',
        volumePct: session.volumePct,
      },
      {
        id: 'gym-sonos-02',
        name: 'Garage Sonos Sub',
        type: 'Speaker',
        isActive: session.activeDeviceId === 'gym-sonos-02',
        volumePct: session.volumePct,
      },
    ];
  }

  async transferPlayback(userId: string, deviceId: string): Promise<void> {
    const token = await this.refreshAuthIfNeeded(userId);
    if (token) {
      await this.callSpotifyApi(userId, '/me/player', {
        method: 'PUT',
        body: JSON.stringify({ device_ids: [deviceId], play: true }),
      });
    } else {
      const session = this.getOrCreateSimSession(userId);
      session.activeDeviceId = deviceId;
    }

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
    const existingTimer = this.activeDuckTimers.get(userId);
    if (existingTimer) {
      clearTimeout(existingTimer);
      this.activeDuckTimers.delete(userId);
    }

    const state = await this.getPlaybackState(userId);
    const session = this.simSessions.get(userId);
    const originalVolume = session?.isDucked
      ? session.preDuckVolumePct
      : (state.volumePct ?? 72);

    const clampedTarget = Math.max(5, Math.min(originalVolume, Math.round(targetPct)));

    if (session) {
      session.preDuckVolumePct = originalVolume;
      session.isDucked = true;
    }

    await this.setVolume(userId, clampedTarget);

    const timer = setTimeout(() => {
      this.activeDuckTimers.delete(userId);
      if (session) {
        session.isDucked = false;
      }
      void this.setVolume(userId, originalVolume).catch(() => {});
    }, Math.max(100, durationMs));

    if (typeof timer.unref === 'function') {
      timer.unref();
    }
    this.activeDuckTimers.set(userId, timer);
  }
}

import {
  NormalizedAudioDevice,
  NormalizedPlaybackState,
  NormalizedPlaylist,
  NormalizedTrack,
} from '../MusicProvider';
import { getRuntimeSettings } from '../../services/runtimeSettings';
import { BaseSimulatedMusicProvider } from './BaseSimulatedMusicProvider';
import { haMediaPlayerBridge } from './HomeAssistantMediaPlayerBridge';

interface YtmResolvedConfig {
  apiUrl?: string;
  authToken?: string;
  haEntityId?: string;
}

/**
 * Real YouTube Music Adapter.
 * Supports:
 * 1. YouTube Music Desktop API Server (`th-ch/youtube-music` / Pear Desktop plugin, default `:26538`)
 *    via `/api/v1/song`, `/api/v1/play`, `/api/v1/pause`, `/api/v1/next`, `/api/v1/previous`,
 *    `/api/v1/volume`, and `/api/v1/queue`.
 * 2. Home Assistant `media_player.*` entity bridge (when `haEntityId` is configured).
 */
export class YouTubeMusicProvider extends BaseSimulatedMusicProvider {
  private readonly duckTimers = new Map<string, ReturnType<typeof setTimeout>>();

  constructor() {
    super({
      id: 'youtube_music',
      displayName: 'YouTube Music',
      capabilities: {
        supportsConnectDevices: true,
        supportsVolumeDucking: true,
        supportsPlaylists: true,
      },
      defaultVolumePct: 70,
      tracks: [
        { trackName: 'YT Workout Mix #1', artistName: 'YouTube Music', albumName: 'High Energy' },
        { trackName: 'Strength Cadence', artistName: 'Gym Radio', albumName: 'PR Sessions' },
        { trackName: 'Barbell Drive', artistName: 'Synth Lifting', albumName: 'Night Gym' },
      ],
      playlists: [
        { id: 'ytm-lift', name: 'YouTube Heavy Lift Mix', uri: 'ytm:playlist:lift', trackCount: 25 },
        { id: 'ytm-focus', name: 'Zero Distraction Strength', uri: 'ytm:playlist:focus', trackCount: 18 },
      ],
    });
  }

  getResolvedConfig(): YtmResolvedConfig {
    const saved = getRuntimeSettings().musicProviders?.youtube_music;
    return {
      apiUrl: (saved?.apiUrl || process.env.YTM_API_URL || '').trim().replace(/\/+$/, '') || undefined,
      authToken: (saved?.authToken || process.env.YTM_AUTH_TOKEN || '').trim() || undefined,
      haEntityId: (saved?.haEntityId || process.env.YTM_HA_ENTITY_ID || '').trim() || undefined,
    };
  }

  isLiveConfigured(): boolean {
    const cfg = this.getResolvedConfig();
    return Boolean(cfg.apiUrl || haMediaPlayerBridge.isReady(cfg.haEntityId));
  }

  private apiHeaders(token?: string): Record<string, string> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers.Authorization = `Bearer ${token}`;
    return headers;
  }

  private async ytmFetch(path: string, init: RequestInit = {}): Promise<Response> {
    const cfg = this.getResolvedConfig();
    if (!cfg.apiUrl) throw new Error('YouTube Music API URL is not configured');
    const res = await fetch(`${cfg.apiUrl}${path}`, {
      ...init,
      headers: {
        ...this.apiHeaders(cfg.authToken),
        ...(init.headers as Record<string, string> | undefined),
      },
      signal: AbortSignal.timeout(3500),
    });
    if (!res.ok) {
      throw new Error(`YouTube Music API error (${res.status}) on ${path}`);
    }
    return res;
  }

  override async getPlaybackState(userId: string): Promise<NormalizedPlaybackState> {
    const cfg = this.getResolvedConfig();
    if (cfg.apiUrl) {
      try {
        const res = await this.ytmFetch('/api/v1/song');
        const song = (await res.json()) as {
          title?: string;
          artist?: string;
          album?: string | null;
          imageSrc?: string | null;
          isPaused?: boolean;
          elapsedSeconds?: number;
          songDuration?: number;
        };
        let volPct = 70;
        try {
          const volRes = await this.ytmFetch('/api/v1/volume');
          const volJson = (await volRes.json()) as { state?: number; volume?: number };
          const rawVol = volJson.state ?? volJson.volume;
          if (typeof rawVol === 'number') volPct = Math.max(0, Math.min(100, Math.round(rawVol)));
        } catch {
          // Optional volume endpoint
        }
        return {
          isPlaying: Boolean(song.title) && song.isPaused === false,
          trackName: song.title || 'Not Playing',
          artistName: song.artist || 'YouTube Music',
          albumName: song.album || '',
          artworkUrl: song.imageSrc || undefined,
          progressMs: typeof song.elapsedSeconds === 'number' ? Math.round(song.elapsedSeconds * 1000) : 0,
          durationMs: typeof song.songDuration === 'number' ? Math.round(song.songDuration * 1000) : 0,
          activeDeviceId: cfg.apiUrl,
          volumePct: volPct,
          isDucked: this.duckTimers.has(userId),
          isConnected: true,
        };
      } catch {
        return {
          isPlaying: false,
          trackName: 'YouTube Music Offline',
          artistName: cfg.apiUrl,
          albumName: '',
          progressMs: 0,
          durationMs: 0,
          volumePct: 70,
          isConnected: false,
        };
      }
    }

    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      return haMediaPlayerBridge.getPlaybackState(userId, cfg.haEntityId!);
    }

    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') {
      return super.getPlaybackState(userId);
    }

    return {
      isPlaying: false,
      trackName: 'YouTube Music Not Configured',
      artistName: 'Configure API Server or Home Assistant player in Settings',
      albumName: '',
      progressMs: 0,
      durationMs: 0,
      volumePct: 70,
      isConnected: false,
    };
  }

  override async play(userId: string, contextUri?: string, deviceId?: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (cfg.apiUrl) {
      if (contextUri?.startsWith('ytm:queue:')) {
        const index = Number(contextUri.slice('ytm:queue:'.length));
        if (Number.isInteger(index) && index >= 0) {
          await this.ytmFetch('/api/v1/queue', {
            method: 'PATCH',
            body: JSON.stringify({ index }),
          });
          return;
        }
      }
      await this.ytmFetch('/api/v1/play', { method: 'POST' });
      return;
    }
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.play(userId, cfg.haEntityId!, contextUri, deviceId);
      return;
    }
    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') return super.play(userId, contextUri, deviceId);
    throw new Error('YouTube Music must be configured in Application Settings first.');
  }

  override async pause(userId: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (cfg.apiUrl) {
      await this.ytmFetch('/api/v1/pause', { method: 'POST' });
      return;
    }
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.pause(userId, cfg.haEntityId!);
      return;
    }
    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') return super.pause(userId);
    throw new Error('YouTube Music must be configured in Application Settings first.');
  }

  override async nextTrack(userId: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (cfg.apiUrl) {
      await this.ytmFetch('/api/v1/next', { method: 'POST' });
      return;
    }
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.nextTrack(userId, cfg.haEntityId!);
      return;
    }
    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') return super.nextTrack(userId);
    throw new Error('YouTube Music must be configured in Application Settings first.');
  }

  override async previousTrack(userId: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (cfg.apiUrl) {
      await this.ytmFetch('/api/v1/previous', { method: 'POST' });
      return;
    }
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.previousTrack(userId, cfg.haEntityId!);
      return;
    }
    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') return super.previousTrack(userId);
    throw new Error('YouTube Music must be configured in Application Settings first.');
  }

  override async setVolume(userId: string, volumePct: number): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (cfg.apiUrl) {
      const clamped = Math.max(0, Math.min(100, Math.round(volumePct)));
      await this.ytmFetch('/api/v1/volume', {
        method: 'POST',
        body: JSON.stringify({ volume: clamped }),
      });
      return;
    }
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.setVolume(userId, cfg.haEntityId!, volumePct);
      return;
    }
    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') return super.setVolume(userId, volumePct);
    throw new Error('YouTube Music must be configured in Application Settings first.');
  }

  override async duckVolume(userId: string, arg1 = 25, arg2 = 1500): Promise<void> {
    const [targetPct, durationMs] = arg1 > 100 ? [arg2, arg1] : [arg1, arg2];
    const cfg = this.getResolvedConfig();
    if (cfg.apiUrl) {
      const st = await this.getPlaybackState(userId);
      const prev = st.volumePct ?? 70;
      await this.setVolume(userId, Math.min(prev, targetPct));
      const existing = this.duckTimers.get(userId);
      if (existing) clearTimeout(existing);
      const timer = setTimeout(() => {
        this.duckTimers.delete(userId);
        void this.setVolume(userId, prev).catch(() => {});
      }, Math.min(Math.max(durationMs, 400), 5000));
      if (typeof timer.unref === 'function') timer.unref();
      this.duckTimers.set(userId, timer);
      return;
    }
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.duckVolume(userId, cfg.haEntityId!, targetPct, durationMs);
      return;
    }
    return super.duckVolume(userId, durationMs, targetPct);
  }

  override async getPlaylists(userId: string): Promise<NormalizedPlaylist[]> {
    const cfg = this.getResolvedConfig();
    if (cfg.apiUrl) {
      try {
        const tracks = await this.getQueueTracks();
        return [
          {
            id: 'ytm-queue',
            name: 'Up Next Queue',
            uri: 'ytm:queue:0',
            trackCount: tracks.length,
          },
        ];
      } catch {
        return [];
      }
    }
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      return haMediaPlayerBridge.getSourcePlaylists(userId, cfg.haEntityId!);
    }
    return process.env.ENABLE_SIMULATED_MUSIC === 'true' ? super.getPlaylists(userId) : [];
  }

  private async getQueueTracks(): Promise<NormalizedTrack[]> {
    const res = await this.ytmFetch('/api/v1/queue');
    const data = (await res.json()) as {
      items?: Array<{
        playlistPanelVideoRenderer?: {
          videoId?: string;
          title?: { runs?: Array<{ text?: string }> };
          longBylineText?: { runs?: Array<{ text?: string }> };
        };
      }>;
    };
    const items = data.items ?? [];
    return items.map((entry, idx) => {
      const r = entry.playlistPanelVideoRenderer;
      return {
        id: r?.videoId || `q-${idx}`,
        name: r?.title?.runs?.[0]?.text || `Track ${idx + 1}`,
        uri: `ytm:queue:${idx}`,
        artistName: r?.longBylineText?.runs?.[0]?.text || 'YouTube Music',
        albumName: '',
        durationMs: 0,
      };
    });
  }

  async getPlaylistTracks(_userId: string, playlistId: string): Promise<NormalizedTrack[]> {
    const cfg = this.getResolvedConfig();
    if (cfg.apiUrl && playlistId === 'ytm-queue') {
      return this.getQueueTracks();
    }
    return [];
  }

  override async getDevices(userId: string): Promise<NormalizedAudioDevice[]> {
    const cfg = this.getResolvedConfig();
    if (cfg.apiUrl) {
      return [
        {
          id: cfg.apiUrl,
          name: 'YouTube Music Desktop',
          type: 'Computer',
          isActive: true,
          volumePct: 70,
        },
      ];
    }
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      return haMediaPlayerBridge.getDevices(userId, cfg.haEntityId!);
    }
    return process.env.ENABLE_SIMULATED_MUSIC === 'true' ? super.getDevices(userId) : [];
  }

  override async transferPlayback(userId: string, deviceId: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      haMediaPlayerBridge.setSelectedEntity(userId, deviceId);
      return;
    }
    return super.transferPlayback(userId, deviceId);
  }

  async testConnection(): Promise<{ ok: boolean; message: string }> {
    const cfg = this.getResolvedConfig();
    if (cfg.apiUrl) {
      const res = await this.ytmFetch('/api/v1/song');
      const song = (await res.json()) as { title?: string };
      return {
        ok: true,
        message: `Connected to YouTube Music API (${song.title ? `Now playing: ${song.title}` : 'idle'})`,
      };
    }
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      return haMediaPlayerBridge.testConnection(cfg.haEntityId!);
    }
    return {
      ok: false,
      message: 'Configure a YouTube Music Desktop API URL or Home Assistant media_player entity first.',
    };
  }
}

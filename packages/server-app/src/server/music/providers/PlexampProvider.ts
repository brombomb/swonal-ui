import {
  NormalizedAudioDevice,
  NormalizedPlaybackState,
  NormalizedPlaylist,
  NormalizedTrack,
} from '../MusicProvider';
import { getRuntimeSettings } from '../../services/runtimeSettings';
import { BaseSimulatedMusicProvider } from './BaseSimulatedMusicProvider';

interface PlexampResolvedConfig {
  plexampUrl?: string;
  plexServerUrl?: string;
  plexToken?: string;
}

function xmlAttr(tag: string, name: string): string | undefined {
  const m = tag.match(new RegExp(`${name}="([^"]*)"`, 'i'));
  return m ? m[1] : undefined;
}

/**
 * Real Plexamp + Plex Media Server Adapter.
 * - Controls Headless Plexamp (default port 32500) via Plex Remote Control HTTP API
 *   (`/player/timeline/poll`, `/player/playback/play|pause|skipNext|skipPrevious|setParameters|playMedia`).
 * - Reads real audio playlists, tracks, and active players from Plex Media Server (`:32400`) when configured.
 * - Falls back cleanly to BaseSimulatedMusicProvider only when `ENABLE_SIMULATED_MUSIC=true`.
 */
export class PlexampProvider extends BaseSimulatedMusicProvider {
  private commandId = 1;
  private readonly duckTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly selectedPlayerUrlByUser = new Map<string, string>();
  private cachedMachineId: string | null = null;

  constructor() {
    super({
      id: 'plexamp',
      displayName: 'Plexamp',
      capabilities: {
        supportsConnectDevices: true,
        supportsVolumeDucking: true,
        supportsPlaylists: true,
      },
      defaultVolumePct: 74,
      tracks: [
        { trackName: 'FLAC Powerlifting Mix', artistName: 'Home Plex Library', albumName: 'Lossless Gym' },
        { trackName: 'Garage Iron Sessions', artistName: 'Local Archive', albumName: '24-Bit Studio' },
        { trackName: 'Sonic Eccentric', artistName: 'Synthwave Vault', albumName: 'Plexamp Radio' },
      ],
      playlists: [
        { id: 'plex-lossless', name: 'Lossless Gym Favorites', uri: 'plex:playlist:lossless', trackCount: 42 },
        { id: 'plex-radio', name: 'Plexamp Library Radio', uri: 'plex:playlist:radio', trackCount: 100 },
      ],
      devices: [
        {
          id: 'plexamp-headless',
          name: 'Home Gym Plexamp (Headless)',
          type: 'Speaker',
          isActive: true,
          volumePct: 74,
        },
      ],
    });
  }

  getResolvedConfig(): PlexampResolvedConfig {
    const saved = getRuntimeSettings().musicProviders?.plexamp;
    return {
      plexampUrl: (saved?.plexampUrl || process.env.PLEXAMP_URL || '').trim().replace(/\/+$/, '') || undefined,
      plexServerUrl: (saved?.plexServerUrl || process.env.PLEX_SERVER_URL || '').trim().replace(/\/+$/, '') || undefined,
      plexToken: (saved?.plexToken || process.env.PLEX_TOKEN || '').trim() || undefined,
    };
  }

  isLiveConfigured(): boolean {
    const cfg = this.getResolvedConfig();
    return Boolean(cfg.plexampUrl || (cfg.plexServerUrl && cfg.plexToken));
  }

  private getPlayerBaseUrl(userId: string): string | undefined {
    return this.selectedPlayerUrlByUser.get(userId) || this.getResolvedConfig().plexampUrl;
  }

  private plexHeaders(token?: string): Record<string, string> {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      'X-Plex-Client-Identifier': 'swonal-kiosk-controller',
      'X-Plex-Product': 'Swonal',
      'X-Plex-Version': '1.0',
    };
    if (token) headers['X-Plex-Token'] = token;
    return headers;
  }

  private async dispatchPlexamp(userId: string, endpoint: string): Promise<Response> {
    const baseUrl = this.getPlayerBaseUrl(userId);
    if (!baseUrl) {
      throw new Error('Plexamp Player URL is not configured');
    }
    const sep = endpoint.includes('?') ? '&' : '?';
    const url = `${baseUrl}${endpoint}${sep}commandID=${this.commandId++}`;
    const res = await fetch(url, {
      method: 'GET',
      headers: this.plexHeaders(this.getResolvedConfig().plexToken),
      signal: AbortSignal.timeout(3500),
    });
    if (!res.ok) {
      throw new Error(`Plexamp error (${res.status}) on ${endpoint}`);
    }
    return res;
  }

  private async getServerMachineId(): Promise<string | null> {
    if (this.cachedMachineId) return this.cachedMachineId;
    const { plexServerUrl, plexToken } = this.getResolvedConfig();
    if (!plexServerUrl || !plexToken) return null;
    try {
      const res = await fetch(`${plexServerUrl}/identity`, {
        headers: this.plexHeaders(plexToken),
        signal: AbortSignal.timeout(3000),
      });
      if (!res.ok) return null;
      const text = await res.text();
      if (text.trim().startsWith('{')) {
        const parsed = JSON.parse(text) as { MediaContainer?: { machineIdentifier?: string } };
        this.cachedMachineId = parsed.MediaContainer?.machineIdentifier ?? null;
      } else {
        this.cachedMachineId = xmlAttr(text, 'machineIdentifier') ?? null;
      }
      return this.cachedMachineId;
    } catch {
      return null;
    }
  }

  override async getPlaybackState(userId: string): Promise<NormalizedPlaybackState> {
    if (!this.isLiveConfigured()) {
      return super.getPlaybackState(userId);
    }

    const playerUrl = this.getPlayerBaseUrl(userId);
    if (!playerUrl) {
      return {
        isPlaying: false,
        trackName: 'Ready on Plex Server',
        artistName: 'Configure Plexamp Player URL for live transport',
        albumName: '',
        progressMs: 0,
        durationMs: 0,
        volumePct: 70,
        isConnected: true,
      };
    }

    try {
      const res = await this.dispatchPlexamp(userId, '/player/timeline/poll?wait=0');
      const body = await res.text();
      const musicTag =
        body.match(/<Timeline[^>]*type="music"[^>]*>/i)?.[0] ??
        body.match(/<Timeline[^>]*>/i)?.[0] ??
        '';
      const stateStr = xmlAttr(musicTag, 'state') ?? 'stopped';
      const timeMs = Number(xmlAttr(musicTag, 'time') ?? 0);
      const durationMs = Number(xmlAttr(musicTag, 'duration') ?? 0);
      const volumePct = Number(xmlAttr(musicTag, 'volume') ?? 74);
      const trackTag = body.match(/<Track[^>]*>/i)?.[0] ?? '';
      const trackName = xmlAttr(trackTag, 'title') || (stateStr === 'playing' ? 'Playing on Plexamp' : 'Not Playing');
      const artistName = xmlAttr(trackTag, 'grandparentTitle') || xmlAttr(trackTag, 'originalTitle') || 'Plexamp';
      const albumName = xmlAttr(trackTag, 'parentTitle') || '';
      const thumb = xmlAttr(trackTag, 'thumb');
      const { plexServerUrl, plexToken } = this.getResolvedConfig();
      const artworkUrl =
        thumb && plexServerUrl && plexToken
          ? `${plexServerUrl}${thumb}?X-Plex-Token=${encodeURIComponent(plexToken)}`
          : undefined;

      return {
        isPlaying: stateStr === 'playing',
        trackName,
        artistName,
        albumName,
        artworkUrl,
        progressMs: Number.isFinite(timeMs) ? timeMs : 0,
        durationMs: Number.isFinite(durationMs) ? durationMs : 0,
        activeDeviceId: playerUrl,
        volumePct: Number.isFinite(volumePct) ? Math.max(0, Math.min(100, Math.round(volumePct))) : 74,
        isDucked: this.duckTimers.has(userId),
        isConnected: true,
      };
    } catch {
      return {
        isPlaying: false,
        trackName: 'Plexamp Offline',
        artistName: playerUrl,
        albumName: '',
        progressMs: 0,
        durationMs: 0,
        activeDeviceId: playerUrl,
        volumePct: 74,
        isConnected: false,
      };
    }
  }

  override async play(userId: string, contextUri?: string, deviceId?: string): Promise<void> {
    if (!this.isLiveConfigured()) {
      return super.play(userId, contextUri, deviceId);
    }
    if (deviceId && /^https?:\/\//i.test(deviceId)) {
      this.selectedPlayerUrlByUser.set(userId, deviceId);
    }

    if (contextUri && contextUri.startsWith('plex:key:')) {
      const key = contextUri.slice('plex:key:'.length);
      const { plexServerUrl, plexToken } = this.getResolvedConfig();
      if (plexServerUrl && plexToken) {
        const parsed = new URL(plexServerUrl);
        const machineId = await this.getServerMachineId();
        const params = new URLSearchParams({
          key,
          type: 'music',
          protocol: parsed.protocol.replace(':', ''),
          address: parsed.hostname,
          port: parsed.port || (parsed.protocol === 'https:' ? '443' : '32400'),
          token: plexToken,
        });
        if (machineId) params.set('machineIdentifier', machineId);
        await this.dispatchPlexamp(userId, `/player/playback/playMedia?${params.toString()}`);
        return;
      }
    }

    await this.dispatchPlexamp(userId, '/player/playback/play?type=music');
  }

  override async pause(userId: string): Promise<void> {
    if (!this.isLiveConfigured()) {
      return super.pause(userId);
    }
    await this.dispatchPlexamp(userId, '/player/playback/pause?type=music');
  }

  override async nextTrack(userId: string): Promise<void> {
    if (!this.isLiveConfigured()) {
      return super.nextTrack(userId);
    }
    await this.dispatchPlexamp(userId, '/player/playback/skipNext?type=music');
  }

  override async previousTrack(userId: string): Promise<void> {
    if (!this.isLiveConfigured()) {
      return super.previousTrack(userId);
    }
    await this.dispatchPlexamp(userId, '/player/playback/skipPrevious?type=music');
  }

  override async setVolume(userId: string, volumePct: number): Promise<void> {
    if (!this.isLiveConfigured()) {
      return super.setVolume(userId, volumePct);
    }
    const clamped = Math.max(0, Math.min(100, Math.round(volumePct)));
    await this.dispatchPlexamp(userId, `/player/playback/setParameters?volume=${clamped}&type=music`);
  }

  override async duckVolume(userId: string, arg1 = 25, arg2 = 1500): Promise<void> {
    const [targetPct, durationMs] = arg1 > 100 ? [arg2, arg1] : [arg1, arg2];
    if (!this.isLiveConfigured()) {
      return super.duckVolume(userId, durationMs, targetPct);
    }
    const st = await this.getPlaybackState(userId);
    const prev = st.volumePct ?? 74;
    await this.setVolume(userId, Math.min(prev, targetPct));
    const existing = this.duckTimers.get(userId);
    if (existing) clearTimeout(existing);
    const timer = setTimeout(() => {
      this.duckTimers.delete(userId);
      void this.setVolume(userId, prev).catch(() => {});
    }, Math.min(Math.max(durationMs, 400), 5000));
    if (typeof timer.unref === 'function') timer.unref();
    this.duckTimers.set(userId, timer);
  }

  override async getPlaylists(userId: string): Promise<NormalizedPlaylist[]> {
    if (!this.isLiveConfigured()) {
      return process.env.ENABLE_SIMULATED_MUSIC === 'true' ? super.getPlaylists(userId) : [];
    }
    const { plexServerUrl, plexToken } = this.getResolvedConfig();
    if (!plexServerUrl || !plexToken) return [];

    const res = await fetch(`${plexServerUrl}/playlists?playlistType=audio`, {
      headers: this.plexHeaders(plexToken),
      signal: AbortSignal.timeout(4000),
    });
    if (!res.ok) return [];
    const data = (await res.json()) as {
      MediaContainer?: {
        Metadata?: Array<{
          ratingKey: string;
          title: string;
          key: string;
          composite?: string;
          leafCount?: number;
        }>;
      };
    };
    return (data.MediaContainer?.Metadata ?? []).map((item) => ({
      id: item.ratingKey,
      name: item.title,
      uri: `plex:key:${item.key}`,
      artworkUrl: item.composite
        ? `${plexServerUrl}${item.composite}?X-Plex-Token=${encodeURIComponent(plexToken)}`
        : undefined,
      trackCount: item.leafCount ?? 0,
    }));
  }

  async getPlaylistTracks(_userId: string, playlistId: string): Promise<NormalizedTrack[]> {
    const { plexServerUrl, plexToken } = this.getResolvedConfig();
    if (!plexServerUrl || !plexToken) return [];
    const res = await fetch(
      `${plexServerUrl}/playlists/${encodeURIComponent(playlistId)}/items`,
      {
        headers: this.plexHeaders(plexToken),
        signal: AbortSignal.timeout(4000),
      }
    );
    if (!res.ok) return [];
    const data = (await res.json()) as {
      MediaContainer?: {
        Metadata?: Array<{
          ratingKey: string;
          title: string;
          key: string;
          grandparentTitle?: string;
          parentTitle?: string;
          duration?: number;
          thumb?: string;
        }>;
      };
    };
    return (data.MediaContainer?.Metadata ?? []).map((tr) => ({
      id: tr.ratingKey,
      name: tr.title,
      uri: `plex:key:${tr.key}`,
      artistName: tr.grandparentTitle ?? 'Unknown Artist',
      albumName: tr.parentTitle ?? '',
      durationMs: tr.duration ?? 0,
      artworkUrl: tr.thumb
        ? `${plexServerUrl}${tr.thumb}?X-Plex-Token=${encodeURIComponent(plexToken)}`
        : undefined,
    }));
  }

  override async getDevices(userId: string): Promise<NormalizedAudioDevice[]> {
    if (!this.isLiveConfigured()) {
      return process.env.ENABLE_SIMULATED_MUSIC === 'true' ? super.getDevices(userId) : [];
    }
    const activeUrl = this.getPlayerBaseUrl(userId);
    const devices: NormalizedAudioDevice[] = [];
    const { plexampUrl, plexServerUrl, plexToken } = this.getResolvedConfig();

    if (plexampUrl) {
      devices.push({
        id: plexampUrl,
        name: 'Headless Plexamp',
        type: 'Speaker',
        isActive: activeUrl === plexampUrl,
        volumePct: 74,
      });
    }

    if (plexServerUrl && plexToken) {
      try {
        const res = await fetch(`${plexServerUrl}/clients`, {
          headers: this.plexHeaders(plexToken),
          signal: AbortSignal.timeout(3000),
        });
        if (res.ok) {
          const data = (await res.json()) as {
            MediaContainer?: {
              Server?: Array<{
                name: string;
                host: string;
                port: number;
                protocol?: string;
              }>;
            };
          };
          for (const client of data.MediaContainer?.Server ?? []) {
            const clientUrl = `${client.protocol || 'http'}://${client.host}:${client.port}`;
            if (devices.some((d) => d.id === clientUrl)) continue;
            devices.push({
              id: clientUrl,
              name: client.name || client.host,
              type: 'Speaker',
              isActive: activeUrl === clientUrl,
              volumePct: 74,
            });
          }
        }
      } catch {
        // Ignore discovery timeout; keep configured plexampUrl
      }
    }

    return devices;
  }

  override async transferPlayback(userId: string, deviceId: string): Promise<void> {
    if (!this.isLiveConfigured()) {
      return super.transferPlayback(userId, deviceId);
    }
    this.selectedPlayerUrlByUser.set(userId, deviceId);
  }

  async testConnection(): Promise<{ ok: boolean; message: string }> {
    const cfg = this.getResolvedConfig();
    if (!this.isLiveConfigured()) {
      return { ok: false, message: 'Enter a Plexamp URL or Plex Server URL + Token first.' };
    }
    const notes: string[] = [];
    if (cfg.plexampUrl) {
      const res = await fetch(`${cfg.plexampUrl}/player/timeline/poll?wait=0&commandID=${this.commandId++}`, {
        headers: this.plexHeaders(cfg.plexToken),
        signal: AbortSignal.timeout(3500),
      });
      if (!res.ok) throw new Error(`Plexamp player HTTP ${res.status}`);
      notes.push(`Plexamp player reachable at ${cfg.plexampUrl}`);
    }
    if (cfg.plexServerUrl && cfg.plexToken) {
      const playlists = await this.getPlaylists('test');
      notes.push(`Plex Server connected (${playlists.length} audio playlists)`);
    }
    return { ok: true, message: notes.join(' • ') };
  }
}

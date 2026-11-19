import {
  NormalizedAudioDevice,
  NormalizedPlaybackState,
  NormalizedPlaylist,
  NormalizedTrack,
} from '../MusicProvider';
import { getRuntimeSettings } from '../../services/runtimeSettings';
import { BaseSimulatedMusicProvider } from './BaseSimulatedMusicProvider';
import { haMediaPlayerBridge } from './HomeAssistantMediaPlayerBridge';

interface AppleMusicResolvedConfig {
  developerToken?: string;
  musicUserToken?: string;
  haEntityId?: string;
}

/**
 * Real Apple Music Adapter.
 * - Reads real Apple Music Library Playlists & Tracks from Apple Music API (`api.music.apple.com/v1`)
 *   when Developer Token + Music User Token are configured.
 * - Controls live playback, speakers, volume, and ducking on Apple TV / HomePod / AirPlay / Music Assistant
 *   via the Home Assistant `media_player` bridge.
 */
export class AppleMusicProvider extends BaseSimulatedMusicProvider {
  constructor() {
    super({
      id: 'apple_music',
      displayName: 'Apple Music',
      capabilities: {
        supportsConnectDevices: true,
        supportsVolumeDucking: true,
        supportsPlaylists: true,
      },
      defaultVolumePct: 75,
      tracks: [
        { trackName: 'Pure Strength Radio', artistName: 'Apple Fitness+', albumName: 'Studio Mix' },
        { trackName: 'Tempo Push', artistName: 'Apple Music', albumName: 'Daylight Session' },
        { trackName: 'Iron & Rhythm', artistName: 'Fitness Beats', albumName: 'Apple Gym' },
      ],
      playlists: [
        { id: 'am-strength', name: 'Apple Fitness Strength', uri: 'am:playlist:strength', trackCount: 20 },
        { id: 'am-rock', name: 'Pure Lifting Rock', uri: 'am:playlist:rock', trackCount: 32 },
      ],
    });
  }

  getResolvedConfig(): AppleMusicResolvedConfig {
    const saved = getRuntimeSettings().musicProviders?.apple_music;
    return {
      developerToken:
        (saved?.developerToken || process.env.APPLE_MUSIC_DEVELOPER_TOKEN || '').trim() || undefined,
      musicUserToken:
        (saved?.musicUserToken || process.env.APPLE_MUSIC_USER_TOKEN || '').trim() || undefined,
      haEntityId:
        (saved?.haEntityId || process.env.APPLE_MUSIC_HA_ENTITY_ID || '').trim() || undefined,
    };
  }

  private hasAppleApi(cfg: AppleMusicResolvedConfig): boolean {
    return Boolean(cfg.developerToken && cfg.musicUserToken);
  }

  isLiveConfigured(): boolean {
    const cfg = this.getResolvedConfig();
    return Boolean(this.hasAppleApi(cfg) || haMediaPlayerBridge.isReady(cfg.haEntityId));
  }

  private async appleApiFetch(path: string, cfg: AppleMusicResolvedConfig): Promise<Response> {
    const res = await fetch(`https://api.music.apple.com${path}`, {
      headers: {
        Authorization: `Bearer ${cfg.developerToken}`,
        'Music-User-Token': cfg.musicUserToken!,
      },
      signal: AbortSignal.timeout(5000),
    });
    if (!res.ok) {
      throw new Error(`Apple Music API error (${res.status})`);
    }
    return res;
  }

  override async getPlaybackState(userId: string): Promise<NormalizedPlaybackState> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      return haMediaPlayerBridge.getPlaybackState(userId, cfg.haEntityId!);
    }
    if (this.hasAppleApi(cfg)) {
      return {
        isPlaying: false,
        trackName: 'Apple Music Library Connected',
        artistName: 'Link a Home Assistant media_player for remote transport',
        albumName: '',
        progressMs: 0,
        durationMs: 0,
        volumePct: 75,
        isConnected: true,
      };
    }
    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') {
      return super.getPlaybackState(userId);
    }
    return {
      isPlaying: false,
      trackName: 'Apple Music Not Configured',
      artistName: 'Add tokens or Home Assistant player in Settings',
      albumName: '',
      progressMs: 0,
      durationMs: 0,
      volumePct: 75,
      isConnected: false,
    };
  }

  override async play(userId: string, contextUri?: string, deviceId?: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.play(userId, cfg.haEntityId!, contextUri, deviceId);
      return;
    }
    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') return super.play(userId, contextUri, deviceId);
    throw new Error('Configure a Home Assistant media_player entity for Apple Music playback.');
  }

  override async pause(userId: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.pause(userId, cfg.haEntityId!);
      return;
    }
    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') return super.pause(userId);
    throw new Error('Configure a Home Assistant media_player entity for Apple Music playback.');
  }

  override async nextTrack(userId: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.nextTrack(userId, cfg.haEntityId!);
      return;
    }
    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') return super.nextTrack(userId);
    throw new Error('Configure a Home Assistant media_player entity for Apple Music playback.');
  }

  override async previousTrack(userId: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.previousTrack(userId, cfg.haEntityId!);
      return;
    }
    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') return super.previousTrack(userId);
    throw new Error('Configure a Home Assistant media_player entity for Apple Music playback.');
  }

  override async setVolume(userId: string, volumePct: number): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.setVolume(userId, cfg.haEntityId!, volumePct);
      return;
    }
    if (process.env.ENABLE_SIMULATED_MUSIC === 'true') return super.setVolume(userId, volumePct);
    throw new Error('Configure a Home Assistant media_player entity for Apple Music playback.');
  }

  override async duckVolume(userId: string, arg1 = 25, arg2 = 1500): Promise<void> {
    const [targetPct, durationMs] = arg1 > 100 ? [arg2, arg1] : [arg1, arg2];
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.duckVolume(userId, cfg.haEntityId!, targetPct, durationMs);
      return;
    }
    return super.duckVolume(userId, durationMs, targetPct);
  }

  override async getPlaylists(userId: string): Promise<NormalizedPlaylist[]> {
    const cfg = this.getResolvedConfig();
    if (this.hasAppleApi(cfg)) {
      const res = await this.appleApiFetch('/v1/me/library/playlists?limit=25', cfg);
      const body = (await res.json()) as {
        data?: Array<{
          id: string;
          attributes?: {
            name?: string;
            artwork?: { url?: string };
            playParams?: { globalId?: string; id?: string };
          };
        }>;
      };
      return (body.data ?? []).map((pl) => ({
        id: pl.id,
        name: pl.attributes?.name || 'Untitled Playlist',
        uri: pl.attributes?.playParams?.globalId || pl.id,
        artworkUrl: pl.attributes?.artwork?.url?.replace('{w}', '300').replace('{h}', '300'),
        trackCount: 0,
      }));
    }
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      return haMediaPlayerBridge.getSourcePlaylists(userId, cfg.haEntityId!);
    }
    return process.env.ENABLE_SIMULATED_MUSIC === 'true' ? super.getPlaylists(userId) : [];
  }

  async getPlaylistTracks(_userId: string, playlistId: string): Promise<NormalizedTrack[]> {
    const cfg = this.getResolvedConfig();
    if (!this.hasAppleApi(cfg)) return [];
    const res = await this.appleApiFetch(
      `/v1/me/library/playlists/${encodeURIComponent(playlistId)}/tracks?limit=50`,
      cfg
    );
    const body = (await res.json()) as {
      data?: Array<{
        id: string;
        attributes?: {
          name?: string;
          artistName?: string;
          albumName?: string;
          durationInMillis?: number;
          artwork?: { url?: string };
          playParams?: { catalogId?: string; id?: string };
        };
      }>;
    };
    return (body.data ?? []).map((tr) => ({
      id: tr.id,
      name: tr.attributes?.name || 'Unknown Track',
      uri: tr.attributes?.playParams?.catalogId || tr.id,
      artistName: tr.attributes?.artistName || 'Unknown Artist',
      albumName: tr.attributes?.albumName || '',
      durationMs: tr.attributes?.durationInMillis ?? 0,
      artworkUrl: tr.attributes?.artwork?.url?.replace('{w}', '200').replace('{h}', '200'),
    }));
  }

  override async getDevices(userId: string): Promise<NormalizedAudioDevice[]> {
    const cfg = this.getResolvedConfig();
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
    const notes: string[] = [];
    if (this.hasAppleApi(cfg)) {
      const playlists = await this.getPlaylists('test');
      notes.push(`Apple Music Library API OK (${playlists.length} playlists)`);
    }
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      const ha = await haMediaPlayerBridge.testConnection(cfg.haEntityId!);
      notes.push(ha.message);
    }
    if (notes.length === 0) {
      return {
        ok: false,
        message: 'Configure Apple Music API tokens and/or a Home Assistant media_player entity first.',
      };
    }
    return { ok: true, message: notes.join(' • ') };
  }
}

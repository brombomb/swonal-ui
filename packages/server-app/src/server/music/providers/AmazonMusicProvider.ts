import {
  NormalizedAudioDevice,
  NormalizedPlaybackState,
  NormalizedPlaylist,
} from '../MusicProvider';
import { getRuntimeSettings } from '../../services/runtimeSettings';
import { BaseSimulatedMusicProvider } from './BaseSimulatedMusicProvider';
import { haMediaPlayerBridge } from './HomeAssistantMediaPlayerBridge';

interface AmazonMusicResolvedConfig {
  haEntityId?: string;
}

/**
 * Real Amazon Music Adapter via Home Assistant `media_player` bridge
 * (Echo / Alexa Media Player, Sonos, Fire TV, WiiM, or Music Assistant).
 */
export class AmazonMusicProvider extends BaseSimulatedMusicProvider {
  constructor() {
    super({
      id: 'amazon_music',
      displayName: 'Amazon Music',
      capabilities: {
        supportsConnectDevices: true,
        supportsVolumeDucking: true,
        supportsPlaylists: true,
      },
      defaultVolumePct: 72,
      tracks: [
        { trackName: 'Heavy Rep Fuel', artistName: 'Amazon Gym Station', albumName: 'Peak Output' },
        { trackName: 'Max Effort Flow', artistName: 'Prime Fitness', albumName: 'Strength Lab' },
        { trackName: 'Compound Lift Tempo', artistName: 'Studio Audio', albumName: 'Power Block' },
      ],
      playlists: [
        { id: 'amz-pr', name: 'Amazon PR Anthems', uri: 'amazon:playlist:pr', trackCount: 28 },
        { id: 'amz-hiit', name: 'Strength & Conditioning', uri: 'amazon:playlist:hiit', trackCount: 22 },
      ],
    });
  }

  getResolvedConfig(): AmazonMusicResolvedConfig {
    const saved = getRuntimeSettings().musicProviders?.amazon_music;
    return {
      haEntityId:
        (saved?.haEntityId || process.env.AMAZON_MUSIC_HA_ENTITY_ID || '').trim() || undefined,
    };
  }

  isLiveConfigured(): boolean {
    return haMediaPlayerBridge.isReady(this.getResolvedConfig().haEntityId);
  }

  override async getPlaybackState(userId: string): Promise<NormalizedPlaybackState> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      return haMediaPlayerBridge.getPlaybackState(userId, cfg.haEntityId!);
    }
    return super.getPlaybackState(userId);
  }

  override async play(userId: string, contextUri?: string, deviceId?: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.play(userId, cfg.haEntityId!, contextUri, deviceId);
      return;
    }
    return super.play(userId, contextUri, deviceId);
  }

  override async pause(userId: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.pause(userId, cfg.haEntityId!);
      return;
    }
    return super.pause(userId);
  }

  override async nextTrack(userId: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.nextTrack(userId, cfg.haEntityId!);
      return;
    }
    return super.nextTrack(userId);
  }

  override async previousTrack(userId: string): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.previousTrack(userId, cfg.haEntityId!);
      return;
    }
    return super.previousTrack(userId);
  }

  override async setVolume(userId: string, volumePct: number): Promise<void> {
    const cfg = this.getResolvedConfig();
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      await haMediaPlayerBridge.setVolume(userId, cfg.haEntityId!, volumePct);
      return;
    }
    return super.setVolume(userId, volumePct);
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
    if (haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      return haMediaPlayerBridge.getSourcePlaylists(userId, cfg.haEntityId!);
    }
    return process.env.ENABLE_SIMULATED_MUSIC === 'true' ? super.getPlaylists(userId) : [];
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
    if (!haMediaPlayerBridge.isReady(cfg.haEntityId)) {
      return {
        ok: false,
        message: 'Configure Home Assistant Core URL/Token and an Amazon/Echo/Sonos media_player entity first.',
      };
    }
    return haMediaPlayerBridge.testConnection(cfg.haEntityId!);
  }
}

import { homeAssistantBus } from '../../integrations/homeAssistantBus';
import {
  NormalizedAudioDevice,
  NormalizedPlaybackState,
  NormalizedPlaylist,
} from '../MusicProvider';

interface HaEntityState {
  entity_id: string;
  state: string;
  attributes?: {
    friendly_name?: string;
    media_title?: string;
    media_artist?: string;
    media_album_name?: string;
    entity_picture?: string;
    media_position?: number;
    media_duration?: number;
    volume_level?: number;
    source?: string;
    source_list?: string[];
  };
}

/**
 * Reusable Home Assistant `media_player` bridge used by music providers that
 * control speakers/receivers via Home Assistant (Apple TV, HomePod, Echo/Alexa,
 * Sonos, WiiM, Music Assistant, etc.).
 */
export class HomeAssistantMediaPlayerBridge {
  private readonly selectedEntityByUser = new Map<string, string>();
  private readonly duckTimers = new Map<string, ReturnType<typeof setTimeout>>();

  isReady(defaultEntityId?: string): boolean {
    return Boolean(homeAssistantBus.haUrl && homeAssistantBus.haToken && defaultEntityId?.trim());
  }

  resolveEntityId(userId: string, defaultEntityId?: string): string {
    return (
      this.selectedEntityByUser.get(userId) ||
      defaultEntityId?.trim() ||
      ''
    );
  }

  setSelectedEntity(userId: string, entityId: string): void {
    this.selectedEntityByUser.set(userId, entityId);
  }

  private getAuthHeaders(): Record<string, string> {
    const token = homeAssistantBus.haToken;
    if (!token) throw new Error('Home Assistant Long-Lived Access Token is not configured');
    return {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    };
  }

  private getBaseUrl(): string {
    const url = homeAssistantBus.haUrl;
    if (!url) throw new Error('Home Assistant Core URL is not configured');
    return url;
  }

  private async getEntityState(entityId: string): Promise<HaEntityState> {
    const res = await fetch(
      `${this.getBaseUrl()}/api/states/${encodeURIComponent(entityId)}`,
      {
        headers: this.getAuthHeaders(),
        signal: AbortSignal.timeout(4000),
      }
    );
    if (!res.ok) {
      throw new Error(`Home Assistant media_player error (${res.status}) for ${entityId}`);
    }
    return (await res.json()) as HaEntityState;
  }

  private async callService(service: string, body: Record<string, unknown>): Promise<void> {
    const res = await fetch(
      `${this.getBaseUrl()}/api/services/media_player/${service}`,
      {
        method: 'POST',
        headers: this.getAuthHeaders(),
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(4000),
      }
    );
    if (!res.ok) {
      throw new Error(`Home Assistant service media_player.${service} failed (${res.status})`);
    }
  }

  async getPlaybackState(userId: string, defaultEntityId: string): Promise<NormalizedPlaybackState> {
    const entityId = this.resolveEntityId(userId, defaultEntityId);
    const st = await this.getEntityState(entityId);
    const attrs = st.attributes ?? {};
    const pic = attrs.entity_picture
      ? attrs.entity_picture.startsWith('http')
        ? attrs.entity_picture
        : `${this.getBaseUrl()}${attrs.entity_picture}`
      : undefined;

    return {
      isPlaying: st.state === 'playing',
      trackName: attrs.media_title || (st.state === 'playing' ? 'Playing' : 'Not Playing'),
      artistName: attrs.media_artist || attrs.friendly_name || entityId,
      albumName: attrs.media_album_name || '',
      artworkUrl: pic,
      progressMs: typeof attrs.media_position === 'number' ? Math.round(attrs.media_position * 1000) : 0,
      durationMs: typeof attrs.media_duration === 'number' ? Math.round(attrs.media_duration * 1000) : 0,
      activeDeviceId: entityId,
      volumePct: typeof attrs.volume_level === 'number' ? Math.round(attrs.volume_level * 100) : 70,
      isDucked: this.duckTimers.has(userId),
      isConnected: st.state !== 'unavailable',
    };
  }

  async play(
    userId: string,
    defaultEntityId: string,
    contextUri?: string,
    deviceId?: string
  ): Promise<void> {
    if (deviceId) this.setSelectedEntity(userId, deviceId);
    const entityId = this.resolveEntityId(userId, defaultEntityId);

    if (contextUri?.startsWith('ha-source:')) {
      const source = contextUri.slice('ha-source:'.length);
      await this.callService('select_source', { entity_id: entityId, source });
      return;
    }

    if (contextUri) {
      await this.callService('play_media', {
        entity_id: entityId,
        media_content_id: contextUri,
        media_content_type: 'playlist',
      });
      return;
    }

    await this.callService('media_play', { entity_id: entityId });
  }

  async pause(userId: string, defaultEntityId: string): Promise<void> {
    const entityId = this.resolveEntityId(userId, defaultEntityId);
    await this.callService('media_pause', { entity_id: entityId });
  }

  async nextTrack(userId: string, defaultEntityId: string): Promise<void> {
    const entityId = this.resolveEntityId(userId, defaultEntityId);
    await this.callService('media_next_track', { entity_id: entityId });
  }

  async previousTrack(userId: string, defaultEntityId: string): Promise<void> {
    const entityId = this.resolveEntityId(userId, defaultEntityId);
    await this.callService('media_previous_track', { entity_id: entityId });
  }

  async setVolume(userId: string, defaultEntityId: string, volumePct: number): Promise<void> {
    const entityId = this.resolveEntityId(userId, defaultEntityId);
    const level = Math.max(0, Math.min(1, Math.round(volumePct) / 100));
    await this.callService('volume_set', { entity_id: entityId, volume_level: level });
  }

  async duckVolume(
    userId: string,
    defaultEntityId: string,
    targetPct: number,
    durationMs: number
  ): Promise<void> {
    const current = await this.getPlaybackState(userId, defaultEntityId);
    const prevVol = current.volumePct ?? 70;
    const duckVol = Math.min(prevVol, Math.max(0, Math.min(100, targetPct)));
    await this.setVolume(userId, defaultEntityId, duckVol);

    const existing = this.duckTimers.get(userId);
    if (existing) clearTimeout(existing);
    const timer = setTimeout(() => {
      this.duckTimers.delete(userId);
      void this.setVolume(userId, defaultEntityId, prevVol).catch(() => {});
    }, Math.min(Math.max(durationMs, 400), 5000));
    if (typeof timer.unref === 'function') timer.unref();
    this.duckTimers.set(userId, timer);
  }

  async getSourcePlaylists(userId: string, defaultEntityId: string): Promise<NormalizedPlaylist[]> {
    const entityId = this.resolveEntityId(userId, defaultEntityId);
    const st = await this.getEntityState(entityId);
    const sources = st.attributes?.source_list ?? [];
    return sources.map((src, idx) => ({
      id: `ha-src-${idx}`,
      name: src,
      uri: `ha-source:${src}`,
      trackCount: 0,
    }));
  }

  async getDevices(userId: string, defaultEntityId: string): Promise<NormalizedAudioDevice[]> {
    const activeId = this.resolveEntityId(userId, defaultEntityId);
    const players = await homeAssistantBus.listMediaPlayers();
    return players.map((p) => ({
      id: p.entityId,
      name: p.friendlyName,
      type: 'Speaker',
      isActive: p.entityId === activeId,
      volumePct: p.volumePct ?? 70,
    }));
  }

  async testConnection(defaultEntityId: string): Promise<{ ok: boolean; message: string }> {
    const st = await this.getEntityState(defaultEntityId);
    const name = st.attributes?.friendly_name || st.entity_id;
    return {
      ok: st.state !== 'unavailable',
      message: `Connected to ${name} (${st.state})`,
    };
  }
}

export const haMediaPlayerBridge = new HomeAssistantMediaPlayerBridge();

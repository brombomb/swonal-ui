import {
  IMusicProvider,
  NormalizedAudioDevice,
  NormalizedPlaybackState,
  NormalizedPlaylist,
  ProviderCapabilities,
} from '../MusicProvider';

export interface SimulatedTrack {
  trackName: string;
  artistName: string;
  albumName: string;
  durationMs?: number;
}

export interface SimulatedProviderConfig {
  id: string;
  displayName: string;
  capabilities?: ProviderCapabilities;
  defaultVolumePct?: number;
  tracks: SimulatedTrack[];
  playlists: NormalizedPlaylist[];
  devices?: NormalizedAudioDevice[];
}

/**
 * Shared base adapter for community music providers.
 * Maintains isolated per-user playback state, playlist selection, device routing,
 * and automatic volume ducking with restoration.
 */
export abstract class BaseSimulatedMusicProvider implements IMusicProvider {
  readonly id: string;
  readonly displayName: string;
  readonly capabilities: ProviderCapabilities;

  private readonly defaultVolumePct: number;
  private readonly tracks: SimulatedTrack[];
  private readonly playlists: NormalizedPlaylist[];
  private readonly devices: NormalizedAudioDevice[];
  private readonly stateByUser = new Map<
    string,
    {
      isPlaying: boolean;
      volumePct: number;
      trackIdx: number;
      activeDeviceId: string;
      isConnected: boolean;
    }
  >();

  protected constructor(config: SimulatedProviderConfig) {
    this.id = config.id;
    this.displayName = config.displayName;
    this.capabilities = config.capabilities ?? {
      supportsConnectDevices: false,
      supportsVolumeDucking: true,
      supportsPlaylists: true,
    };
    this.defaultVolumePct = config.defaultVolumePct ?? 72;
    this.tracks = config.tracks;
    this.playlists = config.playlists;
    this.devices = config.devices ?? [
      {
        id: 'tonal-browser-audio',
        name: 'Tonal Tablet Speakers',
        type: 'Browser',
        isActive: true,
        volumePct: this.defaultVolumePct,
      },
    ];
  }

  protected getState(userId: string) {
    let st = this.stateByUser.get(userId);
    if (!st) {
      st = {
        isPlaying: false,
        volumePct: this.defaultVolumePct,
        trackIdx: 0,
        activeDeviceId: this.devices[0]?.id ?? 'tonal-browser-audio',
        isConnected: false,
      };
      this.stateByUser.set(userId, st);
    }
    return st;
  }

  async getAuthUrl(userId: string): Promise<string> {
    return `/api/music/${this.id}/callback?code=${this.id}-connected&state=${encodeURIComponent(userId)}`;
  }

  async handleAuthCallback(userId: string, _code: string): Promise<void> {
    this.getState(userId).isConnected = true;
  }

  async refreshAuthIfNeeded(_userId: string): Promise<string> {
    return `${this.id}-session-token`;
  }

  async getPlaybackState(userId: string): Promise<NormalizedPlaybackState> {
    const st = this.getState(userId);
    const track = this.tracks[st.trackIdx % this.tracks.length];
    return {
      isPlaying: st.isPlaying,
      trackName: track.trackName,
      artistName: track.artistName,
      albumName: track.albumName,
      progressMs: 18000,
      durationMs: track.durationMs ?? 205000,
      activeDeviceId: st.activeDeviceId,
      volumePct: st.volumePct,
      isConnected: st.isConnected,
    };
  }

  async play(userId: string, contextUri?: string, deviceId?: string): Promise<void> {
    const st = this.getState(userId);
    st.isPlaying = true;
    if (deviceId) {
      st.activeDeviceId = deviceId;
    }
    if (contextUri) {
      const playlistIdx = this.playlists.findIndex((p) => p.uri === contextUri);
      if (playlistIdx >= 0) {
        st.trackIdx = playlistIdx % this.tracks.length;
      }
    }
  }

  async pause(userId: string): Promise<void> {
    this.getState(userId).isPlaying = false;
  }

  async nextTrack(userId: string): Promise<void> {
    const st = this.getState(userId);
    st.trackIdx = (st.trackIdx + 1) % this.tracks.length;
  }

  async previousTrack(userId: string): Promise<void> {
    const st = this.getState(userId);
    st.trackIdx = (st.trackIdx - 1 + this.tracks.length) % this.tracks.length;
  }

  async setVolume(userId: string, volumePct: number): Promise<void> {
    this.getState(userId).volumePct = Math.max(0, Math.min(100, Math.round(volumePct)));
  }

  async getPlaylists(_userId: string): Promise<NormalizedPlaylist[]> {
    return this.playlists;
  }

  async getDevices(userId: string): Promise<NormalizedAudioDevice[]> {
    const st = this.getState(userId);
    return this.devices.map((d) => ({
      ...d,
      isActive: d.id === st.activeDeviceId,
      volumePct: st.volumePct,
    }));
  }

  async transferPlayback(userId: string, deviceId: string): Promise<void> {
    this.getState(userId).activeDeviceId = deviceId;
  }

  async duckVolume(userId: string, durationMs = 2000, duckToPct = 20): Promise<void> {
    const st = this.getState(userId);
    const prev = st.volumePct;
    st.volumePct = Math.min(prev, duckToPct);
    const timer = setTimeout(() => {
      st.volumePct = prev;
    }, Math.min(durationMs, 3000));
    if (typeof timer.unref === 'function') timer.unref();
  }
}

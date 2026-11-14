import {
  IMusicProvider,
  NormalizedAudioDevice,
  NormalizedPlaybackState,
  NormalizedPlaylist,
  ProviderCapabilities,
} from '../MusicProvider';

/**
 * Community Adapter Stub for Apple Music (MusicKit JS / API bridge).
 */
export class AppleMusicProvider implements IMusicProvider {
  readonly id = 'apple_music' as const;
  readonly displayName = 'Apple Music';
  readonly capabilities: ProviderCapabilities = {
    supportsConnectDevices: false,
    supportsVolumeDucking: true,
    supportsPlaylists: true,
  };

  private readonly stateByUser = new Map<
    string,
    { isPlaying: boolean; volumePct: number; trackIdx: number }
  >();

  private readonly tracks = [
    { trackName: 'Pure Strength Radio', artistName: 'Apple Fitness+', albumName: 'Studio Mix' },
    { trackName: 'Tempo Push', artistName: 'Apple Music', albumName: 'Daylight Session' },
  ];

  private getState(userId: string) {
    let st = this.stateByUser.get(userId);
    if (!st) {
      st = { isPlaying: false, volumePct: 75, trackIdx: 0 };
      this.stateByUser.set(userId, st);
    }
    return st;
  }

  async getAuthUrl(userId: string): Promise<string> {
    return `/api/music/apple_music/callback?code=am-stub&state=${encodeURIComponent(userId)}`;
  }

  async handleAuthCallback(_userId: string, _code: string): Promise<void> {}

  async refreshAuthIfNeeded(_userId: string): Promise<string> {
    return 'apple-musickit-stub-token';
  }

  async getPlaybackState(userId: string): Promise<NormalizedPlaybackState> {
    const st = this.getState(userId);
    const track = this.tracks[st.trackIdx % this.tracks.length];
    return {
      isPlaying: st.isPlaying,
      trackName: track.trackName,
      artistName: track.artistName,
      albumName: track.albumName,
      progressMs: 12000,
      durationMs: 195000,
      activeDeviceId: 'tonal-browser-audio',
      volumePct: st.volumePct,
      isConnected: false,
    };
  }

  async play(userId: string): Promise<void> {
    this.getState(userId).isPlaying = true;
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
    return [
      { id: 'am-strength', name: 'Apple Fitness Strength', uri: 'am:playlist:strength', trackCount: 20 },
      { id: 'am-rock', name: 'Pure Lifting Rock', uri: 'am:playlist:rock', trackCount: 32 },
    ];
  }

  async getDevices(userId: string): Promise<NormalizedAudioDevice[]> {
    return [
      {
        id: 'tonal-browser-audio',
        name: 'Tonal Tablet Speakers',
        type: 'Browser',
        isActive: true,
        volumePct: this.getState(userId).volumePct,
      },
    ];
  }

  async transferPlayback(_userId: string, _deviceId: string): Promise<void> {}

  async duckVolume(userId: string, targetPct: number, durationMs: number): Promise<void> {
    const st = this.getState(userId);
    const prev = st.volumePct;
    st.volumePct = Math.min(prev, targetPct);
    const timer = setTimeout(() => {
      st.volumePct = prev;
    }, Math.max(100, durationMs));
    if (typeof timer.unref === 'function') timer.unref();
  }
}

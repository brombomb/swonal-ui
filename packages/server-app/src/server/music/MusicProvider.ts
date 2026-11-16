export interface ProviderCapabilities {
  supportsConnectDevices: boolean;
  supportsVolumeDucking: boolean;
  supportsPlaylists: boolean;
}

export interface NormalizedAudioDevice {
  id: string;
  name: string;
  type: string;
  isActive: boolean;
  volumePct: number;
}

export interface NormalizedPlaylist {
  id: string;
  name: string;
  uri: string;
  artworkUrl?: string;
  trackCount: number;
}

export interface NormalizedTrack {
  id: string;
  name: string;
  uri: string;
  artistName: string;
  albumName: string;
  durationMs: number;
  artworkUrl?: string;
}

export interface NormalizedPlaybackState {
  isPlaying: boolean;
  trackName: string;
  artistName: string;
  albumName: string;
  artworkUrl?: string;
  progressMs: number;
  durationMs: number;
  activeDeviceId?: string;
  volumePct?: number;
  isDucked?: boolean;
  isConnected?: boolean;
}

export interface IMusicProvider {
  readonly id: 'spotify' | 'youtube_music' | 'apple_music' | 'amazon_music' | string;
  readonly displayName: string;
  readonly capabilities: ProviderCapabilities;

  getAuthUrl(userId: string): Promise<string>;
  handleAuthCallback(userId: string, code: string): Promise<void>;
  refreshAuthIfNeeded(userId: string): Promise<string>;

  getPlaybackState(userId: string): Promise<NormalizedPlaybackState>;
  play(
    userId: string,
    contextUri?: string,
    deviceId?: string,
    fallbackDeviceId?: string
  ): Promise<void>;
  pause(userId: string): Promise<void>;
  nextTrack(userId: string): Promise<void>;
  previousTrack(userId: string): Promise<void>;
  setVolume(userId: string, volumePct: number): Promise<void>;
  getPlaylists(userId: string): Promise<NormalizedPlaylist[]>;
  getPlaylistTracks?(userId: string, playlistId: string): Promise<NormalizedTrack[]>;
  getDevices(userId: string): Promise<NormalizedAudioDevice[]>;
  transferPlayback(userId: string, deviceId: string): Promise<void>;
  duckVolume(userId: string, targetPct: number, durationMs: number): Promise<void>;
}

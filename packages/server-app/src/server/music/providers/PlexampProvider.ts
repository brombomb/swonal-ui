import { BaseSimulatedMusicProvider } from './BaseSimulatedMusicProvider';

/**
 * Community Adapter for Headless Plexamp.
 * When PLEXAMP_URL is configured (e.g. http://192.168.1.50:32500), forwards
 * transport commands to the local Plexamp player API and falls back cleanly
 * to local state tracking if unreachable.
 */
export class PlexampProvider extends BaseSimulatedMusicProvider {
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
        {
          id: 'tonal-browser-audio',
          name: 'Tonal Tablet Speakers',
          type: 'Browser',
          isActive: false,
          volumePct: 74,
        },
      ],
    });
  }

  private async dispatchPlexamp(endpoint: string): Promise<void> {
    const baseUrl = process.env.PLEXAMP_URL?.trim();
    if (!baseUrl) return;
    try {
      await fetch(`${baseUrl.replace(/\/$/, '')}${endpoint}`, {
        method: 'GET',
        signal: AbortSignal.timeout(1500),
      });
    } catch {
      // Fall back silently to local state when headless Plexamp isn't reachable
    }
  }

  override async play(userId: string, contextUri?: string, deviceId?: string): Promise<void> {
    await super.play(userId, contextUri, deviceId);
    await this.dispatchPlexamp('/player/playback/play');
  }

  override async pause(userId: string): Promise<void> {
    await super.pause(userId);
    await this.dispatchPlexamp('/player/playback/pause');
  }

  override async nextTrack(userId: string): Promise<void> {
    await super.nextTrack(userId);
    await this.dispatchPlexamp('/player/playback/skipNext');
  }

  override async previousTrack(userId: string): Promise<void> {
    await super.previousTrack(userId);
    await this.dispatchPlexamp('/player/playback/skipPrevious');
  }
}

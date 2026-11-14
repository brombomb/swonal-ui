import { BaseSimulatedMusicProvider } from './BaseSimulatedMusicProvider';

/**
 * Community Adapter for Apple Music (MusicKit JS / API bridge).
 */
export class AppleMusicProvider extends BaseSimulatedMusicProvider {
  constructor() {
    super({
      id: 'apple_music',
      displayName: 'Apple Music',
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
}

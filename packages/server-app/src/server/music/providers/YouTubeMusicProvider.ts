import { BaseSimulatedMusicProvider } from './BaseSimulatedMusicProvider';

/**
 * Community Adapter for YouTube Music.
 */
export class YouTubeMusicProvider extends BaseSimulatedMusicProvider {
  constructor() {
    super({
      id: 'youtube_music',
      displayName: 'YouTube Music',
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
}

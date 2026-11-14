import { BaseSimulatedMusicProvider } from './BaseSimulatedMusicProvider';

/**
 * Community Adapter for Amazon Music.
 */
export class AmazonMusicProvider extends BaseSimulatedMusicProvider {
  constructor() {
    super({
      id: 'amazon_music',
      displayName: 'Amazon Music',
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
}

import { IMusicProvider } from './MusicProvider';
import { SpotifyProvider } from './providers/SpotifyProvider';
import { YouTubeMusicProvider } from './providers/YouTubeMusicProvider';
import { AppleMusicProvider } from './providers/AppleMusicProvider';
import { AmazonMusicProvider } from './providers/AmazonMusicProvider';
import { PlexampProvider } from './providers/PlexampProvider';

export class MusicProviderRegistry {
  private readonly providers = new Map<string, IMusicProvider>();

  constructor() {
    this.register(new SpotifyProvider());
    this.register(new YouTubeMusicProvider());
    this.register(new AppleMusicProvider());
    this.register(new AmazonMusicProvider());
    this.register(new PlexampProvider());
  }

  register(provider: IMusicProvider): void {
    this.providers.set(provider.id, provider);
  }

  get(providerId: string): IMusicProvider {
    const provider = this.providers.get(providerId);
    if (!provider) {
      throw new Error(`Unsupported music provider: ${providerId}`);
    }
    return provider;
  }

  isProviderConfigured(providerId: string): boolean {
    if (providerId === 'spotify') {
      return Boolean(process.env.SPOTIFY_CLIENT_ID?.trim() && process.env.SPOTIFY_CLIENT_SECRET?.trim());
    }
    if (providerId === 'plexamp') {
      return Boolean(process.env.PLEXAMP_URL?.trim());
    }
    return process.env.ENABLE_SIMULATED_MUSIC === 'true';
  }

  list(): Array<{
    id: string;
    displayName: string;
    configured: boolean;
    capabilities: IMusicProvider['capabilities'];
  }> {
    return Array.from(this.providers.values()).map((p) => ({
      id: p.id,
      displayName: p.displayName,
      configured: this.isProviderConfigured(p.id),
      capabilities: p.capabilities,
    }));
  }
}

export const musicRegistry = new MusicProviderRegistry();

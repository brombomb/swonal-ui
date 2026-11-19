import { IMusicProvider } from './MusicProvider';
import { SpotifyProvider } from './providers/SpotifyProvider';
import { YouTubeMusicProvider } from './providers/YouTubeMusicProvider';
import { AppleMusicProvider } from './providers/AppleMusicProvider';
import { AmazonMusicProvider } from './providers/AmazonMusicProvider';
import { PlexampProvider } from './providers/PlexampProvider';
import {
  getRuntimeSettings,
  PersistedMusicProvidersConfig,
  updateRuntimeSettings,
} from '../services/runtimeSettings';

function assertSafeOptionalUrl(raw: string | undefined, label: string): string | undefined {
  const trimmed = (raw ?? '').trim().replace(/\/+$/, '');
  if (!trimmed) return undefined;
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      throw new Error(`${label} must use http:// or https://`);
    }
    return trimmed;
  } catch {
    throw new Error(`${label} must be a valid http:// or https:// URL`);
  }
}

export class MusicProviderRegistry {
  private readonly providers = new Map<string, IMusicProvider>();
  private readonly plexamp = new PlexampProvider();
  private readonly youtubeMusic = new YouTubeMusicProvider();
  private readonly appleMusic = new AppleMusicProvider();
  private readonly amazonMusic = new AmazonMusicProvider();

  constructor() {
    this.register(new SpotifyProvider());
    this.register(this.youtubeMusic);
    this.register(this.appleMusic);
    this.register(this.amazonMusic);
    this.register(this.plexamp);
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
      return this.plexamp.isLiveConfigured() || process.env.ENABLE_SIMULATED_MUSIC === 'true';
    }
    if (providerId === 'youtube_music') {
      return this.youtubeMusic.isLiveConfigured() || process.env.ENABLE_SIMULATED_MUSIC === 'true';
    }
    if (providerId === 'apple_music') {
      return this.appleMusic.isLiveConfigured() || process.env.ENABLE_SIMULATED_MUSIC === 'true';
    }
    if (providerId === 'amazon_music') {
      return this.amazonMusic.isLiveConfigured() || process.env.ENABLE_SIMULATED_MUSIC === 'true';
    }
    return process.env.ENABLE_SIMULATED_MUSIC === 'true';
  }

  getPublicConfigs() {
    const plex = this.plexamp.getResolvedConfig();
    const ytm = this.youtubeMusic.getResolvedConfig();
    const apple = this.appleMusic.getResolvedConfig();
    const amazon = this.amazonMusic.getResolvedConfig();
    return {
      plexamp: {
        plexampUrl: plex.plexampUrl ?? '',
        plexServerUrl: plex.plexServerUrl ?? '',
        hasPlexToken: Boolean(plex.plexToken),
        configured: this.isProviderConfigured('plexamp'),
      },
      youtube_music: {
        apiUrl: ytm.apiUrl ?? '',
        hasAuthToken: Boolean(ytm.authToken),
        haEntityId: ytm.haEntityId ?? '',
        configured: this.isProviderConfigured('youtube_music'),
      },
      apple_music: {
        hasDeveloperToken: Boolean(apple.developerToken),
        hasMusicUserToken: Boolean(apple.musicUserToken),
        haEntityId: apple.haEntityId ?? '',
        configured: this.isProviderConfigured('apple_music'),
      },
      amazon_music: {
        haEntityId: amazon.haEntityId ?? '',
        configured: this.isProviderConfigured('amazon_music'),
      },
    };
  }

  updateProviderConfig(providerId: string, body: Record<string, unknown>) {
    const current: PersistedMusicProvidersConfig = {
      ...(getRuntimeSettings().musicProviders ?? {}),
    };

    if (providerId === 'plexamp') {
      const prev = current.plexamp ?? {};
      current.plexamp = {
        plexampUrl:
          body.plexampUrl !== undefined
            ? assertSafeOptionalUrl(String(body.plexampUrl), 'Plexamp URL')
            : prev.plexampUrl,
        plexServerUrl:
          body.plexServerUrl !== undefined
            ? assertSafeOptionalUrl(String(body.plexServerUrl), 'Plex Server URL')
            : prev.plexServerUrl,
        plexToken:
          body.clearToken === true
            ? undefined
            : typeof body.plexToken === 'string' && body.plexToken.trim()
              ? body.plexToken.trim()
              : prev.plexToken,
      };
    } else if (providerId === 'youtube_music') {
      const prev = current.youtube_music ?? {};
      current.youtube_music = {
        apiUrl:
          body.apiUrl !== undefined
            ? assertSafeOptionalUrl(String(body.apiUrl), 'YouTube Music API URL')
            : prev.apiUrl,
        authToken:
          body.clearToken === true
            ? undefined
            : typeof body.authToken === 'string' && body.authToken.trim()
              ? body.authToken.trim()
              : prev.authToken,
        haEntityId:
          body.haEntityId !== undefined
            ? String(body.haEntityId).trim() || undefined
            : prev.haEntityId,
      };
    } else if (providerId === 'apple_music') {
      const prev = current.apple_music ?? {};
      current.apple_music = {
        developerToken:
          body.clearToken === true
            ? undefined
            : typeof body.developerToken === 'string' && body.developerToken.trim()
              ? body.developerToken.trim()
              : prev.developerToken,
        musicUserToken:
          body.clearToken === true
            ? undefined
            : typeof body.musicUserToken === 'string' && body.musicUserToken.trim()
              ? body.musicUserToken.trim()
              : prev.musicUserToken,
        haEntityId:
          body.haEntityId !== undefined
            ? String(body.haEntityId).trim() || undefined
            : prev.haEntityId,
      };
    } else if (providerId === 'amazon_music') {
      const prev = current.amazon_music ?? {};
      current.amazon_music = {
        haEntityId:
          body.haEntityId !== undefined
            ? String(body.haEntityId).trim() || undefined
            : prev.haEntityId,
      };
    } else {
      throw new Error(`Configuration update not supported for provider: ${providerId}`);
    }

    updateRuntimeSettings({ musicProviders: current });
    return this.getPublicConfigs();
  }

  async testProviderConnection(providerId: string): Promise<{ ok: boolean; message: string }> {
    if (providerId === 'plexamp') return this.plexamp.testConnection();
    if (providerId === 'youtube_music') return this.youtubeMusic.testConnection();
    if (providerId === 'apple_music') return this.appleMusic.testConnection();
    if (providerId === 'amazon_music') return this.amazonMusic.testConnection();
    if (providerId === 'spotify') {
      const ok = this.isProviderConfigured('spotify');
      return {
        ok,
        message: ok ? 'Spotify OAuth client is configured.' : 'Spotify credentials are missing.',
      };
    }
    throw new Error(`Unsupported music provider: ${providerId}`);
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

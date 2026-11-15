import { z } from 'zod';

const ServerEnvSchema = z.object({
  PORT: z.coerce.number().int().min(1).max(65535).default(3000),
  DATABASE_URL: z.string().optional(),
  TONAL_DAEMON_URL: z.string().url().default('http://localhost:8765'),
  TONAL_DAEMON_WS_URL: z.string().default('ws://localhost:8765/ws'),
  PUBLIC_URL: z.string().optional().default(''),
  COMPANION_BASE_URL: z.string().optional().default(''),
  SPOTIFY_CLIENT_ID: z.string().optional().default(''),
  SPOTIFY_CLIENT_SECRET: z.string().optional().default(''),
  SPOTIFY_REDIRECT_URI: z.string().optional().default(''),
  PLEXAMP_URL: z.string().optional().default(''),
  ENABLE_SIMULATED_MUSIC: z.string().optional().default(''),
});

export type ServerConfig = z.infer<typeof ServerEnvSchema>;

export function getPublicBaseUrl(cfg?: ServerConfig): string {
  const c = cfg ?? getServerConfig();
  const raw = (c.PUBLIC_URL || c.COMPANION_BASE_URL || '').trim();
  return raw.replace(/\/+$/, '');
}

export function getServerConfig(): ServerConfig {
  const parsed = ServerEnvSchema.safeParse({
    PORT: process.env.PORT || undefined,
    DATABASE_URL: process.env.DATABASE_URL || undefined,
    TONAL_DAEMON_URL: process.env.TONAL_DAEMON_URL || undefined,
    TONAL_DAEMON_WS_URL: process.env.TONAL_DAEMON_WS_URL || undefined,
    PUBLIC_URL: process.env.PUBLIC_URL ?? '',
    COMPANION_BASE_URL: process.env.COMPANION_BASE_URL ?? '',
    SPOTIFY_CLIENT_ID: process.env.SPOTIFY_CLIENT_ID ?? '',
    SPOTIFY_CLIENT_SECRET: process.env.SPOTIFY_CLIENT_SECRET ?? '',
    SPOTIFY_REDIRECT_URI: process.env.SPOTIFY_REDIRECT_URI ?? '',
    PLEXAMP_URL: process.env.PLEXAMP_URL ?? '',
    ENABLE_SIMULATED_MUSIC: process.env.ENABLE_SIMULATED_MUSIC ?? '',
  });

  if (!parsed.success) {
    const formatted = parsed.error.issues
      .map((issue) => `${issue.path.join('.')}: ${issue.message}`)
      .join('; ');
    throw new Error(`Invalid server environment configuration: ${formatted}`);
  }

  return parsed.data;
}

export function isSpotifyConfigured(): boolean {
  const cfg = getServerConfig();
  return Boolean(cfg.SPOTIFY_CLIENT_ID.trim() && cfg.SPOTIFY_CLIENT_SECRET.trim());
}

export function getSpotifyConfig() {
  const cfg = getServerConfig();
  const publicBase = getPublicBaseUrl(cfg);
  const explicitRedirect = cfg.SPOTIFY_REDIRECT_URI.trim();
  const redirectUri =
    explicitRedirect ||
    (publicBase ? `${publicBase}/api/spotify/callback` : 'http://localhost:3000/api/spotify/callback');

  return {
    clientId: cfg.SPOTIFY_CLIENT_ID.trim(),
    clientSecret: cfg.SPOTIFY_CLIENT_SECRET.trim(),
    redirectUri,
    configured: Boolean(cfg.SPOTIFY_CLIENT_ID.trim() && cfg.SPOTIFY_CLIENT_SECRET.trim()),
  };
}

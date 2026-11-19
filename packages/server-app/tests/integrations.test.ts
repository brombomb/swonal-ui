import http from 'http';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { aiCoachService } from '../src/server/services/aiCoach';
import { invalidateRuntimeSettingsCache } from '../src/server/services/runtimeSettings';
import { homeAssistantBus } from '../src/server/integrations/homeAssistantBus';

function startMockHttpServer(
  handler: (req: http.IncomingMessage, body: string, res: http.ServerResponse) => void
): Promise<{ server: http.Server; baseUrl: string }> {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      let body = '';
      req.on('data', (chunk) => {
        body += chunk;
      });
      req.on('end', () => handler(req, body, res));
    });
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address() as { port: number };
      resolve({ server, baseUrl: `http://127.0.0.1:${addr.port}` });
    });
  });
}

describe('AI Voice Coach, Home Assistant Bus, and Real Music Providers', () => {
  let settingsFile: string;
  let srv: SwonalServerHandle;
  let appBase: string;

  let mockServer: http.Server;
  let mockBase: string;
  const receivedCalls: Array<{ method: string; url: string; auth?: string; body: string }> = [];

  beforeAll(async () => {
    settingsFile = path.join(os.tmpdir(), `swonal-integrations-${Date.now()}.json`);
    process.env.SWONAL_SETTINGS_FILE = settingsFile;
    invalidateRuntimeSettingsCache();
    aiCoachService.reloadFromSettings();

    const mock = await startMockHttpServer((req, body, res) => {
      const url = req.url || '/';
      receivedCalls.push({
        method: req.method || 'GET',
        url,
        auth: req.headers.authorization,
        body,
      });

      // OpenAI-compatible mock endpoint
      if (url === '/openai/chat/completions') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            choices: [{ message: { content: 'Swonal Coach ready. Crush this set!' } }],
          })
        );
        return;
      }

      // Home Assistant Webhook mock
      if (url === '/ha/webhook') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true }));
        return;
      }

      // Home Assistant REST API states & media_player services
      if (url === '/ha/api/states') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify([
            {
              entity_id: 'media_player.gym_echo',
              state: 'playing',
              attributes: {
                friendly_name: 'Gym Echo Studio',
                volume_level: 0.65,
              },
            },
            {
              entity_id: 'light.gym',
              state: 'on',
              attributes: { friendly_name: 'Gym Lights' },
            },
          ])
        );
        return;
      }
      if (url === '/ha/api/states/media_player.gym_echo') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            entity_id: 'media_player.gym_echo',
            state: 'playing',
            attributes: {
              friendly_name: 'Gym Echo Studio',
              media_title: 'Iron Anthem',
              media_artist: 'Gym Rock',
              media_album_name: 'PR Fuel',
              media_position: 32,
              media_duration: 180,
              volume_level: 0.65,
              source_list: ['Heavy Metal', 'Focus Beats'],
            },
          })
        );
        return;
      }
      if (url.startsWith('/ha/api/services/media_player/')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify([]));
        return;
      }

      // Plexamp timeline & transport mock
      if (url.startsWith('/plexamp/player/timeline/poll')) {
        res.writeHead(200, { 'Content-Type': 'application/xml' });
        res.end(
          `<MediaContainer><Timeline type="music" state="playing" time="12000" duration="240000" volume="82"><Track title="FLAC Deadlifts" grandparentTitle="Lossless Band" parentTitle="Heavy LP"/></Timeline></MediaContainer>`
        );
        return;
      }
      if (url.startsWith('/plexamp/player/playback/')) {
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end('OK');
        return;
      }
      if (url.startsWith('/plex/playlists?playlistType=audio')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            MediaContainer: {
              Metadata: [
                {
                  ratingKey: '99',
                  title: 'Lossless PR Mix',
                  key: '/playlists/99/items',
                  leafCount: 14,
                },
              ],
            },
          })
        );
        return;
      }
      if (url.startsWith('/plex/identity')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ MediaContainer: { machineIdentifier: 'plex-machine-1' } }));
        return;
      }
      if (url.startsWith('/plex/clients')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ MediaContainer: { Server: [] } }));
        return;
      }

      // YouTube Music Desktop API Server mock
      if (url === '/ytm/api/v1/song') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            title: 'Squat Rack Synth',
            artist: 'YT Gym',
            album: 'Night Lift',
            isPaused: false,
            elapsedSeconds: 45,
            songDuration: 200,
          })
        );
        return;
      }
      if (url === '/ytm/api/v1/volume') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ state: 64 }));
        return;
      }
      if (url.startsWith('/ytm/api/v1/')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true }));
        return;
      }

      res.writeHead(404);
      res.end('Not found');
    });
    mockServer = mock.server;
    mockBase = mock.baseUrl;

    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    appBase = `http://127.0.0.1:${srv.port}`;
  });

  afterAll(async () => {
    await homeAssistantBus.close();
    await srv.close();
    await new Promise<void>((resolve) => mockServer.close(() => resolve()));
    delete process.env.SWONAL_SETTINGS_FILE;
    invalidateRuntimeSettingsCache();
    aiCoachService.reloadFromSettings();
    try {
      fs.unlinkSync(settingsFile);
    } catch {
      // ignore cleanup error
    }
  });

  it('persists AI Coach BYOK config and runs live provider connection tests', async () => {
    const saveRes = await fetch(`${appBase}/api/coach/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        provider: 'openai',
        model: 'gpt-4o-mini',
        apiKey: 'sk-test-secret-9999',
        baseUrl: `${mockBase}/openai`,
      }),
    });
    expect(saveRes.status).toBe(200);
    const saved = (await saveRes.json()) as { provider: string; hasApiKey: boolean; keyHint: string };
    expect(saved.provider).toBe('openai');
    expect(saved.hasApiKey).toBe(true);
    expect(saved.keyHint).toBe('••••9999');

    // Verify persistence across cache invalidation (simulated restart)
    invalidateRuntimeSettingsCache();
    aiCoachService.reloadFromSettings();
    const reloaded = aiCoachService.getPublicConfig();
    expect(reloaded.provider).toBe('openai');
    expect(reloaded.hasApiKey).toBe(true);

    const testRes = await fetch(`${appBase}/api/coach/test`, { method: 'POST' });
    expect(testRes.status).toBe(200);
    const testBody = (await testRes.json()) as { ok: boolean; reply: string; latencyMs: number };
    expect(testBody.ok).toBe(true);
    expect(testBody.reply).toContain('Swonal Coach ready');
    expect(testBody.latencyMs).toBeGreaterThanOrEqual(0);
  });

  it('configures Home Assistant webhooks & REST media_player discovery', async () => {
    const cfgRes = await fetch(`${appBase}/api/integrations/ha/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        mqttTopicPrefix: 'swonal_gym',
        webhookUrl: `${mockBase}/ha/webhook`,
        webhookToken: 'ha-hook-secret',
        haUrl: `${mockBase}/ha`,
        haToken: 'ha-long-lived-token',
      }),
    });
    expect(cfgRes.status).toBe(200);
    const haCfg = (await cfgRes.json()) as {
      webhookConfigured: boolean;
      haRestConfigured: boolean;
      mqttTopicPrefix: string;
    };
    expect(haCfg.webhookConfigured).toBe(true);
    expect(haCfg.haRestConfigured).toBe(true);
    expect(haCfg.mqttTopicPrefix).toBe('swonal_gym');

    const testRes = await fetch(`${appBase}/api/integrations/ha/test`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ note: 'integration test' }),
    });
    expect(testRes.status).toBe(200);
    const testJson = (await testRes.json()) as { event: { webhookDelivered: boolean } };
    expect(testJson.event.webhookDelivered).toBe(true);

    const playersRes = await fetch(`${appBase}/api/integrations/ha/media-players`);
    expect(playersRes.status).toBe(200);
    const playersJson = (await playersRes.json()) as {
      players: Array<{ entityId: string; friendlyName: string }>;
    };
    expect(playersJson.players).toHaveLength(1);
    expect(playersJson.players[0].entityId).toBe('media_player.gym_echo');
  });

  it('controls live Plexamp, YouTube Music, Apple Music, and Amazon Music endpoints', async () => {
    // 1. Plexamp
    const plexCfg = await fetch(`${appBase}/api/music/plexamp/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        plexampUrl: `${mockBase}/plexamp`,
        plexServerUrl: `${mockBase}/plex`,
        plexToken: 'plex-token-abc',
      }),
    });
    expect(plexCfg.status).toBe(200);

    const plexState = (await (await fetch(`${appBase}/api/music/plexamp/state`)).json()) as {
      isPlaying: boolean;
      trackName: string;
      volumePct: number;
    };
    expect(plexState.isPlaying).toBe(true);
    expect(plexState.trackName).toBe('FLAC Deadlifts');
    expect(plexState.volumePct).toBe(82);

    const plexPlaylists = (await (await fetch(`${appBase}/api/music/plexamp/playlists`)).json()) as Array<{
      name: string;
    }>;
    expect(plexPlaylists).toHaveLength(1);
    expect(plexPlaylists[0].name).toBe('Lossless PR Mix');

    const plexTest = (await (
      await fetch(`${appBase}/api/music/plexamp/test`, { method: 'POST' })
    ).json()) as { ok: boolean };
    expect(plexTest.ok).toBe(true);

    // 2. YouTube Music Desktop API
    const ytmCfg = await fetch(`${appBase}/api/music/youtube_music/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        apiUrl: `${mockBase}/ytm`,
        authToken: 'ytm-secret',
      }),
    });
    expect(ytmCfg.status).toBe(200);

    const ytmState = (await (await fetch(`${appBase}/api/music/youtube_music/state`)).json()) as {
      isPlaying: boolean;
      trackName: string;
      volumePct: number;
    };
    expect(ytmState.isPlaying).toBe(true);
    expect(ytmState.trackName).toBe('Squat Rack Synth');
    expect(ytmState.volumePct).toBe(64);

    // 3. Amazon Music & Apple Music via Home Assistant media_player bridge
    await fetch(`${appBase}/api/music/amazon_music/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ haEntityId: 'media_player.gym_echo' }),
    });
    await fetch(`${appBase}/api/music/apple_music/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ haEntityId: 'media_player.gym_echo' }),
    });

    const amzState = (await (await fetch(`${appBase}/api/music/amazon_music/state`)).json()) as {
      isPlaying: boolean;
      trackName: string;
      volumePct: number;
    };
    expect(amzState.isPlaying).toBe(true);
    expect(amzState.trackName).toBe('Iron Anthem');
    expect(amzState.volumePct).toBe(65);

    const appleTest = (await (
      await fetch(`${appBase}/api/music/apple_music/test`, { method: 'POST' })
    ).json()) as { ok: boolean; message: string };
    expect(appleTest.ok).toBe(true);
    expect(appleTest.message).toContain('Gym Echo Studio');

    // Verify providers list now reports all 4 as configured
    const providers = (await (await fetch(`${appBase}/api/music/providers`)).json()) as Array<{
      id: string;
      configured: boolean;
    }>;
    for (const id of ['plexamp', 'youtube_music', 'apple_music', 'amazon_music']) {
      expect(providers.find((p) => p.id === id)?.configured).toBe(true);
    }

    expect(receivedCalls.some((c) => c.url === '/ha/webhook' && c.auth === 'Bearer ha-hook-secret')).toBe(
      true
    );
  });
});

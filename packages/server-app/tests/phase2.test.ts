import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DEFAULT_USERS } from '../prisma/seed';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { musicRegistry } from '../src/server/music/Registry';

describe('Phase 2 Nice-to-Have Features', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  let originalMediaUrl: string | null = null;
  let testedExerciseId: string | null = null;

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
  });

  afterAll(async () => {
    if (testedExerciseId) {
      await prisma.exercise
        .update({ where: { id: testedExerciseId }, data: { mediaUrl: originalMediaUrl } })
        .catch(() => {});
    }
    await srv.close();
    await prisma.$disconnect();
  });

  it('registers all 5 music providers (Spotify, YouTube Music, Apple Music, Amazon Music, Plexamp)', async () => {
    const res = await fetch(`${baseUrl}/api/music/providers`);
    expect(res.status).toBe(200);
    const providers = (await res.json()) as Array<{ id: string; displayName: string }>;
    const ids = providers.map((p) => p.id);

    expect(ids).toEqual(
      expect.arrayContaining([
        'spotify',
        'youtube_music',
        'apple_music',
        'amazon_music',
        'plexamp',
      ])
    );

    // Exercise Amazon Music & Plexamp transport + ducking
    for (const pid of ['amazon_music', 'plexamp']) {
      const p = musicRegistry.get(pid);
      await p.play(DEFAULT_USERS.USER_ONE_ID);
      const st = await p.getPlaybackState(DEFAULT_USERS.USER_ONE_ID);
      expect(st.isPlaying).toBe(true);
      await p.nextTrack(DEFAULT_USERS.USER_ONE_ID);
      await p.duckVolume?.(DEFAULT_USERS.USER_ONE_ID, 250, 15);
      const ducked = await p.getPlaybackState(DEFAULT_USERS.USER_ONE_ID);
      expect(ducked.volumePct).toBeLessThanOrEqual(15);
    }
  });

  it('serves self-hosted exercise demo loops from /media and supports PATCH /api/exercises/:id/media', async () => {
    const exRes = await fetch(`${baseUrl}/api/exercises`);
    expect(exRes.status).toBe(200);
    const exercises = (await exRes.json()) as Array<{ id: string; name: string; mediaUrl: string | null }>;
    expect(exercises.length).toBeGreaterThanOrEqual(50);

    const bench = exercises.find((e) => e.name === 'Bench Press');
    expect(bench).toBeDefined();
    expect(bench?.mediaUrl).toBe('/media/chest-press.svg');

    // Verify static SVG loop is served over HTTP
    const mediaRes = await fetch(`${baseUrl}${bench?.mediaUrl}`);
    expect(mediaRes.status).toBe(200);
    const svgText = await mediaRes.text();
    expect(svgText).toContain('<svg');
    expect(svgText).toContain('CHEST PRESS');

    // Update mediaUrl via PATCH and restore in afterAll
    testedExerciseId = bench!.id;
    originalMediaUrl = bench!.mediaUrl;
    const patchRes = await fetch(`${baseUrl}/api/exercises/${bench!.id}/media`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mediaUrl: '/media/custom-bench.mp4' }),
    });
    expect(patchRes.status).toBe(200);
    const patched = (await patchRes.json()) as { mediaUrl: string };
    expect(patched.mediaUrl).toBe('/media/custom-bench.mp4');
  });

  it('publishes Home Assistant / MQTT event bus payloads and exposes diagnostics', async () => {
    const testRes = await fetch(`${baseUrl}/api/integrations/ha/test`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ note: 'vitest ha ping' }),
    });
    expect(testRes.status).toBe(200);
    const payload = (await testRes.json()) as {
      status: string;
      event: { topic: string; payload: { event: string; note: string } };
      bus: { recentEvents: Array<{ topic: string }> };
    };

    expect(payload.status).toBe('published');
    expect(payload.event.topic).toBe('swonal/session');
    expect(payload.event.payload.event).toBe('test_ping');
    expect(payload.event.payload.note).toBe('vitest ha ping');
    expect(payload.bus.recentEvents.length).toBeGreaterThan(0);

    const statusRes = await fetch(`${baseUrl}/api/integrations/ha/status`);
    expect(statusRes.status).toBe(200);
    const statusJson = (await statusRes.json()) as { mqttTopicPrefix: string; recentEvents: unknown[] };
    expect(statusJson.mqttTopicPrefix).toBe('swonal');
    expect(statusJson.recentEvents.length).toBeGreaterThan(0);
  });

  it('supports progressive overload recommendations, CSV/JSON export, SQLite backup, and BYOK AI Coach', async () => {
    const exRes = await fetch(`${baseUrl}/api/exercises`);
    const exercises = (await exRes.json()) as Array<{ id: string }>;
    const overloadRes = await fetch(
      `${baseUrl}/api/overload/${DEFAULT_USERS.USER_ONE_ID}/${exercises[0].id}`
    );
    expect(overloadRes.status).toBe(200);
    const overload = (await overloadRes.json()) as { recommendedWeightLb: number };
    expect(overload.recommendedWeightLb).toBeGreaterThanOrEqual(5);

    const csvRes = await fetch(`${baseUrl}/api/export/sessions.csv?userId=${DEFAULT_USERS.USER_ONE_ID}`);
    expect(csvRes.status).toBe(200);
    expect(await csvRes.text()).toContain('SessionId,Routine,StartedAt,Exercise');

    const jsonRes = await fetch(`${baseUrl}/api/export/sessions.json?userId=${DEFAULT_USERS.USER_ONE_ID}`);
    expect(jsonRes.status).toBe(200);
    const exported = (await jsonRes.json()) as { activityType: string; sessions: unknown[] };
    expect(exported.activityType).toBe('TraditionalStrengthTraining');

    const backupRes = await fetch(`${baseUrl}/api/admin/backup/db`);
    expect(backupRes.status).toBe(200);
    const buf = Buffer.from(await backupRes.arrayBuffer());
    expect(buf.subarray(0, 15).toString('utf8')).toBe('SQLite format 3');

    const cfgRes = await fetch(`${baseUrl}/api/coach/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'local', model: 'swonal-local-coach-v1' }),
    });
    expect(cfgRes.status).toBe(200);

    const askRes = await fetch(`${baseUrl}/api/coach/ask`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: DEFAULT_USERS.USER_ONE_ID, prompt: 'Add 5 pounds' }),
    });
    expect(askRes.status).toBe(200);
    const coach = (await askRes.json()) as {
      reply: string;
      suggestedAction?: { type: string; deltaLb?: number };
    };
    expect(coach.suggestedAction?.type).toBe('set_weight_delta');
    expect(coach.suggestedAction?.deltaLb).toBe(5);
  });
});

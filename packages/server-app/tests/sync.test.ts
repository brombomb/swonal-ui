import fs from 'fs';
import os from 'os';
import path from 'path';
import WebSocket from 'ws';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DEFAULT_USERS } from '../prisma/seed';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { queueWorkoutForSession } from '../src/server/mcp/mcpServer';
import { createRotatingSqliteBackup } from '../src/server/services/backupService';

describe('Optional Server Sync, Tombstones, 24/7 MCP Queue & Backups', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  let wsUrl: string;
  const createdSessionIds: string[] = [];
  const createdTemplateIds: string[] = [];
  let tempBackupDir: string;

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
    wsUrl = `ws://127.0.0.1:${srv.port}`;
    tempBackupDir = fs.mkdtempSync(path.join(os.tmpdir(), 'swonal-backup-test-'));
  });

  afterAll(async () => {
    for (const sid of createdSessionIds) {
      await prisma.workoutSession.delete({ where: { id: sid } }).catch(() => {});
    }
    for (const tid of createdTemplateIds) {
      await prisma.workoutTemplate.delete({ where: { id: tid } }).catch(() => {});
    }
    await prisma.appSetting.deleteMany({ where: { key: 'pending_queued_workout' } }).catch(() => {});
    await srv.close();
    await prisma.$disconnect();
    fs.rmSync(tempBackupDir, { recursive: true, force: true });
  });

  it('idempotently pushes offline workouts, remaps colliding exercise names, and enforces PR max-envelope', async () => {
    const seededBench = await prisma.exercise.findUnique({ where: { name: 'Bench Press' } });
    expect(seededBench).not.toBeNull();

    const localExId = 'local-ex-bench-temp-99';
    const sessId = `sync-sess-${Date.now()}`;
    const setId = `sync-set-${Date.now()}`;
    createdSessionIds.push(sessId);

    const pushBody = {
      deviceId: 'tonal-test-1',
      pushedAtMs: Date.now(),
      users: [],
      exercises: [
        {
          id: localExId,
          name: 'Bench Press',
          muscleGroup: 'Chest',
          accessory: 'HANDLES',
          recommendedHeight: 4,
          recommendedRotation: 2,
          recommendedAngle: 1,
          unilateral: false,
          updatedAtMs: 1000, // older than server row -> server canonical metadata wins
        },
      ],
      templates: [],
      sessions: [
        {
          id: sessId,
          userId: DEFAULT_USERS.USER_ONE_ID,
          templateId: null,
          startedAt: new Date(Date.now() - 600_000).toISOString(),
          completedAt: new Date().toISOString(),
          totalVolume: 2000,
          updatedAtMs: Date.now(),
        },
      ],
      setLogs: [
        {
          id: setId,
          sessionId: sessId,
          exerciseId: localExId,
          setNumber: 1,
          mode: 'chains',
          modifierPct: 15,
          weightLb: 200,
          repsCompleted: 10,
          volumeLb: 2000,
          timestamp: new Date().toISOString(),
          updatedAtMs: Date.now(),
        },
      ],
      personalRecords: [
        {
          id: `sync-pr-${Date.now()}`,
          userId: DEFAULT_USERS.USER_ONE_ID,
          exerciseId: localExId,
          oneRepMaxEst: 266.7,
          maxWeightLb: 200,
          updatedAtMs: Date.now(),
        },
        {
          // Stale lower PR attempt should never downgrade max envelope
          id: `sync-pr-stale-${Date.now()}`,
          userId: DEFAULT_USERS.USER_ONE_ID,
          exerciseId: localExId,
          oneRepMaxEst: 90,
          maxWeightLb: 70,
          updatedAtMs: Date.now() - 5000,
        },
      ],
      friends: [],
    };

    const res1 = await fetch(`${baseUrl}/api/sync/push`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(pushBody),
    });
    expect(res1.status).toBe(200);
    const data1 = (await res1.json()) as {
      ok: boolean;
      exerciseIdRemap: Record<string, string>;
      acceptedCounts: Record<string, number>;
    };
    expect(data1.ok).toBe(true);
    expect(data1.exerciseIdRemap[localExId]).toBe(seededBench!.id);

    // Duplicate push must be 100% idempotent (0 duplicate setLogs inserted, volume unchanged)
    const res2 = await fetch(`${baseUrl}/api/sync/push`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(pushBody),
    });
    expect(res2.status).toBe(200);
    const data2 = (await res2.json()) as { acceptedCounts: Record<string, number> };
    expect(data2.acceptedCounts.setLogs).toBe(0);

    const dbSession = await prisma.workoutSession.findUnique({
      where: { id: sessId },
      include: { setLogs: true },
    });
    expect(dbSession?.setLogs.length).toBe(1);
    expect(dbSession?.setLogs[0].exerciseId).toBe(seededBench!.id);
    expect(dbSession?.setLogs[0].modifierPct).toBe(15);
    expect(dbSession?.totalVolume).toBe(2000);

    const pr = await prisma.personalRecord.findUnique({
      where: {
        userId_exerciseId: {
          userId: DEFAULT_USERS.USER_ONE_ID,
          exerciseId: seededBench!.id,
        },
      },
    });
    expect(pr?.maxWeightLb).toBeGreaterThanOrEqual(200);
    expect(pr?.oneRepMaxEst).toBeGreaterThanOrEqual(266.7);
  });

  it('persists MCP queued workouts while Tonal is offline and delivers via pull & /ws/sync', async () => {
    const seededTpl = await prisma.workoutTemplate.findFirst({ where: { deletedAt: null } });
    expect(seededTpl).not.toBeNull();

    // Queue while Tonal is offline
    const queued = await queueWorkoutForSession({
      userId: DEFAULT_USERS.USER_ONE_ID,
      templateId: seededTpl!.id,
    });
    expect(queued.templateId).toBe(seededTpl!.id);

    // Pull returns pendingQueuedWorkout
    const pullRes = await fetch(`${baseUrl}/api/sync/pull?since=0`);
    expect(pullRes.status).toBe(200);
    const pullJson = (await pullRes.json()) as { pendingQueuedWorkout: { templateId: string } | null };
    expect(pullJson.pendingQueuedWorkout?.templateId).toBe(seededTpl!.id);

    // Connecting on /ws/sync also receives workout_queued immediately
    const receivedOnWs = await new Promise<string>((resolve, reject) => {
      const ws = new WebSocket(`${wsUrl}/ws/sync`);
      const t = setTimeout(() => {
        ws.close();
        reject(new Error('Timed out waiting for workout_queued on /ws/sync'));
      }, 4000);
      ws.on('message', (raw) => {
        const str = raw.toString();
        if (str.includes('workout_queued')) {
          clearTimeout(t);
          ws.close();
          resolve(str);
        }
      });
      ws.on('error', (err) => {
        clearTimeout(t);
        reject(err);
      });
    });
    expect(receivedOnWs).toContain(seededTpl!.id);

    // Acknowledge with stale queuedAt must NOT clear a newly queued workout
    const ackStaleRes = await fetch(`${baseUrl}/api/sync/ack-queued-workout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ queuedAt: '2020-01-01T00:00:00.000Z' }),
    });
    expect(ackStaleRes.status).toBe(200);

    const pullStillQueued = await fetch(`${baseUrl}/api/sync/pull?since=0`);
    const stillJson = (await pullStillQueued.json()) as {
      pendingQueuedWorkout: { templateId: string; queuedAt: string } | null;
    };
    expect(stillJson.pendingQueuedWorkout?.queuedAt).toBe(queued.queuedAt);

    // Matching queuedAt cleanly clears pending queue
    const ackRes = await fetch(`${baseUrl}/api/sync/ack-queued-workout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ queuedAt: queued.queuedAt }),
    });
    expect(ackRes.status).toBe(200);

    const pullAfter = await fetch(`${baseUrl}/api/sync/pull?since=0`);
    const afterJson = (await pullAfter.json()) as { pendingQueuedWorkout: unknown };
    expect(afterJson.pendingQueuedWorkout).toBeNull();
  });

  it('propagates soft-delete tombstones via /api/sync/pull and /api/sync/restore-bundle and creates rotating SQLite backups', async () => {
    const tplId = `tombstone-tpl-${Date.now()}`;
    createdTemplateIds.push(tplId);
    const beforeMs = Date.now() - 1000;

    await fetch(`${baseUrl}/api/sync/push`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId: 'tonal-test-1',
        pushedAtMs: Date.now(),
        templates: [
          {
            id: tplId,
            userId: DEFAULT_USERS.USER_ONE_ID,
            name: 'Temporary Tombstone Test',
            blocksJson: '[]',
            scheduleDays: [1, 3],
            updatedAtMs: Date.now(),
          },
        ],
      }),
    });

    const delRes = await fetch(`${baseUrl}/api/templates/${tplId}`, { method: 'DELETE' });
    expect(delRes.status).toBe(200);

    // Active GET /api/templates hides tombstoned template
    const listRes = await fetch(`${baseUrl}/api/templates`);
    const list = (await listRes.json()) as Array<{ id: string }>;
    expect(list.some((t) => t.id === tplId)).toBe(false);

    // Delta /api/sync/pull?since=... includes deletedAtMs tombstone
    const pullRes = await fetch(`${baseUrl}/api/sync/pull?since=${beforeMs}`);
    const pull = (await pullRes.json()) as {
      templates: Array<{ id: string; deletedAtMs: number | null }>;
    };
    const tomb = pull.templates.find((t) => t.id === tplId);
    expect(tomb).toBeDefined();
    expect(tomb?.deletedAtMs).not.toBeNull();

    // Restore bundle also retains soft-deleted parent tombstones so foreign keys hold on restore
    const restoreRes = await fetch(`${baseUrl}/api/sync/restore-bundle`);
    expect(restoreRes.status).toBe(200);
    const restoreJson = (await restoreRes.json()) as {
      templates: Array<{ id: string; deletedAtMs: number | null }>;
    };
    const restoredTomb = restoreJson.templates.find((t) => t.id === tplId);
    expect(restoredTomb?.deletedAtMs).not.toBeNull();

    // CSWSH check: disallowed external WebSocket Origin is rejected with 403
    await expect(
      new Promise<void>((resolve, reject) => {
        const ws = new WebSocket(`${wsUrl}/ws`, { headers: { Origin: 'https://evil.example' } });
        ws.on('open', () => {
          ws.close();
          reject(new Error('Expected CSWSH upgrade to be rejected'));
        });
        ws.on('unexpected-response', (_req, res) => {
          expect(res.statusCode).toBe(403);
          resolve();
        });
        ws.on('error', () => {
          resolve();
        });
      })
    ).resolves.toBeUndefined();

    // Backup service snapshot check
    const snapPath = await createRotatingSqliteBackup(prisma, tempBackupDir);
    expect(fs.existsSync(snapPath)).toBe(true);
    expect(fs.statSync(snapPath).size).toBeGreaterThan(4096);
  });
});

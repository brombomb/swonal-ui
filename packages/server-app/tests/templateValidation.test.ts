import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DEFAULT_USERS } from '../prisma/seed';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { createCustomWorkoutTemplate } from '../src/server/mcp/mcpServer';
import { UnknownExerciseError } from '../src/server/services/exerciseValidation';

function block(exerciseId: string) {
  return {
    blockType: 'STRAIGHT_SET' as const,
    restSeconds: 60,
    items: [
      { exerciseId, sets: 3, targetReps: 10, mode: 'standard' as const, modifierPct: 0, targetWeightLb: 30 },
    ],
  };
}

describe('Workout templates reject unknown exercise ids', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  let validExerciseId: string;
  const createdTemplateIds: string[] = [];
  const createdActivityIds: string[] = [];
  const createdSessionIds: string[] = [];
  const createdExerciseIds: string[] = [];

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
    const ex = await prisma.exercise.findFirstOrThrow({ where: { deletedAt: null } });
    validExerciseId = ex.id;
  });

  afterAll(async () => {
    await prisma.friendActivity.deleteMany({ where: { eventId: { in: createdActivityIds } } });
    await prisma.workoutSession.deleteMany({ where: { id: { in: createdSessionIds } } });
    if (createdTemplateIds.length > 0) {
      await prisma.workoutTemplate.deleteMany({ where: { id: { in: createdTemplateIds } } });
    }
    await prisma.exercise.deleteMany({ where: { id: { in: createdExerciseIds } } });
    await prisma.appSetting.deleteMany({ where: { key: 'pending_queued_workout' } });
    await srv.close();
    await prisma.$disconnect();
  });

  it('POST /api/templates returns 400 listing the unknown ids and stores nothing', async () => {
    const bogus = randomUUID();
    const before = await prisma.workoutTemplate.count();
    const res = await fetch(`${baseUrl}/api/templates`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: DEFAULT_USERS.USER_ONE_ID,
        name: 'Has a typo',
        blocks: [block(validExerciseId), block(bogus)],
      }),
    });
    expect(res.status).toBe(400);
    const body = (await res.json()) as { error: string; unknownExerciseIds: string[] };
    expect(body.unknownExerciseIds).toEqual([bogus]);
    expect(await prisma.workoutTemplate.count()).toBe(before);
  });

  it('POST /api/templates still accepts templates whose exercises all exist', async () => {
    const res = await fetch(`${baseUrl}/api/templates`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: DEFAULT_USERS.USER_ONE_ID,
        name: 'All valid',
        blocks: [block(validExerciseId)],
      }),
    });
    expect(res.status).toBe(201);
    const created = (await res.json()) as { id: string };
    createdTemplateIds.push(created.id);
  });

  it('PUT /api/templates/:id rejects unknown ids and leaves the template unchanged', async () => {
    const tpl = await createCustomWorkoutTemplate({
      userId: DEFAULT_USERS.USER_ONE_ID,
      name: 'Will not change',
      blocks: [block(validExerciseId)],
    });
    createdTemplateIds.push(tpl.id);

    const res = await fetch(`${baseUrl}/api/templates/${tpl.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ blocks: [block(randomUUID())] }),
    });
    expect(res.status).toBe(400);
    const after = await prisma.workoutTemplate.findUniqueOrThrow({ where: { id: tpl.id } });
    expect(after.blocksJson).toBe(tpl.blocksJson);
  });

  it('treats soft-deleted exercises as unknown (MCP/direct callers get UnknownExerciseError)', async () => {
    const doomed = await prisma.exercise.create({
      data: { name: `Deleted test move ${randomUUID()}`, muscleGroup: 'Test', deletedAt: new Date() },
    });
    try {
      await expect(
        createCustomWorkoutTemplate({
          userId: DEFAULT_USERS.USER_ONE_ID,
          name: 'Uses a deleted move',
          blocks: [block(doomed.id)],
        })
      ).rejects.toBeInstanceOf(UnknownExerciseError);
    } finally {
      await prisma.exercise.delete({ where: { id: doomed.id } });
    }
  });

  describe('Nostr routine import', () => {
    async function cacheSharedRoutine(blocks: unknown[]) {
      const eventId = randomUUID().replace(/-/g, '');
      await prisma.friendActivity.create({
        data: {
          eventId,
          friendPubkeyHex: 'ab'.repeat(32),
          friendDisplayName: 'Test Friend',
          kind: 30102,
          createdAt: new Date(),
          contentJson: JSON.stringify({ type: 'routine_share', name: 'Shared', blocks }),
        },
      });
      createdActivityIds.push(eventId);
      return eventId;
    }

    function importIt(eventId: string) {
      return fetch(`${baseUrl}/api/friends/import-routine/${DEFAULT_USERS.USER_ONE_ID}/${eventId}`, {
        method: 'POST',
      });
    }

    it('rejects a shared routine with unknown exercise ids and stores nothing', async () => {
      const bogus = randomUUID();
      const eventId = await cacheSharedRoutine([block(validExerciseId), block(bogus)]);
      const before = await prisma.workoutTemplate.count();

      const res = await importIt(eventId);
      expect(res.status).toBe(400);
      const body = (await res.json()) as { error: string; unknownExerciseIds: string[] };
      expect(body.unknownExerciseIds).toEqual([bogus]);
      expect(body.error).toContain("aren't in your library");
      expect(await prisma.workoutTemplate.count()).toBe(before);
    });

    it('rejects shared steps that carry no exercise id', async () => {
      const eventId = await cacheSharedRoutine([
        { blockType: 'STRAIGHT_SET', restSeconds: 60, items: [{ sets: 3, targetReps: 10 }] },
      ]);
      const res = await importIt(eventId);
      expect(res.status).toBe(400);
      const body = (await res.json()) as { unknownExerciseIds: string[] };
      expect(body.unknownExerciseIds).toEqual(['']);
    });

    it('still imports a shared routine whose exercises all exist', async () => {
      const eventId = await cacheSharedRoutine([block(validExerciseId)]);
      const res = await importIt(eventId);
      expect(res.status).toBe(200);
      const created = (await res.json()) as { id: string };
      createdTemplateIds.push(created.id);
    });
  });

  describe('POST /api/sync/push', () => {
    it('skips and reports templates with unknown exercise ids but keeps the rest of the push', async () => {
      const now = Date.now();
      const bogus = randomUUID();
      const customExId = randomUUID();
      const goodTplId = randomUUID();
      const badTplId = randomUUID();
      const tombstoneTplId = randomUUID();
      const sessId = randomUUID();
      const setId = randomUUID();
      createdTemplateIds.push(goodTplId, badTplId, tombstoneTplId);
      createdSessionIds.push(sessId);
      createdExerciseIds.push(customExId);
      const tpl = (id: string, exerciseIds: string[], extra: Record<string, unknown> = {}) => ({
        id,
        userId: DEFAULT_USERS.USER_ONE_ID,
        name: `Synced ${id.slice(0, 8)}`,
        blocksJson: JSON.stringify(exerciseIds.map((e) => block(e))),
        updatedAtMs: now,
        ...extra,
      });

      const res = await fetch(`${baseUrl}/api/sync/push`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          deviceId: 'template-validation-test',
          pushedAtMs: now,
          // A custom exercise created on the device in the same push must count as known.
          exercises: [
            { id: customExId, name: `Device move ${customExId}`, muscleGroup: 'Test', updatedAtMs: now },
          ],
          templates: [
            tpl(goodTplId, [validExerciseId, customExId]),
            tpl(badTplId, [validExerciseId, bogus]),
            // Deleting a template is never blocked by what it references.
            tpl(tombstoneTplId, [bogus], { deletedAtMs: now }),
          ],
          sessions: [
            {
              id: sessId,
              userId: DEFAULT_USERS.USER_ONE_ID,
              templateId: badTplId,
              startedAt: new Date(now - 600_000).toISOString(),
              completedAt: new Date(now).toISOString(),
              totalVolume: 300,
              updatedAtMs: now,
            },
          ],
          setLogs: [
            {
              id: setId,
              sessionId: sessId,
              exerciseId: validExerciseId,
              setNumber: 1,
              weightLb: 30,
              repsCompleted: 10,
              volumeLb: 300,
              timestamp: new Date(now).toISOString(),
              updatedAtMs: now,
            },
          ],
        }),
      });
      expect(res.status).toBe(200);
      const body = (await res.json()) as {
        acceptedCounts: Record<string, number>;
        rejectedTemplates: Array<{ id: string; unknownExerciseIds: string[] }>;
      };
      expect(body.rejectedTemplates).toEqual([
        expect.objectContaining({ id: badTplId, unknownExerciseIds: [bogus] }),
      ]);
      expect(body.acceptedCounts.templates).toBe(2);

      expect(await prisma.workoutTemplate.findUnique({ where: { id: goodTplId } })).not.toBeNull();
      expect(await prisma.workoutTemplate.findUnique({ where: { id: badTplId } })).toBeNull();
      const tombstone = await prisma.workoutTemplate.findUnique({ where: { id: tombstoneTplId } });
      expect(tombstone?.deletedAt).not.toBeNull();

      // The push's session and set still land; the session just loses its link to the skipped template.
      const session = await prisma.workoutSession.findUnique({
        where: { id: sessId },
        include: { setLogs: true },
      });
      expect(session?.templateId).toBeNull();
      expect(session?.setLogs.map((l) => l.id)).toEqual([setId]);
    });
  });

  describe('queueing a routine', () => {
    it('POST /api/templates/:id/queue refuses a routine whose exercise was deleted, and queues nothing', async () => {
      const doomed = await prisma.exercise.create({
        data: { name: `Queued then deleted ${randomUUID()}`, muscleGroup: 'Test' },
      });
      createdExerciseIds.push(doomed.id);
      const tpl = await createCustomWorkoutTemplate({
        userId: DEFAULT_USERS.USER_ONE_ID,
        name: 'Stale routine',
        blocks: [block(validExerciseId), block(doomed.id)],
      });
      createdTemplateIds.push(tpl.id);
      await prisma.exercise.update({ where: { id: doomed.id }, data: { deletedAt: new Date() } });
      await prisma.appSetting.deleteMany({ where: { key: 'pending_queued_workout' } });

      const res = await fetch(`${baseUrl}/api/templates/${tpl.id}/queue`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: DEFAULT_USERS.USER_ONE_ID }),
      });
      expect(res.status).toBe(400);
      const body = (await res.json()) as { error: string; unknownExerciseIds: string[] };
      expect(body.unknownExerciseIds).toEqual([doomed.id]);
      expect(body.error).toContain("Can't queue this routine");
      expect(await prisma.appSetting.findUnique({ where: { key: 'pending_queued_workout' } })).toBeNull();
    });
  });
});

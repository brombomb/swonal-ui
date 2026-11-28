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

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
    const ex = await prisma.exercise.findFirstOrThrow({ where: { deletedAt: null } });
    validExerciseId = ex.id;
  });

  afterAll(async () => {
    await prisma.friendActivity.deleteMany({ where: { eventId: { in: createdActivityIds } } });
    if (createdTemplateIds.length > 0) {
      await prisma.workoutTemplate.deleteMany({ where: { id: { in: createdTemplateIds } } });
    }
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
});

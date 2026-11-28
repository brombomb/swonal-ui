import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DEFAULT_USERS } from '../prisma/seed';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { closePool, publishRoutine } from '../src/server/services/nostrFriendsService';
import { pickExerciseByName } from '../src/server/services/sharedRoutineExercises';

function block(...exerciseIds: string[]) {
  return {
    blockType: 'STRAIGHT_SET',
    restSeconds: 60,
    items: exerciseIds.map((exerciseId) => ({
      exerciseId,
      sets: 3,
      targetReps: 10,
      mode: 'standard',
      modifierPct: 0,
      targetWeightLb: 30,
    })),
  };
}

type Ref = { id: string; name: string; muscleGroup?: string; accessory?: string };

describe('Shared routines resolve exercises by name across installs', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  let exA: { id: string; name: string; muscleGroup: string; accessory: string };
  let exB: { id: string; name: string; muscleGroup: string; accessory: string };
  const createdTemplateIds: string[] = [];
  const createdActivityIds: string[] = [];
  const createdExerciseIds: string[] = [];

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
    const seeded = await prisma.exercise.findMany({
      where: { deletedAt: null },
      orderBy: { name: 'asc' },
      take: 2,
    });
    [exA, exB] = seeded;
  });

  afterAll(async () => {
    closePool();
    await prisma.friendActivity.deleteMany({ where: { eventId: { in: createdActivityIds } } });
    await prisma.workoutTemplate.deleteMany({ where: { id: { in: createdTemplateIds } } });
    await prisma.exercise.deleteMany({ where: { id: { in: createdExerciseIds } } });
    await srv.close();
    await prisma.$disconnect();
  });

  async function cacheShared(content: Record<string, unknown>) {
    const eventId = randomUUID().replace(/-/g, '');
    await prisma.friendActivity.create({
      data: {
        eventId,
        friendPubkeyHex: 'cd'.repeat(32),
        friendDisplayName: 'Remote Friend',
        kind: 30102,
        createdAt: new Date(),
        contentJson: JSON.stringify({ type: 'routine_share', name: 'Remote Push', ...content }),
      },
    });
    createdActivityIds.push(eventId);
    return eventId;
  }

  async function importIt(eventId: string) {
    const res = await fetch(`${baseUrl}/api/friends/import-routine/${DEFAULT_USERS.USER_ONE_ID}/${eventId}`, {
      method: 'POST',
    });
    const body = (await res.json()) as any;
    if (res.ok) createdTemplateIds.push(body.id);
    return { status: res.status, body };
  }

  function stepIds(blocksJson: string): string[] {
    return (JSON.parse(blocksJson) as Array<{ items: Array<{ exerciseId: string }> }>).flatMap((b) =>
      b.items.map((i) => i.exerciseId)
    );
  }

  it('publishRoutine includes name, muscle group and accessory for each exercise, keeping the ids', async () => {
    const tpl = await prisma.workoutTemplate.create({
      data: {
        userId: DEFAULT_USERS.USER_ONE_ID,
        name: 'Shared with names',
        blocksJson: JSON.stringify([block(exA.id, exB.id), block(exA.id)]),
      },
    });
    createdTemplateIds.push(tpl.id);

    const event = await publishRoutine(DEFAULT_USERS.USER_ONE_ID, tpl.id);
    createdActivityIds.push(event.id);
    const payload = JSON.parse(event.content);
    expect(stepIds(JSON.stringify(payload.blocks))).toEqual([exA.id, exB.id, exA.id]);
    expect(payload.exercises).toEqual([
      { id: exA.id, name: exA.name, muscleGroup: exA.muscleGroup, accessory: exA.accessory },
      { id: exB.id, name: exB.name, muscleGroup: exB.muscleGroup, accessory: exB.accessory },
    ]);
  });

  it('imports a routine shared from another install by matching names, rewriting the ids', async () => {
    // Publish here, then make it look like it came from an install whose seed gave other ids.
    const tpl = await prisma.workoutTemplate.create({
      data: {
        userId: DEFAULT_USERS.USER_ONE_ID,
        name: 'Round trip',
        blocksJson: JSON.stringify([block(exA.id, exB.id)]),
      },
    });
    createdTemplateIds.push(tpl.id);
    const event = await publishRoutine(DEFAULT_USERS.USER_ONE_ID, tpl.id);
    createdActivityIds.push(event.id);
    const remoteIds = new Map([
      [exA.id, randomUUID()],
      [exB.id, randomUUID()],
    ]);
    let content = event.content;
    for (const [local, remote] of remoteIds) content = content.split(local).join(remote);
    const payload = JSON.parse(content);
    // Names differ only by case and surrounding spaces on the other install.
    payload.exercises[0].name = `  ${exA.name.toUpperCase()} `;

    const eventId = await cacheShared(payload);
    const { status, body } = await importIt(eventId);
    expect(status).toBe(200);
    expect(stepIds(body.blocksJson)).toEqual([exA.id, exB.id]);
    // Everything else about the step survives the rewrite.
    expect(body.blocks[0].items[1]).toEqual({ ...block(exB.id).items[0] });
  });

  it('keeps an id that exists here even when the payload names another exercise (same install)', async () => {
    const eventId = await cacheShared({
      blocks: [block(exA.id)],
      exercises: [{ id: exA.id, name: exB.name }],
    });
    const { status, body } = await importIt(eventId);
    expect(status).toBe(200);
    expect(stepIds(body.blocksJson)).toEqual([exA.id]);
  });

  it('imports an old payload without names exactly as before, and still refuses unknown ids', async () => {
    const blocks = [block(exA.id)];
    const ok = await importIt(await cacheShared({ blocks }));
    expect(ok.status).toBe(200);
    expect(ok.body.blocksJson).toBe(JSON.stringify(blocks));

    const bogus = randomUUID();
    const bad = await importIt(await cacheShared({ blocks: [block(exA.id, bogus)] }));
    expect(bad.status).toBe(400);
    expect(bad.body.unknownExerciseIds).toEqual([bogus]);
    expect(bad.body.error).toBe(
      `Can't import this routine: it uses exercises that aren't in your library: ${bogus}`
    );
  });

  it('refuses the whole import, listing missing exercises by name and id, and stores nothing', async () => {
    const remoteA = randomUUID();
    const remoteMissing = randomUUID();
    const unnamed = randomUUID();
    const exercises: Ref[] = [
      { id: remoteA, name: exA.name },
      { id: remoteMissing, name: 'Zercher Moonwalk', muscleGroup: 'Legs', accessory: 'BAR' },
    ];
    const before = await prisma.workoutTemplate.count();
    const { status, body } = await importIt(
      await cacheShared({ blocks: [block(remoteA, remoteMissing, unnamed)], exercises })
    );
    expect(status).toBe(400);
    expect(body.unknownExerciseIds).toEqual([remoteMissing, unnamed]);
    expect(body.missingExercises).toEqual([{ id: remoteMissing, name: 'Zercher Moonwalk' }, { id: unnamed }]);
    expect(body.error).toContain(`Zercher Moonwalk (${remoteMissing})`);
    expect(body.error).toContain(unnamed);
    expect(await prisma.workoutTemplate.count()).toBe(before);
  });

  it('never matches a deleted exercise by name', async () => {
    const deleted = await prisma.exercise.create({
      data: { name: `Retired move ${randomUUID()}`, muscleGroup: 'Test', deletedAt: new Date() },
    });
    createdExerciseIds.push(deleted.id);
    const remote = randomUUID();
    const { status, body } = await importIt(
      await cacheShared({ blocks: [block(remote)], exercises: [{ id: remote, name: deleted.name }] })
    );
    expect(status).toBe(400);
    expect(body.missingExercises).toEqual([{ id: remote, name: deleted.name }]);
  });

  it('falls back to the name when the shared id exists here but was deleted', async () => {
    const deleted = await prisma.exercise.create({
      data: { name: `Old copy ${randomUUID()}`, muscleGroup: 'Test', deletedAt: new Date() },
    });
    createdExerciseIds.push(deleted.id);
    const { status, body } = await importIt(
      await cacheShared({ blocks: [block(deleted.id)], exercises: [{ id: deleted.id, name: exB.name }] })
    );
    expect(status).toBe(200);
    expect(stepIds(body.blocksJson)).toEqual([exB.id]);
  });
});

describe('pickExerciseByName', () => {
  it('matches trimmed and case-insensitively, preferring an exact match, then name order, then id', () => {
    const lib = [
      { id: 'b', name: 'bench press' },
      { id: 'a', name: 'Bench Press ' },
      { id: 'c', name: 'BENCH PRESS' },
      { id: 'd', name: 'Squat' },
    ];
    expect(pickExerciseByName(lib, 'Bench Press')?.id).toBe('a');
    expect(pickExerciseByName(lib, ' bench press')?.id).toBe('b');
    expect(pickExerciseByName(lib, 'Bench press')?.id).toBe('c'); // 'BENCH PRESS' < 'Bench Press ' < 'bench press'
    expect(pickExerciseByName(lib, 'Deadlift')).toBeUndefined();
    expect(pickExerciseByName([{ id: 'y', name: 'X' }, { id: 'x', name: 'X' }], 'x')?.id).toBe('x');
  });
});

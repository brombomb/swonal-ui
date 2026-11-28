import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DEFAULT_USERS } from '../prisma/seed';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { estimateOneRepMaxLb } from '../src/server/utils/units';

describe('A set with 0 reps never becomes a personal record', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  let exerciseId: string;
  const userId = DEFAULT_USERS.USER_ONE_ID;
  const sessionIds: string[] = [];

  async function newSession(): Promise<string> {
    const res = await fetch(`${baseUrl}/api/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId }),
    });
    expect(res.status).toBe(201);
    const s = (await res.json()) as { id: string };
    sessionIds.push(s.id);
    return s.id;
  }

  async function logSet(sessionId: string, weightLb: number, repsCompleted: number) {
    const res = await fetch(`${baseUrl}/api/sessions/${sessionId}/sets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId, exerciseId, setNumber: 1, weightLb, repsCompleted, restSeconds: 0 }),
    });
    expect(res.status).toBe(201);
    return (await res.json()) as { newPr: boolean };
  }

  function getPr() {
    return prisma.personalRecord.findUnique({
      where: { userId_exerciseId: { userId, exerciseId } },
    });
  }

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
    // An exercise this user has no PR for yet.
    const withPr = await prisma.personalRecord.findMany({ where: { userId }, select: { exerciseId: true } });
    const ex = await prisma.exercise.findFirstOrThrow({
      where: { deletedAt: null, id: { notIn: withPr.map((p) => p.exerciseId) } },
    });
    exerciseId = ex.id;
  });

  afterAll(async () => {
    await prisma.personalRecord.deleteMany({ where: { userId, exerciseId } });
    await prisma.setLog.deleteMany({ where: { sessionId: { in: sessionIds } } });
    await prisma.workoutSession.deleteMany({ where: { id: { in: sessionIds } } });
    await srv.close();
    await prisma.$disconnect();
  });

  it('estimates a 0 lb 1RM for 0 reps', () => {
    expect(estimateOneRepMaxLb(100, 0)).toBe(0);
    expect(estimateOneRepMaxLb(100, 1)).toBe(100);
    expect(estimateOneRepMaxLb(100, 10)).toBeCloseTo(133.3, 1);
  });

  it('a first-ever set with 0 reps creates no PR', async () => {
    const sid = await newSession();
    const result = await logSet(sid, 150, 0);
    expect(result.newPr).toBe(false);
    expect(await getPr()).toBeNull();
  });

  it('a 0-rep set heavier than the existing PR leaves the PR unchanged', async () => {
    const sid = await newSession();
    expect((await logSet(sid, 40, 8)).newPr).toBe(true);
    const before = await getPr();
    expect(before).not.toBeNull();

    const result = await logSet(sid, 120, 0);
    expect(result.newPr).toBe(false);
    const after = await getPr();
    expect(after?.maxWeightLb).toBe(before?.maxWeightLb);
    expect(after?.oneRepMaxEst).toBe(before?.oneRepMaxEst);
  });

  it('a 0-rep set is still logged (history and volume stay honest)', async () => {
    const sid = await newSession();
    await logSet(sid, 60, 0);
    const sets = await prisma.setLog.findMany({ where: { sessionId: sid } });
    expect(sets).toHaveLength(1);
    expect(sets[0].repsCompleted).toBe(0);
    expect(sets[0].volumeLb).toBe(0);
  });
});

describe('Sync never lets a 0-rep PR (no estimated 1RM) back in', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  const userId = DEFAULT_USERS.USER_ONE_ID;
  const stamp = Date.now();
  const sessionId = `zero-rep-sync-sess-${stamp}`;
  let freshExId: string; // no PR yet
  let legitExId: string; // no PR yet, gets a real PR in the same push
  let benchId: string; // has a real PR
  const cleanupExerciseIds: string[] = [];

  function getPr(exerciseId: string) {
    return prisma.personalRecord.findUnique({ where: { userId_exerciseId: { userId, exerciseId } } });
  }

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
    const withPr = await prisma.personalRecord.findMany({ where: { userId }, select: { exerciseId: true } });
    const noPr = await prisma.exercise.findMany({
      where: { deletedAt: null, id: { notIn: withPr.map((p) => p.exerciseId) } },
      take: 2,
    });
    expect(noPr).toHaveLength(2);
    [freshExId, legitExId] = noPr.map((e) => e.id);
    cleanupExerciseIds.push(freshExId, legitExId);
    benchId = (await prisma.exercise.findUniqueOrThrow({ where: { name: 'Bench Press' } })).id;
    if (!(await getPr(benchId))) {
      await prisma.personalRecord.create({ data: { userId, exerciseId: benchId, oneRepMaxEst: 85, maxWeightLb: 65 } });
      cleanupExerciseIds.push(benchId);
    }
  });

  afterAll(async () => {
    await prisma.personalRecord.deleteMany({ where: { userId, exerciseId: { in: cleanupExerciseIds } } });
    await prisma.workoutSession.deleteMany({ where: { id: sessionId } });
    await srv.close();
    await prisma.$disconnect();
  });

  it('push skips 0-1RM PR rows but keeps the rest of the push', async () => {
    const benchBefore = await getPr(benchId);
    const now = Date.now();
    const res = await fetch(`${baseUrl}/api/sync/push`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId: 'unpatched-peer',
        pushedAtMs: now,
        sessions: [
          {
            id: sessionId,
            userId,
            templateId: null,
            startedAt: new Date(now - 60_000).toISOString(),
            completedAt: new Date(now).toISOString(),
            totalVolume: 0,
            updatedAtMs: now,
          },
        ],
        setLogs: [
          {
            id: `zero-rep-sync-set-${stamp}`,
            sessionId,
            exerciseId: freshExId,
            setNumber: 1,
            weightLb: 150,
            repsCompleted: 0,
            volumeLb: 0,
            timestamp: new Date(now - 30_000).toISOString(),
            updatedAtMs: now,
          },
        ],
        personalRecords: [
          // 0-rep PR on an exercise with no PR: must not create one
          { id: `zr-pr-a-${stamp}`, userId, exerciseId: freshExId, oneRepMaxEst: 0, maxWeightLb: 150, updatedAtMs: now },
          // 0-rep PR heavier than an existing PR: must not raise its max weight
          {
            id: `zr-pr-b-${stamp}`,
            userId,
            exerciseId: benchId,
            oneRepMaxEst: 0,
            maxWeightLb: (benchBefore?.maxWeightLb ?? 0) + 100,
            updatedAtMs: now,
          },
          // A real PR in the same push still lands
          { id: `zr-pr-c-${stamp}`, userId, exerciseId: legitExId, oneRepMaxEst: 53.3, maxWeightLb: 40, updatedAtMs: now },
        ],
      }),
    });
    expect(res.status).toBe(200);
    const data = (await res.json()) as { acceptedCounts: Record<string, number> };
    expect(data.acceptedCounts.sessions).toBe(1);
    expect(data.acceptedCounts.setLogs).toBe(1);
    expect(data.acceptedCounts.personalRecords).toBe(1);

    expect(await getPr(freshExId)).toBeNull();
    const benchAfter = await getPr(benchId);
    expect(benchAfter?.maxWeightLb).toBe(benchBefore?.maxWeightLb);
    expect(benchAfter?.oneRepMaxEst).toBe(benchBefore?.oneRepMaxEst);
    const legit = await getPr(legitExId);
    expect(legit?.oneRepMaxEst).toBe(53.3);
    expect(legit?.maxWeightLb).toBe(40);

    const sets = await prisma.setLog.findMany({ where: { sessionId } });
    expect(sets).toHaveLength(1);
    expect(sets[0].repsCompleted).toBe(0);
  });

  it('pull and restore-bundle leave out 0-1RM PR rows already in the database', async () => {
    await prisma.personalRecord.deleteMany({ where: { userId, exerciseId: freshExId } });
    const stale = await prisma.personalRecord.create({
      data: { userId, exerciseId: freshExId, oneRepMaxEst: 0, maxWeightLb: 150 },
    });

    type Bundle = { personalRecords: Array<{ id: string }> };
    const pull = (await (await fetch(`${baseUrl}/api/sync/pull`)).json()) as Bundle;
    const since = (await (
      await fetch(`${baseUrl}/api/sync/pull?since=${stale.updatedAt.getTime() - 1}`)
    ).json()) as Bundle;
    const restore = (await (
      await fetch(`${baseUrl}/api/sync/restore-bundle`, { method: 'POST' })
    ).json()) as Bundle;

    for (const bundle of [pull, since, restore]) {
      expect(bundle.personalRecords.map((p) => p.id)).not.toContain(stale.id);
    }
    // Real PRs are still served
    expect(pull.personalRecords.length).toBeGreaterThan(0);
    expect(restore.personalRecords.map((p) => p.id).sort()).toEqual(pull.personalRecords.map((p) => p.id).sort());
  });
});

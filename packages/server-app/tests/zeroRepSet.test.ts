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

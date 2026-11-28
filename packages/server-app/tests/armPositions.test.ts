import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import {
  normalizeSyncedAllowedPositions,
  parsePositionSet,
  serializePositionSet,
} from '../src/server/utils/armPositions';

type ApiExercise = {
  id: string;
  name: string;
  recommendedHeight: number;
  recommendedRotation: number;
  recommendedAngle: number;
  allowedHeights: number[];
  allowedRotations: number[];
  allowedAngles: number[];
};

describe('Arm position scales & allowed position sets', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  const stamp = Date.now();
  const names: string[] = [];
  const syncIds: string[] = [];

  const exName = (label: string) => {
    const name = `ArmPos ${label} ${stamp}`;
    names.push(name);
    return name;
  };

  const postExercise = (body: Record<string, unknown>) =>
    fetch(`${baseUrl}/api/exercises`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ muscleGroup: 'Back', accessory: 'HANDLES', ...body }),
    });

  const patchExercise = (id: string, body: Record<string, unknown>) =>
    fetch(`${baseUrl}/api/exercises/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });

  const push = (exercises: Record<string, unknown>[]) =>
    fetch(`${baseUrl}/api/sync/push`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: 'armpos-test', pushedAtMs: Date.now(), exercises }),
    });

  const pullExercise = async (id: string) => {
    const res = await fetch(`${baseUrl}/api/sync/pull?since=0`);
    expect(res.status).toBe(200);
    const json = (await res.json()) as { exercises: ApiExercise[] };
    return json.exercises.find((e) => e.id === id);
  };

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
  });

  afterAll(async () => {
    await prisma.exercise.deleteMany({ where: { OR: [{ name: { in: names } }, { id: { in: syncIds } }] } });
    await srv.close();
    await prisma.$disconnect();
  });

  it('parses, dedupes and sorts stored comma-separated sets', () => {
    expect(parsePositionSet('')).toEqual([]);
    expect(parsePositionSet(null)).toEqual([]);
    expect(parsePositionSet('5, 4,4,')).toEqual([4, 5]);
    expect(serializePositionSet([6, 4, 4])).toBe('4,6');
    expect(
      normalizeSyncedAllowedPositions({
        recommendedHeight: 3,
        recommendedRotation: 4,
        recommendedAngle: 1,
        allowedHeights: [4, 99],
        allowedRotations: [0, 7],
        allowedAngles: undefined,
      })
    ).toEqual({ allowedHeights: '3,4', allowedRotations: '', allowedAngles: '' });
  });

  it('accepts rotation 6 and angle 5, rejects rotation 7 / angle 0 / height 13', async () => {
    const ok = await postExercise({
      name: exName('rot6'),
      recommendedHeight: 9,
      recommendedRotation: 6,
      recommendedAngle: 5,
    });
    expect(ok.status).toBe(201);
    const created = (await ok.json()) as ApiExercise;
    expect(created.recommendedRotation).toBe(6);
    expect(created.recommendedAngle).toBe(5);

    const badBodies = [
      { recommendedRotation: 7 },
      { recommendedAngle: 0 },
      { recommendedHeight: 13 },
      { recommendedRotation: 4, allowedRotations: [4, 7] },
    ];
    for (const [i, bad] of badBodies.entries()) {
      const res = await postExercise({ name: exName(`bad${i}`), ...bad });
      expect(res.status, JSON.stringify(bad)).toBe(400);
      const body = (await res.json()) as { error: string };
      expect(body.error).toBe('Validation error');
    }
  });

  it('rejects a recommended value that is not in its non-empty allowed set', async () => {
    const res = await postExercise({
      name: exName('mismatch'),
      recommendedRotation: 3,
      allowedRotations: [4, 5],
    });
    expect(res.status).toBe(400);
    const body = (await res.json()) as { error: string };
    expect(body.error).toContain('recommendedRotation');
  });

  it('round-trips allowed sets through REST create/list/update and defaults missing sets to []', async () => {
    const name = exName('roundtrip');
    const res = await postExercise({
      name,
      recommendedHeight: 3,
      recommendedRotation: 4,
      recommendedAngle: 1,
      allowedHeights: [4, 3],
      allowedRotations: [5, 4, 4],
    });
    expect(res.status).toBe(201);
    const created = (await res.json()) as ApiExercise;
    expect(created.allowedHeights).toEqual([3, 4]);
    expect(created.allowedRotations).toEqual([4, 5]);
    expect(created.allowedAngles).toEqual([]);

    const row = await prisma.exercise.findUnique({ where: { id: created.id } });
    expect(row?.allowedHeights).toBe('3,4');
    expect(row?.allowedRotations).toBe('4,5');
    expect(row?.allowedAngles).toBe('');

    const list = (await (await fetch(`${baseUrl}/api/exercises`)).json()) as ApiExercise[];
    const listed = list.find((e) => e.id === created.id);
    expect(listed?.allowedRotations).toEqual([4, 5]);
    // Seeded exercises expose arrays: an empty set where only the recommended value counts.
    const bench = list.find((e) => e.name === 'Bench Press');
    expect(bench?.allowedRotations).toEqual([4, 5]);
    expect(bench?.allowedHeights).toEqual([]);

    // Partial update that would strand recommended* outside the stored set is rejected.
    const stranded = await patchExercise(created.id, { recommendedRotation: 6 });
    expect(stranded.status).toBe(400);

    const patched = await patchExercise(created.id, {
      recommendedRotation: 6,
      allowedRotations: [5, 6],
      allowedAngles: [1, 2],
    });
    expect(patched.status).toBe(200);
    const updated = (await patched.json()) as ApiExercise;
    expect(updated.recommendedRotation).toBe(6);
    expect(updated.allowedRotations).toEqual([5, 6]);
    expect(updated.allowedAngles).toEqual([1, 2]);
    expect(updated.allowedHeights).toEqual([3, 4]);

    const plain = await postExercise({ name: exName('plain') });
    const plainJson = (await plain.json()) as ApiExercise;
    expect(plainJson.allowedHeights).toEqual([]);
    expect(plainJson.allowedRotations).toEqual([]);
    expect(plainJson.allowedAngles).toEqual([]);
  });

  it('round-trips allowed sets through sync push/pull, normalising and preserving them for older kiosks', async () => {
    const id = `armpos-sync-${stamp}`;
    syncIds.push(id);
    const base = {
      id,
      name: exName('sync'),
      muscleGroup: 'Back',
      accessory: 'HANDLES',
      recommendedHeight: 3,
      recommendedRotation: 4,
      recommendedAngle: 1,
      unilateral: false,
    };

    // New kiosk: recommended* missing from the set gets added; out-of-range values dropped.
    const res1 = await push([
      { ...base, allowedHeights: [4], allowedRotations: [5, 9], allowedAngles: [], updatedAtMs: Date.now() },
    ]);
    expect(res1.status).toBe(200);
    let pulled = await pullExercise(id);
    expect(pulled?.allowedHeights).toEqual([3, 4]);
    expect(pulled?.allowedRotations).toEqual([4, 5]);
    expect(pulled?.allowedAngles).toEqual([]);

    // Older kiosk omits allowed* entirely: stored sets survive, recommended* still enforced.
    const res2 = await push([{ ...base, recommendedRotation: 6, updatedAtMs: Date.now() + 1000 }]);
    expect(res2.status).toBe(200);
    pulled = await pullExercise(id);
    expect(pulled?.recommendedRotation).toBe(6);
    expect(pulled?.allowedHeights).toEqual([3, 4]);
    expect(pulled?.allowedRotations).toEqual([4, 5, 6]);

    // An explicit empty array clears the set.
    const res3 = await push([{ ...base, allowedRotations: [], updatedAtMs: Date.now() + 2000 }]);
    expect(res3.status).toBe(200);
    pulled = await pullExercise(id);
    expect(pulled?.allowedRotations).toEqual([]);
    expect(pulled?.allowedHeights).toEqual([3, 4]);
  });

  it('defaults allowed sets to [] for exercises created by an older kiosk push', async () => {
    const id = `armpos-legacy-${stamp}`;
    syncIds.push(id);
    const res = await push([
      {
        id,
        name: exName('legacy'),
        muscleGroup: 'Arms',
        recommendedHeight: 2,
        recommendedRotation: 2,
        recommendedAngle: 1,
        updatedAtMs: Date.now(),
      },
    ]);
    expect(res.status).toBe(200);
    const row = await prisma.exercise.findUnique({ where: { id } });
    expect(row?.allowedHeights).toBe('');
    expect(row?.allowedRotations).toBe('');
    expect(row?.allowedAngles).toBe('');
    const pulled = await pullExercise(id);
    expect(pulled?.allowedHeights).toEqual([]);
  });
});

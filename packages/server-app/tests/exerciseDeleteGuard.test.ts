import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DEFAULT_USERS } from '../prisma/seed';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';

describe('Deleting an exercise that a saved routine uses is refused', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  const exerciseIds: string[] = [];
  const templateIds: string[] = [];

  async function createExercise(name: string): Promise<string> {
    const res = await fetch(`${baseUrl}/api/exercises`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, muscleGroup: 'Arms' }),
    });
    expect(res.status).toBe(201);
    const ex = (await res.json()) as { id: string };
    exerciseIds.push(ex.id);
    return ex.id;
  }

  async function createTemplate(name: string, exerciseId: string): Promise<string> {
    const res = await fetch(`${baseUrl}/api/templates`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: DEFAULT_USERS.USER_ONE_ID,
        name,
        blocks: [
          {
            blockType: 'STRAIGHT_SET',
            restSeconds: 60,
            items: [{ exerciseId, sets: 3, targetReps: 10, mode: 'standard', modifierPct: 0, targetWeightLb: 30 }],
          },
        ],
      }),
    });
    expect(res.status).toBe(201);
    const t = (await res.json()) as { id: string };
    templateIds.push(t.id);
    return t.id;
  }

  const deleteExercise = (id: string) => fetch(`${baseUrl}/api/exercises/${id}`, { method: 'DELETE' });

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
  });

  afterAll(async () => {
    await prisma.workoutTemplate.deleteMany({ where: { id: { in: templateIds } } });
    await prisma.exercise.deleteMany({ where: { id: { in: exerciseIds } } });
    await srv.close();
    await prisma.$disconnect();
  });

  it('returns 409 naming the routines and leaves the exercise in place', async () => {
    const exId = await createExercise('Guard Test Curl');
    const tplId = await createTemplate('Guard Test Arms Day', exId);

    const res = await deleteExercise(exId);
    expect(res.status).toBe(409);
    const body = (await res.json()) as { error: string; templates: { id: string; name: string }[] };
    expect(body.templates).toEqual([{ id: tplId, name: 'Guard Test Arms Day' }]);
    expect(body.error).toContain('Guard Test Arms Day');

    const ex = await prisma.exercise.findUnique({ where: { id: exId } });
    expect(ex?.deletedAt).toBeNull();
  });

  it('allows the delete once no live routine uses the exercise', async () => {
    const exId = await createExercise('Guard Test Kickback');
    const tplId = await createTemplate('Guard Test Old Routine', exId);
    const delTpl = await fetch(`${baseUrl}/api/templates/${tplId}`, { method: 'DELETE' });
    expect(delTpl.status).toBe(200);

    const res = await deleteExercise(exId);
    expect(res.status).toBe(200);
    const ex = await prisma.exercise.findUnique({ where: { id: exId } });
    expect(ex?.deletedAt).not.toBeNull();
  });

  it('fails closed when a routine that mentions the exercise has unreadable blocks', async () => {
    const exId = await createExercise('Guard Test Bad Json');
    const rawRoutines = [
      { name: 'Guard Test Truncated', blocksJson: `[{"items":[{"exerciseId":"${exId}"` },
      { name: 'Guard Test Not An Array', blocksJson: `{"items":[{"exerciseId":"${exId}"}]}` },
      { name: 'Guard Test Null Block', blocksJson: `[null,{"note":"${exId}"}]` },
    ];
    for (const r of rawRoutines) {
      const t = await prisma.workoutTemplate.create({
        data: { userId: DEFAULT_USERS.USER_ONE_ID, name: r.name, blocksJson: r.blocksJson },
      });
      templateIds.push(t.id);
    }

    const res = await deleteExercise(exId);
    expect(res.status).toBe(409);
    const body = (await res.json()) as { templates: { name: string }[] };
    expect(body.templates.map((t) => t.name).sort()).toEqual(rawRoutines.map((r) => r.name).sort());
    const ex = await prisma.exercise.findUnique({ where: { id: exId } });
    expect(ex?.deletedAt).toBeNull();
  });

  it('is not blocked by a well-formed routine that mentions the id outside an exerciseId', async () => {
    const exId = await createExercise('Guard Test Mentioned');
    const t = await prisma.workoutTemplate.create({
      data: {
        userId: DEFAULT_USERS.USER_ONE_ID,
        name: 'Guard Test Mention Only',
        description: null,
        blocksJson: JSON.stringify([{ blockType: 'STRAIGHT_SET', restSeconds: 60, items: [], note: exId }]),
      },
    });
    templateIds.push(t.id);

    const res = await deleteExercise(exId);
    expect(res.status).toBe(200);
  });

  it('still deletes an exercise no routine mentions', async () => {
    const exId = await createExercise('Guard Test Unused');
    const res = await deleteExercise(exId);
    expect(res.status).toBe(200);
  });
});

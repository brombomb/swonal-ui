import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DEFAULT_USERS } from '../prisma/seed';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { clearPendingQueuedWorkout } from '../src/server/mcp/mcpServer';
import { sessionCoordinator } from '../src/server/services/sessionState';

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

  // Lets a test run code right after the delete route has looked up the routines
  // that use a given exercise, and just before it soft-deletes that exercise.
  let deleteHook:
    | { exerciseId: string; afterRoutineCheck: () => Promise<void>; beforeWrite: () => Promise<void>; fired?: boolean }
    | undefined;

  beforeAll(async () => {
    prisma.$use(async (params, next) => {
      const hook = deleteHook;
      if (!hook) return next(params);
      const where = (params.args as { where?: { id?: unknown; blocksJson?: { contains?: unknown } } } | undefined)
        ?.where;
      if (params.model === 'WorkoutTemplate' && params.action === 'findMany' && where?.blocksJson?.contains === hook.exerciseId && !hook.fired) {
        hook.fired = true;
        const result = await next(params);
        await hook.afterRoutineCheck();
        return result;
      }
      if (params.model === 'Exercise' && params.action === 'update' && where?.id === hook.exerciseId) {
        await hook.beforeWrite();
      }
      return next(params);
    });
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

  it('a routine edit racing the delete cannot commit between its check and its write', async () => {
    const exId = await createExercise('Guard Test Race Target');
    const otherId = await createExercise('Guard Test Race Other');
    const tplId = await createTemplate('Guard Test Race Routine', otherId);

    let putDone: Promise<Response> | undefined;
    let routineUsedItAtWrite: boolean | undefined;
    deleteHook = {
      exerciseId: exId,
      // Once the delete has found no routine using the exercise, add it to the
      // routine and give that write time to commit if nothing holds it back.
      afterRoutineCheck: async () => {
        putDone = fetch(`${baseUrl}/api/templates/${tplId}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            blocks: [
              {
                blockType: 'STRAIGHT_SET',
                restSeconds: 60,
                items: [{ exerciseId: exId, sets: 3, targetReps: 10, mode: 'standard', modifierPct: 0, targetWeightLb: 30 }],
              },
            ],
          }),
        });
        await Promise.race([putDone, new Promise((r) => setTimeout(r, 300))]);
      },
      // Committed state (read outside the delete's transaction) at the moment of the write.
      beforeWrite: async () => {
        const t = await prisma.workoutTemplate.findUnique({ where: { id: tplId } });
        routineUsedItAtWrite = t!.blocksJson.includes(exId);
      },
    };
    try {
      const res = await deleteExercise(exId);
      expect(res.status).toBe(200);
      // Without the transaction the PUT commits mid-check, so the delete writes
      // over a routine that now uses the exercise.
      expect(routineUsedItAtWrite).toBe(false);
    } finally {
      deleteHook = undefined;
      // The PUT then runs after the delete has committed. Rejecting a routine
      // that names a deleted exercise is the template-side check's job, not this one.
      await putDone;
    }
  });

  it('refuses while a queued workout uses the exercise, in memory or only persisted', async () => {
    const exId = await createExercise('Guard Test Queued');
    const tplId = await createTemplate('Guard Test Queued Routine', exId);
    try {
      const queue = await fetch(`${baseUrl}/api/templates/${tplId}/queue`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      expect(queue.status).toBe(200);
      // The queued copy keeps the exercise even once the routine itself is gone.
      expect((await fetch(`${baseUrl}/api/templates/${tplId}`, { method: 'DELETE' })).status).toBe(200);

      const res = await deleteExercise(exId);
      expect(res.status).toBe(409);
      const body = (await res.json()) as { error: string; queuedWorkout: { templateId: string; templateName: string } };
      expect(body.error).toContain('queued workout "Guard Test Queued Routine"');
      expect(body.queuedWorkout).toEqual({ templateId: tplId, templateName: 'Guard Test Queued Routine' });

      // After a restart only the AppSetting row is left.
      sessionCoordinator.clearQueuedWorkout();
      expect((await deleteExercise(exId)).status).toBe(409);

      await clearPendingQueuedWorkout();
      expect((await deleteExercise(exId)).status).toBe(200);
    } finally {
      await clearPendingQueuedWorkout();
    }
  });

  it('still deletes an exercise no routine mentions', async () => {
    const exId = await createExercise('Guard Test Unused');
    const res = await deleteExercise(exId);
    expect(res.status).toBe(200);
  });
});

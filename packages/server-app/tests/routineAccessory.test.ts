import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DEFAULT_USERS } from '../prisma/seed';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { flattenTemplateBlocks } from '../src/client/src/hooks/useSwonalState';
import { normalizeAccessory, resolveAccessory } from '../src/client/src/utils/accessories';

describe('Routine items carry their own accessory pick', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  const createdTemplateIds: string[] = [];

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
  });

  afterAll(async () => {
    await prisma.workoutTemplate.deleteMany({ where: { id: { in: createdTemplateIds } } });
    await srv.close();
    await prisma.$disconnect();
  });

  it('saves HANDLES/BAR/ROPE picks, keeps "default" items unset, and drops unknown values', async () => {
    const barExercise = await prisma.exercise.findFirstOrThrow({
      where: { accessory: 'BAR', deletedAt: null },
    });
    const item = { exerciseId: barExercise.id, sets: 3, targetReps: 10, targetWeightLb: 45 };

    const resp = await fetch(`${baseUrl}/api/templates`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: DEFAULT_USERS.USER_ONE_ID,
        name: 'Handles Only Push',
        blocks: [
          {
            blockType: 'STRAIGHT_SET',
            restSeconds: 60,
            items: [
              { ...item, accessory: 'HANDLES' },
              { ...item, accessory: 'ROPE' },
              { ...item, accessory: null },
              { ...item },
              { ...item, accessory: 'KETTLEBELL' },
            ],
          },
        ],
      }),
    });
    expect(resp.status).toBe(201);
    const created = (await resp.json()) as {
      id: string;
      blocks: Array<{ items: Array<{ accessory?: string | null }> }>;
    };
    createdTemplateIds.push(created.id);

    const saved = created.blocks[0].items.map((i) => i.accessory ?? null);
    expect(saved).toEqual(['HANDLES', 'ROPE', null, null, null]);

    // Editing the routine keeps the per-item picks.
    const putResp = await fetch(`${baseUrl}/api/templates/${created.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        blocks: [{ blockType: 'STRAIGHT_SET', restSeconds: 60, items: [{ ...item, accessory: 'BAR' }] }],
      }),
    });
    expect(putResp.status).toBe(200);
    const updated = (await putResp.json()) as { blocks: Array<{ items: Array<{ accessory?: string }> }> };
    expect(updated.blocks[0].items[0].accessory).toBe('BAR');
  });

  it('routine steps use the item pick, falling back to the exercise accessory', () => {
    const steps = flattenTemplateBlocks([
      {
        blockType: 'SUPERSET',
        restSeconds: 45,
        items: [
          { exerciseId: 'a', sets: 2, targetReps: 8, mode: 'standard', modifierPct: 0, targetWeightLb: 40, accessory: 'HANDLES' },
          { exerciseId: 'b', sets: 1, targetReps: 8, mode: 'standard', modifierPct: 0, targetWeightLb: 40 },
        ],
      },
    ]);
    expect(steps.map((s) => [s.exerciseId, s.accessory])).toEqual([
      ['a', 'HANDLES'],
      ['b', null],
      ['a', 'HANDLES'],
    ]);

    expect(resolveAccessory('BAR', steps[0].accessory)).toBe('HANDLES');
    expect(resolveAccessory('BAR', steps[1].accessory)).toBe('BAR');
    expect(normalizeAccessory(' rope ')).toBe('ROPE');
    expect(normalizeAccessory('BODYWEIGHT')).toBeNull();
    expect(normalizeAccessory(undefined)).toBeNull();
  });
});

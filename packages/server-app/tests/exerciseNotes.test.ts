import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';

type ExerciseRow = { id: string; name: string; notes: string | null; muscleGroup: string };

describe('Exercise notes are saved on create and update', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  const exerciseIds: string[] = [];

  const send = (method: 'POST' | 'PATCH', path: string, body: unknown) =>
    fetch(`${baseUrl}${path}`, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });

  async function createExercise(body: Record<string, unknown>): Promise<ExerciseRow> {
    const res = await send('POST', '/api/exercises', { muscleGroup: 'Arms', ...body });
    expect(res.status).toBe(201);
    const ex = (await res.json()) as ExerciseRow;
    exerciseIds.push(ex.id);
    return ex;
  }

  async function patchExercise(id: string, body: Record<string, unknown>): Promise<ExerciseRow> {
    const res = await send('PATCH', `/api/exercises/${id}`, body);
    expect(res.status).toBe(200);
    return (await res.json()) as ExerciseRow;
  }

  const storedNotes = async (id: string) =>
    (await prisma.exercise.findUniqueOrThrow({ where: { id }, select: { notes: true } })).notes;

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
  });

  afterAll(async () => {
    await prisma.exercise.deleteMany({ where: { id: { in: exerciseIds } } });
    await srv.close();
    await prisma.$disconnect();
  });

  it('POST /api/exercises saves trimmed notes', async () => {
    const ex = await createExercise({ name: 'Notes Test Curl', notes: '  Keep elbows pinned.  ' });
    expect(ex.notes).toBe('Keep elbows pinned.');
    expect(await storedNotes(ex.id)).toBe('Keep elbows pinned.');
  });

  it('POST /api/exercises without notes, or with blank notes, stores null', async () => {
    const none = await createExercise({ name: 'Notes Test No Notes' });
    expect(none.notes).toBeNull();
    const blank = await createExercise({ name: 'Notes Test Blank Notes', notes: '   ' });
    expect(blank.notes).toBeNull();
    expect(await storedNotes(blank.id)).toBeNull();
  });

  it('PATCH /api/exercises/:id updates notes', async () => {
    const ex = await createExercise({ name: 'Notes Test Patch', notes: 'Old cue.' });
    const updated = await patchExercise(ex.id, { notes: 'New cue: slow eccentric.' });
    expect(updated.notes).toBe('New cue: slow eccentric.');
    expect(await storedNotes(ex.id)).toBe('New cue: slow eccentric.');
  });

  it('PATCH with notes null or "" clears them', async () => {
    const a = await createExercise({ name: 'Notes Test Clear Null', notes: 'Clear me.' });
    expect((await patchExercise(a.id, { notes: null })).notes).toBeNull();
    expect(await storedNotes(a.id)).toBeNull();

    const b = await createExercise({ name: 'Notes Test Clear Empty', notes: 'Clear me too.' });
    expect((await patchExercise(b.id, { notes: '' })).notes).toBeNull();
    expect(await storedNotes(b.id)).toBeNull();
  });

  it('PATCH without notes keeps them', async () => {
    const ex = await createExercise({ name: 'Notes Test Keep', notes: 'Stay put.' });
    const updated = await patchExercise(ex.id, { muscleGroup: 'Back' });
    expect(updated.muscleGroup).toBe('Back');
    expect(updated.notes).toBe('Stay put.');
    expect(await storedNotes(ex.id)).toBe('Stay put.');
  });

  it('rejects notes longer than 500 characters', async () => {
    const tooLong = 'x'.repeat(501);
    const res = await send('POST', '/api/exercises', { name: 'Notes Test Too Long', muscleGroup: 'Arms', notes: tooLong });
    expect(res.status).toBe(400);

    const ex = await createExercise({ name: 'Notes Test Too Long Patch', notes: 'Short.' });
    const patchRes = await send('PATCH', `/api/exercises/${ex.id}`, { notes: tooLong });
    expect(patchRes.status).toBe(400);
    expect(await storedNotes(ex.id)).toBe('Short.');

    // 500 after trimming is fine.
    const atLimit = await patchExercise(ex.id, { notes: ` ${'y'.repeat(500)} ` });
    expect(atLimit.notes).toHaveLength(500);
  });
});

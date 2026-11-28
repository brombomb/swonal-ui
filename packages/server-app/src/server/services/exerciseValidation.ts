import { Prisma } from '@prisma/client';
import { prisma } from '../db';

type ExerciseReader = Pick<Prisma.TransactionClient, 'exercise'>;

/**
 * Thrown when a workout template references exercise ids that don't exist (or were deleted).
 * The REST error handler maps it to HTTP 400; MCP tools surface the message to the caller.
 */
export class UnknownExerciseError extends Error {
  readonly exerciseIds: string[];

  constructor(exerciseIds: string[], prefix = 'Unknown exercise id(s)') {
    super(`${prefix}: ${exerciseIds.map((id) => id || '(missing)').join(', ')}`);
    this.name = 'UnknownExerciseError';
    this.exerciseIds = exerciseIds;
  }
}

/**
 * Distinct exercise ids referenced by a blocks array of unknown shape (stored or received JSON).
 * An item without a string exerciseId is reported as '' so it can never pass validation.
 */
export function exerciseIdsInBlocks(blocks: unknown): string[] {
  if (!Array.isArray(blocks)) return [];
  const ids = new Set<string>();
  for (const block of blocks) {
    const items = (block as { items?: unknown } | null)?.items;
    if (!Array.isArray(items)) continue;
    for (const item of items) {
      const id = (item as { exerciseId?: unknown } | null)?.exerciseId;
      ids.add(typeof id === 'string' ? id : '');
    }
  }
  return Array.from(ids);
}

/**
 * Returns the ids that have no matching exercise, in one query. By default soft-deleted exercises
 * count as unknown; `includeDeleted` treats any existing row as known (used by sync, which mirrors
 * another device's state rather than creating new references).
 */
export async function findUnknownExerciseIds(
  ids: string[],
  opts: { db?: ExerciseReader; includeDeleted?: boolean } = {}
): Promise<string[]> {
  if (ids.length === 0) return [];
  const db = opts.db ?? prisma;
  const found = await db.exercise.findMany({
    where: { id: { in: ids }, ...(opts.includeDeleted ? {} : { deletedAt: null }) },
    select: { id: true },
  });
  const foundIds = new Set(found.map((e) => e.id));
  return ids.filter((id) => !foundIds.has(id));
}

export async function assertExercisesExist(blocks: unknown, prefix?: string): Promise<void> {
  const missing = await findUnknownExerciseIds(exerciseIdsInBlocks(blocks));
  if (missing.length > 0) throw new UnknownExerciseError(missing, prefix);
}

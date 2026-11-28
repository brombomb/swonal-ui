import { prisma } from '../db';
import { exerciseIdsInBlocks, findUnknownExerciseIds, UnknownExerciseError } from './exerciseValidation';

/**
 * Exercises named in a shared routine (Nostr kind 30102, payload `type: 'routine_share'`).
 *
 * Every install seeds its exercise library with its own random ids, so a routine shared from
 * another install points at ids that don't exist here. The share payload therefore carries,
 * next to the unchanged `blocks`, one entry per referenced exercise:
 *
 *   "exercises": [{ "id": "<sharer's id>", "name": "Bench Press", "muscleGroup": "Chest", "accessory": "HANDLES" }]
 *
 * On import each step's exercise is resolved in this order (same rules as the Android kiosk's
 * LocalNostrSwocialEngine):
 *   1. the id exists here and isn't deleted -> keep it (same-install sharing, paired devices);
 *   2. else the payload names it -> a non-deleted exercise with that name, compared trimmed and
 *      case-insensitively; if several match, an exact (case-sensitive) trimmed match wins, then
 *      the name that sorts first by UTF-16 code units, then the smaller id;
 *   3. else the whole import is refused with an UnknownExerciseError listing name and id.
 * Nothing is created; matched ids are rewritten in the imported blocks. Payloads without
 * `exercises` (older installs) only get step 1, exactly as before.
 */
export interface SharedExerciseRef {
  id: string;
  name: string;
  muscleGroup?: string;
  accessory?: string;
}

interface NamedExercise {
  id: string;
  name: string;
}

export function normalizeExerciseName(name: string): string {
  return name.trim().toLowerCase();
}

/** The `exercises` list for a share payload: one entry per exercise the blocks use (deleted ones too). */
export async function sharedExerciseRefs(blocks: unknown): Promise<SharedExerciseRef[]> {
  const ids = exerciseIdsInBlocks(blocks).filter((id) => id !== '');
  if (ids.length === 0) return [];
  const rows = await prisma.exercise.findMany({
    where: { id: { in: ids } },
    select: { id: true, name: true, muscleGroup: true, accessory: true },
  });
  const byId = new Map(rows.map((r) => [r.id, r]));
  return ids.flatMap((id) => {
    const r = byId.get(id);
    return r ? [{ id: r.id, name: r.name, muscleGroup: r.muscleGroup, accessory: r.accessory }] : [];
  });
}

/** Reads a received `exercises` list, ignoring malformed entries. First entry per id wins. */
export function parseSharedExerciseRefs(raw: unknown): Map<string, SharedExerciseRef> {
  const refs = new Map<string, SharedExerciseRef>();
  if (!Array.isArray(raw)) return refs;
  for (const entry of raw) {
    const e = entry as Record<string, unknown> | null;
    const id = e?.id;
    const name = e?.name;
    if (typeof id !== 'string' || id === '' || typeof name !== 'string' || name.trim() === '') continue;
    if (refs.has(id)) continue;
    refs.set(id, {
      id,
      name: name.trim(),
      muscleGroup: typeof e?.muscleGroup === 'string' ? e.muscleGroup : undefined,
      accessory: typeof e?.accessory === 'string' ? e.accessory : undefined,
    });
  }
  return refs;
}

/** The deterministic name match described above, or undefined. */
export function pickExerciseByName<T extends NamedExercise>(
  candidates: readonly T[],
  sharedName: string
): T | undefined {
  const wanted = normalizeExerciseName(sharedName);
  const exact = sharedName.trim();
  const matches = candidates.filter((c) => normalizeExerciseName(c.name) === wanted);
  if (matches.length === 0) return undefined;
  const cmp = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
  return [...matches].sort((a, b) => {
    const aExact = a.name.trim() === exact ? 0 : 1;
    const bExact = b.name.trim() === exact ? 0 : 1;
    return aExact - bExact || cmp(a.name, b.name) || cmp(a.id, b.id);
  })[0];
}

function rewriteExerciseIds(blocks: unknown, remap: ReadonlyMap<string, string>): unknown {
  if (!Array.isArray(blocks)) return blocks;
  return blocks.map((block) => {
    const items = (block as { items?: unknown } | null)?.items;
    if (!Array.isArray(items)) return block;
    return {
      ...(block as object),
      items: items.map((item) => {
        const id = (item as { exerciseId?: unknown } | null)?.exerciseId;
        return typeof id === 'string' && remap.has(id) ? { ...(item as object), exerciseId: remap.get(id) } : item;
      }),
    };
  });
}

/**
 * Resolves a shared routine's exercises against this library (rules above). Returns the blocks
 * with matched ids rewritten and whether anything changed, or throws UnknownExerciseError.
 */
export async function resolveSharedRoutineExercises(
  blocks: unknown,
  refs: ReadonlyMap<string, SharedExerciseRef>,
  prefix: string
): Promise<{ blocks: unknown; remapped: boolean }> {
  const unknown = await findUnknownExerciseIds(exerciseIdsInBlocks(blocks));
  if (unknown.length === 0) return { blocks, remapped: false };

  const library = unknown.some((id) => refs.has(id))
    ? await prisma.exercise.findMany({ where: { deletedAt: null }, select: { id: true, name: true } })
    : [];
  const remap = new Map<string, string>();
  const missing: string[] = [];
  for (const id of unknown) {
    const ref = refs.get(id);
    const match = ref ? pickExerciseByName(library, ref.name) : undefined;
    if (match) remap.set(id, match.id);
    else missing.push(id);
  }
  if (missing.length > 0) {
    const names = new Map(Array.from(refs.values(), (r) => [r.id, r.name] as const));
    throw new UnknownExerciseError(missing, prefix, names);
  }
  return { blocks: rewriteExerciseIds(blocks, remap), remapped: true };
}

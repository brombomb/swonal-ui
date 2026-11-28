import { z } from 'zod';

/**
 * Tonal V1 arm position scales, as reported by tonal-daemon telemetry:
 * - height:   1 = bottom … 9 = top (the API accepts up to 12)
 * - rotation: 1 = stowed, 2–6 = latching positions
 * - angle:    0 = folded (never a target), 1 = pointing down … 5 = pointing up
 *
 * An exercise has one recommended value per axis plus an optional set of acceptable values
 * (`allowed*`). An empty set means only the recommended value counts. Sets are stored in
 * sqlite as comma-separated ints ("4,5") and exposed over the API as int arrays ([4, 5]).
 */
export const ARM_POSITION_RANGES = {
  height: { min: 1, max: 12 },
  rotation: { min: 1, max: 6 },
  angle: { min: 1, max: 5 },
} as const;

export type ArmAxis = keyof typeof ARM_POSITION_RANGES;

const AXES = [
  { axis: 'height', recommended: 'recommendedHeight', allowed: 'allowedHeights' },
  { axis: 'rotation', recommended: 'recommendedRotation', allowed: 'allowedRotations' },
  { axis: 'angle', recommended: 'recommendedAngle', allowed: 'allowedAngles' },
] as const;

type AllowedKey = (typeof AXES)[number]['allowed'];

export interface ExercisePositions {
  recommendedHeight: number;
  recommendedRotation: number;
  recommendedAngle: number;
  allowedHeights: number[];
  allowedRotations: number[];
  allowedAngles: number[];
}

export type StoredAllowedPositions = Record<AllowedKey, string>;

/** Strict REST validation for one recommended arm position value. */
export function positionValueSchema(axis: ArmAxis) {
  const { min, max } = ARM_POSITION_RANGES[axis];
  return z.number().int().min(min).max(max);
}

/** Strict REST validation for an allowed set, e.g. [4, 5]. */
export function positionSetSchema(axis: ArmAxis) {
  return z.array(positionValueSchema(axis)).max(ARM_POSITION_RANGES[axis].max);
}

/** Dedupe, drop non-integers and sort ascending. */
export function normalizePositionSet(values: readonly number[]): number[] {
  return Array.from(new Set(values.filter((v) => Number.isInteger(v)))).sort((a, b) => a - b);
}

export function parsePositionSet(raw: string | null | undefined): number[] {
  if (!raw) return [];
  return normalizePositionSet(
    raw
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0)
      .map(Number)
  );
}

export function serializePositionSet(values: readonly number[]): string {
  return normalizePositionSet(values).join(',');
}

/** Converts the allowed* arrays that are present into their stored string form. */
export function serializeAllowedPositions(
  values: Partial<Record<AllowedKey, number[]>>
): Partial<StoredAllowedPositions> {
  const out: Partial<StoredAllowedPositions> = {};
  for (const { allowed } of AXES) {
    const set = values[allowed];
    if (set !== undefined) out[allowed] = serializePositionSet(set);
  }
  return out;
}

/** Returns a 400-style message when a non-empty allowed set omits its recommended value. */
export function allowedSetError(p: ExercisePositions): string | null {
  for (const { recommended, allowed } of AXES) {
    const set = p[allowed];
    if (set.length > 0 && !set.includes(p[recommended])) {
      return `${recommended} ${p[recommended]} must be one of ${allowed} [${set.join(', ')}]`;
    }
  }
  return null;
}

/**
 * Lenient normalisation for sync pushes: out-of-range values are dropped and a non-empty set
 * always gains its recommended value. A missing set (older kiosks) keeps the stored one.
 */
export function normalizeSyncedAllowedPositions(
  incoming: Pick<ExercisePositions, (typeof AXES)[number]['recommended']> &
    Partial<Record<AllowedKey, number[] | null>>,
  existing?: StoredAllowedPositions | null
): StoredAllowedPositions {
  const out = {} as StoredAllowedPositions;
  for (const { axis, recommended, allowed } of AXES) {
    const { min, max } = ARM_POSITION_RANGES[axis];
    const values = (incoming[allowed] ?? parsePositionSet(existing?.[allowed])).filter(
      (v) => v >= min && v <= max
    );
    out[allowed] = values.length > 0 ? serializePositionSet([...values, incoming[recommended]]) : '';
  }
  return out;
}

/** Prisma row -> API JSON shape (allowed* as int arrays). */
export function toApiExercise<T extends StoredAllowedPositions>(
  row: T
): Omit<T, AllowedKey> & Record<AllowedKey, number[]> {
  return {
    ...row,
    allowedHeights: parsePositionSet(row.allowedHeights),
    allowedRotations: parsePositionSet(row.allowedRotations),
    allowedAngles: parsePositionSet(row.allowedAngles),
  };
}

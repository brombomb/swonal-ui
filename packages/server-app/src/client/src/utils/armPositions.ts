import type { Exercise } from '../types';

/** Positions that count as aligned: the allowed set, or just the recommended value when it is empty. */
export function acceptedPositions(allowed: number[] | undefined, recommended: number): number[] {
  return allowed && allowed.length > 0 ? allowed : [recommended];
}

type SidePair = { left?: number; right?: number } | undefined;

/**
 * Live arms vs. an exercise's accepted positions. Two-arm exercises need both arms in place;
 * one-arm (unilateral) exercises need only the working arm, either side.
 */
export function armsMatchExercise(
  ex: Exercise,
  arms: { height?: SidePair; rotation?: SidePair; angle?: SidePair } | undefined,
): boolean {
  const okH = acceptedPositions(ex.allowedHeights, ex.recommendedHeight);
  const okR = acceptedPositions(ex.allowedRotations, ex.recommendedRotation);
  const okA = acceptedPositions(ex.allowedAngles, ex.recommendedAngle);
  const sideOk = (side: 'left' | 'right') =>
    okH.includes(Math.round(arms?.height?.[side] ?? -1)) &&
    okR.includes(Math.round(arms?.rotation?.[side] ?? -1)) &&
    okA.includes(Math.round(arms?.angle?.[side] ?? -1));
  return ex.unilateral ? sideOk('left') || sideOk('right') : sideOk('left') && sideOk('right');
}

/** Compact set label: [4, 5] -> "4–5", [4, 6] -> "4,6", [3, 4, 5, 7] -> "3–5,7". */
export function formatPositionSet(allowed: number[] | undefined, recommended: number): string {
  const values = Array.from(new Set(acceptedPositions(allowed, recommended))).sort((a, b) => a - b);
  const parts: string[] = [];
  for (let i = 0; i < values.length; i++) {
    let j = i;
    while (j + 1 < values.length && values[j + 1] === values[j] + 1) j++;
    parts.push(j > i ? `${values[i]}–${values[j]}` : `${values[i]}`);
    i = j;
  }
  return parts.join(',');
}

/** e.g. "H3–4 R4–5 A1" */
export function formatArmTarget(ex: Exercise): string {
  return [
    `H${formatPositionSet(ex.allowedHeights, ex.recommendedHeight)}`,
    `R${formatPositionSet(ex.allowedRotations, ex.recommendedRotation)}`,
    `A${formatPositionSet(ex.allowedAngles, ex.recommendedAngle)}`,
  ].join(' ');
}

/** Parses admin text input like "4,5" or "4 5" into ints; returns null when a token isn't an integer. */
export function parsePositionSetInput(text: string): number[] | null {
  const tokens = text.split(/[\s,]+/).filter((t) => t.length > 0);
  const values = tokens.map(Number);
  return values.every((v) => Number.isInteger(v)) ? values : null;
}

/** A non-empty set always includes its recommended value; an empty set stays empty. */
export function withRecommended(values: number[], recommended: number): number[] {
  return values.length > 0 ? Array.from(new Set([...values, recommended])).sort((a, b) => a - b) : [];
}

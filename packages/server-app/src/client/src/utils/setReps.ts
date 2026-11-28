/**
 * How many reps a "Complete & Log Set" stores (upstream issue #16).
 *
 * A set used to fall back to the step's target reps (or 10 in Free Lift) when the rep counter
 * read 0, so an accidental tap stored a fake set and could show "NEW PR!". Now a 0 count with
 * no explicit rep count asks the user instead. Mirrors the kiosk's `SetRepsPolicy.kt`.
 */

/** Default suggestion in Free Lift, where there is no target. */
export const FREE_LIFT_DEFAULT_REPS = 10;

/** Same upper bound the sets API accepts. */
export const MAX_LOGGED_REPS = 500;

/** Outcome of a log attempt, so callers only say "Logged set!" when it was. */
export type LogSetResult = 'logged' | 'needs_reps' | 'not_logged';

/** A pending "No reps counted" prompt; `rpe` carries the companion's RPE through to the log. */
export interface RepConfirmRequest {
  suggestedReps: number;
  rpe: number | null;
}

export type RepsDecision =
  | { kind: 'log'; reps: number }
  | { kind: 'confirm'; suggestedReps: number };

/** Prefill for the confirm prompt: the step target, or 10 in Free Lift, kept within 1..500. */
export function suggestedReps(targetReps: number | null | undefined): number {
  const target = targetReps ?? FREE_LIFT_DEFAULT_REPS;
  if (!Number.isFinite(target) || target < 1) return FREE_LIFT_DEFAULT_REPS;
  return Math.min(Math.round(target), MAX_LOGGED_REPS);
}

/**
 * @param repsOverride an explicit count from the caller (auto-log, the confirm prompt); always used as-is.
 * @param countedReps the live rep counter.
 * @param targetReps the routine step's target, or undefined in Free Lift.
 */
export function decideRepsToLog(
  repsOverride: number | null | undefined,
  countedReps: number,
  targetReps: number | null | undefined
): RepsDecision {
  if (repsOverride != null) return { kind: 'log', reps: repsOverride };
  if (countedReps > 0) return { kind: 'log', reps: countedReps };
  return { kind: 'confirm', suggestedReps: suggestedReps(targetReps) };
}

/** Parses what the user entered; null (don't log) for blank, 0, decimals, non-numbers or more than 500. */
export function parseEnteredReps(text: string): number | null {
  const trimmed = text.trim();
  if (!/^\d+$/.test(trimmed)) return null;
  const n = Number(trimmed);
  return n >= 1 && n <= MAX_LOGGED_REPS ? n : null;
}

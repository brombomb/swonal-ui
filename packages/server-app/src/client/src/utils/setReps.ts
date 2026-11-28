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

/**
 * Holds the current set's rep count across the motor board's own counter resets.
 * Mirrors the kiosk's `SetRepTracker.kt`.
 *
 * The Tonal V1 motor board zeroes its rep counter in the same telemetry frame that reports the
 * weight turning off (recorded on a real V1: 0 on → 2 on → 3 on → 4 on → 0 off), so the raw
 * `rep_count` already reads 0 when the user taps "Complete & Log Set" or the disengage auto-log runs.
 *
 * - The set count follows the board while it is above zero and is kept after weight off, until
 *   the set is logged or a new set starts.
 * - A later engagement that counts reps replaces it (latest engagement wins): no adding up across
 *   a re-grip, no keeping the highest, so a second set done without logging is logged as itself.
 * - `reset()` (set logged, reps reset, workout ended) and a change of `setKey` (session, routine step
 *   or exercise) start a new set at 0. What the board still shows at that moment belongs to the
 *   previous set and is subtracted until the board count drops again, so it is never counted twice.
 */
export class SetRepTracker {
  private held = 0;
  private baseline = 0;
  private lastBoardReps = 0;
  private key: string | null = null;

  /** Reps of the current set: what COMPLETED REPS shows and what a log without an explicit count stores. */
  get setReps(): number {
    return this.held;
  }

  /** Feed one telemetry frame; returns the current set's reps after it. */
  onFrame(boardReps: number, setKey: string): number {
    const reps = Number.isFinite(boardReps) ? Math.max(0, Math.trunc(boardReps)) : 0;
    const previousKey = this.key;
    this.key = setKey;
    if (previousKey !== null && previousKey !== setKey) this.reset();
    if (reps < this.baseline) this.baseline = 0; // the board restarted its count
    this.lastBoardReps = reps;
    const counted = reps - this.baseline;
    if (counted > 0) this.held = counted;
    return this.held;
  }

  /** Start a new set at 0 (set logged, reps reset, workout ended). */
  reset(): void {
    this.baseline = this.lastBoardReps;
    this.held = 0;
  }
}

/**
 * Reps to auto-log when the weight turns off during a routine step, or null to not auto-log.
 * Uses the held set count, not the disengage frame's raw count (0 on a V1).
 */
export function autoLogReps(setReps: number, targetReps: number): number | null {
  return setReps > 0 && setReps >= targetReps ? setReps : null;
}

/** Identifies the current set's context; a change starts a new set in `SetRepTracker`. */
export function setKeyOf(
  activeSessionId: string | null | undefined,
  currentStepIdx: number,
  activeExerciseId: string | null | undefined
): string {
  return `${activeSessionId ?? 'none'}:${currentStepIdx}:${activeExerciseId ?? 'none'}`;
}

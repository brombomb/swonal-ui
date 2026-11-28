import type { PersonalRecord, WorkoutSession } from '../types';

export interface ExercisePoint {
  dateLabel: string;
  timestampMs: number;
  est1RmLb: number;
  maxWeightLb: number;
  repsAtBest: number;
}

export interface ExerciseSeries {
  exerciseId: string;
  name: string;
  muscleGroup: string;
  points: ExercisePoint[];
}

export function estimate1RmLb(weightLb: number, reps: number): number {
  if (reps < 1) return 0;
  if (reps === 1) return weightLb;
  return Math.round(weightLb * (1 + reps / 30) * 10) / 10;
}

/** Per-exercise 1RM progression: each session's best set, plus PR-only exercises with no logs yet. */
export function buildExerciseSeries(
  sessions: WorkoutSession[],
  personalRecords: PersonalRecord[]
): Map<string, ExerciseSeries> {
  const map = new Map<string, ExerciseSeries>();

  // Chronological order (oldest first)
  const sortedSessions = [...sessions].sort(
    (a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime()
  );

  for (const sess of sortedSessions) {
    const logs = sess.setLogs ?? [];
    const perSessionBest = new Map<
      string,
      { name: string; muscleGroup: string; est1RmLb: number; maxWeightLb: number; repsAtBest: number }
    >();

    for (const log of logs) {
      if (log.repsCompleted < 1) continue; // no completed reps: not a strength data point
      const name = log.exercise?.name ?? 'Exercise';
      const muscleGroup = log.exercise?.muscleGroup ?? 'Full Body';
      const est = estimate1RmLb(log.weightLb, log.repsCompleted);
      const existing = perSessionBest.get(log.exerciseId);
      if (!existing || est > existing.est1RmLb) {
        perSessionBest.set(log.exerciseId, {
          name,
          muscleGroup,
          est1RmLb: est,
          maxWeightLb: Math.max(existing?.maxWeightLb ?? 0, log.weightLb),
          repsAtBest: log.repsCompleted,
        });
      } else if (log.weightLb > existing.maxWeightLb) {
        existing.maxWeightLb = log.weightLb;
      }
    }

    const d = new Date(sess.startedAt);
    const dateLabel = `${d.getMonth() + 1}/${d.getDate()}`;
    const timestampMs = d.getTime();

    for (const [exerciseId, best] of perSessionBest.entries()) {
      let entry = map.get(exerciseId);
      if (!entry) {
        entry = {
          exerciseId,
          name: best.name,
          muscleGroup: best.muscleGroup,
          points: [],
        };
        map.set(exerciseId, entry);
      }
      entry.points.push({
        dateLabel,
        timestampMs,
        est1RmLb: best.est1RmLb,
        maxWeightLb: best.maxWeightLb,
        repsAtBest: best.repsAtBest,
      });
    }
  }

  // Include PR-only exercises if they don't have session logs yet. A PR with no estimated
  // 1RM is a leftover from a 0-rep set (older databases), not a strength data point.
  for (const pr of personalRecords) {
    if (!(pr.oneRepMaxEst > 0)) continue;
    if (!map.has(pr.exerciseId) && pr.exercise) {
      const d = new Date(pr.updatedAt);
      map.set(pr.exerciseId, {
        exerciseId: pr.exerciseId,
        name: pr.exercise.name,
        muscleGroup: pr.exercise.muscleGroup,
        points: [
          {
            dateLabel: `${d.getMonth() + 1}/${d.getDate()}`,
            timestampMs: d.getTime(),
            est1RmLb: pr.oneRepMaxEst,
            maxWeightLb: pr.maxWeightLb,
            repsAtBest: 1,
          },
        ],
      });
    }
  }

  return map;
}

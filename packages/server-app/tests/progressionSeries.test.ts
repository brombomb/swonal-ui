import { describe, expect, it } from 'vitest';
import type { Exercise, PersonalRecord, WorkoutSession } from '../src/client/src/types';
import { buildExerciseSeries } from '../src/client/src/utils/progression';

function exercise(id: string, name: string): Exercise {
  return {
    id,
    name,
    muscleGroup: 'Chest',
    accessory: 'HANDLES',
    recommendedHeight: 4,
    recommendedRotation: 2,
    recommendedAngle: 1,
    unilateral: false,
  };
}

function pr(exerciseId: string, name: string, oneRepMaxEst: number, maxWeightLb: number): PersonalRecord {
  return {
    id: `pr-${exerciseId}`,
    userId: 'u-1',
    exerciseId,
    oneRepMaxEst,
    maxWeightLb,
    updatedAt: '2026-01-02T10:00:00.000Z',
    exercise: exercise(exerciseId, name),
  };
}

describe('Progression series ignores 0-rep data', () => {
  it('leaves out a PR-only exercise whose PR has no estimated 1RM (an old 0-rep PR)', () => {
    const series = buildExerciseSeries([], [
      pr('ex-zero', 'Zero Rep Row', 0, 150),
      pr('ex-real', 'Real Press', 53.3, 40),
    ]);
    expect(series.has('ex-zero')).toBe(false);
    expect(series.get('ex-real')?.points).toEqual([
      expect.objectContaining({ est1RmLb: 53.3, maxWeightLb: 40 }),
    ]);
  });

  it('builds points from completed sets only', () => {
    const sessions: WorkoutSession[] = [
      {
        id: 's-1',
        userId: 'u-1',
        startedAt: '2026-01-03T10:00:00.000Z',
        totalVolume: 320,
        setLogs: [
          {
            id: 'l-1',
            sessionId: 's-1',
            exerciseId: 'ex-a',
            setNumber: 1,
            mode: 'standard',
            weightLb: 40,
            repsCompleted: 8,
            volumeLb: 320,
            timestamp: '2026-01-03T10:01:00.000Z',
            exercise: { name: 'A', muscleGroup: 'Chest', accessory: 'HANDLES' },
          },
          {
            id: 'l-2',
            sessionId: 's-1',
            exerciseId: 'ex-a',
            setNumber: 2,
            mode: 'standard',
            weightLb: 120,
            repsCompleted: 0,
            volumeLb: 0,
            timestamp: '2026-01-03T10:02:00.000Z',
            exercise: { name: 'A', muscleGroup: 'Chest', accessory: 'HANDLES' },
          },
        ],
      },
    ];
    const series = buildExerciseSeries(sessions, []);
    expect(series.get('ex-a')?.points).toEqual([
      expect.objectContaining({ est1RmLb: 50.7, maxWeightLb: 40, repsAtBest: 8 }),
    ]);
  });
});

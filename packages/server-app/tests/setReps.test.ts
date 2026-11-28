import { describe, expect, it } from 'vitest';
import { decideRepsToLog, parseEnteredReps, suggestedReps } from '../src/client/src/utils/setReps';
import { executeCoachSuggestedAction, type VoiceActionHandlers } from '../src/client/src/utils/voiceCommands';

describe('decideRepsToLog (issue #16)', () => {
  it('logs counted reps as-is', () => {
    expect(decideRepsToLog(undefined, 7, 10)).toEqual({ kind: 'log', reps: 7 });
    expect(decideRepsToLog(undefined, 3, undefined)).toEqual({ kind: 'log', reps: 3 });
  });

  it('always uses an explicit rep count, even with the counter at 0', () => {
    expect(decideRepsToLog(12, 0, 10)).toEqual({ kind: 'log', reps: 12 });
    expect(decideRepsToLog(8, 5, 10)).toEqual({ kind: 'log', reps: 8 });
  });

  it('asks when nothing was counted, prefilled with the step target', () => {
    expect(decideRepsToLog(undefined, 0, 8)).toEqual({ kind: 'confirm', suggestedReps: 8 });
  });

  it('asks in Free Lift, prefilled with 10', () => {
    expect(decideRepsToLog(undefined, 0, undefined)).toEqual({ kind: 'confirm', suggestedReps: 10 });
    expect(decideRepsToLog(null, -1, null)).toEqual({ kind: 'confirm', suggestedReps: 10 });
  });

  it('keeps the suggestion within 1 to 500', () => {
    expect(suggestedReps(0)).toBe(10);
    expect(suggestedReps(-3)).toBe(10);
    expect(suggestedReps(900)).toBe(500);
    expect(suggestedReps(1)).toBe(1);
  });
});

describe('parseEnteredReps', () => {
  it('accepts 1 to 500', () => {
    expect(parseEnteredReps('12')).toBe(12);
    expect(parseEnteredReps(' 5 ')).toBe(5);
    expect(parseEnteredReps('500')).toBe(500);
  });

  it('rejects blank, 0, out of range and non-integers', () => {
    for (const bad of ['', '0', '501', '-4', '2.5', 'abc', '1e2']) {
      expect(parseEnteredReps(bad)).toBeNull();
    }
  });
});

describe('voice "complete set"', () => {
  function handlers(repCount: number) {
    const calls: number[] = [];
    const h: VoiceActionHandlers = {
      currentWeightLb: 45,
      currentRepCount: repCount,
      activeUnit: 'LB',
      exercises: [],
      setTargetWeight: () => {},
      setWeightEnabled: () => {},
      toggleWeight: () => {},
      setWeightMode: () => {},
      stepRoutine: () => {},
      selectExercise: () => {},
      skipRest: () => {},
      completeSet: () => {
        calls.push(repCount);
      },
    };
    return { h, calls };
  }

  it('logs and confirms when reps were counted', () => {
    const { h, calls } = handlers(6);
    const reply = executeCoachSuggestedAction({ type: 'complete_set', label: 'Complete & Log Set' }, h);
    expect(calls).toEqual([6]);
    expect(reply).toMatch(/set logged/i);
  });

  it('does not claim a set was logged when nothing was counted', () => {
    const { h, calls } = handlers(0);
    const reply = executeCoachSuggestedAction({ type: 'complete_set', label: 'Complete & Log Set' }, h);
    expect(calls).toEqual([0]); // opens the rep prompt; the hook decides, nothing is logged yet
    expect(reply).not.toMatch(/set logged/i);
    expect(reply).toMatch(/no reps counted/i);
  });
});

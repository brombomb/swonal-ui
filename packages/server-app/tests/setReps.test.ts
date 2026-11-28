import { describe, expect, it } from 'vitest';
import {
  autoLogReps,
  decideRepsToLog,
  parseEnteredReps,
  SetRepTracker,
  setKeyOf,
  suggestedReps,
} from '../src/client/src/utils/setReps';
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

describe('SetRepTracker (V1 board zeroes reps when the weight turns off)', () => {
  type Frame = [reps: number, enabled: boolean];
  const KEY = setKeyOf('session-1', 0, 'ex-bench');
  /** Recorded on a real Tonal V1 (daemon WebSocket, printed on change). */
  const v1FourReps: Frame[] = [
    [0, true], // weight on
    [2, true], // the board reports the first rep late
    [3, true],
    [4, true],
    [0, false], // weight off: reps reset in the same frame
  ];
  const replay = (t: SetRepTracker, frames: Frame[], key = KEY) =>
    frames.reduce((_, [reps]) => t.onFrame(reps, key), 0);

  it('Complete & Log Set after weight off logs 4, no prompt', () => {
    const t = new SetRepTracker();
    expect(replay(t, v1FourReps)).toBe(4);
    expect(decideRepsToLog(undefined, t.setReps, undefined)).toEqual({ kind: 'log', reps: 4 });
  });

  it('routine auto-log on disengage with target 4 logs 4', () => {
    const t = new SetRepTracker();
    let wasEnabled = false;
    let autoLogged: number | null = null;
    for (const [reps, enabled] of v1FourReps) {
      const setReps = t.onFrame(reps, KEY);
      if (wasEnabled && !enabled) autoLogged = autoLogReps(setReps, 4);
      wasEnabled = enabled;
    }
    expect(autoLogged).toBe(4);
    expect(autoLogReps(0, 4)).toBeNull(); // the raw disengage frame alone
    expect(autoLogReps(3, 4)).toBeNull();
    expect(autoLogReps(0, 0)).toBeNull();
  });

  it('shows the count while lifting and keeps it after weight off', () => {
    const t = new SetRepTracker();
    expect(v1FourReps.map(([reps]) => t.onFrame(reps, KEY))).toEqual([0, 2, 3, 4, 4]);
  });

  it('starts the next set from 0 after logging', () => {
    const t = new SetRepTracker();
    replay(t, v1FourReps);
    t.reset();
    expect(t.onFrame(0, KEY)).toBe(0);
    expect(decideRepsToLog(undefined, t.setReps, undefined)).toEqual({ kind: 'confirm', suggestedReps: 10 });
    expect(replay(t, [[1, true], [6, true], [0, false]])).toBe(6);
  });

  it('does not count the board leftover again when logged with the weight still on', () => {
    const t = new SetRepTracker();
    replay(t, [[0, true], [4, true]]);
    t.reset();
    expect(t.onFrame(4, KEY)).toBe(0);
    expect(t.onFrame(0, KEY)).toBe(0);
    replay(t, [[0, true], [4, true]]);
    t.reset();
    expect(replay(t, [[4, true], [5, true], [6, true], [0, false]])).toBe(2);
  });

  it('resets on an exercise, step or session change', () => {
    const t = new SetRepTracker();
    replay(t, v1FourReps);
    expect(t.onFrame(0, setKeyOf('session-1', 1, 'ex-row'))).toBe(0);
    replay(t, [[0, true], [5, true]], KEY);
    expect(t.onFrame(5, setKeyOf('session-1', 1, 'ex-row'))).toBe(0);
    expect(t.onFrame(6, setKeyOf('session-1', 1, 'ex-row'))).toBe(1);
  });

  it('re-grip within a set: the latest engagement wins (not the max, not the sum)', () => {
    const t = new SetRepTracker();
    replay(t, v1FourReps);
    expect(replay(t, [[0, true], [0, false]])).toBe(4); // re-engaged without reps: keep 4
    expect(replay(t, [[0, true], [2, true], [3, true], [0, false]])).toBe(3);
    const t2 = new SetRepTracker();
    replay(t2, [[0, true], [8, true], [0, false]]);
    expect(replay(t2, [[0, true], [5, true], [0, false]])).toBe(5);
  });

  it('works the same with a board that keeps the count at weight off', () => {
    const t = new SetRepTracker();
    expect(replay(t, [[0, true], [4, true], [4, false]])).toBe(4);
    expect(new SetRepTracker().onFrame(-1, KEY)).toBe(0);
  });
});

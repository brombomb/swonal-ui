import { describe, expect, it } from 'vitest';
import { armsMatchExercise, formatArmTarget } from '../src/client/src/utils/armPositions';
import type { Exercise } from '../src/client/src/types';

const base = {
  id: 'x',
  name: 'Test',
  muscleGroup: 'Back',
  accessory: 'HANDLES',
  recommendedHeight: 9,
  recommendedRotation: 4,
  recommendedAngle: 4,
  allowedHeights: [],
  allowedRotations: [4, 5],
  allowedAngles: [],
  unilateral: false,
} as unknown as Exercise;

const arms = (l: [number, number, number], r: [number, number, number]) => ({
  height: { left: l[0], right: r[0] },
  rotation: { left: l[1], right: r[1] },
  angle: { left: l[2], right: r[2] },
});

describe('web arm alignment (sets + one-arm moves)', () => {
  it('two-arm moves need both arms inside the accepted sets', () => {
    expect(armsMatchExercise(base, arms([9, 4, 4], [9, 5, 4]))).toBe(true);
    expect(armsMatchExercise(base, arms([9, 4, 4], [9, 1, 0]))).toBe(false);
  });

  it('one-arm moves pass on either working arm', () => {
    const single = { ...base, unilateral: true } as Exercise;
    expect(armsMatchExercise(single, arms([9, 1, 0], [9, 5, 4]))).toBe(true);
    expect(armsMatchExercise(single, arms([9, 4, 4], [1, 1, 0]))).toBe(true);
    expect(armsMatchExercise(single, arms([1, 1, 0], [1, 1, 0]))).toBe(false);
  });

  it('a folded arm (A0) never matches pointing down (A1)', () => {
    const low = { ...base, recommendedHeight: 1, recommendedAngle: 1 } as Exercise;
    expect(armsMatchExercise(low, arms([1, 4, 1], [1, 4, 1]))).toBe(true);
    expect(armsMatchExercise(low, arms([1, 4, 0], [1, 4, 1]))).toBe(false);
  });

  it('formats sets compactly', () => {
    expect(formatArmTarget(base)).toBe('H9 R4–5 A4');
  });
});

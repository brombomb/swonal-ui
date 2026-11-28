export type WeightUnit = 'LB' | 'KG';

export const LB_TO_KG = 0.45359237;
export const KG_TO_LB = 1 / LB_TO_KG;
export const MIN_WEIGHT_LB = 5.0;
export const MAX_WEIGHT_LB = 200.0;

/**
 * Convert canonical pounds (lb) to kilograms (kg), rounded to 0.5 kg increments.
 */
export function lbToKg(weightLb: number): number {
  const rawKg = weightLb * LB_TO_KG;
  return Math.round(rawKg * 2) / 2;
}

/**
 * Convert canonical pounds (lb) to kilograms (kg) rounded to 1 decimal place for live telemetry/volume display.
 */
export function lbToKgExact(weightLb: number): number {
  return Math.round(weightLb * LB_TO_KG * 10) / 10;
}

/**
 * Convert kilograms (kg) into canonical pounds (lb), rounded to 0.5 lb and clamped within motor limits (5-200 lb).
 */
export function kgToLb(weightKg: number, clamp: boolean = true): number {
  const rawLb = weightKg * KG_TO_LB;
  const rounded = Math.round(rawLb * 2) / 2;
  if (!clamp) return rounded;
  return Math.min(MAX_WEIGHT_LB, Math.max(MIN_WEIGHT_LB, rounded));
}

/**
 * Format a canonical pound value into the target display unit.
 */
export function formatWeight(weightLb: number, unit: WeightUnit, exact: boolean = false): number {
  if (unit === 'KG') {
    return exact ? lbToKgExact(weightLb) : lbToKg(weightLb);
  }
  return Math.round(weightLb * 10) / 10;
}

/**
 * Normalize an incoming value in `unit` to canonical pounds (lb).
 */
export function toCanonicalLb(value: number, unit: WeightUnit, clamp: boolean = true): number {
  if (unit === 'KG') {
    return kgToLb(value, clamp);
  }
  const rounded = Math.round(value * 2) / 2;
  if (!clamp) return rounded;
  return Math.min(MAX_WEIGHT_LB, Math.max(MIN_WEIGHT_LB, rounded));
}

/**
 * Calculate Epley 1RM estimate in pounds. A set with no completed reps proves
 * nothing about strength, so it estimates 0.
 */
export function estimateOneRepMaxLb(weightLb: number, reps: number): number {
  if (reps < 1) return 0;
  if (reps === 1) return Math.round(weightLb * 10) / 10;
  return Math.round(weightLb * (1 + reps / 30) * 10) / 10;
}

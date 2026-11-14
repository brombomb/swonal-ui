export type WeightUnit = 'LB' | 'KG';

export const LB_TO_KG = 0.45359237;
export const KG_TO_LB = 1 / LB_TO_KG;

export function formatDisplayWeight(weightLb: number, unit: WeightUnit, exact = false): number {
  if (unit === 'KG') {
    const kg = weightLb * LB_TO_KG;
    return exact ? Math.round(kg * 10) / 10 : Math.round(kg * 2) / 2;
  }
  return Math.round(weightLb * 10) / 10;
}

export function stepDisplayWeight(
  currentWeightLb: number,
  deltaInUnit: number,
  unit: WeightUnit
): number {
  if (unit === 'KG') {
    const currentKg = formatDisplayWeight(currentWeightLb, 'KG');
    const nextKg = currentKg + deltaInUnit;
    const rawLb = nextKg * KG_TO_LB;
    const roundedLb = Math.round(rawLb * 2) / 2;
    return Math.min(200, Math.max(5, roundedLb));
  }
  const nextLb = Math.round((currentWeightLb + deltaInUnit) * 2) / 2;
  return Math.min(200, Math.max(5, nextLb));
}

export function hexToRgba(hex: string, alpha: number): string {
  const clean = hex.replace('#', '');
  const full =
    clean.length === 3
      ? clean
          .split('')
          .map((c) => c + c)
          .join('')
      : clean;
  const num = parseInt(full, 16);
  if (Number.isNaN(num)) return `rgba(0, 240, 255, ${alpha})`;
  const r = (num >> 16) & 255;
  const g = (num >> 8) & 255;
  const b = num & 255;
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

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

/**
 * Returns crisp #FFFFFF or #07090E text color based on WCAG relative luminance
 * of the active accent color so buttons and badges (e.g. #FF007A vs #CCFF00)
 * always maintain high contrast.
 */
export function getContrastTextColor(hex: string): string {
  const clean = hex.replace('#', '');
  const full =
    clean.length === 3
      ? clean
          .split('')
          .map((c) => c + c)
          .join('')
      : clean;
  const num = parseInt(full, 16);
  if (Number.isNaN(num)) return '#07090E';
  const toLinear = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  const lum =
    0.2126 * toLinear((num >> 16) & 255) +
    0.7152 * toLinear((num >> 8) & 255) +
    0.0722 * toLinear(num & 255);
  return lum > 0.42 ? '#07090E' : '#FFFFFF';
}

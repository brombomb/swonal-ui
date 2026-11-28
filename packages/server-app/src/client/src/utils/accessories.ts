import { AccessoryType } from '../types';

/** Attachments a routine item can pick, in the order the builder lists them. */
export const SELECTABLE_ACCESSORIES: AccessoryType[] = ['HANDLES', 'BAR', 'ROPE'];

/** The upper-cased value when it names a selectable attachment; null when unset or unknown. */
export function normalizeAccessory(raw: unknown): AccessoryType | null {
  if (typeof raw !== 'string') return null;
  const v = raw.trim().toUpperCase() as AccessoryType;
  return SELECTABLE_ACCESSORIES.includes(v) ? v : null;
}

/**
 * The attachment an exercise uses: the routine item's own pick, else the exercise's catalog
 * accessory. (The Kiosk app also applies the lifter's default accessory between the two.)
 */
export function resolveAccessory(
  exerciseAccessory: AccessoryType,
  itemAccessory?: string | null
): AccessoryType {
  return normalizeAccessory(itemAccessory) ?? exerciseAccessory;
}

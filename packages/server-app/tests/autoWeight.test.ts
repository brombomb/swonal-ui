import { describe, expect, it } from 'vitest';
import { WorkoutBlockSchema, WorkoutItemSchema } from '../src/server/mcp/mcpServer';

const item = {
  exerciseId: '11111111-1111-4111-8111-111111111111',
  sets: 3,
  targetReps: 10,
  targetWeightLb: 45,
};

describe('Kiosk AUTO starting weight on routine items', () => {
  it('keeps autoWeight so web edits do not turn AUTO steps into fixed weights', () => {
    expect(WorkoutItemSchema.parse({ ...item, autoWeight: true }).autoWeight).toBe(true);
    expect(WorkoutItemSchema.parse({ ...item, autoWeight: false }).autoWeight).toBe(false);
    const block = WorkoutBlockSchema.parse({ blockType: 'STRAIGHT_SET', restSeconds: 60, items: [{ ...item, autoWeight: true }] });
    expect(block.items[0].autoWeight).toBe(true);
  });

  it('treats a missing or invalid autoWeight as a fixed weight', () => {
    expect(WorkoutItemSchema.parse(item).autoWeight).toBeUndefined();
    expect(WorkoutItemSchema.parse({ ...item, autoWeight: 'yes' }).autoWeight).toBeUndefined();
  });
});

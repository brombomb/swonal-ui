import { PrismaClient } from '@prisma/client';
import { normalizeSyncedAllowedPositions } from '../src/server/utils/armPositions';

interface SeedExercise {
  name: string;
  muscleGroup: string;
  accessory: string;
  recommendedHeight: number;
  recommendedRotation: number;
  recommendedAngle: number;
  /** Acceptable positions; omitted/empty = only the recommended value counts. */
  allowedHeights?: number[];
  allowedRotations?: number[];
  allowedAngles?: number[];
  unilateral: boolean;
  notes: string;
}

const SEED_EXERCISES: SeedExercise[] = [
  // Chest
  { name: 'Bench Press', muscleGroup: 'Chest', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Flat bench barbell press with Smart Bar.' },
  { name: 'Incline Bench Press', muscleGroup: 'Chest', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Upper chest focus on 30-degree incline bench.' },
  { name: 'Decline Bench Press', muscleGroup: 'Chest', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Lower pectoral press using Smart Handles.' },
  { name: 'Cable Fly', muscleGroup: 'Chest', accessory: 'HANDLES', recommendedHeight: 3, recommendedRotation: 4, recommendedAngle: 3, allowedHeights: [3, 4], allowedRotations: [3, 4], unilateral: false, notes: 'Wide arm sweep keeping soft bend in elbows.' },
  { name: 'Incline Cable Fly', muscleGroup: 'Chest', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [3, 4], unilateral: false, notes: 'Low-to-high fly targeting clavicular chest fibers.' },
  { name: 'Decline Cable Fly', muscleGroup: 'Chest', accessory: 'HANDLES', recommendedHeight: 9, recommendedRotation: 4, recommendedAngle: 4, allowedRotations: [3, 4], unilateral: false, notes: 'High-to-low cable crossover.' },
  { name: 'Single-Arm Chest Press', muscleGroup: 'Chest', accessory: 'HANDLES', recommendedHeight: 3, recommendedRotation: 4, recommendedAngle: 3, allowedHeights: [3, 4], allowedRotations: [4, 5], unilateral: true, notes: 'Anti-rotation unilateral standing press.' },
  { name: 'Push-Up', muscleGroup: 'Chest', accessory: 'BODYWEIGHT', recommendedHeight: 1, recommendedRotation: 1, recommendedAngle: 1, unilateral: false, notes: 'Bodyweight warmup and chest finisher.' },

  // Back
  { name: 'Lat Pulldown', muscleGroup: 'Back', accessory: 'BAR', recommendedHeight: 9, recommendedRotation: 4, recommendedAngle: 4, allowedRotations: [4, 5], unilateral: false, notes: 'Kneeling or seated wide-grip lat pulldown.' },
  { name: 'Neutral-Grip Lat Pulldown', muscleGroup: 'Back', accessory: 'HANDLES', recommendedHeight: 9, recommendedRotation: 4, recommendedAngle: 4, allowedRotations: [4, 5], unilateral: false, notes: 'Palms facing inward for deep lat stretch.' },
  { name: 'Seated Cable Row', muscleGroup: 'Back', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Pull handles toward lower ribcage with braced core.' },
  { name: 'Single-Arm Low Row', muscleGroup: 'Back', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: true, notes: 'Unilateral row emphasizing scapular retraction.' },
  { name: 'Bent-Over Barbell Row', muscleGroup: 'Back', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Hinge at hips 45 degrees and row bar to navel.' },
  { name: 'Face Pull', muscleGroup: 'Back', accessory: 'ROPE', recommendedHeight: 9, recommendedRotation: 4, recommendedAngle: 4, allowedRotations: [4, 5], unilateral: false, notes: 'External rotation rear-delt and upper-back health.' },
  { name: 'Straight-Arm Pulldown', muscleGroup: 'Back', accessory: 'ROPE', recommendedHeight: 9, recommendedRotation: 4, recommendedAngle: 4, allowedRotations: [4, 5], unilateral: false, notes: 'Lock elbows slightly and sweep rope to thighs.' },
  { name: 'Standing Reverse Fly', muscleGroup: 'Back', accessory: 'HANDLES', recommendedHeight: 3, recommendedRotation: 4, recommendedAngle: 3, allowedHeights: [3, 4], allowedRotations: [3, 4], unilateral: false, notes: 'Cross cables for rear deltoid and rhomboid activation.' },
  { name: 'Single-Arm Lat Pulldown', muscleGroup: 'Back', accessory: 'HANDLES', recommendedHeight: 9, recommendedRotation: 4, recommendedAngle: 4, allowedRotations: [4, 5], unilateral: true, notes: 'Unilateral overhead pull.' },

  // Shoulders
  { name: 'Standing Overhead Press', muscleGroup: 'Shoulders', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Strict vertical barbell press overhead.' },
  { name: 'Seated Overhead Press', muscleGroup: 'Shoulders', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Seated dual-handle shoulder press.' },
  { name: 'Lateral Raise', muscleGroup: 'Shoulders', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Raise handles to shoulder height with controlled eccentric.' },
  { name: 'Single-Arm Cable Lateral Raise', muscleGroup: 'Shoulders', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: true, notes: 'Cross-body constant tension lateral raise.' },
  { name: 'Front Raise', muscleGroup: 'Shoulders', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Raise Smart Bar to eye level.' },
  { name: 'Arnold Press', muscleGroup: 'Shoulders', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Rotational shoulder press.' },
  { name: 'Upright Row', muscleGroup: 'Shoulders', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Lead with elbows to upper chest height.' },
  { name: 'Cable Shrug', muscleGroup: 'Shoulders', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Upper trapezius elevation with 1-sec hold.' },

  // Arms
  { name: 'Bicep Curl', muscleGroup: 'Arms', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Supinated cable curls keeping elbows pinned.' },
  { name: 'Barbell Bicep Curl', muscleGroup: 'Arms', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Constant-tension Smart Bar curl.' },
  { name: 'Hammer Curl', muscleGroup: 'Arms', accessory: 'ROPE', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Neutral-grip rope curl targeting brachialis.' },
  { name: 'Single-Arm Concentration Curl', muscleGroup: 'Arms', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: true, notes: 'Strict unilateral bicep peak isolation.' },
  { name: 'Overhead Bicep Curl', muscleGroup: 'Arms', accessory: 'HANDLES', recommendedHeight: 9, recommendedRotation: 4, recommendedAngle: 4, allowedRotations: [4, 5], unilateral: false, notes: 'High-pulley double-bicep pose curl.' },
  { name: 'Tricep Extension', muscleGroup: 'Arms', accessory: 'ROPE', recommendedHeight: 9, recommendedRotation: 4, recommendedAngle: 4, allowedRotations: [4, 5], unilateral: false, notes: 'Split rope at bottom for full tricep lockout.' },
  { name: 'Overhead Tricep Extension', muscleGroup: 'Arms', accessory: 'ROPE', recommendedHeight: 9, recommendedRotation: 4, recommendedAngle: 4, allowedRotations: [4, 5], unilateral: false, notes: 'Long-head tricep stretch overhead.' },
  { name: 'Single-Arm Tricep Kickback', muscleGroup: 'Arms', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: true, notes: 'Hinged unilateral tricep extension.' },
  { name: 'Close-Grip Bench Press', muscleGroup: 'Arms', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Heavy compound tricep and inner chest press.' },

  // Legs
  { name: 'Goblet Squat', muscleGroup: 'Legs', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Front-racked Smart Bar squat.' },
  { name: 'Barbell Front Squat', muscleGroup: 'Legs', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Quad-dominant front squat with upright torso.' },
  { name: 'Resisted Split Squat', muscleGroup: 'Legs', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: true, notes: 'Staggered stance quad and glute builder.' },
  { name: 'Bulgarian Split Squat', muscleGroup: 'Legs', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: true, notes: 'Rear foot elevated on bench.' },
  { name: 'Alternating Reverse Lunge', muscleGroup: 'Legs', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: true, notes: 'Knee-friendly backward stepping lunge.' },
  { name: 'Lateral Lunge', muscleGroup: 'Legs', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: true, notes: 'Frontal plane hip mobility and adductor strength.' },
  { name: 'Standing Calf Raise', muscleGroup: 'Legs', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Full plantarflexion pause at top.' },

  // Legs / Posterior
  { name: 'Romanian Deadlift', muscleGroup: 'Legs / Posterior', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Hip hinge keeping bar close to shins.' },
  { name: 'Sumo Deadlift', muscleGroup: 'Legs / Posterior', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Wide stance posterior chain pull.' },
  { name: 'Barbell Hip Thrust', muscleGroup: 'Legs / Posterior', accessory: 'BAR', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Back supported on bench for maximal glute drive.' },
  { name: 'Single-Leg RDL', muscleGroup: 'Legs / Posterior', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: true, notes: 'Unilateral hamstring and balance hinge.' },
  { name: 'Cable Pull-Through', muscleGroup: 'Legs / Posterior', accessory: 'ROPE', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: false, notes: 'Explosive hip extension with rope between legs.' },

  // Core
  { name: 'Pallof Press', muscleGroup: 'Core', accessory: 'HANDLES', recommendedHeight: 3, recommendedRotation: 4, recommendedAngle: 3, allowedHeights: [3, 4], allowedRotations: [4, 5], unilateral: true, notes: 'Anti-rotation core stability press.' },
  { name: 'Standing Woodchopper', muscleGroup: 'Core', accessory: 'HANDLES', recommendedHeight: 9, recommendedRotation: 4, recommendedAngle: 4, allowedRotations: [4, 5], unilateral: true, notes: 'Diagonal oblique rotation across torso.' },
  { name: 'Kneeling Cable Crunch', muscleGroup: 'Core', accessory: 'ROPE', recommendedHeight: 9, recommendedRotation: 4, recommendedAngle: 4, allowedRotations: [4, 5], unilateral: false, notes: 'Flex spine rather than hips under load.' },
  { name: 'Rotational Lift', muscleGroup: 'Core', accessory: 'HANDLES', recommendedHeight: 1, recommendedRotation: 4, recommendedAngle: 1, allowedRotations: [4, 5], unilateral: true, notes: 'Low-to-high athletic rotational core drive.' },
];

export const DEFAULT_USERS = {
  USER_ONE_ID: '11111111-1111-4111-8111-111111111111',
  USER_TWO_ID: '22222222-2222-4222-8222-222222222222',
  ALEX_ID: '11111111-1111-4111-8111-111111111111',
  TAYLOR_ID: '22222222-2222-4222-8222-222222222222',
};

export function resolveExerciseMediaUrl(name: string, muscleGroup: string): string {
  const n = name.toLowerCase();
  if (n.includes('lat pulldown') || n.includes('straight-arm pulldown')) return '/media/lat-pulldown.svg';
  if (n.includes('row') || n.includes('face pull') || n.includes('reverse fly')) return '/media/row.svg';
  if (n.includes('curl')) return '/media/curl.svg';
  if (n.includes('tricep') || n.includes('close-grip')) return '/media/tricep.svg';
  if (n.includes('squat') || n.includes('lunge') || n.includes('calf')) return '/media/squat.svg';
  if (n.includes('deadlift') || n.includes('rdl') || n.includes('hip thrust') || n.includes('pull-through')) {
    return '/media/deadlift.svg';
  }
  if (n.includes('overhead press') || n.includes('lateral raise') || n.includes('front raise') || n.includes('arnold') || n.includes('shrug')) {
    return '/media/shoulder-press.svg';
  }
  if (muscleGroup === 'Core') return '/media/core.svg';
  if (muscleGroup === 'Chest') return '/media/chest-press.svg';
  return '/media/row.svg';
}

export async function seedDatabase(prisma: PrismaClient): Promise<void> {
  console.log(`Seeding ${SEED_EXERCISES.length} Tonal exercises...`);
  const exerciseMap = new Map<string, string>();

  for (const seedEx of SEED_EXERCISES) {
    const { allowedHeights, allowedRotations, allowedAngles, ...ex } = seedEx;
    const mediaUrl = resolveExerciseMediaUrl(ex.name, ex.muscleGroup);
    // Seed sets are ours, but normalise like sync: drop out-of-range values, add recommended*.
    const allowed = normalizeSyncedAllowedPositions({
      ...ex,
      allowedHeights: allowedHeights ?? [],
      allowedRotations: allowedRotations ?? [],
      allowedAngles: allowedAngles ?? [],
    });
    const record = await prisma.exercise.upsert({
      where: { name: ex.name },
      update: {
        muscleGroup: ex.muscleGroup,
        accessory: ex.accessory,
        recommendedHeight: ex.recommendedHeight,
        recommendedRotation: ex.recommendedRotation,
        recommendedAngle: ex.recommendedAngle,
        ...allowed,
        unilateral: ex.unilateral,
        notes: ex.notes,
        mediaUrl,
      },
      create: { ...ex, ...allowed, mediaUrl },
    });
    exerciseMap.set(ex.name, record.id);
  }

  // Upsert Household Profiles: Alex & Taylor
  const alex = await prisma.user.upsert({
    where: { id: DEFAULT_USERS.USER_ONE_ID },
    update: {},
    create: {
      id: DEFAULT_USERS.USER_ONE_ID,
      name: 'Alex',
      avatarColor: '#FF007A',
      preferredMusicProvider: 'spotify',
      themeMode: 'dark',
      accentColor: '#FF007A',
      weightUnit: 'LB',
    },
  });

  const taylor = await prisma.user.upsert({
    where: { id: DEFAULT_USERS.USER_TWO_ID },
    update: {},
    create: {
      id: DEFAULT_USERS.USER_TWO_ID,
      name: 'Taylor',
      avatarColor: '#00F0FF',
      preferredMusicProvider: 'spotify',
      themeMode: 'dark',
      accentColor: '#00F0FF',
      weightUnit: 'LB',
    },
  });

  // Seed Music Integrations
  for (const user of [alex, taylor]) {
    await prisma.musicIntegration.upsert({
      where: { userId_provider: { userId: user.id, provider: 'spotify' } },
      update: {},
      create: {
        userId: user.id,
        provider: 'spotify',
        preferredDeviceId: 'tonal-librespot-01',
        metadataJson: JSON.stringify({ deviceName: 'Tonal Gym (librespot)' }),
      },
    });
  }

  // Seed Personal Records (canonical pounds)
  const alexPRs: Array<[string, number, number]> = [
    ['Bench Press', 65, 85],
    ['Incline Bench Press', 55, 70],
    ['Cable Fly', 30, 38],
    ['Lat Pulldown', 70, 92],
    ['Seated Cable Row', 65, 84],
    ['Standing Overhead Press', 45, 58],
    ['Bicep Curl', 25, 32],
    ['Tricep Extension', 35, 45],
    ['Goblet Squat', 75, 96],
    ['Romanian Deadlift', 95, 122],
    ['Barbell Hip Thrust', 120, 156],
    ['Pallof Press', 20, 26],
  ];

  const taylorPRs: Array<[string, number, number]> = [
    ['Bench Press', 135, 175],
    ['Incline Bench Press', 110, 142],
    ['Cable Fly', 45, 58],
    ['Lat Pulldown', 125, 160],
    ['Seated Cable Row', 120, 155],
    ['Standing Overhead Press', 85, 108],
    ['Bicep Curl', 45, 57],
    ['Tricep Extension', 60, 78],
    ['Goblet Squat', 115, 148],
    ['Romanian Deadlift', 165, 200],
    ['Barbell Hip Thrust', 180, 200],
    ['Pallof Press', 35, 44],
  ];

  for (const [exName, maxW, est1rm] of alexPRs) {
    const exerciseId = exerciseMap.get(exName);
    if (exerciseId) {
      await prisma.personalRecord.upsert({
        where: { userId_exerciseId: { userId: alex.id, exerciseId } },
        update: {},
        create: { userId: alex.id, exerciseId, maxWeightLb: maxW, oneRepMaxEst: est1rm },
      });
    }
  }

  for (const [exName, maxW, est1rm] of taylorPRs) {
    const exerciseId = exerciseMap.get(exName);
    if (exerciseId) {
      await prisma.personalRecord.upsert({
        where: { userId_exerciseId: { userId: taylor.id, exerciseId } },
        update: {},
        create: { userId: taylor.id, exerciseId, maxWeightLb: maxW, oneRepMaxEst: est1rm },
      });
    }
  }

  // Seed Workout Templates if none exist yet
  const existingTemplates = await prisma.workoutTemplate.count();
  if (existingTemplates === 0) {
    const ex = (name: string) => exerciseMap.get(name)!;

    await prisma.workoutTemplate.createMany({
      data: [
        {
          userId: alex.id,
          name: 'Upper Body Sculpt & Eccentric',
          description: 'Balanced chest, back, and shoulder superset routine with eccentric overload.',
          blocksJson: JSON.stringify([
            {
              blockType: 'WARMUP',
              restSeconds: 30,
              items: [
                { exerciseId: ex('Face Pull'), sets: 2, targetReps: 12, mode: 'standard', modifierPct: 0, targetWeightLb: 20 },
              ],
            },
            {
              blockType: 'SUPERSET',
              restSeconds: 60,
              items: [
                { exerciseId: ex('Incline Bench Press'), sets: 3, targetReps: 10, mode: 'eccentric', modifierPct: 25, targetWeightLb: 45 },
                { exerciseId: ex('Single-Arm Low Row'), sets: 3, targetReps: 10, mode: 'standard', modifierPct: 0, targetWeightLb: 35 },
              ],
            },
            {
              blockType: 'CIRCUIT',
              restSeconds: 45,
              items: [
                { exerciseId: ex('Lateral Raise'), sets: 3, targetReps: 12, mode: 'chains', modifierPct: 20, targetWeightLb: 15 },
                { exerciseId: ex('Tricep Extension'), sets: 3, targetReps: 12, mode: 'burnout', modifierPct: 0, targetWeightLb: 30 },
              ],
            },
          ]),
        },
        {
          userId: alex.id,
          name: 'Glutes & Posterior Power',
          description: 'Lower body strength block with smart bar hinge and split squats.',
          blocksJson: JSON.stringify([
            {
              blockType: 'WARMUP',
              restSeconds: 30,
              items: [
                { exerciseId: ex('Cable Pull-Through'), sets: 2, targetReps: 12, mode: 'standard', modifierPct: 0, targetWeightLb: 35 },
              ],
            },
            {
              blockType: 'STRAIGHT_SET',
              restSeconds: 75,
              items: [
                { exerciseId: ex('Barbell Hip Thrust'), sets: 4, targetReps: 10, mode: 'chains', modifierPct: 25, targetWeightLb: 95 },
              ],
            },
            {
              blockType: 'SUPERSET',
              restSeconds: 60,
              items: [
                { exerciseId: ex('Romanian Deadlift'), sets: 3, targetReps: 10, mode: 'eccentric', modifierPct: 20, targetWeightLb: 75 },
                { exerciseId: ex('Bulgarian Split Squat'), sets: 3, targetReps: 10, mode: 'spotter', modifierPct: 0, targetWeightLb: 30 },
              ],
            },
          ]),
        },
        {
          userId: taylor.id,
          name: 'Heavy Push / Pull Hypertrophy',
          description: 'Smart Bar compound presses paired with heavy lat pulldowns and chains.',
          blocksJson: JSON.stringify([
            {
              blockType: 'WARMUP',
              restSeconds: 30,
              items: [
                { exerciseId: ex('Face Pull'), sets: 2, targetReps: 15, mode: 'standard', modifierPct: 0, targetWeightLb: 30 },
              ],
            },
            {
              blockType: 'STRAIGHT_SET',
              restSeconds: 90,
              items: [
                { exerciseId: ex('Bench Press'), sets: 4, targetReps: 8, mode: 'chains', modifierPct: 25, targetWeightLb: 115 },
              ],
            },
            {
              blockType: 'SUPERSET',
              restSeconds: 75,
              items: [
                { exerciseId: ex('Lat Pulldown'), sets: 4, targetReps: 10, mode: 'eccentric', modifierPct: 25, targetWeightLb: 105 },
                { exerciseId: ex('Standing Overhead Press'), sets: 3, targetReps: 8, mode: 'spotter', modifierPct: 0, targetWeightLb: 70 },
              ],
            },
            {
              blockType: 'CIRCUIT',
              restSeconds: 45,
              items: [
                { exerciseId: ex('Bicep Curl'), sets: 3, targetReps: 12, mode: 'burnout', modifierPct: 0, targetWeightLb: 35 },
                { exerciseId: ex('Tricep Extension'), sets: 3, targetReps: 12, mode: 'standard', modifierPct: 0, targetWeightLb: 50 },
              ],
            },
          ]),
        },
        {
          userId: taylor.id,
          name: 'Athletic Lower & Core',
          description: 'Squats, RDLs, and anti-rotation core work.',
          blocksJson: JSON.stringify([
            {
              blockType: 'STRAIGHT_SET',
              restSeconds: 90,
              items: [
                { exerciseId: ex('Goblet Squat'), sets: 4, targetReps: 10, mode: 'standard', modifierPct: 0, targetWeightLb: 95 },
              ],
            },
            {
              blockType: 'SUPERSET',
              restSeconds: 60,
              items: [
                { exerciseId: ex('Romanian Deadlift'), sets: 3, targetReps: 8, mode: 'eccentric', modifierPct: 25, targetWeightLb: 135 },
                { exerciseId: ex('Pallof Press'), sets: 3, targetReps: 12, mode: 'standard', modifierPct: 0, targetWeightLb: 25 },
              ],
            },
          ]),
        },
      ],
    });
  }

  // Seed initial completed sessions for strength history
  const existingSessions = await prisma.workoutSession.count();
  if (existingSessions === 0) {
    const alexSession = await prisma.workoutSession.create({
      data: {
        userId: alex.id,
        startedAt: new Date(Date.now() - 1000 * 60 * 60 * 48),
        completedAt: new Date(Date.now() - 1000 * 60 * 60 * 47),
        totalVolume: 2150,
        setLogs: {
          create: [
            {
              exerciseId: exerciseMap.get('Bench Press')!,
              setNumber: 1,
              mode: 'standard',
              weightLb: 55,
              repsCompleted: 10,
              volumeLb: 550,
              rpe: 7.5,
            },
            {
              exerciseId: exerciseMap.get('Bench Press')!,
              setNumber: 2,
              mode: 'eccentric',
              weightLb: 60,
              repsCompleted: 10,
              volumeLb: 600,
              rpe: 8.5,
            },
            {
              exerciseId: exerciseMap.get('Lat Pulldown')!,
              setNumber: 1,
              mode: 'standard',
              weightLb: 50,
              repsCompleted: 10,
              volumeLb: 500,
              rpe: 8.0,
            },
            {
              exerciseId: exerciseMap.get('Lat Pulldown')!,
              setNumber: 2,
              mode: 'standard',
              weightLb: 50,
              repsCompleted: 10,
              volumeLb: 500,
              rpe: 8.5,
            },
          ],
        },
      },
    });

    await prisma.workoutSession.create({
      data: {
        userId: taylor.id,
        startedAt: new Date(Date.now() - 1000 * 60 * 60 * 24),
        completedAt: new Date(Date.now() - 1000 * 60 * 60 * 23),
        totalVolume: 4800,
        setLogs: {
          create: [
            {
              exerciseId: exerciseMap.get('Bench Press')!,
              setNumber: 1,
              mode: 'chains',
              weightLb: 120,
              repsCompleted: 10,
              volumeLb: 1200,
              rpe: 8.0,
            },
            {
              exerciseId: exerciseMap.get('Bench Press')!,
              setNumber: 2,
              mode: 'chains',
              weightLb: 125,
              repsCompleted: 8,
              volumeLb: 1000,
              rpe: 9.0,
            },
            {
              exerciseId: exerciseMap.get('Lat Pulldown')!,
              setNumber: 1,
              mode: 'standard',
              weightLb: 110,
              repsCompleted: 12,
              volumeLb: 1320,
              rpe: 8.0,
            },
            {
              exerciseId: exerciseMap.get('Romanian Deadlift')!,
              setNumber: 1,
              mode: 'eccentric',
              weightLb: 160,
              repsCompleted: 8,
              volumeLb: 1280,
              rpe: 8.5,
            },
          ],
        },
      },
    });

    console.log(`Seeded sample workout sessions (including ${alexSession.id}).`);
  }

  console.log('Database seed complete.');
}

if (require.main === module) {
  const client = new PrismaClient();
  seedDatabase(client)
    .catch((err) => {
      console.error('Seed failed:', err);
      process.exit(1);
    })
    .finally(async () => {
      await client.$disconnect();
    });
}

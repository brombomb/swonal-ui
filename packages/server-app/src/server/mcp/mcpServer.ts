import { McpServer, ResourceTemplate } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { prisma } from '../db';
import { formatWeight, toCanonicalLb, WeightUnit } from '../utils/units';
import { QueuedWorkoutPayload, sessionCoordinator } from '../services/sessionState';

export const WorkoutItemSchema = z.object({
  exerciseId: z.string().uuid(),
  sets: z.number().int().min(1).max(20),
  targetReps: z.number().int().min(1).max(100),
  mode: z.enum(['standard', 'eccentric', 'chains', 'spotter', 'burnout', 'smart_flex']).default('standard'),
  modifierPct: z.number().int().min(0).max(100).default(0),
  targetWeightLb: z.number().positive(),
  // Kiosk "AUTO" starting weight (Auto-Adjust Weights); kept so web edits don't turn AUTO steps into fixed weights.
  autoWeight: z.boolean().optional().catch(undefined),
});

export const WorkoutBlockSchema = z.object({
  blockType: z.enum(['WARMUP', 'STRAIGHT_SET', 'SUPERSET', 'CIRCUIT']),
  restSeconds: z.number().int().min(0).max(600).default(60),
  items: z.array(WorkoutItemSchema).min(1),
});

export type WorkoutBlockInput = z.infer<typeof WorkoutBlockSchema>;

type McpToolTextResult = {
  content: Array<{ type: 'text'; text: string }>;
};

/**
 * Strongly-typed wrapper around McpServer.registerTool that prevents TypeScript's
 * Zod v3/v4 compat union generics from causing exponential type instantiation.
 */
function registerZodTool<S extends z.ZodObject<z.ZodRawShape>>(
  server: McpServer,
  name: string,
  config: {
    title: string;
    description: string;
    schema: S;
  },
  handler: (args: z.infer<S>) => Promise<McpToolTextResult>
): void {
  const registerRaw = server.registerTool.bind(server) as (
    toolName: string,
    toolConfig: {
      title?: string;
      description?: string;
      inputSchema?: z.ZodRawShape;
    },
    cb: (args: Record<string, unknown>) => Promise<McpToolTextResult>
  ) => unknown;

  registerRaw(
    name,
    {
      title: config.title,
      description: config.description,
      inputSchema: config.schema.shape,
    },
    async (rawArgs) => {
      const parsed = config.schema.parse(rawArgs);
      return handler(parsed);
    }
  );
}

function normalizeBlocksToCanonicalLb(
  blocks: WorkoutBlockInput[],
  unit: WeightUnit
): WorkoutBlockInput[] {
  return blocks.map((block) => ({
    ...block,
    items: block.items.map((item) => ({
      ...item,
      targetWeightLb: toCanonicalLb(item.targetWeightLb, unit, true),
    })),
  }));
}

export async function getExercisesCatalog(filters?: {
  muscleGroup?: string;
  accessory?: string;
  unilateral?: boolean;
}) {
  return prisma.exercise.findMany({
    where: {
      deletedAt: null,
      ...(filters?.muscleGroup ? { muscleGroup: filters.muscleGroup } : {}),
      ...(filters?.accessory ? { accessory: filters.accessory } : {}),
      ...(filters?.unilateral !== undefined ? { unilateral: filters.unilateral } : {}),
    },
    orderBy: [{ muscleGroup: 'asc' }, { name: 'asc' }],
  });
}

export async function getUserProfileAndPrs(userId: string) {
  const user = await prisma.user.findFirst({
    where: { id: userId, deletedAt: null },
    include: {
      personalRecords: {
        include: { exercise: true },
        orderBy: { updatedAt: 'desc' },
      },
      sessions: {
        orderBy: { startedAt: 'desc' },
        take: 10,
      },
    },
  });
  if (!user) {
    throw new Error(`User not found: ${userId}`);
  }
  const unit = (user.weightUnit === 'KG' ? 'KG' : 'LB') as WeightUnit;
  const recentTotalVolumeLb = user.sessions.reduce((acc, s) => acc + s.totalVolume, 0);

  return {
    user: {
      id: user.id,
      name: user.name,
      weightUnit: unit,
      themeMode: user.themeMode,
      accentColor: user.accentColor,
      preferredMusicProvider: user.preferredMusicProvider,
    },
    personalRecords: user.personalRecords.map((pr) => ({
      exerciseId: pr.exerciseId,
      exerciseName: pr.exercise.name,
      muscleGroup: pr.exercise.muscleGroup,
      maxWeightLb: pr.maxWeightLb,
      oneRepMaxEstLb: pr.oneRepMaxEst,
      displayUnit: unit,
      displayMaxWeight: formatWeight(pr.maxWeightLb, unit),
      displayOneRepMaxEst: formatWeight(pr.oneRepMaxEst, unit),
      updatedAt: pr.updatedAt,
    })),
    recentVolumeSummary: {
      sessionCount: user.sessions.length,
      totalVolumeLb: Math.round(recentTotalVolumeLb * 10) / 10,
      displayVolume: formatWeight(recentTotalVolumeLb, unit, true),
      displayUnit: unit,
    },
  };
}

export async function getUserWorkouts(userId: string) {
  const templates = await prisma.workoutTemplate.findMany({
    where: { userId, deletedAt: null },
    orderBy: { createdAt: 'desc' },
  });
  const sessions = await prisma.workoutSession.findMany({
    where: { userId },
    orderBy: { startedAt: 'desc' },
    take: 15,
    include: {
      setLogs: {
        include: { exercise: { select: { name: true, muscleGroup: true } } },
        orderBy: { timestamp: 'asc' },
      },
    },
  });

  return {
    templates: templates.map((t) => ({
      ...t,
      blocks: JSON.parse(t.blocksJson),
    })),
    sessions,
  };
}

export async function getUserStrengthHistory(params: {
  userId: string;
  exerciseId: string;
  limit?: number;
  unit?: WeightUnit;
}) {
  const user = await prisma.user.findFirst({ where: { id: params.userId, deletedAt: null } });
  if (!user) throw new Error(`User not found: ${params.userId}`);
  const effectiveUnit: WeightUnit =
    params.unit ?? (user.weightUnit === 'KG' ? 'KG' : 'LB');

  const pr = await prisma.personalRecord.findUnique({
    where: {
      userId_exerciseId: {
        userId: params.userId,
        exerciseId: params.exerciseId,
      },
    },
  });

  const setLogs = await prisma.setLog.findMany({
    where: {
      exerciseId: params.exerciseId,
      session: { userId: params.userId },
    },
    orderBy: { timestamp: 'desc' },
    take: params.limit ?? 10,
  });

  return {
    unit: effectiveUnit,
    personalRecord: pr
      ? {
          ...pr,
          displayMaxWeight: formatWeight(pr.maxWeightLb, effectiveUnit),
          displayOneRepMaxEst: formatWeight(pr.oneRepMaxEst, effectiveUnit),
        }
      : null,
    recentLogs: setLogs.map((log) => ({
      ...log,
      displayWeight: formatWeight(log.weightLb, effectiveUnit),
      displayVolume: formatWeight(log.volumeLb, effectiveUnit, true),
    })),
  };
}

function normalizeScheduleDays(days?: number[]): string | undefined {
  if (days === undefined) return undefined;
  const clean = Array.from(
    new Set(days.filter((d) => Number.isInteger(d) && d >= 0 && d <= 6))
  ).sort((a, b) => a - b);
  return JSON.stringify(clean);
}

export async function createCustomWorkoutTemplate(params: {
  userId: string;
  name: string;
  description?: string;
  unit?: WeightUnit;
  blocks: WorkoutBlockInput[];
  scheduleDays?: number[];
}) {
  const user = await prisma.user.findUnique({ where: { id: params.userId } });
  if (!user) throw new Error(`User not found: ${params.userId}`);
  const effectiveUnit: WeightUnit =
    params.unit ?? (user.weightUnit === 'KG' ? 'KG' : 'LB');

  const canonicalBlocks = normalizeBlocksToCanonicalLb(params.blocks, effectiveUnit);
  const scheduleDaysJson = normalizeScheduleDays(params.scheduleDays);
  return prisma.workoutTemplate.create({
    data: {
      userId: params.userId,
      name: params.name,
      description: params.description,
      blocksJson: JSON.stringify(canonicalBlocks),
      ...(scheduleDaysJson !== undefined ? { scheduleDays: scheduleDaysJson } : {}),
    },
  });
}

export async function updateCustomWorkoutTemplate(params: {
  templateId: string;
  name?: string;
  description?: string;
  unit?: WeightUnit;
  blocks?: WorkoutBlockInput[];
  scheduleDays?: number[];
}) {
  const existing = await prisma.workoutTemplate.findFirst({
    where: { id: params.templateId, deletedAt: null },
    include: { user: true },
  });
  if (!existing) throw new Error(`Workout template not found: ${params.templateId}`);

  const effectiveUnit: WeightUnit =
    params.unit ?? (existing.user.weightUnit === 'KG' ? 'KG' : 'LB');

  const blocksJson = params.blocks
    ? JSON.stringify(normalizeBlocksToCanonicalLb(params.blocks, effectiveUnit))
    : undefined;
  const scheduleDaysJson = normalizeScheduleDays(params.scheduleDays);

  return prisma.workoutTemplate.update({
    where: { id: params.templateId },
    data: {
      ...(params.name !== undefined ? { name: params.name } : {}),
      ...(params.description !== undefined ? { description: params.description } : {}),
      ...(blocksJson !== undefined ? { blocksJson } : {}),
      ...(scheduleDaysJson !== undefined ? { scheduleDays: scheduleDaysJson } : {}),
    },
  });
}

let queueMutationChain: Promise<void> = Promise.resolve();

function withQueuedWorkoutLock<T>(fn: () => Promise<T>): Promise<T> {
  const run = queueMutationChain.then(fn, fn);
  queueMutationChain = run.then(
    () => undefined,
    () => undefined
  );
  return run;
}

export async function queueWorkoutForSession(params: {
  userId: string;
  templateId: string;
}): Promise<QueuedWorkoutPayload> {
  return withQueuedWorkoutLock(async () => {
    const template = await prisma.workoutTemplate.findFirst({
      where: { id: params.templateId, deletedAt: null },
    });
    if (!template) {
      throw new Error(`Workout template not found: ${params.templateId}`);
    }

    const payload: QueuedWorkoutPayload = {
      userId: params.userId,
      templateId: template.id,
      templateName: template.name,
      description: template.description,
      blocks: JSON.parse(template.blocksJson),
      queuedAt: new Date().toISOString(),
    };
    await prisma.appSetting.upsert({
      where: { key: 'pending_queued_workout' },
      create: { key: 'pending_queued_workout', value: JSON.stringify(payload) },
      update: { value: JSON.stringify(payload) },
    });
    sessionCoordinator.queueWorkout(payload);
    return payload;
  });
}

export async function getPendingQueuedWorkout(): Promise<QueuedWorkoutPayload | null> {
  const inMem = sessionCoordinator.getState().queuedWorkout;
  if (inMem) return inMem;
  const row = await prisma.appSetting.findUnique({
    where: { key: 'pending_queued_workout' },
  });
  if (!row) return null;
  try {
    return JSON.parse(row.value) as QueuedWorkoutPayload;
  } catch {
    return null;
  }
}

export async function clearPendingQueuedWorkout(expectedQueuedAt?: string): Promise<void> {
  return withQueuedWorkoutLock(async () => {
    await prisma.$transaction(async (tx) => {
      if (expectedQueuedAt) {
        const row = await tx.appSetting.findUnique({
          where: { key: 'pending_queued_workout' },
        });
        if (row) {
          let storedQueuedAt: string | undefined;
          try {
            storedQueuedAt = (JSON.parse(row.value) as { queuedAt?: string }).queuedAt;
          } catch {
            storedQueuedAt = undefined;
          }
          if (storedQueuedAt && storedQueuedAt !== expectedQueuedAt) {
            return;
          }
          await tx.appSetting.deleteMany({
            where: { key: 'pending_queued_workout', value: row.value },
          });
        }
        if (sessionCoordinator.getState().queuedWorkout?.queuedAt === expectedQueuedAt) {
          sessionCoordinator.clearQueuedWorkout();
        }
        return;
      }
      await tx.appSetting.deleteMany({
        where: { key: 'pending_queued_workout' },
      });
      sessionCoordinator.clearQueuedWorkout();
    });
  });
}

export function createSwonalMcpServer(): McpServer {
  const server = new McpServer({
    name: 'swonal-ui-mcp',
    version: '1.0.0',
  });

  // Resource 1: Full Exercise Catalog
  server.registerResource(
    'exercises-catalog',
    'swonal://exercises/catalog',
    {
      title: 'Tonal Exercise Catalog',
      description: 'Complete list of Tonal movements, muscle groups, accessories, and recommended arm positions.',
      mimeType: 'application/json',
    },
    async (uri) => {
      const exercises = await getExercisesCatalog();
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: 'application/json',
            text: JSON.stringify(exercises, null, 2),
          },
        ],
      };
    }
  );

  // Resource 2: User Profile & Personal Records
  server.registerResource(
    'user-profile-and-prs',
    new ResourceTemplate('swonal://users/{userId}/profile-and-prs', {
      list: async () => {
        const users = await prisma.user.findMany({ select: { id: true, name: true } });
        return {
          resources: users.map((u) => ({
            uri: `swonal://users/${u.id}/profile-and-prs`,
            name: `${u.name} Profile & PRs`,
            mimeType: 'application/json',
          })),
        };
      },
    }),
    {
      title: 'User Profile & Personal Records',
      description: 'User profile preferences, 1RM estimates, max weights, and recent volume trends.',
      mimeType: 'application/json',
    },
    async (uri, variables) => {
      const userId = String(variables.userId);
      const data = await getUserProfileAndPrs(userId);
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: 'application/json',
            text: JSON.stringify(data, null, 2),
          },
        ],
      };
    }
  );

  // Resource 3: User Workouts (Templates & Sessions)
  server.registerResource(
    'user-workouts',
    new ResourceTemplate('swonal://users/{userId}/workouts', {
      list: async () => {
        const users = await prisma.user.findMany({ select: { id: true, name: true } });
        return {
          resources: users.map((u) => ({
            uri: `swonal://users/${u.id}/workouts`,
            name: `${u.name} Workout Templates & Sessions`,
            mimeType: 'application/json',
          })),
        };
      },
    }),
    {
      title: 'User Workout Templates & Session Logs',
      description: 'Custom workout templates and recent completed workout sessions.',
      mimeType: 'application/json',
    },
    async (uri, variables) => {
      const userId = String(variables.userId);
      const data = await getUserWorkouts(userId);
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: 'application/json',
            text: JSON.stringify(data, null, 2),
          },
        ],
      };
    }
  );

  // Tool 1: list_exercises
  registerZodTool(
    server,
    'list_exercises',
    {
      title: 'List Exercises',
      description: 'List Tonal exercises filtered by muscle group, accessory, or unilateral flag.',
      schema: z.object({
        muscleGroup: z.string().optional(),
        accessory: z.enum(['HANDLES', 'BAR', 'ROPE', 'BODYWEIGHT']).optional(),
        unilateral: z.boolean().optional(),
      }),
    },
    async ({ muscleGroup, accessory, unilateral }) => {
      const exercises = await getExercisesCatalog({ muscleGroup, accessory, unilateral });
      return {
        content: [{ type: 'text', text: JSON.stringify(exercises, null, 2) }],
      };
    }
  );

  // Tool 2: get_user_strength_history
  registerZodTool(
    server,
    'get_user_strength_history',
    {
      title: 'Get User Strength History',
      description: 'Retrieve past performance logs and 1RM metrics for a specific user and exercise.',
      schema: z.object({
        userId: z.string().uuid(),
        exerciseId: z.string().uuid(),
        limit: z.number().int().min(1).max(50).default(10),
        unit: z.enum(['LB', 'KG']).optional(),
      }),
    },
    async ({ userId, exerciseId, limit, unit }) => {
      const result = await getUserStrengthHistory({ userId, exerciseId, limit, unit });
      return {
        content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
      };
    }
  );

  // Tool 3: create_custom_workout
  registerZodTool(
    server,
    'create_custom_workout',
    {
      title: 'Create Custom Workout',
      description:
        'Design and store a new structured workout template for a user. Weights are converted to canonical pounds (5-200 lb) if unit is KG.',
      schema: z.object({
        userId: z.string().uuid(),
        name: z.string().min(1),
        description: z.string().optional(),
        unit: z.enum(['LB', 'KG']).optional(),
        blocks: z.array(WorkoutBlockSchema).min(1),
      }),
    },
    async ({ userId, name, description, unit, blocks }) => {
      const template = await createCustomWorkoutTemplate({
        userId,
        name,
        description,
        unit,
        blocks,
      });
      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(
              { status: 'created', templateId: template.id, name: template.name },
              null,
              2
            ),
          },
        ],
      };
    }
  );

  // Tool 4: update_workout_template
  registerZodTool(
    server,
    'update_workout_template',
    {
      title: 'Update Workout Template',
      description: 'Update blocks, name, or description of an existing workout template.',
      schema: z.object({
        templateId: z.string().uuid(),
        name: z.string().min(1).optional(),
        description: z.string().optional(),
        unit: z.enum(['LB', 'KG']).optional(),
        blocks: z.array(WorkoutBlockSchema).min(1).optional(),
      }),
    },
    async ({ templateId, name, description, unit, blocks }) => {
      const updated = await updateCustomWorkoutTemplate({
        templateId,
        name,
        description,
        unit,
        blocks,
      });
      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(
              { status: 'updated', templateId: updated.id, name: updated.name },
              null,
              2
            ),
          },
        ],
      };
    }
  );

  // Tool 5: queue_workout_for_session
  registerZodTool(
    server,
    'queue_workout_for_session',
    {
      title: 'Queue Workout For Session',
      description:
        'Pre-load a workout template onto the Tonal portrait screen so the lifter can walk up and start.',
      schema: z.object({
        userId: z.string().uuid(),
        templateId: z.string().uuid(),
      }),
    },
    async ({ userId, templateId }) => {
      const queued = await queueWorkoutForSession({ userId, templateId });
      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify({ status: 'queued', ...queued }, null, 2),
          },
        ],
      };
    }
  );

  return server;
}

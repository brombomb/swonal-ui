import { Router } from 'express';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { z } from 'zod';
import { prisma } from '../db';
import {
  createCustomWorkoutTemplate,
  getExercisesCatalog,
  queueWorkoutForSession,
  updateCustomWorkoutTemplate,
  WorkoutBlockSchema,
} from '../mcp/mcpServer';
import { sessionCoordinator } from '../services/sessionState';
import { aiCoachService } from '../services/aiCoach';
import {
  getDemoPipEnabled,
  getEffectivePublicBaseUrl,
  getPublicUrlOverride,
  setDemoPipEnabled,
  setPublicUrlOverride,
} from '../services/runtimeSettings';
import { homeAssistantBus } from '../integrations/homeAssistantBus';
import { publishWorkout } from '../services/nostrFriendsService';
import {
  estimateOneRepMaxLb,
  formatWeight,
  MAX_WEIGHT_LB,
  MIN_WEIGHT_LB,
  WeightUnit,
} from '../utils/units';

export interface DaemonCommandSender {
  sendToDaemon: (msg: Record<string, unknown>) => boolean;
  broadcastToClients: (msg: Record<string, unknown>) => void;
  isDaemonConnected: () => boolean;
}

const CreateUserSchema = z.object({
  name: z.string().min(1).max(40),
  avatarColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/).default('#3B82F6'),
  accentColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/).default('#00F0FF'),
  themeMode: z.enum(['dark', 'light', 'system']).default('dark'),
  weightUnit: z.enum(['LB', 'KG']).default('LB'),
  preferredMusicProvider: z.string().default('spotify'),
});

const UpdateUserSchema = CreateUserSchema.partial();

const ScheduleDaysSchema = z.array(z.number().int().min(0).max(6)).max(7);

const CreateTemplateSchema = z.object({
  userId: z.string().uuid(),
  name: z.string().min(1),
  description: z.string().optional(),
  unit: z.enum(['LB', 'KG']).optional(),
  blocks: z.array(WorkoutBlockSchema).min(1),
  scheduleDays: ScheduleDaysSchema.optional(),
});

const UpdateTemplateSchema = z.object({
  name: z.string().min(1).optional(),
  description: z.string().optional(),
  unit: z.enum(['LB', 'KG']).optional(),
  blocks: z.array(WorkoutBlockSchema).min(1).optional(),
  scheduleDays: ScheduleDaysSchema.optional(),
});

function parseScheduleDays(raw: string | null | undefined): number[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((d): d is number => Number.isInteger(d) && d >= 0 && d <= 6);
  } catch {
    return [];
  }
}

const CreateSessionSchema = z.object({
  userId: z.string().uuid(),
  templateId: z.string().uuid().optional().nullable(),
});

const LogSetSchema = z.object({
  userId: z.string().uuid().optional(),
  exerciseId: z.string().uuid(),
  setNumber: z.number().int().min(1),
  mode: z.enum(['standard', 'eccentric', 'chains', 'spotter', 'burnout', 'smart_flex']).default('standard'),
  modifierPct: z.number().int().min(0).max(100).default(0),
  weightLb: z.number().min(MIN_WEIGHT_LB).max(MAX_WEIGHT_LB),
  repsCompleted: z.number().int().min(0).max(500),
  rpe: z.number().min(1).max(10).optional().nullable(),
  restSeconds: z.number().int().min(0).max(600).default(60),
});

/**
 * Whether a blocks array (`[{ items: [{ exerciseId }] }]`) uses `exerciseId`.
 * Anything that isn't that shape counts as a use: callers have already seen
 * the id in the raw JSON, so if we can't rule the reference out we fail closed.
 */
function blocksReferenceExercise(blocks: unknown, exerciseId: string): boolean {
  if (!Array.isArray(blocks)) return true;
  return blocks.some((block) => {
    const items = typeof block === 'object' && block !== null ? (block as { items?: unknown }).items : undefined;
    if (!Array.isArray(items)) return true;
    return items.some(
      (item) =>
        typeof item !== 'object' || item === null || (item as { exerciseId?: unknown }).exerciseId === exerciseId
    );
  });
}

/**
 * Whether serialized JSON holding blocks uses `exerciseId`. `pickBlocks` selects
 * the blocks array from the parsed value (default: the value itself). JSON that
 * mentions the id but can't be parsed counts as a use (fail closed).
 */
function jsonReferencesExercise(
  raw: string,
  exerciseId: string,
  pickBlocks: (parsed: unknown) => unknown = (parsed) => parsed
): boolean {
  if (!raw.includes(exerciseId)) return false;
  try {
    return blocksReferenceExercise(pickBlocks(JSON.parse(raw)), exerciseId);
  } catch {
    return true;
  }
}

export function createApiRouter(bridge: DaemonCommandSender): Router {
  const router = Router();

  router.get('/health', (_req, res) => {
    res.json({
      status: 'ok',
      daemonConnected: bridge.isDaemonConnected(),
      sessionState: sessionCoordinator.getState(),
      timestamp: new Date().toISOString(),
    });
  });

const PUBLIC_USER_SELECT = {
  id: true,
  name: true,
  avatarColor: true,
  preferredMusicProvider: true,
  themeMode: true,
  accentColor: true,
  weightUnit: true,
  nostrPubKeyHex: true,
  nostrNpub: true,
  nostrRelays: true,
  shareWorkouts: true,
  createdAt: true,
  updatedAt: true,
} as const;

  router.get('/state', async (_req, res, next) => {
    try {
      const [users, exercises, templates] = await Promise.all([
        prisma.user.findMany({
          where: { deletedAt: null },
          select: PUBLIC_USER_SELECT,
          orderBy: { createdAt: 'asc' },
        }),
        getExercisesCatalog(),
        prisma.workoutTemplate.findMany({ where: { deletedAt: null }, orderBy: { createdAt: 'desc' } }),
      ]);
      res.json({
        sessionState: sessionCoordinator.getState(),
        daemonConnected: bridge.isDaemonConnected(),
        users,
        exercises,
        templates: templates.map((t) => ({
          ...t,
          blocks: JSON.parse(t.blocksJson),
          scheduleDays: parseScheduleDays(t.scheduleDays),
        })),
      });
    } catch (err) {
      next(err);
    }
  });

  // Users
  router.get('/users', async (_req, res, next) => {
    try {
      const users = await prisma.user.findMany({
        where: { deletedAt: null },
        select: PUBLIC_USER_SELECT,
        orderBy: { createdAt: 'asc' },
      });
      res.json(users);
    } catch (err) {
      next(err);
    }
  });

  router.post('/users', async (req, res, next) => {
    try {
      const parsed = CreateUserSchema.parse(req.body);
      const user = await prisma.user.create({
        data: parsed,
        select: PUBLIC_USER_SELECT,
      });
      bridge.broadcastToClients({ type: 'users_updated' });
      res.status(201).json(user);
    } catch (err) {
      next(err);
    }
  });

  router.patch('/users/:id', async (req, res, next) => {
    try {
      const parsed = UpdateUserSchema.parse(req.body);
      const existing = await prisma.user.findFirst({
        where: { id: req.params.id, deletedAt: null },
        select: { id: true },
      });
      if (!existing) {
        res.status(404).json({ error: 'User not found' });
        return;
      }
      const user = await prisma.user.update({
        where: { id: req.params.id },
        data: parsed,
        select: PUBLIC_USER_SELECT,
      });
      bridge.broadcastToClients({ type: 'user_updated', user });
      res.json(user);
    } catch (err) {
      next(err);
    }
  });

  router.delete('/users/:id', async (req, res, next) => {
    try {
      const existing = await prisma.user.findFirst({
        where: { id: req.params.id, deletedAt: null },
        select: { id: true },
      });
      if (!existing) {
        res.status(404).json({ error: 'User not found' });
        return;
      }
      const total = await prisma.user.count({ where: { deletedAt: null } });
      if (total <= 1) {
        res.status(400).json({ error: 'Cannot delete the last user profile' });
        return;
      }
      const now = new Date();
      await prisma.$transaction([
        prisma.user.update({
          where: { id: req.params.id },
          data: { deletedAt: now },
        }),
        prisma.workoutTemplate.updateMany({
          where: { userId: req.params.id, deletedAt: null },
          data: { deletedAt: now },
        }),
        prisma.friend.updateMany({
          where: { userId: req.params.id, deletedAt: null },
          data: { deletedAt: now },
        }),
      ]);
      if (sessionCoordinator.getState().activeUserId === req.params.id) {
        const nextUser = await prisma.user.findFirst({
          where: { deletedAt: null },
          orderBy: { createdAt: 'asc' },
        });
        if (nextUser) {
          sessionCoordinator.setActiveUser(nextUser.id);
        }
      }
      bridge.broadcastToClients({ type: 'users_updated' });
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  router.get('/users/:id/demo-pip', (req, res) => {
    res.json({ enabled: getDemoPipEnabled(req.params.id) });
  });

  router.patch('/users/:id/demo-pip', (req, res, next) => {
    try {
      const schema = z.object({ enabled: z.boolean() });
      const { enabled } = schema.parse(req.body ?? {});
      const saved = setDemoPipEnabled(req.params.id, enabled);
      bridge.broadcastToClients({
        type: 'demo_pip_updated',
        userId: req.params.id,
        enabled: saved,
      });
      res.json({ enabled: saved });
    } catch (err) {
      next(err);
    }
  });

  // Exercises
  router.get('/exercises', async (req, res, next) => {
    try {
      const muscleGroup =
        typeof req.query.muscleGroup === 'string' ? req.query.muscleGroup : undefined;
      const accessory =
        typeof req.query.accessory === 'string' ? req.query.accessory : undefined;
      const unilateral =
        req.query.unilateral === 'true'
          ? true
          : req.query.unilateral === 'false'
            ? false
            : undefined;
      const exercises = await getExercisesCatalog({ muscleGroup, accessory, unilateral });
      res.json(exercises);
    } catch (err) {
      next(err);
    }
  });

  const SafeMediaUrlSchema = z
    .string()
    .max(500)
    .refine(
      (v) => v.startsWith('/media/') || /^https?:\/\//i.test(v),
      'mediaUrl must be a /media/ path or http(s) URL'
    );

  router.patch('/exercises/:id/media', async (req, res, next) => {
    try {
      const schema = z.object({
        mediaUrl: SafeMediaUrlSchema.nullable(),
      });
      const { mediaUrl } = schema.parse(req.body);
      const updated = await prisma.exercise.update({
        where: { id: req.params.id },
        data: { mediaUrl },
      });
      bridge.broadcastToClients({ type: 'exercises_updated' });
      res.json(updated);
    } catch (err) {
      next(err);
    }
  });

  const ExerciseSchema = z.object({
    name: z.string().min(1).max(80),
    muscleGroup: z.string().min(1).max(40),
    accessory: z.enum(['HANDLES', 'BAR', 'ROPE', 'BODYWEIGHT']).default('HANDLES'),
    unilateral: z.boolean().optional(),
    isUnilateral: z.boolean().optional(),
    recommendedHeight: z.number().int().min(1).max(12).default(4),
    recommendedRotation: z.number().int().min(1).max(3).default(2),
    recommendedAngle: z.number().int().min(1).max(5).default(1),
    mediaUrl: SafeMediaUrlSchema.nullable().optional(),
  });

  router.post('/exercises', async (req, res, next) => {
    try {
      const parsed = ExerciseSchema.parse(req.body);
      const { isUnilateral, unilateral, ...rest } = parsed;
      const effectiveUnilateral = unilateral ?? isUnilateral ?? false;
      const created = await prisma.exercise.upsert({
        where: { name: rest.name },
        create: {
          ...rest,
          unilateral: effectiveUnilateral,
          mediaUrl: rest.mediaUrl || null,
        },
        update: {
          ...rest,
          unilateral: effectiveUnilateral,
          mediaUrl: rest.mediaUrl || null,
          deletedAt: null,
        },
      });
      bridge.broadcastToClients({ type: 'exercises_updated' });
      res.status(201).json(created);
    } catch (err) {
      next(err);
    }
  });

  router.patch('/exercises/:id', async (req, res, next) => {
    try {
      const parsed = ExerciseSchema.partial().parse(req.body);
      const existing = await prisma.exercise.findFirst({
        where: { id: req.params.id, deletedAt: null },
        select: { id: true },
      });
      if (!existing) {
        res.status(404).json({ error: 'Exercise not found' });
        return;
      }
      const { isUnilateral, unilateral, ...rest } = parsed;
      const effectiveUnilateral = unilateral ?? isUnilateral;
      const updated = await prisma.exercise.update({
        where: { id: req.params.id },
        data: {
          ...rest,
          ...(effectiveUnilateral !== undefined ? { unilateral: effectiveUnilateral } : {}),
        },
      });
      bridge.broadcastToClients({ type: 'exercises_updated' });
      res.json(updated);
    } catch (err) {
      next(err);
    }
  });

  router.delete('/exercises/:id', async (req, res, next) => {
    try {
      const exerciseId = req.params.id;
      // Checks and soft-delete share one transaction so a routine saved
      // concurrently can't land between the check and the write. (Prisma opens
      // SQLite interactive transactions with BEGIN IMMEDIATE, so other writers
      // wait until this one commits.)
      const refusal = await prisma.$transaction(
        async (tx): Promise<{ status: number; body: Record<string, unknown> } | null> => {
          const existing = await tx.exercise.findFirst({
            where: { id: exerciseId, deletedAt: null },
            select: { id: true },
          });
          if (!existing) {
            return { status: 404, body: { error: 'Exercise not found' } };
          }
          const usageCount = await tx.setLog.count({ where: { exerciseId } });
          if (usageCount > 0) {
            return { status: 400, body: { error: `Cannot delete exercise with ${usageCount} logged sets.` } };
          }
          // Routines reference exercises by id inside blocksJson; deleting one would
          // silently drop that step from every routine that uses it.
          const candidates = await tx.workoutTemplate.findMany({
            where: { deletedAt: null, blocksJson: { contains: exerciseId } },
            select: { id: true, name: true, blocksJson: true },
            orderBy: { name: 'asc' },
          });
          const usedBy = candidates
            .filter((t) => jsonReferencesExercise(t.blocksJson, exerciseId))
            .map((t) => ({ id: t.id, name: t.name }));
          if (usedBy.length > 0) {
            const one = usedBy.length === 1;
            return {
              status: 409,
              body: {
                error: `Cannot delete exercise: used by ${one ? 'routine' : 'routines'} ${usedBy
                  .map((t) => `"${t.name}"`)
                  .join(', ')}. Remove it from ${one ? 'that routine' : 'those routines'} first.`,
                templates: usedBy,
              },
            };
          }
          await tx.exercise.update({
            where: { id: exerciseId },
            data: { deletedAt: new Date() },
          });
          return null;
        }
      );
      if (refusal) {
        res.status(refusal.status).json(refusal.body);
        return;
      }
      bridge.broadcastToClients({ type: 'exercises_updated' });
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  // Templates
  router.get('/templates', async (req, res, next) => {
    try {
      const userId = typeof req.query.userId === 'string' ? req.query.userId : undefined;
      const templates = await prisma.workoutTemplate.findMany({
        where: { deletedAt: null, ...(userId ? { userId } : {}) },
        orderBy: { createdAt: 'desc' },
      });
      res.json(
        templates.map((t) => ({
          ...t,
          blocks: JSON.parse(t.blocksJson),
          scheduleDays: parseScheduleDays(t.scheduleDays),
        }))
      );
    } catch (err) {
      next(err);
    }
  });

  router.post('/templates', async (req, res, next) => {
    try {
      const parsed = CreateTemplateSchema.parse(req.body);
      const created = await createCustomWorkoutTemplate(parsed);
      bridge.broadcastToClients({ type: 'templates_updated' });
      res.status(201).json({
        ...created,
        blocks: JSON.parse(created.blocksJson),
        scheduleDays: parseScheduleDays(created.scheduleDays),
      });
    } catch (err) {
      next(err);
    }
  });

  router.put('/templates/:id', async (req, res, next) => {
    try {
      const parsed = UpdateTemplateSchema.parse(req.body);
      const updated = await updateCustomWorkoutTemplate({
        templateId: req.params.id,
        ...parsed,
      });
      bridge.broadcastToClients({ type: 'templates_updated' });
      res.json({
        ...updated,
        blocks: JSON.parse(updated.blocksJson),
        scheduleDays: parseScheduleDays(updated.scheduleDays),
      });
    } catch (err) {
      next(err);
    }
  });

  router.delete('/templates/:id', async (req, res, next) => {
    try {
      const existing = await prisma.workoutTemplate.findFirst({
        where: { id: req.params.id, deletedAt: null },
        select: { id: true },
      });
      if (!existing) {
        res.status(404).json({ error: 'Template not found' });
        return;
      }
      await prisma.workoutTemplate.update({
        where: { id: req.params.id },
        data: { deletedAt: new Date() },
      });
      bridge.broadcastToClients({ type: 'templates_updated' });
      res.json({ status: 'deleted', id: req.params.id });
    } catch (err) {
      next(err);
    }
  });

  router.post('/templates/:id/queue', async (req, res, next) => {
    try {
      const template = await prisma.workoutTemplate.findFirst({
        where: { id: req.params.id, deletedAt: null },
      });
      if (!template) {
        res.status(404).json({ error: 'Template not found' });
        return;
      }
      const userId =
        typeof req.body?.userId === 'string' ? req.body.userId : template.userId;
      const queued = await queueWorkoutForSession({ userId, templateId: template.id });
      res.json({ status: 'queued', ...queued });
    } catch (err) {
      next(err);
    }
  });

  // Sessions & Set Logging
  router.get('/sessions', async (req, res, next) => {
    try {
      const userId = typeof req.query.userId === 'string' ? req.query.userId : undefined;
      const sessions = await prisma.workoutSession.findMany({
        where: userId ? { userId } : undefined,
        orderBy: { startedAt: 'desc' },
        take: 25,
        include: {
          template: { select: { name: true } },
          setLogs: {
            include: { exercise: { select: { name: true, muscleGroup: true, accessory: true } } },
            orderBy: { timestamp: 'asc' },
          },
        },
      });
      res.json(sessions);
    } catch (err) {
      next(err);
    }
  });

  router.post('/sessions', async (req, res, next) => {
    try {
      const parsed = CreateSessionSchema.parse(req.body);
      const session = await prisma.workoutSession.create({
        data: {
          userId: parsed.userId,
          templateId: parsed.templateId ?? null,
        },
      });
      sessionCoordinator.setActiveUser(parsed.userId);
      sessionCoordinator.setActiveSession(session.id);
      res.status(201).json(session);
    } catch (err) {
      next(err);
    }
  });

  router.post('/sessions/:id/sets', async (req, res, next) => {
    try {
      const parsed = LogSetSchema.parse(req.body);
      const session = await prisma.workoutSession.findUnique({
        where: { id: req.params.id },
      });
      if (!session) {
        res.status(404).json({ error: 'Workout session not found' });
        return;
      }

      const activeState = sessionCoordinator.getState();
      const effectiveUserId = parsed.userId ?? activeState.activeUserId ?? session.userId;
      const volumeLb = Math.round(parsed.weightLb * parsed.repsCompleted * 10) / 10;

      const setLog = await prisma.setLog.create({
        data: {
          sessionId: session.id,
          exerciseId: parsed.exerciseId,
          setNumber: parsed.setNumber,
          mode: parsed.mode,
          modifierPct: parsed.modifierPct,
          weightLb: parsed.weightLb,
          repsCompleted: parsed.repsCompleted,
          volumeLb,
          rpe: parsed.rpe ?? null,
        },
      });

      const updatedSession = await prisma.workoutSession.update({
        where: { id: session.id },
        data: { totalVolume: { increment: volumeLb } },
      });

      // Evaluate PR
      let newPr = false;
      const oneRepMaxEst = estimateOneRepMaxLb(parsed.weightLb, parsed.repsCompleted);
      const existingPr = await prisma.personalRecord.findUnique({
        where: {
          userId_exerciseId: {
            userId: effectiveUserId,
            exerciseId: parsed.exerciseId,
          },
        },
      });

      if (
        !existingPr ||
        parsed.weightLb > existingPr.maxWeightLb ||
        oneRepMaxEst > existingPr.oneRepMaxEst
      ) {
        newPr = true;
        await prisma.personalRecord.upsert({
          where: {
            userId_exerciseId: {
              userId: effectiveUserId,
              exerciseId: parsed.exerciseId,
            },
          },
          update: {
            maxWeightLb: Math.max(parsed.weightLb, existingPr?.maxWeightLb ?? 0),
            oneRepMaxEst: Math.max(oneRepMaxEst, existingPr?.oneRepMaxEst ?? 0),
          },
          create: {
            userId: effectiveUserId,
            exerciseId: parsed.exerciseId,
            maxWeightLb: parsed.weightLb,
            oneRepMaxEst,
          },
        });
      }

      if (parsed.restSeconds > 0) {
        sessionCoordinator.setRestTimer(Date.now() + parsed.restSeconds * 1000);
      }

      // Partner "Work In" rotation: swap active lifter and load their working weight
      let partnerRotation: {
        rotatedToUserId: string;
        targetWeightLb: number;
      } | null = null;

      if (activeState.partnerModeEnabled && activeState.partnerUserIds.length >= 2) {
        const nextState = sessionCoordinator.rotatePartner();
        const nextUserId = nextState.activeUserId;
        const partnerPr = await prisma.personalRecord.findUnique({
          where: {
            userId_exerciseId: {
              userId: nextUserId,
              exerciseId: parsed.exerciseId,
            },
          },
        });
        const rawTarget = partnerPr
          ? Math.round((partnerPr.maxWeightLb * 0.85) / 5) * 5
          : parsed.weightLb;
        const partnerTargetLb = Math.min(
          MAX_WEIGHT_LB,
          Math.max(MIN_WEIGHT_LB, rawTarget)
        );

        bridge.sendToDaemon({
          type: 'set_weight',
          weight_lb: partnerTargetLb,
          profile: parsed.mode,
          modifier_pct: parsed.modifierPct,
        });
        bridge.sendToDaemon({ type: 'reset_reps' });

        partnerRotation = {
          rotatedToUserId: nextUserId,
          targetWeightLb: partnerTargetLb,
        };
        bridge.broadcastToClients({
          type: 'partner_rotated',
          userId: nextUserId,
          targetWeightLb: partnerTargetLb,
          exerciseId: parsed.exerciseId,
        });
      } else {
        bridge.sendToDaemon({ type: 'reset_reps' });
      }

      bridge.broadcastToClients({
        type: 'set_logged',
        setLog,
        newPr,
        totalVolume: updatedSession.totalVolume,
      });

      void homeAssistantBus.publishSessionEvent('set_logged', {
        userId: session.userId,
        sessionId: session.id,
        exerciseId: parsed.exerciseId,
        weightLb: parsed.weightLb,
        repsCompleted: parsed.repsCompleted,
        volumeLb: parsed.weightLb * parsed.repsCompleted,
        newPr,
      });

      res.status(201).json({
        setLog,
        newPr,
        totalVolume: updatedSession.totalVolume,
        partnerRotation,
      });
    } catch (err) {
      next(err);
    }
  });

  router.post('/sessions/:id/complete', async (req, res, next) => {
    try {
      const completed = await prisma.workoutSession.update({
        where: { id: req.params.id },
        data: { completedAt: new Date() },
        include: {
          template: { select: { name: true } },
          setLogs: {
            include: {
              exercise: { select: { name: true, muscleGroup: true, accessory: true } },
            },
            orderBy: { timestamp: 'asc' },
          },
        },
      });
      sessionCoordinator.setActiveSession(null, null);
      sessionCoordinator.setRestTimer(null);
      void homeAssistantBus.publishSessionEvent('workout_completed', {
        userId: completed.userId,
        sessionId: completed.id,
        routineName: completed.template?.name ?? 'Free Lift',
        totalVolumeLb: completed.totalVolume,
        setsCount: completed.setLogs.length,
      });
      void publishWorkout(completed.userId, {
        sessionId: completed.id,
        routineName: completed.template?.name ?? 'Free Lift',
        totalVolumeLb: completed.totalVolume,
        durationSec: completed.startedAt
          ? Math.round((Date.now() - completed.startedAt.getTime()) / 1000)
          : 0,
        setLogs: completed.setLogs,
      }).catch((err) => {
        console.warn('[Nostr] Auto-publish failed:', err);
      });
      res.json(completed);
    } catch (err) {
      next(err);
    }
  });

  router.get('/prs/:userId', async (req, res, next) => {
    try {
      const prs = await prisma.personalRecord.findMany({
        where: { userId: req.params.userId },
        include: { exercise: true },
        orderBy: { updatedAt: 'desc' },
      });
      res.json(prs);
    } catch (err) {
      next(err);
    }
  });

  // Home Assistant / MQTT Event Bus Diagnostics & Configuration
  router.get('/integrations/ha/status', (_req, res) => {
    res.json(homeAssistantBus.getStatus());
  });

  router.post('/integrations/ha/config', async (req, res, next) => {
    try {
      const schema = z.object({
        mqttBrokerUrl: z.string().max(300).optional(),
        mqttUsername: z.string().max(120).optional(),
        mqttPassword: z.string().max(200).optional(),
        mqttTopicPrefix: z.string().max(120).optional(),
        mqttDiscoveryEnabled: z.boolean().optional(),
        webhookUrl: z.string().max(400).optional(),
        webhookToken: z.string().max(300).optional(),
        haUrl: z.string().max(300).optional(),
        haToken: z.string().max(500).optional(),
      });
      const parsed = schema.parse(req.body ?? {});
      await homeAssistantBus.setConfig(parsed);
      res.json(homeAssistantBus.getStatus());
    } catch (err) {
      next(err);
    }
  });

  router.get('/integrations/ha/media-players', async (_req, res) => {
    try {
      const players = await homeAssistantBus.listMediaPlayers();
      res.json({ players });
    } catch (err) {
      res.status(502).json({
        players: [],
        error: err instanceof Error ? err.message : String(err),
      });
    }
  });

  router.post('/integrations/ha/test', async (req, res, next) => {
    try {
      const activeUserId = sessionCoordinator.getState().activeUserId;
      const user = await prisma.user.findUnique({ where: { id: activeUserId } });
      const record = await homeAssistantBus.publishSessionEvent('test_ping', {
        userId: activeUserId,
        userName: user?.name ?? 'Unknown',
        accentColor: user?.accentColor ?? '#00F0FF',
        note: typeof req.body?.note === 'string' ? req.body.note : 'Manual Home Assistant test trigger',
      });
      res.json({ status: 'published', event: record, bus: homeAssistantBus.getStatus() });
    } catch (err) {
      next(err);
    }
  });

  // Smart Progressive Overload Recommendation
  router.get('/overload/:userId/:exerciseId', async (req, res, next) => {
    try {
      const { userId, exerciseId } = req.params;
      const recentSets = await prisma.setLog.findMany({
        where: {
          exerciseId,
          session: { userId },
        },
        orderBy: { timestamp: 'desc' },
        take: 4,
      });
      const pr = await prisma.personalRecord.findUnique({
        where: { userId_exerciseId: { userId, exerciseId } },
      });
      const latest = recentSets[0];
      const twoStrongSets =
        recentSets.length >= 2 &&
        recentSets[0].repsCompleted >= 8 &&
        recentSets[1].repsCompleted >= 8 &&
        recentSets[0].weightLb >= recentSets[1].weightLb;
      const highRepLatest = Boolean(latest && latest.repsCompleted >= 10);
      const ready = Boolean(latest && (twoStrongSets || highRepLatest) && latest.weightLb < MAX_WEIGHT_LB);
      const recommendedWeightLb = latest
        ? Math.min(MAX_WEIGHT_LB, latest.weightLb + (ready ? 5 : 0))
        : pr
          ? Math.min(MAX_WEIGHT_LB, Math.max(MIN_WEIGHT_LB, Math.round((pr.maxWeightLb * 0.85) / 5) * 5))
          : 45;

      res.json({
        ready,
        recommendedBumpLb: ready ? 5 : 0,
        recommendedWeightLb,
        lastWeightLb: latest?.weightLb ?? null,
        lastReps: latest?.repsCompleted ?? null,
        reason: ready
          ? 'Strong rep consistency detected — ready for +5 lb progressive overload'
          : 'Log 8+ reps across 2 sets (or 10+ reps) to unlock +5 lb overload suggestion',
      });
    } catch (err) {
      next(err);
    }
  });

  // Workout History Export (CSV & Apple Health / Strava-compatible JSON)
  router.get('/export/sessions.csv', async (req, res, next) => {
    try {
      const userId = typeof req.query.userId === 'string' ? req.query.userId : sessionCoordinator.getState().activeUserId;
      const user = await prisma.user.findUnique({ where: { id: userId } });
      const unit: WeightUnit = user?.weightUnit === 'KG' ? 'KG' : 'LB';
      const sessions = await prisma.workoutSession.findMany({
        where: { userId },
        orderBy: { startedAt: 'desc' },
        include: {
          template: { select: { name: true } },
          setLogs: {
            include: { exercise: { select: { name: true, muscleGroup: true, accessory: true } } },
            orderBy: { timestamp: 'asc' },
          },
        },
      });

      const escapeCsvCell = (value: string | number): string => {
        const str = String(value);
        const safe = /^[=+\-@\t\r]/.test(str) ? `'${str}` : str;
        return `"${safe.replace(/"/g, '""')}"`;
      };

      const header = ['SessionId', 'Routine', 'StartedAt', 'Exercise', 'MuscleGroup', 'Set', 'Mode', `Weight(${unit})`, 'Reps', `Volume(${unit})`, 'Timestamp'];
      const rows = [header.join(',')];
      for (const s of sessions) {
        const routineName = s.template?.name ?? 'Free Lift';
        for (const log of s.setLogs) {
          rows.push([
            escapeCsvCell(s.id),
            escapeCsvCell(routineName),
            escapeCsvCell(s.startedAt.toISOString()),
            escapeCsvCell(log.exercise.name),
            escapeCsvCell(log.exercise.muscleGroup),
            String(log.setNumber),
            escapeCsvCell(log.mode),
            String(formatWeight(log.weightLb, unit)),
            String(log.repsCompleted),
            String(formatWeight(log.volumeLb, unit)),
            escapeCsvCell(log.timestamp.toISOString()),
          ].join(','));
        }
      }

      res.setHeader('Content-Type', 'text/csv; charset=utf-8');
      res.setHeader('Content-Disposition', `attachment; filename="swonal-workouts-${new Date().toISOString().slice(0, 10)}.csv"`);
      res.send(rows.join('\n'));
    } catch (err) {
      next(err);
    }
  });

  router.get('/export/sessions.json', async (req, res, next) => {
    try {
      const userId = typeof req.query.userId === 'string' ? req.query.userId : sessionCoordinator.getState().activeUserId;
      const user = await prisma.user.findUnique({ where: { id: userId } });
      const unit: WeightUnit = user?.weightUnit === 'KG' ? 'KG' : 'LB';
      const sessions = await prisma.workoutSession.findMany({
        where: { userId },
        orderBy: { startedAt: 'desc' },
        include: {
          template: { select: { name: true } },
          setLogs: {
            include: { exercise: { select: { name: true, muscleGroup: true, accessory: true } } },
            orderBy: { timestamp: 'asc' },
          },
        },
      });

      res.setHeader('Content-Type', 'application/json; charset=utf-8');
      res.setHeader('Content-Disposition', `attachment; filename="swonal-workouts-${new Date().toISOString().slice(0, 10)}.json"`);
      res.json({
        exportedAt: new Date().toISOString(),
        source: 'swonal-ui',
        activityType: 'TraditionalStrengthTraining',
        athlete: user?.name ?? 'Athlete',
        unit,
        sessions: sessions.map((s) => ({
          id: s.id,
          routine: s.template?.name ?? 'Free Lift',
          startedAt: s.startedAt,
          completedAt: s.completedAt,
          totalVolume: formatWeight(s.totalVolume, unit),
          unit,
          sets: s.setLogs.map((l) => ({
            exercise: l.exercise.name,
            muscleGroup: l.exercise.muscleGroup,
            accessory: l.exercise.accessory,
            setNumber: l.setNumber,
            mode: l.mode,
            weight: formatWeight(l.weightLb, unit),
            reps: l.repsCompleted,
            volume: formatWeight(l.volumeLb, unit),
            timestamp: l.timestamp,
          })),
        })),
      });
    } catch (err) {
      next(err);
    }
  });

  // SQLite Database Snapshot Backup
  router.get('/admin/backup/db', async (_req, res, next) => {
    const tmpPath = path.join(os.tmpdir(), `swonal-backup-${crypto.randomUUID()}.db`);
    const cleanup = () => {
      fs.promises.unlink(tmpPath).catch(() => {});
    };
    try {
      await prisma.$executeRaw`VACUUM INTO ${tmpPath}`;
      res.setHeader('Content-Type', 'application/octet-stream');
      res.setHeader('Content-Disposition', `attachment; filename="swonal-backup-${new Date().toISOString().slice(0, 10)}.db"`);
      const stream = fs.createReadStream(tmpPath);
      res.on('close', cleanup);
      stream.on('error', (streamErr) => {
        cleanup();
        if (!res.headersSent) {
          next(streamErr);
        } else {
          res.destroy(streamErr);
        }
      });
      stream.pipe(res);
    } catch (err) {
      cleanup();
      next(err);
    }
  });

  // BYOK AI Voice Coach
  router.get('/coach/config', (_req, res) => {
    res.json(aiCoachService.getPublicConfig());
  });

  router.post('/coach/config', (req, res, next) => {
    try {
      const schema = z.object({
        provider: z.enum(['local', 'openai', 'anthropic', 'gemini', 'ollama']).optional(),
        model: z.string().max(120).optional(),
        apiKey: z.string().max(300).optional(),
        baseUrl: z.string().max(300).optional(),
        clearApiKey: z.boolean().optional(),
      });
      const parsed = schema.parse(req.body);
      aiCoachService.setConfig(parsed);
      res.json(aiCoachService.getPublicConfig());
    } catch (err) {
      next(err);
    }
  });

  router.post('/coach/test', async (_req, res, next) => {
    try {
      const result = await aiCoachService.testConnection();
      res.json(result);
    } catch (err) {
      next(err);
    }
  });

  router.post('/coach/ask', async (req, res, next) => {
    try {
      const schema = z.object({
        userId: z.string().min(1).optional(),
        prompt: z.string().min(1).max(600),
        exerciseName: z.string().optional(),
        currentWeightLb: z.number().optional(),
        repCount: z.number().optional(),
        meanVelocity: z.number().optional(),
        velocityDropPct: z.number().optional(),
      });
      const parsed = schema.parse(req.body);
      const userId = parsed.userId ?? sessionCoordinator.getState().activeUserId;
      const result = await aiCoachService.ask({ ...parsed, userId });
      res.json(result);
    } catch (err) {
      next(err);
    }
  });

  // Network IP discovery for phone Companion QR code
  router.get('/network-info', (req, res) => {
    const ifaces = os.networkInterfaces();
    const candidates: Array<{ iface: string; address: string; score: number }> = [];

    const isVirtualOrCgnat = (ifaceName: string, addr: string): boolean => {
      const lower = ifaceName.toLowerCase();
      if (/^(docker|veth|br-|virbr|tun|tap|utun|tailscale|wg)/.test(lower)) return true;
      if (/^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(addr)) return true;
      if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(addr)) return true;
      return false;
    };

    for (const [iface, entries] of Object.entries(ifaces)) {
      if (!entries) continue;
      for (const entry of entries) {
        if (entry.family !== 'IPv4' || entry.internal) continue;
        if (isVirtualOrCgnat(iface, entry.address)) continue;
        const lower = iface.toLowerCase();
        let score = 10;
        if (/^(wl|wlan|en|eth|enp)/.test(lower)) {
          score = 20;
        }
        if (entry.address.startsWith('192.168.') || entry.address.startsWith('10.')) {
          score += 5;
        }
        candidates.push({ iface, address: entry.address, score });
      }
    }

    candidates.sort((a, b) => b.score - a.score);
    const ips = Array.from(new Set(candidates.map((c) => c.address)));
    const primaryIp = ips[0] ?? null;

    const hostHeader = req.get('host') || '';
    const portMatch = hostHeader.match(/:(\d+)$/);
    const port = portMatch ? Number(portMatch[1]) : Number(process.env.PORT || 3000);
    const overrideBase = getEffectivePublicBaseUrl();
    const companionBaseUrl =
      overrideBase || (primaryIp ? `${req.protocol}://${primaryIp}:${port}` : null);

    const distSibling = path.resolve(__dirname, 'mcp/stdio.js');
    const mcpStdioPath = fs.existsSync(distSibling)
      ? distSibling
      : path.resolve(__dirname, '../../../dist/server/mcp/stdio.js');

    res.json({
      primaryIp,
      ips,
      port,
      publicUrl: overrideBase || null,
      publicUrlOverride: getPublicUrlOverride(),
      companionBaseUrl,
      mcpStdioPath,
    });
  });

  router.post('/network-info/public-url', (req, res, next) => {
    try {
      const schema = z.object({
        publicUrl: z.string().max(300).nullable().optional(),
      });
      const parsed = schema.parse(req.body ?? {});
      const saved = setPublicUrlOverride(parsed.publicUrl ?? null);
      res.json({
        publicUrl: getEffectivePublicBaseUrl() || null,
        publicUrlOverride: saved,
      });
    } catch (err) {
      next(err);
    }
  });

  return router;
}

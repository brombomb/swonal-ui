import { Router } from 'express';
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
import { estimateOneRepMaxLb, MAX_WEIGHT_LB, MIN_WEIGHT_LB } from '../utils/units';

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

const CreateTemplateSchema = z.object({
  userId: z.string().uuid(),
  name: z.string().min(1),
  description: z.string().optional(),
  unit: z.enum(['LB', 'KG']).optional(),
  blocks: z.array(WorkoutBlockSchema).min(1),
});

const UpdateTemplateSchema = z.object({
  name: z.string().min(1).optional(),
  description: z.string().optional(),
  unit: z.enum(['LB', 'KG']).optional(),
  blocks: z.array(WorkoutBlockSchema).min(1).optional(),
});

const CreateSessionSchema = z.object({
  userId: z.string().uuid(),
  templateId: z.string().uuid().optional().nullable(),
});

const LogSetSchema = z.object({
  userId: z.string().uuid().optional(),
  exerciseId: z.string().uuid(),
  setNumber: z.number().int().min(1),
  mode: z.enum(['standard', 'eccentric', 'chains', 'spotter', 'burnout']).default('standard'),
  modifierPct: z.number().int().min(0).max(100).default(0),
  weightLb: z.number().min(MIN_WEIGHT_LB).max(MAX_WEIGHT_LB),
  repsCompleted: z.number().int().min(0).max(500),
  rpe: z.number().min(1).max(10).optional().nullable(),
  restSeconds: z.number().int().min(0).max(600).default(60),
});

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

  router.get('/state', async (_req, res, next) => {
    try {
      const [users, exercises, templates] = await Promise.all([
        prisma.user.findMany({ orderBy: { createdAt: 'asc' } }),
        getExercisesCatalog(),
        prisma.workoutTemplate.findMany({ orderBy: { createdAt: 'desc' } }),
      ]);
      res.json({
        sessionState: sessionCoordinator.getState(),
        daemonConnected: bridge.isDaemonConnected(),
        users,
        exercises,
        templates: templates.map((t) => ({ ...t, blocks: JSON.parse(t.blocksJson) })),
      });
    } catch (err) {
      next(err);
    }
  });

  // Users
  router.get('/users', async (_req, res, next) => {
    try {
      const users = await prisma.user.findMany({ orderBy: { createdAt: 'asc' } });
      res.json(users);
    } catch (err) {
      next(err);
    }
  });

  router.post('/users', async (req, res, next) => {
    try {
      const parsed = CreateUserSchema.parse(req.body);
      const user = await prisma.user.create({ data: parsed });
      bridge.broadcastToClients({ type: 'users_updated' });
      res.status(201).json(user);
    } catch (err) {
      next(err);
    }
  });

  router.patch('/users/:id', async (req, res, next) => {
    try {
      const parsed = UpdateUserSchema.parse(req.body);
      const user = await prisma.user.update({
        where: { id: req.params.id },
        data: parsed,
      });
      bridge.broadcastToClients({ type: 'user_updated', user });
      res.json(user);
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

  // Templates
  router.get('/templates', async (req, res, next) => {
    try {
      const userId = typeof req.query.userId === 'string' ? req.query.userId : undefined;
      const templates = await prisma.workoutTemplate.findMany({
        where: userId ? { userId } : undefined,
        orderBy: { createdAt: 'desc' },
      });
      res.json(templates.map((t) => ({ ...t, blocks: JSON.parse(t.blocksJson) })));
    } catch (err) {
      next(err);
    }
  });

  router.post('/templates', async (req, res, next) => {
    try {
      const parsed = CreateTemplateSchema.parse(req.body);
      const created = await createCustomWorkoutTemplate(parsed);
      bridge.broadcastToClients({ type: 'templates_updated' });
      res.status(201).json({ ...created, blocks: JSON.parse(created.blocksJson) });
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
      res.json({ ...updated, blocks: JSON.parse(updated.blocksJson) });
    } catch (err) {
      next(err);
    }
  });

  router.delete('/templates/:id', async (req, res, next) => {
    try {
      await prisma.workoutTemplate.delete({ where: { id: req.params.id } });
      bridge.broadcastToClients({ type: 'templates_updated' });
      res.json({ status: 'deleted', id: req.params.id });
    } catch (err) {
      next(err);
    }
  });

  router.post('/templates/:id/queue', async (req, res, next) => {
    try {
      const template = await prisma.workoutTemplate.findUnique({
        where: { id: req.params.id },
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
      });
      sessionCoordinator.setActiveSession(null, null);
      sessionCoordinator.setRestTimer(null);
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

  return router;
}

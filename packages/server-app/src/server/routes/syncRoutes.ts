import { Router } from 'express';
import { z } from 'zod';
import { prisma } from '../db';
import {
  clearPendingQueuedWorkout,
  getPendingQueuedWorkout,
} from '../mcp/mcpServer';
import { getDemoPipEnabled, setDemoPipEnabled } from '../services/runtimeSettings';
import { normalizeSyncedAllowedPositions, parsePositionSet } from '../utils/armPositions';

type BroadcastFn = (payload: Record<string, unknown>) => void;

const SyncUserSchema = z.object({
  id: z.string().min(1).max(120),
  name: z.string().min(1).max(60),
  avatarColor: z.string().max(24).default('#3B82F6'),
  preferredMusicProvider: z.string().max(40).default('spotify'),
  themeMode: z.string().max(16).default('dark'),
  accentColor: z.string().max(24).default('#00F0FF'),
  weightUnit: z.string().max(8).default('LB'),
  nostrPubKeyHex: z.string().nullable().optional(),
  nostrNpub: z.string().nullable().optional(),
  nostrRelays: z.string().nullable().optional(),
  shareWorkouts: z.boolean().default(true),
  demoPipEnabled: z.boolean().default(true),
  createdAt: z.string().optional(),
  updatedAt: z.string().optional(),
  updatedAtMs: z.number().nonnegative().default(0),
  deletedAtMs: z.number().nullable().optional(),
});

const SyncExerciseSchema = z.object({
  id: z.string().min(1).max(120),
  name: z.string().min(1).max(120),
  muscleGroup: z.string().min(1).max(60),
  accessory: z.string().max(32).default('HANDLES'),
  recommendedHeight: z.number().int().default(4),
  recommendedRotation: z.number().int().default(2),
  recommendedAngle: z.number().int().default(1),
  // Lenient on purpose: out-of-range values are dropped and recommended* is added on accept.
  // Omitted (older kiosks) = keep the stored set; new rows default to [].
  allowedHeights: z.array(z.number()).nullish(),
  allowedRotations: z.array(z.number()).nullish(),
  allowedAngles: z.array(z.number()).nullish(),
  unilateral: z.boolean().default(false),
  notes: z.string().nullable().optional(),
  mediaUrl: z.string().nullable().optional(),
  updatedAtMs: z.number().nonnegative().default(0),
  deletedAtMs: z.number().nullable().optional(),
});

const SyncTemplateSchema = z.object({
  id: z.string().min(1).max(120),
  userId: z.string().min(1).max(120),
  name: z.string().min(1).max(120),
  description: z.string().nullable().optional(),
  blocksJson: z.string().default('[]'),
  scheduleDays: z.array(z.number().int().min(0).max(6)).default([]),
  createdAt: z.string().optional(),
  updatedAt: z.string().optional(),
  updatedAtMs: z.number().nonnegative().default(0),
  deletedAtMs: z.number().nullable().optional(),
});

const SyncSessionSchema = z.object({
  id: z.string().min(1).max(120),
  userId: z.string().min(1).max(120),
  templateId: z.string().nullable().optional(),
  startedAt: z.string(),
  completedAt: z.string().nullable().optional(),
  totalVolume: z.number().default(0),
  updatedAtMs: z.number().nonnegative().default(0),
});

const SyncSetLogSchema = z.object({
  id: z.string().min(1).max(120),
  sessionId: z.string().min(1).max(120),
  exerciseId: z.string().min(1).max(120),
  setNumber: z.number().int().min(1),
  mode: z.string().default('standard'),
  modifierPct: z.number().int().min(0).max(100).default(0),
  weightLb: z.number().min(0).max(500),
  repsCompleted: z.number().int().min(0).max(500),
  volumeLb: z.number().min(0).default(0),
  rpe: z.number().nullable().optional(),
  timestamp: z.string(),
  updatedAtMs: z.number().nonnegative().default(0),
});

const SyncPrSchema = z.object({
  id: z.string().min(1).max(120),
  userId: z.string().min(1).max(120),
  exerciseId: z.string().min(1).max(120),
  oneRepMaxEst: z.number().min(0),
  maxWeightLb: z.number().min(0),
  updatedAt: z.string().optional(),
  updatedAtMs: z.number().nonnegative().default(0),
});

const SyncFriendSchema = z.object({
  id: z.string().min(1).max(120),
  userId: z.string().min(1).max(120),
  npub: z.string().min(1).max(128),
  pubkeyHex: z.string().min(1).max(128),
  displayName: z.string().min(1).max(80),
  avatarUrl: z.string().nullable().optional(),
  relaysJson: z.string().nullable().optional(),
  lastSeenAt: z.string().nullable().optional(),
  createdAt: z.string().optional(),
  updatedAt: z.string().optional(),
  updatedAtMs: z.number().nonnegative().default(0),
  deletedAtMs: z.number().nullable().optional(),
});

const SyncPushSchema = z.object({
  deviceId: z.string().max(120).default('tonal-kiosk'),
  pushedAtMs: z.number().nonnegative().default(0),
  users: z.array(SyncUserSchema).default([]),
  exercises: z.array(SyncExerciseSchema).default([]),
  templates: z.array(SyncTemplateSchema).default([]),
  sessions: z.array(SyncSessionSchema).default([]),
  setLogs: z.array(SyncSetLogSchema).default([]),
  personalRecords: z.array(SyncPrSchema).default([]),
  friends: z.array(SyncFriendSchema).default([]),
});

function parseScheduleDays(raw: string | null | undefined): number[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed)
      ? parsed.filter((d): d is number => Number.isInteger(d) && d >= 0 && d <= 6)
      : [];
  } catch {
    return [];
  }
}

function rewriteBlocksExerciseIds(
  blocksJson: string,
  remap: Record<string, string>
): string {
  if (Object.keys(remap).length === 0) return blocksJson;
  try {
    const parsed = JSON.parse(blocksJson);
    if (!Array.isArray(parsed)) return blocksJson;
    const mapped = parsed.map((blk: Record<string, unknown>) => {
      if (!Array.isArray(blk.items)) return blk;
      return {
        ...blk,
        items: blk.items.map((item: Record<string, unknown>) => {
          const oldId = typeof item.exerciseId === 'string' ? item.exerciseId : '';
          return {
            ...item,
            exerciseId: remap[oldId] ?? oldId,
          };
        }),
      };
    });
    return JSON.stringify(mapped);
  } catch {
    return blocksJson;
  }
}

function toSafeDate(ms: number | null | undefined, fallback?: string): Date {
  if (typeof ms === 'number' && Number.isFinite(ms) && ms > 0) {
    return new Date(ms);
  }
  if (fallback) {
    const d = new Date(fallback);
    if (!Number.isNaN(d.getTime())) return d;
  }
  return new Date();
}

export interface SyncRouterDeps {
  broadcastToClients: BroadcastFn;
  notifyTonalSyncPoke?: (originDeviceId?: string) => void;
  isTonalSyncConnected?: () => boolean;
}

export function createSyncRouter(deps: SyncRouterDeps): Router {
  const router = Router();

  router.get('/status', async (_req, res, next) => {
    try {
      const pending = await getPendingQueuedWorkout();
      res.json({
        ok: true,
        serverTimeMs: Date.now(),
        tonalSyncConnected: deps.isTonalSyncConnected?.() ?? false,
        hasPendingQueuedWorkout: pending !== null,
      });
    } catch (err) {
      next(err);
    }
  });

  router.post('/push', async (req, res, next) => {
    try {
      const bundle = SyncPushSchema.parse(req.body ?? {});
      const exerciseIdRemap: Record<string, string> = {};
      const acceptedCounts: Record<string, number> = {
        users: 0,
        exercises: 0,
        templates: 0,
        sessions: 0,
        setLogs: 0,
        personalRecords: 0,
        friends: 0,
      };

      await prisma.$transaction(async (tx) => {
        // 1. Users (LWW)
        for (const u of bundle.users) {
          const existing = await tx.user.findUnique({ where: { id: u.id } });
          const deletedAt = u.deletedAtMs ? new Date(u.deletedAtMs) : null;
          if (!existing) {
            await tx.user.create({
              data: {
                id: u.id,
                name: u.name,
                avatarColor: u.avatarColor,
                preferredMusicProvider: u.preferredMusicProvider,
                themeMode: u.themeMode,
                accentColor: u.accentColor,
                weightUnit: u.weightUnit,
                nostrPubKeyHex: u.nostrPubKeyHex ?? null,
                nostrNpub: u.nostrNpub ?? null,
                nostrRelays: u.nostrRelays ?? null,
                shareWorkouts: u.shareWorkouts,
                createdAt: toSafeDate(null, u.createdAt),
                deletedAt,
              },
            });
            setDemoPipEnabled(u.id, u.demoPipEnabled);
            acceptedCounts.users += 1;
          } else if (u.updatedAtMs >= existing.updatedAt.getTime()) {
            await tx.user.update({
              where: { id: u.id },
              data: {
                name: u.name,
                avatarColor: u.avatarColor,
                preferredMusicProvider: u.preferredMusicProvider,
                themeMode: u.themeMode,
                accentColor: u.accentColor,
                weightUnit: u.weightUnit,
                ...(u.nostrPubKeyHex ? { nostrPubKeyHex: u.nostrPubKeyHex } : {}),
                ...(u.nostrNpub ? { nostrNpub: u.nostrNpub } : {}),
                ...(u.nostrRelays ? { nostrRelays: u.nostrRelays } : {}),
                shareWorkouts: u.shareWorkouts,
                deletedAt,
              },
            });
            setDemoPipEnabled(u.id, u.demoPipEnabled);
            acceptedCounts.users += 1;
          }
        }

        // 2. Exercises (match by id OR unique name, build canonical remap)
        for (const ex of bundle.exercises) {
          const byId = await tx.exercise.findUnique({ where: { id: ex.id } });
          const byName = !byId
            ? await tx.exercise.findUnique({ where: { name: ex.name } })
            : null;
          const existing = byId ?? byName;
          const deletedAt = ex.deletedAtMs ? new Date(ex.deletedAtMs) : null;

          if (byName && byName.id !== ex.id) {
            exerciseIdRemap[ex.id] = byName.id;
          }

          if (!existing) {
            await tx.exercise.create({
              data: {
                id: ex.id,
                name: ex.name,
                muscleGroup: ex.muscleGroup,
                accessory: ex.accessory,
                recommendedHeight: ex.recommendedHeight,
                recommendedRotation: ex.recommendedRotation,
                recommendedAngle: ex.recommendedAngle,
                ...normalizeSyncedAllowedPositions(ex),
                unilateral: ex.unilateral,
                notes: ex.notes ?? null,
                mediaUrl: ex.mediaUrl ?? null,
                deletedAt,
              },
            });
            acceptedCounts.exercises += 1;
          } else if (ex.updatedAtMs >= existing.updatedAt.getTime()) {
            const nameConflict = existing.name !== ex.name
              ? await tx.exercise.findFirst({ where: { name: ex.name, NOT: { id: existing.id } } })
              : null;
            await tx.exercise.update({
              where: { id: existing.id },
              data: {
                ...(nameConflict ? {} : { name: ex.name }),
                muscleGroup: ex.muscleGroup,
                accessory: ex.accessory,
                recommendedHeight: ex.recommendedHeight,
                recommendedRotation: ex.recommendedRotation,
                recommendedAngle: ex.recommendedAngle,
                ...normalizeSyncedAllowedPositions(ex, existing),
                unilateral: ex.unilateral,
                notes: ex.notes ?? null,
                mediaUrl: ex.mediaUrl ?? null,
                deletedAt,
              },
            });
            acceptedCounts.exercises += 1;
          }
        }

        // 3. Templates (LWW + rewrite remapped exercise IDs)
        for (const tpl of bundle.templates) {
          const existing = await tx.workoutTemplate.findUnique({ where: { id: tpl.id } });
          const cleanBlocks = rewriteBlocksExerciseIds(tpl.blocksJson, exerciseIdRemap);
          const scheduleStr = JSON.stringify(tpl.scheduleDays);
          const deletedAt = tpl.deletedAtMs ? new Date(tpl.deletedAtMs) : null;
          if (!existing) {
            await tx.workoutTemplate.create({
              data: {
                id: tpl.id,
                userId: tpl.userId,
                name: tpl.name,
                description: tpl.description ?? null,
                blocksJson: cleanBlocks,
                scheduleDays: scheduleStr,
                createdAt: toSafeDate(null, tpl.createdAt),
                deletedAt,
              },
            });
            acceptedCounts.templates += 1;
          } else if (tpl.updatedAtMs >= existing.updatedAt.getTime()) {
            await tx.workoutTemplate.update({
              where: { id: tpl.id },
              data: {
                userId: tpl.userId,
                name: tpl.name,
                description: tpl.description ?? null,
                blocksJson: cleanBlocks,
                scheduleDays: scheduleStr,
                deletedAt,
              },
            });
            acceptedCounts.templates += 1;
          }
        }

        // 4. Sessions (idempotent upsert, preserve completedAt & max volume)
        for (const s of bundle.sessions) {
          const existing = await tx.workoutSession.findUnique({ where: { id: s.id } });
          const validTemplate = s.templateId
            ? await tx.workoutTemplate.findUnique({ where: { id: s.templateId }, select: { id: true } })
            : null;
          if (!existing) {
            await tx.workoutSession.create({
              data: {
                id: s.id,
                userId: s.userId,
                templateId: validTemplate ? s.templateId : null,
                startedAt: toSafeDate(null, s.startedAt),
                completedAt: s.completedAt ? toSafeDate(null, s.completedAt) : null,
                totalVolume: s.totalVolume,
              },
            });
            acceptedCounts.sessions += 1;
          } else {
            await tx.workoutSession.update({
              where: { id: s.id },
              data: {
                completedAt:
                  existing.completedAt ??
                  (s.completedAt ? toSafeDate(null, s.completedAt) : null),
                totalVolume: Math.max(existing.totalVolume, s.totalVolume),
              },
            });
            acceptedCounts.sessions += 1;
          }
        }

        // 5. Append-only SetLogs (idempotent on UUID, recompute session totalVolume if newly inserted)
        const touchedSessions = new Set<string>();
        for (const log of bundle.setLogs) {
          const exists = await tx.setLog.findUnique({
            where: { id: log.id },
            select: { id: true },
          });
          if (exists) continue;
          const mappedExId = exerciseIdRemap[log.exerciseId] ?? log.exerciseId;
          const sessionExists = await tx.workoutSession.findUnique({
            where: { id: log.sessionId },
            select: { id: true },
          });
          if (!sessionExists) continue;
          await tx.setLog.create({
            data: {
              id: log.id,
              sessionId: log.sessionId,
              exerciseId: mappedExId,
              setNumber: log.setNumber,
              mode: log.mode,
              modifierPct: log.modifierPct,
              weightLb: log.weightLb,
              repsCompleted: log.repsCompleted,
              volumeLb: log.volumeLb,
              rpe: log.rpe ?? null,
              timestamp: toSafeDate(null, log.timestamp),
            },
          });
          touchedSessions.add(log.sessionId);
          acceptedCounts.setLogs += 1;
        }
        for (const sid of touchedSessions) {
          const agg = await tx.setLog.aggregate({
            where: { sessionId: sid },
            _sum: { volumeLb: true },
          });
          await tx.workoutSession.update({
            where: { id: sid },
            data: { totalVolume: Math.round((agg._sum.volumeLb ?? 0) * 10) / 10 },
          });
        }

        // 6. PersonalRecords (max envelope). A PR with no estimated 1RM came from a set with
        // no completed reps: skip that row (not the whole push, so its sessions and set logs
        // still land) so an unpatched peer can't sync a 0-rep "record" back in.
        for (const pr of bundle.personalRecords) {
          if (!(pr.oneRepMaxEst > 0)) continue;
          const mappedExId = exerciseIdRemap[pr.exerciseId] ?? pr.exerciseId;
          const existing = await tx.personalRecord.findUnique({
            where: {
              userId_exerciseId: {
                userId: pr.userId,
                exerciseId: mappedExId,
              },
            },
          });
          if (!existing) {
            await tx.personalRecord.create({
              data: {
                id: pr.id,
                userId: pr.userId,
                exerciseId: mappedExId,
                oneRepMaxEst: pr.oneRepMaxEst,
                maxWeightLb: pr.maxWeightLb,
              },
            });
          } else {
            await tx.personalRecord.update({
              where: { id: existing.id },
              data: {
                oneRepMaxEst: Math.max(existing.oneRepMaxEst, pr.oneRepMaxEst),
                maxWeightLb: Math.max(existing.maxWeightLb, pr.maxWeightLb),
              },
            });
          }
          acceptedCounts.personalRecords += 1;
        }

        // 7. Friends (LWW on unique [userId, pubkeyHex])
        for (const fr of bundle.friends) {
          const existing = await tx.friend.findUnique({
            where: {
              userId_pubkeyHex: {
                userId: fr.userId,
                pubkeyHex: fr.pubkeyHex,
              },
            },
          });
          const deletedAt = fr.deletedAtMs ? new Date(fr.deletedAtMs) : null;
          if (!existing) {
            await tx.friend.create({
              data: {
                id: fr.id,
                userId: fr.userId,
                npub: fr.npub,
                pubkeyHex: fr.pubkeyHex,
                displayName: fr.displayName,
                avatarUrl: fr.avatarUrl ?? null,
                relaysJson: fr.relaysJson ?? null,
                lastSeenAt: fr.lastSeenAt ? toSafeDate(null, fr.lastSeenAt) : null,
                createdAt: toSafeDate(null, fr.createdAt),
                deletedAt,
              },
            });
            acceptedCounts.friends += 1;
          } else if (fr.updatedAtMs >= existing.updatedAt.getTime()) {
            await tx.friend.update({
              where: { id: existing.id },
              data: {
                npub: fr.npub,
                displayName: fr.displayName,
                avatarUrl: fr.avatarUrl ?? null,
                relaysJson: fr.relaysJson ?? null,
                lastSeenAt: fr.lastSeenAt ? toSafeDate(null, fr.lastSeenAt) : existing.lastSeenAt,
                deletedAt,
              },
            });
            acceptedCounts.friends += 1;
          }
        }
      });

      if (acceptedCounts.users > 0) deps.broadcastToClients({ type: 'users_updated' });
      if (acceptedCounts.exercises > 0) deps.broadcastToClients({ type: 'exercises_updated' });
      if (acceptedCounts.templates > 0) deps.broadcastToClients({ type: 'templates_updated' });
      if (
        acceptedCounts.users + acceptedCounts.exercises + acceptedCounts.templates + acceptedCounts.sessions >
        0
      ) {
        deps.notifyTonalSyncPoke?.(bundle.deviceId);
      }

      res.json({
        ok: true,
        serverTimeMs: Date.now(),
        exerciseIdRemap,
        acceptedCounts,
      });
    } catch (err) {
      next(err);
    }
  });

  async function buildPullPayload(sinceMs: number, includeParentTombstones: boolean) {
    const sinceDate = sinceMs > 0 ? new Date(sinceMs) : null;
    const serverTimeMs = Date.now();
    // Always include parent tombstones on full pulls/restore bundles so foreign-key integrity
    // never breaks when historical sessions/logs reference soft-deleted users, exercises, or routines.
    const includeDeleted = includeParentTombstones || sinceMs === 0;

    const [users, exercises, templates, sessions, setLogs, prs, friends, pendingQueuedWorkout] =
      await Promise.all([
        prisma.user.findMany({
          where: {
            ...(includeDeleted ? {} : { deletedAt: null }),
            ...(sinceDate ? { updatedAt: { gte: sinceDate } } : {}),
          },
          orderBy: { createdAt: 'asc' },
        }),
        prisma.exercise.findMany({
          where: {
            ...(includeDeleted ? {} : { deletedAt: null }),
            ...(sinceDate ? { updatedAt: { gte: sinceDate } } : {}),
          },
          orderBy: { name: 'asc' },
        }),
        prisma.workoutTemplate.findMany({
          where: {
            ...(includeDeleted ? {} : { deletedAt: null }),
            ...(sinceDate ? { updatedAt: { gte: sinceDate } } : {}),
          },
          orderBy: { createdAt: 'desc' },
        }),
        prisma.workoutSession.findMany({
          where: sinceDate ? { updatedAt: { gte: sinceDate } } : undefined,
          orderBy: { startedAt: 'desc' },
        }),
        prisma.setLog.findMany({
          where: sinceDate ? { updatedAt: { gte: sinceDate } } : undefined,
          orderBy: { timestamp: 'asc' },
        }),
        // Never hand out a PR with no estimated 1RM (a 0-rep set's leftover), on pull or restore.
        prisma.personalRecord.findMany({
          where: {
            oneRepMaxEst: { gt: 0 },
            ...(sinceDate ? { updatedAt: { gte: sinceDate } } : {}),
          },
        }),
        prisma.friend.findMany({
          where: {
            ...(includeDeleted ? {} : { deletedAt: null }),
            ...(sinceDate ? { updatedAt: { gte: sinceDate } } : {}),
          },
        }),
        getPendingQueuedWorkout(),
      ]);

    return {
      serverTimeMs,
      users: users.map((u) => ({
        id: u.id,
        name: u.name,
        avatarColor: u.avatarColor,
        preferredMusicProvider: u.preferredMusicProvider,
        themeMode: u.themeMode,
        accentColor: u.accentColor,
        weightUnit: u.weightUnit,
        nostrPubKeyHex: u.nostrPubKeyHex,
        nostrNpub: u.nostrNpub,
        nostrRelays: u.nostrRelays,
        shareWorkouts: u.shareWorkouts,
        demoPipEnabled: getDemoPipEnabled(u.id),
        createdAt: u.createdAt.toISOString(),
        updatedAt: u.updatedAt.toISOString(),
        updatedAtMs: u.updatedAt.getTime(),
        deletedAtMs: u.deletedAt ? u.deletedAt.getTime() : null,
      })),
      exercises: exercises.map((e) => ({
        id: e.id,
        name: e.name,
        muscleGroup: e.muscleGroup,
        accessory: e.accessory,
        recommendedHeight: e.recommendedHeight,
        recommendedRotation: e.recommendedRotation,
        recommendedAngle: e.recommendedAngle,
        allowedHeights: parsePositionSet(e.allowedHeights),
        allowedRotations: parsePositionSet(e.allowedRotations),
        allowedAngles: parsePositionSet(e.allowedAngles),
        unilateral: e.unilateral,
        notes: e.notes,
        mediaUrl: e.mediaUrl,
        updatedAtMs: e.updatedAt.getTime(),
        deletedAtMs: e.deletedAt ? e.deletedAt.getTime() : null,
      })),
      templates: templates.map((t) => ({
        id: t.id,
        userId: t.userId,
        name: t.name,
        description: t.description,
        blocksJson: t.blocksJson,
        scheduleDays: parseScheduleDays(t.scheduleDays),
        createdAt: t.createdAt.toISOString(),
        updatedAt: t.updatedAt.toISOString(),
        updatedAtMs: t.updatedAt.getTime(),
        deletedAtMs: t.deletedAt ? t.deletedAt.getTime() : null,
      })),
      sessions: sessions.map((s) => ({
        id: s.id,
        userId: s.userId,
        templateId: s.templateId,
        startedAt: s.startedAt.toISOString(),
        completedAt: s.completedAt ? s.completedAt.toISOString() : null,
        totalVolume: s.totalVolume,
        updatedAtMs: Math.max(s.updatedAt.getTime(), (s.completedAt ?? s.startedAt).getTime()),
      })),
      setLogs: setLogs.map((l) => ({
        id: l.id,
        sessionId: l.sessionId,
        exerciseId: l.exerciseId,
        setNumber: l.setNumber,
        mode: l.mode,
        modifierPct: l.modifierPct,
        weightLb: l.weightLb,
        repsCompleted: l.repsCompleted,
        volumeLb: l.volumeLb,
        rpe: l.rpe,
        timestamp: l.timestamp.toISOString(),
        updatedAtMs: Math.max(l.updatedAt.getTime(), l.timestamp.getTime()),
      })),
      personalRecords: prs.map((p) => ({
        id: p.id,
        userId: p.userId,
        exerciseId: p.exerciseId,
        oneRepMaxEst: p.oneRepMaxEst,
        maxWeightLb: p.maxWeightLb,
        updatedAt: p.updatedAt.toISOString(),
        updatedAtMs: p.updatedAt.getTime(),
      })),
      friends: friends.map((f) => ({
        id: f.id,
        userId: f.userId,
        npub: f.npub,
        pubkeyHex: f.pubkeyHex,
        displayName: f.displayName,
        avatarUrl: f.avatarUrl,
        relaysJson: f.relaysJson,
        lastSeenAt: f.lastSeenAt ? f.lastSeenAt.toISOString() : null,
        createdAt: f.createdAt.toISOString(),
        updatedAt: f.updatedAt.toISOString(),
        updatedAtMs: f.updatedAt.getTime(),
        deletedAtMs: f.deletedAt ? f.deletedAt.getTime() : null,
      })),
      pendingQueuedWorkout,
    };
  }

  router.get('/pull', async (req, res, next) => {
    try {
      const sinceRaw = typeof req.query.since === 'string' ? Number(req.query.since) : 0;
      const sinceMs = Number.isFinite(sinceRaw) && sinceRaw > 0 ? sinceRaw : 0;
      const payload = await buildPullPayload(sinceMs, sinceMs > 0);
      res.json(payload);
    } catch (err) {
      next(err);
    }
  });

  router.post('/ack-queued-workout', async (req, res, next) => {
    try {
      const body = req.body as { queuedAt?: unknown } | undefined;
      const queuedAt = typeof body?.queuedAt === 'string' ? body.queuedAt : undefined;
      await clearPendingQueuedWorkout(queuedAt);
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  const handleRestoreBundle = async (_req: unknown, res: { json: (v: unknown) => void }, next: (e: unknown) => void) => {
    try {
      const bundle = await buildPullPayload(0, true);
      res.json({
        version: 1,
        exportedAt: new Date().toISOString(),
        ...bundle,
      });
    } catch (err) {
      next(err);
    }
  };
  router.get('/restore-bundle', handleRestoreBundle);
  router.post('/restore-bundle', handleRestoreBundle);

  return router;
}

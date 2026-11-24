import dotenv from 'dotenv';
import cors from 'cors';
import express, { NextFunction, Request, Response } from 'express';
import fs from 'fs';
import http from 'http';
import path from 'path';

// Load package-local .env first, then monorepo root .env, filling any missing or empty values
// Set SWONAL_SKIP_DOTENV=1 in clean-room CI runs to avoid loading developer .env files
if (process.env.SWONAL_SKIP_DOTENV !== '1') {
  for (const envPath of [
    path.resolve(process.cwd(), '.env'),
    path.resolve(__dirname, '../../../.env'),
    path.resolve(__dirname, '../../../../.env'),
  ]) {
    if (!fs.existsSync(envPath)) continue;
    const parsed = dotenv.parse(fs.readFileSync(envPath));
    for (const [k, v] of Object.entries(parsed)) {
      if (!process.env[k]?.trim() && v.trim()) {
        process.env[k] = v;
      }
    }
  }
}
import WebSocket, { WebSocketServer } from 'ws';
import { ZodError } from 'zod';
import { SSEServerTransport } from '@modelcontextprotocol/sdk/server/sse.js';
import { prisma } from './db';
import { seedDatabase } from '../../prisma/seed';
import { createSwonalMcpServer, clearPendingQueuedWorkout, getPendingQueuedWorkout } from './mcp/mcpServer';
import { musicRegistry } from './music/Registry';
import { createApiRouter } from './routes/apiRoutes';
import { createDaemonRouter } from './routes/daemonRoutes';
import { createMusicRouter } from './routes/musicRoutes';
import { createSpotifyRouter } from './routes/spotify';
import { createAuthRouter } from './routes/authRoutes';
import { createFriendsRouter } from './routes/friendsRoutes';
import { createSyncRouter } from './routes/syncRoutes';
import { startScheduledBackupLoop } from './services/backupService';
import { getServerConfig } from './config';
import { isRequestAuthenticated } from './services/authService';
import { getEffectivePublicBaseUrl } from './services/runtimeSettings';
import { sessionCoordinator } from './services/sessionState';
import { homeAssistantBus } from './integrations/homeAssistantBus';

export interface SwonalServerOptions {
  port?: number;
  daemonHttpUrl?: string;
  daemonWsUrl?: string;
  connectDaemonWs?: boolean;
}

export interface SwonalServerHandle {
  app: express.Express;
  server: http.Server;
  port: number;
  close: () => Promise<void>;
}

const DAEMON_FORWARD_TYPES = new Set([
  'set_weight',
  'toggle_weight',
  'enable_weight',
  'disable_weight',
  'set_accessory',
  'set_arm_positions',
  'reset_reps',
  'simulate_ble',
  'simulate_bar_tilt',
]);

export async function createSwonalServer(
  options: SwonalServerOptions = {}
): Promise<SwonalServerHandle> {
  const serverConfig = getServerConfig();

  // Auto-seed fresh SQLite databases cleanly (essential for first Docker boot)
  const existingUsers = await prisma.user.count();
  if (existingUsers === 0) {
    await seedDatabase(prisma);
  }

  const daemonHttpUrl =
    options.daemonHttpUrl ?? serverConfig.TONAL_DAEMON_URL;
  const daemonWsUrl =
    options.daemonWsUrl ?? serverConfig.TONAL_DAEMON_WS_URL;
  const connectDaemonWs = options.connectDaemonWs ?? true;

  const isAllowedOrigin = (origin: string): boolean => {
    try {
      const parsed = new URL(origin);
      if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
        return false;
      }
      const host = parsed.hostname.toLowerCase().replace(/^\[|\]$/g, '');
      const isLocalOrLan =
        host === 'localhost' ||
        host === '127.0.0.1' ||
        host === '::1' ||
        host.endsWith('.local') ||
        /^10\.\d+\.\d+\.\d+$/.test(host) ||
        /^192\.168\.\d+\.\d+$/.test(host) ||
        /^172\.(1[6-9]|2\d|3[0-1])\.\d+\.\d+$/.test(host);

      const publicBase = getEffectivePublicBaseUrl();
      let isPublicOrigin = false;
      if (publicBase) {
        try {
          isPublicOrigin = new URL(publicBase).origin === parsed.origin;
        } catch {
          isPublicOrigin = false;
        }
      }
      return isLocalOrLan || isPublicOrigin;
    } catch {
      return false;
    }
  };

  const app = express();
  app.set('trust proxy', 'loopback');
  app.use(
    cors({
      origin: (origin, callback) => {
        if (!origin || isAllowedOrigin(origin)) {
          callback(null, true);
          return;
        }
        callback(new Error('CORS origin not allowed'));
      },
    })
  );
  app.use(express.json({ limit: '2mb' }));

  homeAssistantBus.init();

  // Self-hosted exercise form demo media (/data/media or local data/media)
  const resolvedMediaDir =
    process.env.MEDIA_DIR ||
    (fs.existsSync('/data/media') ? '/data/media' : path.resolve(__dirname, '../../data/media'));
  if (!fs.existsSync(resolvedMediaDir)) {
    fs.mkdirSync(resolvedMediaDir, { recursive: true });
  }
  app.use('/media', express.static(resolvedMediaDir));

  const httpServer = http.createServer(app);
  const wss = new WebSocketServer({ noServer: true });
  const syncWss = new WebSocketServer({ noServer: true });
  const browserClients = new Set<WebSocket>();
  const tonalSyncClients = new Set<WebSocket>();

  httpServer.on('upgrade', (req, socket, head) => {
    const origin = req.headers.origin;
    if (origin && !isAllowedOrigin(origin)) {
      socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    const pathname = (req.url || '/').split('?')[0];
    if (pathname === '/ws') {
      wss.handleUpgrade(req, socket, head, (ws) => {
        wss.emit('connection', ws, req);
      });
    } else if (pathname === '/ws/sync') {
      syncWss.handleUpgrade(req, socket, head, (ws) => {
        syncWss.emit('connection', ws, req);
      });
    } else {
      socket.destroy();
    }
  });

  const stopBackupLoop = startScheduledBackupLoop(prisma);

  let daemonWs: WebSocket | null = null;
  let daemonConnected = false;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  let closing = false;
  let latestTelemetry: Record<string, unknown> = {
    type: 'telemetry',
    mode: 'CUSTOM',
    weight_enabled: false,
    target_weight_lb: 45,
    applied_resistance_lb: 0,
    profile_mode: 'standard',
    modifier_pct: 0,
    rep_count: 0,
    accessory: 'HANDLES',
    left_cable: { position: 0, velocity: 0, load_lb: 0 },
    right_cable: { position: 0, velocity: 0, load_lb: 0 },
    arm_positions: {
      height: { left: 4, right: 4 },
      rotation: { left: 2, right: 2, left_unlocked: false, right_unlocked: false },
      angle: { left: 1, right: 1 },
    },
  };

  const broadcastToSyncClients = (payload: Record<string, unknown>) => {
    const serialized = JSON.stringify(payload);
    for (const client of tonalSyncClients) {
      if (client.readyState === WebSocket.OPEN) {
        client.send(serialized);
      }
    }
  };

  const broadcastToClients = (payload: Record<string, unknown>) => {
    const serialized = JSON.stringify(payload);
    for (const client of browserClients) {
      if (client.readyState === WebSocket.OPEN) {
        client.send(serialized);
      }
    }
    if (
      payload.type === 'users_updated' ||
      payload.type === 'user_updated' ||
      payload.type === 'templates_updated' ||
      payload.type === 'exercises_updated'
    ) {
      broadcastToSyncClients({ type: 'sync_poke' });
    }
  };

  const sendToDaemon = (msg: Record<string, unknown>): boolean => {
    if (daemonWs && daemonWs.readyState === WebSocket.OPEN) {
      daemonWs.send(JSON.stringify(msg));
      return true;
    }
    return false;
  };

  let activeUserCache: { id: string; name: string; accentColor: string; themeMode: string; weightUnit: string } | null = null;
  const syncActiveUserCache = async (userId: string) => {
    const u = await prisma.user.findUnique({ where: { id: userId } });
    if (u) {
      activeUserCache = {
        id: u.id,
        name: u.name,
        accentColor: u.accentColor,
        themeMode: u.themeMode,
        weightUnit: u.weightUnit,
      };
    }
  };
  void syncActiveUserCache(sessionCoordinator.getState().activeUserId);

  const publishHaState = (safetyFault?: string | null) => {
    const st = sessionCoordinator.getState();
    if (!activeUserCache || activeUserCache.id !== st.activeUserId) {
      void syncActiveUserCache(st.activeUserId);
    }
    homeAssistantBus.publishStateIfChanged({
      userId: st.activeUserId,
      userName: activeUserCache?.name,
      accentColor: activeUserCache?.accentColor,
      themeMode: activeUserCache?.themeMode,
      weightUnit: activeUserCache?.weightUnit,
      partnerModeEnabled: st.partnerModeEnabled,
      weightEnabled: Boolean(latestTelemetry.weight_enabled),
      targetWeightLb: Number(latestTelemetry.target_weight_lb ?? 45),
      mode: String(latestTelemetry.profile_mode ?? 'standard'),
      accessory: String(latestTelemetry.accessory ?? 'HANDLES'),
      repCount: Number(latestTelemetry.rep_count ?? 0),
      safetyFault: safetyFault ?? null,
    });
  };

  const onStateChanged = (state: ReturnType<typeof sessionCoordinator.getState>) => {
    broadcastToClients({ type: 'session_state', state });
    void syncActiveUserCache(state.activeUserId).then(() => publishHaState());
  };
  const onWorkoutQueued = (payload: unknown) => {
    broadcastToClients({ type: 'workout_queued', workout: payload });
    broadcastToSyncClients({ type: 'workout_queued', workout: payload });
    void homeAssistantBus.publishSessionEvent('workout_queued', {
      userId: sessionCoordinator.getState().activeUserId,
      workout: payload,
    });
  };

  sessionCoordinator.on('state_changed', onStateChanged);
  sessionCoordinator.on('workout_queued', onWorkoutQueued);

  const connectToDaemon = () => {
    if (closing || !connectDaemonWs) return;
    try {
      const ws = new WebSocket(daemonWsUrl);
      daemonWs = ws;

      ws.on('open', () => {
        daemonConnected = true;
        broadcastToClients({ type: 'daemon_status', connected: true });
        ws.send(JSON.stringify({ type: 'ping' }));
      });

      ws.on('message', (raw) => {
        try {
          const msg = JSON.parse(raw.toString()) as Record<string, unknown>;
          if (msg.type === 'telemetry') {
            latestTelemetry = msg;
            publishHaState();
          } else if (msg.type === 'safety_event') {
            publishHaState(String(msg.reason || 'SAFETY_CUTOUT'));
            void homeAssistantBus.publishSessionEvent('safety_cutout', {
              userId: sessionCoordinator.getState().activeUserId,
              reason: msg.reason,
              delta: msg.delta,
            });
          } else if (msg.type === 'handle_event' && msg.action === 'spotify_next') {
            const activeUserId = sessionCoordinator.getState().activeUserId;
            void prisma.user
              .findUnique({ where: { id: activeUserId } })
              .then(async (user) => {
                const providerId = user?.preferredMusicProvider ?? 'spotify';
                await musicRegistry.get(providerId).nextTrack(activeUserId);
                broadcastToClients({
                  type: 'music_updated',
                  userId: activeUserId,
                  provider: providerId,
                });
              })
              .catch(() => {});
          }
          broadcastToClients(msg);
        } catch {
          // Ignore malformed frame
        }
      });

      ws.on('close', () => {
        daemonConnected = false;
        daemonWs = null;
        broadcastToClients({ type: 'daemon_status', connected: false });
        if (!closing) {
          reconnectTimer = setTimeout(connectToDaemon, 2500);
        }
      });

      ws.on('error', () => {
        // Error is followed by close event
      });
    } catch {
      if (!closing) {
        reconnectTimer = setTimeout(connectToDaemon, 2500);
      }
    }
  };

  let lastClientPingAt = 0;

  if (connectDaemonWs) {
    connectToDaemon();
    heartbeatTimer = setInterval(() => {
      if (daemonWs && daemonWs.readyState === WebSocket.OPEN) {
        // Only forward liveness heartbeat if an active UI client pinged within the last 5s
        if (browserClients.size > 0 && Date.now() - lastClientPingAt < 5000) {
          daemonWs.send(JSON.stringify({ type: 'ping' }));
        }
      }
    }, 2000);
  }

  wss.on('connection', (client, req) => {
    if (!isRequestAuthenticated(req)) {
      client.close(4401, 'Unauthorized');
      return;
    }
    browserClients.add(client);
    lastClientPingAt = Date.now();
    client.send(JSON.stringify({ type: 'daemon_status', connected: daemonConnected }));
    client.send(
      JSON.stringify({ type: 'session_state', state: sessionCoordinator.getState() })
    );
    client.send(JSON.stringify(latestTelemetry));

    client.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw.toString()) as Record<string, unknown>;
        const type = String(msg.type || '');

        if (type === 'ping') {
          lastClientPingAt = Date.now();
          sendToDaemon({ type: 'ping' });
          client.send(JSON.stringify({ type: 'pong' }));
          return;
        }

        if (DAEMON_FORWARD_TYPES.has(type)) {
          if (type === 'set_weight') {
            if (latestTelemetry.weight_enabled) {
              client.send(
                JSON.stringify({
                  type: 'error',
                  message: 'Cannot change weight from remote WebSocket while resistance is engaged',
                })
              );
              return;
            }
            const w = Number(msg.weight_lb);
            if (!Number.isFinite(w) || w < 5 || w > 200) {
              client.send(JSON.stringify({ type: 'error', message: `Invalid weight_lb: ${msg.weight_lb}` }));
              return;
            }
            if (msg.profile !== undefined) {
              const p = String(msg.profile).trim().toUpperCase();
              if (!['STANDARD', 'ECCENTRIC', 'CHAINS', 'SPOTTER', 'BURNOUT'].includes(p)) {
                client.send(JSON.stringify({ type: 'error', message: `Invalid weight profile: ${msg.profile}` }));
                return;
              }
              msg.profile = p.toLowerCase();
            }
            if (msg.modifier_pct !== undefined) {
              const mod = Number(msg.modifier_pct);
              msg.modifier_pct = Number.isFinite(mod) ? Math.max(0, Math.min(100, Math.round(mod))) : 0;
            }
          }
          if (
            (type === 'enable_weight' || (type === 'toggle_weight' && !latestTelemetry.weight_enabled)) &&
            latestTelemetry.safety_event
          ) {
            client.send(
              JSON.stringify({
                type: 'error',
                message: `Cannot engage weight during active safety event: ${latestTelemetry.safety_event}`,
              })
            );
            return;
          }
          const sent = sendToDaemon(msg);
          if (!sent && type === 'set_weight') {
            latestTelemetry = {
              ...latestTelemetry,
              target_weight_lb: Number(msg.weight_lb ?? latestTelemetry.target_weight_lb),
              profile_mode: String(msg.profile ?? latestTelemetry.profile_mode),
              modifier_pct: Number(msg.modifier_pct ?? 0),
            };
            broadcastToClients(latestTelemetry);
          } else if (!sent && type === 'set_accessory') {
            latestTelemetry = {
              ...latestTelemetry,
              accessory: String(msg.accessory ?? latestTelemetry.accessory),
            };
            broadcastToClients(latestTelemetry);
          } else if (!sent && (type === 'toggle_weight' || type === 'enable_weight' || type === 'disable_weight')) {
            const nextEnabled =
              type === 'enable_weight'
                ? true
                : type === 'disable_weight'
                  ? false
                  : !Boolean(latestTelemetry.weight_enabled);
            latestTelemetry = {
              ...latestTelemetry,
              weight_enabled: nextEnabled,
              applied_resistance_lb: nextEnabled ? Number(latestTelemetry.target_weight_lb ?? 45) : 0,
            };
            broadcastToClients(latestTelemetry);
          }
          return;
        }

        if (type === 'set_active_user' && typeof msg.userId === 'string') {
          sessionCoordinator.setActiveUser(msg.userId);
        } else if (type === 'set_partner_mode') {
          sessionCoordinator.setPartnerMode(
            Boolean(msg.enabled),
            Array.isArray(msg.partnerUserIds) ? (msg.partnerUserIds as string[]) : undefined
          );
        } else if (type === 'skip_rest_timer') {
          sessionCoordinator.setRestTimer(null);
        } else if (type === 'start_rest_timer') {
          const sec = Number(msg.seconds ?? 60);
          sessionCoordinator.setRestTimer(Date.now() + sec * 1000);
        } else if (type === 'clear_queued_workout') {
          void clearPendingQueuedWorkout();
        } else if (type === 'music_updated') {
          broadcastToClients(msg);
        }
      } catch {
        // Ignore invalid WS messages
      }
    });

    client.on('close', () => {
      browserClients.delete(client);
      if (
        process.env.SWONAL_LEGACY_WS_WATCHDOG === '1' &&
        browserClients.size === 0 &&
        tonalSyncClients.size === 0 &&
        latestTelemetry.weight_enabled
      ) {
        console.warn('[SwonalServer] Legacy WS watchdog: last client disconnected while weight engaged — disabling resistance.');
        sendToDaemon({ type: 'disable_weight' });
        fetch(`${daemonHttpUrl}/api/motor/disable`, { method: 'POST' }).catch(() => {});
      }
    });
  });

  syncWss.on('connection', (client, req) => {
    if (!isRequestAuthenticated(req)) {
      client.close(4401, 'Unauthorized');
      return;
    }
    tonalSyncClients.add(client);
    client.send(JSON.stringify({ type: 'sync_hello', serverTimeMs: Date.now() }));
    void getPendingQueuedWorkout()
      .then((pending) => {
        if (pending && client.readyState === WebSocket.OPEN) {
          client.send(JSON.stringify({ type: 'workout_queued', workout: pending }));
        }
      })
      .catch(() => {});

    client.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw.toString()) as Record<string, unknown>;
        const type = String(msg.type || '');
        if (type === 'ping') {
          client.send(JSON.stringify({ type: 'pong', serverTimeMs: Date.now() }));
        } else if (type === 'telemetry') {
          latestTelemetry = msg;
          broadcastToClients(msg);
          publishHaState();
        } else if (type === 'session_event' && typeof msg.eventType === 'string') {
          void homeAssistantBus.publishSessionEvent(
            msg.eventType,
            (typeof msg.payload === 'object' && msg.payload !== null ? msg.payload : {}) as Record<string, unknown>
          );
        } else if (type === 'ack_queued_workout') {
          const queuedAt = typeof msg.queuedAt === 'string' ? msg.queuedAt : undefined;
          void clearPendingQueuedWorkout(queuedAt);
        }
      } catch {
        // Ignore invalid /ws/sync messages
      }
    });

    client.on('close', () => {
      tonalSyncClients.delete(client);
    });
  });

  // REST Routes
  app.use('/api/auth', createAuthRouter());

  // Protect API endpoints when app passcode is enabled (allows health & OAuth callback)
  app.use('/api', (req, res, next) => {
    if (req.path === '/health' || req.path === '/spotify/callback') {
      next();
      return;
    }
    if (!isRequestAuthenticated(req)) {
      res.status(401).json({ error: 'Authentication required' });
      return;
    }
    next();
  });

  app.use(
    '/api/sync',
    createSyncRouter({
      broadcastToClients,
      notifyTonalSyncPoke: () => broadcastToSyncClients({ type: 'sync_poke' }),
      isTonalSyncConnected: () => tonalSyncClients.size > 0,
    })
  );
  app.use(
    '/api',
    createApiRouter({
      sendToDaemon,
      broadcastToClients,
      isDaemonConnected: () => daemonConnected,
    })
  );
  app.use('/api/music', createMusicRouter(broadcastToClients));
  app.use('/api/spotify', createSpotifyRouter(broadcastToClients));
  app.use('/api/daemon', createDaemonRouter(daemonHttpUrl));
  app.use('/api/friends', createFriendsRouter());

  // Built-in MCP SSE Transport
  const sseTransports = new Map<
    string,
    { transport: SSEServerTransport; server: ReturnType<typeof createSwonalMcpServer> }
  >();

  app.get('/mcp/sse', async (req, res) => {
    if (!isRequestAuthenticated(req, { allowQueryToken: true })) {
      res.status(401).json({ error: 'Authentication required' });
      return;
    }
    const transport = new SSEServerTransport('/mcp/messages', res);
    const mcp = createSwonalMcpServer();
    sseTransports.set(transport.sessionId, { transport, server: mcp });

    res.on('close', () => {
      sseTransports.delete(transport.sessionId);
      void mcp.close().catch(() => {});
    });

    await mcp.connect(transport);
  });

  app.post('/mcp/messages', async (req, res) => {
    if (!isRequestAuthenticated(req, { allowQueryToken: true })) {
      res.status(401).json({ error: 'Authentication required' });
      return;
    }
    const sessionId = String(req.query.sessionId || '');
    const entry = sseTransports.get(sessionId);
    if (!entry) {
      res.status(404).json({ error: `Unknown MCP SSE sessionId: ${sessionId}` });
      return;
    }
    await entry.transport.handlePostMessage(req, res, req.body);
  });

  // Serve built Vite portrait kiosk & companion UI when present
  const clientDistDir = path.resolve(__dirname, '../../dist/client');
  if (fs.existsSync(clientDistDir)) {
    app.use(express.static(clientDistDir));
    app.get(
      [
        '/',
        '/companion',
        '/companion/*',
        '/settings',
        '/settings/*',
        '/admin',
        '/admin/*',
        '/history',
        '/history/*',
      ],
      (_req, res) => {
        res.sendFile(path.join(clientDistDir, 'index.html'));
      }
    );
  } else {
    app.get(['/', '/companion'], (_req, res) => {
      res
        .status(200)
        .send(
          '<h1>swonal-ui API Server Running</h1><p>Frontend not built yet. Run <code>npm run build:client</code> or Vite dev server.</p>'
        );
    });
  }

  // Error handler
  app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
    if (err instanceof ZodError) {
      res.status(400).json({ error: 'Validation error', details: err.flatten() });
      return;
    }
    const message = err instanceof Error ? err.message : 'Internal server error';
    res.status(500).json({ error: message });
  });

  const targetPort =
    options.port !== undefined ? options.port : Number(process.env.PORT || 3000);

  await new Promise<void>((resolve) => {
    httpServer.listen(targetPort, '0.0.0.0', () => resolve());
  });

  const addr = httpServer.address();
  const boundPort = typeof addr === 'object' && addr ? addr.port : targetPort;

  return {
    app,
    server: httpServer,
    port: boundPort,
    close: async () => {
      closing = true;
      sessionCoordinator.off('state_changed', onStateChanged);
      sessionCoordinator.off('workout_queued', onWorkoutQueued);
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      if (daemonWs) {
        daemonWs.terminate();
        daemonWs = null;
      }
      for (const { transport } of sseTransports.values()) {
        await transport.close().catch(() => {});
      }
      sseTransports.clear();
      for (const client of browserClients) {
        client.terminate();
      }
      browserClients.clear();
      for (const client of tonalSyncClients) {
        client.terminate();
      }
      tonalSyncClients.clear();
      stopBackupLoop();
      await new Promise<void>((resolve) => syncWss.close(() => resolve()));
      await new Promise<void>((resolve) => wss.close(() => resolve()));
      await new Promise<void>((resolve) => httpServer.close(() => resolve()));
      await homeAssistantBus.close();
    },
  };
}

if (require.main === module) {
  createSwonalServer()
    .then(({ port }) => {
      console.log(`swonal-ui server-app listening on http://0.0.0.0:${port}`);
    })
    .catch((err) => {
      console.error('Failed to start swonal-ui server:', err);
      process.exit(1);
    });
}

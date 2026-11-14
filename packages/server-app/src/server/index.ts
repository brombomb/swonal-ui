import 'dotenv/config';
import cors from 'cors';
import express, { NextFunction, Request, Response } from 'express';
import fs from 'fs';
import http from 'http';
import path from 'path';
import WebSocket, { WebSocketServer } from 'ws';
import { ZodError } from 'zod';
import { SSEServerTransport } from '@modelcontextprotocol/sdk/server/sse.js';
import { prisma } from './db';
import { seedDatabase } from '../../prisma/seed';
import { createSwonalMcpServer } from './mcp/mcpServer';
import { musicRegistry } from './music/Registry';
import { createApiRouter } from './routes/apiRoutes';
import { createDaemonRouter } from './routes/daemonRoutes';
import { createMusicRouter } from './routes/musicRoutes';
import { sessionCoordinator } from './services/sessionState';

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
  // Auto-seed fresh SQLite databases cleanly (essential for first Docker boot)
  const existingUsers = await prisma.user.count();
  if (existingUsers === 0) {
    await seedDatabase(prisma);
  }

  const daemonHttpUrl =
    options.daemonHttpUrl ?? process.env.TONAL_DAEMON_URL ?? 'http://localhost:8765';
  const daemonWsUrl =
    options.daemonWsUrl ?? process.env.TONAL_DAEMON_WS_URL ?? 'ws://localhost:8765/ws';
  const connectDaemonWs = options.connectDaemonWs ?? true;

  const app = express();
  app.use(cors());
  app.use(express.json({ limit: '2mb' }));

  const httpServer = http.createServer(app);
  const wss = new WebSocketServer({ server: httpServer, path: '/ws' });
  const browserClients = new Set<WebSocket>();

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

  const broadcastToClients = (payload: Record<string, unknown>) => {
    const serialized = JSON.stringify(payload);
    for (const client of browserClients) {
      if (client.readyState === WebSocket.OPEN) {
        client.send(serialized);
      }
    }
  };

  const sendToDaemon = (msg: Record<string, unknown>): boolean => {
    if (daemonWs && daemonWs.readyState === WebSocket.OPEN) {
      daemonWs.send(JSON.stringify(msg));
      return true;
    }
    return false;
  };

  const onStateChanged = (state: ReturnType<typeof sessionCoordinator.getState>) => {
    broadcastToClients({ type: 'session_state', state });
  };
  const onWorkoutQueued = (payload: unknown) => {
    broadcastToClients({ type: 'workout_queued', workout: payload });
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

  if (connectDaemonWs) {
    connectToDaemon();
    heartbeatTimer = setInterval(() => {
      if (daemonWs && daemonWs.readyState === WebSocket.OPEN) {
        if (browserClients.size > 0 || latestTelemetry.weight_enabled) {
          daemonWs.send(JSON.stringify({ type: 'ping' }));
        }
      }
    }, 2000);
  }

  wss.on('connection', (client) => {
    browserClients.add(client);
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
          sendToDaemon({ type: 'ping' });
          client.send(JSON.stringify({ type: 'pong' }));
          return;
        }

        if (DAEMON_FORWARD_TYPES.has(type)) {
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
          sessionCoordinator.clearQueuedWorkout();
        } else if (type === 'music_updated') {
          broadcastToClients(msg);
        }
      } catch {
        // Ignore invalid WS messages
      }
    });

    client.on('close', () => {
      browserClients.delete(client);
    });
  });

  // REST Routes
  app.use(
    '/api',
    createApiRouter({
      sendToDaemon,
      broadcastToClients,
      isDaemonConnected: () => daemonConnected,
    })
  );
  app.use('/api/music', createMusicRouter(broadcastToClients));
  app.use('/api/daemon', createDaemonRouter(daemonHttpUrl));

  // Built-in MCP SSE Transport
  const sseTransports = new Map<
    string,
    { transport: SSEServerTransport; server: ReturnType<typeof createSwonalMcpServer> }
  >();

  app.get('/mcp/sse', async (_req, res) => {
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
    app.get(['/', '/companion', '/companion/*'], (_req, res) => {
      res.sendFile(path.join(clientDistDir, 'index.html'));
    });
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
      await new Promise<void>((resolve) => wss.close(() => resolve()));
      await new Promise<void>((resolve) => httpServer.close(() => resolve()));
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

import { Router } from 'express';

export function createDaemonRouter(daemonHttpUrl: string): Router {
  const router = Router();

  const forward = async (path: string, method: 'GET' | 'POST', body?: unknown) => {
    const resp = await fetch(`${daemonHttpUrl}${path}`, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await resp.text();
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = { raw: text };
    }
    return { status: resp.status, data: parsed };
  };

  router.get('/health', async (_req, res) => {
    try {
      const out = await forward('/health', 'GET');
      res.status(out.status).json(out.data);
    } catch (err) {
      res.status(503).json({
        status: 'offline',
        detail: err instanceof Error ? err.message : 'Daemon unreachable',
      });
    }
  });

  router.post('/mode/stock', async (_req, res) => {
    try {
      const out = await forward('/api/mode/stock', 'POST');
      res.status(out.status).json(out.data);
    } catch (err) {
      res.status(502).json({ error: err instanceof Error ? err.message : 'Daemon unreachable' });
    }
  });

  router.post('/mode/custom', async (_req, res) => {
    try {
      const out = await forward('/api/mode/custom', 'POST');
      res.status(out.status).json(out.data);
    } catch (err) {
      res.status(502).json({ error: err instanceof Error ? err.message : 'Daemon unreachable' });
    }
  });

  router.post('/mock/ble-event', async (req, res) => {
    try {
      const out = await forward('/api/mock/ble-event', 'POST', req.body);
      res.status(out.status).json(out.data);
    } catch (err) {
      res.status(502).json({ error: err instanceof Error ? err.message : 'Daemon unreachable' });
    }
  });

  router.post('/mock/bar-tilt', async (req, res) => {
    try {
      const out = await forward('/api/mock/bar-tilt', 'POST', req.body);
      res.status(out.status).json(out.data);
    } catch (err) {
      res.status(502).json({ error: err instanceof Error ? err.message : 'Daemon unreachable' });
    }
  });

  router.post('/mock/arm-positions', async (req, res) => {
    try {
      const out = await forward('/api/mock/arm-positions', 'POST', req.body);
      res.status(out.status).json(out.data);
    } catch (err) {
      res.status(502).json({ error: err instanceof Error ? err.message : 'Daemon unreachable' });
    }
  });

  return router;
}

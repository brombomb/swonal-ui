import { NextFunction, Request, Response, Router } from 'express';
import { musicRegistry } from '../music/Registry';
import { sessionCoordinator } from '../services/sessionState';

export function createMusicRouter(broadcastToClients: (msg: Record<string, unknown>) => void): Router {
  const router = Router();

  const resolveUserId = (req: { query: Record<string, unknown>; body?: Record<string, unknown> }) => {
    if (typeof req.query.userId === 'string' && req.query.userId) return req.query.userId;
    if (typeof req.body?.userId === 'string' && req.body.userId) return req.body.userId;
    return sessionCoordinator.getState().activeUserId;
  };

  router.get('/providers', (_req, res) => {
    res.json(musicRegistry.list());
  });

  router.get('/:provider/auth', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      const url = await provider.getAuthUrl(userId);
      res.json({ url });
    } catch (err) {
      next(err);
    }
  });

  router.get('/:provider/callback', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const code = String(req.query.code || '');
      const userId = String(req.query.state || sessionCoordinator.getState().activeUserId);
      await provider.handleAuthCallback(userId, code);
      broadcastToClients({ type: 'music_updated', userId, provider: provider.id });
      res.redirect('/?music_connected=1');
    } catch (err) {
      next(err);
    }
  });

  router.get('/:provider/state', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      const state = await provider.getPlaybackState(userId);
      res.json(state);
    } catch (err) {
      next(err);
    }
  });

  router.post('/:provider/play', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      await provider.play(
        userId,
        req.body?.contextUri,
        req.body?.deviceId,
        req.body?.fallbackDeviceId
      );
      const state = await provider.getPlaybackState(userId);
      broadcastToClients({ type: 'music_updated', userId, provider: provider.id });
      res.json(state);
    } catch (err) {
      next(err);
    }
  });

  router.post('/:provider/pause', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      await provider.pause(userId);
      const state = await provider.getPlaybackState(userId);
      broadcastToClients({ type: 'music_updated', userId, provider: provider.id });
      res.json(state);
    } catch (err) {
      next(err);
    }
  });

  router.post('/:provider/next', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      await provider.nextTrack(userId);
      const state = await provider.getPlaybackState(userId);
      broadcastToClients({ type: 'music_updated', userId, provider: provider.id });
      res.json(state);
    } catch (err) {
      next(err);
    }
  });

  router.post('/:provider/previous', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      await provider.previousTrack(userId);
      const state = await provider.getPlaybackState(userId);
      broadcastToClients({ type: 'music_updated', userId, provider: provider.id });
      res.json(state);
    } catch (err) {
      next(err);
    }
  });

  router.post('/:provider/volume', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      const volumePct = Number(req.body?.volumePct ?? 70);
      await provider.setVolume(userId, volumePct);
      const state = await provider.getPlaybackState(userId);
      broadcastToClients({ type: 'music_updated', userId, provider: provider.id });
      res.json(state);
    } catch (err) {
      next(err);
    }
  });

  router.post('/:provider/duck', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      const targetPct = Number(req.body?.targetPct ?? 25);
      const durationMs = Number(req.body?.durationMs ?? 1200);
      await provider.duckVolume(userId, targetPct, durationMs);
      const state = await provider.getPlaybackState(userId);
      broadcastToClients({ type: 'music_updated', userId, provider: provider.id });
      res.json(state);
    } catch (err) {
      next(err);
    }
  });

  router.get('/:provider/playlists', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      const playlists = await provider.getPlaylists(userId);
      res.json(playlists);
    } catch (err) {
      next(err);
    }
  });

  router.get('/:provider/playlists/:playlistId/tracks', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      if (typeof provider.getPlaylistTracks !== 'function') {
        res.json([]);
        return;
      }
      const tracks = await provider.getPlaylistTracks(userId, req.params.playlistId);
      res.json(tracks);
    } catch (err) {
      next(err);
    }
  });

  router.get('/:provider/devices', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      const devices = await provider.getDevices(userId);
      res.json(devices);
    } catch (err) {
      next(err);
    }
  });

  router.post('/:provider/transfer', async (req, res, next) => {
    try {
      const provider = musicRegistry.get(req.params.provider);
      const userId = resolveUserId(req);
      const deviceId = String(req.body?.deviceId || '');
      if (!deviceId) {
        res.status(400).json({ error: 'deviceId is required' });
        return;
      }
      await provider.transferPlayback(userId, deviceId);
      const state = await provider.getPlaybackState(userId);
      broadcastToClients({ type: 'music_updated', userId, provider: provider.id });
      res.json(state);
    } catch (err) {
      next(err);
    }
  });

  router.use((err: unknown, _req: Request, res: Response, next: NextFunction) => {
    const message = err instanceof Error ? err.message : String(err);
    if (message.includes('must be connected')) {
      res.status(401).json({ error: message });
      return;
    }
    if (message.includes('NO_ACTIVE_DEVICE') || message.includes('(404)')) {
      res.status(404).json({ error: message });
      return;
    }
    if (message.includes('PREMIUM_REQUIRED') || message.includes('(403)')) {
      res.status(403).json({ error: message });
      return;
    }
    if (message.includes('Spotify API error')) {
      res.status(502).json({ error: message });
      return;
    }
    next(err);
  });

  return router;
}

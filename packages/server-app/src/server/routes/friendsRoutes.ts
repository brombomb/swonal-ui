import { Router, Request, Response, NextFunction } from 'express';
import {
  getOrCreateUserIdentity,
  listFriends,
  addFriend,
  removeFriend,
  updateUserFriendsSettings,
  getFeed,
  syncFeed,
  publishRoutine,
  importRoutine,
  publishReaction,
} from '../services/nostrFriendsService';

function paramStr(val: string | string[] | undefined): string {
  if (Array.isArray(val)) return val[0] || '';
  return String(val || '');
}

export function createFriendsRouter(): Router {
  const router = Router();

  // Get self-sovereign Nostr public identity
  router.get('/identity/:userId', async (req: Request, res: Response, next: NextFunction) => {
    try {
      const userId = paramStr(req.params.userId);
      const identity = await getOrCreateUserIdentity(userId);
      res.json(identity);
    } catch (err) {
      next(err);
    }
  });

  // Update Nostr friend settings (relays, auto-share workouts)
  router.post('/identity/:userId', async (req: Request, res: Response, next: NextFunction) => {
    try {
      const userId = paramStr(req.params.userId);
      const { shareWorkouts, relays } = req.body;
      const updated = await updateUserFriendsSettings(userId, {
        shareWorkouts: typeof shareWorkouts === 'boolean' ? shareWorkouts : undefined,
        relays: Array.isArray(relays) ? relays : undefined,
      });
      res.json(updated);
    } catch (err) {
      next(err);
    }
  });

  // List friends
  router.get('/:userId', async (req: Request, res: Response, next: NextFunction) => {
    try {
      const userId = paramStr(req.params.userId);
      const friends = await listFriends(userId);
      res.json(friends);
    } catch (err) {
      next(err);
    }
  });

  // Add a friend by npub or hex
  router.post('/:userId', async (req: Request, res: Response, next: NextFunction) => {
    try {
      const userId = paramStr(req.params.userId);
      const { npubOrHex, displayName } = req.body;
      if (!npubOrHex || typeof npubOrHex !== 'string') {
        res.status(400).json({ error: 'npubOrHex string is required' });
        return;
      }
      const friend = await addFriend(userId, npubOrHex, displayName);
      res.json(friend);
    } catch (err: any) {
      res.status(400).json({ error: err.message || 'Failed to add friend' });
    }
  });

  // Remove a friend
  router.delete('/:userId/:friendId', async (req: Request, res: Response, next: NextFunction) => {
    try {
      const userId = paramStr(req.params.userId);
      const friendId = paramStr(req.params.friendId);
      await removeFriend(userId, friendId);
      res.json({ success: true });
    } catch (err) {
      next(err);
    }
  });

  // Get social feed
  router.get('/feed/:userId', async (req: Request, res: Response, next: NextFunction) => {
    try {
      const userId = paramStr(req.params.userId);
      const limit = Number(req.query.limit || 50);
      const feed = await getFeed(userId, limit);
      res.json(feed);
    } catch (err) {
      next(err);
    }
  });

  // Trigger feed sync
  router.post('/sync/:userId', async (req: Request, res: Response, next: NextFunction) => {
    try {
      const userId = paramStr(req.params.userId);
      const result = await syncFeed(userId);
      res.json(result);
    } catch (err) {
      next(err);
    }
  });

  // Share routine template to Nostr
  router.post('/share-routine/:userId/:templateId', async (req: Request, res: Response, next: NextFunction) => {
    try {
      const userId = paramStr(req.params.userId);
      const templateId = paramStr(req.params.templateId);
      const event = await publishRoutine(userId, templateId);
      res.json({ success: true, eventId: event.id });
    } catch (err: any) {
      res.status(400).json({ error: err.message || 'Failed to share routine' });
    }
  });

  // Import shared routine from feed
  router.post('/import-routine/:userId/:eventId', async (req: Request, res: Response, next: NextFunction) => {
    try {
      const userId = paramStr(req.params.userId);
      const eventId = paramStr(req.params.eventId);
      const template = await importRoutine(userId, eventId);
      res.json(template);
    } catch (err: any) {
      res.status(400).json({ error: err.message || 'Failed to import routine' });
    }
  });

  // React to a workout or activity
  router.post('/react/:userId', async (req: Request, res: Response, next: NextFunction) => {
    try {
      const userId = paramStr(req.params.userId);
      const { targetEventId, targetPubkey, emoji } = req.body;
      if (!targetEventId || !targetPubkey) {
        res.status(400).json({ error: 'targetEventId and targetPubkey are required' });
        return;
      }
      const event = await publishReaction(
        userId,
        String(targetEventId),
        String(targetPubkey),
        String(emoji || '💪')
      );
      res.json({ success: true, eventId: event.id });
    } catch (err: any) {
      res.status(400).json({ error: err.message || 'Failed to publish reaction' });
    }
  });

  return router;
}

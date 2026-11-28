import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { generateSecretKey, getPublicKey, nip19, verifyEvent } from 'nostr-tools';
import { DEFAULT_USERS } from '../prisma/seed';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import {
  normalizePubkey,
  getOrCreateUserIdentity,
  addFriend,
  listFriends,
  removeFriend,
  publishWorkout,
  publishRoutine,
  importRoutine,
  publishReaction,
  getFeed,
  closePool,
  DEFAULT_RELAYS,
} from '../src/server/services/nostrFriendsService';

describe('Decentralized Peer-to-Peer Friends System (Nostr Protocol)', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  let testFriendPubkeyHex: string;
  let testFriendNpub: string;
  let importedTemplateId: string | null = null;

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;

    // Generate a mock friend's Nostr identity
    const friendSk = generateSecretKey();
    testFriendPubkeyHex = getPublicKey(friendSk);
    testFriendNpub = nip19.npubEncode(testFriendPubkeyHex);
  });

  afterAll(async () => {
    closePool();
    if (importedTemplateId) {
      await prisma.workoutTemplate.delete({ where: { id: importedTemplateId } }).catch(() => {});
    }
    // Clean up friend activity & friend relations created during test
    await prisma.friend.deleteMany({
      where: { userId: DEFAULT_USERS.USER_ONE_ID },
    }).catch(() => {});
    await prisma.friendActivity.deleteMany({
      where: { friendPubkeyHex: testFriendPubkeyHex },
    }).catch(() => {});
    await srv.close();
    await prisma.$disconnect();
  });

  describe('Key & Identity Management', () => {
    it('normalizes npub and 64-character hex strings correctly', () => {
      const normalizedFromNpub = normalizePubkey(testFriendNpub);
      expect(normalizedFromNpub.pubkeyHex).toBe(testFriendPubkeyHex.toLowerCase());
      expect(normalizedFromNpub.npub).toBe(testFriendNpub);

      const normalizedFromHex = normalizePubkey(testFriendPubkeyHex);
      expect(normalizedFromHex.pubkeyHex).toBe(testFriendPubkeyHex.toLowerCase());
      expect(normalizedFromHex.npub).toBe(testFriendNpub);

      expect(() => normalizePubkey('invalid-key')).toThrow();
    });

    it('generates self-sovereign secp256k1 identity on demand and protects private keys', async () => {
      const identity = await getOrCreateUserIdentity(DEFAULT_USERS.USER_ONE_ID);
      expect(identity.userId).toBe(DEFAULT_USERS.USER_ONE_ID);
      expect(identity.npub).toMatch(/^npub1[0-9a-z]+$/);
      expect(identity.pubkeyHex).toMatch(/^[0-9a-f]{64}$/);
      expect(identity.shareWorkouts).toBe(true);
      expect(identity.relays.length).toBeGreaterThan(0);
      expect((identity as any).nostrPrivKeyHex).toBeUndefined();

      // Subsequent retrieval yields identical pubkey
      const cached = await getOrCreateUserIdentity(DEFAULT_USERS.USER_ONE_ID);
      expect(cached.pubkeyHex).toBe(identity.pubkeyHex);
    });
  });

  describe('Relays saved before the identity exists', () => {
    const PRIVATE_RELAYS = ['ws://127.0.0.1:7777'];
    const createdUserIds: string[] = [];

    async function createFreshUser(name: string): Promise<string> {
      const user = await prisma.user.create({ data: { name } });
      createdUserIds.push(user.id);
      return user.id;
    }

    afterAll(async () => {
      await prisma.user.deleteMany({ where: { id: { in: createdUserIds } } }).catch(() => {});
    });

    it('keeps relays posted before the first identity read (HTTP)', async () => {
      const userId = await createFreshUser('Relay First');

      const postRes = await fetch(`${baseUrl}/api/friends/identity/${userId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ relays: PRIVATE_RELAYS }),
      });
      expect(postRes.status).toBe(200);

      const getRes = await fetch(`${baseUrl}/api/friends/identity/${userId}`);
      expect(getRes.status).toBe(200);
      const identity = await getRes.json();
      expect(identity.npub).toMatch(/^npub1[0-9a-z]+$/);
      expect(identity.relays).toEqual(PRIVATE_RELAYS);

      const stored = await prisma.user.findUnique({ where: { id: userId }, select: { nostrRelays: true } });
      expect(JSON.parse(stored!.nostrRelays!)).toEqual(PRIVATE_RELAYS);
    });

    it('keeps saved relays when publishing a workout creates the identity', async () => {
      const userId = await createFreshUser('Relay First Share');
      await prisma.user.update({
        where: { id: userId },
        data: { nostrRelays: JSON.stringify(PRIVATE_RELAYS) },
      });

      // publishWorkout goes through getUserSecretKey, which creates the identity on demand
      const event = await publishWorkout(userId, {
        sessionId: 'relay-first-session',
        routineName: 'Relay Test Routine',
        totalVolumeLb: 1000,
        durationSec: 600,
      });
      expect(event).not.toBeNull();

      const stored = await prisma.user.findUnique({ where: { id: userId }, select: { nostrRelays: true } });
      expect(JSON.parse(stored!.nostrRelays!)).toEqual(PRIVATE_RELAYS);
    });

    it('still gives a user with no saved relays the defaults', async () => {
      const userId = await createFreshUser('No Relays');
      const identity = await getOrCreateUserIdentity(userId);
      expect(identity.relays).toEqual(DEFAULT_RELAYS);

      const stored = await prisma.user.findUnique({ where: { id: userId }, select: { nostrRelays: true } });
      expect(JSON.parse(stored!.nostrRelays!)).toEqual(DEFAULT_RELAYS);
    });

    it('leaves an existing identity and its relays unchanged', async () => {
      const userId = await createFreshUser('Existing Identity');
      const first = await getOrCreateUserIdentity(userId);
      await prisma.user.update({
        where: { id: userId },
        data: { nostrRelays: JSON.stringify(PRIVATE_RELAYS) },
      });

      const again = await getOrCreateUserIdentity(userId);
      expect(again.pubkeyHex).toBe(first.pubkeyHex);
      expect(again.npub).toBe(first.npub);
      expect(again.relays).toEqual(PRIVATE_RELAYS);
    });
  });

  describe('Friend Management', () => {
    let createdFriendId: string;

    it('adds a friend via npub and prevents self-friending', async () => {
      const myIdentity = await getOrCreateUserIdentity(DEFAULT_USERS.USER_ONE_ID);
      await expect(addFriend(DEFAULT_USERS.USER_ONE_ID, myIdentity.npub)).rejects.toThrow(
        /Cannot add yourself/i
      );

      const friend = await addFriend(
        DEFAULT_USERS.USER_ONE_ID,
        testFriendNpub,
        'Sarah Connor'
      );
      expect(friend.displayName).toBe('Sarah Connor');
      expect(friend.pubkeyHex).toBe(testFriendPubkeyHex.toLowerCase());
      expect(friend.npub).toBe(testFriendNpub);
      createdFriendId = friend.id;

      const friendsList = await listFriends(DEFAULT_USERS.USER_ONE_ID);
      expect(friendsList.some((f) => f.id === createdFriendId)).toBe(true);
    });

    it('removes a friend cleanly', async () => {
      await removeFriend(DEFAULT_USERS.USER_ONE_ID, createdFriendId);
      const friendsList = await listFriends(DEFAULT_USERS.USER_ONE_ID);
      expect(friendsList.some((f) => f.id === createdFriendId)).toBe(false);

      // Re-add for following feed tests
      await addFriend(DEFAULT_USERS.USER_ONE_ID, testFriendNpub, 'Sarah Connor');
    });
  });

  describe('Event Signing & Feed Verification', () => {
    it('signs and verifies workout completion events (kind 30101)', async () => {
      const event = await publishWorkout(DEFAULT_USERS.USER_ONE_ID, {
        sessionId: 'test-session-123',
        routineName: 'Chest Hypertrophy',
        totalVolumeLb: 18500,
        durationSec: 2400,
        prs: ['Bench Press'],
        setLogs: [
          {
            exercise: { name: 'Bench Press' },
            weightLb: 225,
            repsCompleted: 8,
            volumeLb: 1800,
          },
        ],
      });

      expect(event).not.toBeNull();
      if (!event) return;

      expect(event.kind).toBe(30101);
      expect(verifyEvent(event)).toBe(true);

      const parsed = JSON.parse(event.content);
      expect(parsed.routineName).toBe('Chest Hypertrophy');
      expect(parsed.totalVolumeLb).toBe(18500);
      expect(parsed.prs).toContain('Bench Press');
    });

    it('publishes and imports routine templates (kind 30102)', async () => {
      // Create a test routine template
      const template = await prisma.workoutTemplate.create({
        data: {
          userId: DEFAULT_USERS.USER_ONE_ID,
          name: 'P2P Test Routine',
          description: 'A great upper routine',
          blocksJson: JSON.stringify([{ name: 'Warmup' }]),
        },
      });

      const event = await publishRoutine(DEFAULT_USERS.USER_ONE_ID, template.id);
      expect(event.kind).toBe(30102);
      expect(verifyEvent(event)).toBe(true);

      // Import the routine
      const imported = await importRoutine(DEFAULT_USERS.USER_ONE_ID, event.id);
      expect(imported.name).toContain('P2P Test Routine');
      importedTemplateId = imported.id;

      // Clean up original template
      await prisma.workoutTemplate.delete({ where: { id: template.id } });
    });

    it('publishes reaction events (kind 7) and aggregates in getFeed', async () => {
      const workoutEvent = await publishWorkout(DEFAULT_USERS.USER_ONE_ID, {
        sessionId: 'test-session-react',
        routineName: 'Leg Day Annihilation',
        totalVolumeLb: 22000,
        durationSec: 3000,
      });
      expect(workoutEvent).not.toBeNull();
      if (!workoutEvent) return;

      const reaction = await publishReaction(
        DEFAULT_USERS.USER_ONE_ID,
        workoutEvent.id,
        workoutEvent.pubkey,
        '🔥'
      );
      expect(reaction.kind).toBe(7);
      expect(verifyEvent(reaction)).toBe(true);

      const feed = await getFeed(DEFAULT_USERS.USER_ONE_ID);
      const post = feed.find((item) => item.id === workoutEvent.id);
      expect(post).toBeDefined();
      expect(post?.reactions.some((r) => r.emoji === '🔥')).toBe(true);
    });
  });

  describe('REST API Endpoints (/api/friends)', () => {
    it('GET /api/friends/identity/:userId returns self-sovereign identity', async () => {
      const res = await fetch(`${baseUrl}/api/friends/identity/${DEFAULT_USERS.USER_ONE_ID}`);
      expect(res.status).toBe(200);
      const body = (await res.json()) as any;
      expect(body.npub).toMatch(/^npub1/);
      expect(body.shareWorkouts).toBe(true);
      expect(Array.isArray(body.relays)).toBe(true);
      expect(body.nostrPrivKeyHex).toBeUndefined();
    });

    it('POST /api/friends/identity/:userId updates relay and share preferences', async () => {
      const res = await fetch(`${baseUrl}/api/friends/identity/${DEFAULT_USERS.USER_ONE_ID}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          shareWorkouts: false,
          relays: ['wss://relay.damus.io', 'wss://nos.lol'],
        }),
      });
      expect(res.status).toBe(200);
      const updated = (await res.json()) as any;
      expect(updated.shareWorkouts).toBe(false);

      // Re-enable
      await fetch(`${baseUrl}/api/friends/identity/${DEFAULT_USERS.USER_ONE_ID}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ shareWorkouts: true }),
      });
    });

    it('GET /api/friends/:userId and POST /api/friends/:userId manage friends over HTTP', async () => {
      // Add a friend
      const postRes = await fetch(`${baseUrl}/api/friends/${DEFAULT_USERS.USER_ONE_ID}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          npubOrHex: testFriendNpub,
          displayName: 'Coach John',
        }),
      });
      expect(postRes.status).toBe(200);
      const friendData = (await postRes.json()) as any;
      expect(friendData.displayName).toBe('Coach John');

      // List friends
      const listRes = await fetch(`${baseUrl}/api/friends/${DEFAULT_USERS.USER_ONE_ID}`);
      expect(listRes.status).toBe(200);
      const listData = (await listRes.json()) as any[];
      expect(listData.some((f) => f.id === friendData.id)).toBe(true);

      // Delete friend
      const delRes = await fetch(
        `${baseUrl}/api/friends/${DEFAULT_USERS.USER_ONE_ID}/${friendData.id}`,
        { method: 'DELETE' }
      );
      expect(delRes.status).toBe(200);
    });

    it('GET /api/friends/feed/:userId returns structured activity feed with reactions', async () => {
      const res = await fetch(`${baseUrl}/api/friends/feed/${DEFAULT_USERS.USER_ONE_ID}`);
      expect(res.status).toBe(200);
      const feed = (await res.json()) as any[];
      expect(Array.isArray(feed)).toBe(true);
      if (feed.length > 0) {
        expect(feed[0]).toHaveProperty('id');
        expect(feed[0]).toHaveProperty('kind');
        expect(feed[0]).toHaveProperty('payload');
        expect(feed[0]).toHaveProperty('reactions');
      }
    });

    it('POST /api/friends/react/:userId records reactions to posts', async () => {
      const feedRes = await fetch(`${baseUrl}/api/friends/feed/${DEFAULT_USERS.USER_ONE_ID}`);
      const feed = (await feedRes.json()) as any[];
      if (feed.length > 0) {
        const target = feed[0];
        const reactRes = await fetch(`${baseUrl}/api/friends/react/${DEFAULT_USERS.USER_ONE_ID}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            targetEventId: target.id,
            targetPubkey: target.authorPubkey,
            emoji: '🏆',
          }),
        });
        expect(reactRes.status).toBe(200);
        const data = (await reactRes.json()) as any;
        expect(data.success).toBe(true);
      }
    });
  });
});

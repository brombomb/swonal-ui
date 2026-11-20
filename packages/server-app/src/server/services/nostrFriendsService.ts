import {
  generateSecretKey,
  getPublicKey,
  finalizeEvent,
  verifyEvent,
  nip19,
  SimplePool,
  type Event as NostrEvent,
} from 'nostr-tools';
import { prisma } from '../db';

export const DEFAULT_RELAYS: string[] = [
  'wss://relay.damus.io',
  'wss://nos.lol',
  'wss://relay.snort.social',
  'wss://relay.primal.net',
];

export const NOSTR_KINDS = {
  WORKOUT: 30101,
  ROUTINE: 30102,
  REACTION: 7,
} as const;

export interface NostrIdentity {
  userId: string;
  userName: string;
  npub: string;
  pubkeyHex: string;
  shareWorkouts: boolean;
  relays: string[];
}

export interface WorkoutSummaryPayload {
  sessionId: string;
  routineName: string;
  totalVolumeLb: number;
  durationSec: number;
  setLogs?: Array<{
    exercise?: { name?: string };
    weightLb: number;
    repsCompleted: number;
    volumeLb: number;
  }>;
  prs?: string[];
}

export interface RoutineTemplatePayload {
  templateId: string;
  name: string;
  description?: string;
  blocksJson: string;
}

export interface FeedReaction {
  id: string;
  authorPubkey: string;
  authorName: string;
  emoji: string;
  createdAt: string;
}

export interface FeedItem {
  id: string;
  kind: number;
  authorPubkey: string;
  authorName: string;
  createdAt: string;
  payload: any;
  reactions: FeedReaction[];
}

let sharedPool: SimplePool | null = null;

export function getSimplePool(): SimplePool {
  if (!sharedPool) {
    sharedPool = new SimplePool();
  }
  return sharedPool;
}

export function closePool(): void {
  if (sharedPool) {
    try {
      sharedPool.destroy();
    } catch {
      // ignore
    }
    sharedPool = null;
  }
}

function secretKeyToHex(sk: Uint8Array): string {
  return Buffer.from(sk).toString('hex');
}

function hexToSecretKey(hex: string): Uint8Array {
  return new Uint8Array(Buffer.from(hex, 'hex'));
}

export function normalizePubkey(input: string): { pubkeyHex: string; npub: string } {
  const trimmed = input.trim();
  if (trimmed.startsWith('npub1')) {
    const decoded = nip19.decode(trimmed);
    if (decoded.type === 'npub') {
      const pubkeyHex = decoded.data as string;
      return { pubkeyHex: pubkeyHex.toLowerCase(), npub: trimmed };
    }
    throw new Error('Invalid npub format');
  } else if (/^[0-9a-fA-F]{64}$/.test(trimmed)) {
    const pubkeyHex = trimmed.toLowerCase();
    const npub = nip19.npubEncode(pubkeyHex);
    return { pubkeyHex, npub };
  } else {
    throw new Error('Invalid key. Expected 64-character hex or npub1... format');
  }
}

export async function getOrCreateUserIdentity(userId: string): Promise<NostrIdentity> {
  const user = await prisma.user.findUnique({
    where: { id: userId },
    select: {
      id: true,
      name: true,
      nostrPrivKeyHex: true,
      nostrPubKeyHex: true,
      nostrNpub: true,
      nostrRelays: true,
      shareWorkouts: true,
    },
  });

  if (!user) {
    throw new Error(`User not found: ${userId}`);
  }

  const parseRelays = (relaysJson: string | null | undefined): string[] => {
    if (!relaysJson) return DEFAULT_RELAYS;
    try {
      const parsed = JSON.parse(relaysJson);
      return Array.isArray(parsed) && parsed.length > 0 ? parsed : DEFAULT_RELAYS;
    } catch {
      return DEFAULT_RELAYS;
    }
  };

  if (user.nostrPrivKeyHex && user.nostrPubKeyHex && user.nostrNpub) {
    return {
      userId: user.id,
      userName: user.name,
      npub: user.nostrNpub,
      pubkeyHex: user.nostrPubKeyHex,
      shareWorkouts: user.shareWorkouts,
      relays: parseRelays(user.nostrRelays),
    };
  }

  // Generate self-sovereign secp256k1 keypair
  const sk = generateSecretKey();
  const privHex = secretKeyToHex(sk);
  const pubHex = getPublicKey(sk);
  const npub = nip19.npubEncode(pubHex);
  const relaysJson = JSON.stringify(DEFAULT_RELAYS);

  const updated = await prisma.user.update({
    where: { id: userId },
    data: {
      nostrPrivKeyHex: privHex,
      nostrPubKeyHex: pubHex,
      nostrNpub: npub,
      nostrRelays: relaysJson,
    },
    select: {
      id: true,
      name: true,
      nostrPubKeyHex: true,
      nostrNpub: true,
      nostrRelays: true,
      shareWorkouts: true,
    },
  });

  return {
    userId: updated.id,
    userName: updated.name,
    npub: updated.nostrNpub!,
    pubkeyHex: updated.nostrPubKeyHex!,
    shareWorkouts: updated.shareWorkouts,
    relays: DEFAULT_RELAYS,
  };
}

async function getUserSecretKey(userId: string): Promise<{
  sk: Uint8Array;
  pubkeyHex: string;
  relays: string[];
  name: string;
}> {
  const user = await prisma.user.findUnique({
    where: { id: userId },
    select: {
      id: true,
      name: true,
      nostrPrivKeyHex: true,
      nostrPubKeyHex: true,
      nostrRelays: true,
    },
  });

  if (!user || !user.nostrPrivKeyHex || !user.nostrPubKeyHex) {
    await getOrCreateUserIdentity(userId);
    const refreshed = await prisma.user.findUnique({
      where: { id: userId },
      select: {
        name: true,
        nostrPrivKeyHex: true,
        nostrPubKeyHex: true,
        nostrRelays: true,
      },
    });
    return {
      sk: hexToSecretKey(refreshed!.nostrPrivKeyHex!),
      pubkeyHex: refreshed!.nostrPubKeyHex!,
      name: refreshed!.name,
      relays: refreshed!.nostrRelays ? JSON.parse(refreshed!.nostrRelays) : DEFAULT_RELAYS,
    };
  }

  return {
    sk: hexToSecretKey(user.nostrPrivKeyHex),
    pubkeyHex: user.nostrPubKeyHex,
    name: user.name,
    relays: user.nostrRelays ? JSON.parse(user.nostrRelays) : DEFAULT_RELAYS,
  };
}

export async function publishToRelays(relays: string[], event: NostrEvent): Promise<void> {
  const pool = getSimplePool();
  const targetRelays = relays && relays.length > 0 ? relays : DEFAULT_RELAYS;
  try {
    const promises = pool.publish(targetRelays, event);
    await Promise.allSettled(promises);
  } catch (err) {
    console.warn('[Nostr] Broadcast error:', err);
  }
}

export async function listFriends(userId: string) {
  return prisma.friend.findMany({
    where: { userId },
    orderBy: { displayName: 'asc' },
  });
}

export async function addFriend(
  userId: string,
  npubOrHex: string,
  displayName?: string
) {
  const { pubkeyHex, npub } = normalizePubkey(npubOrHex);

  // Prevent adding own key
  const user = await prisma.user.findUnique({
    where: { id: userId },
    select: { nostrPubKeyHex: true },
  });
  if (user?.nostrPubKeyHex && user.nostrPubKeyHex.toLowerCase() === pubkeyHex.toLowerCase()) {
    throw new Error('Cannot add yourself as a friend');
  }

  const finalDisplayName = displayName?.trim() || `Lifter-${pubkeyHex.slice(0, 6)}`;

  const friend = await prisma.friend.upsert({
    where: {
      userId_pubkeyHex: {
        userId,
        pubkeyHex,
      },
    },
    create: {
      userId,
      npub,
      pubkeyHex,
      displayName: finalDisplayName,
      relaysJson: JSON.stringify(DEFAULT_RELAYS),
    },
    update: {
      displayName: displayName?.trim() ? displayName.trim() : undefined,
      npub,
    },
  });

  // Kick off asynchronous feed sync for this friend
  void syncFeed(userId).catch(() => {});

  return friend;
}

export async function removeFriend(userId: string, friendId: string) {
  return prisma.friend.delete({
    where: {
      id: friendId,
      userId,
    },
  });
}

export async function updateUserFriendsSettings(
  userId: string,
  options: { shareWorkouts?: boolean; relays?: string[] }
) {
  const updateData: { shareWorkouts?: boolean; nostrRelays?: string } = {};
  if (typeof options.shareWorkouts === 'boolean') {
    updateData.shareWorkouts = options.shareWorkouts;
  }
  if (Array.isArray(options.relays)) {
    const validRelays = options.relays
      .map((r) => r.trim())
      .filter((r) => r.startsWith('ws://') || r.startsWith('wss://'));
    updateData.nostrRelays = JSON.stringify(validRelays.length > 0 ? validRelays : DEFAULT_RELAYS);
  }

  return prisma.user.update({
    where: { id: userId },
    data: updateData,
    select: {
      id: true,
      name: true,
      nostrNpub: true,
      nostrPubKeyHex: true,
      nostrRelays: true,
      shareWorkouts: true,
    },
  });
}

export async function publishWorkout(
  userId: string,
  summary: WorkoutSummaryPayload
): Promise<NostrEvent | null> {
  const user = await prisma.user.findUnique({
    where: { id: userId },
    select: { shareWorkouts: true, name: true },
  });
  if (!user || !user.shareWorkouts) {
    return null;
  }

  const { sk, pubkeyHex, relays, name } = await getUserSecretKey(userId);

  const prs = summary.prs ?? [];
  const setLogs = summary.setLogs ?? [];
  const setsCount = setLogs.length;
  const repsCount = setLogs.reduce((sum, s) => sum + (s.repsCompleted || 0), 0);

  const payload = {
    type: 'workout_completion',
    sessionId: summary.sessionId,
    userName: name,
    routineName: summary.routineName,
    totalVolumeLb: Math.round(summary.totalVolumeLb),
    durationSec: Math.round(summary.durationSec),
    setsCount,
    repsCount,
    prs,
    summaryText: `Completed ${summary.routineName}: ${Math.round(
      summary.totalVolumeLb
    ).toLocaleString()} lb across ${setsCount} sets! 💪`,
    completedAt: new Date().toISOString(),
  };

  const eventTemplate = {
    kind: NOSTR_KINDS.WORKOUT,
    created_at: Math.floor(Date.now() / 1000),
    tags: [
      ['d', `swonal-session-${summary.sessionId}`],
      ['t', 'swonal'],
      ['t', 'workout'],
      ['app', 'swonal'],
      ['title', summary.routineName],
    ],
    content: JSON.stringify(payload),
  };

  const signedEvent = finalizeEvent(eventTemplate, sk);

  // Store in local cache so user immediately sees their own post
  await prisma.friendActivity.upsert({
    where: { eventId: signedEvent.id },
    create: {
      eventId: signedEvent.id,
      friendPubkeyHex: pubkeyHex,
      friendDisplayName: name,
      kind: signedEvent.kind,
      createdAt: new Date(signedEvent.created_at * 1000),
      contentJson: signedEvent.content,
    },
    update: {
      contentJson: signedEvent.content,
    },
  });

  void publishToRelays(relays, signedEvent);

  return signedEvent;
}

export async function publishRoutine(
  userId: string,
  templateId: string
): Promise<NostrEvent> {
  const template = await prisma.workoutTemplate.findUnique({
    where: { id: templateId },
    include: { user: { select: { name: true } } },
  });
  if (!template) {
    throw new Error(`Workout template not found: ${templateId}`);
  }

  const { sk, pubkeyHex, relays, name } = await getUserSecretKey(userId);

  let blocks: unknown[] = [];
  try {
    blocks = JSON.parse(template.blocksJson || '[]');
  } catch {
    blocks = [];
  }

  const payload = {
    type: 'routine_share',
    templateId: template.id,
    name: template.name,
    description: template.description ?? '',
    authorName: name,
    blocks,
    sharedAt: new Date().toISOString(),
  };

  const eventTemplate = {
    kind: NOSTR_KINDS.ROUTINE,
    created_at: Math.floor(Date.now() / 1000),
    tags: [
      ['d', `swonal-routine-${template.id}`],
      ['t', 'swonal'],
      ['t', 'routine'],
      ['app', 'swonal'],
      ['title', template.name],
    ],
    content: JSON.stringify(payload),
  };

  const signedEvent = finalizeEvent(eventTemplate, sk);

  await prisma.friendActivity.upsert({
    where: { eventId: signedEvent.id },
    create: {
      eventId: signedEvent.id,
      friendPubkeyHex: pubkeyHex,
      friendDisplayName: name,
      kind: signedEvent.kind,
      createdAt: new Date(signedEvent.created_at * 1000),
      contentJson: signedEvent.content,
    },
    update: {
      contentJson: signedEvent.content,
    },
  });

  void publishToRelays(relays, signedEvent);

  return signedEvent;
}

export async function importRoutine(
  userId: string,
  eventId: string
) {
  const activity = await prisma.friendActivity.findUnique({
    where: { eventId },
  });
  if (!activity || activity.kind !== NOSTR_KINDS.ROUTINE) {
    throw new Error('Shared routine not found in activity cache');
  }

  let parsed: any;
  try {
    parsed = JSON.parse(activity.contentJson);
  } catch {
    throw new Error('Corrupt routine payload in activity record');
  }

  const friendAuthor = activity.friendDisplayName || 'Friend';
  const name = parsed.name
    ? `${parsed.name} (from ${friendAuthor})`
    : `Shared Routine (from ${friendAuthor})`;
  const description =
    parsed.description || `Shared peer-to-peer via Nostr by ${friendAuthor}`;
  const blocksJson =
    typeof parsed.blocks === 'string'
      ? parsed.blocks
      : JSON.stringify(parsed.blocks ?? []);

  const newTemplate = await prisma.workoutTemplate.create({
    data: {
      userId,
      name,
      description,
      blocksJson,
    },
  });

  return newTemplate;
}

export async function publishReaction(
  userId: string,
  targetEventId: string,
  targetPubkey: string,
  emoji: string
): Promise<NostrEvent> {
  const { sk, pubkeyHex, relays, name } = await getUserSecretKey(userId);

  const cleanEmoji = emoji.trim() || '💪';

  const eventTemplate = {
    kind: NOSTR_KINDS.REACTION,
    created_at: Math.floor(Date.now() / 1000),
    tags: [
      ['e', targetEventId],
      ['p', targetPubkey],
      ['k', String(NOSTR_KINDS.WORKOUT)],
    ],
    content: cleanEmoji,
  };

  const signedEvent = finalizeEvent(eventTemplate, sk);

  await prisma.friendActivity.upsert({
    where: { eventId: signedEvent.id },
    create: {
      eventId: signedEvent.id,
      friendPubkeyHex: pubkeyHex,
      friendDisplayName: name,
      kind: NOSTR_KINDS.REACTION,
      createdAt: new Date(signedEvent.created_at * 1000),
      contentJson: JSON.stringify({
        emoji: cleanEmoji,
        targetEventId,
        targetPubkey,
      }),
    },
    update: {
      contentJson: JSON.stringify({
        emoji: cleanEmoji,
        targetEventId,
        targetPubkey,
      }),
    },
  });

  void publishToRelays(relays, signedEvent);

  return signedEvent;
}

export async function syncFeed(userId: string): Promise<{ syncedCount: number }> {
  const friends = await prisma.friend.findMany({
    where: { userId },
  });
  const user = await prisma.user.findUnique({
    where: { id: userId },
    select: { nostrPubKeyHex: true, nostrRelays: true, name: true },
  });

  const relays: string[] = user?.nostrRelays
    ? JSON.parse(user.nostrRelays)
    : DEFAULT_RELAYS;

  const friendMap = new Map(friends.map((f) => [f.pubkeyHex.toLowerCase(), f]));
  const friendPubkeys = friends.map((f) => f.pubkeyHex.toLowerCase());

  const watchedPubkeys = [...friendPubkeys];
  if (user?.nostrPubKeyHex && !watchedPubkeys.includes(user.nostrPubKeyHex.toLowerCase())) {
    watchedPubkeys.push(user.nostrPubKeyHex.toLowerCase());
  }

  if (watchedPubkeys.length === 0) {
    return { syncedCount: 0 };
  }

  const pool = getSimplePool();
  let fetchedEvents: NostrEvent[] = [];

  try {
    const postFilter = {
      kinds: [NOSTR_KINDS.WORKOUT, NOSTR_KINDS.ROUTINE, NOSTR_KINDS.REACTION],
      authors: watchedPubkeys,
      limit: 100,
    };
    const reactionFilter = {
      kinds: [NOSTR_KINDS.REACTION],
      '#p': watchedPubkeys,
      limit: 100,
    };

    const queryPromise = Promise.all([
      pool.querySync(relays, postFilter, { maxWait: 4000 }),
      pool.querySync(relays, reactionFilter, { maxWait: 4000 }),
    ]);

    const results = await Promise.race([
      queryPromise,
      new Promise<[NostrEvent[], NostrEvent[]]>((_, reject) =>
        setTimeout(() => reject(new Error('Sync timeout')), 4500)
      ),
    ]);

    fetchedEvents = [...results[0], ...results[1]];
  } catch (err) {
    console.warn('[Nostr] Sync query finished with notice:', err);
  }

  let upsertedCount = 0;
  for (const ev of fetchedEvents) {
    try {
      if (!verifyEvent(ev)) continue;

      const authorLower = ev.pubkey.toLowerCase();
      const friend = friendMap.get(authorLower);
      const isSelf = user?.nostrPubKeyHex?.toLowerCase() === authorLower;
      const displayName = friend
        ? friend.displayName
        : isSelf
        ? `${user?.name || 'You'} (You)`
        : undefined;

      await prisma.friendActivity.upsert({
        where: { eventId: ev.id },
        create: {
          eventId: ev.id,
          friendPubkeyHex: authorLower,
          friendDisplayName: displayName,
          kind: ev.kind,
          createdAt: new Date(ev.created_at * 1000),
          contentJson: ev.content,
        },
        update: {
          friendDisplayName: displayName ?? undefined,
        },
      });
      upsertedCount++;

      if (friend) {
        const evDate = new Date(ev.created_at * 1000);
        if (!friend.lastSeenAt || evDate > friend.lastSeenAt) {
          await prisma.friend.update({
            where: { id: friend.id },
            data: { lastSeenAt: evDate },
          });
        }
      }
    } catch {
      // Ignore individual parse/db collision errors
    }
  }

  return { syncedCount: upsertedCount };
}

export async function getFeed(userId: string, limit = 50): Promise<FeedItem[]> {
  const friends = await prisma.friend.findMany({
    where: { userId },
  });
  const user = await prisma.user.findUnique({
    where: { id: userId },
    select: { nostrPubKeyHex: true, name: true },
  });

  const friendMap = new Map<string, string>();
  for (const f of friends) {
    friendMap.set(f.pubkeyHex.toLowerCase(), f.displayName);
  }
  if (user?.nostrPubKeyHex) {
    friendMap.set(user.nostrPubKeyHex.toLowerCase(), `${user.name} (You)`);
  }

  const allowedPubkeys = Array.from(friendMap.keys());
  if (allowedPubkeys.length === 0) {
    return [];
  }

  const activities = await prisma.friendActivity.findMany({
    where: {
      friendPubkeyHex: { in: allowedPubkeys },
      kind: { in: [NOSTR_KINDS.WORKOUT, NOSTR_KINDS.ROUTINE] },
    },
    orderBy: { createdAt: 'desc' },
    take: limit,
  });

  // Fetch reactions
  const reactions = await prisma.friendActivity.findMany({
    where: {
      kind: NOSTR_KINDS.REACTION,
    },
    orderBy: { createdAt: 'asc' },
  });

  const reactionsByTarget = new Map<string, FeedReaction[]>();
  for (const r of reactions) {
    try {
      let targetId: string | undefined;
      let emoji = '💪';
      try {
        const parsed = JSON.parse(r.contentJson);
        targetId = parsed.targetEventId;
        emoji = parsed.emoji || r.contentJson;
      } catch {
        emoji = r.contentJson;
      }

      if (targetId) {
        const existing = reactionsByTarget.get(targetId) || [];
        existing.push({
          id: r.eventId,
          authorPubkey: r.friendPubkeyHex,
          authorName: friendMap.get(r.friendPubkeyHex.toLowerCase()) || r.friendDisplayName || 'Friend',
          emoji,
          createdAt: r.createdAt.toISOString(),
        });
        reactionsByTarget.set(targetId, existing);
      }
    } catch {
      // ignore
    }
  }

  return activities.map((act) => {
    let payload: any = {};
    try {
      payload = JSON.parse(act.contentJson);
    } catch {
      payload = { raw: act.contentJson };
    }

    return {
      id: act.eventId,
      kind: act.kind,
      authorPubkey: act.friendPubkeyHex,
      authorName:
        friendMap.get(act.friendPubkeyHex.toLowerCase()) ||
        act.friendDisplayName ||
        'Friend',
      createdAt: act.createdAt.toISOString(),
      payload,
      reactions: reactionsByTarget.get(act.eventId) || [],
    };
  });
}

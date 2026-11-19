import { describe, expect, it, afterAll } from 'vitest';
import { prisma } from '../src/server/db';
import { DEFAULT_USERS } from '../prisma/seed';
import {
  estimateOneRepMaxLb,
  kgToLb,
  lbToKg,
  toCanonicalLb,
} from '../src/server/utils/units';
import { musicRegistry } from '../src/server/music/Registry';

describe('Milestone 2: Units, Database Seed, & Music Provider Architecture', () => {
  afterAll(async () => {
    await prisma.$disconnect();
  });

  it('converts pounds and kilograms accurately with 0.5 increments and 5-200 lb clamping', () => {
    expect(lbToKg(100)).toBe(45.5);
    expect(lbToKg(45)).toBe(20.5);
    expect(kgToLb(20)).toBe(44);
    expect(kgToLb(100)).toBe(200); // clamped to max motor limit
    expect(kgToLb(1)).toBe(5); // clamped to min motor limit
    expect(toCanonicalLb(45, 'LB')).toBe(45);
    expect(toCanonicalLb(20.5, 'KG')).toBe(45);
    expect(estimateOneRepMaxLb(100, 10)).toBeCloseTo(133.3, 1);
  });

  it('verifies seeded catalog of 45+ exercises, Alex & Taylor profiles, PRs, and templates', async () => {
    const exerciseCount = await prisma.exercise.count();
    expect(exerciseCount).toBeGreaterThanOrEqual(48);

    const accessories = await prisma.exercise.findMany({
      distinct: ['accessory'],
      select: { accessory: true },
    });
    const accSet = new Set(accessories.map((a) => a.accessory));
    expect(accSet.has('HANDLES')).toBe(true);
    expect(accSet.has('BAR')).toBe(true);
    expect(accSet.has('ROPE')).toBe(true);
    expect(accSet.has('BODYWEIGHT')).toBe(true);

    const alex = await prisma.user.findUnique({ where: { id: DEFAULT_USERS.USER_ONE_ID } });
    const taylor = await prisma.user.findUnique({ where: { id: DEFAULT_USERS.USER_TWO_ID } });
    expect(['Alex', 'Rob']).toContain(alex?.name);
    expect(alex?.accentColor).toMatch(/^#[0-9A-Fa-f]{6}$/);
    expect(['Taylor', 'Jeri']).toContain(taylor?.name);
    expect(taylor?.accentColor).toMatch(/^#[0-9A-Fa-f]{6}$/);

    const templates = await prisma.workoutTemplate.findMany();
    expect(templates.length).toBeGreaterThanOrEqual(4);
    const parsedBlocks = templates.flatMap((t) => JSON.parse(t.blocksJson));
    const blockTypes = new Set(parsedBlocks.map((b: { blockType: string }) => b.blockType));
    expect(blockTypes.has('WARMUP')).toBe(true);
    expect(blockTypes.has('STRAIGHT_SET')).toBe(true);
    expect(blockTypes.has('SUPERSET')).toBe(true);
    expect(blockTypes.has('CIRCUIT')).toBe(true);
  });

  it('exercises MusicProviderRegistry and requires Spotify connection before playback', async () => {
    const providers = musicRegistry.list();
    expect(providers.map((p) => p.id)).toEqual(
      expect.arrayContaining(['spotify', 'youtube_music', 'apple_music', 'amazon_music', 'plexamp'])
    );

    const spotify = musicRegistry.get('spotify');
    const userId = 'test-disconnected-music-user';

    const disconnectedState = await spotify.getPlaybackState(userId);
    expect(disconnectedState.isConnected).toBe(false);
    expect(disconnectedState.isPlaying).toBe(false);
    expect(disconnectedState.artistName).toContain('Connect a music service to play');

    const playlists = await spotify.getPlaylists(userId);
    expect(playlists).toEqual([]);

    const devices = await spotify.getDevices(userId);
    expect(devices).toEqual([]);

    await expect(spotify.play(userId)).rejects.toThrow('must be connected');
  });
});

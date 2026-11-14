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

  it('verifies seeded catalog of 45+ exercises, Jeri & Rob profiles, PRs, and templates', async () => {
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

    const jeri = await prisma.user.findUnique({ where: { id: DEFAULT_USERS.JERI_ID } });
    const rob = await prisma.user.findUnique({ where: { id: DEFAULT_USERS.ROB_ID } });
    expect(jeri?.name).toBe('Jeri');
    expect(jeri?.accentColor).toBe('#FF007A');
    expect(rob?.name).toBe('Rob');
    expect(rob?.accentColor).toBe('#00F0FF');

    const templates = await prisma.workoutTemplate.findMany();
    expect(templates.length).toBeGreaterThanOrEqual(4);
    const parsedBlocks = templates.flatMap((t) => JSON.parse(t.blocksJson));
    const blockTypes = new Set(parsedBlocks.map((b: { blockType: string }) => b.blockType));
    expect(blockTypes.has('WARMUP')).toBe(true);
    expect(blockTypes.has('STRAIGHT_SET')).toBe(true);
    expect(blockTypes.has('SUPERSET')).toBe(true);
    expect(blockTypes.has('CIRCUIT')).toBe(true);
  });

  it('exercises MusicProviderRegistry, SpotifyProvider playback, device transfer, and volume ducking', async () => {
    const providers = musicRegistry.list();
    expect(providers.map((p) => p.id)).toEqual(['spotify', 'youtube_music', 'apple_music']);

    const spotify = musicRegistry.get('spotify');
    const userId = DEFAULT_USERS.JERI_ID;

    const authUrl = await spotify.getAuthUrl(userId);
    expect(authUrl).toContain('spotify');

    await spotify.setVolume(userId, 80);
    await spotify.play(userId);
    const playingState = await spotify.getPlaybackState(userId);
    expect(playingState.isPlaying).toBe(true);
    const firstTrack = playingState.trackName;

    await spotify.nextTrack(userId);
    const nextState = await spotify.getPlaybackState(userId);
    expect(nextState.trackName).not.toBe(firstTrack);

    const playlists = await spotify.getPlaylists(userId);
    expect(playlists.length).toBeGreaterThanOrEqual(3);

    const devices = await spotify.getDevices(userId);
    expect(devices.some((d) => d.name.includes('librespot'))).toBe(true);

    await spotify.transferPlayback(userId, 'gym-sonos-02');
    const updatedState = await spotify.getPlaybackState(userId);
    expect(updatedState.activeDeviceId).toBe('gym-sonos-02');

    // Duck volume to 25% for 150ms, verify immediate duck and restore after 220ms
    await spotify.duckVolume(userId, 25, 150);
    const duckedState = await spotify.getPlaybackState(userId);
    expect(duckedState.volumePct).toBe(25);
    expect(duckedState.isDucked).toBe(true);

    await new Promise((r) => setTimeout(r, 220));
    const restoredState = await spotify.getPlaybackState(userId);
    expect(restoredState.volumePct).toBe(80);
    expect(restoredState.isDucked).toBe(false);
  });
});

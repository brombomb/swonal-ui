import fs from 'fs';
import os from 'os';
import path from 'path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DEFAULT_USERS } from '../prisma/seed';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { aiCoachService } from '../src/server/services/aiCoach';
import { invalidateRuntimeSettingsCache } from '../src/server/services/runtimeSettings';

describe('Hands-Free AirPods Voice Agent & Audio Coaching Commands', () => {
  let settingsFile: string;
  let srv: SwonalServerHandle;

  beforeAll(async () => {
    settingsFile = path.join(os.tmpdir(), `swonal-voice-coach-${Date.now()}.json`);
    process.env.SWONAL_SETTINGS_FILE = settingsFile;
    invalidateRuntimeSettingsCache();
    aiCoachService.reloadFromSettings();
    aiCoachService.setConfig({ provider: 'local' });

    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
  });

  afterAll(async () => {
    await srv.close();
    await prisma.$disconnect();
    delete process.env.SWONAL_SETTINGS_FILE;
    invalidateRuntimeSettingsCache();
    aiCoachService.reloadFromSettings();
    try {
      fs.unlinkSync(settingsFile);
    } catch {
      // ignore cleanup error
    }
  });

  it('detects weight delta commands with digits ("add 5 pounds", "drop 10 lbs", "plus 5", "minus 5")', async () => {
    const add5 = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'add 5 pounds',
      currentWeightLb: 45,
    });
    expect(add5.suggestedAction).toEqual({
      type: 'set_weight_delta',
      deltaLb: 5,
      label: 'Add +5 lb',
    });
    expect(add5.reply).toContain('50');

    const inc10 = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'increase weight by 10',
      currentWeightLb: 50,
    });
    expect(inc10.suggestedAction?.type).toBe('set_weight_delta');
    expect(inc10.suggestedAction?.deltaLb).toBe(10);

    const drop5 = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'drop 5 pounds',
      currentWeightLb: 50,
    });
    expect(drop5.suggestedAction).toEqual({
      type: 'set_weight_delta',
      deltaLb: -5,
      label: 'Drop -5 lb',
    });

    const minus10 = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'decrease weight by 10',
      currentWeightLb: 60,
    });
    expect(minus10.suggestedAction?.type).toBe('set_weight_delta');
    expect(minus10.suggestedAction?.deltaLb).toBe(-10);

    // Small relative deltas (<5 lb) must NOT be clamped up to the 5 lb absolute floor
    const add1 = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'add 1 pound',
      currentWeightLb: 50,
    });
    expect(add1.suggestedAction?.deltaLb).toBe(1);

    const drop25 = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'drop 2.5 lb',
      currentWeightLb: 50,
    });
    expect(drop25.suggestedAction?.deltaLb).toBe(-2.5);
  });

  it('detects weight delta commands with spoken number words ("add five pounds", "drop ten pounds", "bump fifteen")', async () => {
    const addFive = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'add five pounds',
      currentWeightLb: 45,
    });
    expect(addFive.suggestedAction?.type).toBe('set_weight_delta');
    expect(addFive.suggestedAction?.deltaLb).toBe(5);

    const dropTen = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'drop ten pounds',
      currentWeightLb: 65,
    });
    expect(dropTen.suggestedAction?.type).toBe('set_weight_delta');
    expect(dropTen.suggestedAction?.deltaLb).toBe(-10);

    const heavierTwenty = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'heavier by twenty',
      currentWeightLb: 45,
    });
    expect(heavierTwenty.suggestedAction?.type).toBe('set_weight_delta');
    expect(heavierTwenty.suggestedAction?.deltaLb).toBe(20);
  });

  it('detects absolute weight target commands ("set weight to 50 pounds", "make it forty five lbs")', async () => {
    const set50 = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'set weight to 50 pounds',
      currentWeightLb: 35,
    });
    expect(set50.suggestedAction).toEqual({
      type: 'set_weight_target',
      targetLb: 50,
      label: 'Set Weight to 50 lb',
    });

    const make45 = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'make it forty five lbs',
      currentWeightLb: 35,
    });
    expect(make45.suggestedAction?.type).toBe('set_weight_target');
    expect(make45.suggestedAction?.targetLb).toBe(45);
  });

  it('detects all 5 dynamic workout mode voice commands (STANDARD, ECCENTRIC, CHAINS, SPOTTER, BURNOUT)', async () => {
    const cases: Array<{ prompt: string; expectedMode: string }> = [
      { prompt: 'switch to eccentric mode', expectedMode: 'ECCENTRIC' },
      { prompt: 'turn on chains', expectedMode: 'CHAINS' },
      { prompt: 'spotter mode', expectedMode: 'SPOTTER' },
      { prompt: 'switch to burnout mode', expectedMode: 'BURNOUT' },
      { prompt: 'normal mode', expectedMode: 'STANDARD' },
    ];

    for (const c of cases) {
      const res = await aiCoachService.ask({
        userId: DEFAULT_USERS.USER_ONE_ID,
        prompt: c.prompt,
      });
      expect(res.suggestedAction?.type).toBe('set_mode');
      expect(res.suggestedAction?.mode).toBe(c.expectedMode);
    }
  });

  it('detects next, previous, and named exercise selection commands', async () => {
    const nextRes = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'next exercise',
    });
    expect(nextRes.suggestedAction?.type).toBe('next_exercise');

    const prevRes = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'previous exercise',
    });
    expect(prevRes.suggestedAction?.type).toBe('prev_exercise');

    const selectRes = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'switch to Barbell Bicep Curl',
    });
    expect(selectRes.suggestedAction).toEqual({
      type: 'select_exercise',
      exerciseQuery: 'Barbell Bicep Curl',
      label: 'Switch to Barbell Bicep Curl',
    });

    const changeWorkoutRes = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'change workout to Lat Pulldown',
    });
    expect(changeWorkoutRes.suggestedAction?.type).toBe('select_exercise');
    expect(changeWorkoutRes.suggestedAction?.exerciseQuery).toBe('Lat Pulldown');
  });

  it('detects weight on/off, toggle weight, skip rest, and complete set commands', async () => {
    const weightOn = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'turn weight on',
    });
    expect(weightOn.suggestedAction).toEqual({
      type: 'set_weight_enabled',
      enabled: true,
      label: 'Turn Weight On',
    });

    const weightOff = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'rack weight',
    });
    expect(weightOff.suggestedAction).toEqual({
      type: 'set_weight_enabled',
      enabled: false,
      label: 'Turn Weight Off',
    });

    const toggle = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'toggle weight',
    });
    expect(toggle.suggestedAction?.type).toBe('toggle_weight');

    const skipRest = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'skip rest',
    });
    expect(skipRest.suggestedAction?.type).toBe('skip_rest');

    const completeSet = await aiCoachService.ask({
      userId: DEFAULT_USERS.USER_ONE_ID,
      prompt: 'complete set',
    });
    expect(completeSet.suggestedAction?.type).toBe('complete_set');
  });
});

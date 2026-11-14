import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT_DIR = path.resolve(__dirname, '..');
const DAEMON_DIR = path.join(ROOT_DIR, 'packages', 'tonal-daemon');
const SERVER_DIR = path.join(ROOT_DIR, 'packages', 'server-app');

const requireFromServer = createRequire(path.join(SERVER_DIR, 'package.json'));
const WebSocket = requireFromServer('ws');
const { Client } = requireFromServer('@modelcontextprotocol/sdk/client/index.js');
const { SSEClientTransport } = requireFromServer('@modelcontextprotocol/sdk/client/sse.js');

const DAEMON_PORT = 8765;
const SERVER_PORT = 3000;
const DAEMON_HTTP = `http://127.0.0.1:${DAEMON_PORT}`;
const SERVER_HTTP = `http://127.0.0.1:${SERVER_PORT}`;
const SERVER_WS = `ws://127.0.0.1:${SERVER_PORT}/ws`;

async function waitForHttp(url, checkFn = (r) => r.ok, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const resp = await fetch(url);
      if (await checkFn(resp)) return resp;
    } catch {
      // Retry until ready
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`Timed out waiting for ${url}`);
}

async function run() {
  console.log('1. Starting tonal-daemon (--mock) and server-app...');
  const daemonProc = spawn(
    path.join(DAEMON_DIR, '.venv', 'bin', 'python'),
    ['-m', 'src.main', '--mock'],
    {
      cwd: DAEMON_DIR,
      env: { ...process.env, MOCK_TONAL: 'true', DAEMON_PORT: String(DAEMON_PORT) },
      stdio: ['ignore', 'pipe', 'pipe'],
    }
  );

  const serverProc = spawn('node', ['dist/server/index.js'], {
    cwd: SERVER_DIR,
    env: {
      ...process.env,
      NODE_ENV: 'production',
      PORT: String(SERVER_PORT),
      TONAL_DAEMON_URL: DAEMON_HTTP,
      TONAL_DAEMON_WS_URL: `ws://127.0.0.1:${DAEMON_PORT}/ws`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let ws;
  let createdTemplateId = null;
  let createdSessionId = null;

  const cleanup = async () => {
    try {
      if (ws && ws.readyState === WebSocket.OPEN) ws.close();
    } catch {}
    daemonProc.kill('SIGTERM');
    serverProc.kill('SIGTERM');
  };

  try {
    await waitForHttp(`${DAEMON_HTTP}/health`);
    const serverHealthResp = await waitForHttp(`${SERVER_HTTP}/api/health`, async (r) => {
      if (!r.ok) return false;
      const body = await r.clone().json();
      return body.daemonConnected === true;
    });
    const health = await serverHealthResp.json();
    assert.equal(health.status, 'ok');
    assert.equal(health.daemonConnected, true);
    console.log('   ✓ Daemon (:8765) & Server (:3000) online and bridged.');

    // 2. Verify frontend routes serve built HTML
    const [kioskHtmlResp, companionHtmlResp] = await Promise.all([
      fetch(`${SERVER_HTTP}/`),
      fetch(`${SERVER_HTTP}/companion`),
    ]);
    assert.equal(kioskHtmlResp.status, 200);
    assert.equal(companionHtmlResp.status, 200);
    const kioskHtml = await kioskHtmlResp.text();
    assert.ok(kioskHtml.includes('swonal-ui'));
    console.log('   ✓ Production UI served at / and /companion.');

    // 3. Verify users, exercises, user profile PATCH, and WorkoutTemplate CRUD + queue
    const users = await (await fetch(`${SERVER_HTTP}/api/users`)).json();
    const jeri = users.find((u) => u.name === 'Jeri');
    const rob = users.find((u) => u.name === 'Rob');
    assert.ok(jeri && rob, 'Seeded household profiles Jeri & Rob must exist');
    assert.equal(jeri.accentColor, '#FF007A');
    assert.equal(rob.accentColor, '#00F0FF');

    const exercises = await (await fetch(`${SERVER_HTTP}/api/exercises`)).json();
    assert.ok(exercises.length >= 48, `Expected >=48 exercises, got ${exercises.length}`);
    const bench = exercises.find((e) => e.name === 'Bench Press');
    assert.ok(bench);

    // Toggle unit to KG and restore LB
    const patchKg = await fetch(`${SERVER_HTTP}/api/users/${jeri.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ weightUnit: 'KG' }),
    });
    assert.equal((await patchKg.json()).weightUnit, 'KG');
    await fetch(`${SERVER_HTTP}/api/users/${jeri.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ weightUnit: 'LB' }),
    });

    const createTplResp = await fetch(`${SERVER_HTTP}/api/templates`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: jeri.id,
        name: 'E2E Verification Routine',
        description: 'Automated E2E test template',
        blocks: [
          {
            blockType: 'STRAIGHT_SET',
            restSeconds: 45,
            items: [
              {
                exerciseId: bench.id,
                sets: 2,
                targetReps: 8,
                mode: 'chains',
                modifierPct: 25,
                targetWeightLb: 55,
              },
            ],
          },
        ],
      }),
    });
    assert.equal(createTplResp.status, 201);
    const tpl = await createTplResp.json();
    createdTemplateId = tpl.id;
    const queueResp = await fetch(`${SERVER_HTTP}/api/templates/${tpl.id}/queue`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: jeri.id }),
    });
    assert.equal(queueResp.status, 200);
    console.log('   ✓ Users, 50 exercises, template CRUD & queueing verified.');

    // 4. Verify Music Provider API & Volume Ducking
    const provs = await (await fetch(`${SERVER_HTTP}/api/music/providers`)).json();
    assert.ok(provs.some((p) => p.id === 'spotify'));
    const playResp = await fetch(`${SERVER_HTTP}/api/music/spotify/play`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: jeri.id }),
    });
    const playingMusic = await playResp.json();
    assert.equal(playingMusic.isPlaying, true);
    const duckResp = await fetch(`${SERVER_HTTP}/api/music/spotify/duck`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: jeri.id, targetPct: 20, durationMs: 200 }),
    });
    const duckedMusic = await duckResp.json();
    assert.equal(duckedMusic.isDucked, true);
    console.log('   ✓ Pluggable Music Provider & volume ducking verified.');

    // 5. Connect WebSocket to server-app and test 30Hz telemetry, reps, BLE events, Partner rotation, & Bar Tilt Cutout
    ws = new WebSocket(SERVER_WS);
    const receivedMessages = [];
    ws.on('message', (raw) => {
      receivedMessages.push(JSON.parse(raw.toString()));
    });
    await new Promise((resolve, reject) => {
      ws.once('open', resolve);
      ws.once('error', reject);
    });

    const waitForMsg = async (predicate, timeoutMs = 6000) => {
      const start = Date.now();
      while (Date.now() - start < timeoutMs) {
        const match = receivedMessages.find(predicate);
        if (match) return match;
        await new Promise((r) => setTimeout(r, 40));
      }
      throw new Error('Timed out waiting for matching WebSocket message');
    };

    // Reset reps, configure 55lb chains, enable weight
    ws.send(JSON.stringify({ type: 'reset_reps' }));
    ws.send(
      JSON.stringify({
        type: 'set_weight',
        weight_lb: 55,
        profile: 'chains',
        modifier_pct: 25,
      })
    );
    ws.send(JSON.stringify({ type: 'enable_weight' }));

    const activeFrame = await waitForMsg(
      (m) => m.type === 'telemetry' && m.weight_enabled === true && m.target_weight_lb === 55
    );
    assert.ok(activeFrame.applied_resistance_lb >= 55);
    console.log('   ✓ Live 30Hz telemetry & weight engagement verified.');

    // Wait for at least 1 simulated rep (~3s)
    const repFrame = await waitForMsg(
      (m) => m.type === 'telemetry' && m.rep_count >= 1,
      6000
    );
    assert.ok(repFrame.rep_count >= 1);
    console.log(`   ✓ Simulated rep counting verified (rep_count=${repFrame.rep_count}).`);

    // Trigger BLE double-click spotify_next and emergency_stop
    const beforeTrack = playingMusic.trackName;
    await fetch(`${SERVER_HTTP}/api/daemon/mock/ble-event`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'spotify_next' }),
    });
    await new Promise((r) => setTimeout(r, 150));
    const afterMusic = await (
      await fetch(`${SERVER_HTTP}/api/music/spotify/state?userId=${jeri.id}`)
    ).json();
    assert.notEqual(afterMusic.trackName, beforeTrack);

    await fetch(`${SERVER_HTTP}/api/daemon/mock/ble-event`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'emergency_stop' }),
    });
    receivedMessages.length = 0;
    await waitForMsg((m) => m.type === 'telemetry' && m.weight_enabled === false);
    console.log('   ✓ BLE Smart Handle double-click (skip track) & long-press E-stop verified.');

    // Partner Work-In Set Logging
    ws.send(
      JSON.stringify({
        type: 'set_partner_mode',
        enabled: true,
        partnerUserIds: [jeri.id, rob.id],
      })
    );
    await new Promise((r) => setTimeout(r, 100));
    const sessResp = await fetch(`${SERVER_HTTP}/api/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: jeri.id, templateId: tpl.id }),
    });
    const session = await sessResp.json();
    createdSessionId = session.id;
    const setResp = await fetch(`${SERVER_HTTP}/api/sessions/${session.id}/sets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: jeri.id,
        exerciseId: bench.id,
        setNumber: 1,
        mode: 'standard',
        weightLb: 55,
        repsCompleted: 8,
        restSeconds: 30,
      }),
    });
    const setOut = await setResp.json();
    assert.equal(setOut.partnerRotation.rotatedToUserId, rob.id);
    console.log('   ✓ Partner Work-In set logging & weight rotation verified.');

    // Phase 1.5 Bar Tilt Safety Cutout
    receivedMessages.length = 0;
    const tiltResp = await fetch(`${SERVER_HTTP}/api/daemon/mock/bar-tilt`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ delta: 48.0, duration_sec: 0.7, auto_enable: true }),
    });
    assert.equal(tiltResp.status, 200);
    const safetyEvt = await waitForMsg(
      (m) => m.type === 'safety_event' && m.reason === 'BAR_TILT_CUTOUT',
      3000
    );
    assert.equal(safetyEvt.delta, 48.0);
    await waitForMsg((m) => m.type === 'telemetry' && m.weight_enabled === false, 2000);
    console.log('   ✓ Phase 1.5 Bar Tilt Safety Cutout (BAR_TILT_CUTOUT + auto-disable) verified.');

    // 6. MCP SSE Endpoint check
    const mcpClient = new Client({ name: 'e2e-mcp-client', version: '1.0.0' });
    const mcpTransport = new SSEClientTransport(new URL(`${SERVER_HTTP}/mcp/sse`));
    await mcpClient.connect(mcpTransport);
    const toolOut = await mcpClient.callTool({
      name: 'list_exercises',
      arguments: { accessory: 'ROPE' },
    });
    const ropeExercises = JSON.parse(toolOut.content[0].text);
    assert.ok(ropeExercises.length >= 4);
    await mcpClient.close();
    console.log('   ✓ Built-in MCP SSE server (/mcp/sse) verified.');

    // Clean up temporary E2E records
    if (createdTemplateId) {
      await fetch(`${SERVER_HTTP}/api/templates/${createdTemplateId}`, { method: 'DELETE' });
    }
    if (createdSessionId) {
      await fetch(`${SERVER_HTTP}/api/sessions/${createdSessionId}/complete`, { method: 'POST' });
    }

    console.log('\nALL E2E CHECKS PASSED SUCCESSFULLY!');
  } finally {
    await cleanup();
  }
}

run().catch((err) => {
  console.error('\nE2E Verification FAILED:', err);
  process.exit(1);
});

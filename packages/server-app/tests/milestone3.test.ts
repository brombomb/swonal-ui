import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';
import { DEFAULT_USERS } from '../prisma/seed';
import { prisma } from '../src/server/db';
import { createSwonalServer, SwonalServerHandle } from '../src/server/index';
import { sessionCoordinator } from '../src/server/services/sessionState';

describe('Milestone 3: MCP Server Layer, HTTP SSE, REST API, & Partner Rotation', () => {
  let srv: SwonalServerHandle;
  let baseUrl: string;
  let wsUrl: string;
  let createdTemplateId: string | null = null;
  let createdSessionId: string | null = null;

  beforeAll(async () => {
    srv = await createSwonalServer({ port: 0, connectDaemonWs: false });
    baseUrl = `http://127.0.0.1:${srv.port}`;
    wsUrl = `ws://127.0.0.1:${srv.port}/ws`;
  });

  afterAll(async () => {
    if (createdSessionId) {
      await prisma.workoutSession.delete({ where: { id: createdSessionId } }).catch(() => {});
    }
    if (createdTemplateId) {
      await prisma.workoutTemplate.delete({ where: { id: createdTemplateId } }).catch(() => {});
    }
    sessionCoordinator.setPartnerMode(false);
    await srv.close();
    await prisma.$disconnect();
  });

  it('connects an MCP client over /mcp/sse, lists/reads all 3 resources, and runs all 5 tools with KG->LB conversion', async () => {
    const client = new Client({ name: 'test-mcp-client', version: '1.0.0' });
    const transport = new SSEClientTransport(new URL(`${baseUrl}/mcp/sse`));
    await client.connect(transport);

    const resources = await client.listResources();
    const uris = resources.resources.map((r) => r.uri);
    expect(uris).toContain('swonal://exercises/catalog');

    const catalogRead = await client.readResource({ uri: 'swonal://exercises/catalog' });
    const catalogJson = JSON.parse((catalogRead.contents[0] as { text: string }).text);
    expect(catalogJson.length).toBeGreaterThanOrEqual(48);

    const profileRead = await client.readResource({
      uri: `swonal://users/${DEFAULT_USERS.USER_ONE_ID}/profile-and-prs`,
    });
    const profileJson = JSON.parse((profileRead.contents[0] as { text: string }).text);
    expect(['Alex', 'Rob']).toContain(profileJson.user.name);
    expect(profileJson.personalRecords.length).toBeGreaterThan(0);

    const workoutsRead = await client.readResource({
      uri: `swonal://users/${DEFAULT_USERS.USER_TWO_ID}/workouts`,
    });
    const workoutsJson = JSON.parse((workoutsRead.contents[0] as { text: string }).text);
    expect(workoutsJson.templates.length).toBeGreaterThan(0);

    // Tool 1: list_exercises
    const listRes = await client.callTool({
      name: 'list_exercises',
      arguments: { accessory: 'BAR', muscleGroup: 'Chest' },
    });
    const barChest = JSON.parse((listRes.content as Array<{ text: string }>)[0].text);
    expect(barChest.some((e: { name: string }) => e.name === 'Bench Press')).toBe(true);
    const benchId = barChest.find((e: { name: string }) => e.name === 'Bench Press').id;

    // Tool 2: get_user_strength_history in KG
    const histRes = await client.callTool({
      name: 'get_user_strength_history',
      arguments: {
        userId: DEFAULT_USERS.USER_ONE_ID,
        exerciseId: benchId,
        limit: 5,
        unit: 'KG',
      },
    });
    const histJson = JSON.parse((histRes.content as Array<{ text: string }>)[0].text);
    expect(histJson.unit).toBe('KG');
    expect(histJson.personalRecord.displayUnit).toBeUndefined();
    expect(histJson.personalRecord.displayMaxWeight).toBeGreaterThan(0);

    // Tool 3: create_custom_workout in KG (20 kg -> ~44 lb canonical)
    const createRes = await client.callTool({
      name: 'create_custom_workout',
      arguments: {
        userId: DEFAULT_USERS.USER_ONE_ID,
        name: 'MCP Metric Bench Block',
        description: 'Created over MCP SSE in KG',
        unit: 'KG',
        blocks: [
          {
            blockType: 'STRAIGHT_SET',
            restSeconds: 60,
            items: [
              {
                exerciseId: benchId,
                sets: 3,
                targetReps: 8,
                mode: 'eccentric',
                modifierPct: 25,
                targetWeightLb: 20, // 20 KG -> ~44 LB canonical
              },
            ],
          },
        ],
      },
    });
    const createdInfo = JSON.parse((createRes.content as Array<{ text: string }>)[0].text);
    expect(createdInfo.status).toBe('created');
    createdTemplateId = createdInfo.templateId;

    const stored = await prisma.workoutTemplate.findUnique({
      where: { id: createdTemplateId! },
    });
    const storedBlocks = JSON.parse(stored!.blocksJson);
    expect(storedBlocks[0].items[0].targetWeightLb).toBeCloseTo(44, 0.5);

    // Tool 4: update_workout_template
    const updRes = await client.callTool({
      name: 'update_workout_template',
      arguments: {
        templateId: createdTemplateId!,
        name: 'MCP Updated Bench Block',
      },
    });
    expect(JSON.parse((updRes.content as Array<{ text: string }>)[0].text).status).toBe('updated');

    // Tool 5: queue_workout_for_session -> verify WebSocket client receives workout_queued
    const ws = new WebSocket(wsUrl);
    const queuedEventPromise = new Promise<Record<string, unknown>>((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('WS workout_queued timeout')), 3000);
      ws.on('message', (data) => {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'workout_queued') {
          clearTimeout(timeout);
          resolve(msg);
        }
      });
    });

    await new Promise<void>((resolve) => ws.on('open', () => resolve()));
    const qRes = await client.callTool({
      name: 'queue_workout_for_session',
      arguments: { userId: DEFAULT_USERS.USER_ONE_ID, templateId: createdTemplateId!, durationMs: 0 },
    });
    expect(JSON.parse((qRes.content as Array<{ text: string }>)[0].text).status).toBe('queued');

    const wsEvent = await queuedEventPromise;
    expect((wsEvent.workout as { templateName: string }).templateName).toBe(
      'MCP Updated Bench Block'
    );

    ws.close();
    await client.close();
  });

  it('handles workout sessions, set logging, and Partner Work-In rotation over REST', async () => {
    const healthResp = await fetch(`${baseUrl}/api/health`);
    expect(healthResp.status).toBe(200);

    const exResp = await fetch(`${baseUrl}/api/exercises`);
    const exercises = (await exResp.json()) as Array<{ id: string; name: string }>;
    const bench = exercises.find((e) => e.name === 'Bench Press')!;

    // Enable partner mode via WS
    const ws = new WebSocket(wsUrl);
    await new Promise<void>((resolve) => ws.on('open', () => resolve()));
    ws.send(
      JSON.stringify({
        type: 'set_partner_mode',
        enabled: true,
        partnerUserIds: [DEFAULT_USERS.USER_ONE_ID, DEFAULT_USERS.USER_TWO_ID],
      })
    );
    await new Promise((r) => setTimeout(r, 80));

    const sessResp = await fetch(`${baseUrl}/api/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: DEFAULT_USERS.USER_ONE_ID }),
    });
    expect(sessResp.status).toBe(201);
    const session = (await sessResp.json()) as { id: string };
    createdSessionId = session.id;

    // Log Alex's set 1 -> should automatically rotate active user to Taylor
    const setResp = await fetch(`${baseUrl}/api/sessions/${session.id}/sets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        exerciseId: bench.id,
        setNumber: 1,
        mode: 'standard',
        weightLb: 55,
        repsCompleted: 10,
        rpe: 8,
        restSeconds: 45,
      }),
    });
    expect(setResp.status).toBe(201);
    const setData = (await setResp.json()) as {
      totalVolume: number;
      partnerRotation: { rotatedToUserId: string; targetWeightLb: number } | null;
    };
    expect(setData.totalVolume).toBe(550);
    expect(setData.partnerRotation?.rotatedToUserId).toBe(DEFAULT_USERS.USER_TWO_ID);
    expect(setData.partnerRotation?.targetWeightLb).toBeGreaterThanOrEqual(100);
    expect(sessionCoordinator.getState().activeUserId).toBe(DEFAULT_USERS.USER_TWO_ID);

    ws.close();
  });

  it('connects an MCP client over Streamable HTTP (POST /mcp, 2025-03-26 spec)', async () => {
    const initResp = await fetch(`${baseUrl}/mcp`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json, text/event-stream',
      },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'initialize',
        params: {
          protocolVersion: '2025-03-26',
          capabilities: {},
          clientInfo: { name: 'vitest-streamable', version: '1.0.0' },
        },
      }),
    });
    expect(initResp.status).toBe(200);
    const sessionId = initResp.headers.get('mcp-session-id');
    expect(sessionId).toBeTruthy();

    const listResp = await fetch(`${baseUrl}/mcp`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json, text/event-stream',
        'mcp-session-id': sessionId!,
      },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 2,
        method: 'tools/list',
        params: {},
      }),
    });
    expect(listResp.status).toBe(200);
    const bodyText = await listResp.text();
    expect(bodyText).toContain('list_exercises');
    expect(bodyText).toContain('queue_workout_for_session');

    // Cleanly close session
    const delResp = await fetch(`${baseUrl}/mcp`, {
      method: 'DELETE',
      headers: { 'mcp-session-id': sessionId! },
    });
    expect([200, 204]).toContain(delResp.status);
  });
});

import mqtt, { MqttClient } from 'mqtt';
import {
  getRuntimeSettings,
  PersistedHomeAssistantConfig,
  updateRuntimeSettings,
} from '../services/runtimeSettings';

export interface HomeAssistantEventRecord {
  id: string;
  topic: string;
  payload: Record<string, unknown>;
  mqttDelivered: boolean;
  webhookDelivered: boolean;
  timestamp: string;
}

export interface HomeAssistantStateSnapshot {
  userId: string;
  userName?: string;
  accentColor?: string;
  themeMode?: string;
  weightUnit?: string;
  partnerModeEnabled: boolean;
  weightEnabled: boolean;
  targetWeightLb: number;
  mode: string;
  accessory: string;
  repCount: number;
  safetyFault?: string | null;
}

export interface HomeAssistantMediaPlayerInfo {
  entityId: string;
  friendlyName: string;
  state: string;
  volumePct: number | null;
}

class HomeAssistantBus {
  private client: MqttClient | null = null;
  private mqttConnected = false;
  private discoveryPublished = false;
  private lastMqttError: string | null = null;
  private lastStateSignature = '';
  private readonly recentEvents: HomeAssistantEventRecord[] = [];

  private get persisted(): PersistedHomeAssistantConfig {
    return getRuntimeSettings().homeAssistant ?? {};
  }

  get topicPrefix(): string {
    return (this.persisted.mqttTopicPrefix || process.env.MQTT_TOPIC_PREFIX || 'swonal')
      .trim()
      .replace(/\/+$/, '');
  }

  get brokerUrl(): string | undefined {
    return (this.persisted.mqttBrokerUrl || process.env.MQTT_BROKER_URL || '').trim() || undefined;
  }

  private get mqttUsername(): string | undefined {
    return (this.persisted.mqttUsername || process.env.MQTT_USERNAME || '').trim() || undefined;
  }

  private get mqttPassword(): string | undefined {
    return (this.persisted.mqttPassword || process.env.MQTT_PASSWORD || '').trim() || undefined;
  }

  private get mqttDiscoveryEnabled(): boolean {
    return this.persisted.mqttDiscoveryEnabled ?? true;
  }

  get webhookUrl(): string | undefined {
    return (this.persisted.webhookUrl || process.env.HA_WEBHOOK_URL || '').trim() || undefined;
  }

  private get webhookToken(): string | undefined {
    return (this.persisted.webhookToken || process.env.HA_WEBHOOK_TOKEN || '').trim() || undefined;
  }

  get haUrl(): string | undefined {
    return (this.persisted.haUrl || process.env.HA_URL || '').trim().replace(/\/+$/, '') || undefined;
  }

  get haToken(): string | undefined {
    return (this.persisted.haToken || process.env.HA_TOKEN || '').trim() || undefined;
  }

  private publishDiscoveryConfigs(): void {
    if (!this.client || !this.mqttConnected || !this.mqttDiscoveryEnabled) return;
    const stateTopic = `${this.topicPrefix}/state`;
    const device = {
      identifiers: ['swonal_tonal_v1'],
      name: 'Swonal Smart Gym',
      manufacturer: 'Swonal',
      model: 'Tonal V1',
    };

    const entities: Array<{ component: 'sensor' | 'binary_sensor'; id: string; cfg: Record<string, unknown> }> = [
      {
        component: 'binary_sensor',
        id: 'swonal_weight_engaged',
        cfg: {
          name: 'Weight Engaged',
          unique_id: 'swonal_weight_engaged',
          state_topic: stateTopic,
          value_template: '{{ "ON" if value_json.weightEnabled else "OFF" }}',
          device_class: 'power',
          device,
        },
      },
      {
        component: 'sensor',
        id: 'swonal_active_lifter',
        cfg: {
          name: 'Active Lifter',
          unique_id: 'swonal_active_lifter',
          state_topic: stateTopic,
          value_template: '{{ value_json.userName | default(value_json.userId) }}',
          icon: 'mdi:account',
          device,
        },
      },
      {
        component: 'sensor',
        id: 'swonal_target_weight',
        cfg: {
          name: 'Target Weight',
          unique_id: 'swonal_target_weight',
          state_topic: stateTopic,
          value_template: '{{ value_json.targetWeightLb }}',
          unit_of_measurement: 'lb',
          icon: 'mdi:dumbbell',
          device,
        },
      },
      {
        component: 'sensor',
        id: 'swonal_rep_count',
        cfg: {
          name: 'Current Set Reps',
          unique_id: 'swonal_rep_count',
          state_topic: stateTopic,
          value_template: '{{ value_json.repCount }}',
          icon: 'mdi:counter',
          device,
        },
      },
      {
        component: 'sensor',
        id: 'swonal_resistance_mode',
        cfg: {
          name: 'Resistance Mode',
          unique_id: 'swonal_resistance_mode',
          state_topic: stateTopic,
          value_template: '{{ value_json.mode }}',
          icon: 'mdi:tune',
          device,
        },
      },
      {
        component: 'sensor',
        id: 'swonal_safety_fault',
        cfg: {
          name: 'Safety Fault',
          unique_id: 'swonal_safety_fault',
          state_topic: stateTopic,
          value_template: '{{ value_json.safetyFault | default("none") }}',
          icon: 'mdi:shield-alert',
          device,
        },
      },
    ];

    for (const item of entities) {
      const discoveryTopic = `homeassistant/${item.component}/${item.id}/config`;
      this.client.publish(discoveryTopic, JSON.stringify(item.cfg), { qos: 0, retain: true });
    }
    this.discoveryPublished = true;
  }

  init(): void {
    const broker = this.brokerUrl;
    if (!broker || this.client) return;

    try {
      this.client = mqtt.connect(broker, {
        username: this.mqttUsername,
        password: this.mqttPassword,
        reconnectPeriod: 5000,
        connectTimeout: 4000,
      });

      this.client.on('connect', () => {
        this.mqttConnected = true;
        this.lastMqttError = null;
        this.publishDiscoveryConfigs();
      });
      this.client.on('close', () => {
        this.mqttConnected = false;
      });
      this.client.on('error', (err) => {
        this.mqttConnected = false;
        this.lastMqttError = err.message;
      });
    } catch (err) {
      this.lastMqttError = err instanceof Error ? err.message : String(err);
    }
  }

  async setConfig(patch: Partial<PersistedHomeAssistantConfig>): Promise<void> {
    const current = this.persisted;
    const next: PersistedHomeAssistantConfig = {
      mqttBrokerUrl: patch.mqttBrokerUrl !== undefined ? patch.mqttBrokerUrl.trim() || undefined : current.mqttBrokerUrl,
      mqttUsername: patch.mqttUsername !== undefined ? patch.mqttUsername.trim() || undefined : current.mqttUsername,
      mqttPassword:
        patch.mqttPassword !== undefined && patch.mqttPassword.trim()
          ? patch.mqttPassword.trim()
          : current.mqttPassword,
      mqttTopicPrefix:
        patch.mqttTopicPrefix !== undefined
          ? patch.mqttTopicPrefix.trim().replace(/\/+$/, '') || 'swonal'
          : current.mqttTopicPrefix,
      mqttDiscoveryEnabled:
        patch.mqttDiscoveryEnabled !== undefined
          ? Boolean(patch.mqttDiscoveryEnabled)
          : (current.mqttDiscoveryEnabled ?? true),
      webhookUrl: patch.webhookUrl !== undefined ? patch.webhookUrl.trim() || undefined : current.webhookUrl,
      webhookToken:
        patch.webhookToken !== undefined && patch.webhookToken.trim()
          ? patch.webhookToken.trim()
          : current.webhookToken,
      haUrl: patch.haUrl !== undefined ? patch.haUrl.trim().replace(/\/+$/, '') || undefined : current.haUrl,
      haToken:
        patch.haToken !== undefined && patch.haToken.trim()
          ? patch.haToken.trim()
          : current.haToken,
    };

    updateRuntimeSettings({ homeAssistant: next });
    await this.close();
    this.lastMqttError = null;
    this.discoveryPublished = false;
    this.init();
  }

  async listMediaPlayers(): Promise<HomeAssistantMediaPlayerInfo[]> {
    const url = this.haUrl;
    const token = this.haToken;
    if (!url || !token) return [];
    const res = await fetch(`${url}/api/states`, {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      signal: AbortSignal.timeout(4000),
    });
    if (!res.ok) {
      throw new Error(`Home Assistant HTTP ${res.status}`);
    }
    const states = (await res.json()) as Array<{
      entity_id: string;
      state: string;
      attributes?: { friendly_name?: string; volume_level?: number };
    }>;
    return states
      .filter((s) => s.entity_id.startsWith('media_player.'))
      .map((s) => ({
        entityId: s.entity_id,
        friendlyName: s.attributes?.friendly_name || s.entity_id,
        state: s.state,
        volumePct:
          typeof s.attributes?.volume_level === 'number'
            ? Math.round(s.attributes.volume_level * 100)
            : null,
      }))
      .sort((a, b) => a.friendlyName.localeCompare(b.friendlyName));
  }

  private recordEvent(record: HomeAssistantEventRecord): void {
    this.recentEvents.unshift(record);
    if (this.recentEvents.length > 25) {
      this.recentEvents.length = 25;
    }
  }

  private async dispatch(topicSuffix: string, payload: Record<string, unknown>): Promise<HomeAssistantEventRecord> {
    this.init();
    const topic = `${this.topicPrefix}/${topicSuffix}`;
    const timestamp = new Date().toISOString();
    const enriched = { ...payload, topic, timestamp };

    let mqttDelivered = false;
    if (this.client && this.mqttConnected) {
      this.client.publish(topic, JSON.stringify(enriched), { qos: 0, retain: topicSuffix === 'state' });
      mqttDelivered = true;
    }

    let webhookDelivered = false;
    const webhook = this.webhookUrl;
    if (webhook) {
      try {
        const headers: Record<string, string> = { 'Content-Type': 'application/json' };
        if (this.webhookToken) {
          headers.Authorization = `Bearer ${this.webhookToken}`;
        }
        const res = await fetch(webhook, {
          method: 'POST',
          headers,
          body: JSON.stringify(enriched),
          signal: AbortSignal.timeout(2500),
        });
        webhookDelivered = res.ok;
      } catch {
        webhookDelivered = false;
      }
    }

    const record: HomeAssistantEventRecord = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      topic,
      payload: enriched,
      mqttDelivered,
      webhookDelivered,
      timestamp,
    };
    this.recordEvent(record);
    return record;
  }

  publishStateIfChanged(snapshot: HomeAssistantStateSnapshot): void {
    const signature = JSON.stringify([
      snapshot.userId,
      snapshot.accentColor,
      snapshot.themeMode,
      snapshot.partnerModeEnabled,
      snapshot.weightEnabled,
      snapshot.targetWeightLb,
      snapshot.mode,
      snapshot.accessory,
      snapshot.repCount,
      snapshot.safetyFault ?? null,
    ]);
    if (signature === this.lastStateSignature) return;
    this.lastStateSignature = signature;

    void this.dispatch('state', { ...snapshot });
  }

  async publishSessionEvent(
    event: string,
    data: Record<string, unknown> = {}
  ): Promise<HomeAssistantEventRecord> {
    return this.dispatch('session', { event, ...data });
  }

  getStatus() {
    return {
      mqttConfigured: Boolean(this.brokerUrl),
      mqttConnected: this.mqttConnected,
      mqttBrokerUrl: this.brokerUrl ?? '',
      mqttUsername: this.mqttUsername ?? '',
      hasMqttPassword: Boolean(this.mqttPassword),
      mqttTopicPrefix: this.topicPrefix,
      mqttDiscoveryEnabled: this.mqttDiscoveryEnabled,
      discoveryPublished: this.discoveryPublished,
      lastMqttError: this.lastMqttError,
      webhookConfigured: Boolean(this.webhookUrl),
      webhookUrl: this.webhookUrl ?? '',
      hasWebhookToken: Boolean(this.webhookToken),
      haRestConfigured: Boolean(this.haUrl && this.haToken),
      haUrl: this.haUrl ?? '',
      hasHaToken: Boolean(this.haToken),
      recentEvents: this.recentEvents,
    };
  }

  async close(): Promise<void> {
    if (this.client) {
      await new Promise<void>((resolve) => {
        this.client?.end(true, {}, () => resolve());
      });
      this.client = null;
      this.mqttConnected = false;
      this.discoveryPublished = false;
    }
  }
}

export const homeAssistantBus = new HomeAssistantBus();

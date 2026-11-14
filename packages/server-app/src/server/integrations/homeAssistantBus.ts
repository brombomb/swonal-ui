import mqtt, { MqttClient } from 'mqtt';

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

class HomeAssistantBus {
  private client: MqttClient | null = null;
  private mqttConnected = false;
  private lastMqttError: string | null = null;
  private lastStateSignature = '';
  private readonly recentEvents: HomeAssistantEventRecord[] = [];

  private get topicPrefix(): string {
    return (process.env.MQTT_TOPIC_PREFIX || 'swonal').replace(/\/+$/, '');
  }

  private get brokerUrl(): string | undefined {
    return process.env.MQTT_BROKER_URL?.trim() || undefined;
  }

  private get webhookUrl(): string | undefined {
    return process.env.HA_WEBHOOK_URL?.trim() || undefined;
  }

  init(): void {
    const broker = this.brokerUrl;
    if (!broker || this.client) return;

    try {
      this.client = mqtt.connect(broker, {
        username: process.env.MQTT_USERNAME || undefined,
        password: process.env.MQTT_PASSWORD || undefined,
        reconnectPeriod: 5000,
        connectTimeout: 4000,
      });

      this.client.on('connect', () => {
        this.mqttConnected = true;
        this.lastMqttError = null;
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
        if (process.env.HA_WEBHOOK_TOKEN) {
          headers.Authorization = `Bearer ${process.env.HA_WEBHOOK_TOKEN}`;
        }
        const res = await fetch(webhook, {
          method: 'POST',
          headers,
          body: JSON.stringify(enriched),
          signal: AbortSignal.timeout(2000),
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
      mqttTopicPrefix: this.topicPrefix,
      lastMqttError: this.lastMqttError,
      webhookConfigured: Boolean(this.webhookUrl),
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
    }
  }
}

export const homeAssistantBus = new HomeAssistantBus();

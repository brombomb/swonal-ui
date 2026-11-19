import { prisma } from '../db';
import { formatWeight, WeightUnit } from '../utils/units';
import { getRuntimeSettings, updateRuntimeSettings } from './runtimeSettings';

export type AiProviderId = 'local' | 'openai' | 'anthropic' | 'gemini' | 'ollama';

export interface AiCoachConfig {
  provider: AiProviderId;
  model: string;
  apiKey?: string;
  baseUrl?: string;
  clearApiKey?: boolean;
}

export interface CoachAskRequest {
  userId: string;
  prompt: string;
  exerciseName?: string;
  currentWeightLb?: number;
  repCount?: number;
  meanVelocity?: number;
  velocityDropPct?: number;
}

export interface CoachAskResponse {
  provider: AiProviderId;
  model: string;
  reply: string;
  fallbackReason?: string | null;
  suggestedAction?: {
    type: 'set_weight_delta' | 'toggle_weight' | 'skip_rest';
    deltaLb?: number;
    label: string;
  } | null;
}

export interface CoachTestResult {
  ok: boolean;
  provider: AiProviderId;
  model: string;
  latencyMs: number;
  reply?: string;
  error?: string;
}

export const DEFAULT_COACH_MODELS: Record<AiProviderId, string> = {
  local: 'swonal-local-coach-v1',
  openai: 'gpt-4o-mini',
  anthropic: 'claude-3-5-haiku-latest',
  gemini: 'gemini-2.5-flash',
  ollama: 'llama3.2',
};

function sanitizeHttpBaseUrl(raw: string | undefined): string {
  const trimmed = (raw ?? '').trim().replace(/\/+$/, '');
  if (!trimmed) return '';
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      throw new Error('Base URL must use http:// or https://');
    }
    return trimmed;
  } catch {
    throw new Error('Base URL must be a valid http:// or https:// URL');
  }
}

class AiCoachService {
  private provider: AiProviderId = 'local';
  private model: string = DEFAULT_COACH_MODELS.local;
  private apiKey: string = '';
  private baseUrl: string = '';
  private hydrated = false;

  private ensureHydrated(): void {
    if (this.hydrated) return;
    this.hydrated = true;

    const saved = getRuntimeSettings().aiCoach;
    if (saved) {
      this.provider = saved.provider;
      this.model = saved.model || DEFAULT_COACH_MODELS[saved.provider];
      this.apiKey = saved.apiKey ?? '';
      this.baseUrl = saved.baseUrl ?? '';
      return;
    }

    if (process.env.OPENAI_API_KEY) {
      this.provider = 'openai';
      this.model = DEFAULT_COACH_MODELS.openai;
      this.apiKey = process.env.OPENAI_API_KEY;
    } else if (process.env.ANTHROPIC_API_KEY) {
      this.provider = 'anthropic';
      this.model = DEFAULT_COACH_MODELS.anthropic;
      this.apiKey = process.env.ANTHROPIC_API_KEY;
    } else if (process.env.GEMINI_API_KEY) {
      this.provider = 'gemini';
      this.model = DEFAULT_COACH_MODELS.gemini;
      this.apiKey = process.env.GEMINI_API_KEY;
    } else if (process.env.OLLAMA_URL) {
      this.provider = 'ollama';
      this.model = DEFAULT_COACH_MODELS.ollama;
      this.baseUrl = process.env.OLLAMA_URL;
    }
  }

  reloadFromSettings(): void {
    this.hydrated = false;
    this.provider = 'local';
    this.model = DEFAULT_COACH_MODELS.local;
    this.apiKey = '';
    this.baseUrl = '';
    this.ensureHydrated();
  }

  setConfig(cfg: Partial<AiCoachConfig>): void {
    this.ensureHydrated();
    if (cfg.provider) {
      const providerChanged = cfg.provider !== this.provider;
      this.provider = cfg.provider;
      if (!cfg.model || (providerChanged && !cfg.model.trim())) {
        this.model = DEFAULT_COACH_MODELS[cfg.provider];
      }
    }
    if (cfg.model !== undefined && cfg.model.trim()) {
      this.model = cfg.model.trim();
    }
    if (cfg.clearApiKey) {
      this.apiKey = '';
    } else if (cfg.apiKey !== undefined && cfg.apiKey.trim()) {
      this.apiKey = cfg.apiKey.trim();
    }
    if (cfg.baseUrl !== undefined) {
      this.baseUrl = sanitizeHttpBaseUrl(cfg.baseUrl);
    }

    updateRuntimeSettings({
      aiCoach: {
        provider: this.provider,
        model: this.model,
        apiKey: this.apiKey || undefined,
        baseUrl: this.baseUrl || undefined,
      },
    });
  }

  getPublicConfig() {
    this.ensureHydrated();
    return {
      provider: this.provider,
      model: this.model,
      hasApiKey: Boolean(this.apiKey),
      keyHint: this.apiKey ? `••••${this.apiKey.slice(-4)}` : null,
      baseUrl: this.baseUrl || (this.provider === 'ollama' ? 'http://localhost:11434' : null),
      defaultModels: DEFAULT_COACH_MODELS,
      supportedProviders: [
        { id: 'local', label: 'Built-In Local Coach (No Key)' },
        { id: 'openai', label: 'OpenAI (BYOK)' },
        { id: 'anthropic', label: 'Anthropic Claude (BYOK)' },
        { id: 'gemini', label: 'Google Gemini (BYOK)' },
        { id: 'ollama', label: 'Ollama (Local LLM)' },
      ],
    };
  }

  private detectVoiceCommand(prompt: string): CoachAskResponse['suggestedAction'] {
    const q = prompt.toLowerCase();
    const addMatch = q.match(/(?:add|increase|up)\s+(\d+)\s*(?:lb|lbs|pound|pounds)?/);
    if (addMatch) {
      const delta = Number(addMatch[1]);
      return { type: 'set_weight_delta', deltaLb: delta, label: `Add +${delta} lb` };
    }
    const dropMatch = q.match(/(?:drop|decrease|lower|reduce)\s+(\d+)\s*(?:lb|lbs|pound|pounds)?/);
    if (dropMatch) {
      const delta = -Number(dropMatch[1]);
      return { type: 'set_weight_delta', deltaLb: delta, label: `Drop ${delta} lb` };
    }
    if (q.includes('skip rest') || q.includes('next set')) {
      return { type: 'skip_rest', label: 'Skip Rest Timer' };
    }
    if (
      q.includes('weight off') ||
      q.includes('stop weight') ||
      q.includes('weight on') ||
      q.includes('start weight')
    ) {
      return { type: 'toggle_weight', label: 'Toggle Weight' };
    }
    return null;
  }

  private async callExternalProvider(
    systemPrompt: string,
    contextSummary: string,
    userPrompt: string,
    timeoutMs = 10000
  ): Promise<{ reply?: string; error?: string }> {
    if (this.provider === 'openai') {
      if (!this.apiKey) return { error: 'OpenAI API key is required' };
      const endpoint = this.baseUrl
        ? `${this.baseUrl}/chat/completions`
        : 'https://api.openai.com/v1/chat/completions';
      const res = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${this.apiKey}`,
        },
        body: JSON.stringify({
          model: this.model,
          messages: [
            { role: 'system', content: `${systemPrompt}\nContext: ${contextSummary}` },
            { role: 'user', content: userPrompt },
          ],
          max_tokens: 180,
        }),
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (!res.ok) {
        const body = await res.text().catch(() => '');
        return { error: `OpenAI HTTP ${res.status}: ${body.slice(0, 160)}` };
      }
      const data = (await res.json()) as { choices?: Array<{ message?: { content?: string } }> };
      const text = data.choices?.[0]?.message?.content?.trim();
      return text ? { reply: text } : { error: 'OpenAI returned an empty reply' };
    }

    if (this.provider === 'anthropic') {
      if (!this.apiKey) return { error: 'Anthropic API key is required' };
      const endpoint = this.baseUrl
        ? `${this.baseUrl}/v1/messages`
        : 'https://api.anthropic.com/v1/messages';
      const res = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-api-key': this.apiKey,
          'anthropic-version': '2023-06-01',
        },
        body: JSON.stringify({
          model: this.model,
          max_tokens: 180,
          system: `${systemPrompt}\nContext: ${contextSummary}`,
          messages: [{ role: 'user', content: userPrompt }],
        }),
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (!res.ok) {
        const body = await res.text().catch(() => '');
        return { error: `Anthropic HTTP ${res.status}: ${body.slice(0, 160)}` };
      }
      const data = (await res.json()) as { content?: Array<{ text?: string }> };
      const text = data.content?.[0]?.text?.trim();
      return text ? { reply: text } : { error: 'Anthropic returned an empty reply' };
    }

    if (this.provider === 'gemini') {
      if (!this.apiKey) return { error: 'Gemini API key is required' };
      const res = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(this.model)}:generateContent?key=${encodeURIComponent(this.apiKey)}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            contents: [
              {
                parts: [
                  { text: `${systemPrompt}\nContext: ${contextSummary}\nUser: ${userPrompt}` },
                ],
              },
            ],
          }),
          signal: AbortSignal.timeout(timeoutMs),
        }
      );
      if (!res.ok) {
        const body = await res.text().catch(() => '');
        return { error: `Gemini HTTP ${res.status}: ${body.slice(0, 160)}` };
      }
      const data = (await res.json()) as {
        candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
      };
      const text = data.candidates?.[0]?.content?.parts?.[0]?.text?.trim();
      return text ? { reply: text } : { error: 'Gemini returned an empty reply' };
    }

    if (this.provider === 'ollama') {
      const url = (this.baseUrl || 'http://localhost:11434').replace(/\/$/, '');
      const res = await fetch(`${url}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: this.model,
          stream: false,
          messages: [
            { role: 'system', content: `${systemPrompt}\nContext: ${contextSummary}` },
            { role: 'user', content: userPrompt },
          ],
        }),
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (!res.ok) {
        const body = await res.text().catch(() => '');
        return { error: `Ollama HTTP ${res.status}: ${body.slice(0, 160)}` };
      }
      const data = (await res.json()) as { message?: { content?: string } };
      const text = data.message?.content?.trim();
      return text ? { reply: text } : { error: 'Ollama returned an empty reply' };
    }

    return {};
  }

  async testConnection(): Promise<CoachTestResult> {
    this.ensureHydrated();
    const start = Date.now();
    if (this.provider === 'local') {
      return {
        ok: true,
        provider: 'local',
        model: this.model,
        latencyMs: Date.now() - start,
        reply: 'Built-in Local Heuristic Coach is ready offline (PR lookups, VBT fatigue cues, and voice commands active).',
      };
    }

    try {
      const result = await this.callExternalProvider(
        'You are Swonal Coach. Reply in one short sentence confirming connection.',
        'Connection self-test from Swonal Settings.',
        'Respond with "Swonal Coach ready."'
      );
      const latencyMs = Date.now() - start;
      if (result.reply) {
        return {
          ok: true,
          provider: this.provider,
          model: this.model,
          latencyMs,
          reply: result.reply,
        };
      }
      return {
        ok: false,
        provider: this.provider,
        model: this.model,
        latencyMs,
        error: result.error ?? 'Unknown AI provider error',
      };
    } catch (err) {
      return {
        ok: false,
        provider: this.provider,
        model: this.model,
        latencyMs: Date.now() - start,
        error: err instanceof Error ? err.message : String(err),
      };
    }
  }

  async ask(req: CoachAskRequest): Promise<CoachAskResponse> {
    this.ensureHydrated();
    const user = await prisma.user.findUnique({ where: { id: req.userId } });
    const unit = (user?.weightUnit === 'KG' ? 'KG' : 'LB') as WeightUnit;
    const prs = await prisma.personalRecord.findMany({
      where: { userId: req.userId },
      include: { exercise: { select: { name: true } } },
      orderBy: { updatedAt: 'desc' },
      take: 6,
    });
    const recentSessions = await prisma.workoutSession.findMany({
      where: { userId: req.userId },
      orderBy: { startedAt: 'desc' },
      take: 3,
      include: { template: { select: { name: true } } },
    });

    const suggestedAction = this.detectVoiceCommand(req.prompt);

    const contextSummary = [
      `Lifter: ${user?.name ?? 'Athlete'} (${unit}).`,
      req.exerciseName ? `Active exercise: ${req.exerciseName}.` : '',
      req.currentWeightLb !== undefined
        ? `Current target weight: ${formatWeight(req.currentWeightLb, unit)} ${unit}.`
        : '',
      req.repCount !== undefined ? `Current set reps: ${req.repCount}.` : '',
      req.velocityDropPct !== undefined && req.velocityDropPct > 0
        ? `Live velocity drop: ${req.velocityDropPct.toFixed(0)}%.`
        : '',
      prs.length > 0
        ? `Top PRs: ${prs
            .map((p) => `${p.exercise.name}: ${formatWeight(p.oneRepMaxEst, unit)} ${unit} 1RM`)
            .join('; ')}.`
        : 'No PRs logged yet.',
      recentSessions.length > 0
        ? `Recent sessions: ${recentSessions
            .map((s) => `${s.template?.name ?? 'Free Lift'} (${formatWeight(s.totalVolume, unit)} ${unit} vol)`)
            .join('; ')}.`
        : '',
    ]
      .filter(Boolean)
      .join(' ');

    const systemPrompt =
      'You are Swonal Coach, a concise, motivating strength coach on a smart cable gym screen. Keep replies under 3 short sentences so they sound great spoken aloud between sets. Always match the user unit.';

    let fallbackReason: string | null = null;
    if (this.provider !== 'local') {
      try {
        const external = await this.callExternalProvider(systemPrompt, contextSummary, req.prompt);
        if (external.reply) {
          return {
            provider: this.provider,
            model: this.model,
            reply: external.reply,
            fallbackReason: null,
            suggestedAction,
          };
        }
        fallbackReason = external.error ?? `${this.provider} did not return a response`;
      } catch (err) {
        fallbackReason = err instanceof Error ? err.message : String(err);
      }
    }

    // Built-in Local Heuristic Coach (works completely offline)
    if (suggestedAction) {
      return {
        provider: 'local',
        model: DEFAULT_COACH_MODELS.local,
        reply: `Got it, ${user?.name ?? 'athlete'} — ready to ${suggestedAction.label.toLowerCase()}.`,
        fallbackReason,
        suggestedAction,
      };
    }

    const q = req.prompt.toLowerCase();
    if (q.includes('pr') || q.includes('record') || q.includes('best')) {
      const top = prs[0];
      const msg = top
        ? `${user?.name ?? 'Your'} top recorded PR is ${top.exercise.name} at ${formatWeight(top.oneRepMaxEst, unit)} ${unit} estimated 1RM.`
        : `You haven't logged a PR yet—complete a working set to set your baseline.`;
      return {
        provider: 'local',
        model: DEFAULT_COACH_MODELS.local,
        reply: msg,
        fallbackReason,
        suggestedAction: null,
      };
    }

    if (req.velocityDropPct !== undefined && req.velocityDropPct >= 25) {
      return {
        provider: 'local',
        model: DEFAULT_COACH_MODELS.local,
        reply: `Velocity dropped ${req.velocityDropPct.toFixed(0)}% on ${req.exerciseName ?? 'this set'}—rack the weight or switch to Spotter mode to protect form.`,
        fallbackReason,
        suggestedAction: null,
      };
    }

    const lastVol = recentSessions[0]?.totalVolume ?? 0;
    return {
      provider: 'local',
      model: DEFAULT_COACH_MODELS.local,
      reply: `${user?.name ?? 'Athlete'}, you're set at ${formatWeight(req.currentWeightLb ?? 45, unit)} ${unit}${req.exerciseName ? ` on ${req.exerciseName}` : ''}. Last session volume was ${formatWeight(lastVol, unit)} ${unit}—keep eccentric tempo smooth and brace your core.`,
      fallbackReason,
      suggestedAction: null,
    };
  }
}

export const aiCoachService = new AiCoachService();

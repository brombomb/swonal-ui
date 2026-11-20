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

export type CoachVoiceMode = 'STANDARD' | 'ECCENTRIC' | 'CHAINS' | 'SPOTTER' | 'BURNOUT';

export interface CoachSuggestedAction {
  type:
    | 'set_weight_delta'
    | 'set_weight_target'
    | 'set_mode'
    | 'next_exercise'
    | 'prev_exercise'
    | 'select_exercise'
    | 'skip_rest'
    | 'complete_set'
    | 'set_weight_enabled'
    | 'toggle_weight';
  deltaLb?: number;
  targetLb?: number;
  mode?: CoachVoiceMode;
  exerciseQuery?: string;
  enabled?: boolean;
  label: string;
}

export interface CoachAskResponse {
  provider: AiProviderId;
  model: string;
  reply: string;
  fallbackReason?: string | null;
  suggestedAction?: CoachSuggestedAction | null;
}

export interface CoachTestResult {
  ok: boolean;
  provider: AiProviderId;
  model: string;
  latencyMs: number;
  reply?: string;
  error?: string;
}

const NUMBER_WORDS: Record<string, number> = {
  one: 1,
  two: 2,
  three: 3,
  four: 4,
  five: 5,
  six: 6,
  seven: 7,
  eight: 8,
  nine: 9,
  ten: 10,
  eleven: 11,
  twelve: 12,
  fifteen: 15,
  twenty: 20,
  'twenty five': 25,
  'twenty-five': 25,
  thirty: 30,
  'thirty five': 35,
  'thirty-five': 35,
  forty: 40,
  'forty five': 45,
  'forty-five': 45,
  fifty: 50,
  'fifty five': 55,
  'fifty-five': 55,
  sixty: 60,
  'sixty five': 65,
  'sixty-five': 65,
  seventy: 70,
  'seventy five': 75,
  'seventy-five': 75,
  eighty: 80,
  'eighty five': 85,
  'eighty-five': 85,
  ninety: 90,
  'ninety five': 95,
  'ninety-five': 95,
  hundred: 100,
  'one hundred': 100,
};

function parseSpokenNumber(token: string): number | null {
  const cleaned = token.trim().toLowerCase();
  if (!cleaned) return null;
  if (NUMBER_WORDS[cleaned] !== undefined) {
    return NUMBER_WORDS[cleaned];
  }
  const asNum = Number(cleaned);
  return !Number.isNaN(asNum) && asNum > 0 ? asNum : null;
}

const NUMBER_TOKEN_PATTERN =
  '(?:\\d+(?:\\.\\d+)?|twenty[-\\s]five|thirty[-\\s]five|forty[-\\s]five|fifty[-\\s]five|sixty[-\\s]five|seventy[-\\s]five|eighty[-\\s]five|ninety[-\\s]five|one\\s+hundred|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred)';

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

  detectVoiceCommand(prompt: string): CoachAskResponse['suggestedAction'] {
    const q = prompt.trim().toLowerCase().replace(/[.!?]+$/g, '');

    // 1. Weight adjustments (delta up / down with digits or number words)
    const addRegex = new RegExp(
      `(?:add|increase(?:\\s+weight)?(?:\\s+by)?|plus|up(?:\\s+by)?|bump(?:\\s+up)?(?:\\s+by)?|heavier(?:\\s+by)?|raise(?:\\s+weight)?(?:\\s+by)?)\\s+(${NUMBER_TOKEN_PATTERN})\\s*(?:lb|lbs|pound|pounds|kg|kilos)?`,
      'i'
    );
    const addMatch = q.match(addRegex);
    if (addMatch) {
      const delta = parseSpokenNumber(addMatch[1]);
      if (delta !== null) {
        return { type: 'set_weight_delta', deltaLb: delta, label: `Add +${delta} lb` };
      }
    }

    const dropRegex = new RegExp(
      `(?:drop|decrease(?:\\s+weight)?(?:\\s+by)?|lower(?:\\s+weight)?(?:\\s+by)?|reduce(?:\\s+weight)?(?:\\s+by)?|minus|subtract|down(?:\\s+by)?|lighter(?:\\s+by)?|remove)\\s+(${NUMBER_TOKEN_PATTERN})\\s*(?:lb|lbs|pound|pounds|kg|kilos)?`,
      'i'
    );
    const dropMatch = q.match(dropRegex);
    if (dropMatch) {
      const delta = parseSpokenNumber(dropMatch[1]);
      if (delta !== null) {
        return { type: 'set_weight_delta', deltaLb: -delta, label: `Drop -${delta} lb` };
      }
    }

    // 2. Absolute weight target ("set weight to 50 pounds", "make it 45 lbs", "set weight 60", "weight to 35")
    const targetRegex = new RegExp(
      `(?:set\\s+(?:the\\s+)?weight\\s+(?:to\\s+)?|change\\s+(?:the\\s+)?weight\\s+to\\s+|make\\s+(?:it|the\\s+weight)\\s+|weight\\s+to\\s+)(${NUMBER_TOKEN_PATTERN})\\s*(?:lb|lbs|pound|pounds|kg|kilos)?`,
      'i'
    );
    const targetMatch = q.match(targetRegex);
    if (targetMatch) {
      const targetLb = parseSpokenNumber(targetMatch[1]);
      if (targetLb !== null) {
        return {
          type: 'set_weight_target',
          targetLb,
          label: `Set Weight to ${targetLb} lb`,
        };
      }
    }

    // 3. Workout modes ("switch to chains mode", "eccentric mode", "turn on spotter", "burnout mode", "standard mode")
    if (/\b(?:eccentric\s+mode|switch\s+to\s+eccentric|turn\s+on\s+eccentric|enable\s+eccentric)\b/.test(q)) {
      return { type: 'set_mode', mode: 'ECCENTRIC', label: 'Switch to Eccentric Mode' };
    }
    if (/\b(?:chains?\s+mode|switch\s+to\s+chains?|turn\s+on\s+chains?|enable\s+chains?)\b/.test(q)) {
      return { type: 'set_mode', mode: 'CHAINS', label: 'Switch to Chains Mode' };
    }
    if (/\b(?:spotter\s+mode|switch\s+to\s+spotter|turn\s+on\s+spotter|enable\s+spotter)\b/.test(q)) {
      return { type: 'set_mode', mode: 'SPOTTER', label: 'Switch to Spotter Mode' };
    }
    if (/\b(?:burnout\s+mode|switch\s+to\s+burnout|turn\s+on\s+burnout|enable\s+burnout)\b/.test(q)) {
      return { type: 'set_mode', mode: 'BURNOUT', label: 'Switch to Burnout Mode' };
    }
    if (
      /\b(?:standard\s+mode|normal\s+mode|switch\s+to\s+standard|switch\s+to\s+normal|turn\s+on\s+standard)\b/.test(
        q
      )
    ) {
      return { type: 'set_mode', mode: 'STANDARD', label: 'Switch to Standard Mode' };
    }

    // 4. Workouts & Routine / Exercise control
    if (
      /\b(?:next\s+exercise|next\s+workout|next\s+movement|skip\s+exercise|skip\s+movement)\b/.test(q)
    ) {
      return { type: 'next_exercise', label: 'Next Exercise' };
    }
    if (
      /\b(?:prev(?:ious)?\s+exercise|prev(?:ious)?\s+workout|prev(?:ious)?\s+movement|last\s+exercise|go\s+back\s+an?\s+exercise)\b/.test(
        q
      )
    ) {
      return { type: 'prev_exercise', label: 'Previous Exercise' };
    }

    // Specific exercise selection ("switch to Barbell Bicep Curl", "change workout to Bench Press", "do Lat Pulldown")
    const selectExMatch = prompt
      .trim()
      .replace(/[.!?]+$/g, '')
      .match(
        /^(?:switch\s+(?:exercise\s+|workout\s+|movement\s+)?to|change\s+(?:exercise\s+|workout\s+|movement\s+)?to|select\s+exercise|load\s+exercise|start\s+exercise|do)\s+(.+)$/i
      );
    if (selectExMatch) {
      const candidate = selectExMatch[1].trim();
      const lowerCand = candidate.toLowerCase();
      const isModeOrWeight =
        /\b(?:standard|normal|eccentric|chains?|spotter|burnout)\b/.test(lowerCand) ||
        /^\d+\s*(?:lb|lbs|pound|pounds|kg)?$/.test(lowerCand);
      if (candidate.length >= 2 && !isModeOrWeight) {
        return {
          type: 'select_exercise',
          exerciseQuery: candidate,
          label: `Switch to ${candidate}`,
        };
      }
    }

    // 5. Rest & Set completion
    if (
      q.includes('skip rest') ||
      q.includes('end rest') ||
      q.includes('start set now') ||
      q.includes('next set')
    ) {
      return { type: 'skip_rest', label: 'Skip Rest Timer' };
    }

    if (
      /\b(?:complete\s+set|log\s+set|finish\s+set|done\s+with\s+set|end\s+set)\b/.test(q)
    ) {
      return { type: 'complete_set', label: 'Complete & Log Set' };
    }

    // 6. Weight Enable / Disable / Toggle
    if (/\b(?:toggle\s+weight)\b/.test(q)) {
      return { type: 'toggle_weight', label: 'Toggle Weight' };
    }
    if (
      /\b(?:weight\s+off|turn\s+weight\s+off|turn\s+off\s+weight|stop\s+weight|rack\s+weight|rack\s+it|kill\s+weight|drop\s+load|emergency\s+stop|disable\s+weight|load\s+off)\b/.test(
        q
      )
    ) {
      return {
        type: 'set_weight_enabled',
        enabled: false,
        label: 'Turn Weight Off',
      };
    }
    if (
      /\b(?:weight\s+on|turn\s+weight\s+on|turn\s+on\s+weight|start\s+weight|engage\s+weight|enable\s+weight|load\s+on)\b/.test(
        q
      )
    ) {
      return {
        type: 'set_weight_enabled',
        enabled: true,
        label: 'Turn Weight On',
      };
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
      const currentW = req.currentWeightLb ?? 45;
      let reply = `Got it, ${user?.name ?? 'athlete'} — ready to ${suggestedAction.label.toLowerCase()}.`;
      if (suggestedAction.type === 'set_weight_delta' && suggestedAction.deltaLb !== undefined) {
        const nextW = Math.max(5, Math.min(200, currentW + suggestedAction.deltaLb));
        const verb = suggestedAction.deltaLb > 0 ? 'Adding' : 'Dropping';
        reply = `${verb} ${Math.abs(suggestedAction.deltaLb)} ${unit}. Target is now ${formatWeight(nextW, unit)} ${unit}.`;
      } else if (suggestedAction.type === 'set_weight_target' && suggestedAction.targetLb !== undefined) {
        reply = `Setting target weight to ${formatWeight(suggestedAction.targetLb, unit)} ${unit}.`;
      } else if (suggestedAction.type === 'set_mode' && suggestedAction.mode) {
        reply = `Switched to ${suggestedAction.mode.toLowerCase()} mode. Lock in your form!`;
      } else if (suggestedAction.type === 'set_weight_enabled') {
        reply = suggestedAction.enabled
          ? `Weight engaged. Control the eccentric and drive hard!`
          : `Weight off. Great set, catch your breath.`;
      } else if (suggestedAction.type === 'next_exercise') {
        reply = `Moving to the next exercise.`;
      } else if (suggestedAction.type === 'prev_exercise') {
        reply = `Going back to the previous exercise.`;
      } else if (suggestedAction.type === 'select_exercise' && suggestedAction.exerciseQuery) {
        reply = `Switching to ${suggestedAction.exerciseQuery}.`;
      } else if (suggestedAction.type === 'complete_set') {
        reply = `Set logged! Solid work, take your rest.`;
      } else if (suggestedAction.type === 'skip_rest') {
        reply = `Rest timer skipped. Let's get after this set!`;
      }
      return {
        provider: 'local',
        model: DEFAULT_COACH_MODELS.local,
        reply,
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

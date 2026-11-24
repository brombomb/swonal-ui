import { CoachSuggestedAction, DynamicMode, Exercise } from '../types';
import { formatDisplayWeight, KG_TO_LB, WeightUnit } from './units';

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

/**
 * Fast client-side voice command matcher for zero-latency execution from AirPods / Bluetooth mics.
 */
export function matchClientVoiceCommand(
  prompt: string,
  activeUnit: WeightUnit = 'LB'
): CoachSuggestedAction | null {
  const q = prompt.trim().toLowerCase().replace(/[.!?]+$/g, '');
  if (!q) return null;

  const resolveSpokenUnit = (suffix?: string): WeightUnit => {
    const s = suffix?.toLowerCase();
    if (s === 'kg' || s === 'kilos') return 'KG';
    if (s === 'lb' || s === 'lbs' || s === 'pound' || s === 'pounds') return 'LB';
    return activeUnit === 'KG' ? 'KG' : 'LB';
  };
  const roundHalfLb = (lb: number) => Math.round(lb * 2) / 2;

  // 1. Weight adjustments (delta up / down with digits or number words)
  const addRegex = new RegExp(
    `(?:add|increase(?:\\s+weight)?(?:\\s+by)?|plus|up(?:\\s+by)?|bump(?:\\s+up)?(?:\\s+by)?|heavier(?:\\s+by)?|raise(?:\\s+weight)?(?:\\s+by)?)\\s+(${NUMBER_TOKEN_PATTERN})\\s*(lb|lbs|pound|pounds|kg|kilos)?\\b`,
    'i'
  );
  const addMatch = q.match(addRegex);
  if (addMatch) {
    const rawNum = parseSpokenNumber(addMatch[1]);
    if (rawNum !== null) {
      const unit = resolveSpokenUnit(addMatch[2]);
      const deltaLb = Math.max(0.5, Math.min(20, roundHalfLb(unit === 'KG' ? rawNum * KG_TO_LB : rawNum)));
      return {
        type: 'set_weight_delta',
        deltaLb,
        label: `Add +${rawNum} ${unit.toLowerCase()}`,
      };
    }
  }

  const dropRegex = new RegExp(
    `(?:drop|decrease(?:\\s+weight)?(?:\\s+by)?|lower(?:\\s+weight)?(?:\\s+by)?|reduce(?:\\s+weight)?(?:\\s+by)?|minus|subtract|down(?:\\s+by)?|lighter(?:\\s+by)?|remove)\\s+(${NUMBER_TOKEN_PATTERN})\\s*(lb|lbs|pound|pounds|kg|kilos)?\\b`,
    'i'
  );
  const dropMatch = q.match(dropRegex);
  if (dropMatch) {
    const rawNum = parseSpokenNumber(dropMatch[1]);
    if (rawNum !== null) {
      const unit = resolveSpokenUnit(dropMatch[2]);
      const deltaLb = -Math.max(0.5, Math.min(20, roundHalfLb(unit === 'KG' ? rawNum * KG_TO_LB : rawNum)));
      return {
        type: 'set_weight_delta',
        deltaLb,
        label: `Drop -${rawNum} ${unit.toLowerCase()}`,
      };
    }
  }

  // 2. Absolute weight target ("set weight to 50 pounds", "make it 45 lbs", "set weight 60", "weight to 35")
  const targetRegex = new RegExp(
    `(?:set\\s+(?:the\\s+)?weight\\s+(?:to\\s+)?|change\\s+(?:the\\s+)?weight\\s+to\\s+|make\\s+(?:it|the\\s+weight)\\s+|weight\\s+to\\s+)(${NUMBER_TOKEN_PATTERN})\\s*(lb|lbs|pound|pounds|kg|kilos)?\\b`,
    'i'
  );
  const targetMatch = q.match(targetRegex);
  if (targetMatch) {
    const rawNum = parseSpokenNumber(targetMatch[1]);
    if (rawNum !== null) {
      const unit = resolveSpokenUnit(targetMatch[2]);
      const targetLb = Math.max(5, Math.min(200, roundHalfLb(unit === 'KG' ? rawNum * KG_TO_LB : rawNum)));
      return {
        type: 'set_weight_target',
        targetLb,
        label: `Set Weight to ${rawNum} ${unit.toLowerCase()}`,
      };
    }
  }

  // 3. Workout modes
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
  if (/\b(?:next\s+exercise|next\s+workout|next\s+movement|skip\s+exercise|skip\s+movement)\b/.test(q)) {
    return { type: 'next_exercise', label: 'Next Exercise' };
  }
  if (
    /\b(?:prev(?:ious)?\s+exercise|prev(?:ious)?\s+workout|prev(?:ious)?\s+movement|last\s+exercise|go\s+back\s+an?\s+exercise)\b/.test(
      q
    )
  ) {
    return { type: 'prev_exercise', label: 'Previous Exercise' };
  }

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

  if (/\b(?:complete\s+set|log\s+set|finish\s+set|done\s+with\s+set|end\s+set)\b/.test(q)) {
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

export function findMatchingExercise(exercises: Exercise[], query: string): Exercise | null {
  const q = query.trim().toLowerCase();
  if (!q) return null;
  const exact = exercises.find((e) => e.name.toLowerCase() === q);
  if (exact) return exact;
  const includes = exercises.find((e) => e.name.toLowerCase().includes(q));
  if (includes) return includes;
  const words = q.split(/\s+/).filter((w) => w.length >= 3);
  if (words.length > 0) {
    let best: Exercise | null = null;
    let bestScore = 0;
    for (const ex of exercises) {
      const lower = ex.name.toLowerCase();
      const score = words.reduce((acc, w) => (lower.includes(w) ? acc + 1 : acc), 0);
      if (score > bestScore) {
        bestScore = score;
        best = ex;
      }
    }
    if (bestScore > 0) return best;
  }
  return null;
}

export interface VoiceActionHandlers {
  currentWeightLb: number;
  activeUnit: WeightUnit;
  exercises: Exercise[];
  setTargetWeight: (weightLb: number, mode?: DynamicMode, modifierPct?: number) => void;
  setWeightEnabled: (enabled: boolean) => void;
  toggleWeight: () => void;
  setWeightMode: (mode: DynamicMode, modifierPct?: number) => void;
  stepRoutine: (delta: number) => void;
  selectExercise: (exercise: Exercise) => void;
  skipRest: () => void;
  completeSet: () => void | Promise<void>;
}

/**
 * Executes a CoachSuggestedAction immediately against UI state and returns a crisp spoken confirmation string.
 */
export function executeCoachSuggestedAction(
  action: CoachSuggestedAction,
  handlers: VoiceActionHandlers
): string {
  const unitLabel = handlers.activeUnit === 'KG' ? 'kilograms' : 'pounds';

  switch (action.type) {
    case 'set_weight_delta': {
      // deltaLb is ALWAYS canonical pounds from matchClientVoiceCommand / AI coach endpoints
      const deltaLbCanonical = Math.max(-20, Math.min(20, action.deltaLb ?? 0));
      const nextLb = Math.max(5, Math.min(200, handlers.currentWeightLb + deltaLbCanonical));
      handlers.setTargetWeight(nextLb);
      const dispDelta = formatDisplayWeight(Math.abs(deltaLbCanonical), handlers.activeUnit);
      const dispTarget = formatDisplayWeight(nextLb, handlers.activeUnit);
      const verb = deltaLbCanonical >= 0 ? 'Adding' : 'Dropping';
      return `${verb} ${dispDelta} ${unitLabel}. Target is now ${dispTarget} ${unitLabel}.`;
    }
    case 'set_weight_target': {
      // targetLb is ALWAYS canonical pounds
      const targetLbCanonical = action.targetLb ?? handlers.currentWeightLb;
      const clampedLb = Math.max(5, Math.min(200, targetLbCanonical));
      handlers.setTargetWeight(clampedLb);
      const dispTarget = formatDisplayWeight(clampedLb, handlers.activeUnit);
      return `Setting weight to ${dispTarget} ${unitLabel}.`;
    }
    case 'set_mode': {
      const rawMode = (action.mode ?? 'STANDARD').toLowerCase() as DynamicMode;
      const defaultPct = rawMode === 'eccentric' || rawMode === 'chains' ? 25 : 0;
      handlers.setWeightMode(rawMode, defaultPct);
      const modeName = rawMode.charAt(0).toUpperCase() + rawMode.slice(1);
      return `Switched to ${modeName} mode.`;
    }
    case 'next_exercise': {
      handlers.stepRoutine(1);
      return 'Moving to next exercise.';
    }
    case 'prev_exercise': {
      handlers.stepRoutine(-1);
      return 'Going back to previous exercise.';
    }
    case 'select_exercise': {
      const query = action.exerciseQuery ?? '';
      const matched = findMatchingExercise(handlers.exercises, query);
      if (matched) {
        handlers.selectExercise(matched);
        return `Switching to ${matched.name}.`;
      }
      return `Could not find exercise matching ${query}.`;
    }
    case 'skip_rest': {
      handlers.skipRest();
      return "Rest timer skipped. Let's work!";
    }
    case 'complete_set': {
      void handlers.completeSet();
      return 'Set logged. Great work, take your rest!';
    }
    case 'set_weight_enabled': {
      const enable = Boolean(action.enabled);
      handlers.setWeightEnabled(enable);
      return enable
        ? "Weight engaged. Let's work!"
        : 'Weight off. Rack it and recover.';
    }
    case 'toggle_weight': {
      handlers.toggleWeight();
      return 'Toggling weight.';
    }
    default:
      return action.label;
  }
}

export function speakCoachAudio(text: string, soundMuted = false) {
  if (soundMuted || typeof window === 'undefined' || !('speechSynthesis' in window)) return;
  try {
    window.speechSynthesis.cancel();
    const utter = new SpeechSynthesisUtterance(text);
    utter.rate = 1.05;
    utter.pitch = 1.0;
    window.speechSynthesis.speak(utter);
  } catch {
    // Ignore speech synthesis errors in headless environments
  }
}

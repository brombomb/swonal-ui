import { WeightUnit } from './utils/units';

export type DynamicMode = 'standard' | 'eccentric' | 'chains' | 'spotter' | 'burnout';
export type AccessoryType = 'HANDLES' | 'BAR' | 'ROPE' | 'BODYWEIGHT';
export type BlockType = 'WARMUP' | 'STRAIGHT_SET' | 'SUPERSET' | 'CIRCUIT';

export interface UserProfile {
  id: string;
  name: string;
  avatarColor: string;
  preferredMusicProvider: string;
  themeMode: 'dark' | 'light' | 'system';
  accentColor: string;
  weightUnit: WeightUnit;
}

export interface Exercise {
  id: string;
  name: string;
  muscleGroup: string;
  accessory: AccessoryType;
  recommendedHeight: number;
  recommendedRotation: number;
  recommendedAngle: number;
  unilateral: boolean;
  notes?: string | null;
  mediaUrl?: string | null;
}

export interface WorkoutBlockItem {
  exerciseId: string;
  sets: number;
  targetReps: number;
  mode: DynamicMode;
  modifierPct: number;
  targetWeightLb: number;
}

export interface WorkoutBlock {
  blockType: BlockType;
  restSeconds: number;
  items: WorkoutBlockItem[];
}

export interface WorkoutTemplate {
  id: string;
  userId: string;
  name: string;
  description?: string | null;
  blocksJson: string;
  blocks: WorkoutBlock[];
  createdAt: string;
}

export interface PersonalRecord {
  id: string;
  userId: string;
  exerciseId: string;
  oneRepMaxEst: number;
  maxWeightLb: number;
  updatedAt: string;
  exercise?: Exercise;
}

export interface SetLogEntry {
  id: string;
  sessionId: string;
  exerciseId: string;
  setNumber: number;
  mode: DynamicMode;
  weightLb: number;
  repsCompleted: number;
  volumeLb: number;
  rpe?: number | null;
  timestamp: string;
  exercise?: {
    name: string;
    muscleGroup: string;
    accessory: string;
  };
}

export interface WorkoutSession {
  id: string;
  userId: string;
  templateId?: string | null;
  startedAt: string;
  completedAt?: string | null;
  totalVolume: number;
  template?: { name: string } | null;
  setLogs?: SetLogEntry[];
}

export interface CableTelemetry {
  position: number;
  velocity: number;
  load_lb: number;
}

export interface TelemetryFrame {
  type: 'telemetry';
  mode: 'CUSTOM' | 'STOCK';
  weight_enabled: boolean;
  target_weight_lb: number;
  applied_resistance_lb: number;
  profile_mode: DynamicMode;
  modifier_pct: number;
  rep_count: number;
  accessory: AccessoryType;
  left_cable: CableTelemetry;
  right_cable: CableTelemetry;
  arm_positions: {
    height: { left: number; right: number };
    rotation: { left: number; right: number; left_unlocked?: boolean; right_unlocked?: boolean };
    angle: { left: number; right: number };
  };
}

export interface SafetyEvent {
  reason: string;
  delta?: number;
  timestamp: number;
}

export interface QueuedWorkout {
  userId: string;
  templateId: string;
  templateName: string;
  description?: string | null;
  blocks: WorkoutBlock[];
  queuedAt: string;
}

export interface SharedSessionState {
  activeUserId: string;
  partnerModeEnabled: boolean;
  partnerUserIds: string[];
  activePartnerIndex: number;
  activeSessionId: string | null;
  activeExerciseId: string | null;
  queuedWorkout: QueuedWorkout | null;
  restTimerEndsAt: number | null;
}

export interface MusicProviderMeta {
  id: string;
  displayName: string;
  configured?: boolean;
  capabilities: {
    supportsConnectDevices: boolean;
    supportsVolumeDucking: boolean;
    supportsPlaylists: boolean;
  };
}

export interface MusicPlaybackState {
  isPlaying: boolean;
  trackName: string;
  artistName: string;
  albumName: string;
  artworkUrl?: string;
  progressMs: number;
  durationMs: number;
  activeDeviceId?: string;
  volumePct?: number;
  isDucked?: boolean;
  isConnected?: boolean;
}

export interface MusicPlaylist {
  id: string;
  name: string;
  uri: string;
  artworkUrl?: string;
  trackCount: number;
}

export interface MusicTrack {
  id: string;
  name: string;
  uri: string;
  artistName: string;
  albumName: string;
  durationMs: number;
  artworkUrl?: string;
}

export interface MusicDevice {
  id: string;
  name: string;
  type: string;
  isActive: boolean;
  volumePct: number;
}

export interface RoutineStep {
  stepIndex: number;
  blockIndex: number;
  blockType: BlockType;
  restSeconds: number;
  setNumber: number;
  totalSetsInItem: number;
  targetReps: number;
  targetWeightLb: number;
  mode: DynamicMode;
  modifierPct: number;
  exerciseId: string;
}

export interface HomeAssistantEventRecord {
  id: string;
  topic: string;
  payload: Record<string, unknown>;
  mqttDelivered: boolean;
  webhookDelivered: boolean;
  timestamp: string;
}

export interface HomeAssistantBusStatus {
  mqttConfigured: boolean;
  mqttConnected: boolean;
  mqttTopicPrefix: string;
  lastMqttError: string | null;
  webhookConfigured: boolean;
  recentEvents: HomeAssistantEventRecord[];
}

export interface OverloadSuggestion {
  ready: boolean;
  recommendedBumpLb: number;
  recommendedWeightLb: number;
  lastWeightLb: number | null;
  lastReps: number | null;
  reason: string;
}

export interface VbtMetrics {
  currentVelocity: number;
  peakSetVelocity: number;
  lastRepPeakVelocity: number;
  velocityDropPct: number;
  fatigueAlert: boolean;
}

export interface SymmetryMetrics {
  positionDelta: number;
  signedBalance: number;
  leftLoadPct: number;
  rightLoadPct: number;
  status: 'balanced' | 'drift' | 'critical';
}

export interface AiCoachPublicConfig {
  provider: 'local' | 'openai' | 'anthropic' | 'gemini' | 'ollama';
  model: string;
  hasApiKey: boolean;
  keyHint: string | null;
  baseUrl: string | null;
  supportedProviders: Array<{ id: string; label: string }>;
}

export interface SessionPrHit {
  exerciseId: string;
  exerciseName: string;
  oneRepMaxEstLb: number;
  maxWeightLb: number;
}

export interface MuscleGroupVolumeItem {
  muscleGroup: string;
  volumeLb: number;
  sets: number;
  pct: number;
}

export interface WorkoutSummaryData {
  sessionId: string;
  userId: string;
  sessionName: string;
  startedAt: string;
  completedAt: string;
  durationSeconds: number;
  totalVolumeLb: number;
  totalSets: number;
  totalReps: number;
  avgRpe: number | null;
  prsHit: SessionPrHit[];
  muscleBreakdown: MuscleGroupVolumeItem[];
}

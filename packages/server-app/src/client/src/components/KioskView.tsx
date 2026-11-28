import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import QRCode from 'qrcode';
import { KioskCableBar } from './KioskCableBar';

const DIAL_TICKS = Array.from({ length: 60 }, (_, idx) => {
  const angle = (idx * 6 * Math.PI) / 180;
  const isMajor = idx % 5 === 0;
  const rOuter = 250;
  const rInner = isMajor ? 238 : 243;
  return {
    idx,
    isMajor,
    x1: 310 + rInner * Math.cos(angle),
    y1: 310 + rInner * Math.sin(angle),
    x2: 310 + rOuter * Math.cos(angle),
    y2: 310 + rOuter * Math.sin(angle),
  };
});

const DialTickRing: React.FC<{ enabled: boolean }> = React.memo(({ enabled }) => (
  <g aria-hidden="true">
    {DIAL_TICKS.map((t) => (
      <line
        key={t.idx}
        x1={t.x1}
        y1={t.y1}
        x2={t.x2}
        y2={t.y2}
        stroke={enabled ? 'var(--accent-primary)' : 'var(--text-muted)'}
        strokeOpacity={t.isMajor ? (enabled ? 0.55 : 0.32) : 0.16}
        strokeWidth={t.isMajor ? 2 : 1}
        strokeLinecap="round"
      />
    ))}
  </g>
));
import {
  Activity,
  AlertTriangle,
  Bot,
  Check,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Copy,
  Database,
  Download,
  Dumbbell,
  FastForward,
  Gauge,
  Info,
  Key,
  ListMusic,
  Mic,
  MicOff,
  Moon,
  Music,
  Palette,
  Pause,
  Play,
  Plus,
  QrCode,
  Radio,
  RefreshCw,
  Rewind,
  Scale,
  Send,
  Settings,
  SkipForward,
  Sliders,
  Sparkles,
  Sun,
  TrendingUp,
  Trophy,
  Users,
  Video,
  Volume2,
  VolumeX,
  Wrench,
  X,
  Zap,
} from 'lucide-react';
import { useSwonalState } from '../hooks/useSwonalState';
import { PostWorkoutSummaryModal } from './PostWorkoutSummaryModal';
import { MusicPickerDialog } from './MusicPickerDialog';
import {
  AccessoryType,
  AiCoachPublicConfig,
  CoachSuggestedAction,
  DynamicMode,
  Exercise,
  HomeAssistantBusStatus,
} from '../types';
import { soundEngine } from '../utils/soundEngine';
import { armsMatchExercise, formatArmTarget } from '../utils/armPositions';
import {
  formatDisplayWeight,
  getContrastTextColor,
  KG_TO_LB,
  stepDisplayWeight,
} from '../utils/units';
import {
  executeCoachSuggestedAction,
  matchClientVoiceCommand,
  speakCoachAudio,
} from '../utils/voiceCommands';

const ACCENT_SWATCHES = [
  { name: 'Electric Cyan', hex: '#00F0FF' },
  { name: 'Hyper Pink', hex: '#FF007A' },
  { name: 'Volt Lime', hex: '#CCFF00' },
  { name: 'Blaze Orange', hex: '#FF5500' },
  { name: 'Ultra Violet', hex: '#8B5CF6' },
  { name: 'Emerald Green', hex: '#10B981' },
  { name: 'Crimson Red', hex: '#EF4444' },
  { name: 'Dark Blue', hex: '#1D4ED8' },
];

const DYNAMIC_MODES: Array<{ id: DynamicMode; label: string; defaultPct: number }> = [
  { id: 'standard', label: 'Standard', defaultPct: 0 },
  { id: 'spotter', label: 'Spotter', defaultPct: 0 },
  { id: 'burnout', label: 'Burnout', defaultPct: 0 },
  { id: 'eccentric', label: 'Eccentric', defaultPct: 25 },
  { id: 'chains', label: 'Chains', defaultPct: 25 },
  { id: 'smart_flex', label: 'Smart Flex', defaultPct: 25 },
];

type SwonalHookReturn = ReturnType<typeof useSwonalState>;

export const KioskView: React.FC<{ swonal: SwonalHookReturn }> = ({ swonal }) => {
  const {
    users,
    activeUser,
    activeUnit,
    activeProviderId,
    exercises,
    templates,
    personalRecords,
    sessionState,
    telemetry,
    daemonConnected,
    wsConnected,
    safetyEvent,
    clearSafetyEvent,
    lastPrBanner,
    soundMuted,
    toggleSoundMuted,
    showDemoPip,
    setShowDemoPip,
    activeExercise,
    activeAccessory,
    activeExercisePr,
    overloadSuggestion,
    vbtMetrics,
    symmetryMetrics,
    sessions,
    activeTemplateName,
    routineSteps,
    currentStep,
    currentStepIdx,
    restSecondsRemaining,
    musicProviders,
    musicConfigured,
    musicState,
    musicError,
    clearMusicError,
    webPlayerEnabled,
    setWebPlayerEnabled,
    webPlayerReady,
    playlists,
    favoritePlaylistIds,
    devices,
    sendWs,
    subscribeLiveTelemetry,
    setWeight,
    setTargetWeight,
    toggleWeight,
    setWeightEnabled,
    setWeightMode,
    setAccessory,
    alignMockArmsToExercise,
    switchUser,
    updateUserPreferences,
    createUser,
    togglePartnerMode,
    selectFreeExercise,
    selectExercise,
    startWorkoutTemplate,
    stepRoutine,
    logCompletedSet,
    completeSet,
    finishActiveWorkout,
    lastCompletedWorkoutSummary,
    dismissWorkoutSummary,
    skipRestTimer,
    skipRest,
    extendRestTimer,
    toggleMusicPlay,
    nextTrack,
    previousTrack,
    setMusicVolume,
    playPlaylist,
    fetchPlaylistTracks,
    transferMusicDevice,
    toggleFavoritePlaylist,
    refreshMusicNow,
    refreshBootstrap,
  } = swonal;

  // Native <dialog> refs
  const qrDialogRef = useRef<HTMLDialogElement>(null);
  const friendsDialogRef = useRef<HTMLDialogElement>(null);
  const libraryDialogRef = useRef<HTMLDialogElement>(null);
  const profileDialogRef = useRef<HTMLDialogElement>(null);
  const mockDialogRef = useRef<HTMLDialogElement>(null);
  const musicDialogRef = useRef<HTMLDialogElement>(null);
  const aiDialogRef = useRef<HTMLDialogElement>(null);
  const diagnosticsDialogRef = useRef<HTMLDialogElement>(null);
  const weightDialogRef = useRef<HTMLDialogElement>(null);
  const [weightInputDraft, setWeightInputDraft] = useState<string>('');

  // Friends & Nostr P2P Modal State
  const [friendsKioskTab, setFriendsKioskTab] = useState<'mycode' | 'feed' | 'add'>('mycode');
  const [kioskFriendIdentity, setKioskFriendIdentity] = useState<{
    npub: string;
    pubkeyHex: string;
    shareWorkouts: boolean;
    relays: string[];
  } | null>(null);
  const [kioskFriendQrDataUrl, setKioskFriendQrDataUrl] = useState<string>('');
  const [kioskCopiedNpub, setKioskCopiedNpub] = useState(false);
  const [kioskFriendsList, setKioskFriendsList] = useState<
    Array<{ id: string; friendNpub: string; friendPubkey: string; nickname?: string | null; createdAt: string }>
  >([]);
  const [kioskFriendsFeed, setKioskFriendsFeed] = useState<any[]>([]);
  const [kioskFriendsLoading, setKioskFriendsLoading] = useState(false);
  const [kioskFriendsSyncing, setKioskFriendsSyncing] = useState(false);
  const [kioskAddFriendCode, setKioskAddFriendCode] = useState('');
  const [kioskAddFriendNick, setKioskAddFriendNick] = useState('');
  const [kioskAddFriendMsg, setKioskAddFriendMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const [qrDataUrl, setQrDataUrl] = useState<string>('');
  const [libraryTab, setLibraryTab] = useState<'routines' | 'exercises'>('routines');
  const [libraryOpen, setLibraryOpen] = useState(false);
  const [routineUserFilter, setRoutineUserFilter] = useState<string>('ACTIVE');
  const [exerciseSearch, setExerciseSearch] = useState('');
  const [muscleFilter, setMuscleFilter] = useState<string>('ALL');
  const [accessoryFilter, setAccessoryFilter] = useState<string>('ALL');

  const [newProfileName, setNewProfileName] = useState('');
  const [newProfileAccent, setNewProfileAccent] = useState('#CCFF00');
  const [mockStatusMsg, setMockStatusMsg] = useState<string | null>(null);
  const [isMockMode, setIsMockMode] = useState(false);
  const [showQuickSettings, setShowQuickSettings] = useState(false);
  const quickSettingsRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!showQuickSettings) return;
    const handlePointerDown = (e: PointerEvent) => {
      if (quickSettingsRef.current && !quickSettingsRef.current.contains(e.target as Node)) {
        setShowQuickSettings(false);
      }
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowQuickSettings(false);
    };
    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [showQuickSettings]);
  const [telemetryDisplayMode, setTelemetryDisplayMode] = useState<'auto' | 'always' | 'off'>(() => {
    if (typeof window === 'undefined') return 'auto';
    const saved = window.localStorage.getItem('swonal_telemetry_display');
    return saved === 'always' || saved === 'off' ? saved : 'auto';
  });
  const [showArmInfo, setShowArmInfo] = useState(false);
  const [haStatus, setHaStatus] = useState<HomeAssistantBusStatus | null>(null);
  const [coachConfig, setCoachConfig] = useState<AiCoachPublicConfig | null>(null);
  const [recentFriendTicker, setRecentFriendTicker] = useState<string | null>(null);

  // Poll recent friend activity ticker on rest timer
  useEffect(() => {
    if (restSecondsRemaining <= 0 || !activeUser?.id) return;
    const fetchTicker = async () => {
      try {
        const res = await fetch(`/api/friends/feed/${encodeURIComponent(activeUser.id)}?limit=5`);
        if (res.ok) {
          const items = (await res.json()) as Array<{
            id: string;
            kind: number;
            authorName: string;
            payload: any;
          }>;
          const workoutPost = items.find((it) => it.kind === 30101);
          if (workoutPost) {
            const p = workoutPost.payload;
            const vol = p.totalVolumeLb ? `${Math.round(p.totalVolumeLb).toLocaleString()} lb` : '';
            setRecentFriendTicker(
              `${workoutPost.authorName} completed ${p.routineName || 'Workout'}${vol ? ` (${vol})` : ''} 💪`
            );
          }
        }
      } catch {
        // ignore
      }
    };
    void fetchTicker();
  }, [restSecondsRemaining > 0, activeUser?.id]);
  const [coachPrompt, setCoachPrompt] = useState('');
  const [coachReply, setCoachReply] = useState<string>(
    'Tap AirPods Voice or say "Add 5 pounds", "Chains mode", "Weight on", or "Next exercise".'
  );
  const [coachAction, setCoachAction] = useState<CoachSuggestedAction | null>(null);
  const [coachBusy, setCoachBusy] = useState(false);
  const [isListening, setIsListening] = useState(false);
  const [liveVoiceCoachEnabled, setLiveVoiceCoachEnabled] = useState(true);
  const [voiceToast, setVoiceToast] = useState<{
    heard: string;
    reply: string;
    actionLabel?: string;
  } | null>(null);
  const voiceToastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [byokProvider, setByokProvider] = useState<'local' | 'openai' | 'anthropic' | 'gemini' | 'ollama'>('local');
  const [byokModel, setByokModel] = useState('');
  const [byokApiKey, setByokApiKey] = useState('');
  const [byokBaseUrl, setByokBaseUrl] = useState('');
  const [calibrationStatus, setCalibrationStatus] = useState<{
    zero_offsets?: { left: number; right: number };
    last_calibrated_at?: number | null;
    expected_board?: string;
  } | null>(null);
  const [networkInfo, setNetworkInfo] = useState<{
    primaryIp: string | null;
    ips: string[];
    port: number;
    publicUrl?: string | null;
    companionBaseUrl: string | null;
  } | null>(null);
  const [selectedLanIp, setSelectedLanIp] = useState<string | null>(null);
  const [qrUseExternal, setQrUseExternal] = useState<boolean>(true);
  const [baseUrlOverride] = useState<string>(() => {
    if (typeof window === 'undefined') return '';
    return window.localStorage.getItem('swonal_base_url_override') ?? '';
  });

  useEffect(() => {
    const checkHealth = () => {
      void fetch('/api/daemon/health')
        .then((r) => (r.ok ? r.json() : null))
        .then((h: { mock?: boolean; mock_mode?: boolean } | null) => {
          if (h && (h.mock_mode !== undefined || h.mock !== undefined)) {
            setIsMockMode(Boolean(h.mock_mode ?? h.mock));
          }
        })
        .catch(() => {});
    };
    checkHealth();
    const interval = setInterval(checkHealth, 5000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    void fetch('/api/network-info')
      .then((r) => (r.ok ? r.json() : null))
      .then((info) => {
        if (info) {
          setNetworkInfo(info);
          if (info.primaryIp) setSelectedLanIp(info.primaryIp);
        }
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    void fetch('/api/coach/config')
      .then((r) => (r.ok ? r.json() : null))
      .then((cfg: AiCoachPublicConfig | null) => {
        if (cfg) {
          setCoachConfig(cfg);
          setByokProvider(cfg.provider);
          setByokModel(cfg.model);
          setByokBaseUrl(cfg.baseUrl || '');
        }
      })
      .catch(() => {});
  }, []);

  const showVoiceStatusToast = useCallback(
    (heard: string, reply: string, actionLabel?: string) => {
      setVoiceToast({ heard, reply, actionLabel });
      if (voiceToastTimerRef.current) clearTimeout(voiceToastTimerRef.current);
      voiceToastTimerRef.current = setTimeout(() => {
        setVoiceToast(null);
      }, 6500);
    },
    []
  );

  const executeVoiceAction = useCallback(
    (action: CoachSuggestedAction): string => {
      soundEngine.playVoiceCommandExecuted();
      return executeCoachSuggestedAction(action, {
        currentWeightLb: telemetry.target_weight_lb,
        activeUnit,
        exercises,
        setTargetWeight,
        setWeightEnabled,
        toggleWeight,
        setWeightMode,
        stepRoutine,
        selectExercise,
        skipRest,
        completeSet,
      });
    },
    [
      telemetry.target_weight_lb,
      activeUnit,
      exercises,
      setTargetWeight,
      setWeightEnabled,
      toggleWeight,
      setWeightMode,
      stepRoutine,
      selectExercise,
      skipRest,
      completeSet,
    ]
  );

  // Real-Time Live Audio Coaching & Encouragement into AirPods during sets
  const prevVoiceWeightOnRef = useRef<boolean>(telemetry.weight_enabled);
  const prevVoiceRepRef = useRef<number>(telemetry.rep_count);
  const vbtWarnedThisSetRef = useRef<boolean>(false);

  useEffect(() => {
    const wasOn = prevVoiceWeightOnRef.current;
    prevVoiceWeightOnRef.current = telemetry.weight_enabled;

    if (!wasOn && telemetry.weight_enabled) {
      vbtWarnedThisSetRef.current = false;
      prevVoiceRepRef.current = 0;
      if ((isListening || liveVoiceCoachEnabled) && !soundMuted) {
        const cue = 'Weight is live. Control the eccentric, drive hard!';
        setCoachReply(cue);
        speakCoachAudio(cue, soundMuted);
      }
    } else if (wasOn && !telemetry.weight_enabled) {
      vbtWarnedThisSetRef.current = false;
    }
  }, [telemetry.weight_enabled, isListening, liveVoiceCoachEnabled, soundMuted]);

  useEffect(() => {
    const prevRep = prevVoiceRepRef.current;
    const rep = telemetry.rep_count;
    prevVoiceRepRef.current = rep;

    if (!telemetry.weight_enabled || rep <= prevRep || rep === 0) return;
    if (!(isListening || liveVoiceCoachEnabled) || soundMuted) return;

    const targetReps = currentStep?.targetReps ?? 10;
    const halfwayRep = Math.max(2, Math.floor(targetReps / 2));
    const twoLeftRep = Math.max(3, targetReps - 2);

    let cue: string | null = null;
    if (rep === 1) {
      cue = 'Good first rep, smooth tempo!';
    } else if (rep === targetReps) {
      cue = 'Target reps hit! Rack it or go for a burnout PR!';
    } else if (rep === twoLeftRep && targetReps >= 5) {
      cue = 'Two more reps! Push through!';
    } else if (rep === halfwayRep && targetReps >= 4) {
      cue = 'Halfway there! Keep your core braced!';
    }

    if (cue) {
      setCoachReply(cue);
      speakCoachAudio(cue, soundMuted);
    }
  }, [
    telemetry.rep_count,
    telemetry.weight_enabled,
    currentStep?.targetReps,
    isListening,
    liveVoiceCoachEnabled,
    soundMuted,
  ]);

  useEffect(() => {
    if (!lastPrBanner) return;
    if (!(isListening || liveVoiceCoachEnabled) || soundMuted) return;
    const cue = 'Boom! New personal record! Massive lift!';
    setCoachReply(cue);
    speakCoachAudio(cue, soundMuted);
  }, [lastPrBanner, isListening, liveVoiceCoachEnabled, soundMuted]);

  useEffect(() => {
    if (!telemetry.weight_enabled || telemetry.rep_count < 2) return;
    if (vbtMetrics.velocityDropPct >= 25 && !vbtWarnedThisSetRef.current) {
      vbtWarnedThisSetRef.current = true;
      if ((isListening || liveVoiceCoachEnabled) && !soundMuted) {
        const cue = `Bar speed dropped ${Math.round(vbtMetrics.velocityDropPct)} percent. Drive explosively or rack the weight.`;
        setCoachReply(cue);
        speakCoachAudio(cue, soundMuted);
      }
    }
  }, [
    vbtMetrics.velocityDropPct,
    telemetry.weight_enabled,
    telemetry.rep_count,
    isListening,
    liveVoiceCoachEnabled,
    soundMuted,
  ]);

  const askCoach = async (promptText: string) => {
    const trimmed = promptText.trim();
    if (!trimmed || !activeUser) return;

    // Zero-latency client-side voice command detection & immediate auto-execution
    const fastAction = matchClientVoiceCommand(trimmed, activeUnit);
    let executedClientSide = false;
    let spokenConfirmation = '';
    if (fastAction) {
      executedClientSide = true;
      spokenConfirmation = executeVoiceAction(fastAction);
      setCoachReply(spokenConfirmation);
      setCoachAction(fastAction);
      showVoiceStatusToast(trimmed, spokenConfirmation, fastAction.label);
      speakCoachAudio(spokenConfirmation, soundMuted);
    }

    setCoachBusy(true);
    try {
      const res = await fetch('/api/coach/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: activeUser.id,
          prompt: trimmed,
          exerciseName: activeExercise?.name,
          currentWeightLb: telemetry.target_weight_lb,
          repCount: telemetry.rep_count,
          meanVelocity: vbtMetrics.currentVelocity,
          velocityDropPct: vbtMetrics.velocityDropPct,
        }),
      });
      if (res.ok) {
        const data = (await res.json()) as {
          reply: string;
          suggestedAction?: CoachSuggestedAction | null;
        };
        const rawServerAction = data.suggestedAction ?? null;
        const safeServerAction =
          rawServerAction &&
          rawServerAction.type !== 'toggle_weight' &&
          !(rawServerAction.type === 'set_weight_enabled' && rawServerAction.enabled)
            ? rawServerAction.type === 'set_weight_delta' && rawServerAction.deltaLb !== undefined
              ? { ...rawServerAction, deltaLb: Math.max(-20, Math.min(20, rawServerAction.deltaLb)) }
              : rawServerAction
            : null;
        setCoachAction(safeServerAction);

        if (safeServerAction && !executedClientSide) {
          const confirmText = executeVoiceAction(safeServerAction);
          const finalReply = confirmText || data.reply;
          setCoachReply(finalReply);
          showVoiceStatusToast(trimmed, finalReply, safeServerAction.label);
          speakCoachAudio(finalReply, soundMuted);
        } else if (!executedClientSide) {
          setCoachReply(data.reply);
          showVoiceStatusToast(trimmed, data.reply);
          speakCoachAudio(data.reply, soundMuted);
        }
      }
    } finally {
      setCoachBusy(false);
    }
  };

  const voiceMicActiveRef = useRef(false);
  const voicePauseTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const voicePendingRef = useRef('');
  const voiceLastSubmittedIdxRef = useRef(0);
  const voiceRecognitionRef = useRef<{
    stop: () => void;
  } | null>(null);

  useEffect(() => {
    return () => {
      voiceMicActiveRef.current = false;
      if (voicePauseTimerRef.current) clearTimeout(voicePauseTimerRef.current);
      try {
        voiceRecognitionRef.current?.stop();
      } catch {
        // Ignore cleanup stop errors
      }
    };
  }, []);

  const stopVoiceListening = () => {
    voiceMicActiveRef.current = false;
    if (voicePauseTimerRef.current) {
      clearTimeout(voicePauseTimerRef.current);
      voicePauseTimerRef.current = null;
    }
    try {
      voiceRecognitionRef.current?.stop();
    } catch {
      // Ignore stop errors
    }
    voiceRecognitionRef.current = null;
    setIsListening(false);
  };

  const toggleVoiceListening = () => {
    if (isListening || voiceMicActiveRef.current) {
      stopVoiceListening();
      return;
    }

    const SpeechRec =
      (window as unknown as Record<string, unknown>).SpeechRecognition ||
      (window as unknown as Record<string, unknown>).webkitSpeechRecognition;
    if (!SpeechRec || typeof SpeechRec !== 'function') {
      setCoachReply('Browser voice recognition unavailable here — type your question below.');
      return;
    }

    type SpeechRecInstance = {
      lang: string;
      continuous: boolean;
      interimResults: boolean;
      maxAlternatives: number;
      onresult: (e: {
        results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal?: boolean }>;
      }) => void;
      onerror: (e?: { error?: string }) => void;
      onend: () => void;
      start: () => void;
      stop: () => void;
    };

    const launchRecognition = () => {
      if (!voiceMicActiveRef.current) return;
      const recognition = new (SpeechRec as new () => SpeechRecInstance)();
      recognition.lang = 'en-US';
      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.maxAlternatives = 1;
      voiceLastSubmittedIdxRef.current = 0;
      voicePendingRef.current = '';
      voiceRecognitionRef.current = recognition;

      recognition.onresult = (event) => {
        let segment = '';
        for (let i = voiceLastSubmittedIdxRef.current; i < event.results.length; i += 1) {
          segment += event.results[i]?.[0]?.transcript ?? '';
        }
        const trimmed = segment.trim();
        if (trimmed) {
          voicePendingRef.current = trimmed;
          setCoachPrompt(trimmed);
        }

        if (voicePauseTimerRef.current) {
          clearTimeout(voicePauseTimerRef.current);
        }
        // Send query after a 1.4s pause in speech, while keeping the mic open
        voicePauseTimerRef.current = setTimeout(() => {
          const query = voicePendingRef.current.trim();
          if (query) {
            voiceLastSubmittedIdxRef.current = event.results.length;
            voicePendingRef.current = '';
            setCoachPrompt('');
            void askCoach(query);
          }
        }, 1400);
      };

      recognition.onerror = (err) => {
        if (err?.error === 'not-allowed' || err?.error === 'service-not-allowed') {
          stopVoiceListening();
          setCoachReply('Microphone permission denied — allow mic access or type below.');
        }
      };

      recognition.onend = () => {
        // Keep mic open across browser silence timeouts until user toggles off
        if (voiceMicActiveRef.current) {
          setTimeout(launchRecognition, 150);
        } else {
          setIsListening(false);
        }
      };

      recognition.start();
    };

    voiceMicActiveRef.current = true;
    setIsListening(true);
    setLiveVoiceCoachEnabled(true);
    soundEngine.playVoiceListeningStart();
    showVoiceStatusToast(
      'AirPods Voice Active',
      'Say "Add 5 pounds", "Chains mode", "Weight on", or "Next exercise".'
    );
    launchRecognition();
  };

  const saveCoachConfig = async () => {
    const res = await fetch('/api/coach/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        provider: byokProvider,
        model: byokModel || undefined,
        apiKey: byokApiKey || undefined,
        baseUrl: byokBaseUrl || undefined,
      }),
    });
    if (res.ok) {
      const cfg = (await res.json()) as AiCoachPublicConfig;
      setCoachConfig(cfg);
      setByokApiKey('');
      aiDialogRef.current?.close();
    }
  };

  const refreshCalibration = async () => {
    try {
      const res = await fetch('/api/daemon/calibration');
      if (res.ok) setCalibrationStatus(await res.json());
    } catch {
      // Ignore
    }
  };

  const zeroCalibration = async () => {
    try {
      const res = await fetch('/api/daemon/calibration/zero', { method: 'POST' });
      const data = await res.json();
      if (res.ok) {
        setCalibrationStatus(data);
        setMockStatusMsg('Cable encoder zero offsets calibrated');
      } else {
        setMockStatusMsg(data.detail || 'Calibration requires weight OFF');
      }
    } catch {
      setMockStatusMsg('Calibration endpoint unreachable');
    }
  };

  const refreshHaStatus = async () => {
    try {
      const res = await fetch('/api/integrations/ha/status');
      if (res.ok) {
        setHaStatus((await res.json()) as HomeAssistantBusStatus);
      }
    } catch {
      // Ignore transient network errors
    }
  };

  const triggerHaTest = async () => {
    try {
      const res = await fetch('/api/integrations/ha/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ note: 'Kiosk diagnostic test event' }),
      });
      if (res.ok) {
        const data = await res.json();
        setHaStatus(data.bus as HomeAssistantBusStatus);
        setMockStatusMsg(`Published HA event to ${data.event.topic}`);
      }
    } catch {
      setMockStatusMsg('Failed to trigger Home Assistant test event');
    }
  };

  const externalBaseUrl = (
    baseUrlOverride.trim() ||
    networkInfo?.publicUrl ||
    ''
  ).replace(/\/+$/, '');

  const companionUrl = useMemo(() => {
    const sessionParam = sessionState.activeSessionId
      ? `?session=${encodeURIComponent(sessionState.activeSessionId)}`
      : '';
    if (qrUseExternal && externalBaseUrl) {
      return `${externalBaseUrl}/companion${sessionParam}`;
    }
    if (typeof window === 'undefined') {
      return `http://127.0.0.1:3000/companion${sessionParam}`;
    }
    const port =
      networkInfo?.port ?? (window.location.port ? Number(window.location.port) : 3000);
    if (selectedLanIp) {
      return `http://${selectedLanIp}:${port}/companion${sessionParam}`;
    }
    const isLocalhost = ['localhost', '127.0.0.1', '::1', '[::1]'].includes(
      window.location.hostname
    );
    if (isLocalhost && networkInfo?.primaryIp) {
      return `http://${networkInfo.primaryIp}:${port}/companion${sessionParam}`;
    }
    return `${window.location.origin}/companion${sessionParam}`;
  }, [sessionState.activeSessionId, selectedLanIp, networkInfo, qrUseExternal, externalBaseUrl]);

  useEffect(() => {
    void QRCode.toDataURL(companionUrl, {
      width: 280,
      margin: 2,
      color: { dark: '#07090E', light: '#FFFFFF' },
    }).then(setQrDataUrl);
  }, [companionUrl]);

  const loadKioskFriendsData = useCallback(async (userId?: string) => {
    const targetUserId = userId || activeUser?.id;
    if (!targetUserId) return;
    setKioskFriendsLoading(true);
    try {
      const [identRes, friendsRes, feedRes] = await Promise.all([
        fetch(`/api/friends/identity/${encodeURIComponent(targetUserId)}`),
        fetch(`/api/friends/${encodeURIComponent(targetUserId)}`),
        fetch(`/api/friends/feed/${encodeURIComponent(targetUserId)}?limit=25`),
      ]);
      if (identRes.ok) {
        const identData = await identRes.json();
        setKioskFriendIdentity(identData);
        if (identData.npub) {
          const qr = await QRCode.toDataURL(identData.npub, {
            width: 280,
            margin: 2,
            color: { dark: '#07090E', light: '#FFFFFF' },
          });
          setKioskFriendQrDataUrl(qr);
        }
      }
      if (friendsRes.ok) {
        setKioskFriendsList(await friendsRes.json());
      }
      if (feedRes.ok) {
        setKioskFriendsFeed(await feedRes.json());
      }
    } catch (err) {
      console.warn('Failed to load kiosk friends data:', err);
    } finally {
      setKioskFriendsLoading(false);
    }
  }, [activeUser?.id]);

  useEffect(() => {
    if (activeUser?.id) {
      void loadKioskFriendsData(activeUser.id);
    }
  }, [activeUser?.id, loadKioskFriendsData]);

  const handleKioskSyncFriends = async () => {
    if (!activeUser?.id) return;
    setKioskFriendsSyncing(true);
    try {
      await fetch(`/api/friends/sync/${encodeURIComponent(activeUser.id)}`, { method: 'POST' });
      await loadKioskFriendsData(activeUser.id);
    } catch (err) {
      console.warn('Failed to sync friends feed:', err);
    } finally {
      setKioskFriendsSyncing(false);
    }
  };

  const handleKioskAddFriend = async () => {
    if (!activeUser?.id || !kioskAddFriendCode.trim()) return;
    try {
      const res = await fetch(`/api/friends/${encodeURIComponent(activeUser.id)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          npubOrHex: kioskAddFriendCode.trim(),
          displayName: kioskAddFriendNick.trim() || undefined,
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        setKioskAddFriendMsg({ ok: false, text: data.error || 'Failed to add friend' });
      } else {
        setKioskAddFriendMsg({ ok: true, text: 'Friend added! Syncing workouts...' });
        setKioskAddFriendCode('');
        setKioskAddFriendNick('');
        await handleKioskSyncFriends();
      }
    } catch {
      setKioskAddFriendMsg({ ok: false, text: 'Network error adding friend' });
    }
  };

  const handleKioskReact = async (activityId: string, targetPubkey: string, emoji: string) => {
    if (!activeUser?.id) return;
    try {
      await fetch(`/api/friends/react/${encodeURIComponent(activeUser.id)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ targetEventId: activityId, targetPubkey, emoji }),
      });
      const feedRes = await fetch(`/api/friends/feed/${encodeURIComponent(activeUser.id)}?limit=25`);
      if (feedRes.ok) setKioskFriendsFeed(await feedRes.json());
    } catch (err) {
      console.warn('Failed to react:', err);
    }
  };

  const handleKioskToggleShareWorkouts = async (share: boolean) => {
    if (!activeUser?.id || !kioskFriendIdentity) return;
    try {
      const res = await fetch(`/api/friends/identity/${encodeURIComponent(activeUser.id)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ shareWorkouts: share }),
      });
      if (res.ok) {
        setKioskFriendIdentity((prev) => (prev ? { ...prev, shareWorkouts: share } : prev));
      }
    } catch (err) {
      console.warn('Failed to update share workouts:', err);
    }
  };

  const partnerUser = useMemo(() => {
    if (!activeUser || users.length < 2) return null;
    return users.find((u) => u.id !== activeUser.id) ?? null;
  }, [users, activeUser]);

  const isLightTheme =
    activeUser?.themeMode === 'light' ||
    (activeUser?.themeMode === 'system' &&
      typeof window !== 'undefined' &&
      window.matchMedia('(prefers-color-scheme: light)').matches);

  // Arm alignment check against current exercise
  const armsAligned = useMemo(() => {
    if (!activeExercise) return true;
    return (
      armsMatchExercise(activeExercise, telemetry.arm_positions) &&
      telemetry.accessory === (activeAccessory ?? activeExercise.accessory)
    );
  }, [activeExercise, activeAccessory, telemetry.arm_positions, telemetry.accessory]);

  const muscleGroups = useMemo(
    () => ['ALL', ...Array.from(new Set(exercises.map((e) => e.muscleGroup)))],
    [exercises]
  );

  const filteredExercises = useMemo(() => {
    return exercises.filter((e) => {
      if (muscleFilter !== 'ALL' && e.muscleGroup !== muscleFilter) return false;
      if (accessoryFilter !== 'ALL' && e.accessory !== accessoryFilter) return false;
      if (
        exerciseSearch.trim() &&
        !e.name.toLowerCase().includes(exerciseSearch.trim().toLowerCase())
      ) {
        return false;
      }
      return true;
    });
  }, [exercises, muscleFilter, accessoryFilter, exerciseSearch]);

  const groupedCatalogExercises = useMemo(() => {
    const groups = new Map<string, Exercise[]>();
    for (const ex of filteredExercises) {
      const group = ex.muscleGroup || 'Other';
      const list = groups.get(group) ?? [];
      list.push(ex);
      groups.set(group, list);
    }
    return Array.from(groups.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  }, [filteredExercises]);

  const prByExerciseId = useMemo(() => {
    const map = new Map<string, (typeof personalRecords)[number]>();
    for (const pr of personalRecords) {
      map.set(pr.exerciseId, pr);
    }
    return map;
  }, [personalRecords]);

  // SVG Gauge Math
  const radius = 276;
  const circumference = 2 * Math.PI * radius;
  const accentTextColor = getContrastTextColor(activeUser?.accentColor ?? '#00F0FF');
  const nextStep =
    activeTemplateName && currentStepIdx + 1 < routineSteps.length
      ? routineSteps[currentStepIdx + 1]
      : null;
  const nextExercise = nextStep
    ? exercises.find((e) => e.id === nextStep.exerciseId) ?? null
    : null;
  const shownWeightLb = telemetry.weight_enabled
    ? telemetry.applied_resistance_lb
    : telemetry.target_weight_lb;
  const displayWeight = formatDisplayWeight(shownWeightLb, activeUnit, telemetry.weight_enabled);
  const displayTarget = formatDisplayWeight(telemetry.target_weight_lb, activeUnit);
  const progress = Math.min(Math.max(shownWeightLb / 200, 0.025), 1);
  const strokeDashoffset = circumference - progress * circumference;

  const stepSmall = activeUnit === 'KG' ? 0.5 : 1;

  const formatVelocity = (cmPerSec: number, unit: 'LB' | 'KG') => {
    if (unit === 'LB') {
      return { value: (cmPerSec / 2.54).toFixed(1), unitLabel: 'in/s' };
    }
    return { value: cmPerSec.toFixed(1), unitLabel: 'cm/s' };
  };
  const stepLarge = activeUnit === 'KG' ? 2.5 : 5;

  const triggerMockBle = async (action: 'toggle_weight' | 'spotify_next' | 'emergency_stop') => {
    const resp = await fetch('/api/daemon/mock/ble-event', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action }),
    });
    setMockStatusMsg(resp.ok ? `Sent BLE event: ${action}` : 'Daemon unavailable');
  };

  const triggerMockBarTilt = async () => {
    const resp = await fetch('/api/daemon/mock/bar-tilt', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ delta: 48.0, duration_sec: 0.7, auto_enable: true }),
    });
    setMockStatusMsg(
      resp.ok
        ? 'Injected 48lb cable tilt on BAR — cutout triggers after 250ms dwell!'
        : 'Daemon unavailable'
    );
    mockDialogRef.current?.close();
  };

  const musicProgressPct =
    musicState.durationMs > 0
      ? Math.min(100, (musicState.progressMs / musicState.durationMs) * 100)
      : 0;

  return (
    <div
      className={`relative swonal-canvas w-[1080px] h-[1920px] flex flex-col justify-between p-7 select-none overflow-hidden transition-colors duration-300 ${
        restSecondsRemaining > 0 ? 'rest-edge-glow' : ''
      }`}
      style={{ color: 'var(--text-primary)' }}
    >
      {/* Subtle Architectural Crosshair / Blueprint Grid & Anodized Grain */}
      <div
        aria-hidden="true"
        className="swonal-dot-grid absolute inset-0 pointer-events-none z-0"
      />
      <div
        aria-hidden="true"
        className="swonal-noise-grain absolute inset-0 pointer-events-none z-0"
      />
      {/* Dynamic Ambient Profile Bloom (zero-blur radial gradient for smooth FPS) */}
      <div
        aria-hidden="true"
        className="absolute -top-32 -right-32 w-[680px] h-[680px] rounded-full opacity-80 pointer-events-none z-0"
        style={{ background: 'radial-gradient(circle, var(--accent-muted) 0%, transparent 70%)' }}
      />
      <div
        aria-hidden="true"
        className="absolute -bottom-44 -left-44 w-[520px] h-[520px] rounded-full opacity-40 pointer-events-none z-0"
        style={{ background: 'radial-gradient(circle, var(--accent-muted) 0%, transparent 70%)' }}
      />

      {/* TOP STATUS BAR */}
      <header className="relative surface-card rounded-3xl px-6 py-4 flex items-center justify-between gap-4 z-30">
        {/* Household Profile Quick Switcher */}
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2">
            {users.map((u) => {
              const isActive = u.id === activeUser?.id;
              return (
                <button
                  key={u.id}
                  type="button"
                  onClick={() => void switchUser(u.id)}
                  className={`flex items-center gap-2.5 px-4 py-2.5 rounded-2xl transition font-bold ${
                    isActive
                      ? 'ring-2 shadow-lg'
                      : 'opacity-70 hover:opacity-100 elevated-pill'
                  }`}
                  style={
                    isActive
                      ? {
                          backgroundColor: 'var(--accent-muted)',
                          borderColor: u.accentColor,
                          boxShadow: `0 0 20px var(--accent-glow)`,
                        }
                      : undefined
                  }
                >
                  <span
                    className="w-9 h-9 rounded-full flex items-center justify-center text-sm font-black"
                    style={{
                      backgroundColor: u.accentColor,
                      color: getContrastTextColor(u.accentColor),
                    }}
                  >
                    {u.name.slice(0, 1).toUpperCase()}
                  </span>
                  <span className="text-base">{u.name}</span>
                </button>
              );
            })}
          </div>

          {/* Partner Work-In Mode Toggle */}
          {users.length >= 2 && (
            <button
              type="button"
              onClick={() => togglePartnerMode(!sessionState.partnerModeEnabled)}
              className="flex items-center gap-2 px-4 py-3 rounded-2xl text-sm font-extrabold transition elevated-pill"
              style={
                sessionState.partnerModeEnabled
                  ? {
                      backgroundColor: 'var(--accent-primary)',
                      color: accentTextColor,
                      boxShadow: '0 0 18px var(--accent-glow)',
                    }
                  : undefined
              }
            >
              <Users size={18} />
              <span>
                {sessionState.partnerModeEnabled && partnerUser
                  ? `Work In: ${activeUser?.name} ⇄ ${partnerUser.name}`
                  : 'Work In'}
              </span>
            </button>
          )}
        </div>

        {/* Right Utility Controls (Consolidated Quick-Settings Popover + AirPods Voice + Phone + Hardware + Original Tonal) */}
        <div className="flex items-center gap-2.5">
          {/* Prominent Hands-Free AirPods / Voice Control Toggle Button */}
          <button
            type="button"
            onClick={toggleVoiceListening}
            aria-pressed={isListening}
            title="Hands-Free AirPods Voice Control & Live Audio Coach"
            className={`px-4 py-3 rounded-2xl flex items-center gap-2 text-sm font-extrabold transition ${
              isListening
                ? 'bg-red-500 text-white shadow-lg ring-2 ring-red-400/60 animate-pulse'
                : 'elevated-pill hover:opacity-90'
            }`}
          >
            {isListening ? (
              <Mic size={18} />
            ) : (
              <Mic size={18} style={{ color: 'var(--accent-ink)' }} />
            )}
            <span>{isListening ? 'Voice Active' : 'AirPods Voice'}</span>
            {isListening && (
              <span className="w-2 h-2 rounded-full bg-white animate-ping" />
            )}
          </button>

          {/* Spotify & On-Device Music Trigger */}
          <button
            type="button"
            onClick={() => {
              void refreshMusicNow();
              musicDialogRef.current?.showModal();
            }}
            aria-label="Open Music & Speakers"
            title="Spotify Playlists & Tonal Speakers"
            className="elevated-pill px-4 py-3 rounded-2xl flex items-center gap-2 text-sm font-bold hover:opacity-90 transition"
          >
            <Music size={18} style={{ color: 'var(--accent-ink)' }} />
            <span className="max-w-[120px] truncate">
              {musicState.isPlaying && musicState.trackName
                ? musicState.trackName
                : 'Music'}
            </span>
            {musicState.isPlaying && (
              <span
                className="w-2 h-2 rounded-full animate-pulse shrink-0"
                style={{ backgroundColor: 'var(--accent-primary)' }}
              />
            )}
          </button>

          {/* Quick Settings Popover Trigger (Theme, Units, Accent Color / Profiles) */}
          <div ref={quickSettingsRef} className="relative">
            <button
              type="button"
              onClick={() => setShowQuickSettings((v) => !v)}
              aria-label="Quick Settings"
              title="Customize Theme, Units, & Accent Color"
              className="elevated-pill px-4 py-3 rounded-2xl flex items-center gap-2 text-sm font-bold hover:opacity-90 transition"
            >
              <Settings size={20} style={{ color: 'var(--accent-ink)' }} />
            </button>

            {showQuickSettings && (
              <div
                className="absolute right-0 top-14 w-72 rounded-2xl p-4 shadow-2xl border space-y-3 z-40"
                style={{
                  backgroundColor: 'var(--bg-surface-solid)',
                  borderColor: 'var(--border-top-highlight)',
                }}
              >
                <div className="flex items-center justify-between">
                  <span className="text-xs uppercase tracking-widest font-bold opacity-70">
                    Quick Preferences
                  </span>
                  <button
                    type="button"
                    onClick={() => setShowQuickSettings(false)}
                    className="opacity-70 hover:opacity-100"
                  >
                    <X size={16} />
                  </button>
                </div>

                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    onClick={() => {
                      if (!activeUser) return;
                      void updateUserPreferences(activeUser.id, {
                        weightUnit: activeUnit === 'LB' ? 'KG' : 'LB',
                      });
                    }}
                    className="elevated-pill py-3 px-3 rounded-xl font-mono font-bold text-sm flex items-center justify-center gap-1.5"
                  >
                    <span>Units: {activeUnit}</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      if (!activeUser) return;
                      void updateUserPreferences(activeUser.id, {
                        themeMode: isLightTheme ? 'dark' : 'light',
                      });
                    }}
                    className="elevated-pill py-3 px-3 rounded-xl font-bold text-sm flex items-center justify-center gap-1.5"
                  >
                    {isLightTheme ? <Moon size={16} /> : <Sun size={16} />}
                    <span>{isLightTheme ? 'Light' : 'Dark'}</span>
                  </button>
                </div>

                <button
                  type="button"
                  onClick={() => setLiveVoiceCoachEnabled((v) => !v)}
                  className="w-full elevated-pill py-2.5 px-3 rounded-xl font-bold text-xs flex items-center justify-between"
                >
                  <span>Live Audio Coach (AirPods)</span>
                  <span style={{ color: 'var(--accent-ink)' }}>
                    {liveVoiceCoachEnabled ? 'Enabled' : 'Muted'}
                  </span>
                </button>

                <button
                  type="button"
                  onClick={() => setShowDemoPip(!showDemoPip)}
                  className="w-full elevated-pill py-2.5 px-3 rounded-xl font-bold text-xs flex items-center justify-between"
                >
                  <span>Form Demo Video</span>
                  <span style={{ color: 'var(--accent-ink)' }}>
                    {showDemoPip ? 'Shown' : 'Hidden'}
                  </span>
                </button>

                <button
                  type="button"
                  onClick={() => {
                    const next =
                      telemetryDisplayMode === 'auto'
                        ? 'always'
                        : telemetryDisplayMode === 'always'
                          ? 'off'
                          : 'auto';
                    setTelemetryDisplayMode(next);
                    if (typeof window !== 'undefined') {
                      window.localStorage.setItem('swonal_telemetry_display', next);
                    }
                  }}
                  className="w-full elevated-pill py-2.5 px-3 rounded-xl font-bold text-xs flex items-center justify-between"
                >
                  <span>VBT &amp; Symmetry Meters</span>
                  <span style={{ color: 'var(--accent-ink)' }}>
                    {telemetryDisplayMode === 'always'
                      ? 'Always'
                      : telemetryDisplayMode === 'off'
                        ? 'Off'
                        : 'During Set'}
                  </span>
                </button>

                <button
                  type="button"
                  onClick={() => {
                    setShowQuickSettings(false);
                    profileDialogRef.current?.showModal();
                  }}
                  className="w-full elevated-pill py-3 px-3 rounded-xl font-bold text-sm flex items-center justify-center gap-2"
                >
                  <Palette size={17} style={{ color: 'var(--accent-ink)' }} />
                  <span>Accent Color &amp; Profiles</span>
                </button>

                <button
                  type="button"
                  onClick={() => {
                    setShowQuickSettings(false);
                    void loadKioskFriendsData();
                    friendsDialogRef.current?.showModal();
                  }}
                  className="w-full elevated-pill py-3 px-3 rounded-xl font-bold text-sm flex items-center justify-center gap-2 hover:opacity-90"
                >
                  <Users size={16} style={{ color: 'var(--accent-ink)' }} />
                  <span>Swocial</span>
                </button>

                <a
                  href="/history"
                  className="w-full elevated-pill py-3 px-3 rounded-xl font-bold text-sm flex items-center justify-center gap-2 hover:opacity-90"
                >
                  <TrendingUp size={16} style={{ color: 'var(--accent-ink)' }} />
                  <span>History &amp; PRs</span>
                </a>

                <a
                  href="/settings"
                  className="w-full py-3 px-3 rounded-xl font-black text-sm flex items-center justify-center gap-2 transition hover:opacity-95"
                  style={{
                    backgroundColor: 'var(--accent-primary)',
                    color: accentTextColor,
                  }}
                >
                  <Settings size={16} />
                  <span>Application Settings →</span>
                </a>
              </div>
            )}
          </div>

          {/* Swocial Friends Feed (Icon-Only) */}
          <button
            type="button"
            onClick={() => {
              setFriendsKioskTab('feed');
              void loadKioskFriendsData();
              friendsDialogRef.current?.showModal();
            }}
            aria-label="Open Swocial Feed"
            title="Swocial Feed"
            className="elevated-pill p-3 rounded-2xl flex items-center justify-center hover:opacity-90 transition"
          >
            <Users size={18} style={{ color: 'var(--accent-ink)' }} />
          </button>

          {/* Companion QR Remote Modal */}
          <button
            type="button"
            onClick={() => qrDialogRef.current?.showModal()}
            title="Open Companion QR Code Remote"
            className="elevated-pill px-4 py-3 rounded-2xl flex items-center gap-2 text-sm font-bold hover:opacity-90 transition"
          >
            <QrCode size={18} style={{ color: 'var(--accent-ink)' }} />
            <span>Phone</span>
          </button>

          {/* Hardware Telemetry Status Dot (shows full Tonal CDC details on press) */}
          <button
            type="button"
            onClick={() => {
              void refreshHaStatus();
              void refreshCalibration();
              mockDialogRef.current?.showModal();
            }}
            aria-label={daemonConnected ? (isMockMode ? 'MOCK 30Hz' : 'TONAL CDC 30Hz') : 'DAEMON WAIT'}
            title={
              daemonConnected
                ? `${isMockMode ? 'MOCK 30Hz' : 'TONAL CDC 30Hz'} — Tap for Hardware Diagnostics`
                : 'DAEMON WAIT — Tap for Hardware Diagnostics'
            }
            className={`p-3 rounded-2xl border flex items-center justify-center ${
              daemonConnected && wsConnected
                ? ''
                : 'bg-amber-500/10 border-amber-500/40 text-amber-400'
            }`}
            style={
              daemonConnected && wsConnected
                ? {
                    backgroundColor: 'var(--accent-muted)',
                    borderColor: 'var(--accent-primary)',
                    color: 'var(--accent-ink)',
                  }
                : undefined
            }
          >
            <span
              className={`w-3 h-3 rounded-full ${
                daemonConnected ? 'animate-pulse' : 'bg-amber-400'
              }`}
              style={daemonConnected ? { backgroundColor: 'var(--accent-primary)' } : undefined}
            />
          </button>
        </div>
      </header>

      {/* NOTIFICATION BANNERS (AIRPODS VOICE TOAST / QUEUED WORKOUT / SAFETY CUTOUT / PR CELEBRATION) */}
      <div className="space-y-3 my-3">
        {(isListening || voiceToast) && (
          <div
            className="surface-card rounded-2xl px-6 py-3.5 flex items-center justify-between gap-4 border"
            style={{
              borderColor: isListening ? '#EF4444' : 'var(--accent-primary)',
              boxShadow: isListening
                ? '0 0 24px rgba(239, 68, 68, 0.25)'
                : '0 0 20px var(--accent-glow)',
            }}
          >
            <div className="flex items-center gap-3 min-w-0 flex-1">
              <div
                className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 ${
                  isListening ? 'bg-red-500 text-white animate-pulse' : ''
                }`}
                style={
                  !isListening
                    ? { backgroundColor: 'var(--accent-primary)', color: accentTextColor }
                    : undefined
                }
              >
                <Mic size={20} />
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-xs uppercase tracking-widest font-black opacity-80">
                    {isListening ? 'AirPods Hands-Free Listening' : 'Voice Coach Executed'}
                  </span>
                  {voiceToast?.actionLabel && (
                    <span
                      className="px-2.5 py-0.5 rounded-full text-[11px] font-mono font-black"
                      style={{
                        backgroundColor: 'var(--accent-muted)',
                        color: 'var(--accent-ink)',
                      }}
                    >
                      {voiceToast.actionLabel}
                    </span>
                  )}
                </div>
                <p className="text-sm font-extrabold truncate mt-0.5">
                  {voiceToast
                    ? `"${voiceToast.heard}" → ${voiceToast.reply}`
                    : coachPrompt
                      ? `Hearing: "${coachPrompt}"...`
                      : coachReply}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <button
                type="button"
                onClick={() => setLiveVoiceCoachEnabled((v) => !v)}
                className="elevated-pill px-3 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5"
                title="Toggle real-time rep & VBT audio encouragement"
              >
                {liveVoiceCoachEnabled ? <Volume2 size={15} /> : <VolumeX size={15} />}
                <span>{liveVoiceCoachEnabled ? 'Live Cues ON' : 'Live Cues OFF'}</span>
              </button>
              {voiceToast && !isListening && (
                <button
                  type="button"
                  onClick={() => setVoiceToast(null)}
                  className="elevated-pill p-2 rounded-xl opacity-75 hover:opacity-100"
                  aria-label="Dismiss Voice Toast"
                >
                  <X size={15} />
                </button>
              )}
            </div>
          </div>
        )}
        {sessionState.queuedWorkout && (
          <div
            className="surface-card rounded-2xl px-6 py-4 flex items-center justify-between border-2"
            style={{
              borderColor: 'var(--accent-primary)',
              boxShadow: '0 0 28px var(--accent-glow)',
            }}
          >
            <div className="flex items-center gap-3">
              <Sparkles size={24} style={{ color: 'var(--accent-ink)' }} />
              <div>
                <p className="text-xs uppercase tracking-widest font-bold opacity-70">
                  Queued From Companion / AI Coach
                </p>
                <p className="text-xl font-extrabold">{sessionState.queuedWorkout.templateName}</p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={() => sendWs({ type: 'clear_queued_workout' })}
                className="elevated-pill px-4 py-2.5 rounded-xl font-semibold text-sm"
              >
                Dismiss
              </button>
              <button
                type="button"
                onClick={() =>
                  void startWorkoutTemplate({
                    id: sessionState.queuedWorkout!.templateId,
                    name: sessionState.queuedWorkout!.templateName,
                    blocks: sessionState.queuedWorkout!.blocks,
                    userId: sessionState.queuedWorkout!.userId,
                  })
                }
                className="px-6 py-2.5 rounded-xl font-extrabold text-slate-950"
                style={{ backgroundColor: 'var(--accent-primary)' }}
              >
                Start Workout
              </button>
            </div>
          </div>
        )}

        {safetyEvent && (
          <div className="bg-red-500/15 border-2 border-red-500 rounded-2xl px-6 py-4 flex items-center justify-between text-red-300">
            <div className="flex items-center gap-3">
              <AlertTriangle size={28} className="text-red-400 shrink-0" />
              <div>
                <p className="text-lg font-black tracking-wide">
                  SAFETY CUTOUT TRIGGERED: {safetyEvent.reason}
                </p>
                <p className="text-sm opacity-90">
                  Resistance safely disabled
                  {safetyEvent.delta !== undefined
                    ? ` (Bar cable imbalance Δ ${safetyEvent.delta})`
                    : ''}
                  . Level the bar before re-engaging weight.
                </p>
              </div>
            </div>
            <button
              type="button"
              onClick={clearSafetyEvent}
              className="px-4 py-2 rounded-xl bg-red-500/30 hover:bg-red-500/40 font-bold text-sm"
            >
              Acknowledge
            </button>
          </div>
        )}
      </div>

      {/* CENTER WORKOUT STAGE */}
      <main className="flex-1 flex flex-col justify-between py-2">
        {/* Active Exercise & Routine Header Card */}
        <section className="surface-card rounded-3xl px-8 py-6 flex items-center justify-between gap-6">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-3 mb-2">
              <span
                className="px-4 py-1.5 rounded-full text-sm font-extrabold uppercase tracking-wider whitespace-nowrap shrink-0"
                style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
              >
                {activeTemplateName
                  ? `${activeTemplateName} • ${currentStep?.blockType.replace('_', ' ')} • Step ${currentStepIdx + 1}/${routineSteps.length}`
                  : 'Free Lift Mode'}
              </span>
              {activeExercise && (
                <span className="text-base font-bold opacity-80 whitespace-nowrap shrink-0">
                  {activeExercise.muscleGroup}
                </span>
              )}
            </div>
            <h1 className="text-4xl font-black tracking-tight leading-tight">
              {activeExercise?.name ?? 'Select Movement'}
            </h1>
            <div className="mt-2 text-lg font-bold opacity-85">
              {currentStep ? (
                <span>
                  Set {currentStep.setNumber} of {currentStep.totalSetsInItem} • Target{' '}
                  {currentStep.targetReps} reps
                </span>
              ) : (
                <span>{activeExercise?.notes || 'Tap weight gauge or use handle click to engage'}</span>
              )}
            </div>
          </div>

          <div className="w-[316px] flex flex-col items-stretch justify-center gap-2.5 shrink-0">
            <button
              type="button"
              onClick={() => {
                setLibraryOpen(true);
                libraryDialogRef.current?.showModal();
              }}
              className="w-full elevated-pill px-4 py-2.5 rounded-2xl font-bold text-sm flex items-center justify-center gap-2 hover:opacity-90 whitespace-nowrap"
            >
              <Dumbbell size={17} style={{ color: 'var(--accent-ink)' }} />
              <span>Routines &amp; Exercises</span>
            </button>

            {/* Arm Position Guide Badge + H/R/A Info Bubble */}
            {activeExercise && (
              <div className="relative w-full">
                <div
                  role="button"
                  tabIndex={0}
                  onClick={() => void alignMockArmsToExercise(activeExercise)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      void alignMockArmsToExercise(activeExercise);
                    }
                  }}
                  title={armsAligned ? 'Arms aligned — tap to re-verify' : 'Tap to sync arms to recommended position'}
                  className={`w-full px-4 py-2.5 rounded-2xl border text-sm font-mono font-bold flex items-center justify-center gap-2 cursor-pointer hover:opacity-90 whitespace-nowrap ${
                    armsAligned
                      ? ''
                      : isLightTheme
                        ? 'bg-amber-500/15 border-amber-600/45 text-amber-900'
                        : 'bg-amber-500/15 border-amber-500/40 text-amber-400'
                  }`}
                  style={
                    armsAligned
                      ? {
                          backgroundColor: 'var(--accent-muted)',
                          borderColor: 'var(--accent-ink)',
                          color: 'var(--accent-ink)',
                        }
                      : undefined
                  }
                >
                  <CheckCircle2 size={18} />
                  <span>
                    ARMS {armsAligned ? 'ALIGNED' : 'ADJUST'} • {formatArmTarget(activeExercise)} •{' '}
                    {activeAccessory ?? activeExercise.accessory}
                  </span>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      setShowArmInfo((v) => !v);
                    }}
                    aria-label="Explain Arm Position Codes"
                    className={`ml-1 bg-transparent border-0 p-1 rounded-full opacity-80 hover:opacity-100 transition ${
                      isLightTheme ? 'hover:bg-black/10' : 'hover:bg-white/10'
                    }`}
                  >
                    <Info size={16} />
                  </button>
                </div>
                {showArmInfo && (
                  <div
                    className="absolute right-0 top-14 w-80 rounded-2xl p-4 shadow-2xl border text-xs space-y-1.5 z-40"
                    style={{
                      backgroundColor: 'var(--bg-surface-solid)',
                      borderColor: 'var(--border-top-highlight)',
                    }}
                  >
                    <div className="flex items-center justify-between font-bold uppercase tracking-wider opacity-75">
                      <span>Arm Position Guide</span>
                      <button type="button" onClick={() => setShowArmInfo(false)}>
                        <X size={14} />
                      </button>
                    </div>
                    <p><strong>H (Height):</strong> Vertical rail notch (1 bottom – 9 top)</p>
                    <p><strong>R (Rotation):</strong> Arm swing latch (2 – 6; 1 = stowed)</p>
                    <p><strong>A (Angle):</strong> Arm tilt (1 pointing down – 5 pointing up; 0 = folded)</p>
                    <p className="opacity-70">A range like R4–5 means any of those positions works.</p>
                  </div>
                )}
              </div>
            )}

            {/* Consolidated Attachment Selector */}
            <div className="w-full grid grid-cols-3 gap-2">
              {(['HANDLES', 'BAR', 'ROPE'] as AccessoryType[]).map((acc) => {
                const active = telemetry.accessory === acc;
                return (
                  <button
                    key={acc}
                    type="button"
                    onClick={() => setAccessory(acc)}
                    className={`px-3 py-2 rounded-xl font-mono text-xs font-black text-center ${
                      active ? 'border-2' : 'elevated-pill opacity-75 hover:opacity-100'
                    }`}
                    style={
                      active
                        ? {
                            borderColor: 'var(--accent-ink)',
                            backgroundColor: 'var(--accent-muted)',
                            color: 'var(--accent-ink)',
                          }
                        : undefined
                    }
                  >
                    {acc}
                  </button>
                );
              })}
            </div>
          </div>
        </section>

        {/* Quick-Access Routine / Movement Stepper Bar + Docked Form Demo */}
        <div className="flex items-center justify-between gap-4 px-2 mt-5 h-36">
          <button
            type="button"
            onClick={() => stepRoutine(-1)}
            className="elevated-pill px-5 py-2.5 rounded-2xl font-bold text-sm flex items-center gap-1.5 hover:opacity-90 shrink-0"
          >
            <ChevronLeft size={18} style={{ color: 'var(--accent-ink)' }} />
            <span>{activeTemplateName ? 'Prev Step' : 'Prev Exercise'}</span>
          </button>

          {activeExercise?.mediaUrl && showDemoPip ? (
            <div className="surface-card rounded-2xl px-4 py-3 flex items-center gap-4 max-w-xl">
              <div className="w-44 h-28 rounded-xl overflow-hidden bg-slate-950 shrink-0 flex items-center justify-center">
                {/\.(mp4|webm)(\?|$)/i.test(activeExercise.mediaUrl) ? (
                  <video
                    src={activeExercise.mediaUrl}
                    autoPlay
                    loop
                    muted
                    playsInline
                    className="w-full h-full object-contain"
                  />
                ) : (
                  <img
                    src={activeExercise.mediaUrl}
                    alt={`${activeExercise.name} form demo`}
                    className="w-full h-full object-contain"
                  />
                )}
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-[11px] font-mono font-bold uppercase tracking-wider opacity-60">
                  Form Demo
                </p>
                <p className="text-sm font-extrabold truncate">{activeExercise.name}</p>
              </div>
              <button
                type="button"
                onClick={() => setShowDemoPip(false)}
                aria-label="Close Form Demo"
                className="opacity-60 hover:opacity-100 p-1.5 shrink-0"
              >
                <X size={16} />
              </button>
            </div>
          ) : activeTemplateName && routineSteps.length > 0 ? (
            <div className="text-xs font-mono font-bold uppercase tracking-widest opacity-70 text-center">
              {`${activeTemplateName} • Step ${currentStepIdx + 1} / ${routineSteps.length}`}
            </div>
          ) : null}

          <button
            type="button"
            onClick={() => stepRoutine(1)}
            className="elevated-pill px-5 py-2.5 rounded-2xl font-bold text-sm flex items-center gap-1.5 hover:opacity-90 shrink-0"
          >
            <span>{activeTemplateName ? 'Next Step' : 'Next Exercise'}</span>
            <ChevronRight size={18} style={{ color: 'var(--accent-ink)' }} />
          </button>
        </div>

        {/* Central ~620px Weight Dial flanked closely by Left/Right Cable ROM Bars + Direct Steppers */}
        <section className="relative flex flex-col items-center justify-center my-auto py-2 gap-4">

          {/* Flanking ROM Bars + 620px Dial Row */}
          <div className="flex items-center justify-center gap-8">
            {/* Left Cable ROM & Velocity Bar */}
            <KioskCableBar
              side="left"
              label="LEFT"
              activeUnit={activeUnit}
              initialCable={telemetry.left_cable}
              subscribeLiveTelemetry={subscribeLiveTelemetry}
            />

            {/* ~620px Interactive SVG Weight Gauge & Tap Target */}
            <div className="relative flex items-center justify-center">
              <div
                aria-hidden="true"
                className={`absolute w-[760px] h-[760px] rounded-full pointer-events-none transition-all duration-500 ${
                  telemetry.weight_enabled ? 'opacity-95 scale-105' : 'opacity-25 scale-100'
                }`}
                style={{
                  background: 'radial-gradient(circle, var(--accent-glow) 0%, transparent 66%)',
                }}
              />
              <svg className="w-[620px] h-[620px] -rotate-90" viewBox="0 0 620 620">
                <circle
                  cx="310"
                  cy="310"
                  r={radius}
                  stroke="var(--bg-elevated)"
                  strokeWidth="36"
                  fill="transparent"
                />
                {/* Concentric Compass / Load-Cell Tick Ring */}
                <DialTickRing enabled={telemetry.weight_enabled} />
                <circle
                  cx="310"
                  cy="310"
                  r={radius}
                  stroke={telemetry.weight_enabled ? 'var(--accent-primary)' : 'var(--text-muted)'}
                  strokeOpacity={telemetry.weight_enabled ? 1 : 0.35}
                  strokeWidth="36"
                  strokeDasharray={circumference}
                  strokeDashoffset={strokeDashoffset}
                  strokeLinecap="round"
                  fill="transparent"
                  className="transition-all duration-100"
                />
              </svg>

              <button
                type="button"
                onClick={toggleWeight}
                aria-label="Toggle Weight Resistance"
                className={`absolute w-[476px] h-[476px] rounded-full flex flex-col items-center justify-center transition-all duration-300 border-4 ${
                  telemetry.weight_enabled ? 'accent-glow-ring scale-[1.02]' : 'surface-card'
                }`}
                style={{
                  borderColor: telemetry.weight_enabled
                    ? 'var(--accent-primary)'
                    : 'var(--border-subtle)',
                  background: telemetry.weight_enabled
                    ? 'radial-gradient(circle at 50% 35%, var(--accent-muted) 0%, var(--bg-surface-solid) 100%)'
                    : 'var(--bg-dial-idle)',
                  boxShadow: telemetry.weight_enabled
                    ? undefined
                    : 'inset 0 1px 0 rgba(255,255,255,0.09), inset 0 -10px 28px rgba(0,0,0,0.35)',
                }}
              >
                <span
                  role="button"
                  tabIndex={0}
                  title="Tap to enter exact weight"
                  onClick={(e) => {
                    e.stopPropagation();
                    const currentShown =
                      activeUnit === 'KG'
                        ? displayWeight.toFixed(1).replace(/\.0$/, '')
                        : String(Math.round(displayWeight));
                    setWeightInputDraft(currentShown);
                    weightDialogRef.current?.showModal();
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      e.stopPropagation();
                      const currentShown =
                        activeUnit === 'KG'
                          ? displayWeight.toFixed(1).replace(/\.0$/, '')
                          : String(Math.round(displayWeight));
                      setWeightInputDraft(currentShown);
                      weightDialogRef.current?.showModal();
                    }
                  }}
                  className="font-mono tabular-nums text-[9rem] leading-none font-black tracking-tighter hover:opacity-80 cursor-pointer select-none"
                >
                  {activeUnit === 'KG'
                    ? displayWeight.toFixed(1).replace(/\.0$/, '')
                    : Math.round(displayWeight)}
                </span>
                <span className="text-xl uppercase tracking-[0.22em] font-extrabold opacity-80 mt-2">
                  {telemetry.weight_enabled ? `Applied ${activeUnit}` : `Target ${activeUnit}`}
                </span>
                <div
                  className={`mt-6 px-10 py-4 rounded-full font-black text-lg tracking-widest uppercase border-2 shadow-lg transition ${
                    telemetry.weight_enabled ? 'animate-pulse' : 'elevated-pill'
                  }`}
                  style={
                    telemetry.weight_enabled
                      ? {
                          backgroundColor: 'var(--accent-primary)',
                          borderColor: 'var(--accent-primary)',
                          color: accentTextColor,
                        }
                      : { borderColor: 'var(--border-top-highlight)' }
                  }
                >
                  {telemetry.weight_enabled ? 'WEIGHT ON' : 'WEIGHT OFF'}
                </div>
              </button>

              {/* Peripheral Edge-Glow Rest Timer Overlay */}
              {restSecondsRemaining > 0 && (
                <div
                  className="absolute -bottom-4 px-8 py-4 rounded-3xl surface-card border-2 flex items-center gap-6 z-20"
                  style={{
                    borderColor: 'var(--accent-primary)',
                    boxShadow: '0 0 36px var(--accent-glow)',
                  }}
                >
                  <div>
                    <p className="text-xs uppercase tracking-widest font-bold opacity-70">
                      Rest Timer
                    </p>
                    <p
                      className="font-mono tabular-nums text-4xl font-black"
                      style={{ color: 'var(--accent-ink)' }}
                    >
                      {Math.floor(restSecondsRemaining / 60)}:
                      {String(restSecondsRemaining % 60).padStart(2, '0')}
                    </p>
                  </div>
                  {recentFriendTicker && (
                    <div className="hidden lg:flex items-center gap-2 max-w-[280px] px-3.5 py-2 rounded-2xl elevated-pill text-xs font-bold truncate">
                      <span className="text-sm">🔥</span>
                      <span className="truncate">{recentFriendTicker}</span>
                    </div>
                  )}
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => extendRestTimer(15)}
                      className="elevated-pill px-5 py-3 rounded-xl font-bold text-base"
                    >
                      +15s
                    </button>
                    <button
                      type="button"
                      onClick={skipRestTimer}
                      className="px-5 py-3 rounded-xl font-bold text-base flex items-center gap-1.5"
                      style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                    >
                      <SkipForward size={18} />
                      <span>Skip</span>
                    </button>
                  </div>
                </div>
              )}
            </div>

            {/* Right Cable ROM & Velocity Bar */}
            <KioskCableBar
              side="right"
              label="RIGHT"
              activeUnit={activeUnit}
              initialCable={telemetry.right_cable}
              subscribeLiveTelemetry={subscribeLiveTelemetry}
            />
          </div>

          {/* Direct Weight Stepper Strip Directly Below Dial */}
          <div className="flex items-center justify-center gap-4">
            <button
              type="button"
              onClick={() =>
                setWeight(stepDisplayWeight(telemetry.target_weight_lb, -stepLarge, activeUnit))
              }
              className="elevated-pill min-w-[92px] min-h-[72px] px-6 py-4 rounded-2xl font-mono font-black text-3xl hover:opacity-85 transition"
            >
              -{stepLarge}
            </button>
            <button
              type="button"
              onClick={() =>
                setWeight(stepDisplayWeight(telemetry.target_weight_lb, -stepSmall, activeUnit))
              }
              className="elevated-pill min-w-[84px] min-h-[72px] px-6 py-4 rounded-2xl font-mono font-black text-3xl hover:opacity-85 transition"
            >
              -{stepSmall}
            </button>
            <span className="font-mono tabular-nums text-sm font-extrabold uppercase tracking-widest opacity-75 px-3 text-center min-w-[116px]">
              {activeExercisePr?.oneRepMaxEst
                ? `${Math.round((telemetry.target_weight_lb / activeExercisePr.oneRepMaxEst) * 100)}% 1RM`
                : telemetry.modifier_pct > 0
                  ? `+${telemetry.modifier_pct}% ${telemetry.profile_mode}`
                  : activeUnit === 'KG'
                    ? '2.5–90 KG'
                    : '5–200 LB'}
            </span>
            <button
              type="button"
              onClick={() =>
                setWeight(stepDisplayWeight(telemetry.target_weight_lb, stepSmall, activeUnit))
              }
              className="elevated-pill min-w-[84px] min-h-[72px] px-6 py-4 rounded-2xl font-mono font-black text-3xl hover:opacity-85 transition"
            >
              +{stepSmall}
            </button>
            <button
              type="button"
              onClick={() =>
                setWeight(stepDisplayWeight(telemetry.target_weight_lb, stepLarge, activeUnit))
              }
              className="elevated-pill min-w-[92px] min-h-[72px] px-6 py-4 rounded-2xl font-mono font-black text-3xl hover:opacity-85 transition"
            >
              +{stepLarge}
            </button>
          </div>

          {/* PR Link & Progressive Overload Readiness (Main Dial Stage) */}
          <div className="flex flex-wrap items-center justify-center gap-3 pt-1">
            <a
              href="/history"
              title="View History & Strength Progression"
              className="inline-flex items-center gap-2 px-4 py-2 rounded-xl elevated-pill font-mono tabular-nums text-sm font-extrabold hover:opacity-90"
              style={{ color: activeExercisePr ? 'var(--accent-primary)' : undefined }}
            >
              <Trophy size={16} />
              <span>
                {activeExercisePr
                  ? `PR 1RM: ${formatDisplayWeight(activeExercisePr.oneRepMaxEst, activeUnit)} ${activeUnit} (Max ${formatDisplayWeight(activeExercisePr.maxWeightLb, activeUnit)} ${activeUnit})`
                  : 'PR 1RM: Baseline Pending'}
              </span>
            </a>
            {lastPrBanner && (
              <span
                className="inline-flex items-center gap-2 px-4 py-2 rounded-xl font-black text-sm"
                style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
              >
                <Sparkles size={15} />
                <span>{lastPrBanner}</span>
              </span>
            )}
            {overloadSuggestion?.ready && (
              <button
                type="button"
                onClick={() =>
                  setWeight(
                    overloadSuggestion.recommendedWeightLb,
                    telemetry.profile_mode,
                    telemetry.modifier_pct
                  )
                }
                title={overloadSuggestion.reason}
                className="inline-flex items-center gap-2 px-4 py-2 rounded-xl border font-extrabold text-sm hover:opacity-90"
                style={{
                  backgroundColor: 'var(--accent-muted)',
                  borderColor: 'var(--accent-primary)',
                  color: 'var(--accent-ink)',
                }}
              >
                <TrendingUp size={16} />
                <span>
                  +{formatDisplayWeight(overloadSuggestion.recommendedBumpLb, activeUnit, true)}{' '}
                  {activeUnit} Ready
                </span>
              </button>
            )}
          </div>
        </section>

        {/* Conditional Live Symmetry & VBT Telemetry (Recessed, Lightweight Strip) */}
        {telemetryDisplayMode !== 'off' &&
          (telemetryDisplayMode === 'always' ||
            telemetry.weight_enabled ||
            telemetry.rep_count > 0) && (
          <section
            className="rounded-3xl px-6 py-4 mb-4 border grid grid-cols-2 gap-6 divide-x"
            style={{
              backgroundColor: 'rgba(255, 255, 255, 0.025)',
              borderColor: 'var(--border-subtle)',
            }}
          >
            {/* 1. Form Symmetry & Balance Meter */}
            <div className="space-y-2.5 pr-2">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Scale size={18} style={{ color: 'var(--accent-ink)' }} />
                  <h3 className="font-black text-sm uppercase tracking-wider">
                    Form Symmetry &amp; Balance
                  </h3>
                </div>
                <span
                  className={`px-2.5 py-0.5 rounded-full text-xs font-mono font-black ${
                    symmetryMetrics.status === 'critical'
                      ? 'bg-red-500/20 text-red-400'
                      : symmetryMetrics.status === 'drift'
                        ? 'bg-amber-500/20 text-amber-400'
                        : ''
                  }`}
                  style={
                    symmetryMetrics.status === 'balanced'
                      ? {
                          backgroundColor: 'var(--accent-muted)',
                          color: 'var(--accent-ink)',
                        }
                      : undefined
                  }
                >
                  {symmetryMetrics.status.toUpperCase()} • Δ {symmetryMetrics.positionDelta.toFixed(1)}
                </span>
              </div>
              <div className="relative h-5 flex items-center">
                {/* Center notch tick */}
                <div
                  className="absolute left-1/2 -translate-x-1/2 top-0 bottom-0 w-0.5 z-0 rounded-full"
                  style={{
                    backgroundColor: isLightTheme
                      ? 'rgba(15, 23, 42, 0.25)'
                      : 'rgba(255, 255, 255, 0.25)',
                  }}
                />
                <div className="relative w-full h-3.5 rounded-full elevated-pill overflow-hidden">
                  <div
                    className="absolute top-0.5 bottom-0.5 w-6 rounded-full transition-all duration-75 z-10"
                    style={{
                      left: `calc(${Math.max(4, Math.min(92, 50 + symmetryMetrics.signedBalance * 1.1))}% - 12px)`,
                      backgroundColor:
                        symmetryMetrics.status === 'critical'
                          ? '#EF4444'
                          : symmetryMetrics.status === 'drift'
                            ? '#F59E0B'
                            : 'var(--accent-primary)',
                    }}
                  />
                </div>
              </div>
              <div className="flex items-center justify-between text-xs font-mono tabular-nums font-bold opacity-75">
                <span>L {symmetryMetrics.leftLoadPct}%</span>
                <span>Centered Bar Target</span>
                <span>R {symmetryMetrics.rightLoadPct}%</span>
              </div>
            </div>

            {/* 2. Velocity-Based Training (VBT) & Auto-Regulation */}
            <div className="space-y-2.5 pl-6">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Gauge size={18} style={{ color: 'var(--accent-ink)' }} />
                  <h3 className="font-black text-sm uppercase tracking-wider">
                    Velocity &amp; Fatigue (VBT)
                  </h3>
                </div>
                {vbtMetrics.fatigueAlert ? (
                  <button
                    type="button"
                    onClick={() => setWeight(telemetry.target_weight_lb, 'spotter', 0)}
                    className={`px-3 py-1 rounded-xl border text-xs font-black flex items-center gap-1 ${
                      isLightTheme
                        ? 'bg-amber-500/20 border-amber-600/50 text-amber-900'
                        : 'bg-amber-500/25 border-amber-400/50 text-amber-300'
                    }`}
                  >
                    <Zap size={14} />
                    <span>Enable Spotter</span>
                  </button>
                ) : (
                  <span className="font-mono tabular-nums text-xs font-bold opacity-75">
                    Peak {formatVelocity(vbtMetrics.peakSetVelocity, activeUnit).value}{' '}
                    {formatVelocity(vbtMetrics.peakSetVelocity, activeUnit).unitLabel}
                  </span>
                )}
              </div>
              <div className="flex items-baseline justify-between font-mono tabular-nums">
                <span className="text-2xl font-black">
                  {formatVelocity(vbtMetrics.currentVelocity, activeUnit).value}{' '}
                  <span className="text-xs font-bold opacity-70">
                    {formatVelocity(vbtMetrics.currentVelocity, activeUnit).unitLabel}
                  </span>
                </span>
                <span
                  className={`text-base font-black ${
                    vbtMetrics.velocityDropPct >= 25 ? 'text-amber-400' : ''
                  }`}
                  style={
                    vbtMetrics.velocityDropPct < 25
                      ? { color: 'var(--accent-ink)' }
                      : undefined
                  }
                >
                  -{vbtMetrics.velocityDropPct.toFixed(0)}% Velocity Drop
                </span>
              </div>
              <div className="relative h-3.5 rounded-full elevated-pill overflow-hidden">
                <div
                  className="h-full rounded-full transition-all duration-75"
                  style={{
                    width: `${
                      vbtMetrics.currentVelocity >= 0.5
                        ? Math.min(
                            100,
                            (vbtMetrics.currentVelocity /
                              Math.max(vbtMetrics.peakSetVelocity, 60)) *
                              100
                          )
                        : Math.min(100, vbtMetrics.velocityDropPct * 2)
                    }%`,
                    background:
                      'linear-gradient(90deg, var(--accent-primary) 0%, #F59E0B 60%, #EF4444 100%)',
                  }}
                />
                {/* 5-segment gauge dividers */}
                <div className="absolute inset-0 grid grid-cols-5 pointer-events-none">
                  <div className="border-r border-black/35" />
                  <div className="border-r border-black/35" />
                  <div className="border-r border-black/35" />
                  <div className="border-r border-black/35" />
                  <div />
                </div>
              </div>
            </div>
          </section>
        )}

        {/* Unified Set Control Deck (Completed Reps, Up Next, & Dynamic Modes) */}
        <section className="surface-card rounded-3xl px-8 py-5 space-y-4">
          <div className="flex items-center justify-between gap-4">
            {/* Completed / Target Reps Callout */}
            <div className="flex items-center gap-6">
              <div>
                <p className="text-sm uppercase tracking-widest font-bold opacity-65">
                  Completed Reps
                </p>
                <p className="font-mono tabular-nums text-6xl font-black mt-0.5">
                  {telemetry.rep_count}
                  {currentStep ? (
                    <span className="text-3xl opacity-55"> / {currentStep.targetReps}</span>
                  ) : null}
                </p>
              </div>
              <button
                type="button"
                onClick={() => void logCompletedSet()}
                className="min-h-[64px] px-8 py-4 rounded-2xl font-black text-lg transition hover:scale-[1.02]"
                style={{
                  backgroundColor: 'var(--accent-primary)',
                  color: accentTextColor,
                  boxShadow: '0 0 24px var(--accent-glow)',
                }}
              >
                Complete &amp; Log Set
              </button>
              {sessionState.activeSessionId && (
                <button
                  type="button"
                  onClick={() => void finishActiveWorkout()}
                  className="min-h-[64px] px-5 py-4 rounded-2xl elevated-pill font-bold text-base hover:opacity-90 transition"
                >
                  Finish Workout
                </button>
              )}
            </div>

            {/* Up Next Scheduled Movement Preview */}
            <div className="elevated-pill rounded-2xl px-5 py-3.5 text-left max-w-sm">
              <p className="text-[11px] uppercase tracking-widest font-extrabold opacity-65">
                Up Next
              </p>
              {nextExercise && nextStep ? (
                <>
                  <p className="font-black text-base truncate">{nextExercise.name}</p>
                  <p className="text-xs font-mono font-bold opacity-80">
                    {nextExercise.accessory} • {formatArmTarget(nextExercise)} •{' '}
                    {nextStep.targetReps} reps
                  </p>
                </>
              ) : (
                <p className="font-bold text-sm opacity-75">
                  {activeTemplateName ? 'Final Step in Routine' : 'Free Lift — Tap Routines to Load'}
                </p>
              )}
            </div>
          </div>

          {/* Dynamic Mode Selector Pills (Unified inside Set Control Deck) */}
          <div
            className="border-t pt-4 flex items-center justify-between gap-3"
            style={{ borderColor: 'var(--border-subtle)' }}
          >
            {DYNAMIC_MODES.map((m) => {
              const active = telemetry.profile_mode === m.id;
              return (
                <button
                  key={m.id}
                  type="button"
                  onClick={() => setWeight(telemetry.target_weight_lb, m.id, m.defaultPct)}
                  className={`flex-1 py-4 px-4 rounded-2xl font-extrabold text-base transition ${
                    active ? '' : 'elevated-pill opacity-85 hover:opacity-100'
                  }`}
                  style={
                    active
                      ? {
                          backgroundColor: 'var(--accent-primary)',
                          color: accentTextColor,
                          boxShadow: '0 0 18px var(--accent-glow)',
                        }
                      : undefined
                  }
                >
                  {m.label}
                  {active && telemetry.modifier_pct > 0 ? ` (+${telemetry.modifier_pct}%)` : ''}
                </button>
              );
            })}
          </div>

          {/* Voice Coach (Shown only when an AI provider is configured in Settings) */}
          <div className="grid grid-cols-2 gap-4">

            {/* 3. Voice Coach (Shown only when an AI provider is configured in Settings) */}
            {coachConfig &&
              (coachConfig.provider !== 'local' ||
                coachConfig.hasApiKey ||
                Boolean(coachConfig.baseUrl)) && (
            <div className="col-span-2 surface-card rounded-3xl p-5 space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Bot size={20} style={{ color: 'var(--accent-ink)' }} />
                  <h3 className="font-black text-base uppercase tracking-wider">
                    Voice Coach ({coachConfig.provider.toUpperCase()})
                  </h3>
                </div>
                <span className="text-xs font-mono opacity-65">
                  Configure AI Provider in Settings ⚙
                </span>
              </div>
              <p className="text-sm font-medium opacity-85 line-clamp-2">{coachReply}</p>
              <div className="flex items-center gap-2">
                {typeof window !== 'undefined' &&
                  Boolean(
                    (window as unknown as { SpeechRecognition?: unknown; webkitSpeechRecognition?: unknown })
                      .SpeechRecognition ||
                      (window as unknown as { webkitSpeechRecognition?: unknown })
                        .webkitSpeechRecognition
                  ) && (
                  <button
                    type="button"
                    onClick={toggleVoiceListening}
                    aria-pressed={isListening}
                    className={`px-4 py-3 rounded-xl font-bold text-sm flex items-center gap-1.5 shrink-0 ${
                      isListening ? 'bg-red-500 text-white animate-pulse' : ''
                    }`}
                    style={
                      !isListening
                        ? { backgroundColor: 'var(--accent-primary)', color: accentTextColor }
                        : undefined
                    }
                  >
                    {isListening ? <Mic size={18} /> : <MicOff size={18} />}
                    <span>{isListening ? 'Mic Open (Pause to Send)' : 'Mic Off'}</span>
                  </button>
                )}
                <input
                  type="text"
                  value={coachPrompt}
                  onChange={(e) => setCoachPrompt(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') void askCoach(coachPrompt);
                  }}
                  placeholder="Ask PRs or say 'Add 5 lbs'..."
                  className="flex-1 min-w-0 px-3.5 py-3 rounded-xl elevated-pill text-sm outline-none"
                />
                <button
                  type="button"
                  disabled={coachBusy}
                  onClick={() => void askCoach(coachPrompt)}
                  aria-label="Send to Coach"
                  className="elevated-pill px-4 py-3 rounded-xl font-bold"
                >
                  <Send size={18} />
                </button>
                {coachAction && (
                  <button
                    type="button"
                    onClick={() => {
                      executeVoiceAction(coachAction);
                      setCoachAction(null);
                    }}
                    className="px-3.5 py-3 rounded-xl bg-emerald-500/25 text-emerald-300 text-xs font-black shrink-0"
                  >
                    {coachAction.label}
                  </button>
                )}
              </div>
            </div>
            )}
          </div>
        </section>
      </main>



      {/* DIALOG 1: COMPANION QR CODE */}
      <dialog
        ref={qrDialogRef}
        closedby="any"
        aria-labelledby="qr-dialog-title"
        className="rounded-3xl p-8 max-w-md w-full"
      >
        <div className="flex items-center justify-between mb-4">
          <h2 id="qr-dialog-title" className="text-2xl font-black">
            Companion Remote
          </h2>
          <button
            type="button"
            onClick={() => qrDialogRef.current?.close()}
            className="elevated-pill p-2 rounded-xl"
          >
            <X size={20} />
          </button>
        </div>
        <p className="text-sm opacity-75 mb-5">
          Scan with your phone camera to build multi-block workouts from the couch, send routines
          straight to this screen, and adjust weight or music between sets.
        </p>
        <div className="bg-white p-5 rounded-2xl flex items-center justify-center mb-5">
          {qrDataUrl && <img src={qrDataUrl} alt="Companion Remote QR Code" className="w-64 h-64" />}
        </div>
        <div className="flex flex-col gap-3">
          {(externalBaseUrl || (networkInfo && networkInfo.ips.length > 0)) && (
            <div className="flex flex-wrap items-center justify-center gap-1.5">
              {externalBaseUrl && (
                <button
                  type="button"
                  onClick={() => setQrUseExternal(true)}
                  className={`px-3 py-1.5 rounded-lg font-mono text-xs font-bold ${
                    qrUseExternal ? 'text-slate-950' : 'elevated-pill opacity-75'
                  }`}
                  style={qrUseExternal ? { backgroundColor: 'var(--accent-primary)' } : undefined}
                >
                  {externalBaseUrl.replace(/^https?:\/\//i, '')}
                </button>
              )}
              {(networkInfo?.ips ?? []).map((ip) => {
                const isSelected = (!externalBaseUrl || !qrUseExternal) && selectedLanIp === ip;
                return (
                  <button
                    key={ip}
                    type="button"
                    onClick={() => {
                      setQrUseExternal(false);
                      setSelectedLanIp(ip);
                    }}
                    className={`px-3 py-1.5 rounded-lg font-mono text-xs font-bold ${
                      isSelected ? 'text-slate-950' : 'elevated-pill opacity-75'
                    }`}
                    style={isSelected ? { backgroundColor: 'var(--accent-primary)' } : undefined}
                  >
                    {ip}
                  </button>
                );
              })}
            </div>
          )}

          <a
            href={companionUrl}
            className="w-full py-3 rounded-2xl font-extrabold text-center text-slate-950"
            style={{ backgroundColor: 'var(--accent-primary)' }}
          >
            Open /companion Directly
          </a>
          <p className="text-xs font-mono opacity-60 text-center break-all">{companionUrl}</p>
        </div>
      </dialog>

      {/* DIALOG 3: WORKOUT ROUTINES & EXERCISE LIBRARY */}
      <dialog
        ref={libraryDialogRef}
        closedby="any"
        onClose={() => setLibraryOpen(false)}
        aria-labelledby="library-dialog-title"
        className="rounded-3xl p-8 max-w-4xl w-full max-h-[82vh] overflow-y-auto"
      >
        {libraryOpen && (
          <>
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-4">
            <h2 id="library-dialog-title" className="text-2xl font-black">
              Workout Library
            </h2>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setLibraryTab('routines')}
                className={`px-4 py-2 rounded-xl text-sm font-bold ${
                  libraryTab === 'routines' ? 'text-slate-950' : 'elevated-pill'
                }`}
                style={
                  libraryTab === 'routines'
                    ? { backgroundColor: 'var(--accent-primary)' }
                    : undefined
                }
              >
                Saved Routines ({templates.length})
              </button>
              <button
                type="button"
                onClick={() => setLibraryTab('exercises')}
                className={`px-4 py-2 rounded-xl text-sm font-bold ${
                  libraryTab === 'exercises' ? 'text-slate-950' : 'elevated-pill'
                }`}
                style={
                  libraryTab === 'exercises'
                    ? { backgroundColor: 'var(--accent-primary)' }
                    : undefined
                }
              >
                Free Lift Catalog ({exercises.length})
              </button>
            </div>
          </div>
          <button
            type="button"
            onClick={() => libraryDialogRef.current?.close()}
            className="elevated-pill p-2 rounded-xl"
          >
            <X size={20} />
          </button>
        </div>

        {libraryTab === 'routines' ? (
          <div className="space-y-4">
            <div className="flex items-center justify-end">
              <select
                aria-label="Filter routines by lifter"
                value={routineUserFilter}
                onChange={(e) => setRoutineUserFilter(e.target.value)}
                className="elevated-pill px-3 py-1.5 rounded-xl text-xs font-bold outline-none"
              >
                <option value="ACTIVE">
                  {activeUser ? `${activeUser.name} Only` : 'Active Lifter'}
                </option>
                <option value="ALL">All Lifters</option>
                {users
                  .filter((u) => u.id !== activeUser?.id)
                  .map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.name}
                    </option>
                  ))}
              </select>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {(routineUserFilter === 'ALL'
              ? templates
              : templates.filter(
                  (t) =>
                    t.userId ===
                    (routineUserFilter === 'ACTIVE' ? activeUser?.id : routineUserFilter)
                )
            ).map((tpl) => {
              const owner = users.find((u) => u.id === tpl.userId);
              const totalSets = tpl.blocks.reduce(
                (acc, b) => acc + b.items.reduce((s, i) => s + i.sets, 0),
                0
              );
              return (
                <div key={tpl.id} className="surface-card rounded-2xl p-5 flex flex-col justify-between gap-4">
                  <div>
                    <div className="flex items-center justify-between mb-1">
                      <span
                        className="text-xs font-extrabold uppercase tracking-wider px-2.5 py-0.5 rounded-full text-slate-950"
                        style={{ backgroundColor: owner?.accentColor ?? 'var(--accent-primary)' }}
                      >
                        {owner?.name ?? 'Routine'}
                      </span>
                      <span className="text-xs font-mono opacity-65">
                        {tpl.blocks.length} blocks • {totalSets} sets
                      </span>
                    </div>
                    <h3 className="text-xl font-black mt-2">{tpl.name}</h3>
                    {tpl.description && (
                      <p className="text-sm opacity-75 mt-1">{tpl.description}</p>
                    )}
                    <div className="flex flex-wrap gap-1.5 mt-3">
                      {tpl.blocks.map((b, idx) => (
                        <span
                          key={idx}
                          className="elevated-pill px-2.5 py-1 rounded-lg text-[11px] font-bold"
                        >
                          {b.blockType} ({b.items.length})
                        </span>
                      ))}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      void startWorkoutTemplate(tpl);
                      libraryDialogRef.current?.close();
                    }}
                    className="w-full py-3 rounded-xl font-extrabold text-slate-950"
                    style={{ backgroundColor: 'var(--accent-primary)' }}
                  >
                    Start Routine
                  </button>
                </div>
              );
            })}
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap gap-3">
              <input
                type="search"
                value={exerciseSearch}
                onChange={(e) => setExerciseSearch(e.target.value)}
                placeholder="Search 50 movements..."
                className="flex-1 min-w-[220px] elevated-pill px-4 py-2.5 rounded-xl text-sm outline-none"
              />
              <select
                value={muscleFilter}
                onChange={(e) => setMuscleFilter(e.target.value)}
                className="elevated-pill px-3 py-2.5 rounded-xl text-sm font-semibold"
              >
                {muscleGroups.map((mg) => (
                  <option key={mg} value={mg}>
                    {mg === 'ALL' ? 'All Muscle Groups' : mg}
                  </option>
                ))}
              </select>
              <select
                value={accessoryFilter}
                onChange={(e) => setAccessoryFilter(e.target.value)}
                className="elevated-pill px-3 py-2.5 rounded-xl text-sm font-semibold"
              >
                {['ALL', 'HANDLES', 'BAR', 'ROPE', 'BODYWEIGHT'].map((acc) => (
                  <option key={acc} value={acc}>
                    {acc === 'ALL' ? 'All Accessories' : acc}
                  </option>
                ))}
              </select>
            </div>

            <div className="space-y-2.5 max-h-[50vh] overflow-y-auto pr-1">
              {groupedCatalogExercises.length === 0 ? (
                <p className="text-sm opacity-70 elevated-pill rounded-2xl p-4">
                  No matching exercises found.
                </p>
              ) : (
                groupedCatalogExercises.map(([groupName, groupList]) => {
                  const forceOpen =
                    Boolean(exerciseSearch.trim()) ||
                    muscleFilter !== 'ALL' ||
                    accessoryFilter !== 'ALL';
                  return (
                    <details
                      key={groupName}
                      open={forceOpen}
                      className="surface-card rounded-2xl overflow-hidden border"
                      style={{ borderColor: 'var(--border-subtle)' }}
                    >
                      <summary className="px-4 py-3 cursor-pointer select-none flex items-center justify-between font-black text-sm uppercase tracking-wider">
                        <span>{groupName}</span>
                        <span className="elevated-pill px-2.5 py-0.5 rounded-lg text-xs font-mono">
                          {groupList.length}
                        </span>
                      </summary>
                      <div
                        className="border-t px-3 py-3 grid grid-cols-1 sm:grid-cols-2 gap-2.5"
                        style={{ borderColor: 'var(--border-subtle)' }}
                      >
                        {groupList.map((ex: Exercise) => {
                          const pr = prByExerciseId.get(ex.id);
                          return (
                            <button
                              key={ex.id}
                              type="button"
                              onClick={() => {
                                selectFreeExercise(ex);
                                libraryDialogRef.current?.close();
                              }}
                              className="elevated-pill rounded-2xl p-3.5 text-left hover:opacity-90"
                            >
                              <div className="flex items-center justify-between gap-2">
                                <span className="font-extrabold text-sm truncate">{ex.name}</span>
                                <span className="surface-card px-2 py-0.5 rounded-lg text-[10px] font-mono font-bold shrink-0">
                                  {ex.accessory}
                                </span>
                              </div>
                              <div className="flex items-center justify-between text-[11px] opacity-75 mt-1">
                                <span>
                                  {formatArmTarget(ex)}
                                </span>
                                {pr && (
                                  <span
                                    className="font-mono font-bold"
                                    style={{ color: 'var(--accent-ink)' }}
                                  >
                                    1RM {formatDisplayWeight(pr.oneRepMaxEst, activeUnit)}{' '}
                                    {activeUnit}
                                  </span>
                                )}
                              </div>
                            </button>
                          );
                        })}
                      </div>
                    </details>
                  );
                })
              )}
            </div>
          </div>
        )}
          </>
        )}
      </dialog>

      {/* DIALOG 4: PROFILE & ACCENT CUSTOMIZER */}
      <dialog
        ref={profileDialogRef}
        closedby="any"
        aria-labelledby="profile-dialog-title"
        className="rounded-3xl p-8 max-w-lg w-full"
      >
        <div className="flex items-center justify-between mb-5">
          <h2 id="profile-dialog-title" className="text-2xl font-black">
            Profile Style & Household
          </h2>
          <button
            type="button"
            onClick={() => profileDialogRef.current?.close()}
            className="elevated-pill p-2 rounded-xl"
          >
            <X size={20} />
          </button>
        </div>

        {activeUser && (
          <div className="space-y-5 mb-6">
            <div>
              <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-2">
                {activeUser.name} Accent Color
              </p>
              <div className="flex items-center gap-3 flex-wrap">
                {ACCENT_SWATCHES.map((sw) => (
                  <button
                    key={sw.hex}
                    type="button"
                    onClick={() =>
                      void updateUserPreferences(activeUser.id, {
                        accentColor: sw.hex,
                        avatarColor: sw.hex,
                      })
                    }
                    className={`w-10 h-10 rounded-full border-2 transition ${
                      activeUser.accentColor.toUpperCase() === sw.hex
                        ? 'scale-110 border-white'
                        : 'border-transparent opacity-80'
                    }`}
                    style={{ backgroundColor: sw.hex }}
                    title={sw.name}
                  />
                ))}
                <label className="elevated-pill px-3 py-1.5 rounded-xl flex items-center gap-2 text-xs font-mono cursor-pointer">
                  <input
                    type="color"
                    value={activeUser.accentColor}
                    onChange={(e) =>
                      void updateUserPreferences(activeUser.id, {
                        accentColor: e.target.value,
                        avatarColor: e.target.value,
                      })
                    }
                    className="w-6 h-6 rounded cursor-pointer bg-transparent border-0"
                  />
                  <span>{activeUser.accentColor}</span>
                </label>
              </div>
            </div>
          </div>
        )}

        <div className="border-t pt-5" style={{ borderColor: 'var(--border-subtle)' }}>
          <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-3">
            Add Household Lifter
          </p>
          <div className="flex gap-2">
            <input
              type="text"
              value={newProfileName}
              onChange={(e) => setNewProfileName(e.target.value)}
              placeholder="Name (e.g., Alex)"
              className="flex-1 elevated-pill px-4 py-2.5 rounded-xl text-sm outline-none"
            />
            <input
              type="color"
              value={newProfileAccent}
              onChange={(e) => setNewProfileAccent(e.target.value)}
              className="w-12 h-10 rounded-xl elevated-pill p-1 cursor-pointer"
            />
            <button
              type="button"
              onClick={() => {
                if (!newProfileName.trim()) return;
                void createUser(newProfileName.trim(), newProfileAccent, activeUnit);
                setNewProfileName('');
                profileDialogRef.current?.close();
              }}
              className="px-4 py-2.5 rounded-xl font-extrabold text-slate-950 flex items-center gap-1"
              style={{ backgroundColor: 'var(--accent-primary)' }}
            >
              <Plus size={16} />
              <span>Add</span>
            </button>
          </div>
        </div>
      </dialog>

      {/* DIALOG 5: MOCK HARDWARE DIAGNOSTICS & SIMULATOR */}
      <dialog
        ref={mockDialogRef}
        closedby="any"
        aria-labelledby="mock-dialog-title"
        className="rounded-3xl p-8 max-w-lg w-full"
      >
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2.5">
            <Wrench size={22} style={{ color: 'var(--accent-ink)' }} />
            <h2 id="mock-dialog-title" className="text-2xl font-black">
              Hardware & Mock Controls
            </h2>
          </div>
          <button
            type="button"
            onClick={() => mockDialogRef.current?.close()}
            className="elevated-pill p-2 rounded-xl"
          >
            <X size={20} />
          </button>
        </div>

        <div className="space-y-4">
          <div className="elevated-pill rounded-2xl p-4 text-xs font-mono space-y-1">
            <p>WS Hub: {wsConnected ? 'CONNECTED' : 'RECONNECTING'}</p>
            <p>Daemon Telemetry: {daemonConnected ? 'ONLINE (30Hz)' : 'OFFLINE'}</p>
            <p>
              MCP SSE Endpoint:{' '}
              {typeof window !== 'undefined' ? `${window.location.origin}/mcp/sse` : '/mcp/sse'}
            </p>
          </div>

          <div>
            <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-2">
              Simulated BLE Smart Handle / Bar Events
            </p>
            <div className="grid grid-cols-3 gap-2">
              <button
                type="button"
                onClick={() => void triggerMockBle('toggle_weight')}
                className="elevated-pill py-3 px-2 rounded-xl text-xs font-bold hover:opacity-80"
              >
                Single Click (Weight)
              </button>
              <button
                type="button"
                onClick={() => void triggerMockBle('spotify_next')}
                className="elevated-pill py-3 px-2 rounded-xl text-xs font-bold hover:opacity-80"
              >
                Double Click (Skip Track)
              </button>
              <button
                type="button"
                onClick={() => void triggerMockBle('emergency_stop')}
                className="bg-red-500/20 border border-red-500/40 text-red-300 py-3 px-2 rounded-xl text-xs font-bold"
              >
                Long Press (E-Stop)
              </button>
            </div>
          </div>

          <div>
            <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-2">
              Phase 1.5 Bar Tilt Safety Cutout Test
            </p>
            <button
              type="button"
              onClick={() => void triggerMockBarTilt()}
              className="w-full py-3 rounded-xl bg-amber-500/20 border border-amber-500/40 text-amber-300 font-bold text-sm"
            >
              Simulate Unbalanced Smart Bar Tilt (Δ 48 lb / 250ms)
            </button>
          </div>

          <div className="grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => {
                sendWs({ type: 'reset_reps' });
                setMockStatusMsg('Reset rep counter to 0');
              }}
              className="elevated-pill py-3 rounded-xl text-sm font-bold"
            >
              Reset Rep Counter
            </button>
            <button
              type="button"
              onClick={() => void zeroCalibration()}
              className="elevated-pill py-3 rounded-xl text-sm font-bold"
            >
              Zero Cable Calibration
            </button>
          </div>

          {/* Home Assistant / MQTT Event Bus Diagnostics */}
          <div className="elevated-pill rounded-2xl p-4 space-y-2">
            <div className="flex items-center justify-between">
              <p className="text-xs uppercase tracking-widest font-bold opacity-80">
                Home Assistant / MQTT Bus
              </p>
              <button
                type="button"
                onClick={() => void triggerHaTest()}
                className="px-3 py-1 rounded-lg text-xs font-bold text-slate-950"
                style={{ backgroundColor: 'var(--accent-primary)' }}
              >
                Test Event
              </button>
            </div>
            <p className="text-[11px] font-mono opacity-75">
              Topic: {haStatus?.mqttTopicPrefix ?? 'swonal'}/state &amp; /session • MQTT:{' '}
              {haStatus?.mqttConfigured
                ? haStatus.mqttConnected
                  ? 'CONNECTED'
                  : 'CONNECTING'
                : 'LOCAL LOG (set MQTT_BROKER_URL)'}{' '}
              • Webhook: {haStatus?.webhookConfigured ? 'ACTIVE' : 'UNSET'}
            </p>
            {haStatus && haStatus.recentEvents.length > 0 && (
              <p className="text-[11px] font-mono text-emerald-400 truncate">
                Last: {haStatus.recentEvents[0].topic} ({haStatus.recentEvents[0].timestamp.slice(11, 19)})
              </p>
            )}
          </div>

          {mockStatusMsg && (
            <p className="text-xs font-mono text-emerald-400 text-center">{mockStatusMsg}</p>
          )}
        </div>
      </dialog>

      {/* DIALOG 7: BYOK AI VOICE COACH SETTINGS */}
      <dialog
        ref={aiDialogRef}
        closedby="any"
        aria-labelledby="ai-dialog-title"
        className="rounded-3xl p-8 max-w-lg w-full"
      >
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2.5">
            <Key size={22} style={{ color: 'var(--accent-ink)' }} />
            <h2 id="ai-dialog-title" className="text-2xl font-black">
              Voice Coach (BYOK AI Provider)
            </h2>
          </div>
          <button
            type="button"
            onClick={() => aiDialogRef.current?.close()}
            className="elevated-pill p-2 rounded-xl"
          >
            <X size={20} />
          </button>
        </div>

        <div className="space-y-4">
          <div>
            <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-2">
              Provider
            </p>
            <div className="grid grid-cols-2 gap-2">
              {(coachConfig?.supportedProviders ?? [
                { id: 'local', label: 'Built-In Local Coach' },
                { id: 'openai', label: 'OpenAI (BYOK)' },
                { id: 'anthropic', label: 'Anthropic Claude' },
                { id: 'gemini', label: 'Google Gemini' },
                { id: 'ollama', label: 'Ollama (Local)' },
              ]).map((p) => (
                <button
                  key={p.id}
                  type="button"
                  onClick={() =>
                    setByokProvider(p.id as 'local' | 'openai' | 'anthropic' | 'gemini' | 'ollama')
                  }
                  className={`px-3 py-2.5 rounded-xl text-xs font-bold text-left ${
                    byokProvider === p.id ? 'text-slate-950' : 'elevated-pill'
                  }`}
                  style={
                    byokProvider === p.id
                      ? { backgroundColor: 'var(--accent-primary)' }
                      : undefined
                  }
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>

          <div>
            <label className="block text-xs uppercase tracking-widest font-bold opacity-70 mb-1">
              Model Name
            </label>
            <input
              type="text"
              value={byokModel}
              onChange={(e) => setByokModel(e.target.value)}
              placeholder="gpt-4o-mini / claude-3-5-haiku-latest / gemini-2.5-flash"
              className="w-full px-4 py-3 rounded-xl elevated-pill text-sm outline-none"
            />
          </div>

          {byokProvider !== 'local' && byokProvider !== 'ollama' && (
            <div>
              <label className="block text-xs uppercase tracking-widest font-bold opacity-70 mb-1">
                API Key {coachConfig?.keyHint ? `(Saved: ${coachConfig.keyHint})` : ''}
              </label>
              <input
                type="password"
                value={byokApiKey}
                onChange={(e) => setByokApiKey(e.target.value)}
                placeholder="Paste provider API key (stored in memory / .env)"
                className="w-full px-4 py-3 rounded-xl elevated-pill text-sm outline-none"
              />
            </div>
          )}

          {byokProvider === 'ollama' && (
            <div>
              <label className="block text-xs uppercase tracking-widest font-bold opacity-70 mb-1">
                Ollama Base URL
              </label>
              <input
                type="text"
                value={byokBaseUrl}
                onChange={(e) => setByokBaseUrl(e.target.value)}
                placeholder="http://localhost:11434"
                className="w-full px-4 py-3 rounded-xl elevated-pill text-sm outline-none"
              />
            </div>
          )}

          <button
            type="button"
            onClick={() => void saveCoachConfig()}
            className="w-full py-3.5 rounded-2xl font-black"
            style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
          >
            Save AI Coach Settings
          </button>
        </div>
      </dialog>

      {/* DIALOG 8: SESSION EXPORT, BACKUP & DIAGNOSTICS */}
      <dialog
        ref={diagnosticsDialogRef}
        closedby="any"
        aria-labelledby="diag-dialog-title"
        className="rounded-3xl p-8 max-w-lg w-full"
      >
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2.5">
            <Activity size={22} style={{ color: 'var(--accent-ink)' }} />
            <h2 id="diag-dialog-title" className="text-2xl font-black">
              Session Export &amp; Diagnostics
            </h2>
          </div>
          <button
            type="button"
            onClick={() => diagnosticsDialogRef.current?.close()}
            className="elevated-pill p-2 rounded-xl"
          >
            <X size={20} />
          </button>
        </div>

        <div className="space-y-4">
          <div className="elevated-pill rounded-2xl p-4 flex items-center justify-between font-mono tabular-nums">
            <div>
              <p className="text-xs uppercase opacity-65">Last Session Volume</p>
              <p className="text-2xl font-black">
                {formatDisplayWeight(sessions[0]?.totalVolume ?? 0, activeUnit)} {activeUnit}
              </p>
              <p className="text-xs opacity-70">{sessions.length} Recent Sessions</p>
            </div>
            <div className="text-right">
              <p className="text-xs uppercase opacity-65">Cable Calibration</p>
              <p className="text-sm font-bold text-emerald-400">
                L {calibrationStatus?.zero_offsets?.left ?? 0} / R{' '}
                {calibrationStatus?.zero_offsets?.right ?? 0}
              </p>
              <button
                type="button"
                onClick={() => void zeroCalibration()}
                className="mt-1 px-3 py-1 rounded-lg elevated-pill text-xs font-bold"
              >
                Zero Offsets
              </button>
            </div>
          </div>

          <div className="grid grid-cols-3 gap-2">
            <a
              href={`/api/export/sessions.csv?userId=${encodeURIComponent(activeUser?.id ?? '')}`}
              className="elevated-pill py-3 px-3 rounded-xl text-xs font-bold flex items-center justify-center gap-1.5"
            >
              <Download size={15} />
              <span>Export CSV</span>
            </a>
            <a
              href={`/api/export/sessions.json?userId=${encodeURIComponent(activeUser?.id ?? '')}`}
              className="elevated-pill py-3 px-3 rounded-xl text-xs font-bold flex items-center justify-center gap-1.5"
            >
              <Download size={15} />
              <span>Export JSON</span>
            </a>
            <a
              href="/api/admin/backup/db"
              className="elevated-pill py-3 px-3 rounded-xl text-xs font-bold flex items-center justify-center gap-1.5"
            >
              <Database size={15} />
              <span>Backup DB</span>
            </a>
          </div>
        </div>
      </dialog>

      {/* WEIGHT KEYPAD MODAL */}
      <dialog
        ref={weightDialogRef}
        closedby="any"
        aria-labelledby="weight-keypad-title"
        className="rounded-3xl p-7 max-w-sm w-full"
      >
        <div className="flex items-center justify-between mb-4">
          <h2 id="weight-keypad-title" className="text-xl font-black">
            Set Target Weight ({activeUnit})
          </h2>
          <button
            type="button"
            onClick={() => weightDialogRef.current?.close()}
            className="elevated-pill p-2 rounded-xl"
            aria-label="Close Weight Keypad"
          >
            <X size={18} />
          </button>
        </div>

        <div className="elevated-pill rounded-2xl px-5 py-4 text-center mb-4">
          <span className="font-mono tabular-nums text-5xl font-black">
            {weightInputDraft || '0'}
          </span>
          <span className="ml-2 text-lg font-bold opacity-65">{activeUnit}</span>
        </div>

        <div className="grid grid-cols-3 gap-2 mb-3">
          {(activeUnit === 'KG' ? [10, 20, 30, 45, 60, 80] : [20, 45, 65, 95, 135, 185]).map(
            (preset) => (
              <button
                key={preset}
                type="button"
                onClick={() => {
                  setWeight(activeUnit === 'KG' ? preset * KG_TO_LB : preset);
                  weightDialogRef.current?.close();
                }}
                className="elevated-pill py-2 rounded-xl font-mono font-bold text-xs hover:opacity-90"
              >
                {preset} {activeUnit}
              </button>
            )
          )}
        </div>

        <div className="grid grid-cols-3 gap-2 mb-4">
          {['1', '2', '3', '4', '5', '6', '7', '8', '9', 'Clear', '0', '⌫'].map((key) => (
            <button
              key={key}
              type="button"
              onClick={() => {
                if (key === 'Clear') {
                  setWeightInputDraft('');
                } else if (key === '⌫') {
                  setWeightInputDraft((prev) => prev.slice(0, -1));
                } else {
                  setWeightInputDraft((prev) =>
                    prev === '0' ? key : prev.length < 4 ? `${prev}${key}` : prev
                  );
                }
              }}
              className="elevated-pill py-3.5 rounded-2xl font-mono font-black text-xl hover:opacity-85"
            >
              {key}
            </button>
          ))}
        </div>

        <button
          type="button"
          onClick={() => {
            const val = Number(weightInputDraft);
            if (!Number.isNaN(val) && val > 0) {
              setWeight(activeUnit === 'KG' ? val * KG_TO_LB : val);
            }
            weightDialogRef.current?.close();
          }}
          className="w-full py-3.5 rounded-2xl font-black text-base"
          style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
        >
          Set Weight
        </button>
      </dialog>

      {/* POST-WORKOUT SUMMARY MODAL */}
      <PostWorkoutSummaryModal
        summary={lastCompletedWorkoutSummary}
        unit={activeUnit}
        accentColor={activeUser?.accentColor ?? '#00F0FF'}
        onClose={dismissWorkoutSummary}
      />

      {/* DIALOG: FRIENDS & P2P COMMUNITY */}
      <dialog
        ref={friendsDialogRef}
        closedby="any"
        aria-labelledby="friends-dialog-title"
        className="rounded-3xl p-7 max-w-2xl w-full"
      >
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-3">
            <div
              className="w-10 h-10 rounded-2xl flex items-center justify-center font-black"
              style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
            >
              <Users size={20} />
            </div>
            <div>
              <h2 id="friends-dialog-title" className="text-2xl font-black">
                Swocial
              </h2>
              <p className="text-xs opacity-70">
                Live workout feed and gym buddy activity
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => friendsDialogRef.current?.close()}
            className="elevated-pill p-2 rounded-xl"
            aria-label="Close Swocial Dialog"
          >
            <X size={20} />
          </button>
        </div>

        {/* Tab Pills */}
        <div className="flex gap-2 p-1.5 elevated-pill rounded-2xl mb-5">
          <button
            type="button"
            onClick={() => setFriendsKioskTab('mycode')}
            className={`flex-1 py-2 rounded-xl text-xs font-bold transition flex items-center justify-center gap-1.5 ${
              friendsKioskTab === 'mycode' ? 'font-black' : 'opacity-70 hover:opacity-100'
            }`}
            style={
              friendsKioskTab === 'mycode'
                ? { backgroundColor: 'var(--accent-primary)', color: accentTextColor }
                : {}
            }
          >
            <QrCode size={14} />
            <span>My Swocial Code</span>
          </button>
          <button
            type="button"
            onClick={() => setFriendsKioskTab('feed')}
            className={`flex-1 py-2 rounded-xl text-xs font-bold transition flex items-center justify-center gap-1.5 ${
              friendsKioskTab === 'feed' ? 'font-black' : 'opacity-70 hover:opacity-100'
            }`}
            style={
              friendsKioskTab === 'feed'
                ? { backgroundColor: 'var(--accent-primary)', color: accentTextColor }
                : {}
            }
          >
            <Activity size={14} />
            <span>Feed</span>
          </button>
          <button
            type="button"
            onClick={() => setFriendsKioskTab('add')}
            className={`flex-1 py-2 rounded-xl text-xs font-bold transition flex items-center justify-center gap-1.5 ${
              friendsKioskTab === 'add' ? 'font-black' : 'opacity-70 hover:opacity-100'
            }`}
            style={
              friendsKioskTab === 'add'
                ? { backgroundColor: 'var(--accent-primary)', color: accentTextColor }
                : {}
            }
          >
            <Plus size={14} />
            <span>Friends ({kioskFriendsList.length})</span>
          </button>
        </div>

        {/* TAB 1: MY SWOCIAL CODE */}
        {friendsKioskTab === 'mycode' && (
          <div className="space-y-4">
            <div className="flex flex-col sm:flex-row items-center gap-6 p-4 rounded-2xl elevated-pill">
              <div className="bg-white p-3 rounded-2xl shrink-0 flex items-center justify-center shadow-md">
                {kioskFriendQrDataUrl ? (
                  <img src={kioskFriendQrDataUrl} alt="Swocial QR Code" className="w-52 h-52" />
                ) : (
                  <div className="w-52 h-52 flex items-center justify-center text-xs text-slate-500 font-mono">
                    Generating QR...
                  </div>
                )}
              </div>
              <div className="flex-1 space-y-3 min-w-0 text-center sm:text-left">
                <div>
                  <h3 className="font-extrabold text-base">Your Swocial Code</h3>
                  <p className="text-xs opacity-70 mt-0.5">
                    Have friends scan this QR with their camera or paste this code in Swocial to connect.
                  </p>
                </div>

                <div className="bg-black/30 p-2.5 rounded-xl border border-white/5 font-mono text-xs break-all opacity-85 select-all">
                  {kioskFriendIdentity?.npub || 'Loading Swocial identity...'}
                </div>

                <div className="flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    disabled={!kioskFriendIdentity?.npub}
                    onClick={() => {
                      if (kioskFriendIdentity?.npub) {
                        void navigator.clipboard.writeText(kioskFriendIdentity.npub);
                        setKioskCopiedNpub(true);
                        setTimeout(() => setKioskCopiedNpub(false), 2000);
                      }
                    }}
                    className="px-4 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:opacity-90 transition"
                    style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                  >
                    {kioskCopiedNpub ? <Check size={14} /> : <Copy size={14} />}
                    <span>{kioskCopiedNpub ? 'Copied to Clipboard!' : 'Copy Swocial Code'}</span>
                  </button>

                  <button
                    type="button"
                    disabled={!kioskFriendIdentity}
                    onClick={() =>
                      void handleKioskToggleShareWorkouts(!kioskFriendIdentity?.shareWorkouts)
                    }
                    className="px-3.5 py-2 rounded-xl text-xs font-bold elevated-pill flex items-center gap-1.5 hover:opacity-90"
                  >
                    <span
                      className={`w-2 h-2 rounded-full ${
                        kioskFriendIdentity?.shareWorkouts ? 'bg-emerald-400' : 'bg-slate-500'
                      }`}
                    />
                    <span>
                      Auto-Share: {kioskFriendIdentity?.shareWorkouts ? 'ON' : 'OFF'}
                    </span>
                  </button>
                </div>
              </div>
            </div>

            <div className="text-xs opacity-60 flex items-center justify-between px-1">
              <span>Configured Relays: {kioskFriendIdentity?.relays?.length ?? 4} active</span>
              <span>Full key settings in Application Settings → Friends</span>
            </div>
          </div>
        )}

        {/* TAB 2: ACTIVITY FEED */}
        {friendsKioskTab === 'feed' && (
          <div className="space-y-3">
            <div className="flex items-center justify-between pb-1">
              <span className="text-xs opacity-70">
                Live peer workouts from connected friends
              </span>
              <button
                type="button"
                disabled={kioskFriendsSyncing}
                onClick={() => void handleKioskSyncFriends()}
                className="px-3 py-1.5 rounded-xl text-xs font-bold elevated-pill flex items-center gap-1.5 hover:opacity-90 disabled:opacity-50"
              >
                <RefreshCw size={13} className={kioskFriendsSyncing ? 'animate-spin' : ''} />
                <span>{kioskFriendsSyncing ? 'Syncing...' : 'Sync Relays'}</span>
              </button>
            </div>

            <div className="max-h-[380px] overflow-y-auto space-y-3 pr-1">
              {kioskFriendsFeed.length === 0 ? (
                <div className="elevated-pill rounded-2xl p-8 text-center space-y-2">
                  <Users size={32} className="mx-auto opacity-40" />
                  <p className="text-sm font-extrabold">No friend activity yet</p>
                  <p className="text-xs opacity-70">
                    Add friends in the "Add Friend" tab to view workout milestones, PRs, and shared routines.
                  </p>
                </div>
              ) : (
                kioskFriendsFeed.map((post) => {
                  const isWorkout = post.kind === 30101;
                  const isRoutine = post.kind === 30102;
                  const p = post.payload;
                  const flexCount = post.reactions.filter((r: any) => r.emoji === '💪').length;
                  const fireCount = post.reactions.filter((r: any) => r.emoji === '🔥').length;
                  const trophyCount = post.reactions.filter((r: any) => r.emoji === '🏆').length;

                  return (
                    <div
                      key={post.id}
                      className="elevated-pill rounded-2xl p-4 space-y-3 border border-white/5"
                    >
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <div
                            className="w-7 h-7 rounded-xl flex items-center justify-center font-black text-xs"
                            style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                          >
                            {post.authorName.slice(0, 2).toUpperCase()}
                          </div>
                          <div>
                            <span className="font-extrabold text-sm">{post.authorName}</span>
                            <span className="text-[11px] opacity-60 ml-2 font-mono">
                              {new Date(post.createdAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}
                            </span>
                          </div>
                        </div>
                        <span className="text-[10px] uppercase font-bold tracking-wider px-2 py-0.5 rounded-full bg-white/10 opacity-80">
                          {isWorkout ? 'Workout' : isRoutine ? 'Routine' : 'Activity'}
                        </span>
                      </div>

                      {isWorkout && (
                        <div className="bg-black/25 rounded-xl p-3 flex items-center justify-between text-xs font-mono">
                          <div>
                            <span className="font-bold text-white block">{p.routineName || 'Free Lift'}</span>
                            <span className="opacity-70">{p.setCount || 0} sets • {p.totalReps || 0} reps</span>
                          </div>
                          <div className="text-right">
                            <span className="font-black text-sm block" style={{ color: 'var(--accent-ink)' }}>
                              {Math.round(p.totalVolume || 0)} {p.unit || 'LB'}
                            </span>
                            <span className="opacity-60">{Math.round((p.durationSeconds || 0) / 60)} min</span>
                          </div>
                        </div>
                      )}

                      {isRoutine && (
                        <div className="bg-black/25 rounded-xl p-3 text-xs space-y-1">
                          <span className="font-bold text-white block">{p.name}</span>
                          <p className="opacity-70 line-clamp-1">{p.description || 'Shared routine'}</p>
                        </div>
                      )}

                      <div className="flex items-center justify-between pt-1">
                        <div className="flex items-center gap-1.5">
                          <button
                            type="button"
                            onClick={() => void handleKioskReact(post.id, post.authorPubkey, '💪')}
                            className="px-2.5 py-1 rounded-lg text-xs font-bold elevated-pill hover:scale-105 active:scale-95 transition flex items-center gap-1"
                          >
                            <span>💪</span>
                            {flexCount > 0 && <span className="text-[10px] opacity-80">{flexCount}</span>}
                          </button>
                          <button
                            type="button"
                            onClick={() => void handleKioskReact(post.id, post.authorPubkey, '🔥')}
                            className="px-2.5 py-1 rounded-lg text-xs font-bold elevated-pill hover:scale-105 active:scale-95 transition flex items-center gap-1"
                          >
                            <span>🔥</span>
                            {fireCount > 0 && <span className="text-[10px] opacity-80">{fireCount}</span>}
                          </button>
                          <button
                            type="button"
                            onClick={() => void handleKioskReact(post.id, post.authorPubkey, '🏆')}
                            className="px-2.5 py-1 rounded-lg text-xs font-bold elevated-pill hover:scale-105 active:scale-95 transition flex items-center gap-1"
                          >
                            <span>🏆</span>
                            {trophyCount > 0 && <span className="text-[10px] opacity-80">{trophyCount}</span>}
                          </button>
                        </div>

                        {isRoutine && (
                          <button
                            type="button"
                            onClick={async () => {
                              if (!activeUser?.id) return;
                              const res = await fetch(`/api/friends/import-routine/${encodeURIComponent(activeUser.id)}/${encodeURIComponent(post.id)}`, { method: 'POST' });
                              if (res.ok) {
                                void refreshBootstrap();
                                alert('Routine imported successfully!');
                              }
                            }}
                            className="px-2.5 py-1 rounded-lg text-xs font-bold flex items-center gap-1"
                            style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                          >
                            <Download size={12} />
                            <span>Import Routine</span>
                          </button>
                        )}
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        )}

        {/* TAB 3: ADD FRIEND & ROSTER */}
        {friendsKioskTab === 'add' && (
          <div className="space-y-4">
            <div className="elevated-pill rounded-2xl p-4 space-y-3">
              <h3 className="font-extrabold text-sm">Add a Friend via Swocial Code</h3>
              <div className="space-y-2">
                <input
                  type="text"
                  placeholder="npub1... or hex public key"
                  value={kioskAddFriendCode}
                  onChange={(e) => setKioskAddFriendCode(e.target.value)}
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl font-mono text-xs focus:outline-none"
                />
                <div className="flex gap-2">
                  <input
                    type="text"
                    placeholder="Nickname (optional, e.g. Alex)"
                    value={kioskAddFriendNick}
                    onChange={(e) => setKioskAddFriendNick(e.target.value)}
                    className="flex-1 elevated-pill px-3 py-2 rounded-xl text-xs focus:outline-none"
                  />
                  <button
                    type="button"
                    disabled={!kioskAddFriendCode.trim()}
                    onClick={() => void handleKioskAddFriend()}
                    className="px-4 py-2 rounded-xl text-xs font-bold disabled:opacity-40 hover:opacity-90"
                    style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                  >
                    Add Friend
                  </button>
                </div>
              </div>

              {kioskAddFriendMsg && (
                <p
                  className={`text-xs font-bold ${
                    kioskAddFriendMsg.ok ? 'text-emerald-400' : 'text-rose-400'
                  }`}
                >
                  {kioskAddFriendMsg.text}
                </p>
              )}
            </div>

            <div className="space-y-2">
              <h4 className="text-xs uppercase font-extrabold tracking-wider opacity-70 px-1">
                Connected Friends ({kioskFriendsList.length})
              </h4>
              <div className="max-h-[200px] overflow-y-auto space-y-2 pr-1">
                {kioskFriendsList.length === 0 ? (
                  <p className="text-xs opacity-50 px-1">No friends added yet.</p>
                ) : (
                  kioskFriendsList.map((f) => (
                    <div
                      key={f.id}
                      className="elevated-pill rounded-xl p-3 flex items-center justify-between gap-2"
                    >
                      <div className="min-w-0">
                        <p className="font-extrabold text-xs truncate">
                          {f.nickname || f.friendNpub.slice(0, 16) + '...'}
                        </p>
                        <p className="font-mono text-[10px] opacity-60 truncate">
                          {f.friendNpub}
                        </p>
                      </div>
                      <button
                        type="button"
                        onClick={async () => {
                          if (!activeUser?.id) return;
                          await fetch(`/api/friends/${encodeURIComponent(activeUser.id)}/${encodeURIComponent(f.id)}`, { method: 'DELETE' });
                          await loadKioskFriendsData(activeUser.id);
                        }}
                        className="text-xs text-rose-400 hover:text-rose-300 font-bold px-2 py-1"
                      >
                        Remove
                      </button>
                    </div>
                  ))
                )}
              </div>
            </div>
          </div>
        )}
      </dialog>

      <MusicPickerDialog
        dialogRef={musicDialogRef}
        activeUser={activeUser}
        activeProviderId={activeProviderId}
        musicProviders={musicProviders}
        musicState={musicState}
        musicError={musicError}
        clearMusicError={clearMusicError}
        webPlayerEnabled={webPlayerEnabled}
        setWebPlayerEnabled={setWebPlayerEnabled}
        webPlayerReady={webPlayerReady}
        devices={devices}
        playlists={playlists}
        favoritePlaylistIds={favoritePlaylistIds}
        toggleFavoritePlaylist={toggleFavoritePlaylist}
        onRefreshDevices={refreshMusicNow}
        updateUserPreferences={updateUserPreferences}
        transferMusicDevice={transferMusicDevice}
        playPlaylist={playPlaylist}
        fetchPlaylistTracks={fetchPlaylistTracks}
        onPlayPause={toggleMusicPlay}
        onNextTrack={nextTrack}
        onPrevTrack={previousTrack}
        onSetVolume={setMusicVolume}
      />
    </div>
  );
};

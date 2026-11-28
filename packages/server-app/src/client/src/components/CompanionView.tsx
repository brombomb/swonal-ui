import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import QRCode from 'qrcode';
import {
  ArrowDown,
  ArrowUp,
  Check,
  ChevronLeft,
  ChevronRight,
  Copy,
  Dumbbell,
  FastForward,
  History,
  Info,
  ListPlus,
  Mic,
  MicOff,
  Moon,
  Music,
  Pause,
  Play,
  Plus,
  QrCode,
  Radio,
  RefreshCw,
  Rewind,
  Send,
  Settings,
  Share2,
  SkipForward,
  Sliders,
  Sparkles,
  Sun,
  Trash2,
  Trophy,
  Users,
  Volume2,
} from 'lucide-react';
import { useSwonalState } from '../hooks/useSwonalState';
import { MusicPickerDialog } from './MusicPickerDialog';
import { ProgressionAnalytics } from './ProgressionAnalytics';
import { RepCountDialog } from './RepCountDialog';
import { BlockType, CoachSuggestedAction, DynamicMode, WorkoutBlock, WorkoutTemplate } from '../types';
import { soundEngine } from '../utils/soundEngine';
import { acceptedPositions, armsMatchExercise, formatArmTarget } from '../utils/armPositions';
import { formatDisplayWeight, getContrastTextColor, KG_TO_LB, stepDisplayWeight } from '../utils/units';
import {
  executeCoachSuggestedAction,
  matchClientVoiceCommand,
  speakCoachAudio,
} from '../utils/voiceCommands';

type SwonalHookReturn = ReturnType<typeof useSwonalState>;
type CompanionTab = 'remote' | 'builder' | 'history' | 'friends';

const BLOCK_TYPES: BlockType[] = ['WARMUP', 'STRAIGHT_SET', 'SUPERSET', 'CIRCUIT'];
const DYNAMIC_MODES: Array<{ id: DynamicMode; label: string; defaultPct: number }> = [
  { id: 'standard', label: 'Standard', defaultPct: 0 },
  { id: 'spotter', label: 'Spotter', defaultPct: 0 },
  { id: 'burnout', label: 'Burnout', defaultPct: 0 },
  { id: 'eccentric', label: 'Eccentric', defaultPct: 25 },
  { id: 'chains', label: 'Chains', defaultPct: 25 },
  { id: 'smart_flex', label: 'Smart Flex', defaultPct: 25 },
];

export const CompanionView: React.FC<{ swonal: SwonalHookReturn }> = ({ swonal }) => {
  const {
    users,
    activeUser,
    activeUnit,
    exercises,
    templates,
    personalRecords,
    sessions,
    sessionState,
    telemetry,
    daemonConnected,
    wsConnected,
    activeExercise,
    currentStep,
    restSecondsRemaining,
    vbtMetrics,
    symmetryMetrics,
    soundMuted,
    musicConfigured,
    musicState,
    musicError,
    clearMusicError,
    webPlayerEnabled,
    setWebPlayerEnabled,
    webPlayerReady,
    musicProviders,
    activeProviderId,
    devices,
    playlists,
    favoritePlaylistIds,
    setWeight,
    setTargetWeight,
    toggleWeight,
    setWeightEnabled,
    setWeightMode,
    switchUser,
    updateUserPreferences,
    togglePartnerMode,
    selectFreeExercise,
    selectExercise,
    startWorkoutTemplate,
    stepRoutine,
    logCompletedSet,
    completeSet,
    repConfirm,
    confirmRepCount,
    cancelRepConfirm,
    setRepCount,
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

  const [tab, setTab] = useState<CompanionTab>('remote');
  const [selectedRpe, setSelectedRpe] = useState<number>(8);
  const [toast, setToast] = useState<string | null>(null);
  const [holdingEngage, setHoldingEngage] = useState(false);
  const [isMockMode, setIsMockMode] = useState(false);
  const [showArmInfo, setShowArmInfo] = useState(false);
  const [showRpeInfo, setShowRpeInfo] = useState(false);
  const [routineUserFilter, setRoutineUserFilter] = useState<string>('ACTIVE');
  const holdTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const musicDialogRef = useRef<HTMLDialogElement>(null);
  const workoutPickerDialogRef = useRef<HTMLDialogElement>(null);

  const accentTextColor = getContrastTextColor(activeUser?.accentColor ?? '#00F0FF');

  interface FriendFeedReaction {
    id: string;
    authorPubkey: string;
    authorName: string;
    emoji: string;
    createdAt: string;
  }

  interface FriendFeedItem {
    id: string;
    kind: number;
    authorPubkey: string;
    authorName: string;
    createdAt: string;
    payload: any;
    reactions: FriendFeedReaction[];
  }

  const [friendsFeed, setFriendsFeed] = useState<FriendFeedItem[]>([]);
  const [friendsFeedLoading, setFriendsFeedLoading] = useState(false);
  const [friendsFeedSyncing, setFriendsFeedSyncing] = useState(false);
  const [importingRoutineId, setImportingRoutineId] = useState<string | null>(null);

  // Friend Identity & Nostr P2P
  const [nostrIdentity, setNostrIdentity] = useState<{
    npub: string;
    pubkeyHex: string;
    shareWorkouts: boolean;
    relays: string[];
  } | null>(null);
  const [companionFriendQrUrl, setCompanionFriendQrUrl] = useState<string>('');
  const [showFriendQr, setShowFriendQr] = useState(false);
  const [copiedFriendNpub, setCopiedFriendNpub] = useState(false);
  const [companionFriendsList, setCompanionFriendsList] = useState<
    Array<{ id: string; friendNpub: string; nickname?: string | null }>
  >([]);
  const [newFriendCode, setNewFriendCode] = useState('');
  const [newFriendNick, setNewFriendNick] = useState('');
  const [addFriendStatus, setAddFriendStatus] = useState<{ ok: boolean; text: string } | null>(null);
  const [addingFriend, setAddingFriend] = useState(false);
  const [showAddFriendForm, setShowAddFriendForm] = useState(false);

  const loadFriendsData = async (userId: string) => {
    if (!userId) return;
    setFriendsFeedLoading(true);
    try {
      const [identRes, friendsRes, feedRes] = await Promise.all([
        fetch(`/api/friends/identity/${encodeURIComponent(userId)}`),
        fetch(`/api/friends/${encodeURIComponent(userId)}`),
        fetch(`/api/friends/feed/${encodeURIComponent(userId)}?limit=50`),
      ]);
      if (identRes.ok) {
        const ident = await identRes.json();
        setNostrIdentity(ident);
        if (ident.npub) {
          const qr = await QRCode.toDataURL(ident.npub, {
            width: 256,
            margin: 2,
            color: { dark: '#07090E', light: '#FFFFFF' },
          });
          setCompanionFriendQrUrl(qr);
        }
      }
      if (friendsRes.ok) {
        setCompanionFriendsList(await friendsRes.json());
      }
      if (feedRes.ok) {
        setFriendsFeed(await feedRes.json());
      }
    } catch (err) {
      console.warn('Failed to load friends data:', err);
    } finally {
      setFriendsFeedLoading(false);
    }
  };

  const handleCompanionAddFriend = async () => {
    if (!activeUser?.id || !newFriendCode.trim()) return;
    setAddingFriend(true);
    try {
      const res = await fetch(`/api/friends/${encodeURIComponent(activeUser.id)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          npubOrHex: newFriendCode.trim(),
          displayName: newFriendNick.trim() || undefined,
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        setAddFriendStatus({ ok: false, text: data.error || 'Failed to add friend' });
      } else {
        setAddFriendStatus({ ok: true, text: 'Friend added! Syncing workouts...' });
        setNewFriendCode('');
        setNewFriendNick('');
        setShowAddFriendForm(false);
        await syncFriendsFeed(activeUser.id);
      }
    } catch {
      setAddFriendStatus({ ok: false, text: 'Network error adding friend' });
    } finally {
      setAddingFriend(false);
    }
  };

  const loadFriendsFeed = async (userId: string) => {
    return loadFriendsData(userId);
  };

  const syncFriendsFeed = async (userId: string) => {
    if (!userId) return;
    setFriendsFeedSyncing(true);
    try {
      await fetch(`/api/friends/sync/${encodeURIComponent(userId)}`, { method: 'POST' });
      await loadFriendsData(userId);
      setToast('Feed synchronized with Nostr relays');
      setTimeout(() => setToast(null), 3000);
    } catch {
      setToast('Relay sync timed out');
      setTimeout(() => setToast(null), 3000);
    } finally {
      setFriendsFeedSyncing(false);
    }
  };

  const handleReactToPost = async (
    targetEventId: string,
    targetPubkey: string,
    emoji: string
  ) => {
    if (!activeUser?.id) return;
    // Optimistic UI update
    setFriendsFeed((prev) =>
      prev.map((item) => {
        if (item.id === targetEventId) {
          return {
            ...item,
            reactions: [
              ...item.reactions,
              {
                id: `opt-${Date.now()}`,
                authorPubkey: 'self',
                authorName: activeUser.name,
                emoji,
                createdAt: new Date().toISOString(),
              },
            ],
          };
        }
        return item;
      })
    );

    try {
      await fetch(`/api/friends/react/${encodeURIComponent(activeUser.id)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          targetEventId,
          targetPubkey,
          emoji,
        }),
      });
    } catch {
      // ignore
    }
  };

  const handleImportSharedRoutine = async (eventId: string) => {
    if (!activeUser?.id) return;
    setImportingRoutineId(eventId);
    try {
      const res = await fetch(
        `/api/friends/import-routine/${encodeURIComponent(activeUser.id)}/${encodeURIComponent(eventId)}`,
        { method: 'POST' }
      );
      if (res.ok) {
        await refreshBootstrap();
        setToast('Routine successfully imported to your library!');
        setTimeout(() => setToast(null), 3500);
      } else {
        const data = await res.json().catch(() => null);
        setToast(data?.error || 'Failed to import routine');
        setTimeout(() => setToast(null), 3500);
      }
    } catch {
      setToast('Failed to import routine');
      setTimeout(() => setToast(null), 3500);
    } finally {
      setImportingRoutineId(null);
    }
  };

  useEffect(() => {
    if (activeUser?.id && tab === 'friends') {
      void loadFriendsFeed(activeUser.id);
    }
  }, [activeUser?.id, tab]);

  useEffect(() => {
    const checkHealth = () => {
      void fetch('/api/daemon/health')
        .then((r) => (r.ok ? r.json() : null))
        .then((d: { mock_mode?: boolean; mock?: boolean } | null) => {
          if (d && (d.mock_mode !== undefined || d.mock !== undefined)) {
            setIsMockMode(Boolean(d.mock_mode ?? d.mock));
          }
        })
        .catch(() => {});
    };
    checkHealth();
    const interval = setInterval(checkHealth, 5000);
    return () => clearInterval(interval);
  }, []);

  const beginHoldEngage = () => {
    if (telemetry.weight_enabled) return;
    setHoldingEngage(true);
    holdTimerRef.current = setTimeout(() => {
      setHoldingEngage(false);
      toggleWeight();
    }, 450);
  };

  const cancelHoldEngage = () => {
    setHoldingEngage(false);
    if (holdTimerRef.current) {
      clearTimeout(holdTimerRef.current);
      holdTimerRef.current = null;
    }
  };

  // Workout Builder state
  const [editingTemplateId, setEditingTemplateId] = useState<string | null>(null);
  const [routineName, setRoutineName] = useState('');
  const [routineDesc, setRoutineDesc] = useState('');
  const [blocks, setBlocks] = useState<WorkoutBlock[]>([
    {
      blockType: 'STRAIGHT_SET',
      restSeconds: 60,
      items: [],
    },
  ]);
  const [exerciseSearch, setExerciseSearch] = useState('');
  // Which block's "add exercise" box the search text belongs to; other blocks show the full list.
  const [searchBlockIdx, setSearchBlockIdx] = useState<number | null>(null);
  const [dragBlockIdx, setDragBlockIdx] = useState<number | null>(null);

  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 3500);
  };

  // Hands-Free Voice Control on Companion Remote
  const [isVoiceListening, setIsVoiceListening] = useState(false);
  const [voiceCoachReply, setVoiceCoachReply] = useState<string>(
    'Tap the mic icon to toggle hands-free voice control.'
  );
  const [lastVoiceHeard, setLastVoiceHeard] = useState<string | null>(null);
  const voiceMicActiveRef = useRef(false);
  const voicePauseTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const voicePendingRef = useRef('');
  const voiceLastSubmittedIdxRef = useRef(0);
  const voiceRecognitionRef = useRef<{ stop: () => void } | null>(null);

  useEffect(() => {
    return () => {
      voiceMicActiveRef.current = false;
      if (voicePauseTimerRef.current) clearTimeout(voicePauseTimerRef.current);
      try {
        voiceRecognitionRef.current?.stop();
      } catch {
        // ignore
      }
    };
  }, []);

  const executeCompanionVoiceAction = useCallback(
    (action: CoachSuggestedAction): string => {
      soundEngine.playVoiceCommandExecuted();
      return executeCoachSuggestedAction(action, {
        currentWeightLb: telemetry.target_weight_lb,
        currentRepCount: setRepCount,
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
      setRepCount,
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

  const askCompanionCoach = async (promptText: string) => {
    const trimmed = promptText.trim();
    if (!trimmed || !activeUser) return;
    setLastVoiceHeard(trimmed);

    const fastAction = matchClientVoiceCommand(trimmed, activeUnit);
    let executedClientSide = false;
    if (fastAction) {
      executedClientSide = true;
      const confirmText = executeCompanionVoiceAction(fastAction);
      setVoiceCoachReply(confirmText);
      showToast(`${fastAction.label}: ${confirmText}`);
      speakCoachAudio(confirmText, soundMuted);
    }

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
        if (safeServerAction && !executedClientSide) {
          const confirmText = executeCompanionVoiceAction(safeServerAction);
          const finalReply = confirmText || data.reply;
          setVoiceCoachReply(finalReply);
          showToast(`${safeServerAction.label}: ${finalReply}`);
          speakCoachAudio(finalReply, soundMuted);
        } else if (!executedClientSide) {
          setVoiceCoachReply(data.reply);
          speakCoachAudio(data.reply, soundMuted);
        }
      }
    } catch {
      // ignore offline coach fetch error if client action already ran
    }
  };

  const stopCompanionVoice = () => {
    voiceMicActiveRef.current = false;
    if (voicePauseTimerRef.current) {
      clearTimeout(voicePauseTimerRef.current);
      voicePauseTimerRef.current = null;
    }
    try {
      voiceRecognitionRef.current?.stop();
    } catch {
      // ignore
    }
    voiceRecognitionRef.current = null;
    setIsVoiceListening(false);
  };

  const toggleCompanionVoice = () => {
    if (isVoiceListening || voiceMicActiveRef.current) {
      stopCompanionVoice();
      return;
    }
    const SpeechRec =
      (window as unknown as Record<string, unknown>).SpeechRecognition ||
      (window as unknown as Record<string, unknown>).webkitSpeechRecognition;
    if (!SpeechRec || typeof SpeechRec !== 'function') {
      setVoiceCoachReply('Voice recognition is not supported in this browser.');
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
          setLastVoiceHeard(trimmed);
        }
        if (voicePauseTimerRef.current) clearTimeout(voicePauseTimerRef.current);
        voicePauseTimerRef.current = setTimeout(() => {
          const query = voicePendingRef.current.trim();
          if (query) {
            voiceLastSubmittedIdxRef.current = event.results.length;
            voicePendingRef.current = '';
            void askCompanionCoach(query);
          }
        }, 1200);
      };

      recognition.onerror = (err) => {
        if (err?.error === 'not-allowed' || err?.error === 'service-not-allowed') {
          stopCompanionVoice();
          setVoiceCoachReply('Microphone permission denied.');
        }
      };

      recognition.onend = () => {
        if (voiceMicActiveRef.current) {
          setTimeout(launchRecognition, 150);
        } else {
          setIsVoiceListening(false);
        }
      };

      recognition.start();
    };

    voiceMicActiveRef.current = true;
    setIsVoiceListening(true);
    soundEngine.playVoiceListeningStart();
    launchRecognition();
  };

  const isLightTheme =
    activeUser?.themeMode === 'light' ||
    (activeUser?.themeMode === 'system' &&
      typeof window !== 'undefined' &&
      window.matchMedia('(prefers-color-scheme: light)').matches);

  const stepSmall = activeUnit === 'KG' ? 0.5 : 1;
  const stepLarge = activeUnit === 'KG' ? 2.5 : 5;
  const displayCurrentWeight = formatDisplayWeight(
    telemetry.weight_enabled ? telemetry.applied_resistance_lb : telemetry.target_weight_lb,
    activeUnit,
    telemetry.weight_enabled
  );
  const displayTargetWeight = formatDisplayWeight(telemetry.target_weight_lb, activeUnit);

  const groupByBodyArea = (list: typeof exercises) => {
    const groups = new Map<string, typeof exercises>();
    for (const ex of list) {
      const group = ex.muscleGroup || 'Other';
      const arr = groups.get(group) ?? [];
      arr.push(ex);
      groups.set(group, arr);
    }
    return Array.from(groups.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  };

  const allGroupedExercises = useMemo(() => groupByBodyArea(exercises), [exercises]);

  const searchedGroupedExercises = useMemo(() => {
    const q = exerciseSearch.trim().toLowerCase();
    if (!q) return allGroupedExercises;
    return groupByBodyArea(
      exercises.filter(
        (e) =>
          e.name.toLowerCase().includes(q) ||
          e.muscleGroup.toLowerCase().includes(q) ||
          e.accessory.toLowerCase().includes(q)
      )
    );
  }, [exercises, exerciseSearch, allGroupedExercises]);

  // Called whenever blocks shift position or a different routine loads, so search text
  // never ends up attached to the wrong block.
  const clearBlockSearch = () => {
    setExerciseSearch('');
    setSearchBlockIdx(null);
  };

  const loadTemplateIntoBuilder = (tpl: WorkoutTemplate) => {
    setEditingTemplateId(tpl.id);
    setRoutineName(tpl.name);
    setRoutineDesc(tpl.description ?? '');
    setBlocks(
      tpl.blocks.map((b) => ({
        ...b,
        items: b.items.map((item) => ({ ...item })),
      }))
    );
    clearBlockSearch();
    showToast(`Editing "${tpl.name}"`);
  };

  const resetBuilder = () => {
    setEditingTemplateId(null);
    setRoutineName('');
    setRoutineDesc('');
    setBlocks([{ blockType: 'STRAIGHT_SET', restSeconds: 60, items: [] }]);
    clearBlockSearch();
  };

  const addBlock = () => {
    setBlocks((prev) => [
      ...prev,
      { blockType: 'SUPERSET', restSeconds: 60, items: [] },
    ]);
  };

  const moveBlock = (fromIdx: number, toIdx: number) => {
    if (toIdx < 0 || toIdx >= blocks.length || fromIdx === toIdx) return;
    clearBlockSearch();
    setBlocks((prev) => {
      const next = [...prev];
      const [moved] = next.splice(fromIdx, 1);
      next.splice(toIdx, 0, moved);
      return next;
    });
  };

  // Inserts a deep copy of the block directly below the original, so it can be tweaked in place.
  const duplicateBlock = (idx: number) => {
    setBlocks((prev) => {
      const src = prev[idx];
      if (!src) return prev;
      const next = [...prev];
      next.splice(idx + 1, 0, { ...src, items: src.items.map((item) => ({ ...item })) });
      return next;
    });
    clearBlockSearch();
    showToast('Block copied below');
  };

  const removeBlock = (idx: number) => {
    clearBlockSearch();
    setBlocks((prev) => prev.filter((_, i) => i !== idx));
  };

  const addExerciseToBlock = (blockIdx: number, exerciseId: string) => {
    setBlocks((prev) =>
      prev.map((b, i) =>
        i === blockIdx
          ? {
              ...b,
              items: [
                ...b.items,
                {
                  exerciseId,
                  sets: 3,
                  targetReps: 10,
                  mode: 'standard',
                  modifierPct: 0,
                  targetWeightLb: 45,
                },
              ],
            }
          : b
      )
    );
  };

  const updateBlockItem = (
    blockIdx: number,
    itemIdx: number,
    patch: Partial<WorkoutBlock['items'][number]>
  ) => {
    setBlocks((prev) =>
      prev.map((b, bi) =>
        bi === blockIdx
          ? {
              ...b,
              items: b.items.map((it, ii) => (ii === itemIdx ? { ...it, ...patch } : it)),
            }
          : b
      )
    );
  };

  const removeBlockItem = (blockIdx: number, itemIdx: number) => {
    setBlocks((prev) =>
      prev.map((b, bi) =>
        bi === blockIdx
          ? { ...b, items: b.items.filter((_, ii) => ii !== itemIdx) }
          : b
      )
    );
  };

  const saveRoutine = async (andQueue: boolean) => {
    if (!activeUser) return;
    const validBlocks = blocks.filter((b) => b.items.length > 0);
    if (!routineName.trim() || validBlocks.length === 0) {
      showToast('Enter a routine name and add at least one exercise.');
      return;
    }

    const payload = {
      userId: activeUser.id,
      name: routineName.trim(),
      description: routineDesc.trim() || undefined,
      unit: 'LB' as const, // items hold canonical targetWeightLb
      blocks: validBlocks,
    };

    let savedId = editingTemplateId;
    if (editingTemplateId) {
      const resp = await fetch(`/api/templates/${encodeURIComponent(editingTemplateId)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!resp.ok) {
        showToast('Failed to update routine');
        return;
      }
      const updated = (await resp.json()) as { id: string };
      savedId = updated.id;
    } else {
      const resp = await fetch('/api/templates', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!resp.ok) {
        showToast('Failed to save routine');
        return;
      }
      const created = (await resp.json()) as { id: string };
      savedId = created.id;
      setEditingTemplateId(created.id);
    }

    await refreshBootstrap();

    if (andQueue && savedId) {
      await queueTemplateToTonal(savedId, routineName.trim());
    } else {
      showToast(`Saved "${routineName.trim()}"`);
    }
  };

  const queueTemplateToTonal = async (templateId: string, name: string) => {
    const resp = await fetch(`/api/templates/${encodeURIComponent(templateId)}/queue`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: activeUser?.id }),
    });
    if (resp.ok) {
      showToast(`Sent "${name}" to Tonal Kiosk!`);
    } else {
      showToast('Failed to send routine to Tonal');
    }
  };

  const deleteTemplate = async (templateId: string) => {
    const resp = await fetch(`/api/templates/${encodeURIComponent(templateId)}`, {
      method: 'DELETE',
    });
    if (resp.ok) {
      await refreshBootstrap();
      if (editingTemplateId === templateId) resetBuilder();
      showToast('Deleted routine');
    }
  };

  const triggerMockBle = async (action: 'toggle_weight' | 'spotify_next' | 'emergency_stop') => {
    await fetch('/api/daemon/mock/ble-event', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action }),
    });
    showToast(`Triggered BLE ${action}`);
  };

  return (
    <div
      className="relative swonal-canvas min-h-screen pb-24 overflow-x-hidden transition-colors duration-300"
      style={{ color: 'var(--text-primary)' }}
    >
      {/* Subtle Architectural Crosshair / Blueprint Grid & Anodized Grain */}
      <div
        aria-hidden="true"
        className="swonal-dot-grid fixed inset-0 pointer-events-none z-0"
      />
      <div
        aria-hidden="true"
        className="swonal-noise-grain fixed inset-0 pointer-events-none z-0"
      />
      {/* Dynamic Ambient Profile Bloom */}
      <div
        aria-hidden="true"
        className="fixed -top-24 -right-24 w-[420px] h-[420px] rounded-full blur-[140px] opacity-80 pointer-events-none z-0 transition-colors duration-500"
        style={{ backgroundColor: 'var(--accent-muted)' }}
      />

      {/* Mobile-First Header */}
      <header className="sticky top-0 z-20 backdrop-blur-xl border-b px-4 py-3" style={{ borderColor: 'var(--border-subtle)', backgroundColor: 'var(--bg-surface)' }}>
        <div className="max-w-lg mx-auto w-full flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 overflow-x-auto">
            {users.map((u) => {
              const active = u.id === activeUser?.id;
              return (
                <button
                  key={u.id}
                  type="button"
                  onClick={() => void switchUser(u.id)}
                  className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-bold shrink-0 transition ${
                    active ? '' : 'elevated-pill opacity-75'
                  }`}
                  style={
                    active
                      ? {
                          backgroundColor: u.accentColor,
                          color: getContrastTextColor(u.accentColor),
                        }
                      : undefined
                  }
                >
                  <span>{u.name}</span>
                </button>
              );
            })}
          </div>

          <div className="flex items-center gap-1.5 shrink-0">
            <button
              type="button"
              onClick={() => togglePartnerMode(!sessionState.partnerModeEnabled)}
              className={`px-2.5 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1 ${
                sessionState.partnerModeEnabled ? '' : 'elevated-pill'
              }`}
              style={
                sessionState.partnerModeEnabled
                  ? { backgroundColor: 'var(--accent-primary)', color: accentTextColor }
                  : undefined
              }
            >
              <Users size={14} />
              <span>Work In</span>
            </button>
            <button
              type="button"
              onClick={() => {
                void refreshMusicNow();
                musicDialogRef.current?.showModal();
              }}
              title="Spotify Playlists & Tonal Speakers"
              aria-label="Open Music & Speakers"
              className="elevated-pill px-2.5 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1"
            >
              <Music size={14} style={{ color: 'var(--accent-ink)' }} />
              <span>Music</span>
            </button>
            <a
              href="/settings"
              title="Application Settings"
              aria-label="Application Settings"
              className="elevated-pill p-1.5 rounded-xl flex items-center justify-center hover:opacity-90"
            >
              <Settings size={15} style={{ color: 'var(--accent-ink)' }} />
            </a>
            <button
              type="button"
              onClick={() => {
                if (!activeUser) return;
                void updateUserPreferences(activeUser.id, {
                  themeMode: isLightTheme ? 'dark' : 'light',
                });
              }}
              className="elevated-pill p-1.5 rounded-xl"
            >
              {isLightTheme ? <Moon size={15} /> : <Sun size={15} />}
            </button>
            <span
              className="elevated-pill p-2 rounded-xl flex items-center justify-center"
              title={daemonConnected ? 'Connected to Tonal' : 'Daemon waiting'}
            >
              <span
                className={`w-2.5 h-2.5 rounded-full ${
                  wsConnected && daemonConnected ? 'bg-emerald-400' : 'bg-amber-400'
                }`}
              />
            </span>
          </div>
        </div>
      </header>

      {/* Toast Feedback */}
      {toast && (
        <div className="max-w-lg mx-auto w-full px-4 pt-3">
          <div
            className="surface-card rounded-2xl px-4 py-2.5 text-sm font-bold flex items-center gap-2 border"
            style={{ borderColor: 'var(--accent-primary)', color: 'var(--accent-ink)' }}
          >
            <Sparkles size={16} />
            <span>{toast}</span>
          </div>
        </div>
      )}

      {/* Main Content */}
      <main className="max-w-lg mx-auto w-full px-4 py-4 pb-24 space-y-4">
        {tab === 'remote' && (
          <>
            {/* Live Telemetry & Big Weight Toggle Card */}
            <section className="surface-card rounded-3xl p-5 space-y-5">
              <div>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <p className="text-[11px] uppercase tracking-widest font-bold opacity-65">
                      Active Movement
                    </p>
                    <h2 className="text-[22px] leading-tight font-black truncate mt-0.5">
                      {activeExercise?.name ?? 'Free Lift'}
                    </h2>
                    <p className="text-xs font-semibold opacity-80 mt-0.5">
                      {currentStep
                        ? `Set ${currentStep.setNumber} of ${currentStep.totalSetsInItem} • Target ${currentStep.targetReps} Reps`
                        : 'Free Lift Mode'}
                    </p>
                  </div>
                  <div className="text-right font-mono tabular-nums shrink-0">
                    <p className="text-xs font-bold tracking-wider opacity-65">REPS</p>
                    <p className="text-4xl font-black leading-none mt-0.5">
                      {setRepCount}
                      {currentStep ? (
                        <span className="text-lg opacity-55"> / {currentStep.targetReps}</span>
                      ) : null}
                    </p>
                  </div>
                </div>

                <div className="flex items-center justify-between gap-2 mt-3 flex-wrap">
                  <div className="flex items-center gap-2 min-w-0">
                    <button
                      type="button"
                      onClick={() => workoutPickerDialogRef.current?.showModal()}
                      className="h-8 px-2.5 rounded-lg elevated-pill inline-flex items-center gap-1.5 text-xs font-sans font-bold hover:opacity-90 shrink-0"
                    >
                      <Dumbbell size={13} style={{ color: 'var(--accent-ink)' }} />
                      <span>Saved Workouts</span>
                    </button>
                    {activeExercise && (() => {
                      const arms = telemetry.arm_positions;
                      const okH = acceptedPositions(activeExercise.allowedHeights, activeExercise.recommendedHeight);
                      const okR = acceptedPositions(activeExercise.allowedRotations, activeExercise.recommendedRotation);
                      const okA = acceptedPositions(activeExercise.allowedAngles, activeExercise.recommendedAngle);
                      const target = formatArmTarget(activeExercise);
                      const lH = Math.round(arms?.height?.left ?? 0);
                      const rH = Math.round(arms?.height?.right ?? 0);
                      const lR = Math.round(arms?.rotation?.left ?? 0);
                      const rR = Math.round(arms?.rotation?.right ?? 0);
                      const lA = Math.round(arms?.angle?.left ?? 0);
                      const rA = Math.round(arms?.angle?.right ?? 0);
                      const armsSynced = daemonConnected && armsMatchExercise(activeExercise, arms);
                      return (
                        <div className="relative inline-block">
                          <button
                            type="button"
                            onClick={() => setShowArmInfo((v) => !v)}
                            className={`h-8 px-2.5 rounded-lg inline-flex items-center gap-1.5 text-[11px] font-mono font-bold whitespace-nowrap border ${
                              armsSynced
                                ? 'bg-emerald-500/15 border-emerald-500/40 text-emerald-400'
                                : daemonConnected
                                  ? 'bg-amber-500/15 border-amber-500/40 text-amber-300'
                                  : 'elevated-pill opacity-85'
                            }`}
                          >
                            <span>
                              {activeExercise.accessory} • {target}
                            </span>
                            <Info size={12} />
                          </button>
                          {showArmInfo && (
                            <div className="absolute left-0 top-9 w-64 surface-card rounded-xl p-3 shadow-xl border text-[11px] font-mono space-y-1.5 z-30">
                              <div className="flex items-center justify-between font-sans">
                                <span className="font-bold">Live Arm Sync</span>
                                <span className={armsSynced ? 'text-emerald-400 font-bold' : 'text-amber-300 font-bold'}>
                                  {armsSynced ? 'SYNCED' : daemonConnected ? 'ADJUST ARMS' : 'OFFLINE'}
                                </span>
                              </div>
                              <div className="space-y-0.5 text-[11px]">
                                <p className="opacity-75">Target  {target}</p>
                                <p>
                                  Left    <span className={okH.includes(lH) ? 'text-emerald-400' : 'text-rose-400'}>H{lH}</span>  <span className={okR.includes(lR) ? 'text-emerald-400' : 'text-rose-400'}>R{lR}</span>  <span className={okA.includes(lA) ? 'text-emerald-400' : 'text-rose-400'}>A{lA}</span>
                                </p>
                                <p>
                                  Right   <span className={okH.includes(rH) ? 'text-emerald-400' : 'text-rose-400'}>H{rH}</span>  <span className={okR.includes(rR) ? 'text-emerald-400' : 'text-rose-400'}>R{rR}</span>  <span className={okA.includes(rA) ? 'text-emerald-400' : 'text-rose-400'}>A{rA}</span>
                                </p>
                              </div>
                              <p className="font-sans text-[10px] opacity-65 pt-1 border-t border-[var(--border-subtle)]">
                                H: Height 1 bottom–9 top • R: Rotation 2–6 latches (1 stowed) • A: Angle 1 down–5 up (0 folded)
                              </p>
                            </div>
                          )}
                        </div>
                      );
                    })()}
                  </div>

                  <div className="flex items-center gap-1.5 shrink-0">
                    <button
                      type="button"
                      onClick={() => stepRoutine(-1)}
                      className="h-8 px-2.5 rounded-lg elevated-pill inline-flex items-center gap-0.5 text-xs font-sans font-bold hover:opacity-90"
                    >
                      <ChevronLeft size={14} />
                      <span>Prev</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => stepRoutine(1)}
                      className="h-8 px-2.5 rounded-lg elevated-pill inline-flex items-center gap-0.5 text-xs font-sans font-bold hover:opacity-90"
                    >
                      <span>Next</span>
                      <ChevronRight size={14} />
                    </button>
                  </div>
                </div>
              </div>

              <div className="flex items-center justify-between gap-4">
                <button
                  type="button"
                  onClick={() => {
                    if (telemetry.weight_enabled) {
                      toggleWeight();
                    }
                  }}
                  onDoubleClick={() => {
                    if (!telemetry.weight_enabled) {
                      cancelHoldEngage();
                      toggleWeight();
                    }
                  }}
                  onPointerDown={beginHoldEngage}
                  onPointerUp={cancelHoldEngage}
                  onPointerLeave={cancelHoldEngage}
                  onPointerCancel={cancelHoldEngage}
                  className={`flex-1 py-6 rounded-3xl flex flex-col items-center justify-center border-2 transition select-none ${
                    telemetry.weight_enabled ? '' : 'elevated-pill swonal-carbon-pad'
                  } ${holdingEngage ? 'scale-[0.98] opacity-90' : ''}`}
                  style={
                    telemetry.weight_enabled
                      ? {
                          backgroundColor: 'var(--accent-primary)',
                          color: accentTextColor,
                          borderColor: 'var(--accent-primary)',
                          boxShadow: '0 0 28px var(--accent-glow)',
                        }
                      : undefined
                  }
                >
                  <span className="inline-flex items-baseline gap-1.5 font-mono tabular-nums leading-none">
                    <span className="text-5xl font-black">{displayCurrentWeight}</span>
                    <span className="text-2xl font-bold opacity-75">{activeUnit}</span>
                  </span>
                  <span className="text-xs uppercase tracking-widest font-extrabold mt-2">
                    {telemetry.weight_enabled
                      ? 'WEIGHT ON • TAP TO STOP'
                      : holdingEngage
                        ? 'HOLDING TO ENGAGE...'
                        : 'WEIGHT OFF • HOLD OR DOUBLE-TAP TO ENGAGE'}
                  </span>
                </button>
              </div>

              {/* Left / Right Cable ROM Mini Bars */}
              <div className="grid grid-cols-2 gap-3 font-mono tabular-nums text-xs">
                <div className="elevated-pill rounded-2xl p-3">
                  <div className="flex justify-between mb-1.5">
                    <span>LEFT CABLE</span>
                    <span>{telemetry.left_cable.position.toFixed(0)}</span>
                  </div>
                  <div className="h-2 rounded-full bg-black/25 overflow-hidden">
                    <div
                      className="h-full transition-all duration-75"
                      style={{
                        width: `${Math.min((telemetry.left_cable.position / 200) * 100, 100)}%`,
                        backgroundColor: 'var(--accent-primary)',
                      }}
                    />
                  </div>
                </div>
                <div className="elevated-pill rounded-2xl p-3">
                  <div className="flex justify-between mb-1.5">
                    <span>RIGHT CABLE</span>
                    <span>{telemetry.right_cable.position.toFixed(0)}</span>
                  </div>
                  <div className="h-2 rounded-full bg-black/25 overflow-hidden">
                    <div
                      className="h-full transition-all duration-75"
                      style={{
                        width: `${Math.min((telemetry.right_cable.position / 200) * 100, 100)}%`,
                        backgroundColor: 'var(--accent-primary)',
                      }}
                    />
                  </div>
                </div>
              </div>

              {/* Weight Adjustment Stepper */}
              <div>
                <div className="flex items-center justify-between text-xs uppercase tracking-widest font-bold opacity-65 mb-2">
                  <span>Target Resistance</span>
                </div>
                <div className="grid grid-cols-4 gap-2">
                  <button
                    type="button"
                    onClick={() =>
                      setWeight(stepDisplayWeight(telemetry.target_weight_lb, -stepLarge, activeUnit))
                    }
                    className="elevated-pill py-3 rounded-2xl font-mono font-bold"
                  >
                    -{stepLarge}
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      setWeight(stepDisplayWeight(telemetry.target_weight_lb, -stepSmall, activeUnit))
                    }
                    className="elevated-pill py-3 rounded-2xl font-mono font-bold"
                  >
                    -{stepSmall}
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      setWeight(stepDisplayWeight(telemetry.target_weight_lb, stepSmall, activeUnit))
                    }
                    className="elevated-pill py-3 rounded-2xl font-mono font-bold"
                  >
                    +{stepSmall}
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      setWeight(stepDisplayWeight(telemetry.target_weight_lb, stepLarge, activeUnit))
                    }
                    className="elevated-pill py-3 rounded-2xl font-mono font-bold"
                  >
                    +{stepLarge}
                  </button>
                </div>
              </div>

              {/* Dynamic Mode Pills */}
              <div className="flex flex-wrap gap-2">
                {DYNAMIC_MODES.map((m) => {
                  const active = telemetry.profile_mode === m.id;
                  return (
                    <button
                      key={m.id}
                      type="button"
                      onClick={() => setWeight(telemetry.target_weight_lb, m.id, m.defaultPct)}
                      className={`px-3.5 py-2 rounded-xl text-xs font-bold ${
                        active ? '' : 'elevated-pill opacity-80'
                      }`}
                      style={
                        active
                          ? { backgroundColor: 'var(--accent-primary)', color: accentTextColor }
                          : undefined
                      }
                    >
                      {m.label}
                    </button>
                  );
                })}
              </div>

              {/* Rest Timer & Set Logging */}
              <div className="border-t pt-4 space-y-3" style={{ borderColor: 'var(--border-subtle)' }}>
                {restSecondsRemaining > 0 && (
                  <div className="elevated-pill rounded-2xl p-3 flex items-center justify-between">
                    <div>
                      <p className="text-[11px] uppercase font-bold opacity-65">Rest Countdown</p>
                      <p
                        className="font-mono tabular-nums text-2xl font-black"
                        style={{ color: 'var(--accent-ink)' }}
                      >
                        {Math.floor(restSecondsRemaining / 60)}:
                        {String(restSecondsRemaining % 60).padStart(2, '0')}
                      </p>
                    </div>
                    <div className="flex gap-2">
                      <button
                        type="button"
                        onClick={() => extendRestTimer(15)}
                        className="surface-card px-3 py-2 rounded-xl text-xs font-bold"
                      >
                        +15s
                      </button>
                      <button
                        type="button"
                        onClick={skipRestTimer}
                        className="px-3 py-2 rounded-xl text-xs font-bold text-slate-950 flex items-center gap-1"
                        style={{ backgroundColor: 'var(--accent-primary)' }}
                      >
                        <SkipForward size={14} />
                        <span>Skip Rest</span>
                      </button>
                    </div>
                  </div>
                )}

                <div className="relative flex items-center gap-2">
                  <div className="elevated-pill px-3 py-2.5 rounded-xl text-xs font-bold flex items-center gap-1.5">
                    <button
                      type="button"
                      onClick={() => setShowRpeInfo((v) => !v)}
                      aria-label="Explain RPE Scale"
                      className="opacity-75 hover:opacity-100"
                    >
                      <Info size={13} />
                    </button>
                    <label className="flex items-center gap-1.5">
                      <span>RPE</span>
                      <select
                        value={selectedRpe}
                        onChange={(e) => setSelectedRpe(Number(e.target.value))}
                        className="bg-transparent font-mono outline-none"
                      >
                        {[6, 7, 7.5, 8, 8.5, 9, 9.5, 10].map((v) => (
                          <option key={v} value={v}>
                            {v}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>
                  {showRpeInfo && (
                    <div className="absolute left-0 bottom-14 w-72 surface-card rounded-xl p-3 shadow-xl border text-[11px] space-y-1 z-30">
                      <p className="font-bold">RPE (Rate of Perceived Exertion, 6–10)</p>
                      <p><strong>7:</strong> ~3 reps left in reserve</p>
                      <p><strong>8:</strong> ~2 reps left (ideal working set)</p>
                      <p><strong>9:</strong> ~1 rep left • <strong>10:</strong> Max effort</p>
                    </div>
                  )}
                  <button
                    type="button"
                    onClick={() => {
                      void logCompletedSet(undefined, selectedRpe).then((result) => {
                        if (result === 'logged') showToast('Logged set!');
                      });
                    }}
                    className="flex-1 py-3 rounded-2xl font-extrabold text-sm"
                    style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                  >
                    Log Set & Start Rest
                  </button>
                </div>
              </div>
            </section>

            {/* Live VBT & Symmetry Strip */}
            <section className="surface-card rounded-3xl p-4 grid grid-cols-2 gap-3">
              <div className="elevated-pill rounded-2xl p-3 space-y-1">
                <div className="flex items-center justify-between gap-1">
                  <span className="text-[10px] font-mono font-extrabold uppercase tracking-wider opacity-70">
                    VBT Velocity
                  </span>
                  <span
                    className={`text-[10px] font-mono font-black ${
                      vbtMetrics.fatigueAlert ? 'text-amber-400' : 'text-emerald-400'
                    }`}
                  >
                    -{Math.round(vbtMetrics.velocityDropPct)}% Drop
                  </span>
                </div>
                <p className="text-lg font-mono font-black tabular-nums">
                  {activeUnit === 'LB'
                    ? (vbtMetrics.currentVelocity / 2.54).toFixed(1)
                    : vbtMetrics.currentVelocity.toFixed(1)}{' '}
                  <span className="text-[11px] opacity-70">
                    {activeUnit === 'LB' ? 'in/s' : 'cm/s'}
                  </span>
                </p>
                <p className="text-[10px] font-mono opacity-65">
                  Peak{' '}
                  {activeUnit === 'LB'
                    ? (vbtMetrics.peakSetVelocity / 2.54).toFixed(1)
                    : vbtMetrics.peakSetVelocity.toFixed(1)}{' '}
                  {activeUnit === 'LB' ? 'in/s' : 'cm/s'}
                </p>
              </div>

              <div className="elevated-pill rounded-2xl p-3 space-y-1">
                <div className="flex items-center justify-between gap-1">
                  <span className="text-[10px] font-mono font-extrabold uppercase tracking-wider opacity-70">
                    Symmetry
                  </span>
                  <span
                    className={`text-[10px] font-mono font-black uppercase ${
                      symmetryMetrics.status === 'critical'
                        ? 'text-rose-400'
                        : symmetryMetrics.status === 'drift'
                          ? 'text-amber-400'
                          : 'text-emerald-400'
                    }`}
                  >
                    {symmetryMetrics.status}
                  </span>
                </div>
                <p className="text-lg font-mono font-black tabular-nums">
                  L {Math.round(symmetryMetrics.leftLoadPct)}%{' '}
                  <span className="opacity-40">/</span> R {Math.round(symmetryMetrics.rightLoadPct)}%
                </p>
                <p className="text-[10px] font-mono opacity-65">
                  Δ {symmetryMetrics.positionDelta.toFixed(1)} ROM
                </p>
              </div>
            </section>

            {/* Hands-Free Voice Control Card (Toggle-Only) */}
            <section className="surface-card rounded-3xl p-4">
              <div className="flex items-center gap-3 min-w-0">
                <button
                  type="button"
                  onClick={toggleCompanionVoice}
                  aria-pressed={isVoiceListening}
                  aria-label={isVoiceListening ? 'Turn Voice Control Off' : 'Turn Voice Control On'}
                  className={`w-11 h-11 rounded-2xl flex items-center justify-center shrink-0 transition ${
                    isVoiceListening
                      ? 'bg-red-500 text-white animate-pulse shadow-md'
                      : 'elevated-pill hover:opacity-90'
                  }`}
                >
                  {isVoiceListening ? (
                    <Mic size={20} />
                  ) : (
                    <MicOff size={20} style={{ color: 'var(--accent-ink)' }} />
                  )}
                </button>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <h3 className="text-sm font-black">Voice Control</h3>
                    <span
                      className={`px-2 py-0.5 rounded-full text-[10px] font-mono font-black uppercase ${
                        isVoiceListening
                          ? 'bg-red-500/20 text-red-400'
                          : 'elevated-pill opacity-70'
                      }`}
                    >
                      {isVoiceListening ? 'On' : 'Off'}
                    </span>
                  </div>
                  <p className="text-xs opacity-75 truncate">
                    {lastVoiceHeard ? `"${lastVoiceHeard}" → ${voiceCoachReply}` : voiceCoachReply}
                  </p>
                </div>
              </div>
            </section>



            <dialog
              ref={workoutPickerDialogRef}
              closedby="any"
              aria-labelledby="companion-workout-picker-title"
              className="rounded-3xl p-6 max-w-lg w-full max-h-[82vh] overflow-y-auto"
            >
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-2">
                  <Dumbbell size={20} style={{ color: 'var(--accent-ink)' }} />
                  <h2 id="companion-workout-picker-title" className="text-xl font-black">
                    Saved Workouts
                  </h2>
                </div>
                <select
                  aria-label="Filter saved workouts by lifter"
                  value={routineUserFilter}
                  onChange={(e) => setRoutineUserFilter(e.target.value)}
                  className="elevated-pill px-2.5 py-1.5 rounded-xl text-xs font-bold outline-none"
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
              <div className="space-y-2.5">
                {(() => {
                  const targetUserId =
                    routineUserFilter === 'ACTIVE' ? activeUser?.id : routineUserFilter;
                  const visibleTemplates =
                    routineUserFilter === 'ALL'
                      ? templates
                      : templates.filter((t) => t.userId === targetUserId);
                  if (visibleTemplates.length === 0) {
                    return (
                      <p className="text-xs opacity-70 elevated-pill rounded-2xl p-4">
                        No saved workouts for this lifter yet. Create one in the Builder tab.
                      </p>
                    );
                  }
                  return visibleTemplates.map((tpl) => {
                    const owner = users.find((u) => u.id === tpl.userId);
                    return (
                      <div
                        key={tpl.id}
                        className="elevated-pill rounded-2xl p-3.5 flex items-center justify-between gap-3"
                      >
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <span
                              className="w-2.5 h-2.5 rounded-full shrink-0"
                              style={{
                                backgroundColor: owner?.accentColor ?? 'var(--accent-primary)',
                              }}
                            />
                            <p className="font-extrabold text-sm truncate">{tpl.name}</p>
                          </div>
                          <p className="text-xs opacity-70 truncate mt-0.5">
                            {owner?.name} • {tpl.blocks.length} blocks
                          </p>
                        </div>
                        <button
                          type="button"
                          onClick={() => {
                            startWorkoutTemplate(tpl);
                            void queueTemplateToTonal(tpl.id, tpl.name);
                            workoutPickerDialogRef.current?.close();
                          }}
                          className="px-3.5 py-2 rounded-xl text-xs font-extrabold text-slate-950 shrink-0"
                          style={{ backgroundColor: 'var(--accent-primary)' }}
                        >
                          Start
                        </button>
                      </div>
                    );
                  });
                })()}
              </div>
              <div className="mt-4 flex justify-end">
                <button
                  type="button"
                  onClick={() => workoutPickerDialogRef.current?.close()}
                  className="elevated-pill px-4 py-2 rounded-xl text-xs font-bold"
                >
                  Close
                </button>
              </div>
            </dialog>

            {/* Collapsible Mock Hardware Handle Shortcuts (shown only in --mock mode) */}
            {isMockMode && (
              <details className="surface-card rounded-3xl p-4">
                <summary className="text-[11px] uppercase tracking-widest font-bold opacity-75 cursor-pointer select-none">
                  Mock Hardware Handle Shortcuts
                </summary>
                <div className="grid grid-cols-3 gap-2 mt-3">
                  <button
                    type="button"
                    onClick={() => void triggerMockBle('toggle_weight')}
                    className="elevated-pill py-2.5 rounded-xl text-xs font-bold"
                  >
                    Handle 1x
                  </button>
                  <button
                    type="button"
                    onClick={() => void triggerMockBle('spotify_next')}
                    className="elevated-pill py-2.5 rounded-xl text-xs font-bold"
                  >
                    Handle 2x (Skip)
                  </button>
                  <button
                    type="button"
                    onClick={() => void triggerMockBle('emergency_stop')}
                    className="bg-red-500/15 border border-red-500/35 text-red-400 py-2.5 rounded-xl text-xs font-bold"
                  >
                    E-Stop Hold
                  </button>
                </div>
              </details>
            )}
          </>
        )}

        {tab === 'builder' && (
          <div className="space-y-4">
            {/* Existing Routines + One-Tap Send to Tonal */}
            <section className="surface-card rounded-3xl p-5 space-y-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-lg font-black">Saved Routines</h2>
                <div className="flex items-center gap-2">
                  <select
                    aria-label="Filter routines by lifter"
                    value={routineUserFilter}
                    onChange={(e) => setRoutineUserFilter(e.target.value)}
                    className="elevated-pill px-2.5 py-1.5 rounded-xl text-xs font-bold outline-none"
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
                  <button
                    type="button"
                    onClick={resetBuilder}
                    className="elevated-pill px-3 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1"
                  >
                    <Plus size={14} />
                    <span>New Routine</span>
                  </button>
                </div>
              </div>

              <div className="space-y-2.5">
                {(() => {
                  const targetUserId =
                    routineUserFilter === 'ACTIVE' ? activeUser?.id : routineUserFilter;
                  const visibleTemplates =
                    routineUserFilter === 'ALL'
                      ? templates
                      : templates.filter((t) => t.userId === targetUserId);
                  if (visibleTemplates.length === 0) {
                    return (
                      <p className="text-xs opacity-70 elevated-pill rounded-2xl p-4">
                        No saved routines for this lifter yet. Build one below and save it!
                      </p>
                    );
                  }
                  return visibleTemplates.map((tpl) => {
                  const owner = users.find((u) => u.id === tpl.userId);
                  return (
                    <div
                      key={tpl.id}
                      className="elevated-pill rounded-2xl p-3.5 flex items-center justify-between gap-3"
                    >
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <span
                            className="w-2.5 h-2.5 rounded-full shrink-0"
                            style={{ backgroundColor: owner?.accentColor ?? 'var(--accent-primary)' }}
                          />
                          <p className="font-extrabold text-sm truncate">{tpl.name}</p>
                        </div>
                        <p className="text-xs opacity-70 truncate mt-0.5">
                          {owner?.name} • {tpl.blocks.length} blocks
                        </p>
                      </div>
                      <div className="flex items-center gap-1.5 shrink-0">
                        <button
                          type="button"
                          onClick={() => loadTemplateIntoBuilder(tpl)}
                          className="surface-card px-3 py-2 rounded-xl text-xs font-bold"
                        >
                          Edit
                        </button>
                        <button
                          type="button"
                          onClick={() => void queueTemplateToTonal(tpl.id, tpl.name)}
                          className="px-3 py-2 rounded-xl text-xs font-extrabold text-slate-950 flex items-center gap-1"
                          style={{ backgroundColor: 'var(--accent-primary)' }}
                        >
                          <Send size={13} />
                          <span>Send to Tonal</span>
                        </button>
                        <button
                          type="button"
                          onClick={() => void deleteTemplate(tpl.id)}
                          className="p-2 rounded-xl text-red-400 hover:bg-red-500/15"
                          title="Delete Routine"
                        >
                          <Trash2 size={15} />
                        </button>
                      </div>
                    </div>
                  );
                });
                })()}
              </div>
            </section>

            {/* Workout Builder Composer */}
            <section className="surface-card rounded-3xl p-5 space-y-4">
              <h2 className="text-lg font-black">
                {editingTemplateId ? 'Edit Routine' : 'Workout Builder'}
              </h2>

              <div className="space-y-2.5">
                <input
                  type="text"
                  value={routineName}
                  onChange={(e) => setRoutineName(e.target.value)}
                  placeholder="Routine Name (e.g., Sunday Hypertrophy)"
                  className="w-full elevated-pill px-4 py-3 rounded-2xl text-sm font-semibold outline-none"
                />
                <input
                  type="text"
                  value={routineDesc}
                  onChange={(e) => setRoutineDesc(e.target.value)}
                  placeholder="Optional description or focus notes..."
                  className="w-full elevated-pill px-4 py-2.5 rounded-2xl text-xs outline-none"
                />
              </div>

              <div className="space-y-3">
                {blocks.map((block, bIdx) => (
                  <div
                    key={bIdx}
                    draggable
                    onDragStart={() => setDragBlockIdx(bIdx)}
                    onDragOver={(e) => e.preventDefault()}
                    onDrop={() => {
                      if (dragBlockIdx !== null) {
                        moveBlock(dragBlockIdx, bIdx);
                        setDragBlockIdx(null);
                      }
                    }}
                    className="elevated-pill rounded-2xl p-4 space-y-3 border"
                    style={{ borderColor: 'var(--border-subtle)' }}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <select
                          value={block.blockType}
                          onChange={(e) =>
                            setBlocks((prev) =>
                              prev.map((b, i) =>
                                i === bIdx
                                  ? { ...b, blockType: e.target.value as BlockType }
                                  : b
                              )
                            )
                          }
                          className="surface-card px-3 py-1.5 rounded-xl text-xs font-extrabold"
                        >
                          {BLOCK_TYPES.map((bt) => (
                            <option key={bt} value={bt}>
                              {bt}
                            </option>
                          ))}
                        </select>
                        <label className="text-xs font-bold flex items-center gap-1">
                          <span>Rest:</span>
                          <input
                            type="number"
                            min={0}
                            max={300}
                            value={block.restSeconds}
                            onChange={(e) =>
                              setBlocks((prev) =>
                                prev.map((b, i) =>
                                  i === bIdx
                                    ? { ...b, restSeconds: Number(e.target.value) }
                                    : b
                                )
                              )
                            }
                            className="w-14 surface-card px-2 py-1 rounded-lg font-mono text-xs text-center"
                          />
                          <span>s</span>
                        </label>
                      </div>

                      <div className="flex items-center gap-1">
                        <button
                          type="button"
                          onClick={() => moveBlock(bIdx, bIdx - 1)}
                          className="surface-card p-1.5 rounded-lg"
                          title="Move Block Up"
                        >
                          <ArrowUp size={14} />
                        </button>
                        <button
                          type="button"
                          onClick={() => moveBlock(bIdx, bIdx + 1)}
                          className="surface-card p-1.5 rounded-lg"
                          title="Move Block Down"
                        >
                          <ArrowDown size={14} />
                        </button>
                        <button
                          type="button"
                          onClick={() => duplicateBlock(bIdx)}
                          className="surface-card p-1.5 rounded-lg"
                          title="Copy Block"
                          aria-label="Copy Block"
                        >
                          <Copy size={14} />
                        </button>
                        {blocks.length > 1 && (
                          <button
                            type="button"
                            onClick={() => removeBlock(bIdx)}
                            className="p-1.5 rounded-lg text-red-400"
                            title="Remove Block"
                          >
                            <Trash2 size={14} />
                          </button>
                        )}
                      </div>
                    </div>

                    {/* Items inside block */}
                    <div className="space-y-2">
                      {block.items.map((item, iIdx) => {
                        const ex = exercises.find((e) => e.id === item.exerciseId);
                        const shownItemWeight = formatDisplayWeight(
                          item.targetWeightLb,
                          activeUnit
                        );
                        return (
                          <div
                            key={iIdx}
                            className="surface-card rounded-xl p-3 space-y-2"
                          >
                            <div className="flex items-center justify-between gap-2">
                              <span className="font-bold text-sm">
                                {ex?.name ?? 'Exercise'}
                              </span>
                              <button
                                type="button"
                                onClick={() => removeBlockItem(bIdx, iIdx)}
                                className="text-red-400 p-1"
                              >
                                <Trash2 size={14} />
                              </button>
                            </div>
                            <div className="grid grid-cols-4 gap-2 text-xs">
                              <label className="flex flex-col gap-1">
                                <span className="opacity-65">Sets</span>
                                <input
                                  type="number"
                                  min={1}
                                  max={15}
                                  value={item.sets}
                                  onChange={(e) =>
                                    updateBlockItem(bIdx, iIdx, {
                                      sets: Math.max(1, Number(e.target.value)),
                                    })
                                  }
                                  className="elevated-pill px-2 py-1.5 rounded-lg font-mono"
                                />
                              </label>
                              <label className="flex flex-col gap-1">
                                <span className="opacity-65">Reps</span>
                                <input
                                  type="number"
                                  min={1}
                                  max={50}
                                  value={item.targetReps}
                                  onChange={(e) =>
                                    updateBlockItem(bIdx, iIdx, {
                                      targetReps: Math.max(1, Number(e.target.value)),
                                    })
                                  }
                                  className="elevated-pill px-2 py-1.5 rounded-lg font-mono"
                                />
                              </label>
                              <label className="flex flex-col gap-1">
                                <span className="opacity-65">{activeUnit}</span>
                                <input
                                  type="number"
                                  min={activeUnit === 'KG' ? 2.5 : 5}
                                  max={activeUnit === 'KG' ? 91 : 200}
                                  step={activeUnit === 'KG' ? 0.5 : 5}
                                  value={shownItemWeight}
                                  onChange={(e) => {
                                    const val = Number(e.target.value);
                                    const lb =
                                      activeUnit === 'KG'
                                        ? Math.min(200, Math.max(5, Math.round(val * KG_TO_LB * 2) / 2))
                                        : Math.min(200, Math.max(5, val));
                                    updateBlockItem(bIdx, iIdx, { targetWeightLb: lb });
                                  }}
                                  className="elevated-pill px-2 py-1.5 rounded-lg font-mono"
                                />
                              </label>
                              <label className="flex flex-col gap-1">
                                <span className="opacity-65">Mode</span>
                                <select
                                  value={item.mode}
                                  onChange={(e) => {
                                    const m = e.target.value as DynamicMode;
                                    updateBlockItem(bIdx, iIdx, {
                                      mode: m,
                                      modifierPct:
                                        m === 'eccentric' || m === 'chains' ? 25 : 0,
                                    });
                                  }}
                                  className="elevated-pill px-1.5 py-1.5 rounded-lg"
                                >
                                  {DYNAMIC_MODES.map((m) => (
                                    <option key={m.id} value={m.id}>
                                      {m.label}
                                    </option>
                                  ))}
                                </select>
                              </label>
                            </div>
                          </div>
                        );
                      })}
                    </div>

                    {/* Quick Exercise Adder for Block (Grouped by Body Area) */}
                    <div className="pt-1 space-y-2">
                      <input
                        type="search"
                        value={searchBlockIdx === bIdx ? exerciseSearch : ''}
                        onChange={(e) => {
                          setSearchBlockIdx(bIdx);
                          setExerciseSearch(e.target.value);
                        }}
                        placeholder="Search exercise to add to this block..."
                        className="w-full surface-card px-3 py-2 rounded-xl text-xs outline-none"
                      />
                      <div className="space-y-1.5 max-h-64 overflow-y-auto pr-0.5">
                        {(searchBlockIdx === bIdx ? searchedGroupedExercises : allGroupedExercises)
                          .length === 0 ? (
                          <p className="text-xs opacity-65 px-2 py-1">No matching exercises.</p>
                        ) : (
                          (searchBlockIdx === bIdx
                            ? searchedGroupedExercises
                            : allGroupedExercises
                          ).map(([bodyArea, list]) => (
                            <details
                              key={bodyArea}
                              open={searchBlockIdx === bIdx && Boolean(exerciseSearch.trim())}
                              className="surface-card rounded-xl overflow-hidden"
                            >
                              <summary className="px-3 py-2 text-xs font-extrabold uppercase tracking-wider cursor-pointer select-none flex items-center justify-between">
                                <span>{bodyArea}</span>
                                <span className="opacity-60 font-mono">{list.length}</span>
                              </summary>
                              <div className="px-2 pb-2 space-y-1">
                                {list.map((ex) => (
                                  <button
                                    key={ex.id}
                                    type="button"
                                    onClick={() => addExerciseToBlock(bIdx, ex.id)}
                                    className="w-full elevated-pill px-3 py-2 rounded-lg text-xs font-bold hover:opacity-85 flex items-center justify-between gap-2 text-left"
                                  >
                                    <span className="truncate">{ex.name}</span>
                                    <span className="flex items-center gap-1 shrink-0 text-[10px] opacity-65">
                                      <span>{ex.accessory}</span>
                                      <Plus size={12} style={{ color: 'var(--accent-ink)' }} />
                                    </span>
                                  </button>
                                ))}
                              </div>
                            </details>
                          ))
                        )}
                      </div>
                    </div>
                  </div>
                ))}
              </div>

              <button
                type="button"
                onClick={addBlock}
                className="w-full elevated-pill py-2.5 rounded-2xl text-xs font-bold flex items-center justify-center gap-1.5"
              >
                <ListPlus size={16} />
                <span>Add Block (Warmup / Superset / Circuit)</span>
              </button>

              <div className="grid grid-cols-2 gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => void saveRoutine(false)}
                  className="elevated-pill py-3.5 rounded-2xl font-extrabold text-sm"
                >
                  Save Routine
                </button>
                <button
                  type="button"
                  onClick={() => void saveRoutine(true)}
                  className="py-3.5 rounded-2xl font-extrabold text-sm text-slate-950 flex items-center justify-center gap-1.5"
                  style={{ backgroundColor: 'var(--accent-primary)' }}
                >
                  <Send size={15} />
                  <span>Save & Send to Tonal</span>
                </button>
              </div>
            </section>
          </div>
        )}

        {tab === 'history' && (
          <div className="space-y-4">
            <ProgressionAnalytics
              sessions={sessions}
              personalRecords={personalRecords}
              unit={activeUnit}
            />

            <section className="surface-card rounded-3xl p-5 space-y-3">
              <div className="flex items-center gap-2">
                <Trophy size={20} style={{ color: 'var(--accent-ink)' }} />
                <h2 className="text-lg font-black">
                  {activeUser?.name} Personal Records ({activeUnit})
                </h2>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                {personalRecords.map((pr) => (
                  <div
                    key={pr.id}
                    className="elevated-pill rounded-2xl p-3.5 flex items-center justify-between"
                  >
                    <div>
                      <p className="font-extrabold text-sm">
                        {pr.exercise?.name ?? 'Exercise'}
                      </p>
                      <p className="text-xs opacity-65">{pr.exercise?.muscleGroup}</p>
                    </div>
                    <div className="text-right font-mono tabular-nums">
                      <p
                        className="text-base font-black"
                        style={{ color: 'var(--accent-ink)' }}
                      >
                        {formatDisplayWeight(pr.oneRepMaxEst, activeUnit)} {activeUnit} 1RM
                      </p>
                      <p className="text-xs opacity-65">
                        Max {formatDisplayWeight(pr.maxWeightLb, activeUnit)} {activeUnit}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            </section>

            <section className="surface-card rounded-3xl p-5 space-y-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <History size={20} style={{ color: 'var(--accent-ink)' }} />
                  <h2 className="text-lg font-black">Recent Sessions</h2>
                </div>
                <div className="flex items-center gap-2">
                  <a
                    href={`/api/export/sessions.csv?userId=${encodeURIComponent(activeUser?.id ?? '')}`}
                    className="elevated-pill px-3 py-1.5 rounded-xl text-xs font-bold"
                  >
                    Export CSV
                  </a>
                  <a
                    href={`/api/export/sessions.json?userId=${encodeURIComponent(activeUser?.id ?? '')}`}
                    className="elevated-pill px-3 py-1.5 rounded-xl text-xs font-bold"
                  >
                    Export JSON
                  </a>
                  <a
                    href="/api/admin/backup/db"
                    className="elevated-pill px-3 py-1.5 rounded-xl text-xs font-bold"
                  >
                    Backup DB
                  </a>
                </div>
              </div>
              <div className="space-y-2.5">
                {sessions.map((s) => (
                  <div key={s.id} className="elevated-pill rounded-2xl p-4 space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="font-extrabold text-sm">
                        {s.template?.name ?? 'Free Lift Session'}
                      </span>
                      <span
                        className="font-mono tabular-nums text-sm font-black"
                        style={{ color: 'var(--accent-ink)' }}
                      >
                        {formatDisplayWeight(s.totalVolume, activeUnit, true)} {activeUnit} vol
                      </span>
                    </div>
                    <p className="text-xs opacity-65">
                      {new Date(s.startedAt).toLocaleString()} • {s.setLogs?.length ?? 0} sets logged
                    </p>
                  </div>
                ))}
              </div>
            </section>
          </div>
        )}

        {tab === 'friends' && (
          <div className="space-y-4">
            {/* My Swocial Code & QR Card */}
            <div className="surface-card rounded-3xl p-5 space-y-3.5 border border-white/10">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <Users size={18} style={{ color: 'var(--accent-primary)' }} />
                  <span className="font-black text-sm uppercase tracking-wide">My Swocial Code</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <button
                    type="button"
                    onClick={() => setShowFriendQr(!showFriendQr)}
                    className="px-2.5 py-1.5 rounded-xl text-xs font-bold elevated-pill flex items-center gap-1 hover:opacity-90"
                  >
                    <QrCode size={13} />
                    <span>{showFriendQr ? 'Hide QR' : 'Show QR'}</span>
                  </button>
                  <button
                    type="button"
                    disabled={!nostrIdentity?.npub}
                    onClick={() => {
                      if (nostrIdentity?.npub) {
                        void navigator.clipboard.writeText(nostrIdentity.npub);
                        setCopiedFriendNpub(true);
                        setTimeout(() => setCopiedFriendNpub(false), 2000);
                      }
                    }}
                    className="px-3 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1 hover:opacity-90 transition"
                    style={{
                      backgroundColor: 'var(--accent-primary)',
                      color: accentTextColor,
                    }}
                  >
                    {copiedFriendNpub ? <Check size={13} /> : <Copy size={13} />}
                    <span>{copiedFriendNpub ? 'Copied!' : 'Copy Code'}</span>
                  </button>
                </div>
              </div>

              {nostrIdentity ? (
                <div className="space-y-3">
                  <div className="bg-black/30 p-2.5 rounded-xl border border-white/5 font-mono text-xs break-all opacity-85 select-all">
                    {nostrIdentity.npub}
                  </div>
                  {showFriendQr && companionFriendQrUrl && (
                    <div className="flex flex-col items-center justify-center p-4 bg-white rounded-2xl">
                      <img src={companionFriendQrUrl} alt="My Swocial QR Code" className="w-52 h-52" />
                      <p className="text-slate-900 text-[11px] font-bold mt-2">Scan to connect on Swocial</p>
                    </div>
                  )}
                  <div className="flex items-center justify-between text-[11px] opacity-60">
                    <span>Relays: {nostrIdentity.relays?.length ?? 4} connected</span>
                    <button
                      type="button"
                      onClick={() => setShowAddFriendForm(!showAddFriendForm)}
                      className="font-bold underline hover:opacity-100"
                    >
                      {showAddFriendForm ? 'Hide Add Friend' : '+ Add Friend via Code'}
                    </button>
                  </div>
                </div>
              ) : (
                <div className="text-xs opacity-50 py-2">Loading identity...</div>
              )}

              {/* Inline Add Friend Form */}
              {showAddFriendForm && (
                <div className="pt-2 border-t border-white/5 space-y-2.5">
                  <input
                    type="text"
                    placeholder="Friend's Swocial code or hex"
                    value={newFriendCode}
                    onChange={(e) => setNewFriendCode(e.target.value)}
                    className="w-full elevated-pill px-3 py-2 rounded-xl font-mono text-xs focus:outline-none"
                  />
                  <div className="flex gap-2">
                    <input
                      type="text"
                      placeholder="Nickname (optional)"
                      value={newFriendNick}
                      onChange={(e) => setNewFriendNick(e.target.value)}
                      className="flex-1 elevated-pill px-3 py-2 rounded-xl text-xs focus:outline-none"
                    />
                    <button
                      type="button"
                      disabled={!newFriendCode.trim() || addingFriend}
                      onClick={() => void handleCompanionAddFriend()}
                      className="px-4 py-2 rounded-xl text-xs font-bold hover:opacity-90 disabled:opacity-40"
                      style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                    >
                      {addingFriend ? 'Adding...' : 'Add'}
                    </button>
                  </div>
                  {addFriendStatus && (
                    <p
                      className={`text-xs font-bold ${
                        addFriendStatus.ok ? 'text-emerald-400' : 'text-rose-400'
                      }`}
                    >
                      {addFriendStatus.text}
                    </p>
                  )}
                </div>
              )}
            </div>

            {/* Header & Sync */}
            <div className="surface-card rounded-3xl p-5 flex items-center justify-between gap-3">
              <div>
                <h2 className="text-lg font-black flex items-center gap-2">
                  <Users size={20} style={{ color: 'var(--accent-primary)' }} />
                  <span>Swocial</span>
                </h2>
                <p className="text-xs opacity-70">
                  Live workout activity and high-fives from your gym buddies.
                </p>
              </div>
              <button
                type="button"
                disabled={friendsFeedSyncing}
                onClick={() => {
                  if (activeUser?.id) void syncFriendsFeed(activeUser.id);
                }}
                className="px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5 elevated-pill hover:opacity-90 disabled:opacity-50"
              >
                <RefreshCw size={14} className={friendsFeedSyncing ? 'animate-spin' : ''} />
                <span>{friendsFeedSyncing ? 'Syncing...' : 'Sync'}</span>
              </button>
            </div>

            {/* Feed Items */}
            {friendsFeedLoading ? (
              <div className="surface-card rounded-3xl p-8 text-center text-xs opacity-60">
                Fetching peer workout feed...
              </div>
            ) : friendsFeed.length === 0 ? (
              <div className="surface-card rounded-3xl p-8 text-center space-y-3">
                <Users size={32} className="mx-auto opacity-40" />
                <p className="text-sm font-extrabold">No friend activity yet</p>
                <p className="text-xs opacity-70 max-w-xs mx-auto">
                  Add friends with their Nostr npub key in Settings to view their workouts, celebrate PRs, and share routine templates.
                </p>
                <div className="pt-2">
                  <a
                    href="/settings?tab=friends"
                    className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-bold"
                    style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                  >
                    <Plus size={14} />
                    <span>Manage Friends in Settings</span>
                  </a>
                </div>
              </div>
            ) : (
              <div className="space-y-3">
                {friendsFeed.map((post) => {
                  const isWorkout = post.kind === 30101;
                  const isRoutine = post.kind === 30102;
                  const p = post.payload;
                  const dateStr = new Date(post.createdAt).toLocaleTimeString([], {
                    hour: 'numeric',
                    minute: '2-digit',
                    month: 'short',
                    day: 'numeric',
                  });

                  // Reaction counts
                  const flexCount = post.reactions.filter((r) => r.emoji === '💪').length;
                  const fireCount = post.reactions.filter((r) => r.emoji === '🔥').length;
                  const trophyCount = post.reactions.filter((r) => r.emoji === '🏆').length;

                  return (
                    <article
                      key={post.id}
                      className="surface-card rounded-3xl p-5 space-y-3.5 border border-white/5"
                    >
                      {/* Author Header */}
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-2.5 min-w-0">
                          <div
                            className="w-8 h-8 rounded-xl flex items-center justify-center font-black text-xs shrink-0"
                            style={{
                              backgroundColor: 'var(--accent-primary)',
                              color: accentTextColor,
                            }}
                          >
                            {post.authorName.slice(0, 2).toUpperCase()}
                          </div>
                          <div className="min-w-0">
                            <p className="font-extrabold text-sm truncate">{post.authorName}</p>
                            <p className="text-[11px] opacity-60 font-mono">{dateStr}</p>
                          </div>
                        </div>

                        <span className="px-2.5 py-0.5 rounded-full text-[10px] font-bold uppercase elevated-pill opacity-75">
                          {isWorkout ? 'Workout Completed' : isRoutine ? 'Routine Shared' : 'Activity'}
                        </span>
                      </div>

                      {/* Content Body: Workout */}
                      {isWorkout && (
                        <div className="space-y-3">
                          <div className="flex items-baseline justify-between gap-2">
                            <h3 className="font-black text-base">{p.routineName || 'Free Lift'}</h3>
                            <span
                              className="font-mono tabular-nums text-base font-black shrink-0"
                              style={{ color: 'var(--accent-ink)' }}
                            >
                              {formatDisplayWeight(p.totalVolumeLb || 0, activeUnit, true)} {activeUnit}
                            </span>
                          </div>

                          <div className="grid grid-cols-3 gap-2 text-center text-xs">
                            <div className="elevated-pill rounded-xl p-2">
                              <p className="opacity-60 text-[10px] uppercase font-bold">Sets</p>
                              <p className="font-mono font-black text-sm">{p.setsCount || 0}</p>
                            </div>
                            <div className="elevated-pill rounded-xl p-2">
                              <p className="opacity-60 text-[10px] uppercase font-bold">Reps</p>
                              <p className="font-mono font-black text-sm">{p.repsCount || 0}</p>
                            </div>
                            <div className="elevated-pill rounded-xl p-2">
                              <p className="opacity-60 text-[10px] uppercase font-bold">Duration</p>
                              <p className="font-mono font-black text-sm">
                                {p.durationSec ? `${Math.round(p.durationSec / 60)}m` : '—'}
                              </p>
                            </div>
                          </div>

                          {Array.isArray(p.prs) && p.prs.length > 0 && (
                            <div className="flex flex-wrap items-center gap-1.5 pt-0.5">
                              {p.prs.map((pr: string) => (
                                <span
                                  key={pr}
                                  className="px-2.5 py-1 rounded-xl text-xs font-bold bg-amber-500/15 text-amber-300 border border-amber-500/35 flex items-center gap-1"
                                >
                                  <Trophy size={12} />
                                  <span>PR: {pr}</span>
                                </span>
                              ))}
                            </div>
                          )}
                        </div>
                      )}

                      {/* Content Body: Routine Template */}
                      {isRoutine && (
                        <div className="space-y-3">
                          <div>
                            <h3 className="font-black text-base">{p.name || 'Custom Routine'}</h3>
                            {p.description && (
                              <p className="text-xs opacity-75 mt-1">{p.description}</p>
                            )}
                          </div>
                          <div className="flex items-center justify-between gap-2 pt-1">
                            <span className="text-xs font-mono opacity-65">
                              {Array.isArray(p.blocks) ? `${p.blocks.length} blocks` : 'Structured Routine'}
                            </span>
                            <button
                              type="button"
                              disabled={importingRoutineId === post.id}
                              onClick={() => void handleImportSharedRoutine(post.id)}
                              className="px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:opacity-90 disabled:opacity-50"
                              style={{
                                backgroundColor: 'var(--accent-primary)',
                                color: accentTextColor,
                              }}
                            >
                              {importingRoutineId === post.id ? (
                                <>
                                  <RefreshCw size={13} className="animate-spin" />
                                  <span>Importing...</span>
                                </>
                              ) : (
                                <>
                                  <ListPlus size={14} />
                                  <span>Import Routine</span>
                                </>
                              )}
                            </button>
                          </div>
                        </div>
                      )}

                      {/* Social Reaction Bar */}
                      <div className="flex items-center gap-2 pt-2 border-t border-white/5">
                        <button
                          type="button"
                          onClick={() => void handleReactToPost(post.id, post.authorPubkey, '💪')}
                          className="elevated-pill px-3 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:bg-white/10 active:scale-95 transition"
                        >
                          <span>💪</span>
                          {flexCount > 0 && <span className="font-mono">{flexCount}</span>}
                        </button>
                        <button
                          type="button"
                          onClick={() => void handleReactToPost(post.id, post.authorPubkey, '🔥')}
                          className="elevated-pill px-3 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:bg-white/10 active:scale-95 transition"
                        >
                          <span>🔥</span>
                          {fireCount > 0 && <span className="font-mono">{fireCount}</span>}
                        </button>
                        <button
                          type="button"
                          onClick={() => void handleReactToPost(post.id, post.authorPubkey, '🏆')}
                          className="elevated-pill px-3 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:bg-white/10 active:scale-95 transition"
                        >
                          <span>🏆</span>
                          {trophyCount > 0 && <span className="font-mono">{trophyCount}</span>}
                        </button>
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </main>

      {/* Bottom Mobile Navigation Bar */}
      <nav
        className="fixed bottom-0 inset-x-0 z-20 border-t border-white/10 backdrop-blur-xl pb-[env(safe-area-inset-bottom)]"
        style={{ background: 'var(--bg-secondary-bar)' }}
      >
        <div className="max-w-lg mx-auto grid grid-cols-4 h-[4.25rem]">
          <button
            type="button"
            onClick={() => setTab('remote')}
            className="flex flex-col items-center justify-center gap-1 text-xs font-bold"
            style={{ color: tab === 'remote' ? 'var(--accent-primary)' : 'var(--text-muted)' }}
          >
            <Radio size={20} />
            <span>Remote</span>
          </button>
          <button
            type="button"
            onClick={() => setTab('history')}
            className="flex flex-col items-center justify-center gap-1 text-xs font-bold"
            style={{ color: tab === 'history' ? 'var(--accent-primary)' : 'var(--text-muted)' }}
          >
            <Sliders size={20} />
            <span>History</span>
          </button>
          <button
            type="button"
            onClick={() => setTab('friends')}
            className="flex flex-col items-center justify-center gap-1 text-xs font-bold"
            style={{ color: tab === 'friends' ? 'var(--accent-primary)' : 'var(--text-muted)' }}
          >
            <Users size={20} />
            <span>Swocial</span>
          </button>
          <button
            type="button"
            onClick={() => setTab('builder')}
            className="flex flex-col items-center justify-center gap-1 text-xs font-bold"
            style={{ color: tab === 'builder' ? 'var(--accent-primary)' : 'var(--text-muted)' }}
          >
            <Dumbbell size={20} />
            <span>Builder</span>
          </button>
        </div>
      </nav>

      <RepCountDialog
        request={repConfirm}
        accentTextColor={accentTextColor}
        onConfirm={(reps) => {
          void confirmRepCount(reps).then((result) => {
            if (result === 'logged') showToast('Logged set!');
          });
        }}
        onCancel={cancelRepConfirm}
      />

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

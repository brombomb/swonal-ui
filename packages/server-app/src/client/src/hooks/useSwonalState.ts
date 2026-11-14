import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AccessoryType,
  DynamicMode,
  Exercise,
  MusicDevice,
  MusicPlaybackState,
  MusicPlaylist,
  MusicProviderMeta,
  OverloadSuggestion,
  PersonalRecord,
  RoutineStep,
  SafetyEvent,
  SharedSessionState,
  SymmetryMetrics,
  TelemetryFrame,
  UserProfile,
  VbtMetrics,
  WorkoutBlock,
  WorkoutSession,
  WorkoutTemplate,
} from '../types';
import { soundEngine } from '../utils/soundEngine';
import { hexToRgba, WeightUnit } from '../utils/units';

const DEFAULT_TELEMETRY: TelemetryFrame = {
  type: 'telemetry',
  mode: 'CUSTOM',
  weight_enabled: false,
  target_weight_lb: 45,
  applied_resistance_lb: 0,
  profile_mode: 'standard',
  modifier_pct: 0,
  rep_count: 0,
  accessory: 'HANDLES',
  left_cable: { position: 0, velocity: 0, load_lb: 0 },
  right_cable: { position: 0, velocity: 0, load_lb: 0 },
  arm_positions: {
    height: { left: 4, right: 4 },
    rotation: { left: 2, right: 2, left_unlocked: false, right_unlocked: false },
    angle: { left: 1, right: 1 },
  },
};

export function flattenTemplateBlocks(blocks: WorkoutBlock[]): RoutineStep[] {
  const steps: RoutineStep[] = [];
  let stepIndex = 0;

  blocks.forEach((block, blockIndex) => {
    if (!block.items || block.items.length === 0) return;

    if (block.blockType === 'SUPERSET' || block.blockType === 'CIRCUIT') {
      const maxRounds = Math.max(...block.items.map((item) => item.sets));
      for (let round = 1; round <= maxRounds; round += 1) {
        block.items.forEach((item) => {
          if (round <= item.sets) {
            steps.push({
              stepIndex: stepIndex++,
              blockIndex,
              blockType: block.blockType,
              restSeconds: block.restSeconds,
              setNumber: round,
              totalSetsInItem: item.sets,
              targetReps: item.targetReps,
              targetWeightLb: item.targetWeightLb,
              mode: item.mode,
              modifierPct: item.modifierPct,
              exerciseId: item.exerciseId,
            });
          }
        });
      }
    } else {
      block.items.forEach((item) => {
        for (let setNum = 1; setNum <= item.sets; setNum += 1) {
          steps.push({
            stepIndex: stepIndex++,
            blockIndex,
            blockType: block.blockType,
            restSeconds: block.restSeconds,
            setNumber: setNum,
            totalSetsInItem: item.sets,
            targetReps: item.targetReps,
            targetWeightLb: item.targetWeightLb,
            mode: item.mode,
            modifierPct: item.modifierPct,
            exerciseId: item.exerciseId,
          });
        }
      });
    }
  });

  return steps;
}

export function useSwonalState() {
  const [users, setUsers] = useState<UserProfile[]>([]);
  const [exercises, setExercises] = useState<Exercise[]>([]);
  const [templates, setTemplates] = useState<WorkoutTemplate[]>([]);
  const [personalRecords, setPersonalRecords] = useState<PersonalRecord[]>([]);
  const [sessions, setSessions] = useState<WorkoutSession[]>([]);

  const [sessionState, setSessionState] = useState<SharedSessionState>({
    activeUserId: '',
    partnerModeEnabled: false,
    partnerUserIds: [],
    activePartnerIndex: 0,
    activeSessionId: null,
    activeExerciseId: null,
    queuedWorkout: null,
    restTimerEndsAt: null,
  });

  const [telemetry, setTelemetry] = useState<TelemetryFrame>(DEFAULT_TELEMETRY);
  const [daemonConnected, setDaemonConnected] = useState(false);
  const [wsConnected, setWsConnected] = useState(false);
  const [safetyEvent, setSafetyEvent] = useState<SafetyEvent | null>(null);
  const [lastPrBanner, setLastPrBanner] = useState<string | null>(null);
  const [soundMuted, setSoundMuted] = useState(false);
  const [overloadSuggestion, setOverloadSuggestion] = useState<OverloadSuggestion | null>(null);
  const [vbtMetrics, setVbtMetrics] = useState<VbtMetrics>({
    currentVelocity: 0,
    peakSetVelocity: 0,
    lastRepPeakVelocity: 0,
    velocityDropPct: 0,
    fatigueAlert: false,
  });
  const currentRepPeakVelRef = useRef<number>(0);
  const firstRepPeakVelRef = useRef<number>(0);
  const lastRepPeakVelRef = useRef<number>(0);

  // Active routine & exercise state
  const [selectedExerciseId, setSelectedExerciseId] = useState<string>('');
  const [activeTemplateName, setActiveTemplateName] = useState<string | null>(null);
  const [routineSteps, setRoutineSteps] = useState<RoutineStep[]>([]);
  const [currentStepIdx, setCurrentStepIdx] = useState<number>(0);
  const [restSecondsRemaining, setRestSecondsRemaining] = useState<number>(0);

  // Music state
  const [musicProviders, setMusicProviders] = useState<MusicProviderMeta[]>([]);
  const [musicState, setMusicState] = useState<MusicPlaybackState>({
    isPlaying: false,
    trackName: 'Heavy Workout Mix',
    artistName: 'Librespot',
    albumName: 'Tonal Gym Speaker',
    progressMs: 0,
    durationMs: 200000,
    volumePct: 72,
  });
  const [playlists, setPlaylists] = useState<MusicPlaylist[]>([]);
  const [devices, setDevices] = useState<MusicDevice[]>([]);

  const wsRef = useRef<WebSocket | null>(null);
  const prevRepCountRef = useRef<number>(0);
  const prevWeightEnabledRef = useRef<boolean>(false);
  const prevCountdownTickRef = useRef<number>(0);
  const autoLogCallbackRef = useRef<(reps: number) => void>(() => {});

  const activeUser = useMemo(
    () => users.find((u) => u.id === sessionState.activeUserId) ?? users[0] ?? null,
    [users, sessionState.activeUserId]
  );

  const activeUnit: WeightUnit = activeUser?.weightUnit === 'KG' ? 'KG' : 'LB';
  const activeProviderId = activeUser?.preferredMusicProvider || 'spotify';

  const currentStep: RoutineStep | null = useMemo(() => {
    if (routineSteps.length === 0) return null;
    return routineSteps[Math.min(currentStepIdx, routineSteps.length - 1)] ?? null;
  }, [routineSteps, currentStepIdx]);

  const activeExercise = useMemo(() => {
    const targetId = currentStep?.exerciseId || selectedExerciseId;
    return exercises.find((e) => e.id === targetId) ?? exercises[0] ?? null;
  }, [exercises, currentStep, selectedExerciseId]);

  const activeExercisePr = useMemo(() => {
    if (!activeExercise) return null;
    return personalRecords.find((pr) => pr.exerciseId === activeExercise.id) ?? null;
  }, [personalRecords, activeExercise]);

  const symmetryMetrics: SymmetryMetrics = useMemo(() => {
    const leftPos = telemetry.left_cable.position ?? 0;
    const rightPos = telemetry.right_cable.position ?? 0;
    const positionDelta = Math.abs(leftPos - rightPos);
    const signedBalance = rightPos - leftPos;
    const leftLoad = telemetry.left_cable.load_lb ?? 0;
    const rightLoad = telemetry.right_cable.load_lb ?? 0;
    const totalLoad = leftLoad + rightLoad;
    const leftLoadPct = totalLoad > 0 ? Math.round((leftLoad / totalLoad) * 100) : 50;
    const rightLoadPct = totalLoad > 0 ? 100 - leftLoadPct : 50;
    const status =
      positionDelta > 28 ? 'critical' : positionDelta > 12 ? 'drift' : 'balanced';
    return { positionDelta, signedBalance, leftLoadPct, rightLoadPct, status };
  }, [telemetry.left_cable, telemetry.right_cable]);

  useEffect(() => {
    if (!activeUser?.id || !activeExercise?.id) {
      setOverloadSuggestion(null);
      return;
    }
    let cancelled = false;
    void fetch(`/api/overload/${activeUser.id}/${activeExercise.id}`)
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => {
        if (!cancelled && data) setOverloadSuggestion(data as OverloadSuggestion);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [activeUser?.id, activeExercise?.id, personalRecords.length, sessions.length]);

  useEffect(() => {
    const avgVel =
      (Math.abs(telemetry.left_cable.velocity ?? 0) +
        Math.abs(telemetry.right_cable.velocity ?? 0)) /
      2;

    if (!telemetry.weight_enabled || telemetry.rep_count === 0) {
      if (!telemetry.weight_enabled) {
        currentRepPeakVelRef.current = 0;
        firstRepPeakVelRef.current = 0;
        lastRepPeakVelRef.current = 0;
      }
      if (avgVel > currentRepPeakVelRef.current) {
        currentRepPeakVelRef.current = avgVel;
      }
      setVbtMetrics((prev) => ({
        ...prev,
        currentVelocity: avgVel,
        peakSetVelocity: Math.max(telemetry.weight_enabled ? prev.peakSetVelocity : 0, avgVel),
        velocityDropPct: telemetry.weight_enabled ? prev.velocityDropPct : 0,
        fatigueAlert: telemetry.weight_enabled ? prev.fatigueAlert : false,
      }));
      return;
    }

    if (avgVel > currentRepPeakVelRef.current) {
      currentRepPeakVelRef.current = avgVel;
    }
    setVbtMetrics((prev) => ({
      ...prev,
      currentVelocity: avgVel,
      peakSetVelocity: Math.max(prev.peakSetVelocity, avgVel),
    }));
  }, [telemetry.weight_enabled, telemetry.rep_count, telemetry.left_cable.velocity, telemetry.right_cable.velocity]);

  // Apply adaptive theme + per-user dynamic accent CSS variables
  useEffect(() => {
    if (!activeUser) return;
    const root = document.documentElement;
    const resolvedTheme =
      activeUser.themeMode === 'system'
        ? window.matchMedia('(prefers-color-scheme: light)').matches
          ? 'light'
          : 'dark'
        : activeUser.themeMode;

    root.setAttribute('data-theme', resolvedTheme);
    root.style.setProperty('--accent-primary', activeUser.accentColor);
    root.style.setProperty('--accent-glow', hexToRgba(activeUser.accentColor, 0.35));
    root.style.setProperty('--accent-muted', hexToRgba(activeUser.accentColor, 0.15));
  }, [activeUser]);

  const sendWs = useCallback((payload: Record<string, unknown>) => {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify(payload));
    }
  }, []);

  const refreshBootstrap = useCallback(async () => {
    const resp = await fetch('/api/state');
    if (!resp.ok) return;
    const data = (await resp.json()) as {
      users: UserProfile[];
      exercises: Exercise[];
      templates: WorkoutTemplate[];
      sessionState: SharedSessionState;
      daemonConnected: boolean;
    };
    setUsers(data.users);
    setExercises(data.exercises);
    setTemplates(data.templates);
    setSessionState(data.sessionState);
    setDaemonConnected(data.daemonConnected);

    if (!selectedExerciseId && data.exercises.length > 0) {
      const bench = data.exercises.find((e) => e.name === 'Bench Press') ?? data.exercises[0];
      setSelectedExerciseId(bench.id);
    }
  }, [selectedExerciseId]);

  const refreshUserHistory = useCallback(async (userId: string) => {
    if (!userId) return;
    const [prsResp, sessResp] = await Promise.all([
      fetch(`/api/prs/${encodeURIComponent(userId)}`),
      fetch(`/api/sessions?userId=${encodeURIComponent(userId)}`),
    ]);
    if (prsResp.ok) setPersonalRecords(await prsResp.json());
    if (sessResp.ok) setSessions(await sessResp.json());
  }, []);

  const refreshMusic = useCallback(async (userId: string, providerId: string) => {
    if (!userId) return;
    const qs = `userId=${encodeURIComponent(userId)}`;
    const [provResp, stateResp, plResp, devResp] = await Promise.all([
      fetch('/api/music/providers'),
      fetch(`/api/music/${encodeURIComponent(providerId)}/state?${qs}`),
      fetch(`/api/music/${encodeURIComponent(providerId)}/playlists?${qs}`),
      fetch(`/api/music/${encodeURIComponent(providerId)}/devices?${qs}`),
    ]);
    if (provResp.ok) setMusicProviders(await provResp.json());
    if (stateResp.ok) setMusicState(await stateResp.json());
    if (plResp.ok) setPlaylists(await plResp.json());
    if (devResp.ok) setDevices(await devResp.json());
  }, []);

  useEffect(() => {
    void refreshBootstrap();
  }, [refreshBootstrap]);

  useEffect(() => {
    if (activeUser?.id) {
      void refreshUserHistory(activeUser.id);
      void refreshMusic(activeUser.id, activeProviderId);
    }
  }, [activeUser?.id, activeProviderId, refreshUserHistory, refreshMusic]);

  // Live Rest Timer ticker + 3-2-1 audio cue
  useEffect(() => {
    const updateTimer = () => {
      if (!sessionState.restTimerEndsAt) {
        setRestSecondsRemaining(0);
        prevCountdownTickRef.current = 0;
        return;
      }
      const rem = Math.max(0, Math.ceil((sessionState.restTimerEndsAt - Date.now()) / 1000));
      setRestSecondsRemaining(rem);
      if (rem > 0 && rem <= 3 && rem !== prevCountdownTickRef.current) {
        prevCountdownTickRef.current = rem;
        soundEngine.playCountdownTick(false);
      } else if (rem === 0 && prevCountdownTickRef.current > 0) {
        prevCountdownTickRef.current = 0;
        soundEngine.playCountdownTick(true);
        sendWs({ type: 'skip_rest_timer' });
      }
    };
    updateTimer();
    const timer = setInterval(updateTimer, 250);
    return () => clearInterval(timer);
  }, [sessionState.restTimerEndsAt, sendWs]);

  // Periodic music progress refresh when playing
  useEffect(() => {
    if (!musicState.isPlaying || !activeUser?.id) return;
    const iv = setInterval(() => {
      void refreshMusic(activeUser.id, activeProviderId);
    }, 2000);
    return () => clearInterval(iv);
  }, [musicState.isPlaying, activeUser?.id, activeProviderId, refreshMusic]);

  // WebSocket hub connection
  useEffect(() => {
    let closed = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let pingTimer: ReturnType<typeof setInterval> | null = null;

    const connect = () => {
      if (closed) return;
      const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const ws = new WebSocket(`${proto}//${window.location.host}/ws`);
      wsRef.current = ws;

      ws.onopen = () => {
        setWsConnected(true);
        ws.send(JSON.stringify({ type: 'ping' }));
        pingTimer = setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'ping' }));
          }
        }, 2000);
      };

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'telemetry') {
            const frame = msg as TelemetryFrame;
            if (frame.weight_enabled && !prevWeightEnabledRef.current) {
              soundEngine.playWeightEngage();
            } else if (!frame.weight_enabled && prevWeightEnabledRef.current) {
              soundEngine.playWeightDisengage();
              if (frame.rep_count > 0) {
                autoLogCallbackRef.current(frame.rep_count);
              }
            }
            if (frame.rep_count > prevRepCountRef.current) {
              soundEngine.playRepComplete();
              const repPeak = Math.max(
                currentRepPeakVelRef.current,
                (Math.abs(frame.left_cable.velocity ?? 0) +
                  Math.abs(frame.right_cable.velocity ?? 0)) /
                  2
              );
              if (firstRepPeakVelRef.current === 0 && repPeak > 0.1) {
                firstRepPeakVelRef.current = repPeak;
              }
              lastRepPeakVelRef.current = repPeak;
              currentRepPeakVelRef.current = 0;
              const dropPct =
                firstRepPeakVelRef.current > 0
                  ? Math.max(
                      0,
                      ((firstRepPeakVelRef.current - repPeak) / firstRepPeakVelRef.current) * 100
                    )
                  : 0;
              setVbtMetrics((prev) => ({
                ...prev,
                lastRepPeakVelocity: repPeak,
                velocityDropPct: dropPct,
                fatigueAlert: dropPct >= 25 && frame.rep_count >= 2,
              }));
            }
            prevWeightEnabledRef.current = frame.weight_enabled;
            prevRepCountRef.current = frame.rep_count;
            setTelemetry(frame);
          } else if (msg.type === 'daemon_status') {
            setDaemonConnected(Boolean(msg.connected));
          } else if (msg.type === 'session_state') {
            setSessionState(msg.state);
          } else if (msg.type === 'workout_queued') {
            setSessionState((prev) => ({ ...prev, queuedWorkout: msg.workout }));
          } else if (msg.type === 'safety_event') {
            setSafetyEvent({
              reason: String(msg.reason || 'SAFETY_CUTOUT'),
              delta: typeof msg.delta === 'number' ? msg.delta : undefined,
              timestamp: Date.now(),
            });
            soundEngine.playSafetyCutout(activeProviderId, activeUser?.id);
          } else if (
            msg.type === 'users_updated' ||
            msg.type === 'user_updated' ||
            msg.type === 'templates_updated' ||
            msg.type === 'exercises_updated'
          ) {
            void refreshBootstrap();
          } else if (msg.type === 'music_updated') {
            if (activeUser?.id) {
              void refreshMusic(activeUser.id, activeProviderId);
            }
          } else if (msg.type === 'set_logged' || msg.type === 'partner_rotated') {
            if (activeUser?.id) {
              void refreshUserHistory(activeUser.id);
            }
          }
        } catch {
          // Ignore malformed WS message
        }
      };

      ws.onclose = () => {
        setWsConnected(false);
        if (pingTimer) clearInterval(pingTimer);
        if (!closed) {
          reconnectTimer = setTimeout(connect, 2000);
        }
      };
    };

    connect();
    return () => {
      closed = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (pingTimer) clearInterval(pingTimer);
      wsRef.current?.close();
    };
  }, [activeUser?.id, activeProviderId, refreshBootstrap, refreshMusic, refreshUserHistory]);

  // Motor Actions
  const setWeight = useCallback(
    (weightLb: number, profile: DynamicMode = telemetry.profile_mode, modifierPct = telemetry.modifier_pct) => {
      const clamped = Math.min(200, Math.max(5, Math.round(weightLb * 2) / 2));
      const multiplier = 1 + (profile === 'eccentric' || profile === 'chains' ? modifierPct / 100 : 0);
      const safeTarget =
        clamped * multiplier > 200 ? Math.floor((200 / multiplier) * 2) / 2 : clamped;

      sendWs({
        type: 'set_weight',
        weight_lb: safeTarget,
        profile,
        modifier_pct: profile === 'standard' || profile === 'spotter' || profile === 'burnout' ? 0 : modifierPct,
      });
    },
    [sendWs, telemetry.profile_mode, telemetry.modifier_pct]
  );

  const toggleWeight = useCallback(() => {
    setSafetyEvent(null);
    sendWs({ type: 'toggle_weight' });
  }, [sendWs]);

  const setAccessory = useCallback(
    (accessory: AccessoryType) => {
      sendWs({ type: 'set_accessory', accessory });
    },
    [sendWs]
  );

  const alignMockArmsToExercise = useCallback(
    async (exercise: Exercise) => {
      await fetch('/api/daemon/mock/arm-positions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          accessory: exercise.accessory,
          height_left: exercise.recommendedHeight,
          height_right: exercise.recommendedHeight,
          rot_left: exercise.recommendedRotation,
          rot_right: exercise.recommendedRotation,
          angle_left: exercise.recommendedAngle,
          angle_right: exercise.recommendedAngle,
        }),
      });
    },
    []
  );

  // User, Theme, Accent, Units, & Partner Actions
  const switchUser = useCallback(
    async (userId: string) => {
      sendWs({ type: 'set_active_user', userId });
      await refreshUserHistory(userId);
      const target = users.find((u) => u.id === userId);
      if (target && activeExercise) {
        const prsResp = await fetch(`/api/prs/${encodeURIComponent(userId)}`);
        if (prsResp.ok) {
          const prs = (await prsResp.json()) as PersonalRecord[];
          const matchedPr = prs.find((p) => p.exerciseId === activeExercise.id);
          if (matchedPr) {
            const suggested = Math.max(5, Math.min(200, Math.round((matchedPr.maxWeightLb * 0.85) / 5) * 5));
            setWeight(suggested);
          }
        }
      }
    },
    [sendWs, refreshUserHistory, users, activeExercise, setWeight]
  );

  const updateUserPreferences = useCallback(
    async (userId: string, updates: Partial<Pick<UserProfile, 'themeMode' | 'accentColor' | 'avatarColor' | 'weightUnit' | 'preferredMusicProvider' | 'name'>>) => {
      const resp = await fetch(`/api/users/${encodeURIComponent(userId)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updates),
      });
      if (resp.ok) {
        const updated = (await resp.json()) as UserProfile;
        setUsers((prev) => prev.map((u) => (u.id === updated.id ? updated : u)));
      }
    },
    []
  );

  const createUser = useCallback(
    async (name: string, accentColor: string, weightUnit: WeightUnit = 'LB') => {
      const resp = await fetch('/api/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name,
          accentColor,
          avatarColor: accentColor,
          themeMode: activeUser?.themeMode ?? 'dark',
          weightUnit,
        }),
      });
      if (resp.ok) {
        const created = (await resp.json()) as UserProfile;
        setUsers((prev) => [...prev, created]);
        await switchUser(created.id);
      }
    },
    [activeUser?.themeMode, switchUser]
  );

  const togglePartnerMode = useCallback(
    (enabled: boolean, partnerIds?: string[]) => {
      const ids =
        partnerIds ??
        (users.length >= 2 ? [users[0].id, users[1].id] : users.map((u) => u.id));
      sendWs({ type: 'set_partner_mode', enabled, partnerUserIds: ids });
    },
    [sendWs, users]
  );

  // Workout Routine & Logging
  const selectFreeExercise = useCallback(
    (exercise: Exercise) => {
      setActiveTemplateName(null);
      setRoutineSteps([]);
      setCurrentStepIdx(0);
      setSelectedExerciseId(exercise.id);
      sendWs({ type: 'set_accessory', accessory: exercise.accessory });
      const pr = personalRecords.find((p) => p.exerciseId === exercise.id);
      if (pr) {
        const suggested = Math.max(5, Math.min(200, Math.round((pr.maxWeightLb * 0.85) / 5) * 5));
        setWeight(suggested, 'standard', 0);
      }
    },
    [personalRecords, sendWs, setWeight]
  );

  const startWorkoutTemplate = useCallback(
    async (template: Pick<WorkoutTemplate, 'id' | 'name' | 'blocks'> & { userId?: string }) => {
      const targetUserId = template.userId || activeUser?.id;
      if (!targetUserId) return;

      const steps = flattenTemplateBlocks(template.blocks);
      setActiveTemplateName(template.name);
      setRoutineSteps(steps);
      setCurrentStepIdx(0);
      sendWs({ type: 'clear_queued_workout' });

      const resp = await fetch('/api/sessions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: targetUserId, templateId: template.id }),
      });
      if (resp.ok) {
        const session = (await resp.json()) as WorkoutSession;
        setSessionState((prev) => ({ ...prev, activeSessionId: session.id }));
      }

      if (steps[0]) {
        const firstStep = steps[0];
        setSelectedExerciseId(firstStep.exerciseId);
        const ex = exercises.find((e) => e.id === firstStep.exerciseId);
        if (ex) {
          sendWs({ type: 'set_accessory', accessory: ex.accessory });
        }
        setWeight(firstStep.targetWeightLb, firstStep.mode, firstStep.modifierPct);
        sendWs({ type: 'reset_reps' });
      }
    },
    [activeUser?.id, exercises, sendWs, setWeight]
  );

  const logCompletedSet = useCallback(
    async (repsOverride?: number, rpe?: number | null) => {
      if (!activeUser || !activeExercise) return;
      let sessionId = sessionState.activeSessionId;

      if (!sessionId) {
        const sResp = await fetch('/api/sessions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: activeUser.id }),
        });
        if (sResp.ok) {
          const sess = (await sResp.json()) as WorkoutSession;
          sessionId = sess.id;
          setSessionState((prev) => ({ ...prev, activeSessionId: sess.id }));
        }
      }
      if (!sessionId) return;

      const repsCompleted =
        repsOverride ??
        (telemetry.rep_count > 0 ? telemetry.rep_count : currentStep?.targetReps ?? 10);

      const resp = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/sets`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: activeUser.id,
          exerciseId: activeExercise.id,
          setNumber: currentStep?.setNumber ?? 1,
          mode: telemetry.profile_mode,
          modifierPct: telemetry.modifier_pct,
          weightLb: telemetry.target_weight_lb,
          repsCompleted,
          rpe: rpe ?? null,
          restSeconds: currentStep?.restSeconds ?? 60,
        }),
      });

      if (!resp.ok) return;
      const result = (await resp.json()) as {
        newPr: boolean;
        partnerRotation: { rotatedToUserId: string; targetWeightLb: number } | null;
      };

      if (result.newPr) {
        soundEngine.playPersonalRecord(activeProviderId, activeUser.id);
        setLastPrBanner(`NEW PR! ${activeExercise.name}`);
        setTimeout(() => setLastPrBanner(null), 5000);
      } else {
        soundEngine.playSetComplete(activeProviderId, activeUser.id);
      }

      // Advance routine step if running a structured workout
      if (routineSteps.length > 0) {
        const nextIdx = currentStepIdx + 1;
        if (nextIdx < routineSteps.length) {
          setCurrentStepIdx(nextIdx);
          const nextStep = routineSteps[nextIdx];
          setSelectedExerciseId(nextStep.exerciseId);
          if (!result.partnerRotation) {
            const ex = exercises.find((e) => e.id === nextStep.exerciseId);
            if (ex) sendWs({ type: 'set_accessory', accessory: ex.accessory });
            setWeight(nextStep.targetWeightLb, nextStep.mode, nextStep.modifierPct);
          }
        } else {
          // Workout finished
          await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/complete`, {
            method: 'POST',
          });
          setActiveTemplateName(null);
          setRoutineSteps([]);
          setCurrentStepIdx(0);
        }
      }

      await refreshUserHistory(activeUser.id);
    },
    [
      activeUser,
      activeExercise,
      sessionState.activeSessionId,
      telemetry.rep_count,
      telemetry.profile_mode,
      telemetry.modifier_pct,
      telemetry.target_weight_lb,
      currentStep,
      activeProviderId,
      routineSteps,
      currentStepIdx,
      exercises,
      sendWs,
      setWeight,
      refreshUserHistory,
    ]
  );

  // Music Controls
  const toggleMusicPlay = useCallback(async () => {
    if (!activeUser) return;
    const action = musicState.isPlaying ? 'pause' : 'play';
    const resp = await fetch(
      `/api/music/${encodeURIComponent(activeProviderId)}/${action}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: activeUser.id }),
      }
    );
    if (resp.ok) setMusicState(await resp.json());
  }, [activeUser, activeProviderId, musicState.isPlaying]);

  const nextTrack = useCallback(async () => {
    if (!activeUser) return;
    const resp = await fetch(`/api/music/${encodeURIComponent(activeProviderId)}/next`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: activeUser.id }),
    });
    if (resp.ok) setMusicState(await resp.json());
  }, [activeUser, activeProviderId]);

  const previousTrack = useCallback(async () => {
    if (!activeUser) return;
    const resp = await fetch(
      `/api/music/${encodeURIComponent(activeProviderId)}/previous`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: activeUser.id }),
      }
    );
    if (resp.ok) setMusicState(await resp.json());
  }, [activeUser, activeProviderId]);

  const setMusicVolume = useCallback(
    async (volumePct: number) => {
      if (!activeUser) return;
      const resp = await fetch(
        `/api/music/${encodeURIComponent(activeProviderId)}/volume`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: activeUser.id, volumePct }),
        }
      );
      if (resp.ok) setMusicState(await resp.json());
    },
    [activeUser, activeProviderId]
  );

  const playPlaylist = useCallback(
    async (contextUri: string) => {
      if (!activeUser) return;
      const resp = await fetch(
        `/api/music/${encodeURIComponent(activeProviderId)}/play`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: activeUser.id, contextUri }),
        }
      );
      if (resp.ok) setMusicState(await resp.json());
    },
    [activeUser, activeProviderId]
  );

  const transferMusicDevice = useCallback(
    async (deviceId: string) => {
      if (!activeUser) return;
      const resp = await fetch(
        `/api/music/${encodeURIComponent(activeProviderId)}/transfer`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ userId: activeUser.id, deviceId }),
        }
      );
      if (resp.ok) {
        setMusicState(await resp.json());
        await refreshMusic(activeUser.id, activeProviderId);
      }
    },
    [activeUser, activeProviderId, refreshMusic]
  );

  const toggleSoundMuted = useCallback(() => {
    const next = !soundMuted;
    soundEngine.setMuted(next);
    setSoundMuted(next);
  }, [soundMuted]);

  const switchMusicProvider = useCallback(
    async (providerId: string) => {
      if (!activeUser) return;
      await fetch(`/api/users/${activeUser.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ preferredMusicProvider: providerId }),
      });
      setUsers((prev) =>
        prev.map((u) => (u.id === activeUser.id ? { ...u, preferredMusicProvider: providerId } : u))
      );
      await refreshMusic(activeUser.id, providerId);
    },
    [activeUser, refreshMusic]
  );

  useEffect(() => {
    autoLogCallbackRef.current = (reps: number) => {
      if (activeTemplateName && currentStep && reps >= currentStep.targetReps) {
        void logCompletedSet(reps);
      }
    };
  }, [activeTemplateName, currentStep, logCompletedSet]);

  return {
    users,
    activeUser,
    activeUnit,
    activeProviderId,
    exercises,
    templates,
    personalRecords,
    sessions,
    sessionState,
    telemetry,
    daemonConnected,
    wsConnected,
    safetyEvent,
    clearSafetyEvent: () => setSafetyEvent(null),
    lastPrBanner,
    soundMuted,
    toggleSoundMuted,
    activeExercise,
    activeExercisePr,
    overloadSuggestion,
    vbtMetrics,
    symmetryMetrics,
    activeTemplateName,
    routineSteps,
    currentStep,
    currentStepIdx,
    restSecondsRemaining,
    musicProviders,
    musicState,
    playlists,
    devices,
    sendWs,
    setWeight,
    toggleWeight,
    setAccessory,
    alignMockArmsToExercise,
    switchUser,
    updateUserPreferences,
    createUser,
    togglePartnerMode,
    selectFreeExercise,
    startWorkoutTemplate,
    logCompletedSet,
    skipRestTimer: () => sendWs({ type: 'skip_rest_timer' }),
    extendRestTimer: (sec = 15) =>
      sendWs({ type: 'start_rest_timer', seconds: restSecondsRemaining + sec }),
    toggleMusicPlay,
    nextTrack,
    previousTrack,
    setMusicVolume,
    playPlaylist,
    transferMusicDevice,
    switchMusicProvider,
    refreshBootstrap,
    refreshUserHistory,
  };
}

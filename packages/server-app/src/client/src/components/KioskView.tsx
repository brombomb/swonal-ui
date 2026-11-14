import React, { useEffect, useMemo, useRef, useState } from 'react';
import QRCode from 'qrcode';
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Dumbbell,
  FastForward,
  ListMusic,
  Moon,
  Music,
  Palette,
  Pause,
  Play,
  Plus,
  QrCode,
  Radio,
  Rewind,
  ShieldAlert,
  SkipForward,
  Sliders,
  Sparkles,
  Sun,
  Trophy,
  Users,
  Volume2,
  VolumeX,
  Wrench,
  X,
} from 'lucide-react';
import { useSwonalState } from '../hooks/useSwonalState';
import { AccessoryType, DynamicMode, Exercise } from '../types';
import { formatDisplayWeight, stepDisplayWeight } from '../utils/units';

const ACCENT_SWATCHES = [
  { name: 'Electric Cyan', hex: '#00F0FF' },
  { name: 'Hyper Pink', hex: '#FF007A' },
  { name: 'Volt Lime', hex: '#CCFF00' },
  { name: 'Blaze Orange', hex: '#FF5500' },
  { name: 'Ultra Violet', hex: '#8B5CF6' },
];

const DYNAMIC_MODES: Array<{ id: DynamicMode; label: string; defaultPct: number }> = [
  { id: 'standard', label: 'Standard', defaultPct: 0 },
  { id: 'eccentric', label: 'Eccentric', defaultPct: 25 },
  { id: 'chains', label: 'Chains', defaultPct: 25 },
  { id: 'spotter', label: 'Spotter', defaultPct: 0 },
  { id: 'burnout', label: 'Burnout', defaultPct: 0 },
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
    activeExercise,
    activeExercisePr,
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
    skipRestTimer,
    extendRestTimer,
    toggleMusicPlay,
    nextTrack,
    previousTrack,
    setMusicVolume,
    playPlaylist,
    transferMusicDevice,
  } = swonal;

  // Native <dialog> refs
  const qrDialogRef = useRef<HTMLDialogElement>(null);
  const stockDialogRef = useRef<HTMLDialogElement>(null);
  const libraryDialogRef = useRef<HTMLDialogElement>(null);
  const profileDialogRef = useRef<HTMLDialogElement>(null);
  const mockDialogRef = useRef<HTMLDialogElement>(null);
  const musicDialogRef = useRef<HTMLDialogElement>(null);

  const [qrDataUrl, setQrDataUrl] = useState<string>('');
  const [libraryTab, setLibraryTab] = useState<'routines' | 'exercises'>('routines');
  const [exerciseSearch, setExerciseSearch] = useState('');
  const [muscleFilter, setMuscleFilter] = useState<string>('ALL');
  const [accessoryFilter, setAccessoryFilter] = useState<string>('ALL');

  const [newProfileName, setNewProfileName] = useState('');
  const [newProfileAccent, setNewProfileAccent] = useState('#CCFF00');
  const [stockError, setStockError] = useState<string | null>(null);
  const [mockStatusMsg, setMockStatusMsg] = useState<string | null>(null);

  const companionUrl = useMemo(() => {
    if (typeof window === 'undefined') return 'http://localhost:3000/companion';
    const sessionParam = sessionState.activeSessionId
      ? `?session=${encodeURIComponent(sessionState.activeSessionId)}`
      : '';
    return `${window.location.origin}/companion${sessionParam}`;
  }, [sessionState.activeSessionId]);

  useEffect(() => {
    void QRCode.toDataURL(companionUrl, {
      width: 280,
      margin: 2,
      color: { dark: '#07090E', light: '#FFFFFF' },
    }).then(setQrDataUrl);
  }, [companionUrl]);

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
    const h = telemetry.arm_positions.height;
    const r = telemetry.arm_positions.rotation;
    const a = telemetry.arm_positions.angle;
    return (
      h.left === activeExercise.recommendedHeight &&
      h.right === activeExercise.recommendedHeight &&
      r.left === activeExercise.recommendedRotation &&
      r.right === activeExercise.recommendedRotation &&
      a.left === activeExercise.recommendedAngle &&
      a.right === activeExercise.recommendedAngle &&
      telemetry.accessory === activeExercise.accessory
    );
  }, [activeExercise, telemetry.arm_positions, telemetry.accessory]);

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

  // SVG Gauge Math
  const radius = 195;
  const circumference = 2 * Math.PI * radius;
  const shownWeightLb = telemetry.weight_enabled
    ? telemetry.applied_resistance_lb
    : telemetry.target_weight_lb;
  const displayWeight = formatDisplayWeight(shownWeightLb, activeUnit, telemetry.weight_enabled);
  const displayTarget = formatDisplayWeight(telemetry.target_weight_lb, activeUnit);
  const progress = Math.min(Math.max(shownWeightLb / 200, 0.025), 1);
  const strokeDashoffset = circumference - progress * circumference;

  const stepSmall = activeUnit === 'KG' ? 0.5 : 1;
  const stepLarge = activeUnit === 'KG' ? 2.5 : 5;

  const handleStockSwitchConfirm = async () => {
    setStockError(null);
    if (telemetry.weight_enabled) {
      sendWs({ type: 'disable_weight' });
    }
    const resp = await fetch('/api/daemon/mode/stock', { method: 'POST' });
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({ detail: 'Failed to switch mode' }));
      setStockError(err.detail || err.error || 'Daemon unreachable');
      return;
    }
    stockDialogRef.current?.close();
  };

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
      className={`relative w-[1080px] h-[1920px] flex flex-col justify-between p-7 select-none overflow-hidden transition-colors duration-300 ${
        restSecondsRemaining > 0 ? 'rest-edge-glow' : ''
      }`}
      style={{ backgroundColor: 'var(--bg-canvas)', color: 'var(--text-primary)' }}
    >
      {/* TOP STATUS BAR */}
      <header className="surface-card rounded-3xl px-6 py-4 flex items-center justify-between gap-4">
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
                  className={`flex items-center gap-2.5 px-3.5 py-2 rounded-2xl transition font-semibold ${
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
                    className="w-9 h-9 rounded-full flex items-center justify-center text-sm font-black text-slate-950"
                    style={{ backgroundColor: u.accentColor }}
                  >
                    {u.name.slice(0, 2).toUpperCase()}
                  </span>
                  <span className="text-base">{u.name}</span>
                </button>
              );
            })}
            <button
              type="button"
              onClick={() => profileDialogRef.current?.showModal()}
              title="Customize Theme, Accent Color, or Add Profile"
              className="w-11 h-11 rounded-2xl elevated-pill flex items-center justify-center hover:opacity-90 transition"
            >
              <Palette size={20} style={{ color: 'var(--accent-primary)' }} />
            </button>
          </div>

          {/* Partner Work-In Mode Toggle */}
          {users.length >= 2 && (
            <button
              type="button"
              onClick={() => togglePartnerMode(!sessionState.partnerModeEnabled)}
              className={`flex items-center gap-2 px-4 py-2.5 rounded-2xl text-sm font-bold transition ${
                sessionState.partnerModeEnabled ? 'text-slate-950' : 'elevated-pill'
              }`}
              style={
                sessionState.partnerModeEnabled
                  ? {
                      backgroundColor: 'var(--accent-primary)',
                      boxShadow: '0 0 18px var(--accent-glow)',
                    }
                  : undefined
              }
            >
              <Users size={17} />
              <span>
                {sessionState.partnerModeEnabled && partnerUser
                  ? `Work In: ${activeUser?.name} ⇄ ${partnerUser.name}`
                  : 'Work In'}
              </span>
            </button>
          )}
        </div>

        {/* Right Utility Controls */}
        <div className="flex items-center gap-2.5">
          {/* LB / KG Unit Toggle */}
          <button
            type="button"
            onClick={() => {
              if (!activeUser) return;
              const nextUnit = activeUnit === 'LB' ? 'KG' : 'LB';
              void updateUserPreferences(activeUser.id, { weightUnit: nextUnit });
            }}
            className="elevated-pill px-3.5 py-2.5 rounded-2xl font-mono font-bold text-sm hover:opacity-90 transition"
          >
            {activeUnit}
          </button>

          {/* Light / Dark Theme Toggle */}
          <button
            type="button"
            onClick={() => {
              if (!activeUser) return;
              const nextTheme = isLightTheme ? 'dark' : 'light';
              void updateUserPreferences(activeUser.id, { themeMode: nextTheme });
            }}
            title="Toggle Daylight Studio / Carbon Obsidian"
            className="elevated-pill w-11 h-11 rounded-2xl flex items-center justify-center hover:opacity-90 transition"
          >
            {isLightTheme ? <Moon size={18} /> : <Sun size={18} />}
          </button>

          {/* Companion QR Remote Modal */}
          <button
            type="button"
            onClick={() => qrDialogRef.current?.showModal()}
            title="Open Companion QR Code Remote"
            className="elevated-pill px-3.5 py-2.5 rounded-2xl flex items-center gap-2 text-sm font-semibold hover:opacity-90 transition"
          >
            <QrCode size={18} style={{ color: 'var(--accent-primary)' }} />
            <span>Phone</span>
          </button>

          {/* Hardware Telemetry Pill (also opens Mock Diagnostic Drawer) */}
          <button
            type="button"
            onClick={() => mockDialogRef.current?.showModal()}
            className={`px-3.5 py-2.5 rounded-2xl border text-xs font-mono font-bold flex items-center gap-2 transition ${
              daemonConnected && wsConnected
                ? 'bg-emerald-500/10 border-emerald-500/40 text-emerald-400'
                : 'bg-amber-500/10 border-amber-500/40 text-amber-400'
            }`}
          >
            <span
              className={`w-2.5 h-2.5 rounded-full ${
                daemonConnected ? 'bg-emerald-400 animate-pulse' : 'bg-amber-400'
              }`}
            />
            <span>{daemonConnected ? 'CDC/MOCK 30Hz' : 'DAEMON WAIT'}</span>
          </button>

          {/* Original Tonal Mode Switch */}
          <button
            type="button"
            onClick={() => {
              setStockError(null);
              stockDialogRef.current?.showModal();
            }}
            className="bg-red-500/15 hover:bg-red-500/25 border border-red-500/40 text-red-400 font-bold px-4 py-2.5 rounded-2xl transition flex items-center gap-2 text-sm"
          >
            <ShieldAlert size={18} />
            <span>Original Tonal</span>
          </button>
        </div>
      </header>

      {/* NOTIFICATION BANNERS (QUEUED WORKOUT / SAFETY CUTOUT / PR CELEBRATION) */}
      <div className="space-y-3 my-3">
        {sessionState.queuedWorkout && (
          <div
            className="surface-card rounded-2xl px-6 py-4 flex items-center justify-between border-2"
            style={{
              borderColor: 'var(--accent-primary)',
              boxShadow: '0 0 28px var(--accent-glow)',
            }}
          >
            <div className="flex items-center gap-3">
              <Sparkles size={24} style={{ color: 'var(--accent-primary)' }} />
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

        {lastPrBanner && (
          <div
            className="surface-card rounded-2xl px-6 py-3 flex items-center justify-center gap-3 font-black text-lg"
            style={{
              borderColor: 'var(--accent-primary)',
              color: 'var(--accent-primary)',
            }}
          >
            <Trophy size={24} />
            <span>{lastPrBanner}</span>
          </div>
        )}
      </div>

      {/* CENTER WORKOUT STAGE */}
      <main className="flex-1 flex flex-col justify-between py-2">
        {/* Active Exercise & Routine Header Card */}
        <section className="surface-card rounded-3xl px-7 py-5 flex items-center justify-between">
          <div>
            <div className="flex items-center gap-3 mb-1.5">
              <span
                className="px-3 py-1 rounded-full text-xs font-extrabold uppercase tracking-wider text-slate-950"
                style={{ backgroundColor: 'var(--accent-primary)' }}
              >
                {activeTemplateName
                  ? `${currentStep?.blockType.replace('_', ' ')} • Step ${currentStepIdx + 1}/${routineSteps.length}`
                  : 'Free Lift Mode'}
              </span>
              {activeExercise && (
                <span className="text-sm font-semibold opacity-75">
                  {activeExercise.muscleGroup} • {activeExercise.accessory}
                </span>
              )}
            </div>
            <h1 className="text-4xl font-black tracking-tight">
              {activeExercise?.name ?? 'Select Movement'}
            </h1>
            <div className="flex items-center gap-5 mt-2 text-sm font-semibold opacity-80">
              {currentStep ? (
                <span>
                  Set {currentStep.setNumber} of {currentStep.totalSetsInItem} • Target{' '}
                  {currentStep.targetReps} reps
                </span>
              ) : (
                <span>{activeExercise?.notes || 'Tap weight gauge or use handle click to engage'}</span>
              )}
              {activeExercisePr && (
                <span className="font-mono tabular-nums" style={{ color: 'var(--accent-primary)' }}>
                  PR 1RM: {formatDisplayWeight(activeExercisePr.oneRepMaxEst, activeUnit)}{' '}
                  {activeUnit}
                </span>
              )}
            </div>
          </div>

          <div className="flex flex-col items-end gap-3">
            {/* Arm Position Guide Badge */}
            {activeExercise && (
              <div className="flex items-center gap-2">
                <div
                  className={`px-4 py-2 rounded-2xl border text-xs font-mono font-bold flex items-center gap-2 ${
                    armsAligned
                      ? 'bg-emerald-500/15 border-emerald-500/40 text-emerald-400'
                      : 'bg-amber-500/15 border-amber-500/40 text-amber-400'
                  }`}
                >
                  <CheckCircle2 size={16} />
                  <span>
                    ARMS {armsAligned ? 'ALIGNED' : 'ADJUST'} • H{activeExercise.recommendedHeight}{' '}
                    R{activeExercise.recommendedRotation} A{activeExercise.recommendedAngle} •{' '}
                    {activeExercise.accessory}
                  </span>
                </div>
                {!armsAligned && (
                  <button
                    type="button"
                    onClick={() => void alignMockArmsToExercise(activeExercise)}
                    className="elevated-pill px-3 py-2 rounded-xl text-xs font-bold hover:opacity-90"
                  >
                    Sync Arms
                  </button>
                )}
              </div>
            )}

            <button
              type="button"
              onClick={() => libraryDialogRef.current?.showModal()}
              className="elevated-pill px-5 py-2.5 rounded-2xl font-bold text-sm flex items-center gap-2 hover:opacity-90 transition"
            >
              <Dumbbell size={18} style={{ color: 'var(--accent-primary)' }} />
              <span>Routines & Exercises</span>
            </button>
          </div>
        </section>

        {/* Central 360px Weight Dial + Left/Right Cable ROM Bars */}
        <section className="relative flex items-center justify-center my-auto py-4">
          {/* Left Cable ROM & Velocity Bar */}
          <div className="absolute left-4 flex flex-col items-center gap-3">
            <span className="text-xs font-mono font-bold uppercase tracking-widest opacity-70">
              LEFT
            </span>
            <div className="w-12 h-[420px] elevated-pill rounded-full p-1.5 flex flex-col-reverse overflow-hidden">
              <div
                className="w-full rounded-full transition-all duration-75"
                style={{
                  height: `${Math.min((telemetry.left_cable.position / 200) * 100, 100)}%`,
                  backgroundColor: 'var(--accent-primary)',
                  boxShadow: '0 0 20px var(--accent-glow)',
                }}
              />
            </div>
            <div className="text-center font-mono tabular-nums">
              <p className="text-lg font-bold">
                {formatDisplayWeight(telemetry.left_cable.load_lb, activeUnit, true)} {activeUnit}
              </p>
              <p className="text-xs opacity-70">{telemetry.left_cable.velocity.toFixed(1)} cm/s</p>
              <p className="text-[11px] opacity-50">ROM {telemetry.left_cable.position.toFixed(0)}</p>
            </div>
          </div>

          {/* 360px Interactive SVG Weight Gauge & Tap Target */}
          <div className="relative flex items-center justify-center">
            <svg className="w-[540px] h-[540px] -rotate-90" viewBox="0 0 540 540">
              <circle
                cx="270"
                cy="270"
                r={radius}
                stroke="var(--bg-elevated)"
                strokeWidth="30"
                fill="transparent"
              />
              <circle
                cx="270"
                cy="270"
                r={radius}
                stroke={telemetry.weight_enabled ? 'var(--accent-primary)' : 'var(--text-muted)'}
                strokeOpacity={telemetry.weight_enabled ? 1 : 0.35}
                strokeWidth="30"
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
              className={`absolute w-[360px] h-[360px] rounded-full flex flex-col items-center justify-center transition-all duration-300 border-4 ${
                telemetry.weight_enabled ? 'accent-glow-ring scale-[1.02]' : 'surface-card'
              }`}
              style={{
                borderColor: telemetry.weight_enabled
                  ? 'var(--accent-primary)'
                  : 'var(--border-subtle)',
                backgroundColor: telemetry.weight_enabled
                  ? 'var(--accent-muted)'
                  : 'var(--bg-surface)',
              }}
            >
              <span className="font-mono tabular-nums text-[92px] leading-none font-black tracking-tighter">
                {activeUnit === 'KG' ? displayWeight.toFixed(1).replace(/\.0$/, '') : Math.round(displayWeight)}
              </span>
              <span className="text-lg uppercase tracking-[0.22em] font-bold opacity-75 mt-2">
                {telemetry.weight_enabled ? `Applied ${activeUnit}` : `Target ${activeUnit}`}
              </span>
              <div
                className={`mt-5 px-7 py-2.5 rounded-full font-black text-sm tracking-widest uppercase transition ${
                  telemetry.weight_enabled ? 'text-slate-950 animate-pulse' : 'elevated-pill'
                }`}
                style={
                  telemetry.weight_enabled
                    ? { backgroundColor: 'var(--accent-primary)' }
                    : undefined
                }
              >
                {telemetry.weight_enabled ? 'WEIGHT ON' : 'WEIGHT OFF'}
              </div>
            </button>

            {/* Peripheral Edge-Glow Rest Timer Overlay */}
            {restSecondsRemaining > 0 && (
              <div
                className="absolute -bottom-6 px-8 py-4 rounded-3xl surface-card border-2 flex items-center gap-6"
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
                    style={{ color: 'var(--accent-primary)' }}
                  >
                    {Math.floor(restSecondsRemaining / 60)}:
                    {String(restSecondsRemaining % 60).padStart(2, '0')}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => extendRestTimer(15)}
                    className="elevated-pill px-4 py-2 rounded-xl font-bold text-sm"
                  >
                    +15s
                  </button>
                  <button
                    type="button"
                    onClick={skipRestTimer}
                    className="px-4 py-2 rounded-xl font-bold text-sm text-slate-950 flex items-center gap-1.5"
                    style={{ backgroundColor: 'var(--accent-primary)' }}
                  >
                    <SkipForward size={16} />
                    <span>Skip</span>
                  </button>
                </div>
              </div>
            )}
          </div>

          {/* Right Cable ROM & Velocity Bar */}
          <div className="absolute right-4 flex flex-col items-center gap-3">
            <span className="text-xs font-mono font-bold uppercase tracking-widest opacity-70">
              RIGHT
            </span>
            <div className="w-12 h-[420px] elevated-pill rounded-full p-1.5 flex flex-col-reverse overflow-hidden">
              <div
                className="w-full rounded-full transition-all duration-75"
                style={{
                  height: `${Math.min((telemetry.right_cable.position / 200) * 100, 100)}%`,
                  backgroundColor: 'var(--accent-primary)',
                  boxShadow: '0 0 20px var(--accent-glow)',
                }}
              />
            </div>
            <div className="text-center font-mono tabular-nums">
              <p className="text-lg font-bold">
                {formatDisplayWeight(telemetry.right_cable.load_lb, activeUnit, true)} {activeUnit}
              </p>
              <p className="text-xs opacity-70">{telemetry.right_cable.velocity.toFixed(1)} cm/s</p>
              <p className="text-[11px] opacity-50">
                ROM {telemetry.right_cable.position.toFixed(0)}
              </p>
            </div>
          </div>
        </section>

        {/* Weight Stepper, Rep Counter, Dynamic Modes, & Accessory Controls */}
        <section className="space-y-4">
          {/* Reps & Target Weight Stepper Card */}
          <div className="surface-card rounded-3xl px-8 py-5 flex items-center justify-between">
            {/* Completed Reps */}
            <div className="flex items-center gap-5">
              <div>
                <p className="text-xs uppercase tracking-widest font-bold opacity-65">
                  Completed Reps
                </p>
                <p className="font-mono tabular-nums text-6xl font-black mt-1">
                  {telemetry.rep_count}
                </p>
              </div>
              <button
                type="button"
                onClick={() => void logCompletedSet()}
                className="px-6 py-4 rounded-2xl font-black text-base text-slate-950 transition hover:scale-[1.02]"
                style={{
                  backgroundColor: 'var(--accent-primary)',
                  boxShadow: '0 0 22px var(--accent-glow)',
                }}
              >
                Complete & Log Set
              </button>
            </div>

            <div className="h-14 w-px opacity-20 bg-current" />

            {/* Target Weight +/- Buttons */}
            <div className="flex flex-col items-center">
              <p className="text-xs uppercase tracking-widest font-bold opacity-65 mb-2">
                Target Load ({activeUnit})
              </p>
              <div className="flex items-center gap-2.5">
                <button
                  type="button"
                  onClick={() =>
                    setWeight(stepDisplayWeight(telemetry.target_weight_lb, -stepLarge, activeUnit))
                  }
                  className="elevated-pill px-3.5 py-2.5 rounded-xl font-mono font-bold text-lg hover:opacity-80"
                >
                  -{stepLarge}
                </button>
                <button
                  type="button"
                  onClick={() =>
                    setWeight(stepDisplayWeight(telemetry.target_weight_lb, -stepSmall, activeUnit))
                  }
                  className="elevated-pill px-3.5 py-2.5 rounded-xl font-mono font-bold text-lg hover:opacity-80"
                >
                  -{stepSmall}
                </button>
                <span className="font-mono tabular-nums text-4xl font-black w-28 text-center">
                  {displayTarget}
                </span>
                <button
                  type="button"
                  onClick={() =>
                    setWeight(stepDisplayWeight(telemetry.target_weight_lb, stepSmall, activeUnit))
                  }
                  className="elevated-pill px-3.5 py-2.5 rounded-xl font-mono font-bold text-lg hover:opacity-80"
                >
                  +{stepSmall}
                </button>
                <button
                  type="button"
                  onClick={() =>
                    setWeight(stepDisplayWeight(telemetry.target_weight_lb, stepLarge, activeUnit))
                  }
                  className="elevated-pill px-3.5 py-2.5 rounded-xl font-mono font-bold text-lg hover:opacity-80"
                >
                  +{stepLarge}
                </button>
              </div>
            </div>
          </div>

          {/* Dynamic Mode Selector Pills + Accessory Selector */}
          <div className="surface-card rounded-3xl px-6 py-4 flex items-center justify-between gap-4">
            <div className="flex items-center gap-2.5">
              {DYNAMIC_MODES.map((m) => {
                const active = telemetry.profile_mode === m.id;
                return (
                  <button
                    key={m.id}
                    type="button"
                    onClick={() => setWeight(telemetry.target_weight_lb, m.id, m.defaultPct)}
                    className={`px-4 py-3 rounded-2xl font-bold text-sm transition ${
                      active ? 'text-slate-950' : 'elevated-pill opacity-80 hover:opacity-100'
                    }`}
                    style={
                      active
                        ? {
                            backgroundColor: 'var(--accent-primary)',
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

            <div className="flex items-center gap-2">
              {(['HANDLES', 'BAR', 'ROPE'] as AccessoryType[]).map((acc) => {
                const active = telemetry.accessory === acc;
                return (
                  <button
                    key={acc}
                    type="button"
                    onClick={() => setAccessory(acc)}
                    className={`px-3 py-2.5 rounded-xl font-mono text-xs font-bold transition ${
                      active ? 'border-2' : 'elevated-pill opacity-70'
                    }`}
                    style={
                      active
                        ? {
                            borderColor: 'var(--accent-primary)',
                            color: 'var(--accent-primary)',
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
      </main>

      {/* PERSISTENT BOTTOM MUSIC DOCK */}
      <footer className="surface-card rounded-3xl px-6 py-5 mt-3">
        <div className="flex items-center justify-between gap-6">
          {/* Track Metadata */}
          <div className="flex items-center gap-4 min-w-0 flex-1">
            <div
              className="w-16 h-16 rounded-2xl flex items-center justify-center shrink-0 overflow-hidden"
              style={{
                backgroundColor: 'var(--accent-muted)',
                color: 'var(--accent-primary)',
              }}
            >
              {musicState.artworkUrl ? (
                <img
                  src={musicState.artworkUrl}
                  alt={musicState.albumName}
                  className="w-full h-full object-cover"
                />
              ) : (
                <Music size={30} />
              )}
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <h4 className="font-extrabold text-lg truncate">{musicState.trackName}</h4>
                {musicState.isDucked && (
                  <span className="px-2 py-0.5 rounded-full text-[10px] font-bold uppercase bg-amber-500/20 text-amber-400">
                    Ducked
                  </span>
                )}
              </div>
              <p className="text-sm opacity-70 truncate">
                {musicState.artistName} •{' '}
                {devices.find((d) => d.id === musicState.activeDeviceId)?.name ??
                  'Tonal Gym (librespot)'}
              </p>
              <div className="w-64 h-1.5 rounded-full elevated-pill mt-2 overflow-hidden">
                <div
                  className="h-full transition-all duration-500"
                  style={{
                    width: `${musicProgressPct}%`,
                    backgroundColor: 'var(--accent-primary)',
                  }}
                />
              </div>
            </div>
          </div>

          {/* Playback Controls */}
          <div className="flex items-center gap-5">
            <button
              type="button"
              onClick={() => void previousTrack()}
              className="p-2.5 rounded-xl elevated-pill hover:opacity-80 transition"
              aria-label="Previous Track"
            >
              <Rewind size={22} />
            </button>
            <button
              type="button"
              onClick={() => void toggleMusicPlay()}
              className="w-14 h-14 rounded-full flex items-center justify-center text-slate-950 transition hover:scale-105"
              style={{
                backgroundColor: 'var(--accent-primary)',
                boxShadow: '0 0 22px var(--accent-glow)',
              }}
              aria-label={musicState.isPlaying ? 'Pause Music' : 'Play Music'}
            >
              {musicState.isPlaying ? (
                <Pause size={26} className="fill-current" />
              ) : (
                <Play size={26} className="ml-0.5 fill-current" />
              )}
            </button>
            <button
              type="button"
              onClick={() => void nextTrack()}
              className="p-2.5 rounded-xl elevated-pill hover:opacity-80 transition"
              aria-label="Next Track"
            >
              <FastForward size={22} />
            </button>
          </div>

          {/* Volume Slider + Playlists / Devices Drawer */}
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2 elevated-pill px-3.5 py-2 rounded-2xl">
              <Volume2 size={18} style={{ color: 'var(--accent-primary)' }} />
              <input
                type="range"
                min={0}
                max={100}
                value={musicState.volumePct ?? 72}
                onChange={(e) => void setMusicVolume(Number(e.target.value))}
                className="w-24 accent-current"
                style={{ color: 'var(--accent-primary)' }}
              />
              <span className="font-mono tabular-nums text-xs font-bold w-8 text-right">
                {musicState.volumePct ?? 72}%
              </span>
            </div>

            <button
              type="button"
              onClick={toggleSoundMuted}
              title={soundMuted ? 'Unmute Workout Chimes' : 'Mute Workout Chimes'}
              className="elevated-pill w-11 h-11 rounded-2xl flex items-center justify-center"
            >
              {soundMuted ? <VolumeX size={18} /> : <Activity size={18} />}
            </button>

            <button
              type="button"
              onClick={() => musicDialogRef.current?.showModal()}
              className="elevated-pill px-4 py-2.5 rounded-2xl font-bold text-sm flex items-center gap-2 hover:opacity-90"
            >
              <ListMusic size={18} style={{ color: 'var(--accent-primary)' }} />
              <span>Playlists & Devices</span>
            </button>
          </div>
        </div>
      </footer>

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
          <a
            href="/companion"
            className="w-full py-3 rounded-2xl font-extrabold text-center text-slate-950"
            style={{ backgroundColor: 'var(--accent-primary)' }}
          >
            Open /companion Directly
          </a>
          <p className="text-xs font-mono opacity-60 text-center break-all">{companionUrl}</p>
        </div>
      </dialog>

      {/* DIALOG 2: ORIGINAL TONAL SAFETY CONFIRMATION */}
      <dialog
        ref={stockDialogRef}
        aria-labelledby="stock-dialog-title"
        className="rounded-3xl p-8 max-w-lg w-full"
      >
        <div className="flex items-center gap-3 text-red-400 mb-3">
          <ShieldAlert size={30} />
          <h2 id="stock-dialog-title" className="text-2xl font-black">
            Switch to Original Tonal Mode?
          </h2>
        </div>
        <p className="text-sm opacity-80 leading-relaxed mb-4">
          {telemetry.weight_enabled
            ? 'WARNING: Cable resistance is currently ACTIVE! Switching modes will immediately turn weight OFF, release /dev/ttyACM0, stop BLE listeners, and launch com.tonal.app.'
            : 'This will cleanly disengage motor resistance, release the /dev/ttyACM0 serial lock, and hand control back to the stock com.tonal.app Android experience.'}
        </p>
        {stockError && (
          <div className="mb-4 p-3 rounded-xl bg-red-500/15 border border-red-500/40 text-red-300 text-sm">
            {stockError}
          </div>
        )}
        <div className="flex justify-end gap-3">
          <button
            type="button"
            autoFocus
            onClick={() => stockDialogRef.current?.close()}
            className="elevated-pill px-5 py-2.5 rounded-xl font-bold"
          >
            Stay in swonal-ui
          </button>
          <button
            type="button"
            onClick={() => void handleStockSwitchConfirm()}
            className="bg-red-600 hover:bg-red-500 text-white px-5 py-2.5 rounded-xl font-extrabold"
          >
            {telemetry.weight_enabled ? 'Turn Off Weight & Switch' : 'Switch to Original Tonal'}
          </button>
        </div>
      </dialog>

      {/* DIALOG 3: WORKOUT ROUTINES & EXERCISE LIBRARY */}
      <dialog
        ref={libraryDialogRef}
        closedby="any"
        aria-labelledby="library-dialog-title"
        className="rounded-3xl p-8 max-w-4xl w-full max-h-[82vh] overflow-y-auto"
      >
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
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {templates.map((tpl) => {
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

            <div className="grid grid-cols-2 gap-3 max-h-[48vh] overflow-y-auto pr-1">
              {filteredExercises.map((ex: Exercise) => {
                const pr = personalRecords.find((p) => p.exerciseId === ex.id);
                return (
                  <button
                    key={ex.id}
                    type="button"
                    onClick={() => {
                      selectFreeExercise(ex);
                      libraryDialogRef.current?.close();
                    }}
                    className="surface-card rounded-2xl p-4 text-left hover:border-current transition"
                  >
                    <div className="flex items-center justify-between">
                      <span className="font-extrabold text-base">{ex.name}</span>
                      <span className="elevated-pill px-2 py-0.5 rounded-lg text-[11px] font-mono font-bold">
                        {ex.accessory}
                      </span>
                    </div>
                    <p className="text-xs opacity-70 mt-1">
                      {ex.muscleGroup} • H{ex.recommendedHeight} R{ex.recommendedRotation} A
                      {ex.recommendedAngle}
                    </p>
                    {pr && (
                      <p
                        className="text-xs font-mono font-bold mt-1.5"
                        style={{ color: 'var(--accent-primary)' }}
                      >
                        1RM: {formatDisplayWeight(pr.oneRepMaxEst, activeUnit)} {activeUnit}
                      </p>
                    )}
                  </button>
                );
              })}
            </div>
          </div>
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
            <Wrench size={22} style={{ color: 'var(--accent-primary)' }} />
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

          <button
            type="button"
            onClick={() => {
              sendWs({ type: 'reset_reps' });
              setMockStatusMsg('Reset rep counter to 0');
            }}
            className="w-full elevated-pill py-2.5 rounded-xl text-sm font-bold"
          >
            Reset Rep Counter
          </button>

          {mockStatusMsg && (
            <p className="text-xs font-mono text-emerald-400 text-center">{mockStatusMsg}</p>
          )}
        </div>
      </dialog>

      {/* DIALOG 6: MUSIC PLAYLISTS, PROVIDERS & LIBRESPOT DEVICES */}
      <dialog
        ref={musicDialogRef}
        closedby="any"
        aria-labelledby="music-dialog-title"
        className="rounded-3xl p-8 max-w-2xl w-full max-h-[80vh] overflow-y-auto"
      >
        <div className="flex items-center justify-between mb-5">
          <div className="flex items-center gap-2.5">
            <Sliders size={22} style={{ color: 'var(--accent-primary)' }} />
            <h2 id="music-dialog-title" className="text-2xl font-black">
              Music Provider & Connect Devices
            </h2>
          </div>
          <button
            type="button"
            onClick={() => musicDialogRef.current?.close()}
            className="elevated-pill p-2 rounded-xl"
          >
            <X size={20} />
          </button>
        </div>

        <div className="space-y-6">
          {/* Provider Switcher */}
          <div>
            <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-2">
              Active Music Provider
            </p>
            <div className="flex flex-wrap gap-2">
              {musicProviders.map((prov) => {
                const selected = prov.id === activeProviderId;
                return (
                  <button
                    key={prov.id}
                    type="button"
                    onClick={() => {
                      if (activeUser) {
                        void updateUserPreferences(activeUser.id, {
                          preferredMusicProvider: prov.id,
                        });
                      }
                    }}
                    className={`px-4 py-2.5 rounded-xl text-sm font-bold ${
                      selected ? 'text-slate-950' : 'elevated-pill'
                    }`}
                    style={
                      selected ? { backgroundColor: 'var(--accent-primary)' } : undefined
                    }
                  >
                    {prov.displayName}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Connect Audio Devices */}
          <div>
            <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-2">
              Output Devices (librespot / Connect)
            </p>
            <div className="grid grid-cols-2 gap-3">
              {devices.map((dev) => (
                <button
                  key={dev.id}
                  type="button"
                  onClick={() => void transferMusicDevice(dev.id)}
                  className={`p-4 rounded-2xl text-left border transition flex items-center justify-between ${
                    dev.isActive ? 'border-2' : 'elevated-pill opacity-80'
                  }`}
                  style={
                    dev.isActive
                      ? {
                          borderColor: 'var(--accent-primary)',
                          backgroundColor: 'var(--accent-muted)',
                        }
                      : undefined
                  }
                >
                  <div>
                    <p className="font-bold text-sm">{dev.name}</p>
                    <p className="text-xs opacity-65">{dev.type}</p>
                  </div>
                  <Radio
                    size={18}
                    style={{ color: dev.isActive ? 'var(--accent-primary)' : undefined }}
                  />
                </button>
              ))}
            </div>
          </div>

          {/* Playlists */}
          <div>
            <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-2">
              Workout Playlists
            </p>
            <div className="space-y-2">
              {playlists.map((pl) => (
                <button
                  key={pl.id}
                  type="button"
                  onClick={() => {
                    void playPlaylist(pl.uri);
                    musicDialogRef.current?.close();
                  }}
                  className="w-full elevated-pill p-4 rounded-2xl flex items-center justify-between hover:opacity-90"
                >
                  <div className="text-left">
                    <p className="font-bold">{pl.name}</p>
                    <p className="text-xs opacity-65">{pl.trackCount} tracks</p>
                  </div>
                  <Play size={18} style={{ color: 'var(--accent-primary)' }} />
                </button>
              ))}
            </div>
          </div>
        </div>
      </dialog>
    </div>
  );
};

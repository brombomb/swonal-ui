import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDown,
  ArrowUp,
  ChevronLeft,
  ChevronRight,
  Dumbbell,
  FastForward,
  History,
  Info,
  ListPlus,
  Moon,
  Music,
  Pause,
  Play,
  Plus,
  Radio,
  Rewind,
  Send,
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
import { ProgressionAnalytics } from './ProgressionAnalytics';
import { BlockType, DynamicMode, WorkoutBlock, WorkoutTemplate } from '../types';
import { formatDisplayWeight, getContrastTextColor, KG_TO_LB, stepDisplayWeight } from '../utils/units';

type SwonalHookReturn = ReturnType<typeof useSwonalState>;
type CompanionTab = 'remote' | 'builder' | 'history';

const BLOCK_TYPES: BlockType[] = ['WARMUP', 'STRAIGHT_SET', 'SUPERSET', 'CIRCUIT'];
const DYNAMIC_MODES: Array<{ id: DynamicMode; label: string; defaultPct: number }> = [
  { id: 'standard', label: 'Standard', defaultPct: 0 },
  { id: 'eccentric', label: 'Eccentric', defaultPct: 25 },
  { id: 'chains', label: 'Chains', defaultPct: 25 },
  { id: 'spotter', label: 'Spotter', defaultPct: 0 },
  { id: 'burnout', label: 'Burnout', defaultPct: 0 },
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
    musicConfigured,
    musicState,
    playlists,
    setWeight,
    toggleWeight,
    switchUser,
    updateUserPreferences,
    togglePartnerMode,
    stepRoutine,
    logCompletedSet,
    skipRestTimer,
    extendRestTimer,
    toggleMusicPlay,
    nextTrack,
    previousTrack,
    setMusicVolume,
    playPlaylist,
    refreshBootstrap,
  } = swonal;

  const [tab, setTab] = useState<CompanionTab>('remote');
  const [selectedRpe, setSelectedRpe] = useState<number>(8);
  const [toast, setToast] = useState<string | null>(null);
  const [holdingEngage, setHoldingEngage] = useState(false);
  const [isMockMode, setIsMockMode] = useState(false);
  const [showArmInfo, setShowArmInfo] = useState(false);
  const [showRpeInfo, setShowRpeInfo] = useState(false);
  const holdTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const accentTextColor = getContrastTextColor(activeUser?.accentColor ?? '#00F0FF');

  useEffect(() => {
    void fetch('/api/daemon/health')
      .then((r) => (r.ok ? r.json() : null))
      .then((d: { mock_mode?: boolean } | null) => {
        if (d?.mock_mode) setIsMockMode(true);
      })
      .catch(() => {});
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

  // Couch Workout Builder state
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
  const [dragBlockIdx, setDragBlockIdx] = useState<number | null>(null);

  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 3500);
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

  const filteredExercises = useMemo(() => {
    const q = exerciseSearch.trim().toLowerCase();
    if (!q) return exercises.slice(0, 12);
    return exercises
      .filter(
        (e) =>
          e.name.toLowerCase().includes(q) ||
          e.muscleGroup.toLowerCase().includes(q) ||
          e.accessory.toLowerCase().includes(q)
      )
      .slice(0, 16);
  }, [exercises, exerciseSearch]);

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
    showToast(`Editing "${tpl.name}"`);
  };

  const resetBuilder = () => {
    setEditingTemplateId(null);
    setRoutineName('');
    setRoutineDesc('');
    setBlocks([{ blockType: 'STRAIGHT_SET', restSeconds: 60, items: [] }]);
  };

  const addBlock = () => {
    setBlocks((prev) => [
      ...prev,
      { blockType: 'SUPERSET', restSeconds: 60, items: [] },
    ]);
  };

  const moveBlock = (fromIdx: number, toIdx: number) => {
    if (toIdx < 0 || toIdx >= blocks.length || fromIdx === toIdx) return;
    setBlocks((prev) => {
      const next = [...prev];
      const [moved] = next.splice(fromIdx, 1);
      next.splice(toIdx, 0, moved);
      return next;
    });
  };

  const removeBlock = (idx: number) => {
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
      {/* Subtle Industrial Dot-Grid Texture */}
      <div
        aria-hidden="true"
        className="swonal-dot-grid fixed inset-0 opacity-[0.03] pointer-events-none z-0"
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
                if (!activeUser) return;
                void updateUserPreferences(activeUser.id, {
                  weightUnit: activeUnit === 'LB' ? 'KG' : 'LB',
                });
              }}
              className="elevated-pill px-2.5 py-1.5 rounded-xl font-mono text-xs font-bold"
            >
              {activeUnit}
            </button>
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
              className={`w-2.5 h-2.5 rounded-full ml-1 ${
                wsConnected && daemonConnected ? 'bg-emerald-400' : 'bg-amber-400'
              }`}
              title={daemonConnected ? 'Connected to Tonal' : 'Daemon waiting'}
            />
          </div>
        </div>
      </header>

      {/* Toast Feedback */}
      {toast && (
        <div className="max-w-lg mx-auto w-full px-4 pt-3">
          <div
            className="surface-card rounded-2xl px-4 py-2.5 text-sm font-bold flex items-center gap-2 border"
            style={{ borderColor: 'var(--accent-primary)', color: 'var(--accent-primary)' }}
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
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <p className="text-[11px] uppercase tracking-widest font-bold opacity-65">
                    Active Movement
                  </p>
                  <h2 className="text-xl font-black truncate">
                    {activeExercise?.name ?? 'Free Lift'}
                  </h2>
                  <p className="text-xs font-semibold opacity-80 mt-0.5">
                    {currentStep
                      ? `Set ${currentStep.setNumber} of ${currentStep.totalSetsInItem} • Target ${currentStep.targetReps} Reps`
                      : 'Free Lift Mode'}
                  </p>
                  {activeExercise && (
                    <div className="relative inline-block mt-1.5">
                      <button
                        type="button"
                        onClick={() => setShowArmInfo((v) => !v)}
                        className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-lg elevated-pill text-[11px] font-mono font-bold opacity-85 hover:opacity-100"
                      >
                        <span>
                          {activeExercise.accessory} • H{activeExercise.recommendedHeight} R
                          {activeExercise.recommendedRotation} A{activeExercise.recommendedAngle}
                        </span>
                        <Info size={12} />
                      </button>
                      {showArmInfo && (
                        <div className="absolute left-0 top-7 w-64 surface-card rounded-xl p-3 shadow-xl border text-[11px] space-y-1 z-30">
                          <p><strong>H:</strong> Arm Height (1 low – 6 high)</p>
                          <p><strong>R:</strong> Arm Rotation (swing 1 – 3)</p>
                          <p><strong>A:</strong> Arm Angle (tilt 1 – 2)</p>
                        </div>
                      )}
                    </div>
                  )}
                </div>
                <div className="text-right font-mono tabular-nums shrink-0">
                  <p className="text-xs opacity-65">REPS</p>
                  <p className="text-3xl font-black">
                    {telemetry.rep_count}
                    {currentStep ? (
                      <span className="text-lg opacity-55"> / {currentStep.targetReps}</span>
                    ) : null}
                  </p>
                  <div className="flex items-center justify-end gap-1.5 mt-1.5">
                    <button
                      type="button"
                      onClick={() => stepRoutine(-1)}
                      className="elevated-pill px-2.5 py-1 rounded-lg text-[11px] font-bold flex items-center gap-0.5"
                    >
                      <ChevronLeft size={13} />
                      <span>Prev</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => stepRoutine(1)}
                      className="elevated-pill px-2.5 py-1 rounded-lg text-[11px] font-bold flex items-center gap-0.5"
                    >
                      <span>Next</span>
                      <ChevronRight size={13} />
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
                    telemetry.weight_enabled ? '' : 'elevated-pill'
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
                  <span className="font-mono tabular-nums text-5xl font-black leading-none">
                    {displayCurrentWeight} {activeUnit}
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
                  <span className="font-mono tabular-nums">
                    {displayTargetWeight} {activeUnit}
                  </span>
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
                        style={{ color: 'var(--accent-primary)' }}
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
                      void logCompletedSet(undefined, selectedRpe);
                      showToast('Logged set!');
                    }}
                    className="flex-1 py-3 rounded-2xl font-extrabold text-sm"
                    style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                  >
                    Log Set & Start Rest
                  </button>
                </div>
              </div>
            </section>

            {/* Remote Music Controls (only shown when a music provider is configured) */}
            {musicConfigured && (
            <section className="surface-card rounded-3xl p-5 space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3 min-w-0">
                  <div
                    className="w-12 h-12 rounded-2xl flex items-center justify-center shrink-0"
                    style={{
                      backgroundColor: 'var(--accent-muted)',
                      color: 'var(--accent-primary)',
                    }}
                  >
                    <Music size={22} />
                  </div>
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <p className="font-extrabold truncate">{musicState.trackName}</p>
                      {musicState.isConnected && (
                        <span className="px-2 py-0.5 rounded-full text-[10px] font-bold uppercase bg-emerald-500/20 text-emerald-400 border border-emerald-500/35 shrink-0">
                          Spotify Connected
                        </span>
                      )}
                    </div>
                    <p className="text-xs opacity-70 truncate">
                      {musicState.isConnected
                        ? musicState.artistName
                        : 'Connect a music service to play'}
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    disabled={!musicState.isConnected}
                    onClick={() => void previousTrack()}
                    className="elevated-pill p-2.5 rounded-xl disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <Rewind size={18} />
                  </button>
                  <button
                    type="button"
                    disabled={!musicState.isConnected}
                    onClick={() => void toggleMusicPlay()}
                    className="w-11 h-11 rounded-full flex items-center justify-center disabled:opacity-40 disabled:cursor-not-allowed"
                    style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                  >
                    {musicState.isPlaying ? <Pause size={20} /> : <Play size={20} className="ml-0.5" />}
                  </button>
                  <button
                    type="button"
                    disabled={!musicState.isConnected}
                    onClick={() => void nextTrack()}
                    className="elevated-pill p-2.5 rounded-xl disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <FastForward size={18} />
                  </button>
                </div>
              </div>

              <div className="flex items-center gap-3">
                <Volume2 size={16} style={{ color: 'var(--accent-primary)' }} />
                <input
                  type="range"
                  min={0}
                  max={100}
                  value={musicState.volumePct ?? 72}
                  onChange={(e) => void setMusicVolume(Number(e.target.value))}
                  className="flex-1 accent-current"
                  style={{ color: 'var(--accent-primary)' }}
                />
                <span className="font-mono text-xs font-bold w-9 text-right">
                  {musicState.volumePct ?? 72}%
                </span>
              </div>

              {!musicState.isConnected && (
                <a
                  href={
                    activeUser
                      ? `/api/spotify/login?userId=${encodeURIComponent(activeUser.id)}`
                      : '/api/spotify/login'
                  }
                  className="w-full py-2.5 px-3 rounded-xl font-bold text-xs flex items-center justify-center gap-2 bg-[#1DB954] text-slate-950 hover:opacity-90 transition"
                >
                  <Music size={15} />
                  <span>Connect Spotify</span>
                </a>
              )}

              <div className="flex gap-2 overflow-x-auto pb-1">
                {playlists.map((pl) => (
                  <button
                    key={pl.id}
                    type="button"
                    onClick={() => void playPlaylist(pl.uri)}
                    className="elevated-pill px-3 py-1.5 rounded-xl text-xs font-bold shrink-0"
                  >
                    {pl.name}
                  </button>
                ))}
              </div>
            </section>
            )}

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
              <div className="flex items-center justify-between">
                <h2 className="text-lg font-black">Saved Routines</h2>
                <button
                  type="button"
                  onClick={resetBuilder}
                  className="elevated-pill px-3 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1"
                >
                  <Plus size={14} />
                  <span>New Routine</span>
                </button>
              </div>

              <div className="space-y-2.5">
                {templates.map((tpl) => {
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
                })}
              </div>
            </section>

            {/* Couch Workout Builder Composer */}
            <section className="surface-card rounded-3xl p-5 space-y-4">
              <h2 className="text-lg font-black">
                {editingTemplateId ? 'Edit Routine' : 'Couch Workout Builder'}
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

                    {/* Quick Exercise Adder for Block */}
                    <div className="pt-1 space-y-2">
                      <input
                        type="search"
                        value={exerciseSearch}
                        onChange={(e) => setExerciseSearch(e.target.value)}
                        placeholder="Search exercise to add to this block..."
                        className="w-full surface-card px-3 py-2 rounded-xl text-xs outline-none"
                      />
                      <div className="flex gap-1.5 overflow-x-auto pb-1">
                        {filteredExercises.map((ex) => (
                          <button
                            key={ex.id}
                            type="button"
                            onClick={() => addExerciseToBlock(bIdx, ex.id)}
                            className="surface-card px-2.5 py-1.5 rounded-xl text-xs font-bold shrink-0 hover:opacity-80 flex items-center gap-1"
                          >
                            <Plus size={12} style={{ color: 'var(--accent-primary)' }} />
                            <span>{ex.name}</span>
                          </button>
                        ))}
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
                <Trophy size={20} style={{ color: 'var(--accent-primary)' }} />
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
                        style={{ color: 'var(--accent-primary)' }}
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
                  <History size={20} style={{ color: 'var(--accent-primary)' }} />
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
                        style={{ color: 'var(--accent-primary)' }}
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
      </main>

      {/* Bottom Mobile Navigation Bar */}
      <nav
        className="fixed bottom-0 inset-x-0 z-20 border-t backdrop-blur-xl"
        style={{ borderColor: 'var(--border-subtle)', backgroundColor: 'var(--bg-surface)' }}
      >
        <div className="max-w-lg mx-auto grid grid-cols-3 h-16">
          <button
            type="button"
            onClick={() => setTab('remote')}
            className="flex flex-col items-center justify-center gap-1 text-xs font-bold"
            style={{ color: tab === 'remote' ? 'var(--accent-primary)' : 'var(--text-muted)' }}
          >
            <Radio size={20} />
            <span>Live Remote</span>
          </button>
          <button
            type="button"
            onClick={() => setTab('builder')}
            className="flex flex-col items-center justify-center gap-1 text-xs font-bold"
            style={{ color: tab === 'builder' ? 'var(--accent-primary)' : 'var(--text-muted)' }}
          >
            <Dumbbell size={20} />
            <span>Workout Builder</span>
          </button>
          <button
            type="button"
            onClick={() => setTab('history')}
            className="flex flex-col items-center justify-center gap-1 text-xs font-bold"
            style={{ color: tab === 'history' ? 'var(--accent-primary)' : 'var(--text-muted)' }}
          >
            <Sliders size={20} />
            <span>PRs & History</span>
          </button>
        </div>
      </nav>
    </div>
  );
};

import React, { useEffect, useMemo, useState } from 'react';
import {
  Activity,
  ArrowLeft,
  Cpu,
  Dumbbell,
  Gauge,
  Plus,
  RefreshCw,
  ShieldAlert,
  Sliders,
  Trash2,
  Video,
  Wrench,
} from 'lucide-react';
import { useSwonalState } from '../hooks/useSwonalState';
import { AccessoryType } from '../types';
import { getContrastTextColor } from '../utils/units';

interface AdminViewProps {
  swonal: ReturnType<typeof useSwonalState>;
}

const MUSCLE_GROUPS = ['Chest', 'Back', 'Shoulders', 'Legs', 'Arms', 'Core', 'Full Body'];
const ACCESSORIES: AccessoryType[] = ['HANDLES', 'BAR', 'ROPE'];

export const AdminView: React.FC<AdminViewProps> = ({ swonal }) => {
  const {
    activeUser,
    exercises,
    telemetry,
    daemonConnected,
    wsConnected,
    sendWs,
    refreshBootstrap,
  } = swonal;

  const accentTextColor = getContrastTextColor(activeUser?.accentColor ?? '#00F0FF');

  // Hardware & Calibration state
  const [daemonHealth, setDaemonHealth] = useState<{
    status?: string;
    mode?: string;
    mock?: boolean;
    serial_port?: string;
    latencyMs?: number;
  } | null>(null);
  const [calibrationStatus, setCalibrationStatus] = useState<{
    zero_offsets?: { left: number; right: number };
    last_calibrated_at?: number | null;
    expected_board?: string;
  } | null>(null);
  const [hwMsg, setHwMsg] = useState<string | null>(null);
  const [checkingLatency, setCheckingLatency] = useState(false);

  // Exercise Manager state
  const [exSearch, setExSearch] = useState('');
  const [newExName, setNewExName] = useState('');
  const [newExMuscle, setNewExMuscle] = useState('Chest');
  const [newExAccessory, setNewExAccessory] = useState<AccessoryType>('HANDLES');
  const [newExUnilateral, setNewExUnilateral] = useState(false);
  const [newExHeight, setNewExHeight] = useState(4);
  const [newExRotation, setNewExRotation] = useState(2);
  const [newExAngle, setNewExAngle] = useState(1);
  const [newExMediaUrl, setNewExMediaUrl] = useState('');
  const [mediaDrafts, setMediaDrafts] = useState<Record<string, string>>({});
  const [exMsg, setExMsg] = useState<string | null>(null);

  const checkHardwareStatus = async () => {
    setCheckingLatency(true);
    try {
      const t0 = performance.now();
      const [hResp, cResp] = await Promise.all([
        fetch('/api/daemon/health'),
        fetch('/api/daemon/calibration'),
      ]);
      const latencyMs = Math.round(performance.now() - t0);
      if (hResp.ok) {
        const h = (await hResp.json()) as Record<string, unknown>;
        setDaemonHealth({ ...h, latencyMs });
      } else {
        setDaemonHealth({ status: 'offline', latencyMs });
      }
      if (cResp.ok) {
        setCalibrationStatus(await cResp.json());
      }
    } catch {
      setDaemonHealth({ status: 'offline' });
    } finally {
      setCheckingLatency(false);
    }
  };

  useEffect(() => {
    void checkHardwareStatus();
  }, []);

  const zeroStrainGauges = async () => {
    const resp = await fetch('/api/daemon/calibration/zero', { method: 'POST' });
    if (resp.ok) {
      setCalibrationStatus(await resp.json());
      setHwMsg('Strain gauges zeroed successfully.');
    } else {
      setHwMsg('Failed to zero strain gauges.');
    }
    setTimeout(() => setHwMsg(null), 3500);
  };

  const switchHardwareMode = async (mode: 'stock' | 'custom') => {
    const resp = await fetch(`/api/daemon/mode/${mode}`, { method: 'POST' });
    if (resp.ok) {
      setHwMsg(
        mode === 'stock'
          ? 'Handed control back to Stock Tonal UI.'
          : 'Switched device to Swonal Custom Mode.'
      );
    } else {
      setHwMsg('Daemon mode switch failed.');
    }
    setTimeout(() => setHwMsg(null), 3500);
  };

  const triggerMockTest = async (endpoint: string, body?: Record<string, unknown>) => {
    const resp = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    setHwMsg(resp.ok ? `Triggered ${endpoint}` : `Failed ${endpoint}`);
    setTimeout(() => setHwMsg(null), 3000);
  };

  const filteredExercises = useMemo(() => {
    const q = exSearch.trim().toLowerCase();
    if (!q) return exercises;
    return exercises.filter(
      (e) =>
        e.name.toLowerCase().includes(q) ||
        e.muscleGroup.toLowerCase().includes(q) ||
        e.accessory.toLowerCase().includes(q)
    );
  }, [exercises, exSearch]);

  const handleCreateExercise = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newExName.trim()) return;
    const resp = await fetch('/api/exercises', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: newExName.trim(),
        muscleGroup: newExMuscle,
        accessory: newExAccessory,
        isUnilateral: newExUnilateral,
        recommendedHeight: newExHeight,
        recommendedRotation: newExRotation,
        recommendedAngle: newExAngle,
        mediaUrl: newExMediaUrl.trim() || null,
      }),
    });
    if (resp.ok) {
      setNewExName('');
      setNewExMediaUrl('');
      await refreshBootstrap();
      setExMsg('Exercise added to catalog!');
    } else {
      const err = (await resp.json().catch(() => ({}))) as { error?: string };
      setExMsg(err.error || 'Failed to add exercise.');
    }
    setTimeout(() => setExMsg(null), 3500);
  };

  const saveMediaUrl = async (exerciseId: string, mediaUrl: string) => {
    const resp = await fetch(`/api/exercises/${encodeURIComponent(exerciseId)}/media`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mediaUrl: mediaUrl.trim() || null }),
    });
    if (resp.ok) {
      await refreshBootstrap();
      setExMsg('Updated exercise demo media.');
    } else {
      setExMsg('Failed to update media.');
    }
    setTimeout(() => setExMsg(null), 3000);
  };

  const handleDeleteExercise = async (exerciseId: string, name: string) => {
    const resp = await fetch(`/api/exercises/${encodeURIComponent(exerciseId)}`, {
      method: 'DELETE',
    });
    if (resp.ok) {
      await refreshBootstrap();
      setExMsg(`Deleted ${name}`);
    } else {
      const err = (await resp.json().catch(() => ({}))) as { error?: string };
      setExMsg(err.error || 'Unable to delete exercise.');
    }
    setTimeout(() => setExMsg(null), 3500);
  };

  return (
    <div className="swonal-canvas min-h-screen pb-16 overflow-x-hidden">
      <header className="sticky top-0 z-30 surface-card border-b px-4 sm:px-6 py-4 backdrop-blur-md">
        <div className="max-w-4xl mx-auto flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <a
              href="/settings"
              className="elevated-pill p-2.5 rounded-xl hover:opacity-85 transition"
              title="Back to Settings"
            >
              <ArrowLeft size={20} />
            </a>
            <div>
              <h1 className="text-xl sm:text-2xl font-black tracking-tight flex items-center gap-2">
                <Wrench size={20} style={{ color: 'var(--accent-primary)' }} />
                <span>Hardware Admin &amp; Exercise Manager</span>
              </h1>
              <p className="text-xs opacity-70">
                Real-hardware calibration, latency tuning, and custom exercise/media catalog
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2 text-xs font-bold">
            <a href="/" className="elevated-pill px-3 py-1.5 rounded-xl">
              Kiosk
            </a>
            <a href="/companion" className="elevated-pill px-3 py-1.5 rounded-xl">
              Companion
            </a>
          </div>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-4 sm:px-6 py-6 space-y-6">
        {/* 1. Hardware Calibration, Latency & Mode Switching */}
        <section className="surface-card rounded-3xl p-6 space-y-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2.5">
              <Cpu size={22} style={{ color: 'var(--accent-primary)' }} />
              <div>
                <h2 className="text-xl font-black">Hardware Calibration &amp; Telemetry Tuning</h2>
                <p className="text-xs opacity-70">
                  Verify USB serial connection, zero strain gauges, and inspect live arm/cable sensors.
                </p>
              </div>
            </div>
            <button
              type="button"
              onClick={() => void checkHardwareStatus()}
              disabled={checkingLatency}
              className="elevated-pill px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5"
            >
              <RefreshCw size={14} className={checkingLatency ? 'animate-spin' : ''} />
              <span>Ping Daemon</span>
            </button>
          </div>

          {/* Connection & Latency Cards */}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs">
            <div className="elevated-pill rounded-2xl p-4 space-y-1">
              <p className="uppercase font-bold opacity-60 flex items-center gap-1">
                <Activity size={12} /> Daemon &amp; WS Link
              </p>
              <p className="font-black text-sm">
                {daemonConnected ? 'ONLINE' : 'OFFLINE'} • WS {wsConnected ? 'OK' : 'DOWN'}
              </p>
              <p className="opacity-70 font-mono">
                {daemonHealth?.mock ? 'MOCK ENGINE' : daemonHealth?.serial_port || 'HARDWARE'}
              </p>
            </div>

            <div className="elevated-pill rounded-2xl p-4 space-y-1">
              <p className="uppercase font-bold opacity-60 flex items-center gap-1">
                <Gauge size={12} /> Bridge Latency
              </p>
              <p className="font-black text-sm font-mono">
                {daemonHealth?.latencyMs !== undefined ? `${daemonHealth.latencyMs} ms` : '—'}
              </p>
              <p className="opacity-70">Target &lt; 25 ms local LAN / USB</p>
            </div>

            <div className="elevated-pill rounded-2xl p-4 flex items-center justify-between gap-2">
              <div>
                <p className="uppercase font-bold opacity-60">Strain Gauges</p>
                <p className="font-mono font-black text-sm">
                  L {calibrationStatus?.zero_offsets?.left?.toFixed(2) ?? '0.00'} / R{' '}
                  {calibrationStatus?.zero_offsets?.right?.toFixed(2) ?? '0.00'}
                </p>
                <p className="text-[11px] opacity-65">
                  Board: {calibrationStatus?.expected_board ?? 'Tonal V1'}
                </p>
              </div>
              <button
                type="button"
                onClick={() => void zeroStrainGauges()}
                className="px-3 py-2 rounded-xl text-xs font-black shrink-0"
                style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
              >
                Zero
              </button>
            </div>
          </div>

          {/* Live Sensor Readout */}
          <div className="elevated-pill rounded-2xl p-4 grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs font-mono">
            <div>
              <p className="opacity-60 uppercase">Left Cable</p>
              <p className="font-bold">
                Pos {telemetry.left_cable.position.toFixed(1)} • {telemetry.left_cable.load_lb.toFixed(1)} lb
              </p>
            </div>
            <div>
              <p className="opacity-60 uppercase">Right Cable</p>
              <p className="font-bold">
                Pos {telemetry.right_cable.position.toFixed(1)} • {telemetry.right_cable.load_lb.toFixed(1)} lb
              </p>
            </div>
            <div>
              <p className="opacity-60 uppercase">Left Arm (H/R/A)</p>
              <p className="font-bold">
                H{telemetry.arm_positions.height.left} R{telemetry.arm_positions.rotation.left} A
                {telemetry.arm_positions.angle.left}
              </p>
            </div>
            <div>
              <p className="opacity-60 uppercase">Right Arm (H/R/A)</p>
              <p className="font-bold">
                H{telemetry.arm_positions.height.right} R{telemetry.arm_positions.rotation.right} A
                {telemetry.arm_positions.angle.right}
              </p>
            </div>
          </div>

          {/* Mode Handoff & Safety */}
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => sendWs({ type: 'disable_weight' })}
              className="px-4 py-2.5 rounded-xl bg-red-500/20 border border-red-500/40 text-red-300 text-xs font-black flex items-center gap-1.5"
            >
              <ShieldAlert size={14} />
              <span>Emergency Weight Off</span>
            </button>
            <button
              type="button"
              onClick={() => void switchHardwareMode('custom')}
              className="elevated-pill px-4 py-2.5 rounded-xl text-xs font-bold"
            >
              Activate Swonal Custom Mode
            </button>
            <button
              type="button"
              onClick={() => void switchHardwareMode('stock')}
              className="elevated-pill px-4 py-2.5 rounded-xl text-xs font-bold"
            >
              Hand Off to Stock Tonal UI
            </button>
            <button
              type="button"
              onClick={() => void triggerMockTest('/api/daemon/mock/bar-tilt')}
              className="elevated-pill px-4 py-2.5 rounded-xl text-xs font-bold opacity-80"
            >
              Test Tilt Cutout
            </button>
          </div>

          {hwMsg && <p className="text-xs font-bold text-emerald-400">{hwMsg}</p>}
        </section>

        {/* 2. Manage Exercises & Form Demo Media */}
        <section className="surface-card rounded-3xl p-6 space-y-5">
          <div className="flex items-center gap-2.5">
            <Dumbbell size={22} style={{ color: 'var(--accent-primary)' }} />
            <div>
              <h2 className="text-xl font-black">Manage Exercises &amp; Form Media</h2>
              <p className="text-xs opacity-70">
                Add custom movements to the catalog or assign custom .mp4/.webm/.gif demo URLs from /media.
              </p>
            </div>
          </div>

          {/* Add Custom Exercise Form */}
          <form onSubmit={handleCreateExercise} className="elevated-pill rounded-2xl p-4 space-y-3">
            <p className="text-xs font-black uppercase tracking-wider opacity-75">
              Add Custom Exercise
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
              <input
                type="text"
                required
                value={newExName}
                onChange={(e) => setNewExName(e.target.value)}
                placeholder="Exercise name (e.g. Cable Bayesian Curl)"
                className="sm:col-span-2 surface-card px-3.5 py-2.5 rounded-xl text-sm outline-none"
              />
              <select
                value={newExMuscle}
                onChange={(e) => setNewExMuscle(e.target.value)}
                className="surface-card px-3 py-2.5 rounded-xl text-xs font-bold outline-none"
              >
                {MUSCLE_GROUPS.map((mg) => (
                  <option key={mg} value={mg}>
                    {mg}
                  </option>
                ))}
              </select>
            </div>

            <div className="grid grid-cols-2 sm:grid-cols-5 gap-2.5 text-xs">
              <label className="space-y-1">
                <span className="opacity-65 font-bold">Accessory</span>
                <select
                  value={newExAccessory}
                  onChange={(e) => setNewExAccessory(e.target.value as AccessoryType)}
                  className="w-full surface-card px-2.5 py-2 rounded-xl font-bold outline-none"
                >
                  {ACCESSORIES.map((acc) => (
                    <option key={acc} value={acc}>
                      {acc}
                    </option>
                  ))}
                </select>
              </label>

              <label className="space-y-1">
                <span className="opacity-65 font-bold">Height (1-6)</span>
                <input
                  type="number"
                  min={1}
                  max={6}
                  value={newExHeight}
                  onChange={(e) => setNewExHeight(Number(e.target.value))}
                  className="w-full surface-card px-2.5 py-2 rounded-xl font-mono font-bold outline-none"
                />
              </label>

              <label className="space-y-1">
                <span className="opacity-65 font-bold">Rotation (1-3)</span>
                <input
                  type="number"
                  min={1}
                  max={3}
                  value={newExRotation}
                  onChange={(e) => setNewExRotation(Number(e.target.value))}
                  className="w-full surface-card px-2.5 py-2 rounded-xl font-mono font-bold outline-none"
                />
              </label>

              <label className="space-y-1">
                <span className="opacity-65 font-bold">Angle (1-2)</span>
                <input
                  type="number"
                  min={1}
                  max={2}
                  value={newExAngle}
                  onChange={(e) => setNewExAngle(Number(e.target.value))}
                  className="w-full surface-card px-2.5 py-2 rounded-xl font-mono font-bold outline-none"
                />
              </label>

              <label className="flex items-end gap-2 pb-2 cursor-pointer font-bold">
                <input
                  type="checkbox"
                  checked={newExUnilateral}
                  onChange={(e) => setNewExUnilateral(e.target.checked)}
                  className="w-4 h-4 accent-current"
                  style={{ color: 'var(--accent-primary)' }}
                />
                <span>Unilateral</span>
              </label>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <input
                type="text"
                value={newExMediaUrl}
                onChange={(e) => setNewExMediaUrl(e.target.value)}
                placeholder="Optional form demo URL (e.g. /media/bayesian-curl.mp4)"
                className="flex-1 min-w-[220px] surface-card px-3.5 py-2.5 rounded-xl text-xs font-mono outline-none"
              />
              <button
                type="submit"
                className="px-4 py-2.5 rounded-xl font-black text-xs flex items-center gap-1.5"
                style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
              >
                <Plus size={15} />
                <span>Add Exercise</span>
              </button>
            </div>
          </form>

          {exMsg && <p className="text-xs font-bold text-emerald-400">{exMsg}</p>}

          {/* Catalog Media & Cleanup List */}
          <div className="space-y-3">
            <div className="flex items-center justify-between gap-3">
              <input
                type="search"
                value={exSearch}
                onChange={(e) => setExSearch(e.target.value)}
                placeholder={`Filter ${exercises.length} exercises...`}
                className="flex-1 elevated-pill px-4 py-2.5 rounded-xl text-xs outline-none"
              />
              <span className="text-xs font-mono opacity-65 shrink-0">
                {filteredExercises.length} shown
              </span>
            </div>

            <div className="space-y-2 max-h-[420px] overflow-y-auto pr-1">
              {filteredExercises.map((ex) => {
                const draft = mediaDrafts[ex.id] ?? ex.mediaUrl ?? '';
                return (
                  <div
                    key={ex.id}
                    className="elevated-pill rounded-2xl p-3.5 flex flex-wrap items-center justify-between gap-3"
                  >
                    <div className="min-w-0">
                      <p className="font-extrabold text-sm truncate">{ex.name}</p>
                      <p className="text-[11px] opacity-65 font-mono">
                        {ex.muscleGroup} • {ex.accessory} • H{ex.recommendedHeight} R
                        {ex.recommendedRotation} A{ex.recommendedAngle}
                      </p>
                    </div>

                    <div className="flex items-center gap-2 flex-1 min-w-[240px] justify-end">
                      <div className="flex items-center gap-1.5 flex-1 max-w-xs">
                        <Video size={14} className="opacity-60 shrink-0" />
                        <input
                          type="text"
                          value={draft}
                          onChange={(e) =>
                            setMediaDrafts((prev) => ({ ...prev, [ex.id]: e.target.value }))
                          }
                          placeholder="/media/demo.svg"
                          className="w-full surface-card px-2.5 py-1.5 rounded-lg text-[11px] font-mono outline-none"
                        />
                      </div>
                      <button
                        type="button"
                        onClick={() => void saveMediaUrl(ex.id, draft)}
                        className="surface-card px-3 py-1.5 rounded-lg text-xs font-bold hover:opacity-85 shrink-0"
                      >
                        Save Media
                      </button>
                      <button
                        type="button"
                        onClick={() => void handleDeleteExercise(ex.id, ex.name)}
                        title="Delete Exercise (only if no logged sets)"
                        className="p-2 rounded-lg text-red-400 hover:bg-red-500/15 shrink-0"
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </section>
      </main>
    </div>
  );
};

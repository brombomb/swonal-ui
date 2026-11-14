import React, { useMemo, useState } from 'react';
import { BarChart3, TrendingUp } from 'lucide-react';
import { PersonalRecord, WorkoutSession } from '../types';
import { formatDisplayWeight, WeightUnit } from '../utils/units';

interface ProgressionAnalyticsProps {
  sessions: WorkoutSession[];
  personalRecords: PersonalRecord[];
  unit: WeightUnit;
}

interface ExercisePoint {
  dateLabel: string;
  timestampMs: number;
  est1RmLb: number;
  maxWeightLb: number;
  repsAtBest: number;
}

function getWeekStart(date: Date): Date {
  const d = new Date(date);
  const day = d.getDay();
  const diff = (day === 0 ? -6 : 1) - day; // Monday as week start
  d.setDate(d.getDate() + diff);
  d.setHours(0, 0, 0, 0);
  return d;
}

function estimate1RmLb(weightLb: number, reps: number): number {
  if (reps <= 1) return weightLb;
  return Math.round(weightLb * (1 + reps / 30) * 10) / 10;
}

export const ProgressionAnalytics: React.FC<ProgressionAnalyticsProps> = ({
  sessions,
  personalRecords,
  unit,
}) => {
  // 1. Weekly Volume (Last 8 Weeks)
  const weeklyBuckets = useMemo(() => {
    const now = new Date();
    const currentWeekStart = getWeekStart(now);
    const buckets: Array<{ key: string; label: string; volumeLb: number; sessionsCount: number }> = [];
    const bucketMap = new Map<string, { volumeLb: number; sessionsCount: number }>();

    for (let i = 7; i >= 0; i -= 1) {
      const ws = new Date(currentWeekStart);
      ws.setDate(ws.getDate() - i * 7);
      const key = ws.toISOString().slice(0, 10);
      const label = `${ws.getMonth() + 1}/${ws.getDate()}`;
      bucketMap.set(key, { volumeLb: 0, sessionsCount: 0 });
      buckets.push({ key, label, volumeLb: 0, sessionsCount: 0 });
    }

    for (const sess of sessions) {
      const ws = getWeekStart(new Date(sess.startedAt));
      const key = ws.toISOString().slice(0, 10);
      const entry = bucketMap.get(key);
      if (entry) {
        entry.volumeLb += sess.totalVolume ?? 0;
        entry.sessionsCount += 1;
      }
    }

    return buckets.map((b) => {
      const data = bucketMap.get(b.key)!;
      return {
        ...b,
        volumeLb: Math.round(data.volumeLb),
        sessionsCount: data.sessionsCount,
      };
    });
  }, [sessions]);

  const maxWeeklyVol = Math.max(1, ...weeklyBuckets.map((b) => b.volumeLb));
  const total8WeekVol = weeklyBuckets.reduce((acc, b) => acc + b.volumeLb, 0);

  // 2. Per-Exercise 1RM Progression Series
  const exerciseSeriesMap = useMemo(() => {
    const map = new Map<
      string,
      { exerciseId: string; name: string; muscleGroup: string; points: ExercisePoint[] }
    >();

    // Chronological order (oldest first)
    const sortedSessions = [...sessions].sort(
      (a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime()
    );

    for (const sess of sortedSessions) {
      const logs = sess.setLogs ?? [];
      const perSessionBest = new Map<
        string,
        { name: string; muscleGroup: string; est1RmLb: number; maxWeightLb: number; repsAtBest: number }
      >();

      for (const log of logs) {
        const name = log.exercise?.name ?? 'Exercise';
        const muscleGroup = log.exercise?.muscleGroup ?? 'Full Body';
        const est = estimate1RmLb(log.weightLb, log.repsCompleted);
        const existing = perSessionBest.get(log.exerciseId);
        if (!existing || est > existing.est1RmLb) {
          perSessionBest.set(log.exerciseId, {
            name,
            muscleGroup,
            est1RmLb: est,
            maxWeightLb: Math.max(existing?.maxWeightLb ?? 0, log.weightLb),
            repsAtBest: log.repsCompleted,
          });
        } else if (log.weightLb > existing.maxWeightLb) {
          existing.maxWeightLb = log.weightLb;
        }
      }

      const d = new Date(sess.startedAt);
      const dateLabel = `${d.getMonth() + 1}/${d.getDate()}`;
      const timestampMs = d.getTime();

      for (const [exerciseId, best] of perSessionBest.entries()) {
        let entry = map.get(exerciseId);
        if (!entry) {
          entry = {
            exerciseId,
            name: best.name,
            muscleGroup: best.muscleGroup,
            points: [],
          };
          map.set(exerciseId, entry);
        }
        entry.points.push({
          dateLabel,
          timestampMs,
          est1RmLb: best.est1RmLb,
          maxWeightLb: best.maxWeightLb,
          repsAtBest: best.repsAtBest,
        });
      }
    }

    // Include PR-only exercises if they don't have session logs yet
    for (const pr of personalRecords) {
      if (!map.has(pr.exerciseId) && pr.exercise) {
        const d = new Date(pr.updatedAt);
        map.set(pr.exerciseId, {
          exerciseId: pr.exerciseId,
          name: pr.exercise.name,
          muscleGroup: pr.exercise.muscleGroup,
          points: [
            {
              dateLabel: `${d.getMonth() + 1}/${d.getDate()}`,
              timestampMs: d.getTime(),
              est1RmLb: pr.oneRepMaxEst,
              maxWeightLb: pr.maxWeightLb,
              repsAtBest: 1,
            },
          ],
        });
      }
    }

    return map;
  }, [sessions, personalRecords]);

  const exerciseOptions = useMemo(
    () =>
      Array.from(exerciseSeriesMap.values()).sort(
        (a, b) => b.points.length - a.points.length || a.name.localeCompare(b.name)
      ),
    [exerciseSeriesMap]
  );

  const [selectedExerciseId, setSelectedExerciseId] = useState<string>('');
  const activeSeries = useMemo(() => {
    if (selectedExerciseId && exerciseSeriesMap.has(selectedExerciseId)) {
      return exerciseSeriesMap.get(selectedExerciseId)!;
    }
    return exerciseOptions[0] ?? null;
  }, [selectedExerciseId, exerciseSeriesMap, exerciseOptions]);

  // 3. Cumulative Muscle Group Volume Distribution
  const muscleDistribution = useMemo(() => {
    const totals = new Map<string, number>();
    let grandTotal = 0;
    for (const sess of sessions) {
      for (const log of sess.setLogs ?? []) {
        const mg = log.exercise?.muscleGroup || 'Full Body';
        const vol = log.volumeLb ?? 0;
        totals.set(mg, (totals.get(mg) ?? 0) + vol);
        grandTotal += vol;
      }
    }
    const denom = Math.max(1, grandTotal);
    return Array.from(totals.entries())
      .map(([muscleGroup, volumeLb]) => ({
        muscleGroup,
        volumeLb: Math.round(volumeLb),
        pct: Math.round((volumeLb / denom) * 100),
      }))
      .sort((a, b) => b.volumeLb - a.volumeLb)
      .slice(0, 6);
  }, [sessions]);

  // SVG chart coordinates for 1RM trend
  const chartGeom = useMemo(() => {
    if (!activeSeries || activeSeries.points.length === 0) return null;
    const pts = activeSeries.points;
    const width = 460;
    const height = 160;
    const padX = 24;
    const padY = 20;
    const values = pts.map((p) => formatDisplayWeight(p.est1RmLb, unit));
    const minVal = Math.min(...values);
    const maxVal = Math.max(...values);
    const span = Math.max(5, maxVal - minVal);
    const yMin = Math.max(0, Math.floor(minVal - span * 0.2));
    const yMax = Math.ceil(maxVal + span * 0.2);

    const coords = pts.map((p, idx) => {
      const val = formatDisplayWeight(p.est1RmLb, unit);
      const x =
        pts.length === 1
          ? width / 2
          : padX + (idx / (pts.length - 1)) * (width - padX * 2);
      const y =
        height - padY - ((val - yMin) / Math.max(1, yMax - yMin)) * (height - padY * 2);
      return { x, y, val, label: p.dateLabel, raw: p };
    });

    const linePath = coords.map((c, idx) => `${idx === 0 ? 'M' : 'L'} ${c.x} ${c.y}`).join(' ');
    const areaPath =
      coords.length > 1
        ? `${linePath} L ${coords[coords.length - 1].x} ${height - padY} L ${coords[0].x} ${
            height - padY
          } Z`
        : '';

    const firstVal = values[0];
    const latestVal = values[values.length - 1];
    const delta = Math.round((latestVal - firstVal) * 10) / 10;
    const deltaPct = firstVal > 0 ? Math.round((delta / firstVal) * 100) : 0;

    return { width, height, padY, yMin, yMax, coords, linePath, areaPath, latestVal, delta, deltaPct };
  }, [activeSeries, unit]);

  return (
    <div className="space-y-4">
      {/* Per-Exercise Estimated 1RM Trend */}
      <section className="surface-card rounded-3xl p-5 space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <TrendingUp size={20} style={{ color: 'var(--accent-primary)' }} />
            <h2 className="text-lg font-black">1RM Strength Progression</h2>
          </div>

          {exerciseOptions.length > 0 && (
            <select
              value={activeSeries?.exerciseId ?? ''}
              onChange={(e) => setSelectedExerciseId(e.target.value)}
              aria-label="Select exercise for 1RM progression"
              className="elevated-pill px-3 py-2 rounded-xl text-xs font-bold outline-none max-w-[220px]"
            >
              {exerciseOptions.map((opt) => (
                <option key={opt.exerciseId} value={opt.exerciseId}>
                  {opt.name} ({opt.points.length})
                </option>
              ))}
            </select>
          )}
        </div>

        {!chartGeom || !activeSeries ? (
          <p className="text-sm opacity-65 elevated-pill rounded-2xl p-4">
            Complete a set to start tracking estimated 1RM progression curves.
          </p>
        ) : (
          <div className="space-y-3">
            <div className="flex items-baseline justify-between">
              <div>
                <p className="text-xs uppercase tracking-wider font-bold opacity-65">
                  Latest Est. 1RM ({activeSeries.muscleGroup})
                </p>
                <p
                  className="text-3xl font-black font-mono tabular-nums"
                  style={{ color: 'var(--accent-primary)' }}
                >
                  {chartGeom.latestVal} {unit}
                </p>
              </div>
              {activeSeries.points.length > 1 && (
                <div className="text-right font-mono tabular-nums">
                  <p
                    className={`text-sm font-black ${
                      chartGeom.delta >= 0 ? 'text-emerald-400' : 'text-amber-400'
                    }`}
                  >
                    {chartGeom.delta >= 0 ? `+${chartGeom.delta}` : chartGeom.delta} {unit} (
                    {chartGeom.deltaPct >= 0 ? `+${chartGeom.deltaPct}%` : `${chartGeom.deltaPct}%`})
                  </p>
                  <p className="text-[11px] opacity-65">
                    across {activeSeries.points.length} sessions
                  </p>
                </div>
              )}
            </div>

            <div className="elevated-pill rounded-2xl p-3">
              <svg
                viewBox={`0 0 ${chartGeom.width} ${chartGeom.height}`}
                className="w-full h-40 overflow-visible"
                role="img"
                aria-label={`${activeSeries.name} 1RM trend chart`}
              >
                <defs>
                  <linearGradient id="swonal1RmGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="var(--accent-primary)" stopOpacity="0.35" />
                    <stop offset="100%" stopColor="var(--accent-primary)" stopOpacity="0.0" />
                  </linearGradient>
                </defs>

                {/* Horizontal Grid Lines */}
                <line
                  x1={16}
                  y1={chartGeom.padY}
                  x2={chartGeom.width - 16}
                  y2={chartGeom.padY}
                  stroke="var(--border-subtle)"
                  strokeDasharray="4 4"
                />
                <line
                  x1={16}
                  y1={chartGeom.height - chartGeom.padY}
                  x2={chartGeom.width - 16}
                  y2={chartGeom.height - chartGeom.padY}
                  stroke="var(--border-subtle)"
                  strokeDasharray="4 4"
                />

                {/* Y-axis min/max labels */}
                <text
                  x={4}
                  y={chartGeom.padY - 4}
                  fontSize="10"
                  fill="var(--text-muted)"
                  className="font-mono"
                >
                  {chartGeom.yMax} {unit}
                </text>
                <text
                  x={4}
                  y={chartGeom.height - 4}
                  fontSize="10"
                  fill="var(--text-muted)"
                  className="font-mono"
                >
                  {chartGeom.yMin} {unit}
                </text>

                {chartGeom.areaPath && (
                  <path d={chartGeom.areaPath} fill="url(#swonal1RmGrad)" />
                )}
                {chartGeom.coords.length > 1 && (
                  <path
                    d={chartGeom.linePath}
                    fill="none"
                    stroke="var(--accent-primary)"
                    strokeWidth="3"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                )}

                {chartGeom.coords.map((pt, i) => (
                  <g key={`${pt.label}-${i}`}>
                    <circle
                      cx={pt.x}
                      cy={pt.y}
                      r={5}
                      fill="var(--bg-canvas)"
                      stroke="var(--accent-primary)"
                      strokeWidth="3"
                    />
                    <text
                      x={pt.x}
                      y={pt.y - 10}
                      textAnchor="middle"
                      fontSize="10"
                      fontWeight="bold"
                      fill="var(--text-primary)"
                      className="font-mono"
                    >
                      {pt.val}
                    </text>
                    <text
                      x={pt.x}
                      y={chartGeom.height - 4}
                      textAnchor="middle"
                      fontSize="10"
                      fill="var(--text-muted)"
                      className="font-mono"
                    >
                      {pt.label}
                    </text>
                  </g>
                ))}
              </svg>
              {activeSeries.points.length === 1 && (
                <p className="text-[11px] opacity-65 text-center mt-1">
                  Log more sessions for {activeSeries.name} to plot your multi-week curve.
                </p>
              )}
            </div>
          </div>
        )}
      </section>

      {/* Weekly Volume Bar Chart (Last 8 Weeks) */}
      <section className="surface-card rounded-3xl p-5 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <BarChart3 size={20} style={{ color: 'var(--accent-primary)' }} />
            <h2 className="text-lg font-black">Weekly Volume (8 Weeks)</h2>
          </div>
          <span
            className="font-mono tabular-nums text-sm font-black"
            style={{ color: 'var(--accent-primary)' }}
          >
            {formatDisplayWeight(total8WeekVol, unit, true).toLocaleString()} {unit}
          </span>
        </div>

        <div className="grid grid-cols-8 gap-2 items-end h-36 pt-6 pb-1 px-2 elevated-pill rounded-2xl">
          {weeklyBuckets.map((b) => {
            const pct = b.volumeLb > 0 ? Math.max(10, Math.round((b.volumeLb / maxWeeklyVol) * 100)) : 4;
            return (
              <div key={b.key} className="flex flex-col items-center gap-1.5 h-full justify-end">
                <span className="text-[10px] font-mono tabular-nums opacity-70">
                  {b.volumeLb > 0
                    ? `${Math.round(formatDisplayWeight(b.volumeLb, unit, true) / 100) / 10}k`
                    : ''}
                </span>
                <div
                  className="w-full max-w-[28px] rounded-t-lg transition-all duration-500"
                  style={{
                    height: `${pct}%`,
                    backgroundColor:
                      b.volumeLb > 0 ? 'var(--accent-primary)' : 'var(--border-subtle)',
                    opacity: b.volumeLb > 0 ? 0.9 : 0.4,
                  }}
                  title={`Week of ${b.label}: ${formatDisplayWeight(b.volumeLb, unit, true)} ${unit} (${b.sessionsCount} sessions)`}
                />
                <span className="text-[10px] font-mono opacity-65">{b.label}</span>
              </div>
            );
          })}
        </div>

        {/* Cumulative Muscle Group Split */}
        {muscleDistribution.length > 0 && (
          <div className="space-y-2 pt-1">
            <p className="text-xs uppercase tracking-widest font-bold opacity-65">
              Volume by Muscle Group
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-2">
              {muscleDistribution.map((m) => (
                <div key={m.muscleGroup} className="space-y-1">
                  <div className="flex justify-between text-xs">
                    <span className="font-bold">{m.muscleGroup}</span>
                    <span className="font-mono tabular-nums opacity-75">
                      {formatDisplayWeight(m.volumeLb, unit, true).toLocaleString()} {unit} ({m.pct}%)
                    </span>
                  </div>
                  <div className="h-2 rounded-full elevated-pill overflow-hidden">
                    <div
                      className="h-full rounded-full"
                      style={{
                        width: `${Math.max(5, m.pct)}%`,
                        backgroundColor: 'var(--accent-primary)',
                      }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </section>
    </div>
  );
};

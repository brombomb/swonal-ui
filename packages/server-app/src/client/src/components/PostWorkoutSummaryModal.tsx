import React, { useEffect, useRef } from 'react';
import { Award, CheckCircle2, Clock, Download, Flame, Layers, Trophy, X } from 'lucide-react';
import { WorkoutSummaryData } from '../types';
import { formatDisplayWeight, getContrastTextColor, WeightUnit } from '../utils/units';

interface PostWorkoutSummaryModalProps {
  summary: WorkoutSummaryData | null;
  unit: WeightUnit;
  accentColor: string;
  onClose: () => void;
}

function formatDuration(seconds: number): string {
  const hrs = Math.floor(seconds / 3600);
  const mins = Math.floor((seconds % 3600) / 60);
  const secs = seconds % 60;
  if (hrs > 0) {
    return `${hrs}h ${mins}m`;
  }
  if (mins > 0) {
    return `${mins}m ${String(secs).padStart(2, '0')}s`;
  }
  return `${secs}s`;
}

export const PostWorkoutSummaryModal: React.FC<PostWorkoutSummaryModalProps> = ({
  summary,
  unit,
  accentColor,
  onClose,
}) => {
  const dialogRef = useRef<HTMLDialogElement | null>(null);
  const accentTextColor = getContrastTextColor(accentColor);

  useEffect(() => {
    const dlg = dialogRef.current;
    if (!dlg) return;
    if (summary && !dlg.open) {
      dlg.showModal();
    } else if (!summary && dlg.open) {
      dlg.close();
    }
  }, [summary]);

  if (!summary) {
    return <dialog ref={dialogRef} className="hidden" />;
  }

  return (
    <dialog
      ref={dialogRef}
      closedby="any"
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      aria-labelledby="post-workout-title"
      className="rounded-3xl p-8 max-w-2xl w-full"
    >
      {/* Header */}
      <div className="flex items-start justify-between gap-4 mb-6">
        <div className="flex items-center gap-3.5">
          <div
            className="w-14 h-14 rounded-2xl flex items-center justify-center shrink-0"
            style={{
              backgroundColor: 'var(--accent-muted)',
              color: 'var(--accent-primary)',
              boxShadow: '0 0 28px var(--accent-glow)',
            }}
          >
            <Award size={30} />
          </div>
          <div>
            <p
              className="text-xs font-mono font-black uppercase tracking-widest"
              style={{ color: 'var(--accent-primary)' }}
            >
              Workout Complete
            </p>
            <h2 id="post-workout-title" className="text-3xl font-black tracking-tight">
              {summary.sessionName}
            </h2>
          </div>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="elevated-pill p-2.5 rounded-xl hover:opacity-80"
          aria-label="Close workout summary"
        >
          <X size={20} />
        </button>
      </div>

      {/* 4 KPI Stat Tiles */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-6">
        <div className="elevated-pill rounded-2xl p-4">
          <div className="flex items-center gap-1.5 text-xs font-bold uppercase tracking-wider opacity-65 mb-1">
            <Flame size={14} style={{ color: 'var(--accent-primary)' }} />
            <span>Volume</span>
          </div>
          <p className="text-2xl font-black font-mono tabular-nums">
            {formatDisplayWeight(summary.totalVolumeLb, unit, true).toLocaleString()}
            <span className="text-sm font-bold opacity-70 ml-1">{unit}</span>
          </p>
        </div>

        <div className="elevated-pill rounded-2xl p-4">
          <div className="flex items-center gap-1.5 text-xs font-bold uppercase tracking-wider opacity-65 mb-1">
            <Layers size={14} style={{ color: 'var(--accent-primary)' }} />
            <span>Work</span>
          </div>
          <p className="text-2xl font-black font-mono tabular-nums">
            {summary.totalSets}
            <span className="text-sm font-bold opacity-70 ml-1">sets</span>
          </p>
          <p className="text-xs opacity-65 font-mono tabular-nums">{summary.totalReps} total reps</p>
        </div>

        <div className="elevated-pill rounded-2xl p-4">
          <div className="flex items-center gap-1.5 text-xs font-bold uppercase tracking-wider opacity-65 mb-1">
            <Clock size={14} style={{ color: 'var(--accent-primary)' }} />
            <span>Duration</span>
          </div>
          <p className="text-2xl font-black font-mono tabular-nums">
            {formatDuration(summary.durationSeconds)}
          </p>
        </div>

        <div className="elevated-pill rounded-2xl p-4">
          <div className="flex items-center gap-1.5 text-xs font-bold uppercase tracking-wider opacity-65 mb-1">
            <CheckCircle2 size={14} style={{ color: 'var(--accent-primary)' }} />
            <span>Avg RPE</span>
          </div>
          <p className="text-2xl font-black font-mono tabular-nums">
            {summary.avgRpe !== null ? summary.avgRpe.toFixed(1) : '—'}
          </p>
        </div>
      </div>

      {/* PRs Hit This Session */}
      {summary.prsHit.length > 0 && (
        <div
          className="rounded-2xl p-4 mb-6 border"
          style={{
            backgroundColor: 'var(--accent-muted)',
            borderColor: 'var(--accent-primary)',
          }}
        >
          <div className="flex items-center gap-2 mb-3">
            <Trophy size={18} style={{ color: 'var(--accent-primary)' }} />
            <h3 className="text-sm font-black uppercase tracking-wider">
              Personal Records Broken ({summary.prsHit.length})
            </h3>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
            {summary.prsHit.map((pr) => (
              <div
                key={pr.exerciseId}
                className="surface-card rounded-xl px-3.5 py-2.5 flex items-center justify-between"
              >
                <span className="font-bold text-sm truncate pr-2">{pr.exerciseName}</span>
                <span
                  className="font-mono tabular-nums text-sm font-black shrink-0"
                  style={{ color: 'var(--accent-primary)' }}
                >
                  {formatDisplayWeight(pr.oneRepMaxEstLb, unit)} {unit} 1RM
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Muscle Group Breakdown */}
      <div className="mb-6">
        <h3 className="text-xs font-bold uppercase tracking-widest opacity-70 mb-3">
          Muscle Group Breakdown
        </h3>
        {summary.muscleBreakdown.length === 0 ? (
          <p className="text-sm opacity-65 elevated-pill rounded-2xl p-4">
            No sets were logged during this session.
          </p>
        ) : (
          <div className="space-y-2.5">
            {summary.muscleBreakdown.map((item) => (
              <div key={item.muscleGroup} className="space-y-1">
                <div className="flex items-center justify-between text-sm">
                  <span className="font-bold">
                    {item.muscleGroup}{' '}
                    <span className="text-xs opacity-65 font-normal">({item.sets} sets)</span>
                  </span>
                  <span className="font-mono tabular-nums font-bold">
                    {formatDisplayWeight(item.volumeLb, unit, true).toLocaleString()} {unit} ({item.pct}%)
                  </span>
                </div>
                <div className="h-2.5 rounded-full elevated-pill overflow-hidden">
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{
                      width: `${Math.max(4, item.pct)}%`,
                      backgroundColor: 'var(--accent-primary)',
                    }}
                  />
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Footer Actions */}
      <div className="flex flex-wrap items-center justify-between gap-3 pt-2 border-t" style={{ borderColor: 'var(--border-subtle)' }}>
        <div className="flex items-center gap-2">
          <a
            href={`/api/export/sessions.csv?userId=${encodeURIComponent(summary.userId)}`}
            className="elevated-pill px-4 py-2.5 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:opacity-85"
          >
            <Download size={14} />
            <span>CSV</span>
          </a>
          <a
            href={`/api/export/sessions.json?userId=${encodeURIComponent(summary.userId)}`}
            className="elevated-pill px-4 py-2.5 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:opacity-85"
          >
            <Download size={14} />
            <span>JSON</span>
          </a>
        </div>

        <button
          type="button"
          onClick={onClose}
          className="px-6 py-3 rounded-2xl font-black text-base transition hover:opacity-95"
          style={{
            backgroundColor: 'var(--accent-primary)',
            color: accentTextColor,
            boxShadow: '0 0 24px var(--accent-glow)',
          }}
        >
          Done — Return to Ready
        </button>
      </div>
    </dialog>
  );
};

import React from 'react';
import { ArrowLeft, Calendar, Dumbbell, Smartphone, TrendingUp, Trophy } from 'lucide-react';
import { useSwonalState } from '../hooks/useSwonalState';
import { ProgressionAnalytics } from './ProgressionAnalytics';
import { formatDisplayWeight, getContrastTextColor } from '../utils/units';

interface HistoryViewProps {
  swonal: ReturnType<typeof useSwonalState>;
}

export const HistoryView: React.FC<HistoryViewProps> = ({ swonal }) => {
  const { users, activeUser, activeUnit, personalRecords, sessions, switchUser } = swonal;

  return (
    <div
      className="relative swonal-canvas min-h-screen pb-16 overflow-x-hidden transition-colors duration-300"
      style={{ color: 'var(--text-primary)' }}
    >
      <header
        className="sticky top-0 z-20 border-b px-4 py-3"
        style={{ borderColor: 'var(--border-subtle)', backgroundColor: 'var(--bg-dialog)' }}
      >
        <div className="max-w-4xl mx-auto flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <a
              href="/"
              className="elevated-pill px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:opacity-90"
            >
              <ArrowLeft size={15} />
              <span>Kiosk</span>
            </a>
            <a
              href="/companion"
              className="elevated-pill px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:opacity-90"
            >
              <Smartphone size={15} />
              <span>Companion</span>
            </a>
            <h1 className="text-lg font-black ml-1 flex items-center gap-2">
              <TrendingUp size={18} style={{ color: 'var(--accent-primary)' }} />
              <span>{activeUser ? `${activeUser.name}'s History & PRs` : 'History & PRs'}</span>
            </h1>
          </div>

          <div className="flex items-center gap-2">
            {users.map((u) => {
              const active = u.id === activeUser?.id;
              return (
                <button
                  key={u.id}
                  type="button"
                  onClick={() => void switchUser(u.id)}
                  className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-bold ${
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
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-4 pt-6 space-y-6">
        <ProgressionAnalytics
          sessions={sessions}
          personalRecords={personalRecords}
          unit={activeUnit}
        />

        {/* Personal Records */}
        <section className="surface-card rounded-3xl p-6 space-y-3">
          <div className="flex items-center gap-2">
            <Trophy size={20} style={{ color: 'var(--accent-primary)' }} />
            <h2 className="text-lg font-black">Personal Records ({personalRecords.length})</h2>
          </div>
          {personalRecords.length === 0 ? (
            <p className="text-xs opacity-70 elevated-pill rounded-2xl p-4">
              Complete sets to start tracking Personal Records.
            </p>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
              {personalRecords.map((pr) => (
                <div
                  key={pr.id}
                  className="elevated-pill rounded-2xl p-3.5 flex items-center justify-between gap-3"
                >
                  <div className="min-w-0">
                    <p className="font-extrabold text-sm truncate">
                      {pr.exercise?.name ?? 'Exercise'}
                    </p>
                    <p className="text-xs opacity-65">{pr.exercise?.muscleGroup}</p>
                  </div>
                  <div className="text-right font-mono tabular-nums shrink-0">
                    <p className="font-black text-sm" style={{ color: 'var(--accent-primary)' }}>
                      {formatDisplayWeight(pr.oneRepMaxEst, activeUnit)} {activeUnit} 1RM
                    </p>
                    <p className="text-[11px] opacity-70">
                      Best: {formatDisplayWeight(pr.maxWeightLb, activeUnit)} {activeUnit}
                    </p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>

        {/* Recent Sessions */}
        <section className="surface-card rounded-3xl p-6 space-y-3">
          <div className="flex items-center gap-2">
            <Calendar size={20} style={{ color: 'var(--accent-primary)' }} />
            <h2 className="text-lg font-black">Recent Workout Sessions ({sessions.length})</h2>
          </div>
          {sessions.length === 0 ? (
            <p className="text-xs opacity-70 elevated-pill rounded-2xl p-4">
              No workout sessions logged yet.
            </p>
          ) : (
            <div className="space-y-2.5">
              {sessions.slice(0, 15).map((s) => (
                <div
                  key={s.id}
                  className="elevated-pill rounded-2xl p-4 flex flex-wrap items-center justify-between gap-3"
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <Dumbbell size={18} style={{ color: 'var(--accent-primary)' }} />
                    <div className="min-w-0">
                      <p className="font-extrabold text-sm truncate">
                        {s.template?.name ?? 'Free Lift Session'}
                      </p>
                      <p className="text-xs opacity-65">
                        {new Date(s.startedAt).toLocaleDateString()} • {s.setLogs?.length ?? 0} sets
                      </p>
                    </div>
                  </div>
                  <span className="font-mono font-black text-sm">
                    {formatDisplayWeight(s.totalVolume ?? 0, activeUnit)} {activeUnit}
                  </span>
                </div>
              ))}
            </div>
          )}
        </section>
      </main>
    </div>
  );
};

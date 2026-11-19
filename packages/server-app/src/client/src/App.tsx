import React, { Suspense, lazy, useCallback, useEffect, useState } from 'react';
import { Lock } from 'lucide-react';
import { useSwonalState } from './hooks/useSwonalState';

const CompanionView = lazy(() => import('./components/CompanionView').then(m => ({ default: m.CompanionView })));
const HistoryView = lazy(() => import('./components/HistoryView').then(m => ({ default: m.HistoryView })));
const KioskView = lazy(() => import('./components/KioskView').then(m => ({ default: m.KioskView })));
const SettingsView = lazy(() => import('./components/SettingsView').then(m => ({ default: m.SettingsView })));
const AdminView = lazy(() => import('./components/AdminView').then(m => ({ default: m.AdminView })));

const RouteLoadingFallback = () => (
  <div className="swonal-canvas min-h-screen flex flex-col items-center justify-center gap-3 text-sm font-bold opacity-70">
    <div className="w-8 h-8 rounded-full border-2 border-cyan-500/20 border-t-cyan-400 animate-spin" />
    <span>Loading View...</span>
  </div>
);

function AuthenticatedApp({ onAuthLock }: { onAuthLock: () => void }) {
  const swonal = useSwonalState();
  const [pathname] = useState(() =>
    typeof window !== 'undefined' ? window.location.pathname : '/'
  );
  const [scale, setScale] = useState(1);

  const isCompanion = pathname.startsWith('/companion');
  const isSettings = pathname.startsWith('/settings');
  const isAdmin = pathname.startsWith('/admin');
  const isHistory = pathname.startsWith('/history');

  useEffect(() => {
    if (isCompanion || isSettings || isAdmin || isHistory) return;
    const updateScale = () => {
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      // On native 1080x1920 Tonal portrait screen, scale = 1. On desktop monitors, fit cleanly.
      const s = Math.min(vw / 1080, vh / 1920, 1);
      setScale(s);
    };
    updateScale();
    window.addEventListener('resize', updateScale);
    return () => window.removeEventListener('resize', updateScale);
  }, [isCompanion, isSettings, isAdmin, isHistory]);

  let content: React.ReactNode;
  if (isHistory) {
    content = <HistoryView swonal={swonal} />;
  } else if (isAdmin) {
    content = <AdminView swonal={swonal} />;
  } else if (isSettings) {
    content = <SettingsView swonal={swonal} onAuthLock={onAuthLock} />;
  } else if (isCompanion) {
    content = <CompanionView swonal={swonal} />;
  } else {
    content = (
      <div
        className="w-screen h-screen flex items-center justify-center overflow-hidden"
        style={{ backgroundColor: 'var(--bg-canvas)' }}
      >
        <div
          style={{
            width: 1080,
            height: 1920,
            transform: `scale(${scale})`,
            transformOrigin: 'center center',
          }}
        >
          <KioskView swonal={swonal} />
        </div>
      </div>
    );
  }

  return <Suspense fallback={<RouteLoadingFallback />}>{content}</Suspense>;
}

export function App() {
  const [authChecked, setAuthChecked] = useState(false);
  const [authenticated, setAuthenticated] = useState(true);
  const [passcode, setPasscode] = useState('');
  const [loginError, setLoginError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const checkAuth = useCallback(async () => {
    try {
      const resp = await fetch('/api/auth/status');
      if (resp.ok) {
        const data = (await resp.json()) as { enabled?: boolean; authenticated?: boolean };
        setAuthenticated(!data.enabled || Boolean(data.authenticated));
      }
    } catch {
      // ignore network error during initial boot
    } finally {
      setAuthChecked(true);
    }
  }, []);

  useEffect(() => {
    void checkAuth();
  }, [checkAuth]);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!passcode.trim()) return;
    setSubmitting(true);
    setLoginError(null);
    try {
      const resp = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ passcode: passcode.trim() }),
      });
      if (resp.ok) {
        setPasscode('');
        setAuthenticated(true);
      } else {
        const err = (await resp.json().catch(() => ({}))) as { error?: string };
        setLoginError(err.error || 'Invalid passcode');
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (!authChecked) {
    return (
      <div className="swonal-canvas min-h-screen flex items-center justify-center text-sm font-bold opacity-70">
        Loading Swonal...
      </div>
    );
  }

  if (!authenticated) {
    return (
      <div className="swonal-canvas min-h-screen flex items-center justify-center p-6">
        <form
          onSubmit={handleLogin}
          className="surface-card rounded-3xl p-8 max-w-sm w-full space-y-5 text-center"
        >
          <div className="w-14 h-14 rounded-2xl mx-auto flex items-center justify-center elevated-pill">
            <Lock size={26} style={{ color: 'var(--accent-primary)' }} />
          </div>
          <div>
            <h1 className="text-2xl font-black tracking-tight">Swonal Gym Locked</h1>
            <p className="text-xs opacity-70 mt-1">
              Enter your gym passcode to control weights, workouts, and settings.
            </p>
          </div>
          <input
            type="password"
            autoFocus
            value={passcode}
            onChange={(e) => setPasscode(e.target.value)}
            placeholder="Enter passcode"
            className="w-full elevated-pill px-4 py-3 rounded-xl text-center text-base font-bold outline-none"
          />
          {loginError && (
            <p className="text-xs font-bold text-red-400">{loginError}</p>
          )}
          <button
            type="submit"
            disabled={submitting}
            className="w-full py-3 rounded-xl font-black text-sm text-slate-950 disabled:opacity-50"
            style={{ backgroundColor: 'var(--accent-primary)' }}
          >
            {submitting ? 'Unlocking...' : 'Unlock Swonal'}
          </button>
        </form>
      </div>
    );
  }

  return <AuthenticatedApp onAuthLock={() => setAuthenticated(false)} />;
}

export default App;

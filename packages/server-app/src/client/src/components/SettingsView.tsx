import React, { useEffect, useState } from 'react';
import {
  ArrowLeft,
  Bot,
  Check,
  Copy,
  Database,
  Download,
  Globe,
  Key,
  Music,
  Radio,
  Smartphone,
  Wrench,
} from 'lucide-react';
import { useSwonalState } from '../hooks/useSwonalState';
import { AiCoachPublicConfig, HomeAssistantBusStatus } from '../types';
import { getContrastTextColor } from '../utils/units';

interface SettingsViewProps {
  swonal: ReturnType<typeof useSwonalState>;
}

export const SettingsView: React.FC<SettingsViewProps> = ({ swonal }) => {
  const {
    users,
    activeUser,
    activeProviderId,
    daemonConnected,
    wsConnected,
    musicProviders,
    musicState,
    switchUser,
    updateUserPreferences,
  } = swonal;

  const accentTextColor = getContrastTextColor(activeUser?.accentColor ?? '#00F0FF');

  // Spotify status & Redirect URI
  const [spotifyStatus, setSpotifyStatus] = useState<{
    configured: boolean;
    redirectUri: string;
    connected: boolean;
    hasOAuthToken: boolean;
  } | null>(null);
  const [copiedRedirect, setCopiedRedirect] = useState(false);

  // Network & Base URL Override
  const [networkInfo, setNetworkInfo] = useState<{
    primaryIp: string | null;
    ips: string[];
    port: number;
    publicUrl?: string | null;
    companionBaseUrl: string | null;
  } | null>(null);
  const [baseUrlOverride, setBaseUrlOverride] = useState<string>(() => {
    if (typeof window === 'undefined') return '';
    return window.localStorage.getItem('swonal_base_url_override') ?? '';
  });
  const [baseUrlDraft, setBaseUrlDraft] = useState<string>(baseUrlOverride);
  const [baseUrlSavedMsg, setBaseUrlSavedMsg] = useState<string | null>(null);

  // AI Voice Coach BYOK
  const [coachConfig, setCoachConfig] = useState<AiCoachPublicConfig | null>(null);
  const [byokProvider, setByokProvider] = useState<
    'local' | 'openai' | 'anthropic' | 'gemini' | 'ollama'
  >('local');
  const [byokModel, setByokModel] = useState('');
  const [byokApiKey, setByokApiKey] = useState('');
  const [byokBaseUrl, setByokBaseUrl] = useState('');
  const [coachSavedMsg, setCoachSavedMsg] = useState<string | null>(null);

  // Hardware & Home Assistant
  const [calibrationStatus, setCalibrationStatus] = useState<{
    zero_offsets?: { left: number; right: number };
    last_calibrated_at?: number | null;
    expected_board?: string;
  } | null>(null);
  const [haStatus, setHaStatus] = useState<HomeAssistantBusStatus | null>(null);
  const [diagMsg, setDiagMsg] = useState<string | null>(null);

  useEffect(() => {
    const uid = activeUser?.id ? `?userId=${encodeURIComponent(activeUser.id)}` : '';
    void fetch(`/api/spotify/status${uid}`)
      .then((r) => (r.ok ? r.json() : null))
      .then((s) => {
        if (s) setSpotifyStatus(s);
      })
      .catch(() => {});
  }, [activeUser?.id, musicState.isConnected]);

  useEffect(() => {
    void fetch('/api/network-info')
      .then((r) => (r.ok ? r.json() : null))
      .then((info) => {
        if (info) setNetworkInfo(info);
      })
      .catch(() => {});

    void fetch('/api/coach/config')
      .then((r) => (r.ok ? r.json() : null))
      .then((cfg: AiCoachPublicConfig | null) => {
        if (cfg) {
          setCoachConfig(cfg);
          setByokProvider(cfg.provider);
          setByokModel(cfg.model);
          setByokBaseUrl(cfg.baseUrl ?? '');
        }
      })
      .catch(() => {});

    void fetch('/api/daemon/calibration')
      .then((r) => (r.ok ? r.json() : null))
      .then((cal) => {
        if (cal) setCalibrationStatus(cal);
      })
      .catch(() => {});

    void fetch('/api/integrations/ha/status')
      .then((r) => (r.ok ? r.json() : null))
      .then((ha) => {
        if (ha) setHaStatus(ha);
      })
      .catch(() => {});
  }, []);

  const saveBaseUrlOverride = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = baseUrlDraft.trim().replace(/\/+$/, '');
    const normalized =
      trimmed && !/^https?:\/\//i.test(trimmed) ? `https://${trimmed}` : trimmed;
    setBaseUrlOverride(normalized);
    setBaseUrlDraft(normalized);
    if (typeof window !== 'undefined') {
      if (normalized) {
        window.localStorage.setItem('swonal_base_url_override', normalized);
      } else {
        window.localStorage.removeItem('swonal_base_url_override');
      }
    }
    setBaseUrlSavedMsg(normalized ? `Saved override: ${normalized}` : 'Cleared override');
    setTimeout(() => setBaseUrlSavedMsg(null), 3000);
  };

  const saveCoachConfig = async (e: React.FormEvent) => {
    e.preventDefault();
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
      setCoachSavedMsg('Saved AI Voice Coach configuration.');
      setTimeout(() => setCoachSavedMsg(null), 3000);
    }
  };

  const zeroCalibration = async () => {
    const res = await fetch('/api/daemon/calibration/zero', { method: 'POST' });
    if (res.ok) {
      setCalibrationStatus(await res.json());
      setDiagMsg('Cable zero calibration updated.');
      setTimeout(() => setDiagMsg(null), 3000);
    }
  };

  const triggerHaTest = async () => {
    const res = await fetch('/api/integrations/ha/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ event: 'swonal_settings_test', userId: activeUser?.id }),
    });
    if (res.ok) {
      setDiagMsg('Sent Home Assistant test event.');
      setTimeout(() => setDiagMsg(null), 3000);
    }
  };

  const copyRedirectUri = () => {
    if (!spotifyStatus?.redirectUri || typeof navigator === 'undefined') return;
    void navigator.clipboard?.writeText(spotifyStatus.redirectUri);
    setCopiedRedirect(true);
    setTimeout(() => setCopiedRedirect(false), 2000);
  };

  return (
    <div
      className="relative swonal-canvas min-h-screen pb-16 overflow-x-hidden transition-colors duration-300"
      style={{ color: 'var(--text-primary)' }}
    >
      <div
        aria-hidden="true"
        className="swonal-dot-grid fixed inset-0 opacity-[0.03] pointer-events-none z-0"
      />
      <div
        aria-hidden="true"
        className="fixed -top-24 -right-24 w-[480px] h-[480px] rounded-full blur-[140px] opacity-80 pointer-events-none z-0 transition-colors duration-500"
        style={{ backgroundColor: 'var(--accent-muted)' }}
      />

      {/* Sticky Header */}
      <header
        className="sticky top-0 z-20 backdrop-blur-xl border-b px-4 py-3"
        style={{ borderColor: 'var(--border-subtle)', backgroundColor: 'var(--bg-surface)' }}
      >
        <div className="max-w-3xl mx-auto flex flex-wrap items-center justify-between gap-3">
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
            <h1 className="text-lg font-black ml-1">Application Settings</h1>
          </div>

          {/* Active Profile Switcher */}
          <div className="flex items-center gap-2">
            {users.map((u) => {
              const active = u.id === activeUser?.id;
              return (
                <button
                  key={u.id}
                  type="button"
                  onClick={() => void switchUser(u.id)}
                  className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-bold transition ${
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

      <main className="relative z-10 max-w-3xl mx-auto px-4 pt-6 space-y-6">
        {/* 1. Music Providers & Integrations */}
        <section className="surface-card rounded-3xl p-6 space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <Music size={22} style={{ color: 'var(--accent-primary)' }} />
              <div>
                <h2 className="text-xl font-black">Music Providers &amp; Integrations</h2>
                <p className="text-xs opacity-70">
                  Connect your music account per profile ({activeUser?.name}) and choose your default player.
                </p>
              </div>
            </div>
          </div>

          <div className="space-y-3">
            {musicProviders.map((prov) => {
              const isSelected = prov.id === activeProviderId;
              const isSpotify = prov.id === 'spotify';
              const isSpotifyLinked = Boolean(
                isSpotify && (spotifyStatus?.hasOAuthToken || musicState.isConnected)
              );

              return (
                <div
                  key={prov.id}
                  className="elevated-pill rounded-2xl p-4 space-y-3 border"
                  style={
                    isSelected
                      ? { borderColor: 'var(--accent-primary)' }
                      : { borderColor: 'var(--border-subtle)' }
                  }
                >
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div className="flex items-center gap-2.5">
                      <span className="font-black text-base">{prov.displayName}</span>
                      {prov.configured ? (
                        <span className="px-2 py-0.5 rounded-full text-[10px] font-bold uppercase bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
                          Configured
                        </span>
                      ) : (
                        <span className="px-2 py-0.5 rounded-full text-[10px] font-bold uppercase bg-slate-500/20 text-slate-400">
                          Not Configured
                        </span>
                      )}
                      {isSpotifyLinked && (
                        <span className="px-2 py-0.5 rounded-full text-[10px] font-bold uppercase bg-emerald-500/20 text-emerald-400 border border-emerald-500/35">
                          Spotify Connected
                        </span>
                      )}
                    </div>

                    <div className="flex items-center gap-2">
                      {isSpotify && (
                        <a
                          href={
                            activeUser
                              ? `/api/spotify/login?userId=${encodeURIComponent(activeUser.id)}`
                              : '/api/spotify/login'
                          }
                          className="px-4 py-2 rounded-xl font-bold text-xs flex items-center gap-1.5 bg-[#1DB954] text-slate-950 hover:opacity-90 transition"
                        >
                          <Music size={14} />
                          <span>{isSpotifyLinked ? 'Reconnect Spotify' : 'Connect Spotify'}</span>
                        </a>
                      )}
                      <button
                        type="button"
                        onClick={() => {
                          if (activeUser) {
                            void updateUserPreferences(activeUser.id, {
                              preferredMusicProvider: prov.id,
                            });
                          }
                        }}
                        className="px-3.5 py-2 rounded-xl text-xs font-bold transition elevated-pill"
                        style={
                          isSelected
                            ? {
                                backgroundColor: 'var(--accent-primary)',
                                color: accentTextColor,
                              }
                            : undefined
                        }
                      >
                        {isSelected ? 'Default Provider' : 'Set Default'}
                      </button>
                    </div>
                  </div>

                  {isSpotify && spotifyStatus && (
                    <div className="surface-card rounded-xl p-3 space-y-1.5 text-xs">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-bold opacity-75">
                          Spotify OAuth Redirect URI (must match Spotify Developer Dashboard):
                        </span>
                        <button
                          type="button"
                          onClick={copyRedirectUri}
                          className="elevated-pill px-2.5 py-1 rounded-lg font-bold flex items-center gap-1 hover:opacity-90"
                        >
                          {copiedRedirect ? <Check size={12} /> : <Copy size={12} />}
                          <span>{copiedRedirect ? 'Copied' : 'Copy'}</span>
                        </button>
                      </div>
                      <p className="font-mono text-[11px] break-all opacity-90">
                        {spotifyStatus.redirectUri}
                      </p>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </section>

        {/* 2. Network, Base URL Override & Companion QR */}
        <section className="surface-card rounded-3xl p-6 space-y-4">
          <div className="flex items-center gap-2.5">
            <Globe size={22} style={{ color: 'var(--accent-primary)' }} />
            <div>
              <h2 className="text-xl font-black">Network &amp; Base URL Override</h2>
              <p className="text-xs opacity-70">
                Override the host used for the Companion QR code (e.g. https://swonal.brombomb.com).
              </p>
            </div>
          </div>

          <form onSubmit={saveBaseUrlOverride} className="flex flex-wrap items-center gap-2">
            <input
              type="text"
              value={baseUrlDraft}
              onChange={(e) => setBaseUrlDraft(e.target.value)}
              placeholder="https://swonal.brombomb.com"
              className="flex-1 min-w-[240px] elevated-pill px-4 py-3 rounded-xl text-sm font-mono outline-none"
            />
            <button
              type="submit"
              className="px-5 py-3 rounded-xl font-black text-sm"
              style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
            >
              Save URL
            </button>
            {baseUrlOverride && (
              <button
                type="button"
                onClick={() => {
                  setBaseUrlOverride('');
                  setBaseUrlDraft('');
                  window.localStorage.removeItem('swonal_base_url_override');
                  setBaseUrlSavedMsg('Reset to auto-detected LAN IP');
                  setTimeout(() => setBaseUrlSavedMsg(null), 3000);
                }}
                className="elevated-pill px-4 py-3 rounded-xl text-xs font-bold"
              >
                Reset
              </button>
            )}
          </form>

          {baseUrlSavedMsg && (
            <p className="text-xs font-bold text-emerald-400">{baseUrlSavedMsg}</p>
          )}

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
            <div className="elevated-pill rounded-2xl p-3.5 space-y-1">
              <p className="uppercase font-bold opacity-60">Server PUBLIC_URL</p>
              <p className="font-mono font-bold">
                {networkInfo?.publicUrl ?? 'Not set (using LAN IP / request host)'}
              </p>
            </div>
            <div className="elevated-pill rounded-2xl p-3.5 space-y-1">
              <p className="uppercase font-bold opacity-60">Detected LAN IPs</p>
              <p className="font-mono font-bold">
                {networkInfo?.ips.length ? networkInfo.ips.join(', ') : 'Detecting...'}
              </p>
            </div>
          </div>
        </section>

        {/* 3. AI Voice Coach (BYOK) */}
        <section className="surface-card rounded-3xl p-6 space-y-4">
          <div className="flex items-center gap-2.5">
            <Bot size={22} style={{ color: 'var(--accent-primary)' }} />
            <div>
              <h2 className="text-xl font-black">AI Voice Coach (BYOK)</h2>
              <p className="text-xs opacity-70">
                Bring your own OpenAI, Anthropic, Gemini, or local Ollama key — keys stay in memory only.
              </p>
            </div>
          </div>

          <form onSubmit={saveCoachConfig} className="space-y-3">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <label className="space-y-1 text-xs font-bold">
                <span className="opacity-70 uppercase">Provider</span>
                <select
                  value={byokProvider}
                  onChange={(e) =>
                    setByokProvider(
                      e.target.value as 'local' | 'openai' | 'anthropic' | 'gemini' | 'ollama'
                    )
                  }
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl font-bold outline-none"
                >
                  <option value="local">Built-in Local Heuristic Coach (No Key)</option>
                  <option value="openai">OpenAI (GPT-4o-mini)</option>
                  <option value="anthropic">Anthropic (Claude)</option>
                  <option value="gemini">Google Gemini</option>
                  <option value="ollama">Local Ollama</option>
                </select>
              </label>

              <label className="space-y-1 text-xs font-bold">
                <span className="opacity-70 uppercase">Model Name</span>
                <input
                  type="text"
                  value={byokModel}
                  onChange={(e) => setByokModel(e.target.value)}
                  placeholder="e.g. gpt-4o-mini, claude-3-5-sonnet-latest"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl outline-none"
                />
              </label>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <label className="space-y-1 text-xs font-bold">
                <span className="opacity-70 uppercase flex items-center gap-1">
                  <Key size={12} /> API Key {coachConfig?.hasApiKey ? `(Active: ${coachConfig.keyHint})` : ''}
                </span>
                <input
                  type="password"
                  value={byokApiKey}
                  onChange={(e) => setByokApiKey(e.target.value)}
                  placeholder="sk-... (optional for local/ollama)"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl outline-none"
                />
              </label>

              <label className="space-y-1 text-xs font-bold">
                <span className="opacity-70 uppercase">Base URL (Ollama / Custom)</span>
                <input
                  type="text"
                  value={byokBaseUrl}
                  onChange={(e) => setByokBaseUrl(e.target.value)}
                  placeholder="http://localhost:11434"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl outline-none"
                />
              </label>
            </div>

            <div className="flex items-center justify-between pt-1">
              <span className="text-xs font-bold text-emerald-400">{coachSavedMsg ?? ''}</span>
              <button
                type="submit"
                className="px-5 py-2.5 rounded-xl font-black text-sm"
                style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
              >
                Save AI Coach Config
              </button>
            </div>
          </form>
        </section>

        {/* 4. Hardware, Calibration & Home Assistant */}
        <section className="surface-card rounded-3xl p-6 space-y-4">
          <div className="flex items-center gap-2.5">
            <Wrench size={22} style={{ color: 'var(--accent-primary)' }} />
            <div>
              <h2 className="text-xl font-black">Hardware, Calibration &amp; Home Assistant</h2>
              <p className="text-xs opacity-70">
                Daemon status, cable zero offsets, and MQTT/Webhook automation diagnostics.
              </p>
            </div>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="elevated-pill rounded-2xl p-4 flex items-center justify-between">
              <div>
                <p className="text-xs uppercase font-bold opacity-65">Daemon &amp; WS</p>
                <p className="font-black text-sm">
                  {daemonConnected && wsConnected ? 'Connected (30Hz)' : 'Disconnected / Waiting'}
                </p>
                <p className="text-xs opacity-70 font-mono mt-1">
                  Zero: L {calibrationStatus?.zero_offsets?.left ?? 0} / R{' '}
                  {calibrationStatus?.zero_offsets?.right ?? 0}
                </p>
              </div>
              <button
                type="button"
                onClick={() => void zeroCalibration()}
                className="surface-card px-3.5 py-2 rounded-xl text-xs font-bold hover:opacity-90"
              >
                Zero Offsets
              </button>
            </div>

            <div className="elevated-pill rounded-2xl p-4 flex items-center justify-between">
              <div>
                <p className="text-xs uppercase font-bold opacity-65 flex items-center gap-1">
                  <Radio size={12} /> Home Assistant Bus
                </p>
                <p className="font-black text-sm">
                  {haStatus?.mqttConfigured || haStatus?.webhookConfigured
                    ? 'Configured'
                    : 'Not Configured'}
                </p>
                <p className="text-xs opacity-70 mt-1">
                  Topic: {haStatus?.mqttTopicPrefix ?? 'swonal'}
                </p>
              </div>
              <button
                type="button"
                onClick={() => void triggerHaTest()}
                className="surface-card px-3.5 py-2 rounded-xl text-xs font-bold hover:opacity-90"
              >
                Test Event
              </button>
            </div>
          </div>
          {diagMsg && <p className="text-xs font-bold text-emerald-400">{diagMsg}</p>}
        </section>

        {/* 5. Session Data & Backups */}
        <section className="surface-card rounded-3xl p-6 space-y-3">
          <div className="flex items-center gap-2.5">
            <Database size={22} style={{ color: 'var(--accent-primary)' }} />
            <h2 className="text-xl font-black">Session Data &amp; Backups</h2>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <a
              href={`/api/export/sessions.csv?userId=${encodeURIComponent(activeUser?.id ?? '')}`}
              className="elevated-pill py-3 px-4 rounded-xl text-xs font-bold flex items-center justify-center gap-2 hover:opacity-90"
            >
              <Download size={15} />
              <span>Export Sessions CSV</span>
            </a>
            <a
              href={`/api/export/sessions.json?userId=${encodeURIComponent(activeUser?.id ?? '')}`}
              className="elevated-pill py-3 px-4 rounded-xl text-xs font-bold flex items-center justify-center gap-2 hover:opacity-90"
            >
              <Download size={15} />
              <span>Export Sessions JSON</span>
            </a>
            <a
              href="/api/admin/backup/db"
              className="elevated-pill py-3 px-4 rounded-xl text-xs font-bold flex items-center justify-center gap-2 hover:opacity-90"
            >
              <Database size={15} />
              <span>Backup SQLite DB</span>
            </a>
          </div>
        </section>
      </main>
    </div>
  );
};

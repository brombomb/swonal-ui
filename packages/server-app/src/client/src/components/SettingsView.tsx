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
  Lock,
  Music,
  Plus,
  Radio,
  Server,
  ShieldCheck,
  Smartphone,
  Trash2,
  TrendingUp,
  Users,
  Wrench,
} from 'lucide-react';
import { useSwonalState } from '../hooks/useSwonalState';
import { AiCoachPublicConfig, HomeAssistantBusStatus } from '../types';
import { getContrastTextColor } from '../utils/units';

interface SettingsViewProps {
  swonal: ReturnType<typeof useSwonalState>;
  onAuthLock?: () => void;
}

export const SettingsView: React.FC<SettingsViewProps> = ({ swonal, onAuthLock }) => {
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
    createUser,
    deleteUser,
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
  const [spotifyExpanded, setSpotifyExpanded] = useState(false);

  // User Management
  const [newUserName, setNewUserName] = useState('');
  const [newUserUnit, setNewUserUnit] = useState<'LB' | 'KG'>('LB');
  const [newUserAccent, setNewUserAccent] = useState('#00F0FF');
  const [userMgmtMsg, setUserMgmtMsg] = useState<string | null>(null);

  // Security / Passcode Lock
  const [authEnabled, setAuthEnabled] = useState(false);
  const [trustLocalhost, setTrustLocalhost] = useState(true);
  const [envLocked, setEnvLocked] = useState(false);
  const [passcodeDraft, setPasscodeDraft] = useState('');
  const [apiToken, setApiToken] = useState<string | null>(null);
  const [authMsg, setAuthMsg] = useState<string | null>(null);

  // Network & Base URL Override
  const [networkInfo, setNetworkInfo] = useState<{
    primaryIp: string | null;
    ips: string[];
    port: number;
    publicUrl?: string | null;
    companionBaseUrl: string | null;
    mcpStdioPath?: string;
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
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams();
    if (activeUser?.id) params.set('userId', activeUser.id);
    if (baseUrlOverride) params.set('baseUrl', baseUrlOverride);
    const qs = params.toString();
    void fetch(`/api/spotify/status${qs ? `?${qs}` : ''}`)
      .then((r) => (r.ok ? r.json() : null))
      .then((s) => {
        if (s) setSpotifyStatus(s);
      })
      .catch(() => {});
  }, [activeUser?.id, musicState.isConnected, baseUrlOverride]);

  useEffect(() => {
    void fetch('/api/network-info')
      .then((r) => (r.ok ? r.json() : null))
      .then((info) => {
        if (info) {
          setNetworkInfo(info);
          const serverUrl = info.publicUrlOverride ?? info.publicUrl ?? '';
          if (serverUrl && !baseUrlOverride) {
            setBaseUrlOverride(serverUrl);
            setBaseUrlDraft(serverUrl);
          } else if (!serverUrl && baseUrlOverride) {
            // Sync any previously saved localStorage override up to the server
            void persistPublicUrl(baseUrlOverride);
          }
        }
      })
      .catch(() => {});

    void fetch('/api/auth/status')
      .then((r) => (r.ok ? r.json() : null))
      .then((st: { enabled?: boolean; trustLocalhost?: boolean; envLocked?: boolean } | null) => {
        if (st) {
          setAuthEnabled(Boolean(st.enabled));
          setTrustLocalhost(st.trustLocalhost ?? true);
          setEnvLocked(Boolean(st.envLocked));
        }
      })
      .catch(() => {});

    void fetch('/api/auth/token')
      .then((r) => (r.ok ? r.json() : null))
      .then((tk: { apiToken?: string } | null) => {
        if (tk?.apiToken) setApiToken(tk.apiToken);
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

  const persistPublicUrl = async (rawValue: string | null) => {
    const trimmed = (rawValue ?? '').trim().replace(/\/+$/, '');
    const normalized =
      trimmed && !/^https?:\/\//i.test(trimmed) ? `https://${trimmed}` : trimmed;
    const res = await fetch('/api/network-info/public-url', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ publicUrl: normalized || null }),
    });
    if (res.ok) {
      const updated = (await res.json()) as {
        publicUrl: string | null;
        publicUrlOverride: string | null;
      };
      const effective = updated.publicUrlOverride ?? updated.publicUrl ?? '';
      setBaseUrlOverride(effective);
      setBaseUrlDraft(effective);
      setNetworkInfo((prev) => (prev ? { ...prev, publicUrl: updated.publicUrl } : prev));
      if (typeof window !== 'undefined') {
        if (effective) {
          window.localStorage.setItem('swonal_base_url_override', effective);
        } else {
          window.localStorage.removeItem('swonal_base_url_override');
        }
      }
      setBaseUrlSavedMsg(effective ? `Saved override: ${effective}` : 'Reset to LAN IP');
      setTimeout(() => setBaseUrlSavedMsg(null), 3000);
    }
  };

  const saveBaseUrlOverride = (e: React.FormEvent) => {
    e.preventDefault();
    void persistPublicUrl(baseUrlDraft);
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

  const copyText = (key: string, text: string) => {
    if (typeof navigator === 'undefined') return;
    void navigator.clipboard?.writeText(text);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const effectiveMcpBaseUrl = (() => {
    if (baseUrlOverride) return baseUrlOverride.replace(/\/+$/, '');
    if (networkInfo?.publicUrl) return networkInfo.publicUrl.replace(/\/+$/, '');
    if (networkInfo?.companionBaseUrl) return networkInfo.companionBaseUrl.replace(/\/+$/, '');
    if (typeof window !== 'undefined') return window.location.origin;
    return 'http://localhost:3000';
  })();

  const mcpSseUrl =
    authEnabled && apiToken
      ? `${effectiveMcpBaseUrl}/mcp/sse?token=${encodeURIComponent(apiToken)}`
      : `${effectiveMcpBaseUrl}/mcp/sse`;
  const mcpSseJson = JSON.stringify(
    {
      mcpServers: {
        'swonal-ui': {
          url: mcpSseUrl,
        },
      },
    },
    null,
    2
  );
  const mcpStdioJson = JSON.stringify(
    {
      mcpServers: {
        'swonal-ui': {
          command: 'node',
          args: [
            networkInfo?.mcpStdioPath ||
              'packages/server-app/dist/server/mcp/stdio.js',
          ],
        },
      },
    },
    null,
    2
  );

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
        className="fixed -top-24 -right-24 w-[480px] h-[480px] rounded-full opacity-80 pointer-events-none z-0"
        style={{ background: 'radial-gradient(circle, var(--accent-muted) 0%, transparent 70%)' }}
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
            <a
              href="/history"
              className="elevated-pill px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:opacity-90"
            >
              <TrendingUp size={15} style={{ color: 'var(--accent-primary)' }} />
              <span>History</span>
            </a>
            <a
              href="/admin"
              className="elevated-pill px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:opacity-90"
            >
              <Wrench size={15} style={{ color: 'var(--accent-primary)' }} />
              <span>Admin</span>
            </a>
            <h1 className="text-lg font-black ml-1">Application Settings</h1>
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
                      {!isSpotify && (
                        <span className="px-2 py-0.5 rounded-full text-[10px] font-bold uppercase bg-amber-500/15 text-amber-300 border border-amber-500/35">
                          Not Yet Implemented
                        </span>
                      )}
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
                      {isSpotify && isSpotifyLinked && (
                        <button
                          type="button"
                          onClick={() => setSpotifyExpanded((v) => !v)}
                          className="elevated-pill px-3 py-2 rounded-xl text-xs font-bold hover:opacity-90"
                        >
                          {spotifyExpanded ? 'Minimize' : 'Manage'}
                        </button>
                      )}
                      {isSpotify && (!isSpotifyLinked || spotifyExpanded) && (
                        <a
                          href={(() => {
                            const p = new URLSearchParams();
                            if (activeUser?.id) p.set('userId', activeUser.id);
                            if (baseUrlOverride) p.set('baseUrl', baseUrlOverride);
                            const qs = p.toString();
                            return `/api/spotify/login${qs ? `?${qs}` : ''}`;
                          })()}
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

                  {isSpotify && spotifyStatus && (!isSpotifyLinked || spotifyExpanded) && (
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
                onClick={() => void persistPublicUrl(null)}
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
              <p className="uppercase font-bold opacity-60">Effective Public URL</p>
              <p className="font-mono font-bold break-all">
                {networkInfo?.publicUrl || baseUrlOverride || 'Not set (using LAN IP / request host)'}
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
          <div className="flex flex-wrap items-center justify-between gap-2.5">
            <div className="flex items-center gap-2.5">
              <Bot size={22} style={{ color: 'var(--accent-primary)' }} />
              <div>
                <h2 className="text-xl font-black">AI Voice Coach (BYOK)</h2>
                <p className="text-xs opacity-70">
                  Bring your own OpenAI, Anthropic, Gemini, or local Ollama key — keys stay in memory only.
                </p>
              </div>
            </div>
            <span className="px-2.5 py-1 rounded-full text-[10px] font-bold uppercase bg-amber-500/15 text-amber-300 border border-amber-500/35 shrink-0">
              Not Yet Implemented
            </span>
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

        {/* 4. Model Context Protocol (MCP) Server */}
        <section className="surface-card rounded-3xl p-6 space-y-4">
          <div className="flex items-center gap-2.5">
            <Server size={22} style={{ color: 'var(--accent-primary)' }} />
            <div>
              <h2 className="text-xl font-black">Model Context Protocol (MCP) Server</h2>
              <p className="text-xs opacity-70">
                Copy-paste these configs into Claude Desktop, Cursor, or any MCP-compatible AI to read PRs, stats, and build/queue workouts.
              </p>
            </div>
          </div>

          {/* SSE URL */}
          <div className="elevated-pill rounded-2xl p-4 space-y-2">
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-bold uppercase opacity-70">HTTP SSE Endpoint</span>
              <button
                type="button"
                onClick={() => copyText('mcp-url', mcpSseUrl)}
                className="surface-card px-2.5 py-1 rounded-lg text-xs font-bold flex items-center gap-1 hover:opacity-90"
              >
                {copiedKey === 'mcp-url' ? <Check size={12} /> : <Copy size={12} />}
                <span>{copiedKey === 'mcp-url' ? 'Copied' : 'Copy URL'}</span>
              </button>
            </div>
            <p className="font-mono text-xs break-all">{mcpSseUrl}</p>
          </div>

          {/* Copy-Paste JSON Snippets */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div className="elevated-pill rounded-2xl p-4 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold uppercase opacity-70">Remote SSE Config (JSON)</span>
                <button
                  type="button"
                  onClick={() => copyText('mcp-sse-json', mcpSseJson)}
                  className="surface-card px-2.5 py-1 rounded-lg text-xs font-bold flex items-center gap-1 hover:opacity-90"
                >
                  {copiedKey === 'mcp-sse-json' ? <Check size={12} /> : <Copy size={12} />}
                  <span>{copiedKey === 'mcp-sse-json' ? 'Copied' : 'Copy JSON'}</span>
                </button>
              </div>
              <pre className="text-[11px] font-mono overflow-x-auto opacity-90 leading-relaxed">
                {mcpSseJson}
              </pre>
            </div>

            <div className="elevated-pill rounded-2xl p-4 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold uppercase opacity-70">Local Stdio Config (JSON)</span>
                <button
                  type="button"
                  onClick={() => copyText('mcp-stdio-json', mcpStdioJson)}
                  className="surface-card px-2.5 py-1 rounded-lg text-xs font-bold flex items-center gap-1 hover:opacity-90"
                >
                  {copiedKey === 'mcp-stdio-json' ? <Check size={12} /> : <Copy size={12} />}
                  <span>{copiedKey === 'mcp-stdio-json' ? 'Copied' : 'Copy JSON'}</span>
                </button>
              </div>
              <pre className="text-[11px] font-mono overflow-x-auto opacity-90 leading-relaxed">
                {mcpStdioJson}
              </pre>
            </div>
          </div>

          {/* Targeting Individual Users */}
          <div className="elevated-pill rounded-2xl p-4 space-y-2.5">
            <p className="text-xs font-bold uppercase opacity-70">
              Targeting Individual Users (userId for MCP Tools &amp; Resources)
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {users.map((u) => {
                const handle = `@${u.name.toLowerCase().replace(/[^a-z0-9]+/g, '') || 'lifter'}`;
                return (
                  <div
                    key={u.id}
                    className="surface-card rounded-xl px-3 py-2 flex items-center justify-between gap-2"
                  >
                    <div className="min-w-0 flex items-center gap-2">
                      <span
                        className="w-2.5 h-2.5 rounded-full shrink-0"
                        style={{ backgroundColor: u.accentColor }}
                      />
                      <div className="min-w-0">
                        <p className="text-xs font-black truncate">{u.name}</p>
                        <p className="font-mono text-[10px] opacity-65 truncate">{handle}</p>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => copyText(`uid-${u.id}`, u.id)}
                      className="elevated-pill px-2.5 py-1 rounded-lg text-[11px] font-bold flex items-center gap-1 shrink-0"
                    >
                      {copiedKey === `uid-${u.id}` ? <Check size={11} /> : <Copy size={11} />}
                      <span>{copiedKey === `uid-${u.id}` ? 'Copied' : 'Copy MCP ID'}</span>
                    </button>
                  </div>
                );
              })}
            </div>
            <p className="text-[11px] opacity-70 leading-relaxed">
              Pass <code className="font-mono">userId</code> to{' '}
              <code className="font-mono">get_user_strength_history</code>,{' '}
              <code className="font-mono">create_custom_workout</code>, or{' '}
              <code className="font-mono">queue_workout_for_session</code>, or read{' '}
              <code className="font-mono">swonal://users/&lt;userId&gt;/profile-and-prs</code> and{' '}
              <code className="font-mono">swonal://users/&lt;userId&gt;/workouts</code>.
            </p>
          </div>
        </section>

        {/* 5. Home Assistant Event Bus */}
        <section className="surface-card rounded-3xl p-6 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2.5">
              <Radio size={22} style={{ color: 'var(--accent-primary)' }} />
              <div>
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-xl font-black">Home Assistant Event Bus</h2>
                  <span className="px-2.5 py-0.5 rounded-full text-[10px] font-bold uppercase bg-amber-500/15 text-amber-300 border border-amber-500/35">
                    Not Yet Implemented
                  </span>
                </div>
                <p className="text-xs opacity-70">
                  MQTT &amp; Webhook automation diagnostics (hardware calibration lives in Admin).
                </p>
              </div>
            </div>
            <a
              href="/admin"
              className="elevated-pill px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5 hover:opacity-90"
            >
              <Wrench size={14} style={{ color: 'var(--accent-primary)' }} />
              <span>Hardware Calibration →</span>
            </a>
          </div>

          <div className="elevated-pill rounded-2xl p-4 flex items-center justify-between">
            <div>
              <p className="text-xs uppercase font-bold opacity-65">MQTT / Webhook Status</p>
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

        {/* 6. Household Lifters & User Management */}
        <section className="surface-card rounded-3xl p-6 space-y-4">
          <div className="flex items-center gap-2.5">
            <Users size={22} style={{ color: 'var(--accent-primary)' }} />
            <div>
              <h2 className="text-xl font-black">Household Lifters &amp; Profiles</h2>
              <p className="text-xs opacity-70">
                Add new lifters, switch active profile, customize units and accent colors, or remove profiles.
              </p>
            </div>
          </div>

          <div className="space-y-2.5">
            {users.map((u) => {
              const isCurrent = u.id === activeUser?.id;
              return (
                <div
                  key={u.id}
                  className="elevated-pill rounded-2xl p-4 flex flex-wrap items-center justify-between gap-3 border"
                  style={
                    isCurrent
                      ? { borderColor: 'var(--accent-primary)' }
                      : { borderColor: 'var(--border-subtle)' }
                  }
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <input
                      type="color"
                      aria-label={`${u.name} accent color`}
                      value={u.accentColor}
                      onChange={(e) =>
                        void updateUserPreferences(u.id, {
                          accentColor: e.target.value,
                          avatarColor: e.target.value,
                        })
                      }
                      className="w-8 h-8 rounded-lg cursor-pointer bg-transparent border-0 shrink-0"
                    />
                    <div className="min-w-0">
                      <p className="font-black text-sm truncate">{u.name}</p>
                      <p className="text-[11px] opacity-65">
                        {u.weightUnit} · {u.themeMode}
                      </p>
                    </div>
                  </div>

                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() =>
                        void updateUserPreferences(u.id, {
                          weightUnit: u.weightUnit === 'LB' ? 'KG' : 'LB',
                        })
                      }
                      className="surface-card px-3 py-1.5 rounded-xl text-xs font-bold"
                    >
                      {u.weightUnit}
                    </button>
                    <button
                      type="button"
                      onClick={() => switchUser(u.id)}
                      className="px-3.5 py-1.5 rounded-xl text-xs font-bold"
                      style={
                        isCurrent
                          ? { backgroundColor: 'var(--accent-primary)', color: accentTextColor }
                          : undefined
                      }
                    >
                      {isCurrent ? 'Active' : 'Switch'}
                    </button>
                    {users.length > 1 && (
                      <button
                        type="button"
                        onClick={async () => {
                          const res = await deleteUser(u.id);
                          setUserMgmtMsg(
                            res.ok ? `Removed ${u.name}` : res.error || 'Unable to remove user'
                          );
                          setTimeout(() => setUserMgmtMsg(null), 3000);
                        }}
                        aria-label={`Delete ${u.name}`}
                        className="p-2 rounded-xl bg-red-500/15 text-red-400 hover:bg-red-500/25"
                      >
                        <Trash2 size={14} />
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>

          <form
            onSubmit={async (e) => {
              e.preventDefault();
              if (!newUserName.trim()) return;
              await createUser(newUserName.trim(), newUserAccent, newUserUnit);
              setUserMgmtMsg(`Added lifter ${newUserName.trim()}`);
              setNewUserName('');
              setTimeout(() => setUserMgmtMsg(null), 3000);
            }}
            className="flex flex-wrap items-center gap-2 pt-1"
          >
            <input
              type="text"
              value={newUserName}
              onChange={(e) => setNewUserName(e.target.value)}
              placeholder="New lifter name (e.g. Alex)"
              className="flex-1 min-w-[180px] elevated-pill px-4 py-2.5 rounded-xl text-sm outline-none"
            />
            <select
              value={newUserUnit}
              onChange={(e) => setNewUserUnit(e.target.value as 'LB' | 'KG')}
              className="elevated-pill px-3 py-2.5 rounded-xl text-xs font-bold outline-none"
            >
              <option value="LB">LB</option>
              <option value="KG">KG</option>
            </select>
            <input
              type="color"
              value={newUserAccent}
              onChange={(e) => setNewUserAccent(e.target.value)}
              className="w-11 h-10 rounded-xl elevated-pill p-1 cursor-pointer"
            />
            <button
              type="submit"
              className="px-4 py-2.5 rounded-xl font-black text-xs flex items-center gap-1.5"
              style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
            >
              <Plus size={15} />
              <span>Add Lifter</span>
            </button>
          </form>
          {userMgmtMsg && <p className="text-xs font-bold text-emerald-400">{userMgmtMsg}</p>}
        </section>

        {/* 7. Security & Internet Access Lock */}
        <section className="surface-card rounded-3xl p-6 space-y-4">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2.5">
              <ShieldCheck size={22} style={{ color: 'var(--accent-primary)' }} />
              <div>
                <h2 className="text-xl font-black">Security &amp; Internet Access Lock</h2>
                <p className="text-xs opacity-70">
                  Require a passcode when accessing Swonal over the internet or LAN to prevent unauthorized weight changes.
                </p>
              </div>
            </div>
            <span
              className={`px-2.5 py-1 rounded-full text-[10px] font-bold uppercase shrink-0 ${
                authEnabled
                  ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/35'
                  : 'bg-amber-500/20 text-amber-300 border border-amber-500/35'
              }`}
            >
              {authEnabled ? 'Passcode Active' : 'Unlocked'}
            </span>
          </div>

          {!envLocked && (
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                if (passcodeDraft.trim().length < 4) {
                  setAuthMsg('Passcode must be at least 4 characters.');
                  return;
                }
                const resp = await fetch('/api/auth/configure', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({
                    enabled: true,
                    passcode: passcodeDraft.trim(),
                    trustLocalhost,
                  }),
                });
                if (resp.ok) {
                  const data = (await resp.json()) as {
                    enabled: boolean;
                    trustLocalhost: boolean;
                    apiToken?: string;
                  };
                  setAuthEnabled(data.enabled);
                  setTrustLocalhost(data.trustLocalhost);
                  if (data.apiToken) setApiToken(data.apiToken);
                  setPasscodeDraft('');
                  setAuthMsg('Passcode saved! Remote browsers will now require login.');
                } else {
                  setAuthMsg('Failed to save passcode.');
                }
                setTimeout(() => setAuthMsg(null), 4000);
              }}
              className="flex flex-wrap items-center gap-2"
            >
              <input
                type="password"
                value={passcodeDraft}
                onChange={(e) => setPasscodeDraft(e.target.value)}
                placeholder={authEnabled ? 'New passcode (4+ chars)' : 'Set app passcode (4+ chars)'}
                className="flex-1 min-w-[200px] elevated-pill px-4 py-2.5 rounded-xl text-sm outline-none"
              />
              <button
                type="submit"
                className="px-4 py-2.5 rounded-xl font-black text-xs flex items-center gap-1.5"
                style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
              >
                <Lock size={14} />
                <span>{authEnabled ? 'Update Passcode' : 'Enable Lock'}</span>
              </button>
              {authEnabled && (
                <button
                  type="button"
                  onClick={async () => {
                    const resp = await fetch('/api/auth/configure', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ enabled: false }),
                    });
                    if (resp.ok) {
                      setAuthEnabled(false);
                      setAuthMsg('Passcode lock disabled.');
                      setTimeout(() => setAuthMsg(null), 3000);
                    }
                  }}
                  className="elevated-pill px-4 py-2.5 rounded-xl text-xs font-bold text-red-400"
                >
                  Disable Lock
                </button>
              )}
            </form>
          )}

          <label className="flex items-center gap-2.5 text-xs font-semibold cursor-pointer">
            <input
              type="checkbox"
              checked={trustLocalhost}
              onChange={async (e) => {
                const next = e.target.checked;
                setTrustLocalhost(next);
                await fetch('/api/auth/configure', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ trustLocalhost: next }),
                });
              }}
              className="w-4 h-4 accent-current"
              style={{ color: 'var(--accent-primary)' }}
            />
            <span>
              Allow direct Tonal kiosk touchscreen (localhost without proxy headers) without login
            </span>
          </label>

          {authEnabled && apiToken && (
            <div className="elevated-pill rounded-2xl p-4 space-y-2 text-xs">
              <div className="flex items-center justify-between gap-2">
                <span className="font-bold uppercase opacity-70">MCP / API Bearer Token</span>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => copyText('api-token', apiToken)}
                    className="surface-card px-2.5 py-1 rounded-lg font-bold flex items-center gap-1"
                  >
                    {copiedKey === 'api-token' ? <Check size={12} /> : <Copy size={12} />}
                    <span>{copiedKey === 'api-token' ? 'Copied' : 'Copy Token'}</span>
                  </button>
                  <button
                    type="button"
                    onClick={async () => {
                      await fetch('/api/auth/logout', { method: 'POST' });
                      onAuthLock?.();
                    }}
                    className="surface-card px-2.5 py-1 rounded-lg font-bold text-amber-300"
                  >
                    Lock Now
                  </button>
                </div>
              </div>
              <p className="font-mono text-[11px] opacity-80 break-all">{apiToken}</p>
            </div>
          )}

          {authMsg && <p className="text-xs font-bold text-emerald-400">{authMsg}</p>}
        </section>
      </main>
    </div>
  );
};

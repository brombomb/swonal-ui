import React, { useEffect, useMemo, useState } from 'react';
import QRCode from 'qrcode';
import {
  ArrowLeft,
  Bot,
  Check,
  ChevronDown,
  ChevronUp,
  Copy,
  Database,
  Download,
  Dumbbell,
  Globe,
  Key,
  Lock,
  Music,
  Play,
  Plus,
  QrCode,
  Radio,
  RefreshCw,
  Server,
  Share2,
  ShieldCheck,
  Smartphone,
  Trash2,
  TrendingUp,
  Users,
  Wrench,
} from 'lucide-react';
import { useSwonalState } from '../hooks/useSwonalState';
import {
  AiCoachPublicConfig,
  HomeAssistantBusStatus,
  HomeAssistantMediaPlayer,
  MusicProviderConfigs,
} from '../types';
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
    templates,
    exercises,
    refreshBootstrap,
  } = swonal;

  const accentTextColor = getContrastTextColor(activeUser?.accentColor ?? '#00F0FF');

  type SettingsTab = 'users' | 'friends' | 'ai' | 'network' | 'security';

  const [activeTab, setActiveTab] = useState<SettingsTab>(() => {
    const params = new URLSearchParams(window.location.search);
    const tab = params.get('tab') as SettingsTab;
    if (['users', 'friends', 'ai', 'network', 'security'].includes(tab)) return tab;
    return 'users';
  });

  const handleTabChange = (tab: SettingsTab) => {
    setActiveTab(tab);
    const url = new URL(window.location.href);
    url.searchParams.set('tab', tab);
    window.history.replaceState(null, '', url.toString());
  };

  // Spotify status & Redirect URI
  const [spotifyStatus, setSpotifyStatus] = useState<{
    configured: boolean;
    redirectUri: string;
    connected: boolean;
    hasOAuthToken: boolean;
  } | null>(null);
  const [copiedRedirect, setCopiedRedirect] = useState(false);
  const [spotifyExpanded, setSpotifyExpanded] = useState(false);

  // Non-Spotify Music Provider Configs (Plexamp, YouTube Music, Apple Music, Amazon Music)
  const [expandedProvider, setExpandedProvider] = useState<string | null>(null);
  const [providerConfigs, setProviderConfigs] = useState<MusicProviderConfigs | null>(null);
  const [providerConfiguredOverrides, setProviderConfiguredOverrides] = useState<Record<string, boolean>>({});
  const [providerMsg, setProviderMsg] = useState<Record<string, { ok: boolean; text: string }>>({});
  const [haMediaPlayers, setHaMediaPlayers] = useState<HomeAssistantMediaPlayer[]>([]);

  // Drafts per music provider
  const [plexampUrlDraft, setPlexampUrlDraft] = useState('');
  const [plexServerUrlDraft, setPlexServerUrlDraft] = useState('');
  const [plexTokenDraft, setPlexTokenDraft] = useState('');
  const [ytmApiUrlDraft, setYtmApiUrlDraft] = useState('');
  const [ytmAuthTokenDraft, setYtmAuthTokenDraft] = useState('');
  const [ytmHaEntityDraft, setYtmHaEntityDraft] = useState('');
  const [appleDevTokenDraft, setAppleDevTokenDraft] = useState('');
  const [appleUserTokenDraft, setAppleUserTokenDraft] = useState('');
  const [appleHaEntityDraft, setAppleHaEntityDraft] = useState('');
  const [amazonHaEntityDraft, setAmazonHaEntityDraft] = useState('');

  // User Management
  const [newUserName, setNewUserName] = useState('');
  const [newUserUnit, setNewUserUnit] = useState<'LB' | 'KG'>('LB');
  const [newUserAccent, setNewUserAccent] = useState('#00F0FF');
  const [userMgmtMsg, setUserMgmtMsg] = useState<string | null>(null);

  // Friends & Nostr P2P State
  const [nostrIdentity, setNostrIdentity] = useState<{
    userId: string;
    userName: string;
    npub: string;
    pubkeyHex: string;
    shareWorkouts: boolean;
    relays: string[];
  } | null>(null);
  const [friendsList, setFriendsList] = useState<
    Array<{
      id: string;
      userId: string;
      npub: string;
      pubkeyHex: string;
      displayName: string;
      lastSeenAt?: string | null;
    }>
  >([]);
  const [friendInputNpub, setFriendInputNpub] = useState('');
  const [friendInputName, setFriendInputName] = useState('');
  const [friendsLoading, setFriendsLoading] = useState(false);
  const [friendsSyncing, setFriendsSyncing] = useState(false);
  const [friendsMsg, setFriendsMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [friendsCopiedNpub, setFriendsCopiedNpub] = useState(false);
  const [settingsFriendQrUrl, setSettingsFriendQrUrl] = useState<string>('');

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
  const [coachTestResult, setCoachTestResult] = useState<{ ok: boolean; text: string } | null>(null);
  const [coachTesting, setCoachTesting] = useState(false);

  // Saved Workout Programs (Per-Lifter)
  const [selectedUserProgramFilter, setSelectedUserProgramFilter] = useState<string>('ALL');
  const [programActionMsg, setProgramActionMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [expandedProgramId, setExpandedProgramId] = useState<string | null>(null);
  const [sharingProgramId, setSharingProgramId] = useState<string | null>(null);
  const [queueingProgramId, setQueueingProgramId] = useState<string | null>(null);
  const [deletingProgramId, setDeletingProgramId] = useState<string | null>(null);

  const exerciseMap = useMemo(() => new Map(exercises.map((e) => [e.id, e.name])), [exercises]);

  // Hardware & Home Assistant
  const [calibrationStatus, setCalibrationStatus] = useState<{
    zero_offsets?: { left: number; right: number };
    last_calibrated_at?: number | null;
    expected_board?: string;
  } | null>(null);
  const [haStatus, setHaStatus] = useState<HomeAssistantBusStatus | null>(null);
  const [haMqttBrokerDraft, setHaMqttBrokerDraft] = useState('');
  const [haMqttUserDraft, setHaMqttUserDraft] = useState('');
  const [haMqttPassDraft, setHaMqttPassDraft] = useState('');
  const [haMqttTopicDraft, setHaMqttTopicDraft] = useState('swonal');
  const [haMqttDiscoveryDraft, setHaMqttDiscoveryDraft] = useState(true);
  const [haWebhookUrlDraft, setHaWebhookUrlDraft] = useState('');
  const [haWebhookTokenDraft, setHaWebhookTokenDraft] = useState('');
  const [haCoreUrlDraft, setHaCoreUrlDraft] = useState('');
  const [haCoreTokenDraft, setHaCoreTokenDraft] = useState('');
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
      .then((ha: HomeAssistantBusStatus | null) => {
        if (ha) {
          setHaStatus(ha);
          setHaMqttBrokerDraft(ha.mqttBrokerUrl ?? '');
          setHaMqttUserDraft(ha.mqttUsername ?? '');
          setHaMqttTopicDraft(ha.mqttTopicPrefix || 'swonal');
          setHaMqttDiscoveryDraft(ha.mqttDiscoveryEnabled ?? true);
          setHaWebhookUrlDraft(ha.webhookUrl ?? '');
          setHaCoreUrlDraft(ha.haUrl ?? '');
          if (ha.haRestConfigured) {
            void fetch('/api/integrations/ha/media-players')
              .then((res) => (res.ok ? res.json() : null))
              .then((mp: { players?: HomeAssistantMediaPlayer[] } | null) => {
                if (mp?.players) setHaMediaPlayers(mp.players);
              })
              .catch(() => {});
          }
        }
      })
      .catch(() => {});

    void fetch('/api/music/config')
      .then((r) => (r.ok ? r.json() : null))
      .then((cfgs: MusicProviderConfigs | null) => {
        if (cfgs) {
          setProviderConfigs(cfgs);
          setPlexampUrlDraft(cfgs.plexamp.plexampUrl);
          setPlexServerUrlDraft(cfgs.plexamp.plexServerUrl);
          setYtmApiUrlDraft(cfgs.youtube_music.apiUrl);
          setYtmHaEntityDraft(cfgs.youtube_music.haEntityId);
          setAppleHaEntityDraft(cfgs.apple_music.haEntityId);
          setAmazonHaEntityDraft(cfgs.amazon_music.haEntityId);
        }
      })
      .catch(() => {});
  }, []);

  const loadFriendsData = async (userId: string) => {
    if (!userId) return;
    setFriendsLoading(true);
    try {
      const [identRes, listRes] = await Promise.all([
        fetch(`/api/friends/identity/${encodeURIComponent(userId)}`),
        fetch(`/api/friends/${encodeURIComponent(userId)}`),
      ]);
      if (identRes.ok) {
        const ident = await identRes.json();
        setNostrIdentity(ident);
        if (ident.npub) {
          const qr = await QRCode.toDataURL(ident.npub, {
            width: 256,
            margin: 2,
            color: { dark: '#07090E', light: '#FFFFFF' },
          });
          setSettingsFriendQrUrl(qr);
        }
      }
      if (listRes.ok) {
        setFriendsList(await listRes.json());
      }
    } catch (err) {
      console.warn('Failed to fetch friends data:', err);
    } finally {
      setFriendsLoading(false);
    }
  };

  useEffect(() => {
    if (activeUser?.id && activeTab === 'friends') {
      void loadFriendsData(activeUser.id);
    }
  }, [activeUser?.id, activeTab]);

  const handleAddFriend = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activeUser?.id || !friendInputNpub.trim()) return;
    setFriendsMsg(null);
    try {
      const res = await fetch(`/api/friends/${encodeURIComponent(activeUser.id)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          npubOrHex: friendInputNpub.trim(),
          displayName: friendInputName.trim() || undefined,
        }),
      });
      const data = await res.json();
      if (res.ok) {
        setFriendInputNpub('');
        setFriendInputName('');
        setFriendsMsg({ ok: true, text: `Added friend ${data.displayName || 'successfully'}!` });
        void loadFriendsData(activeUser.id);
        setTimeout(() => setFriendsMsg(null), 3500);
      } else {
        setFriendsMsg({ ok: false, text: data.error || 'Failed to add friend' });
      }
    } catch (err: any) {
      setFriendsMsg({ ok: false, text: err.message || 'Request failed' });
    }
  };

  const handleRemoveFriend = async (friendId: string) => {
    if (!activeUser?.id) return;
    try {
      const res = await fetch(
        `/api/friends/${encodeURIComponent(activeUser.id)}/${encodeURIComponent(friendId)}`,
        { method: 'DELETE' }
      );
      if (res.ok) {
        setFriendsList((prev) => prev.filter((f) => f.id !== friendId));
        setFriendsMsg({ ok: true, text: 'Friend removed' });
        setTimeout(() => setFriendsMsg(null), 3000);
      }
    } catch {
      // ignore
    }
  };

  const handleToggleShareWorkouts = async (share: boolean) => {
    if (!activeUser?.id) return;
    try {
      const res = await fetch(`/api/friends/identity/${encodeURIComponent(activeUser.id)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ shareWorkouts: share }),
      });
      if (res.ok) {
        setNostrIdentity((prev) => (prev ? { ...prev, shareWorkouts: share } : prev));
      }
    } catch {
      // ignore
    }
  };

  const handleSyncFriendsNow = async () => {
    if (!activeUser?.id) return;
    setFriendsSyncing(true);
    setFriendsMsg({ ok: true, text: 'Syncing feed from Nostr relays...' });
    try {
      const res = await fetch(`/api/friends/sync/${encodeURIComponent(activeUser.id)}`, {
        method: 'POST',
      });
      const data = await res.json();
      setFriendsMsg({
        ok: true,
        text: `Sync complete! Synced ${data.syncedCount ?? 0} activity updates.`,
      });
      void loadFriendsData(activeUser.id);
      setTimeout(() => setFriendsMsg(null), 4000);
    } catch {
      setFriendsMsg({ ok: false, text: 'Sync timed out or relays unreachable' });
    } finally {
      setFriendsSyncing(false);
    }
  };

  const handleQueueProgram = async (templateId: string, templateName: string, userId: string) => {
    setQueueingProgramId(templateId);
    setProgramActionMsg(null);
    try {
      const resp = await fetch(`/api/templates/${encodeURIComponent(templateId)}/queue`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId }),
      });
      if (resp.ok) {
        setProgramActionMsg({ ok: true, text: `Queued "${templateName}" to Tonal Kiosk!` });
        setTimeout(() => setProgramActionMsg(null), 3500);
      } else {
        const err = await resp.json().catch(() => null);
        setProgramActionMsg({ ok: false, text: err?.error || 'Failed to queue program to Tonal' });
      }
    } catch (err: any) {
      setProgramActionMsg({ ok: false, text: err.message || 'Network error queueing program' });
    } finally {
      setQueueingProgramId(null);
    }
  };

  const handleShareProgramToSwocial = async (templateId: string, templateName: string, userId: string) => {
    setSharingProgramId(templateId);
    setProgramActionMsg(null);
    try {
      const resp = await fetch(
        `/api/friends/share-routine/${encodeURIComponent(userId)}/${encodeURIComponent(templateId)}`,
        { method: 'POST' }
      );
      if (resp.ok) {
        setProgramActionMsg({ ok: true, text: `Shared "${templateName}" to Swocial feed!` });
        setTimeout(() => setProgramActionMsg(null), 3500);
      } else {
        const err = await resp.json().catch(() => null);
        setProgramActionMsg({ ok: false, text: err?.error || 'Failed to share to Swocial' });
      }
    } catch (err: any) {
      setProgramActionMsg({ ok: false, text: err.message || 'Network error sharing to Swocial' });
    } finally {
      setSharingProgramId(null);
    }
  };

  const handleDeleteProgram = async (templateId: string, templateName: string) => {
    if (!window.confirm(`Are you sure you want to delete the saved program "${templateName}"?`)) {
      return;
    }
    setDeletingProgramId(templateId);
    setProgramActionMsg(null);
    try {
      const resp = await fetch(`/api/templates/${encodeURIComponent(templateId)}`, {
        method: 'DELETE',
      });
      if (resp.ok) {
        await refreshBootstrap();
        setProgramActionMsg({ ok: true, text: `Deleted program "${templateName}".` });
        setTimeout(() => setProgramActionMsg(null), 3000);
      } else {
        const err = await resp.json().catch(() => null);
        setProgramActionMsg({ ok: false, text: err?.error || 'Failed to delete program' });
      }
    } catch (err: any) {
      setProgramActionMsg({ ok: false, text: err.message || 'Network error deleting program' });
    } finally {
      setDeletingProgramId(null);
    }
  };

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
    setCoachTestResult(null);
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
      setByokModel(cfg.model);
      setByokApiKey('');
      setCoachSavedMsg('Saved AI Voice Coach configuration (persisted across restarts).');
      setTimeout(() => setCoachSavedMsg(null), 3500);
    } else {
      const err = (await res.json().catch(() => null)) as { error?: string } | null;
      setCoachTestResult({ ok: false, text: err?.error ?? 'Failed to save AI Coach config' });
    }
  };

  const clearCoachApiKey = async () => {
    const res = await fetch('/api/coach/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ clearApiKey: true }),
    });
    if (res.ok) {
      setCoachConfig((await res.json()) as AiCoachPublicConfig);
      setCoachSavedMsg('Cleared saved API key.');
      setTimeout(() => setCoachSavedMsg(null), 3000);
    }
  };

  const testCoachConnection = async () => {
    setCoachTesting(true);
    setCoachTestResult(null);
    try {
      const res = await fetch('/api/coach/test', { method: 'POST' });
      const data = (await res.json()) as {
        ok: boolean;
        latencyMs?: number;
        reply?: string;
        error?: string;
      };
      if (data.ok) {
        setCoachTestResult({
          ok: true,
          text: `Connected (${data.latencyMs ?? 0}ms): ${data.reply ?? 'Ready'}`,
        });
      } else {
        setCoachTestResult({
          ok: false,
          text: data.error ?? 'Connection test failed',
        });
      }
    } catch (err) {
      setCoachTestResult({
        ok: false,
        text: err instanceof Error ? err.message : 'Request failed',
      });
    } finally {
      setCoachTesting(false);
    }
  };

  const saveMusicProviderConfig = async (
    providerId: string,
    payload: Record<string, unknown>
  ) => {
    const res = await fetch(`/api/music/${encodeURIComponent(providerId)}/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const data = (await res.json().catch(() => null)) as {
      configs?: MusicProviderConfigs;
      providers?: Array<{ id: string; configured: boolean }>;
      error?: string;
    } | null;
    if (res.ok && data?.configs) {
      setProviderConfigs(data.configs);
      if (data.providers) {
        const map: Record<string, boolean> = {};
        data.providers.forEach((p) => {
          map[p.id] = p.configured;
        });
        setProviderConfiguredOverrides(map);
      }
      setPlexTokenDraft('');
      setYtmAuthTokenDraft('');
      setAppleDevTokenDraft('');
      setAppleUserTokenDraft('');
      setProviderMsg((prev) => ({
        ...prev,
        [providerId]: { ok: true, text: 'Configuration saved.' },
      }));
    } else {
      setProviderMsg((prev) => ({
        ...prev,
        [providerId]: { ok: false, text: data?.error ?? 'Failed to save configuration.' },
      }));
    }
  };

  const testMusicProvider = async (providerId: string) => {
    setProviderMsg((prev) => ({
      ...prev,
      [providerId]: { ok: true, text: 'Testing connection...' },
    }));
    try {
      const res = await fetch(`/api/music/${encodeURIComponent(providerId)}/test`, {
        method: 'POST',
      });
      const data = (await res.json()) as { ok: boolean; message?: string };
      setProviderMsg((prev) => ({
        ...prev,
        [providerId]: {
          ok: Boolean(data.ok),
          text: data.message ?? (data.ok ? 'Connected' : 'Connection failed'),
        },
      }));
    } catch (err) {
      setProviderMsg((prev) => ({
        ...prev,
        [providerId]: {
          ok: false,
          text: err instanceof Error ? err.message : 'Test request failed',
        },
      }));
    }
  };

  const saveHaConfig = async (e: React.FormEvent) => {
    e.preventDefault();
    const res = await fetch('/api/integrations/ha/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        mqttBrokerUrl: haMqttBrokerDraft,
        mqttUsername: haMqttUserDraft,
        mqttPassword: haMqttPassDraft || undefined,
        mqttTopicPrefix: haMqttTopicDraft || 'swonal',
        mqttDiscoveryEnabled: haMqttDiscoveryDraft,
        webhookUrl: haWebhookUrlDraft,
        webhookToken: haWebhookTokenDraft || undefined,
        haUrl: haCoreUrlDraft,
        haToken: haCoreTokenDraft || undefined,
      }),
    });
    if (res.ok) {
      const updated = (await res.json()) as HomeAssistantBusStatus;
      setHaStatus(updated);
      setHaMqttPassDraft('');
      setHaWebhookTokenDraft('');
      setHaCoreTokenDraft('');
      setDiagMsg('Saved Home Assistant configuration & reconnected.');
      setTimeout(() => setDiagMsg(null), 3500);
      if (updated.haRestConfigured) {
        void fetch('/api/integrations/ha/media-players')
          .then((r) => (r.ok ? r.json() : null))
          .then((mp: { players?: HomeAssistantMediaPlayer[] } | null) => {
            if (mp?.players) setHaMediaPlayers(mp.players);
          })
          .catch(() => {});
      }
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
      const payload = (await res.json()) as {
        event?: { mqttDelivered?: boolean; webhookDelivered?: boolean };
        bus?: HomeAssistantBusStatus;
      };
      if (payload.bus) setHaStatus(payload.bus);
      const parts: string[] = [];
      if (payload.event?.mqttDelivered) parts.push('MQTT delivered');
      if (payload.event?.webhookDelivered) parts.push('Webhook delivered');
      setDiagMsg(
        parts.length > 0
          ? `Test event published (${parts.join(' + ')}).`
          : 'Test event recorded (configure MQTT Broker or Webhook URL to deliver externally).'
      );
      setTimeout(() => setDiagMsg(null), 4000);
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

      {/* Tab Navigation */}
      <nav
        aria-label="Settings navigation"
        className="sticky top-[57px] z-20 backdrop-blur-xl border-b px-4 py-2"
        style={{ borderColor: 'var(--border-subtle)', backgroundColor: 'var(--bg-surface)' }}
      >
        <div className="max-w-3xl mx-auto flex items-center gap-1.5 overflow-x-auto no-scrollbar">
          {(
            [
              { id: 'users', label: 'Users', icon: Users },
              { id: 'friends', label: 'Swocial', icon: Share2 },
              { id: 'ai', label: 'AI', icon: Bot },
              { id: 'network', label: 'Network', icon: Globe },
              { id: 'security', label: 'Security', icon: ShieldCheck },
            ] as const
          ).map((tab) => {
            const Icon = tab.icon;
            const active = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                type="button"
                onClick={() => handleTabChange(tab.id)}
                className={`flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold transition-all shrink-0 ${
                  active
                    ? 'shadow-md border'
                    : 'elevated-pill opacity-75 hover:opacity-100 hover:bg-[var(--bg-muted)]'
                }`}
                style={
                  active
                    ? {
                        backgroundColor: 'var(--accent-primary)',
                        color: accentTextColor,
                        borderColor: 'transparent',
                      }
                    : undefined
                }
              >
                <Icon size={15} />
                <span>{tab.label}</span>
              </button>
            );
          })}
        </div>
      </nav>

      <main className="relative z-10 max-w-3xl mx-auto px-4 pt-6 space-y-6">
        {/* USERS TAB */}
        {activeTab === 'users' && (
          <div className="space-y-6">
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

            {/* Active User Switcher & Units */}
            <div className="space-y-3">
              <p className="text-xs uppercase tracking-widest font-bold opacity-70">Active Lifter</p>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {users.map((u) => {
                  const isCurrent = u.id === activeUser?.id;
                  return (
                    <div
                      key={u.id}
                      className={`rounded-2xl p-4 border flex items-center justify-between ${
                        isCurrent ? 'border-2' : 'elevated-pill opacity-85'
                      }`}
                      style={
                        isCurrent
                          ? {
                              borderColor: 'var(--accent-primary)',
                              backgroundColor: 'var(--accent-muted)',
                            }
                          : undefined
                      }
                    >
                      <button
                        type="button"
                        onClick={() => void switchUser(u.id)}
                        className="flex items-center gap-3 text-left flex-1"
                      >
                        <div
                          className="w-10 h-10 rounded-full flex items-center justify-center font-black text-sm shrink-0"
                          style={{
                            backgroundColor: u.accentColor || u.avatarColor || '#00F0FF',
                            color: getContrastTextColor(u.accentColor || u.avatarColor || '#00F0FF'),
                          }}
                        >
                          {u.name.charAt(0).toUpperCase()}
                        </div>
                        <div>
                          <p className="font-bold text-sm">{u.name}</p>
                          <p className="text-xs opacity-65">
                            {u.weightUnit} • {u.themeMode}
                          </p>
                        </div>
                      </button>

                      <div className="flex items-center gap-1.5 shrink-0 ml-2">
                        {/* Unit Toggle */}
                        <button
                          type="button"
                          onClick={() =>
                            void updateUserPreferences(u.id, {
                              weightUnit: u.weightUnit === 'LB' ? 'KG' : 'LB',
                            })
                          }
                          className="px-2 py-1 rounded-lg text-[11px] font-bold border border-[var(--border-subtle)] hover:border-[var(--accent-primary)] transition-colors"
                          title="Toggle units (LB/KG)"
                        >
                          {u.weightUnit}
                        </button>

                        {/* Accent Color Picker */}
                        <label
                          className="w-7 h-7 rounded-lg border border-[var(--border-subtle)] cursor-pointer flex items-center justify-center overflow-hidden hover:opacity-85"
                          title="Custom Accent Color"
                        >
                          <input
                            type="color"
                            value={u.accentColor || '#00F0FF'}
                            onChange={(e) =>
                              void updateUserPreferences(u.id, {
                                accentColor: e.target.value,
                              })
                            }
                            className="opacity-0 w-0 h-0 absolute pointer-events-none"
                          />
                          <div
                            className="w-4 h-4 rounded-full border border-black/20"
                            style={{ backgroundColor: u.accentColor || '#00F0FF' }}
                          />
                        </label>

                        {/* Delete User (Prevent deleting sole user) */}
                        {users.length > 1 && (
                          <button
                            type="button"
                            onClick={() => {
                              if (confirm(`Remove lifter "${u.name}"? Historical workouts will remain.`)) {
                                void deleteUser(u.id);
                              }
                            }}
                            className="p-1.5 rounded-lg text-rose-400 hover:bg-rose-500/10 transition-colors"
                            title="Remove lifter"
                          >
                            <Trash2 size={14} />
                          </button>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Create User Form */}
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                if (!newUserName.trim()) return;
                try {
                  await createUser(newUserName.trim(), newUserAccent, newUserUnit);
                  setNewUserName('');
                  setUserMgmtMsg(`Lifter "${newUserName.trim()}" added successfully!`);
                  setTimeout(() => setUserMgmtMsg(null), 3500);
                } catch (err) {
                  setUserMgmtMsg(err instanceof Error ? err.message : 'Failed to create user');
                }
              }}
              className="elevated-pill rounded-2xl p-4 space-y-3"
            >
              <p className="text-xs uppercase tracking-widest font-bold opacity-70">Add New Lifter</p>
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
                <input
                  type="text"
                  placeholder="Lifter Name"
                  value={newUserName}
                  onChange={(e) => setNewUserName(e.target.value)}
                  className="surface-card px-3 py-2 rounded-xl text-sm border font-medium focus:outline-none focus:border-[var(--accent-primary)]"
                  style={{ borderColor: 'var(--border-subtle)' }}
                  required
                />
                <select
                  value={newUserUnit}
                  onChange={(e) => setNewUserUnit(e.target.value as 'LB' | 'KG')}
                  className="surface-card px-3 py-2 rounded-xl text-sm border font-medium focus:outline-none focus:border-[var(--accent-primary)]"
                  style={{ borderColor: 'var(--border-subtle)' }}
                >
                  <option value="LB">Pounds (LB)</option>
                  <option value="KG">Kilograms (KG)</option>
                </select>
                <div className="flex items-center gap-2">
                  <label className="flex items-center gap-2 surface-card px-3 py-2 rounded-xl border flex-1 cursor-pointer">
                    <span className="text-xs opacity-75 font-medium">Accent:</span>
                    <input
                      type="color"
                      value={newUserAccent}
                      onChange={(e) => setNewUserAccent(e.target.value)}
                      className="w-5 h-5 rounded cursor-pointer border-none bg-transparent"
                    />
                  </label>
                </div>
              </div>
              <button
                type="submit"
                className="px-4 py-2 rounded-xl text-xs font-black flex items-center justify-center gap-1.5 transition-opacity hover:opacity-90 w-full sm:w-auto"
                style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
              >
                <Plus size={15} />
                <span>Create Lifter Profile</span>
              </button>
            </form>
            {userMgmtMsg && <p className="text-xs font-bold text-emerald-400">{userMgmtMsg}</p>}
          </section>

          {/* Saved Workout Programs (Per-Lifter) */}
          <section className="surface-card rounded-3xl p-6 space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-2.5">
                <Dumbbell size={22} style={{ color: 'var(--accent-primary)' }} />
                <div>
                  <h2 className="text-xl font-black">Saved Workout Programs &amp; Routines</h2>
                  <p className="text-xs opacity-70">
                    View, queue to Tonal, share to Swocial, or manage custom routines per lifter.
                  </p>
                </div>
              </div>
            </div>

            {/* Action feedback message */}
            {programActionMsg && (
              <div
                className={`p-3 rounded-xl text-xs font-bold ${
                  programActionMsg.ok
                    ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
                    : 'bg-rose-500/10 text-rose-400 border border-rose-500/20'
                }`}
              >
                {programActionMsg.text}
              </div>
            )}

            {/* Lifter filter pill tabs */}
            <div className="flex items-center gap-2 overflow-x-auto no-scrollbar pb-1">
              <button
                type="button"
                onClick={() => setSelectedUserProgramFilter('ALL')}
                className={`px-3.5 py-1.5 rounded-xl text-xs font-bold transition-all shrink-0 ${
                  selectedUserProgramFilter === 'ALL'
                    ? 'shadow-sm border'
                    : 'elevated-pill opacity-75 hover:opacity-100'
                }`}
                style={
                  selectedUserProgramFilter === 'ALL'
                    ? {
                        backgroundColor: 'var(--accent-primary)',
                        color: accentTextColor,
                        borderColor: 'transparent',
                      }
                    : undefined
                }
              >
                All Lifters ({templates.length})
              </button>
              {users.map((u) => {
                const userCount = templates.filter((t) => t.userId === u.id).length;
                const isSelected = selectedUserProgramFilter === u.id;
                return (
                  <button
                    key={u.id}
                    type="button"
                    onClick={() => setSelectedUserProgramFilter(u.id)}
                    className={`flex items-center gap-2 px-3.5 py-1.5 rounded-xl text-xs font-bold transition-all shrink-0 ${
                      isSelected
                        ? 'shadow-sm border'
                        : 'elevated-pill opacity-75 hover:opacity-100'
                    }`}
                    style={
                      isSelected
                        ? {
                            backgroundColor: 'var(--accent-primary)',
                            color: accentTextColor,
                            borderColor: 'transparent',
                          }
                        : undefined
                    }
                  >
                    <span
                      className="w-2.5 h-2.5 rounded-full shrink-0"
                      style={{ backgroundColor: u.accentColor || u.avatarColor || '#00F0FF' }}
                    />
                    <span>{u.name}</span>
                    <span className="opacity-70 text-[11px]">({userCount})</span>
                  </button>
                );
              })}
            </div>

            {/* Workout Programs List */}
            {(() => {
              const filtered =
                selectedUserProgramFilter === 'ALL'
                  ? templates
                  : templates.filter((t) => t.userId === selectedUserProgramFilter);

              if (filtered.length === 0) {
                return (
                  <div className="elevated-pill rounded-2xl p-8 text-center space-y-2 opacity-80">
                    <Dumbbell size={32} className="mx-auto opacity-40 mb-1" />
                    <p className="text-sm font-bold">No saved workout programs found</p>
                    <p className="text-xs opacity-70 max-w-sm mx-auto">
                      {selectedUserProgramFilter === 'ALL'
                        ? 'No workout routines have been created in this household yet. Build custom routines in the Companion app or ask the AI Voice Coach!'
                        : 'This lifter has not saved any workout routines yet. Build routines in Companion or switch lifter filter.'}
                    </p>
                  </div>
                );
              }

              return (
                <div className="space-y-3">
                  {filtered.map((tmpl) => {
                    const owner = users.find((u) => u.id === tmpl.userId);
                    const isExpanded = expandedProgramId === tmpl.id;
                    const isSharing = sharingProgramId === tmpl.id;
                    const isQueueing = queueingProgramId === tmpl.id;
                    const isDeleting = deletingProgramId === tmpl.id;
                    const totalMovements = (tmpl.blocks || []).reduce(
                      (sum, b) => sum + (b.items?.length || 0),
                      0
                    );

                    return (
                      <div
                        key={tmpl.id}
                        className="elevated-pill rounded-2xl p-4 transition-all space-y-3 border border-[var(--border-subtle)] hover:border-[var(--accent-primary)]/40"
                      >
                        <div className="flex flex-wrap items-start justify-between gap-3">
                          <div className="space-y-1 min-w-[200px] flex-1">
                            <div className="flex items-center gap-2">
                              <h3 className="text-base font-black tracking-tight">{tmpl.name}</h3>
                              {owner && (
                                <span
                                  className="px-2 py-0.5 rounded-full text-[10px] font-black uppercase tracking-wider flex items-center gap-1.5"
                                  style={{
                                    backgroundColor: `${owner.accentColor || '#00F0FF'}25`,
                                    color: owner.accentColor || '#00F0FF',
                                  }}
                                >
                                  <span
                                    className="w-1.5 h-1.5 rounded-full"
                                    style={{ backgroundColor: owner.accentColor || '#00F0FF' }}
                                  />
                                  {owner.name}
                                </span>
                              )}
                            </div>
                            {tmpl.description && (
                              <p className="text-xs opacity-75 line-clamp-2">{tmpl.description}</p>
                            )}
                            <div className="flex flex-wrap items-center gap-3 pt-1 text-[11px] font-medium opacity-65">
                              <span>{(tmpl.blocks || []).length} Blocks</span>
                              <span>•</span>
                              <span>{totalMovements} Movements</span>
                              {tmpl.createdAt && (
                                <>
                                  <span>•</span>
                                  <span>
                                    Created{' '}
                                    {new Date(tmpl.createdAt).toLocaleDateString(undefined, {
                                      month: 'short',
                                      day: 'numeric',
                                      year: 'numeric',
                                    })}
                                  </span>
                                </>
                              )}
                            </div>
                          </div>

                          {/* Actions */}
                          <div className="flex flex-wrap items-center gap-2 shrink-0">
                            <button
                              type="button"
                              onClick={() => void handleQueueProgram(tmpl.id, tmpl.name, tmpl.userId)}
                              disabled={isQueueing}
                              className="px-3 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1.5 transition-opacity hover:opacity-90 disabled:opacity-50"
                              style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                              title="Queue workout directly on Tonal Kiosk screen"
                            >
                              <Play size={13} fill="currentColor" />
                              <span>{isQueueing ? 'Queueing...' : 'Queue on Tonal'}</span>
                            </button>

                            <button
                              type="button"
                              onClick={() => void handleShareProgramToSwocial(tmpl.id, tmpl.name, tmpl.userId)}
                              disabled={isSharing}
                              className="surface-card px-3 py-1.5 rounded-xl text-xs font-bold border flex items-center gap-1.5 hover:border-[var(--accent-primary)] transition-colors disabled:opacity-50"
                              style={{ borderColor: 'var(--border-subtle)' }}
                              title="Share routine template with friends on Swocial"
                            >
                              <Share2 size={13} />
                              <span>{isSharing ? 'Sharing...' : 'Share to Swocial'}</span>
                            </button>

                            <button
                              type="button"
                              onClick={() => setExpandedProgramId(isExpanded ? null : tmpl.id)}
                              className="surface-card px-2.5 py-1.5 rounded-xl text-xs font-bold border flex items-center gap-1 hover:border-[var(--accent-primary)] transition-colors"
                              style={{ borderColor: 'var(--border-subtle)' }}
                              title={isExpanded ? 'Hide routine details' : 'View exercise list'}
                            >
                              {isExpanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                              <span>{isExpanded ? 'Less' : 'Details'}</span>
                            </button>

                            <button
                              type="button"
                              onClick={() => void handleDeleteProgram(tmpl.id, tmpl.name)}
                              disabled={isDeleting}
                              className="p-1.5 rounded-xl text-rose-400 hover:bg-rose-500/15 transition-colors disabled:opacity-50"
                              title="Delete routine template"
                            >
                              <Trash2 size={15} />
                            </button>
                          </div>
                        </div>

                        {/* Expanded Details: Block and Exercise breakdown */}
                        {isExpanded && (
                          <div className="mt-3 pt-3 border-t border-[var(--border-subtle)] space-y-3">
                            {(tmpl.blocks || []).map((block, bIdx) => (
                              <div
                                key={bIdx}
                                className="surface-card rounded-xl p-3 border border-[var(--border-subtle)] space-y-2 text-xs"
                              >
                                <div className="flex items-center justify-between font-bold opacity-80 pb-1 border-b border-[var(--border-subtle)]">
                                  <span className="uppercase tracking-wider text-[10px]">
                                    Block {bIdx + 1} ({block.blockType || 'STANDARD'})
                                  </span>
                                  {block.restSeconds ? (
                                    <span className="text-[10px] font-mono opacity-70">
                                      Rest: {block.restSeconds}s
                                    </span>
                                  ) : null}
                                </div>
                                <div className="space-y-1.5">
                                  {(block.items || []).map((item, iIdx) => {
                                    const exerciseName =
                                      exerciseMap.get(item.exerciseId) || item.exerciseId;
                                    return (
                                      <div
                                        key={iIdx}
                                        className="flex items-center justify-between py-1 px-2 rounded-lg bg-[var(--bg-muted)]/50"
                                      >
                                        <div className="font-semibold text-xs truncate max-w-[65%]">
                                          {exerciseName}
                                        </div>
                                        <div className="font-mono text-[11px] opacity-80 flex items-center gap-2">
                                          <span>
                                            {item.sets} × {item.targetReps} reps
                                          </span>
                                          <span>@ {item.targetWeightLb} lb</span>
                                          {item.mode && String(item.mode).toLowerCase() !== 'standard' && (
                                            <span className="px-1.5 py-0.5 rounded text-[9px] font-black uppercase bg-[var(--accent-primary)]/20 text-[var(--accent-primary)]">
                                              {item.mode}
                                            </span>
                                          )}
                                        </div>
                                      </div>
                                    );
                                  })}
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              );
            })()}
          </section>
        </div>
        )}

        {/* SWOCIAL TAB */}
        {activeTab === 'friends' && (
          <div className="space-y-6">
            {/* 1. Swocial Identity */}
            <section className="surface-card rounded-3xl p-6 space-y-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex items-center gap-2.5">
                  <Share2 size={22} style={{ color: 'var(--accent-primary)' }} />
                  <div>
                    <h2 className="text-xl font-black">Swocial</h2>
                    <p className="text-xs opacity-70">
                      Connect with gym buddies to share workouts, celebrate PRs, and exchange routine templates.
                    </p>
                  </div>
                </div>
                <button
                  type="button"
                  disabled={friendsSyncing}
                  onClick={() => void handleSyncFriendsNow()}
                  className="px-3.5 py-2 rounded-xl text-xs font-bold flex items-center gap-1.5 elevated-pill hover:opacity-90 disabled:opacity-50"
                >
                  <RefreshCw size={14} className={friendsSyncing ? 'animate-spin' : ''} />
                  <span>{friendsSyncing ? 'Syncing...' : 'Sync Feed Now'}</span>
                </button>
              </div>

              {friendsLoading ? (
                <div className="p-8 text-center text-xs opacity-60">Loading Swocial identity...</div>
              ) : nostrIdentity ? (
                <div className="space-y-4">
                  <div className="flex flex-col sm:flex-row items-center gap-5 elevated-pill rounded-2xl p-5">
                    {settingsFriendQrUrl && (
                      <div className="bg-white p-3 rounded-2xl shrink-0 flex items-center justify-center shadow-md">
                        <img src={settingsFriendQrUrl} alt="Swocial QR Code" className="w-40 h-40" />
                      </div>
                    )}
                    <div className="flex-1 space-y-2 min-w-0 w-full">
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-xs uppercase font-bold tracking-wider opacity-65">
                          Your Public Swocial Code
                        </span>
                        <button
                          type="button"
                          onClick={() => {
                            if (nostrIdentity.npub) {
                              void navigator.clipboard.writeText(nostrIdentity.npub);
                              setFriendsCopiedNpub(true);
                              setTimeout(() => setFriendsCopiedNpub(false), 2000);
                            }
                          }}
                          className="surface-card px-2.5 py-1 rounded-lg text-xs font-bold flex items-center gap-1 hover:opacity-90"
                        >
                          {friendsCopiedNpub ? <Check size={13} /> : <Copy size={13} />}
                          <span>{friendsCopiedNpub ? 'Copied!' : 'Copy npub'}</span>
                        </button>
                      </div>
                      <p className="font-mono text-sm font-black break-all text-emerald-400">
                        {nostrIdentity.npub}
                      </p>
                      <p className="font-mono text-[11px] opacity-60 break-all">
                        Hex: {nostrIdentity.pubkeyHex}
                      </p>
                    </div>
                  </div>

                  <div className="flex flex-wrap items-center justify-between gap-3 elevated-pill rounded-2xl p-4">
                    <div>
                      <p className="text-sm font-extrabold">Auto-Share Completed Workouts</p>
                      <p className="text-xs opacity-70">
                        Automatically signs and publishes workout summaries to your configured P2P relays.
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => void handleToggleShareWorkouts(!nostrIdentity.shareWorkouts)}
                      className="px-4 py-2 rounded-xl text-xs font-black transition"
                      style={
                        nostrIdentity.shareWorkouts
                          ? { backgroundColor: 'var(--accent-primary)', color: accentTextColor }
                          : undefined
                      }
                    >
                      {nostrIdentity.shareWorkouts ? 'Enabled' : 'Disabled'}
                    </button>
                  </div>

                  <div className="space-y-2">
                    <span className="text-xs uppercase font-bold tracking-wider opacity-65">
                      Store-and-Forward P2P Relays
                    </span>
                    <div className="flex flex-wrap gap-2">
                      {nostrIdentity.relays.map((relay) => (
                        <span
                          key={relay}
                          className="px-3 py-1 rounded-full text-xs font-mono font-bold elevated-pill flex items-center gap-1.5"
                        >
                          <span className="w-2 h-2 rounded-full bg-emerald-400 inline-block" />
                          <span>{relay.replace(/^wss?:\/\//, '')}</span>
                        </span>
                      ))}
                    </div>
                  </div>
                </div>
              ) : null}
            </section>

            {/* 2. Friends Roster & Add Friend */}
            <section className="surface-card rounded-3xl p-6 space-y-4">
              <div className="flex items-center gap-2.5">
                <Users size={22} style={{ color: 'var(--accent-primary)' }} />
                <div>
                  <h2 className="text-xl font-black">Connected Friends ({friendsList.length})</h2>
                  <p className="text-xs opacity-70">
                    Add friends by their npub key to see their workout feed, share routines, and react.
                  </p>
                </div>
              </div>

              {/* Add Friend Form */}
              <form onSubmit={handleAddFriend} className="space-y-3">
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
                  <input
                    type="text"
                    value={friendInputNpub}
                    onChange={(e) => setFriendInputNpub(e.target.value)}
                    placeholder="npub1... or 64-char hex pubkey"
                    className="sm:col-span-2 elevated-pill px-4 py-2.5 rounded-xl text-xs font-mono outline-none"
                  />
                  <input
                    type="text"
                    value={friendInputName}
                    onChange={(e) => setFriendInputName(e.target.value)}
                    placeholder="Display Nickname (e.g. Jeri)"
                    className="elevated-pill px-4 py-2.5 rounded-xl text-xs outline-none"
                  />
                </div>
                <div className="flex justify-end">
                  <button
                    type="submit"
                    className="px-5 py-2.5 rounded-xl font-black text-xs flex items-center gap-1.5"
                    style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                  >
                    <Plus size={15} />
                    <span>Add Friend</span>
                  </button>
                </div>
              </form>

              {friendsMsg && (
                <p
                  className={`text-xs font-bold ${
                    friendsMsg.ok ? 'text-emerald-400' : 'text-rose-400'
                  }`}
                >
                  {friendsMsg.text}
                </p>
              )}

              {/* Friends List */}
              <div className="space-y-2 pt-2">
                {friendsList.length === 0 ? (
                  <p className="text-xs opacity-60 elevated-pill rounded-2xl p-4 text-center">
                    No friends added yet. Enter a lifter's npub above to follow their workouts and exchange routines!
                  </p>
                ) : (
                  friendsList.map((f) => (
                    <div
                      key={f.id}
                      className="elevated-pill rounded-2xl p-3.5 flex items-center justify-between gap-3"
                    >
                      <div className="flex items-center gap-3 min-w-0">
                        <div
                          className="w-9 h-9 rounded-xl flex items-center justify-center font-black text-sm shrink-0"
                          style={{
                            backgroundColor: 'var(--accent-primary)',
                            color: accentTextColor,
                          }}
                        >
                          {f.displayName.slice(0, 2).toUpperCase()}
                        </div>
                        <div className="min-w-0">
                          <p className="font-extrabold text-sm truncate">{f.displayName}</p>
                          <p className="font-mono text-[11px] opacity-60 truncate">
                            {f.npub.slice(0, 16)}...{f.npub.slice(-8)}
                          </p>
                        </div>
                      </div>
                      <div className="flex items-center gap-3 shrink-0">
                        {f.lastSeenAt && (
                          <span className="text-[11px] font-bold text-emerald-400">
                            Active {new Date(f.lastSeenAt).toLocaleDateString()}
                          </span>
                        )}
                        <button
                          type="button"
                          onClick={() => void handleRemoveFriend(f.id)}
                          className="p-2 rounded-xl text-rose-400 hover:bg-rose-500/15 transition"
                          title="Remove Friend"
                        >
                          <Trash2 size={16} />
                        </button>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </section>
          </div>
        )}

        {/* AI TAB */}
        {activeTab === 'ai' && (
          <div className="space-y-6">
            <section className="surface-card rounded-3xl p-6 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-2.5">
            <div className="flex items-center gap-2.5">
              <Bot size={22} style={{ color: 'var(--accent-primary)' }} />
              <div>
                <h2 className="text-xl font-black">AI Voice Coach (BYOK)</h2>
                <p className="text-xs opacity-70">
                  Bring your own OpenAI, Anthropic, Gemini, or local Ollama model — persisted in protected server settings (`0600`).
                </p>
              </div>
            </div>
            <span className="px-2.5 py-1 rounded-full text-[10px] font-bold uppercase bg-emerald-500/15 text-emerald-400 border border-emerald-500/35 shrink-0">
              {coachConfig?.provider === 'local'
                ? 'Local Heuristic Ready'
                : coachConfig?.provider === 'ollama'
                  ? 'Ollama Configured'
                  : coachConfig?.hasApiKey
                    ? `${coachConfig.provider.toUpperCase()} Ready`
                    : `${(coachConfig?.provider ?? 'local').toUpperCase()} • Key Needed`}
            </span>
          </div>

          <form onSubmit={saveCoachConfig} className="space-y-3">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <label className="space-y-1 text-xs font-bold">
                <span className="opacity-70 uppercase">Provider</span>
                <select
                  value={byokProvider}
                  onChange={(e) => {
                    const next = e.target.value as
                      | 'local'
                      | 'openai'
                      | 'anthropic'
                      | 'gemini'
                      | 'ollama';
                    setByokProvider(next);
                    const defModel = coachConfig?.defaultModels?.[next];
                    if (defModel) setByokModel(defModel);
                  }}
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
                  placeholder="e.g. gpt-4o-mini, claude-3-5-haiku-latest"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl outline-none"
                />
              </label>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <label className="space-y-1 text-xs font-bold">
                <span className="opacity-70 uppercase flex items-center gap-1">
                  <Key size={12} /> API Key {coachConfig?.hasApiKey ? `(Saved: ${coachConfig.keyHint})` : ''}
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

            <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
              <div className="space-y-0.5 text-xs font-bold">
                {coachSavedMsg && <p className="text-emerald-400">{coachSavedMsg}</p>}
                {coachTestResult && (
                  <p className={coachTestResult.ok ? 'text-emerald-400' : 'text-rose-400'}>
                    {coachTestResult.text}
                  </p>
                )}
              </div>
              <div className="flex flex-wrap items-center gap-2">
                {coachConfig?.hasApiKey && (
                  <button
                    type="button"
                    onClick={() => void clearCoachApiKey()}
                    className="elevated-pill px-3.5 py-2.5 rounded-xl text-xs font-bold hover:opacity-90"
                  >
                    Clear Key
                  </button>
                )}
                <button
                  type="button"
                  disabled={coachTesting}
                  onClick={() => void testCoachConnection()}
                  className="elevated-pill px-4 py-2.5 rounded-xl text-xs font-bold hover:opacity-90 disabled:opacity-50"
                >
                  {coachTesting ? 'Testing...' : 'Test Connection'}
                </button>
                <button
                  type="submit"
                  className="px-5 py-2.5 rounded-xl font-black text-sm"
                  style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                >
                  Save AI Coach Config
                </button>
              </div>
            </div>
          </form>
        </section>

        {/* Model Context Protocol (MCP) Server */}
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
      </div>
      )}

        {/* NETWORK TAB */}
        {activeTab === 'network' && (
          <div className="space-y-6">
            {/* 1. Network, Base URL Override & Companion QR */}
        <section className="surface-card rounded-3xl p-6 space-y-4">
          <div className="flex items-center gap-2.5">
            <Globe size={22} style={{ color: 'var(--accent-primary)' }} />
            <div>
              <h2 className="text-xl font-black">Network &amp; Base URL Override</h2>
              <p className="text-xs opacity-70">
                Override the host used for the Companion QR code (e.g. https://swonal.example.com).
              </p>
            </div>
          </div>

          <form onSubmit={saveBaseUrlOverride} className="flex flex-wrap items-center gap-2">
            <input
              type="text"
              value={baseUrlDraft}
              onChange={(e) => setBaseUrlDraft(e.target.value)}
              placeholder="https://swonal.example.com"
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

            {/* 5. Home Assistant Event Bus & Media Bridge */}
        <section className="surface-card rounded-3xl p-6 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2.5">
              <Radio size={22} style={{ color: 'var(--accent-primary)' }} />
              <div>
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-xl font-black">Home Assistant Event Bus</h2>
                  {haStatus?.mqttConnected ? (
                    <span className="px-2.5 py-0.5 rounded-full text-[10px] font-bold uppercase bg-emerald-500/15 text-emerald-400 border border-emerald-500/35">
                      MQTT Connected{haStatus.discoveryPublished ? ' • Discovery Active' : ''}
                    </span>
                  ) : haStatus?.mqttConfigured ? (
                    <span className="px-2.5 py-0.5 rounded-full text-[10px] font-bold uppercase bg-amber-500/15 text-amber-300 border border-amber-500/35">
                      MQTT Connecting
                    </span>
                  ) : null}
                  {haStatus?.webhookConfigured && (
                    <span className="px-2.5 py-0.5 rounded-full text-[10px] font-bold uppercase bg-emerald-500/15 text-emerald-400 border border-emerald-500/35">
                      Webhook Active
                    </span>
                  )}
                  {haStatus?.haRestConfigured && (
                    <span className="px-2.5 py-0.5 rounded-full text-[10px] font-bold uppercase bg-emerald-500/15 text-emerald-400 border border-emerald-500/35">
                      HA Media Bridge Ready
                    </span>
                  )}
                </div>
                <p className="text-xs opacity-70">
                  Publish live gym telemetry, MQTT Auto-Discovery sensors, workout webhooks, and link Home Assistant media players.
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

          <form onSubmit={saveHaConfig} className="space-y-3 text-xs">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <label className="space-y-1 font-bold">
                <span className="opacity-70 uppercase">MQTT Broker URL</span>
                <input
                  type="text"
                  value={haMqttBrokerDraft}
                  onChange={(e) => setHaMqttBrokerDraft(e.target.value)}
                  placeholder="mqtt://192.168.1.10:1883"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl font-mono outline-none"
                />
              </label>
              <label className="space-y-1 font-bold">
                <span className="opacity-70 uppercase">MQTT Topic Prefix</span>
                <input
                  type="text"
                  value={haMqttTopicDraft}
                  onChange={(e) => setHaMqttTopicDraft(e.target.value)}
                  placeholder="swonal"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl font-mono outline-none"
                />
              </label>
              <label className="space-y-1 font-bold">
                <span className="opacity-70 uppercase">MQTT Username</span>
                <input
                  type="text"
                  value={haMqttUserDraft}
                  onChange={(e) => setHaMqttUserDraft(e.target.value)}
                  placeholder="Optional broker user"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl outline-none"
                />
              </label>
              <label className="space-y-1 font-bold">
                <span className="opacity-70 uppercase">
                  MQTT Password {haStatus?.hasMqttPassword ? '(Saved)' : ''}
                </span>
                <input
                  type="password"
                  value={haMqttPassDraft}
                  onChange={(e) => setHaMqttPassDraft(e.target.value)}
                  placeholder="Leave blank to keep saved password"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl outline-none"
                />
              </label>
              <label className="space-y-1 font-bold">
                <span className="opacity-70 uppercase">Webhook URL</span>
                <input
                  type="text"
                  value={haWebhookUrlDraft}
                  onChange={(e) => setHaWebhookUrlDraft(e.target.value)}
                  placeholder="http://homeassistant.local:8123/api/webhook/swonal"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl font-mono outline-none"
                />
              </label>
              <label className="space-y-1 font-bold">
                <span className="opacity-70 uppercase">
                  Webhook Bearer Token {haStatus?.hasWebhookToken ? '(Saved)' : ''}
                </span>
                <input
                  type="password"
                  value={haWebhookTokenDraft}
                  onChange={(e) => setHaWebhookTokenDraft(e.target.value)}
                  placeholder="Optional Authorization token"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl outline-none"
                />
              </label>
              <label className="space-y-1 font-bold">
                <span className="opacity-70 uppercase">Home Assistant Core URL (for Speakers)</span>
                <input
                  type="text"
                  value={haCoreUrlDraft}
                  onChange={(e) => setHaCoreUrlDraft(e.target.value)}
                  placeholder="http://homeassistant.local:8123"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl font-mono outline-none"
                />
              </label>
              <label className="space-y-1 font-bold">
                <span className="opacity-70 uppercase">
                  HA Long-Lived Access Token {haStatus?.hasHaToken ? '(Saved)' : ''}
                </span>
                <input
                  type="password"
                  value={haCoreTokenDraft}
                  onChange={(e) => setHaCoreTokenDraft(e.target.value)}
                  placeholder="Leave blank to keep saved token"
                  className="w-full elevated-pill px-3 py-2.5 rounded-xl outline-none"
                />
              </label>
            </div>

            <label className="flex items-center gap-2 font-bold cursor-pointer select-none pt-1">
              <input
                type="checkbox"
                checked={haMqttDiscoveryDraft}
                onChange={(e) => setHaMqttDiscoveryDraft(e.target.checked)}
              />
              <span>Publish Home Assistant MQTT Auto-Discovery sensors (`homeassistant/sensor/swonal_*`)</span>
            </label>

            {haStatus?.lastMqttError && (
              <p className="text-xs font-bold text-rose-400">
                MQTT Error: {haStatus.lastMqttError}
              </p>
            )}

            <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
              <span className="text-xs font-bold text-emerald-400">{diagMsg ?? ''}</span>
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onClick={() => void triggerHaTest()}
                  className="elevated-pill px-4 py-2.5 rounded-xl font-bold hover:opacity-90"
                >
                  Send Test Event
                </button>
                <button
                  type="submit"
                  className="px-5 py-2.5 rounded-xl font-black text-sm"
                  style={{ backgroundColor: 'var(--accent-primary)', color: accentTextColor }}
                >
                  Save &amp; Reconnect
                </button>
              </div>
            </div>
          </form>

          {haStatus && haStatus.recentEvents.length > 0 && (
            <div className="elevated-pill rounded-2xl p-3.5 space-y-1.5 text-[11px] font-mono">
              <p className="uppercase font-bold opacity-60">Recent Dispatched Events</p>
              {haStatus.recentEvents.slice(0, 4).map((ev) => (
                <div key={ev.id} className="flex items-center justify-between gap-2 opacity-85">
                  <span className="truncate">{ev.topic}</span>
                  <span className="shrink-0">
                    MQTT:{ev.mqttDelivered ? 'OK' : '—'} • Hook:{ev.webhookDelivered ? 'OK' : '—'}
                  </span>
                </div>
              ))}
            </div>
          )}
        </section>
          </div>
        )}

        {/* SECURITY TAB */}
        {activeTab === 'security' && (
          <>
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
          </>
        )}
      
      </main>
    </div>
  );
};

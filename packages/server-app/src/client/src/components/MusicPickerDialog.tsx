import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  CheckCircle2,
  ExternalLink,
  Pause,
  Play,
  Radio,
  RefreshCw,
  SkipBack,
  SkipForward,
  Sliders,
  Speaker,
  Star,
  Volume2,
  X,
} from 'lucide-react';
import {
  MusicDevice,
  MusicPlaybackState,
  MusicPlaylist,
  MusicProviderMeta,
  MusicTrack,
  UserProfile,
} from '../types';

interface SpeakerDaemonStatus {
  daemonOnline: boolean;
  running: boolean;
  hasCachedCredentials: boolean;
  authenticatedUser: string | null;
  deviceName: string;
  activeAudioClients?: number;
  isStreamingAudio?: boolean;
  wlanIp?: string | null;
}

interface MusicPickerDialogProps {
  dialogRef: React.RefObject<HTMLDialogElement>;
  activeUser: UserProfile | null;
  activeProviderId: string;
  musicProviders: MusicProviderMeta[];
  musicState: MusicPlaybackState;
  musicError: string | null;
  clearMusicError: () => void;
  webPlayerEnabled?: boolean;
  setWebPlayerEnabled?: (enabled: boolean) => void;
  webPlayerReady?: boolean;
  devices: MusicDevice[];
  playlists: MusicPlaylist[];
  favoritePlaylistIds?: string[];
  toggleFavoritePlaylist?: (playlistId: string) => Promise<void>;
  onRefreshDevices?: () => Promise<void>;
  updateUserPreferences: (
    userId: string,
    patch: Partial<Pick<UserProfile, 'preferredMusicProvider'>>
  ) => Promise<void>;
  transferMusicDevice: (deviceId: string) => Promise<void>;
  playPlaylist: (contextUri: string) => Promise<void>;
  fetchPlaylistTracks: (playlistId: string) => Promise<MusicTrack[]>;
  onPlayPause?: () => Promise<void>;
  onNextTrack?: () => Promise<void>;
  onPrevTrack?: () => Promise<void>;
  onSetVolume?: (volumePct: number) => Promise<void>;
}

export const MusicPickerDialog: React.FC<MusicPickerDialogProps> = ({
  dialogRef,
  activeUser,
  activeProviderId,
  musicProviders,
  musicState,
  musicError,
  clearMusicError,
  webPlayerEnabled = false,
  setWebPlayerEnabled,
  webPlayerReady = false,
  devices,
  playlists,
  favoritePlaylistIds = [],
  toggleFavoritePlaylist,
  onRefreshDevices,
  updateUserPreferences,
  transferMusicDevice,
  playPlaylist,
  fetchPlaylistTracks,
  onPlayPause,
  onNextTrack,
  onPrevTrack,
  onSetVolume,
}) => {
  const [expandedPlaylistId, setExpandedPlaylistId] = useState<string | null>(null);
  const [playlistTracks, setPlaylistTracks] = useState<MusicTrack[]>([]);
  const [loadingTracks, setLoadingTracks] = useState(false);
  const [refreshingDevices, setRefreshingDevices] = useState(false);
  const [showAllPlaylists, setShowAllPlaylists] = useState(false);
  const [speakerStatus, setSpeakerStatus] = useState<SpeakerDaemonStatus | null>(null);
  const [showPairPaste, setShowPairPaste] = useState(false);
  const [pairCodeInput, setPairCodeInput] = useState('');
  const [pairingBusy, setPairingBusy] = useState(false);
  const [pairingMsg, setPairingMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const refreshSpeakerStatus = useCallback(async () => {
    if (activeProviderId !== 'spotify') return;
    try {
      const resp = await fetch('/api/spotify/speaker-status');
      if (resp.ok) {
        setSpeakerStatus((await resp.json()) as SpeakerDaemonStatus);
      }
    } catch {
      // ignore
    }
  }, [activeProviderId]);

  useEffect(() => {
    void refreshSpeakerStatus();
  }, [refreshSpeakerStatus]);

  const handleStartSpeakerPairing = () => {
    const uid = activeUser?.id ? `?userId=${encodeURIComponent(activeUser.id)}` : '';
    window.open(`/api/spotify/speaker-auth/start${uid}`, '_blank', 'noopener,noreferrer');
    setShowPairPaste(true);
    setPairingMsg({
      ok: true,
      text: 'Authorize Spotify in the opened tab. If on a remote device, paste the 127.0.0.1:8898 redirect URL below.',
    });
  };

  const handleCompleteSpeakerPairing = async () => {
    if (!pairCodeInput.trim()) return;
    setPairingBusy(true);
    setPairingMsg(null);
    try {
      const resp = await fetch('/api/spotify/speaker-auth/complete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: activeUser?.id,
          codeOrUrl: pairCodeInput.trim(),
        }),
      });
      const data = (await resp.json()) as { success?: boolean; error?: string };
      if (!resp.ok || !data.success) {
        setPairingMsg({ ok: false, text: data.error || 'Speaker pairing failed' });
      } else {
        setPairCodeInput('');
        setShowPairPaste(false);
        setPairingMsg({ ok: true, text: 'Tonal Speakers paired with Spotify!' });
        await refreshSpeakerStatus();
        if (onRefreshDevices) await onRefreshDevices();
      }
    } catch (err) {
      setPairingMsg({
        ok: false,
        text: err instanceof Error ? err.message : 'Speaker pairing failed',
      });
    } finally {
      setPairingBusy(false);
    }
  };

  const configuredProviders = useMemo(
    () =>
      musicProviders.filter(
        (p) => p.configured || (p.id === activeProviderId && musicState.isConnected)
      ),
    [musicProviders, activeProviderId, musicState.isConnected]
  );

  const favoriteSet = useMemo(() => new Set(favoritePlaylistIds), [favoritePlaylistIds]);

  const { sortedPlaylists, favoriteCount } = useMemo(() => {
    const favs: MusicPlaylist[] = [];
    const rest: MusicPlaylist[] = [];
    for (const pl of playlists) {
      if (favoriteSet.has(pl.id)) {
        favs.push(pl);
      } else {
        rest.push(pl);
      }
    }
    return {
      sortedPlaylists: [...favs, ...rest],
      favoriteCount: favs.length,
    };
  }, [playlists, favoriteSet]);

  const visiblePlaylists =
    favoriteCount > 0 && !showAllPlaylists
      ? sortedPlaylists.filter((p) => favoriteSet.has(p.id))
      : sortedPlaylists;

  return (
    <dialog
      ref={dialogRef}
      closedby="any"
      aria-labelledby="music-dialog-title"
      className="rounded-3xl p-6 sm:p-8 max-w-2xl w-full max-h-[82vh] overflow-y-auto"
    >
      <div className="flex items-center justify-between mb-5">
        <div className="flex items-center gap-2.5">
          <Sliders size={22} style={{ color: 'var(--accent-primary)' }} />
          <h2 id="music-dialog-title" className="text-xl sm:text-2xl font-black">
            Music Picker &amp; Devices
          </h2>
        </div>
        <button
          type="button"
          onClick={() => dialogRef.current?.close()}
          className="elevated-pill p-2 rounded-xl"
          aria-label="Close Music Picker"
        >
          <X size={20} />
        </button>
      </div>

      <div className="space-y-6">
        {musicError && (
          <div className="flex items-center justify-between gap-3 px-4 py-3 rounded-2xl bg-red-500/15 border border-red-500/35 text-red-300 text-xs font-semibold">
            <span>{musicError}</span>
            <button type="button" onClick={clearMusicError} className="underline shrink-0">
              Dismiss
            </button>
          </div>
        )}

        {/* Now Playing & Quick Transport Controls */}
        <div className="elevated-pill rounded-2xl p-4 space-y-3">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-3 min-w-0">
              {musicState.artworkUrl ? (
                <img
                  src={musicState.artworkUrl}
                  alt={musicState.trackName}
                  className="w-12 h-12 rounded-xl object-cover shrink-0"
                />
              ) : (
                <div
                  className="w-12 h-12 rounded-xl flex items-center justify-center shrink-0"
                  style={{ backgroundColor: 'var(--accent-muted)' }}
                >
                  <Speaker size={20} style={{ color: 'var(--accent-primary)' }} />
                </div>
              )}
              <div className="min-w-0">
                <p className="font-extrabold text-sm truncate">
                  {musicState.trackName || 'No Track Playing'}
                </p>
                <p className="text-xs opacity-65 truncate">
                  {musicState.artistName || 'Select a workout playlist below'}
                </p>
              </div>
            </div>

            <div className="flex items-center gap-1.5 shrink-0">
              {onPrevTrack && (
                <button
                  type="button"
                  onClick={() => void onPrevTrack()}
                  className="surface-card p-2.5 rounded-xl hover:opacity-90"
                  aria-label="Previous Track"
                >
                  <SkipBack size={16} />
                </button>
              )}
              {onPlayPause && (
                <button
                  type="button"
                  onClick={() => void onPlayPause()}
                  className="p-2.5 rounded-xl font-bold text-slate-950 hover:opacity-90"
                  style={{ backgroundColor: 'var(--accent-primary)' }}
                  aria-label={musicState.isPlaying ? 'Pause' : 'Play'}
                >
                  {musicState.isPlaying ? <Pause size={17} /> : <Play size={17} />}
                </button>
              )}
              {onNextTrack && (
                <button
                  type="button"
                  onClick={() => void onNextTrack()}
                  className="surface-card p-2.5 rounded-xl hover:opacity-90"
                  aria-label="Next Track"
                >
                  <SkipForward size={16} />
                </button>
              )}
            </div>
          </div>

          {onSetVolume && (
            <div className="flex items-center gap-3 pt-1">
              <Volume2 size={15} className="opacity-70 shrink-0" />
              <input
                type="range"
                min={0}
                max={100}
                value={musicState.volumePct ?? 75}
                onChange={(e) => void onSetVolume(Number(e.target.value))}
                className="w-full accent-current cursor-pointer"
                style={{ color: 'var(--accent-primary)' }}
                aria-label="Speaker Volume"
              />
              <span className="font-mono text-xs font-bold w-10 text-right">
                {musicState.volumePct ?? 75}%
              </span>
            </div>
          )}
        </div>

        {/* Tonal Speakers Hardware Pairing Banner (Spotify Connect / librespot) */}
        {activeProviderId === 'spotify' && (
          <div className="elevated-pill rounded-2xl p-4 space-y-2.5 border border-white/10">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <Speaker size={18} style={{ color: 'var(--accent-primary)' }} />
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-extrabold uppercase tracking-wider">
                      Tonal Device Speakers
                    </span>
                    {speakerStatus?.authenticatedUser || speakerStatus?.hasCachedCredentials ? (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-500/20 text-emerald-300">
                        <CheckCircle2 size={11} />
                        Paired ({speakerStatus.authenticatedUser || 'Ready'})
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-amber-500/20 text-amber-300">
                        Needs 1-Time Pairing
                      </span>
                    )}
                  </div>
                  <p className="text-[11px] opacity-65">
                    {speakerStatus?.authenticatedUser || speakerStatus?.hasCachedCredentials
                      ? `On-device receiver active${speakerStatus?.wlanIp ? ` (${speakerStatus.wlanIp})` : ''} • Audio Bridge clients: ${speakerStatus?.activeAudioClients ?? 0}`
                      : 'Pair Tonal Speakers once with Spotify so playlists play directly out of the Tonal speakers.'}
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={handleStartSpeakerPairing}
                  className="px-3 py-1.5 rounded-xl text-xs font-extrabold text-slate-950 hover:opacity-90"
                  style={{ backgroundColor: 'var(--accent-primary)' }}
                >
                  {speakerStatus?.authenticatedUser || speakerStatus?.hasCachedCredentials
                    ? 'Re-Pair Speakers'
                    : 'Pair Tonal Speakers'}
                </button>
                <button
                  type="button"
                  onClick={() => setShowPairPaste((v) => !v)}
                  className="surface-card px-2.5 py-1.5 rounded-xl text-[11px] font-bold"
                >
                  {showPairPaste ? 'Hide Code' : 'Paste Code'}
                </button>
              </div>
            </div>

            {showPairPaste && (
              <div className="flex items-center gap-2 pt-1">
                <input
                  type="text"
                  value={pairCodeInput}
                  onChange={(e) => setPairCodeInput(e.target.value)}
                  placeholder="Paste http://127.0.0.1:8898/login?code=... URL here"
                  className="flex-1 surface-card px-3 py-2 rounded-xl text-xs font-mono"
                />
                <button
                  type="button"
                  disabled={pairingBusy || !pairCodeInput.trim()}
                  onClick={() => void handleCompleteSpeakerPairing()}
                  className="px-3 py-2 rounded-xl text-xs font-extrabold text-slate-950 disabled:opacity-40"
                  style={{ backgroundColor: 'var(--accent-primary)' }}
                >
                  {pairingBusy ? 'Pairing...' : 'Complete Pairing'}
                </button>
              </div>
            )}

            {pairingMsg && (
              <p
                className={`text-xs font-bold ${
                  pairingMsg.ok ? 'text-emerald-400' : 'text-rose-400'
                }`}
              >
                {pairingMsg.text}
              </p>
            )}
          </div>
        )}

        {/* Provider Switcher (Only shown when 2+ providers are configured) */}
        {configuredProviders.length > 1 && (
          <div>
            <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-2">
              Active Music Provider
            </p>
            <div className="flex flex-wrap gap-2">
              {configuredProviders.map((prov) => {
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
                    style={selected ? { backgroundColor: 'var(--accent-primary)' } : undefined}
                  >
                    {prov.displayName}
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {/* Connect Audio Devices */}
        <div>
          <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
            <p className="text-xs uppercase tracking-widest font-bold opacity-70">
              Output Devices
            </p>
            <div className="flex items-center gap-2">
              {onRefreshDevices && (
                <button
                  type="button"
                  disabled={refreshingDevices}
                  onClick={async () => {
                    setRefreshingDevices(true);
                    await onRefreshDevices();
                    setRefreshingDevices(false);
                  }}
                  className="elevated-pill px-2.5 py-1 rounded-lg text-[11px] font-bold flex items-center gap-1 hover:opacity-90"
                >
                  <RefreshCw size={11} className={refreshingDevices ? 'animate-spin' : ''} />
                  <span>Refresh</span>
                </button>
              )}
              {activeProviderId === 'spotify' && musicState.isConnected && setWebPlayerEnabled && (
                <button
                  type="button"
                  onClick={() => setWebPlayerEnabled(!webPlayerEnabled)}
                  className="elevated-pill px-2.5 py-1 rounded-lg text-[11px] font-bold hover:opacity-90"
                >
                  {webPlayerEnabled
                    ? webPlayerReady
                      ? 'Disable Browser Player (Ready)'
                      : 'Disable Browser Player'
                    : 'Enable Browser Web Player'}
                </button>
              )}
            </div>
          </div>
          {devices.length === 0 ? (
            <div className="text-xs opacity-80 elevated-pill rounded-2xl p-4 space-y-2">
              <p>
                {musicState.isConnected
                  ? 'Spotify Connect only lists devices where Spotify or Tonal Speakers is currently awake. Open Spotify on your phone/PC and tap Refresh, or enable the Browser Web Player.'
                  : 'Connect a music service in Settings to play and select output devices.'}
              </p>
              {musicState.isConnected && activeProviderId === 'spotify' && (
                <a
                  href="https://open.spotify.com"
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1.5 font-bold underline"
                  style={{ color: 'var(--accent-primary)' }}
                >
                  <span>Open Spotify Player</span>
                  <ExternalLink size={12} />
                </a>
              )}
            </div>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {devices.map((dev) => {
                const displayName = dev.name
                  .replace(/\s*\((?:librespot|on-device)\)/gi, '')
                  .replace(/\blibrespot\b/gi, 'Tonal Speakers')
                  .trim() || 'Tonal Speakers';
                return (
                  <button
                    key={dev.id}
                    type="button"
                    onClick={() => void transferMusicDevice(dev.id)}
                    className={`p-4 rounded-2xl text-left border flex items-center justify-between ${
                      dev.isActive ? 'border-2' : 'elevated-pill opacity-85'
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
                      <p className="font-bold text-sm">{displayName}</p>
                      <p className="text-xs opacity-65">{dev.type}</p>
                    </div>
                    <Radio
                      size={18}
                      style={{ color: dev.isActive ? 'var(--accent-primary)' : undefined }}
                    />
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Playlists (Favorites First + Toggle All) */}
        <div>
          <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
            <p className="text-xs uppercase tracking-widest font-bold opacity-70">
              Workout Playlists
            </p>
            {favoriteCount > 0 && (
              <div className="flex items-center gap-1.5 text-[11px] font-bold">
                <button
                  type="button"
                  onClick={() => setShowAllPlaylists(false)}
                  className={`px-2.5 py-1 rounded-lg ${
                    !showAllPlaylists ? 'text-slate-950' : 'elevated-pill'
                  }`}
                  style={!showAllPlaylists ? { backgroundColor: 'var(--accent-primary)' } : undefined}
                >
                  Favorites ({favoriteCount})
                </button>
                <button
                  type="button"
                  onClick={() => setShowAllPlaylists(true)}
                  className={`px-2.5 py-1 rounded-lg ${
                    showAllPlaylists ? 'text-slate-950' : 'elevated-pill'
                  }`}
                  style={showAllPlaylists ? { backgroundColor: 'var(--accent-primary)' } : undefined}
                >
                  All ({playlists.length})
                </button>
              </div>
            )}
          </div>
          {visiblePlaylists.length === 0 ? (
            <p className="text-sm opacity-70 elevated-pill rounded-2xl p-4">
              {musicState.isConnected
                ? 'No playlists found on your account.'
                : 'Connect a music service to load your playlists.'}
            </p>
          ) : (
            <div className="space-y-2">
              {visiblePlaylists.map((pl) => {
                const isExpanded = expandedPlaylistId === pl.id;
                const isFav = favoriteSet.has(pl.id);
                return (
                  <div key={pl.id} className="elevated-pill rounded-2xl overflow-hidden">
                    <div className="p-4 flex items-center justify-between gap-3">
                      {toggleFavoritePlaylist && (
                        <button
                          type="button"
                          onClick={() => void toggleFavoritePlaylist(pl.id)}
                          aria-label={isFav ? `Unfavorite ${pl.name}` : `Favorite ${pl.name}`}
                          className="p-1.5 rounded-lg hover:bg-white/10 shrink-0"
                        >
                          <Star
                            size={16}
                            style={isFav ? { color: 'var(--accent-primary)' } : undefined}
                            className={isFav ? 'fill-current' : 'opacity-45'}
                          />
                        </button>
                      )}
                      <button
                        type="button"
                        onClick={async () => {
                          if (isExpanded) {
                            setExpandedPlaylistId(null);
                            return;
                          }
                          setExpandedPlaylistId(pl.id);
                          setLoadingTracks(true);
                          const tracks = await fetchPlaylistTracks(pl.id);
                          setPlaylistTracks(tracks);
                          setLoadingTracks(false);
                        }}
                        className="text-left flex-1 min-w-0 hover:opacity-90"
                      >
                        <p className="font-bold truncate">{pl.name}</p>
                        <p className="text-xs opacity-65">
                          {pl.trackCount} tracks · {isExpanded ? 'Hide tracks' : 'View tracks'}
                        </p>
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          void playPlaylist(pl.uri);
                          dialogRef.current?.close();
                        }}
                        aria-label={`Play ${pl.name}`}
                        className="p-2.5 rounded-xl bg-white/5 hover:bg-white/10 shrink-0"
                      >
                        <Play size={18} style={{ color: 'var(--accent-primary)' }} />
                      </button>
                    </div>
                    {isExpanded && (
                      <div className="border-t border-white/10 px-4 py-2 space-y-1 max-h-56 overflow-y-auto">
                        {loadingTracks ? (
                          <p className="text-xs opacity-65 py-2">Loading tracks...</p>
                        ) : playlistTracks.length === 0 ? (
                          <p className="text-xs opacity-65 py-2">No playable tracks found.</p>
                        ) : (
                          playlistTracks.map((tr) => (
                            <button
                              key={tr.id}
                              type="button"
                              onClick={() => {
                                void playPlaylist(tr.uri);
                                dialogRef.current?.close();
                              }}
                              className="w-full py-2 px-2 rounded-xl hover:bg-white/5 flex items-center justify-between gap-2 text-left"
                            >
                              <div className="min-w-0">
                                <p className="text-xs font-bold truncate">{tr.name}</p>
                                <p className="text-[11px] opacity-60 truncate">{tr.artistName}</p>
                              </div>
                              <Play size={14} className="shrink-0 opacity-70" />
                            </button>
                          ))
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </dialog>
  );
};

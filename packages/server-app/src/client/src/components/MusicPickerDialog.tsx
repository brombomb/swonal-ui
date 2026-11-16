import React, { useState } from 'react';
import { Play, Radio, Sliders, X } from 'lucide-react';
import {
  MusicDevice,
  MusicPlaybackState,
  MusicPlaylist,
  MusicProviderMeta,
  MusicTrack,
  UserProfile,
} from '../types';

interface MusicPickerDialogProps {
  dialogRef: React.RefObject<HTMLDialogElement>;
  activeUser: UserProfile | null;
  activeProviderId: string;
  musicProviders: MusicProviderMeta[];
  musicState: MusicPlaybackState;
  musicError: string | null;
  clearMusicError: () => void;
  devices: MusicDevice[];
  playlists: MusicPlaylist[];
  updateUserPreferences: (
    userId: string,
    patch: Partial<Pick<UserProfile, 'preferredMusicProvider'>>
  ) => Promise<void>;
  transferMusicDevice: (deviceId: string) => Promise<void>;
  playPlaylist: (contextUri: string) => Promise<void>;
  fetchPlaylistTracks: (playlistId: string) => Promise<MusicTrack[]>;
}

export const MusicPickerDialog: React.FC<MusicPickerDialogProps> = ({
  dialogRef,
  activeUser,
  activeProviderId,
  musicProviders,
  musicState,
  musicError,
  clearMusicError,
  devices,
  playlists,
  updateUserPreferences,
  transferMusicDevice,
  playPlaylist,
  fetchPlaylistTracks,
}) => {
  const [expandedPlaylistId, setExpandedPlaylistId] = useState<string | null>(null);
  const [playlistTracks, setPlaylistTracks] = useState<MusicTrack[]>([]);
  const [loadingTracks, setLoadingTracks] = useState(false);

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

        {/* Provider Switcher */}
        <div>
          <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-2">
            Active Music Provider
          </p>
          <div className="flex flex-wrap gap-2">
            {musicProviders.map((prov) => {
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

        {/* Connect Audio Devices */}
        <div>
          <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-2">
            Output Devices
          </p>
          {devices.length === 0 ? (
            <p className="text-sm opacity-70 elevated-pill rounded-2xl p-4">
              {musicState.isConnected
                ? 'No active playback devices found. Open Swonal Web Player or start Tonal Gym (librespot).'
                : 'Connect a music service to play and select output devices.'}
            </p>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {devices.map((dev) => (
                <button
                  key={dev.id}
                  type="button"
                  onClick={() => void transferMusicDevice(dev.id)}
                  className={`p-4 rounded-2xl text-left border transition flex items-center justify-between ${
                    dev.isActive ? 'border-2' : 'elevated-pill opacity-80'
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
                    <p className="font-bold text-sm">{dev.name}</p>
                    <p className="text-xs opacity-65">{dev.type}</p>
                  </div>
                  <Radio
                    size={18}
                    style={{ color: dev.isActive ? 'var(--accent-primary)' : undefined }}
                  />
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Playlists */}
        <div>
          <p className="text-xs uppercase tracking-widest font-bold opacity-70 mb-2">
            Workout Playlists
          </p>
          {playlists.length === 0 ? (
            <p className="text-sm opacity-70 elevated-pill rounded-2xl p-4">
              {musicState.isConnected
                ? 'No playlists found on your account.'
                : 'Connect a music service to load your playlists.'}
            </p>
          ) : (
            <div className="space-y-2">
              {playlists.map((pl) => {
                const isExpanded = expandedPlaylistId === pl.id;
                return (
                  <div key={pl.id} className="elevated-pill rounded-2xl overflow-hidden">
                    <div className="p-4 flex items-center justify-between gap-3">
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

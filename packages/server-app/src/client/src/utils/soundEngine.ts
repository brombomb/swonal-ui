/**
 * Synthesized Web Audio API athletic workout sound cues with automatic music ducking.
 */
class SoundEngine {
  private ctx: AudioContext | null = null;
  private muted = false;

  private ensureContext(): AudioContext | null {
    if (this.muted || typeof window === 'undefined') return null;
    if (!this.ctx) {
      const AudioCtx =
        window.AudioContext ||
        (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!AudioCtx) return null;
      this.ctx = new AudioCtx();
    }
    if (this.ctx.state === 'suspended') {
      void this.ctx.resume().catch(() => {});
    }
    return this.ctx;
  }

  setMuted(muted: boolean) {
    this.muted = muted;
  }

  isMuted(): boolean {
    return this.muted;
  }

  private playTone(
    freq: number,
    durationSec: number,
    type: OscillatorType = 'sine',
    gainPeak = 0.16,
    delaySec = 0
  ) {
    const ctx = this.ensureContext();
    if (!ctx) return;

    const now = ctx.currentTime + delaySec;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = type;
    osc.frequency.setValueAtTime(freq, now);

    gain.gain.setValueAtTime(0.001, now);
    gain.gain.exponentialRampToValueAtTime(gainPeak, now + 0.015);
    gain.gain.exponentialRampToValueAtTime(0.001, now + durationSec);

    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(now);
    osc.stop(now + durationSec + 0.02);
  }

  private async triggerDuck(provider: string, userId: string, durationMs = 1100) {
    try {
      await fetch(`/api/music/${encodeURIComponent(provider)}/duck`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId, targetPct: 25, durationMs }),
      });
    } catch {
      // Ignore duck failures in offline UI unit tests
    }
  }

  playWeightEngage() {
    this.playTone(523.25, 0.12, 'triangle', 0.15, 0);
    this.playTone(659.25, 0.18, 'triangle', 0.18, 0.07);
  }

  playWeightDisengage() {
    this.playTone(440, 0.12, 'sine', 0.12, 0);
    this.playTone(329.63, 0.16, 'sine', 0.12, 0.06);
  }

  playRepComplete() {
    this.playTone(880, 0.09, 'sine', 0.11, 0);
  }

  playCountdownTick(finalTick = false) {
    this.playTone(finalTick ? 1046.5 : 660, finalTick ? 0.28 : 0.1, 'sine', 0.15, 0);
  }

  playSetComplete(provider?: string, userId?: string) {
    if (provider && userId) {
      void this.triggerDuck(provider, userId, 1200);
    }
    this.playTone(523.25, 0.14, 'triangle', 0.15, 0);
    this.playTone(659.25, 0.14, 'triangle', 0.15, 0.09);
    this.playTone(783.99, 0.26, 'triangle', 0.18, 0.18);
  }

  playPersonalRecord(provider?: string, userId?: string) {
    if (provider && userId) {
      void this.triggerDuck(provider, userId, 1800);
    }
    this.playTone(523.25, 0.14, 'triangle', 0.18, 0);
    this.playTone(659.25, 0.14, 'triangle', 0.18, 0.1);
    this.playTone(783.99, 0.14, 'triangle', 0.18, 0.2);
    this.playTone(1046.5, 0.42, 'triangle', 0.22, 0.3);
  }

  playSafetyCutout(provider?: string, userId?: string) {
    if (provider && userId) {
      void this.triggerDuck(provider, userId, 1800);
    }
    this.playTone(310, 0.18, 'sawtooth', 0.16, 0);
    this.playTone(230, 0.28, 'sawtooth', 0.18, 0.2);
  }

  playVoiceListeningStart() {
    this.playTone(587.33, 0.08, 'sine', 0.12, 0);
    this.playTone(880, 0.12, 'sine', 0.14, 0.07);
  }

  playVoiceCommandExecuted() {
    this.playTone(783.99, 0.08, 'triangle', 0.13, 0);
    this.playTone(1046.5, 0.14, 'triangle', 0.16, 0.06);
  }
}

export const soundEngine = new SoundEngine();

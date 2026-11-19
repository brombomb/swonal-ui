import { EventEmitter } from 'events';
import { DEFAULT_USERS } from '../../../prisma/seed';

export interface QueuedWorkoutPayload {
  userId: string;
  templateId: string;
  templateName: string;
  description?: string | null;
  blocks: Array<{
    blockType: 'WARMUP' | 'STRAIGHT_SET' | 'SUPERSET' | 'CIRCUIT';
    restSeconds: number;
    items: Array<{
      exerciseId: string;
      sets: number;
      targetReps: number;
      mode: 'standard' | 'eccentric' | 'chains' | 'spotter' | 'burnout';
      modifierPct: number;
      targetWeightLb: number;
    }>;
  }>;
  queuedAt: string;
}

export interface SharedSessionState {
  activeUserId: string;
  partnerModeEnabled: boolean;
  partnerUserIds: string[];
  activePartnerIndex: number;
  activeSessionId: string | null;
  activeExerciseId: string | null;
  queuedWorkout: QueuedWorkoutPayload | null;
  restTimerEndsAt: number | null;
}

class SessionCoordinator extends EventEmitter {
  private state: SharedSessionState = {
    activeUserId: DEFAULT_USERS.USER_ONE_ID,
    partnerModeEnabled: false,
    partnerUserIds: [DEFAULT_USERS.USER_ONE_ID, DEFAULT_USERS.USER_TWO_ID],
    activePartnerIndex: 0,
    activeSessionId: null,
    activeExerciseId: null,
    queuedWorkout: null,
    restTimerEndsAt: null,
  };

  getState(): SharedSessionState {
    return { ...this.state };
  }

  setActiveUser(userId: string): SharedSessionState {
    this.state.activeUserId = userId;
    const idx = this.state.partnerUserIds.indexOf(userId);
    if (idx >= 0) {
      this.state.activePartnerIndex = idx;
    }
    this.emit('state_changed', this.getState());
    return this.getState();
  }

  setPartnerMode(enabled: boolean, partnerUserIds?: string[]): SharedSessionState {
    this.state.partnerModeEnabled = enabled;
    if (partnerUserIds && partnerUserIds.length >= 2) {
      this.state.partnerUserIds = partnerUserIds;
      this.state.activePartnerIndex = 0;
      this.state.activeUserId = partnerUserIds[0];
    }
    this.emit('state_changed', this.getState());
    return this.getState();
  }

  rotatePartner(): SharedSessionState {
    if (!this.state.partnerModeEnabled || this.state.partnerUserIds.length < 2) {
      return this.getState();
    }
    this.state.activePartnerIndex =
      (this.state.activePartnerIndex + 1) % this.state.partnerUserIds.length;
    this.state.activeUserId = this.state.partnerUserIds[this.state.activePartnerIndex];
    this.emit('state_changed', this.getState());
    return this.getState();
  }

  setActiveSession(sessionId: string | null, exerciseId?: string | null): SharedSessionState {
    this.state.activeSessionId = sessionId;
    if (exerciseId !== undefined) {
      this.state.activeExerciseId = exerciseId;
    }
    this.emit('state_changed', this.getState());
    return this.getState();
  }

  setRestTimer(endsAt: number | null): SharedSessionState {
    this.state.restTimerEndsAt = endsAt;
    this.emit('state_changed', this.getState());
    return this.getState();
  }

  queueWorkout(payload: QueuedWorkoutPayload): SharedSessionState {
    this.state.activeUserId = payload.userId;
    this.state.queuedWorkout = payload;
    this.emit('workout_queued', payload);
    this.emit('state_changed', this.getState());
    return this.getState();
  }

  clearQueuedWorkout(): SharedSessionState {
    this.state.queuedWorkout = null;
    this.emit('state_changed', this.getState());
    return this.getState();
  }
}

export const sessionCoordinator = new SessionCoordinator();

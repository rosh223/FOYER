import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export type Phase = 'SCHEDULED' | 'LOBBY' | 'OPEN' | 'PAUSED' | 'SOLD_OUT' | 'CLOSED';

export interface Room {
  id: string; slug: string; name: string; venue: string | null; description: string | null; originUrl: string;
  ratePerMinute: number; maxActive: number; passTtlSeconds: number; lobbyOpensAt: string; opensAt: string;
  desiredState: string; powDifficulty: number; joinsPerIpPerMinute: number; seedCommitment: string; seed: string | null;
  epoch: number; version: number; phase: Phase; createdBy: string; createdAt: string; updatedAt: string;
}

export interface RoomRequest {
  slug?: string; name?: string; venue?: string; description?: string; originUrl?: string; ratePerMinute?: number; maxActive?: number;
  passTtlSeconds?: number; lobbyOpensAt?: string; opensAt?: string; powDifficulty?: number; joinsPerIpPerMinute?: number;
}

export interface OriginKey { id: string; label: string; last4: string; createdAt: string; revokedAt: string | null; }
export interface Epoch { epoch: number; seedCommitment: string; seed: string | null; opensAt: string; }
export interface AuditEntry { id: number; actor: string; action: string; target: string; detail: string | null; at: string; }

export interface Live {
  roomId: string; epoch: number; phase: Phase; opened: boolean; lobbyJoined: number; lobbySize: number | null; queueJoined: number;
  total: number; served: number; waiting: number; activeSessions: number; maxActive: number; ratePerMinute: number; claimRatio: number;
  admittedThisMinute: number; admittedLastMinute: number; claimedThisMinute: number; claimedLastMinute: number;
  etaForLastSeconds: number | null; leaseHolder: string; eventsBuffered: number; now: string;
}

export interface MinutePoint { t: string; joins: number; lobbyJoins: number; rejected: number; admitted: number; claimed: number; completed: number; }
export interface Overview {
  epoch: number; series: MinutePoint[];
  totals: { joins: number; lobbyJoins: number; rejectedRate: number; rejectedPow: number; rejectedOther: number; admitted: number; claimed: number; completed: number; purchases: number };
  waits: { n: number; p50: number; p90: number; max: number };
  sale: { firstJoin?: string; openedAt?: string; lobbySize?: number; soldOutAt?: string };
  networks: { subnet: string; joins: number; rejected: number }[];
}
export interface Fairness {
  epoch: number; lobbySize: number | null; seed: string | null; openedAt: string | null; commitmentMatches?: boolean;
  claimedLobbyMembers: number; spearman?: number; significanceThreshold?: number; noEarlyBirdAdvantage?: boolean;
  deciles?: { decile: number; members: number; averagePlace: number }[]; rejections: { reason: string; count: number }[];
}
export interface Rehearsal {
  roomId: string; running: boolean; humans: number; bots: number; joined: number; admitted: number; purchased: number;
  noShows: number; botAttempts: number; botRejected: number; botJoined: number; startedAt: string;
}

/** What a visitor sees: GET /api/q/{slug}. */
export interface PublicRoom {
  id: string; slug: string; name: string; venue: string | null; description: string | null; phase: Phase; lobbyOpensAt: string;
  opensAt: string; ratePerMinute: number; epoch: number; seedCommitment: string; seed: string | null; lobbySize: number | null;
  lobbyJoined: number; waiting: number; served: number; now: string;
}
export interface Challenge { token: string; difficulty: number; expiresAt: number; }
export interface Joined { ticket: string; kind: 'L' | 'Q'; index: number; visitorId: string; }
export interface QueueStatus {
  state: Phase | 'ADMITTED' | 'EXPIRED'; kind: 'L' | 'Q'; ratePerMinute: number; opensAt: string; now: string;
  lobbyNumber?: number; lobbySize?: number; position?: number; ahead?: number; etaSeconds?: number | null; pollSeconds: number;
  pass?: string; passExpiresAt?: string; continueUrl?: string;
}

/** Typed calls to the gateway. Console calls get the operator token from the interceptor. */
@Injectable({ providedIn: 'root' })
export class Api {
  private http = inject(HttpClient);
  private get<T>(url: string, params?: Record<string, string | number>) { return firstValueFrom(this.http.get<T>(url, { params })); }
  private post<T>(url: string, body: unknown = {}) { return firstValueFrom(this.http.post<T>(url, body)); }
  private put<T>(url: string, body: unknown) { return firstValueFrom(this.http.put<T>(url, body)); }

  rooms() { return this.get<Room[]>('/api/console/rooms'); }
  room(id: string) { return this.get<Room>(`/api/console/rooms/${id}`); }
  createRoom(r: RoomRequest) { return this.post<Room>('/api/console/rooms', r); }
  updateRoom(id: string, r: RoomRequest) { return this.put<Room>(`/api/console/rooms/${id}`, r); }
  setRate(id: string, ratePerMinute: number, maxActive: number) { return this.put<Room>(`/api/console/rooms/${id}/rate`, { ratePerMinute, maxActive }); }
  openNow(id: string) { return this.post<Room>(`/api/console/rooms/${id}/open-now`); }
  pause(id: string) { return this.post<Room>(`/api/console/rooms/${id}/pause`); }
  resume(id: string) { return this.post<Room>(`/api/console/rooms/${id}/resume`); }
  close(id: string) { return this.post<Room>(`/api/console/rooms/${id}/close`); }
  reset(id: string, lobbyInMinutes: number, opensInMinutes: number) { return this.post<Room>(`/api/console/rooms/${id}/reset`, { lobbyInMinutes, opensInMinutes }); }
  originKeys(id: string) { return this.get<OriginKey[]>(`/api/console/rooms/${id}/origin-keys`); }
  createOriginKey(id: string, label: string) { return this.post<{ key: OriginKey; secret: string }>(`/api/console/rooms/${id}/origin-keys`, { label }); }
  revokeOriginKey(id: string, keyId: string) { return firstValueFrom(this.http.delete<void>(`/api/console/rooms/${id}/origin-keys/${keyId}`)); }
  epochs(id: string) { return this.get<Epoch[]>(`/api/console/rooms/${id}/epochs`); }
  history(id: string) { return this.get<AuditEntry[]>(`/api/console/rooms/${id}/history`); }

  live(id: string) { return this.get<Live>(`/api/console/live/${id}`); }
  overview(id: string, minutes = 60, epoch?: number) {
    return this.get<Overview>(`/api/console/insight/rooms/${id}/overview`, epoch ? { minutes, epoch } : { minutes });
  }
  fairness(id: string, commitment: string, epoch?: number) {
    return this.get<Fairness>(`/api/console/insight/rooms/${id}/fairness`, epoch ? { commitment, epoch } : { commitment });
  }

  rehearsal(id: string) { return this.get<Rehearsal | null>(`/api/console/rooms/${id}/rehearsal`); }
  startRehearsal(id: string, humans: number, bots: number, spreadSeconds: number) {
    return this.post<Rehearsal>(`/api/console/rooms/${id}/rehearsal`, { humans, bots, spreadSeconds });
  }
  stopRehearsal(id: string) { return this.post<void>(`/api/console/rooms/${id}/rehearsal/stop`); }

  publicRoom(slug: string) { return this.get<PublicRoom>(`/api/q/${slug}`); }
  publicEpochs(slug: string) { return this.get<Epoch[]>(`/api/public/rooms/${slug}/epochs`); }
  challenge(slug: string) { return this.post<Challenge>(`/api/q/${slug}/challenge`); }
  join(slug: string, challenge: string, nonce: string, ticket?: string | null) { return this.post<Joined>(`/api/q/${slug}/join`, { challenge, nonce, ticket }); }
  status(slug: string, ticket: string) {
    return firstValueFrom(this.http.get<QueueStatus>(`/api/q/${slug}/status`, { headers: { 'X-Foyer-Ticket': ticket } }));
  }
}

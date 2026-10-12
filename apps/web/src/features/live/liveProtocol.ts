import type { DataMode } from '@vo/twin-schema';

/** Wire types of the `/ws/live` protocol (docs/api/websocket.md, ADR-37). */

export interface SubscribeSpec {
  id: string;
  site_id: string;
  floor_id?: string;
  bbox?: { min_x: number; min_y: number; max_x: number; max_y: number };
  entity_types?: string[];
  event_types?: string[];
  poses?: boolean;
  events?: boolean;
}

export interface PoseItem {
  entity_id: string;
  entity_type: string;
  event_id: string;
  occurred_at: string;
  floor_id: string | null;
  zone_id: string | null;
  x: number;
  y: number;
  z: number;
  heading_deg: number | null;
  status?: string;
  source: string;
  data_mode: DataMode;
  confidence: number | null;
}

/** Event envelope (event-envelope.v1) as delivered by both `GET /api/events` and the `event` message. */
export interface EventEnvelope {
  event_id: string;
  event_type: string;
  aggregate_type: string;
  aggregate_id: string;
  occurred_at: string;
  ingested_at?: string | null;
  source: string;
  data_mode: DataMode;
  actor_type?: string;
  actor_id?: string | null;
  location?: { site_id?: string; floor_id?: string | null; zone_id?: string | null } | null;
  payload?: Record<string, unknown> | null;
}

export type ServerMessage =
  | { type: 'ready'; heartbeat_s: number; expires_at: string; server_time: string }
  | { type: 'reauthenticated'; expires_at: string; heartbeat_s: number; server_time: string }
  | { type: 'ping'; server_time: string; expires_at: string }
  | { type: 'pong' }
  | { type: 'subscribed'; id: string; site_id: string }
  | { type: 'unsubscribed'; id: string | null; reason: string }
  | { type: 'poses'; sub: string; items: PoseItem[] }
  | { type: 'event'; sub: string; event: EventEnvelope }
  | { type: 'gap'; sub: string; dropped: number }
  | { type: 'error'; id: string | null; code: string; message: string };

export type ConnectionState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'closed';

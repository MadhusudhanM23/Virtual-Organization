import { apiFetch } from './client';

/**
 * Shared command plumbing (ARCHITECTURE.md: every command carries an `Idempotency-Key`). The acknowledgement shape
 * is the same for every command endpoint (incidents, scenarios, recommendations, approvals).
 */
export interface CommandAck<R = Record<string, unknown>> {
  command_id: string;
  status: string;
  correlation_id: string;
  result?: R;
}

export function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

/** POST a command with its Idempotency-Key. A retry of the same intent must reuse the same key. */
export function postCommand<R>(path: string, body: unknown, key: string): Promise<CommandAck<R>> {
  return apiFetch<CommandAck<R>>(path, {
    method: 'POST',
    body: body ?? {},
    headers: { 'Idempotency-Key': key },
  });
}

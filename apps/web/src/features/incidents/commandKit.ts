import { useState } from 'react';
import { ApiError } from '../../lib/api/client';
import { newIdempotencyKey, type CommandAck } from './incidentApi';

export const BUTTON_PRIMARY =
  'inline-flex items-center justify-center gap-1.5 rounded-control bg-navy-900 px-3.5 py-2 text-sm font-semibold text-white hover:bg-navy-800 disabled:opacity-60';
export const BUTTON_SECONDARY =
  'inline-flex items-center justify-center gap-1.5 rounded-control border border-line-strong bg-surface px-3.5 py-2 text-sm font-semibold text-ink-900 hover:bg-surface-sunken disabled:opacity-60';
export const BUTTON_DANGER =
  'inline-flex items-center justify-center gap-1.5 rounded-control border border-danger-line bg-danger-bg px-3.5 py-2 text-sm font-semibold text-danger-fg hover:bg-surface disabled:opacity-60';
export const INPUT =
  'w-full rounded-control border border-line-strong bg-surface px-3 py-2 text-sm text-ink-900 focus-visible:outline-2 focus-visible:outline-accent-500';

/**
 * Command plumbing: one Idempotency-Key per dialog instance (a retry of the same intent reuses it), a pending flag
 * and the last error. On 409 the owner re-reads the incident (it changed meanwhile).
 */
export function useCommand(onConflict?: () => void) {
  const [key] = useState(newIdempotencyKey);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const run = async <R>(fn: (key: string) => Promise<CommandAck<R>>): Promise<CommandAck<R> | null> => {
    setPending(true);
    setError(null);
    try {
      return await fn(key);
    } catch (e) {
      setError(e);
      if (e instanceof ApiError && e.status === 409) onConflict?.();
      return null;
    } finally {
      setPending(false);
    }
  };
  return { pending, error, run };
}

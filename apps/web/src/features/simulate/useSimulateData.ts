import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../../lib/api/client';
import { isTerminal, nextPollDelayMs } from './runPolling';
import { fetchScenarios, fetchSimulationRun, type Scenario, type SimulationRun } from './simulationApi';

export type ScenariosState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error'; message: string; forbidden: boolean }
  | { status: 'ready'; items: Scenario[] };

/** Scenarios of the selected site (docs/api/simulation.md `GET /scenarios`). */
type ScenariosSettled =
  | { key: string; status: 'ready'; items: Scenario[] }
  | { key: string; status: 'error'; message: string; forbidden: boolean };

export function useScenarios(siteId: string | null): {
  state: ScenariosState;
  reload: () => void;
  addScenario: (s: Scenario) => void;
} {
  const [settled, setSettled] = useState<ScenariosSettled | null>(null);
  const [nonce, setNonce] = useState(0);
  const key = `${siteId ?? ''}:${nonce}`;

  useEffect(() => {
    if (!siteId) return undefined;
    let cancelled = false;
    fetchScenarios(siteId)
      .then((res) => {
        if (!cancelled) setSettled({ key, status: 'ready', items: res.items });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        const forbidden = error instanceof ApiError && error.status === 403;
        const message = error instanceof Error ? error.message : 'Request failed';
        setSettled({ key, status: 'error', message, forbidden });
      });
    return () => {
      cancelled = true;
    };
  }, [siteId, key]);

  const addScenario = useCallback((s: Scenario) => {
    setSettled((prev) => (prev && prev.status === 'ready' ? { ...prev, items: [s, ...prev.items] } : prev));
  }, []);

  let state: ScenariosState;
  if (!siteId) state = { status: 'idle' };
  else if (!settled || settled.key !== key) state = { status: 'loading' };
  else if (settled.status === 'ready') state = { status: 'ready', items: settled.items };
  else state = { status: 'error', message: settled.message, forbidden: settled.forbidden };

  return { state, reload: () => setNonce((n) => n + 1), addScenario };
}

export interface RunPollResult {
  run: SimulationRun | null;
  polling: boolean;
  error: string | null;
}

/**
 * Polls `GET /api/simulation-runs/{id}` until the run reaches a terminal state (docs/api/simulation.md: a run
 * typically finishes in ~1-2s but is never assumed instant). Backs off via {@link nextPollDelayMs}; stops on
 * SUCCEEDED/FAILED or a fetch error, and whenever `runId` changes or the component unmounts.
 */
const IDLE_POLL_RESULT: RunPollResult = { run: null, polling: false, error: null };

export function useRunPolling(runId: string | null): RunPollResult {
  const [settled, setSettled] = useState<{ key: string; result: RunPollResult } | null>(null);
  const startRef = useRef<number>(0);

  useEffect(() => {
    if (!runId) return undefined;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    startRef.current = Date.now();

    const poll = () => {
      fetchSimulationRun(runId)
        .then((run) => {
          if (cancelled) return;
          setSettled({ key: runId, result: { run, polling: !isTerminal(run.status), error: null } });
          if (!isTerminal(run.status)) {
            timer = setTimeout(poll, nextPollDelayMs(Date.now() - startRef.current));
          }
        })
        .catch((error: unknown) => {
          if (cancelled) return;
          setSettled((prev) => ({
            key: runId,
            result: { run: prev?.result.run ?? null, polling: false, error: error instanceof Error ? error.message : 'Request failed' },
          }));
        });
    };
    poll();

    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [runId]);

  if (!runId) return IDLE_POLL_RESULT;
  if (!settled || settled.key !== runId) return { run: null, polling: true, error: null };
  return settled.result;
}

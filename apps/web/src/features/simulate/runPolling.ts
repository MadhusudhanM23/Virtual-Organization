import type { SimulationRun } from './simulationApi';

/**
 * Pure state machine for polling a simulation run (docs/api/simulation.md: QUEUED -> RUNNING -> SUCCEEDED|FAILED,
 * "typically completes within ~1-2s", so the UI must poll rather than assume instant completion). Separated from
 * the React hook so the transition logic is unit-testable without timers or network mocks.
 */

export interface PollState {
  run: SimulationRun | null;
  /** True while a poll request is outstanding or about to be scheduled. */
  polling: boolean;
  error: string | null;
}

export const INITIAL_POLL_STATE: PollState = { run: null, polling: false, error: null };

export function isTerminal(status: SimulationRun['status']): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED';
}

/** Next poll interval (ms) given elapsed time since the run started: fast at first, backs off, caps at 2s. */
export function nextPollDelayMs(elapsedMs: number): number {
  if (elapsedMs < 2000) return 300;
  if (elapsedMs < 10_000) return 1000;
  return 2000;
}

export type PollAction =
  | { type: 'started' }
  | { type: 'success'; run: SimulationRun }
  | { type: 'failure'; message: string };

export function pollReducer(state: PollState, action: PollAction): PollState {
  switch (action.type) {
    case 'started':
      return { ...state, polling: true, error: null };
    case 'success':
      return { run: action.run, polling: !isTerminal(action.run.status), error: null };
    case 'failure':
      return { ...state, polling: false, error: action.message };
    default:
      return state;
  }
}

/** Whether polling should continue: not yet terminal, and no fetch error has stopped it. */
export function shouldContinuePolling(state: PollState): boolean {
  if (state.error) return false;
  if (!state.run) return true;
  return !isTerminal(state.run.status);
}

import { describe, expect, it } from 'vitest';
import {
  INITIAL_POLL_STATE,
  isTerminal,
  nextPollDelayMs,
  pollReducer,
  shouldContinuePolling,
} from './runPolling';
import type { SimulationRun } from './simulationApi';

function run(status: SimulationRun['status']): SimulationRun {
  return {
    run_id: 'r1',
    scenario_id: 's1',
    site_id: 'site-1',
    status,
    requested_by: 'actor',
    created_at: '2026-10-12T10:00:00Z',
    version: 1,
  };
}

describe('isTerminal', () => {
  it('is true only for SUCCEEDED and FAILED', () => {
    expect(isTerminal('QUEUED')).toBe(false);
    expect(isTerminal('RUNNING')).toBe(false);
    expect(isTerminal('SUCCEEDED')).toBe(true);
    expect(isTerminal('FAILED')).toBe(true);
  });
});

describe('nextPollDelayMs', () => {
  it('starts fast and backs off, capping at 2s', () => {
    expect(nextPollDelayMs(0)).toBe(300);
    expect(nextPollDelayMs(5000)).toBe(1000);
    expect(nextPollDelayMs(20_000)).toBe(2000);
  });
});

describe('pollReducer', () => {
  it('marks polling on start', () => {
    const next = pollReducer(INITIAL_POLL_STATE, { type: 'started' });
    expect(next.polling).toBe(true);
    expect(next.error).toBeNull();
  });

  it('keeps polling on a non-terminal success', () => {
    const next = pollReducer(INITIAL_POLL_STATE, { type: 'success', run: run('RUNNING') });
    expect(next.polling).toBe(true);
    expect(next.run?.status).toBe('RUNNING');
  });

  it('stops polling on a terminal success', () => {
    const next = pollReducer(INITIAL_POLL_STATE, { type: 'success', run: run('SUCCEEDED') });
    expect(next.polling).toBe(false);
  });

  it('stops polling and records the message on failure', () => {
    const next = pollReducer(INITIAL_POLL_STATE, { type: 'failure', message: 'network down' });
    expect(next.polling).toBe(false);
    expect(next.error).toBe('network down');
  });
});

describe('shouldContinuePolling', () => {
  it('continues before any run is known', () => {
    expect(shouldContinuePolling(INITIAL_POLL_STATE)).toBe(true);
  });

  it('stops once a terminal run is set', () => {
    expect(shouldContinuePolling({ run: run('FAILED'), polling: false, error: null })).toBe(false);
    expect(shouldContinuePolling({ run: run('SUCCEEDED'), polling: false, error: null })).toBe(false);
  });

  it('continues for QUEUED/RUNNING', () => {
    expect(shouldContinuePolling({ run: run('QUEUED'), polling: true, error: null })).toBe(true);
    expect(shouldContinuePolling({ run: run('RUNNING'), polling: true, error: null })).toBe(true);
  });

  it('stops once an error is recorded', () => {
    expect(shouldContinuePolling({ run: null, polling: false, error: 'boom' })).toBe(false);
  });
});

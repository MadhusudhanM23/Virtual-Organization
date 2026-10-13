import { useState } from 'react';
import { CircleAlert, Loader, Play } from 'lucide-react';
import { CompareResults } from './CompareResults';
import { newIdempotencyKey, startSimulationRun, type Scenario } from './simulationApi';
import { useRunPolling } from './useSimulateData';

const BUTTON_PRIMARY =
  'inline-flex items-center justify-center gap-1.5 rounded-control bg-navy-900 px-3.5 py-2 text-sm font-semibold text-white hover:bg-navy-800 disabled:opacity-60';

const STATUS_TEXT: Record<string, string> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
};

/** Run flow for a scenario (REQ-073, Plan 6.4): trigger, poll QUEUED/RUNNING with a spinner, render the compare
 * results on SUCCEEDED, or the error message on FAILED. */
export function RunPanel({ scenario }: { scenario: Scenario }) {
  const [runId, setRunId] = useState<string | null>(null);
  const [triggerError, setTriggerError] = useState<string | null>(null);
  const [triggering, setTriggering] = useState(false);
  const poll = useRunPolling(runId);

  const trigger = async () => {
    setTriggering(true);
    setTriggerError(null);
    try {
      const ack = await startSimulationRun(scenario.id, newIdempotencyKey());
      if (ack.result) setRunId(ack.result.run_id);
    } catch (e) {
      setTriggerError(e instanceof Error ? e.message : 'Could not start the run.');
    } finally {
      setTriggering(false);
    }
  };

  const status = poll.run?.status;

  return (
    <section aria-labelledby="run-title" data-testid="run-panel" className="flex flex-col gap-4 rounded-card border border-line bg-surface p-4 shadow-card">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="run-title" className="text-base font-semibold text-ink-900">
          Run: {scenario.name}
        </h2>
        <button type="button" onClick={() => void trigger()} disabled={triggering || poll.polling} className={BUTTON_PRIMARY} data-testid="run-trigger">
          <Play aria-hidden="true" className="size-4" />
          {runId ? 'Run again' : 'Run simulation'}
        </button>
      </div>

      {triggerError ? (
        <p role="alert" className="rounded-control border border-danger-line bg-danger-bg px-3 py-2 text-sm text-danger-fg">
          {triggerError}
        </p>
      ) : null}

      {poll.error ? (
        <p role="alert" className="rounded-control border border-danger-line bg-danger-bg px-3 py-2 text-sm text-danger-fg">
          Could not read the run status: {poll.error}
        </p>
      ) : null}

      {status === 'QUEUED' || status === 'RUNNING' || (runId && !poll.run && !poll.error) ? (
        <p role="status" data-testid="run-status" className="flex items-center gap-2 text-sm text-ink-700">
          <Loader aria-hidden="true" className="size-4 animate-spin motion-reduce:animate-none" />
          {STATUS_TEXT[status ?? 'QUEUED']} — running the scenario against the baseline on the same seeds…
        </p>
      ) : null}

      {status === 'FAILED' ? (
        <div role="alert" data-testid="run-failed" className="flex items-start gap-2 rounded-control border border-danger-line bg-danger-bg px-3 py-2 text-sm text-danger-fg">
          <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <div>
            <p className="font-semibold">The run failed.</p>
            {poll.run?.error_message ? <p>{poll.run.error_message}</p> : null}
          </div>
        </div>
      ) : null}

      {status === 'SUCCEEDED' && poll.run?.results ? (
        <CompareResults results={poll.run.results} scenarioName={scenario.name} />
      ) : null}
    </section>
  );
}

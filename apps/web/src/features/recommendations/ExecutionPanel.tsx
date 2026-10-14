import { useState } from 'react';
import { Link } from 'react-router';
import { CircleAlert, CircleCheck, FlaskConical, Loader, Play, TriangleAlert } from 'lucide-react';
import { t, type MessageId } from '../../i18n';
import { newIdempotencyKey } from '../../lib/api/commands';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { Card } from '../incidents/DetailSections';
import { BUTTON_PRIMARY, BUTTON_SECONDARY, useCommand } from '../incidents/commandKit';
import { fetchSimulationRun, startSimulationRun, type RunStatus } from '../simulate/simulationApi';
import { useRunPolling } from '../simulate/useSimulateData';
import { GenerateRecommendationButton } from './parts';
import { executeRecommendation, postLink, type DryRun, type Recommendation, type StaleEntry } from './recommendationApi';
import { commandErrorId, parseStaleProblem, type StaleProblem } from './recommendationModel';
import type { RecommendationPermissions } from './useRecommendationPermissions';

function StaleTable({ stale }: { stale: StaleEntry[] }) {
  if (stale.length === 0) return null;
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[24rem] text-left text-sm" data-testid="stale-table">
        <caption className="sr-only">{t('rec.stale.caption', { count: stale.length })}</caption>
        <thead className="border-b border-warning-line text-xs">
          <tr>
            <th scope="col" className="py-1 pr-3 font-semibold">{t('rec.stale.entity')}</th>
            <th scope="col" className="py-1 pr-3 font-semibold">{t('rec.stale.baseline')}</th>
            <th scope="col" className="py-1 pr-3 font-semibold">{t('rec.stale.current')}</th>
          </tr>
        </thead>
        <tbody>
          {stale.map((s) => (
            <tr key={s.entity} data-testid="stale-row" className="border-t border-warning-line/60">
              <td className="py-1 pr-3 font-mono text-xs">{s.entity}</td>
              <td className="py-1 pr-3 font-mono text-xs">{s.baseline_version ?? '-'}</td>
              <td className="py-1 pr-3 font-mono text-xs font-semibold">{s.current_version ?? '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const RUN_STATUS_TEXT: Record<RunStatus, MessageId> = {
  QUEUED: 'rec.resimulate.QUEUED',
  RUNNING: 'rec.resimulate.RUNNING',
  SUCCEEDED: 'rec.resimulate.SUCCEEDED',
  FAILED: 'rec.resimulate.FAILED',
};

/** Stale baseline (Plan 8.3 409 `stale_baseline`): stale entities with baseline -> current versions, and Re-simulate. */
function StalePanel({ rec, problem, stale }: { rec: Recommendation; problem: StaleProblem | null; stale: StaleEntry[] }) {
  const [runId, setRunId] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [key] = useState(newIdempotencyKey);
  const poll = useRunPolling(runId);

  const resimulate = async () => {
    setStarting(true);
    setError(null);
    try {
      let scenarioId = problem?.scenarioId ?? null;
      if (!scenarioId && !problem?.resimulateHref) {
        const runRef = rec.evidence_refs.find((r) => r.kind === 'SIMULATION_RUN')?.ref;
        if (runRef) scenarioId = (await fetchSimulationRun(runRef)).scenario_id;
      }
      const ack = problem?.resimulateHref
        ? await postLink<{ run_id: string }>(problem.resimulateHref, {}, key)
        : scenarioId
          ? await startSimulationRun(scenarioId, key)
          : null;
      if (ack?.result?.run_id) setRunId(ack.result.run_id);
      else if (!ack) setError(new Error(t('rec.resimulate.noScenario')));
    } catch (e) {
      setError(e);
    } finally {
      setStarting(false);
    }
  };

  const status = poll.run?.status;
  return (
    <div role="alert" data-testid="stale-panel" className="flex flex-col gap-2 rounded-control border border-warning-line bg-warning-bg px-3 py-3 text-sm text-warning-fg">
      <p className="flex items-start gap-1.5 font-semibold">
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        {t('rec.error.stale')}
      </p>
      <p className="text-ink-900">{t('rec.stale.explain')}</p>
      <StaleTable stale={stale} />
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => void resimulate()}
          disabled={starting || poll.polling}
          className={BUTTON_SECONDARY}
          data-testid="resimulate"
        >
          <FlaskConical aria-hidden="true" className="size-4" />
          {starting ? t('rec.sending') : t('rec.resimulate')}
        </button>
        {status ? (
          <span role="status" data-testid="resimulate-status" className="flex items-center gap-1.5 text-ink-900">
            {poll.polling ? <Loader aria-hidden="true" className="size-4 motion-safe:animate-spin" /> : null}
            {t(RUN_STATUS_TEXT[status])}
          </span>
        ) : null}
      </div>
      {error ? (
        <p className="text-danger-fg">{error instanceof Error && !('status' in error) ? error.message : t(commandErrorId(error))}</p>
      ) : null}
      {status === 'SUCCEEDED' && runId ? (
        <div className="flex flex-wrap items-center gap-2">
          <Link to="/twin/simulation" className="text-sm font-semibold text-accent-700 hover:underline">
            {t('rec.resimulate.open')}
          </Link>
          <GenerateRecommendationButton siteId={rec.site_id} runId={runId} />
        </div>
      ) : null}
    </div>
  );
}

/**
 * Execute + outcome (REQ-095/096): Execute on an APPROVED recommendation, live step status while EXECUTING (polled by
 * the page), the VERIFIED / PARTIAL / FAILED outcome with its evidence, and the stale-baseline path on 409.
 */
export function ExecutionPanel({
  rec,
  dryRun,
  perms,
  polling,
  onExecuted,
  onConflict,
}: {
  rec: Recommendation;
  dryRun: DryRun | null;
  perms: RecommendationPermissions;
  polling: boolean;
  onExecuted: () => void;
  onConflict: () => void;
}) {
  const fmt = useSiteFormat();
  const exec = useCommand(onConflict);
  const staleProblem = parseStaleProblem(exec.error);
  const stale = staleProblem?.stale.length ? staleProblem.stale : rec.status === 'STALE' ? (dryRun?.stale ?? []) : [];
  const showStale = staleProblem !== null || rec.status === 'STALE';

  const onExecute = async () => {
    const ack = await exec.run((key) => executeRecommendation(rec.recommendation_id, key));
    if (ack) onExecuted();
  };

  const e = rec.execution;
  const outcome = rec.status === 'VERIFIED' || rec.status === 'PARTIAL' || rec.status === 'FAILED' ? rec.status : null;

  return (
    <Card title={t('rec.section.execution')} testId="rec-execution">
      <div className="flex flex-col gap-3">
        {rec.status === 'APPROVED' && !staleProblem ? (
          perms.canExecute ? (
            <div>
              <button
                type="button"
                onClick={() => void onExecute()}
                disabled={exec.pending}
                className={BUTTON_PRIMARY}
                data-testid="execute"
              >
                {exec.pending ? <Loader aria-hidden="true" className="size-4 motion-safe:animate-spin" /> : <Play aria-hidden="true" className="size-4" />}
                {exec.pending ? t('rec.sending') : t('rec.execute')}
              </button>
              <p className="mt-1 text-xs text-ink-600">{t('rec.execute.explain')}</p>
            </div>
          ) : (
            <p className="text-sm text-ink-600">{t('rec.execute.viewOnly')}</p>
          )
        ) : null}

        {exec.error && !staleProblem ? (
          <p role="alert" className="text-sm text-danger-fg" data-testid="execute-error">
            {t(commandErrorId(exec.error))}
          </p>
        ) : null}

        {rec.status === 'EXECUTING' || polling ? (
          <p role="status" data-testid="execution-progress" className="flex items-center gap-2 text-sm text-ink-700">
            <Loader aria-hidden="true" className="size-4 motion-safe:animate-spin motion-reduce:animate-none" />
            {t('rec.execution.running')}
          </p>
        ) : null}

        {outcome ? (
          <div
            role={outcome === 'VERIFIED' ? 'status' : 'alert'}
            data-testid="execution-outcome"
            data-outcome={outcome}
            className={`flex items-start gap-2 rounded-control border px-3 py-2 text-sm ${
              outcome === 'VERIFIED'
                ? 'border-success-line bg-success-bg text-success-fg'
                : outcome === 'PARTIAL'
                  ? 'border-warning-line bg-warning-bg text-warning-fg'
                  : 'border-danger-line bg-danger-bg text-danger-fg'
            }`}
          >
            {outcome === 'VERIFIED' ? (
              <CircleCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            ) : (
              <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            )}
            <p className="font-semibold">{t(`rec.outcome.${outcome}`)}</p>
          </div>
        ) : null}

        {e ? (
          <div className="flex flex-col gap-1 text-xs text-ink-700" data-testid="execution-evidence">
            <p>
              {t('rec.execution.command')}: <span className="font-mono">{e.command_id}</span>
            </p>
            {e.started_at ? <p>{t('rec.execution.started', { time: fmt.dateTime(e.started_at) })}</p> : null}
            {e.finished_at ? <p>{t('rec.execution.finished', { time: fmt.dateTime(e.finished_at) })}</p> : null}
            {e.steps.length > 0 ? (
              <ul className="mt-1 flex flex-col gap-0.5">
                {e.steps.map((s) => (
                  <li key={s.seq} data-testid="execution-step" data-status={s.status}>
                    #{s.seq} <span className="font-semibold">{s.status}</span>
                    {s.observed_event_id ? (
                      <>
                        {' · '}
                        {t('rec.plan.observed')}: <span className="font-mono">{s.observed_event_id}</span>
                      </>
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : rec.status !== 'APPROVED' && !outcome && !showStale && rec.status !== 'EXECUTING' ? (
          <p className="text-sm text-ink-600">{t('rec.execution.none')}</p>
        ) : null}

        {showStale ? <StalePanel rec={rec} problem={staleProblem} stale={stale} /> : null}
      </div>
    </Card>
  );
}

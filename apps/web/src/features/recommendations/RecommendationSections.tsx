import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { ArrowRight, CircleCheck, CircleX, Crosshair, ExternalLink, Loader, RefreshCw, TriangleAlert } from 'lucide-react';
import { t } from '../../i18n';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { Card, Row } from '../incidents/DetailSections';
import { BUTTON_SECONDARY } from '../incidents/commandKit';
import { fetchAllEntities } from '../twin/twinApi';
import { useTwinStore } from '../twin/twinStore';
import type { DryRun, Recommendation } from './recommendationApi';
import {
  commandErrorId,
  diffChanged,
  diffValue,
  evidenceHref,
  formatSigned,
  isFeasible,
  objectiveDelta,
} from './recommendationModel';

function useNum() {
  const fmt = useSiteFormat();
  return (v: number, digits = 2) => fmt.number(v, { maximumFractionDigits: digits });
}

export function RationaleCard({ rec }: { rec: Recommendation }) {
  return (
    <Card title={t('rec.section.rationale')} testId="rec-rationale">
      <p className="text-sm whitespace-pre-line text-ink-900">{rec.rationale}</p>
      <h3 className="mt-4 text-xs font-semibold tracking-wide text-ink-700 uppercase">{t('rec.section.evidence')}</h3>
      {rec.evidence_refs.length === 0 ? (
        <p className="mt-1 text-sm text-ink-600">{t('rec.evidence.empty')}</p>
      ) : (
        <ul className="mt-1 flex flex-col gap-1.5" data-testid="rec-evidence">
          {rec.evidence_refs.map((e) => {
            const href = evidenceHref(e.kind, e.ref);
            return (
              <li key={`${e.kind}:${e.ref}`} className="flex flex-wrap items-center gap-2 text-sm">
                <span className="rounded-md border border-line-strong bg-surface-sunken px-1.5 py-0.5 font-mono text-[0.7rem] text-ink-700">
                  {e.kind}
                </span>
                {href ? (
                  <Link to={href} className="inline-flex items-center gap-1 font-semibold text-accent-700 hover:underline">
                    {e.label || e.ref}
                    <ExternalLink aria-hidden="true" className="size-3.5" />
                  </Link>
                ) : (
                  <span className="text-ink-900">{e.label || e.ref}</span>
                )}
                <span className="font-mono text-xs text-ink-600">{e.ref}</span>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}

export function FeasibilityCard({ rec }: { rec: Recommendation }) {
  const num = useNum();
  const f = rec.feasibility;
  const ok = isFeasible(f.status);
  return (
    <Card title={t('rec.section.feasibility')} testId="rec-feasibility">
      <dl>
        <Row label={t('rec.feasibility.status')}>
          <span
            className={`inline-flex items-center gap-1 font-semibold ${ok ? 'text-success-fg' : 'text-danger-fg'}`}
            data-feasibility={f.status}
          >
            {ok ? <CircleCheck aria-hidden="true" className="size-4" /> : <CircleX aria-hidden="true" className="size-4" />}
            {f.status}
          </span>
        </Row>
        <Row label={t('rec.feasibility.solver')}>{f.solver || '-'}</Row>
        <Row label={t('rec.feasibility.gap')}>
          <span className="font-mono">{f.optimality_gap === null ? '-' : num(f.optimality_gap, 6)}</span>
        </Row>
        <Row label={t('rec.feasibility.solveTime')}>
          <span className="font-mono">
            {f.solve_time_ms === null ? '-' : t('rec.feasibility.ms', { ms: num(f.solve_time_ms, 0) })}
          </span>
        </Row>
        {Object.keys(rec.policy_versions ?? {}).length > 0 ? (
          <Row label={t('rec.feasibility.policies')}>
            <span className="font-mono text-xs">
              {Object.entries(rec.policy_versions)
                .map(([k, v]) => `${k} v${v}`)
                .join(', ')}
            </span>
          </Row>
        ) : null}
      </dl>
      {!ok ? (
        <p role="note" className="mt-2 text-sm text-danger-fg">
          {t('rec.feasibility.notFeasible')}
        </p>
      ) : null}
    </Card>
  );
}

export function ObjectiveCard({ rec }: { rec: Recommendation }) {
  const num = useNum();
  const d = objectiveDelta(rec.objective_before, rec.objective_after);
  const b = rec.objective_before;
  const a = rec.objective_after;
  return (
    <Card title={t('rec.section.objective')} testId="rec-objective">
      {b || a ? (
        <div className="flex flex-col gap-2">
          <p className="text-xs font-medium text-ink-600">{(b ?? a)?.name}</p>
          <p className="flex flex-wrap items-center gap-2 font-mono text-lg font-semibold text-ink-900">
            <span data-testid="objective-before">{b ? `${num(b.value)} ${b.unit}` : '-'}</span>
            <ArrowRight aria-label={t('rec.objective.to')} className="size-4 text-ink-600" />
            <span data-testid="objective-after">{a ? `${num(a.value)} ${a.unit}` : '-'}</span>
          </p>
          {d ? (
            <p className="text-sm text-ink-700" data-testid="objective-delta">
              {t('rec.objective.delta', { delta: formatSigned(d.delta, (v) => num(v)), unit: d.unit })}
            </p>
          ) : null}
          <p className="text-xs text-ink-600">{t('rec.objective.note')}</p>
        </div>
      ) : (
        <p className="text-sm text-ink-600">{t('rec.objective.none')}</p>
      )}
    </Card>
  );
}

export function ConstraintsCard({ rec }: { rec: Recommendation }) {
  const violated = rec.constraints.filter((c) => !c.satisfied).length;
  return (
    <Card title={t('rec.section.constraints')} testId="rec-constraints">
      {rec.constraints.length === 0 ? (
        <p className="text-sm text-ink-600">{t('rec.constraints.empty')}</p>
      ) : (
        <div
          // Scrollable region: keyboard reachable (axe scrollable-region-focusable).
          // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
          tabIndex={0}
          role="region"
          aria-label={t('rec.section.constraints')}
          className="overflow-x-auto"
        >
          <table className="w-full min-w-[32rem] text-left text-sm">
            <caption className="pb-2 text-left text-xs text-ink-600">
              {t('rec.constraints.caption', { total: rec.constraints.length, violated })}
            </caption>
            <thead className="border-b border-line text-xs text-ink-700">
              <tr>
                <th scope="col" className="py-1 pr-3 font-semibold">{t('rec.constraints.name')}</th>
                <th scope="col" className="py-1 pr-3 font-semibold">{t('rec.constraints.kind')}</th>
                <th scope="col" className="py-1 pr-3 font-semibold">{t('rec.constraints.result')}</th>
                <th scope="col" className="py-1 pr-3 font-semibold">{t('rec.constraints.detail')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {rec.constraints.map((c) => (
                <tr
                  key={c.name}
                  data-testid="rec-constraint"
                  data-satisfied={c.satisfied ? 'true' : 'false'}
                  className={c.satisfied ? undefined : 'bg-danger-bg'}
                >
                  <td className="py-1.5 pr-3 font-semibold text-ink-900">{c.name}</td>
                  <td className="py-1.5 pr-3 font-mono text-xs">{c.kind}</td>
                  <td className="py-1.5 pr-3">
                    {c.satisfied ? (
                      <span className="inline-flex items-center gap-1 text-success-fg">
                        <CircleCheck aria-hidden="true" className="size-4" />
                        {t('rec.constraints.satisfied')}
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 font-semibold text-danger-fg">
                        <TriangleAlert aria-hidden="true" className="size-4" />
                        {t('rec.constraints.violated')}
                      </span>
                    )}
                  </td>
                  <td className="py-1.5 pr-3 text-ink-700">{c.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

/** Resolves an entity code on the site's twin and focuses it; false when the code is not a twin entity. */
function useShowInTwin(siteId: string) {
  const navigate = useNavigate();
  const select = useTwinStore((s) => s.select);
  const setCameraMode = useTwinStore((s) => s.setCameraMode);
  return async (code: string): Promise<boolean> => {
    let entity = useTwinStore.getState().entities.find((e) => e.code === code);
    if (!entity) {
      try {
        entity = (await fetchAllEntities(siteId)).find((e) => e.code === code);
      } catch {
        entity = undefined;
      }
    }
    if (!entity) return false;
    select(entity.id);
    setCameraMode('focus');
    void navigate('/twin/live');
    return true;
  };
}

export function AffectedEntitiesCard({ rec }: { rec: Recommendation }) {
  const show = useShowInTwin(rec.site_id);
  const [busy, setBusy] = useState<string | null>(null);
  const [missing, setMissing] = useState<string | null>(null);
  const onShow = async (code: string) => {
    setBusy(code);
    setMissing(null);
    const ok = await show(code);
    setBusy(null);
    if (!ok) setMissing(code);
  };
  return (
    <Card title={t('rec.section.entities')} testId="rec-entities">
      {rec.affected_entities.length === 0 ? (
        <p className="text-sm text-ink-600">{t('rec.entities.empty')}</p>
      ) : (
        <ul className="flex flex-col">
          {rec.affected_entities.map((code) => {
            const v = rec.baseline_entity_versions?.[code];
            return (
              <li
                key={code}
                data-testid="rec-entity"
                className="flex flex-wrap items-center justify-between gap-2 border-t border-line py-2 first:border-t-0"
              >
                <div>
                  <p className="font-mono text-sm text-ink-900">{code}</p>
                  {v !== undefined ? (
                    <p className="text-xs text-ink-600">{t('rec.entities.baseline', { version: v })}</p>
                  ) : null}
                </div>
                <button
                  type="button"
                  onClick={() => void onShow(code)}
                  disabled={busy !== null}
                  className="inline-flex items-center gap-1 rounded-control border border-line-strong px-2 py-1 text-xs font-semibold text-accent-700 hover:bg-accent-50 disabled:opacity-60"
                >
                  {busy === code ? (
                    <Loader aria-hidden="true" className="size-3.5 motion-safe:animate-spin" />
                  ) : (
                    <Crosshair aria-hidden="true" className="size-3.5" />
                  )}
                  {t('rec.entities.show', { code })}
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {missing ? (
        <p role="status" className="mt-2 text-xs text-ink-600">
          {t('rec.entities.notInTwin', { code: missing })}
        </p>
      ) : null}
    </Card>
  );
}

export function DryRunCard({
  dryRun,
  loading,
  error,
  onRefresh,
}: {
  dryRun: DryRun | null;
  loading: boolean;
  error: unknown;
  onRefresh: () => void;
}) {
  const fmt = useSiteFormat();
  return (
    <Card
      title={t('rec.section.dryRun')}
      testId="rec-dry-run"
      actions={
        <button type="button" onClick={onRefresh} disabled={loading} className={BUTTON_SECONDARY}>
          <RefreshCw aria-hidden="true" className={loading ? 'size-4 motion-safe:animate-spin' : 'size-4'} />
          {t('rec.dryRun.refresh')}
        </button>
      }
    >
      <p className="mb-2 text-xs text-ink-600">{t('rec.dryRun.explain')}</p>
      {loading && !dryRun ? (
        <p role="status" className="flex items-center gap-2 text-sm text-ink-600">
          <Loader aria-hidden="true" className="size-4 motion-safe:animate-spin" />
          {t('rec.dryRun.loading')}
        </p>
      ) : error && !dryRun ? (
        <p role="alert" className="text-sm text-danger-fg">
          {t('rec.dryRun.failed')} {t(commandErrorId(error))}
        </p>
      ) : dryRun ? (
        <div className="flex flex-col gap-3">
          <p className="text-xs text-ink-600">
            {t('rec.dryRun.computed', { time: fmt.dateTime(dryRun.computed_at) })}
          </p>
          {dryRun.stale.length > 0 ? (
            <p role="alert" className="flex items-start gap-1.5 rounded-control border border-warning-line bg-warning-bg px-3 py-2 text-sm text-warning-fg">
              <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              {t('rec.dryRun.stale', { count: dryRun.stale.length })}
            </p>
          ) : null}
          {dryRun.diff.length === 0 ? (
            <p className="text-sm text-ink-600">{t('rec.dryRun.empty')}</p>
          ) : (
            <div
              // Scrollable region: keyboard reachable (axe scrollable-region-focusable).
              // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
              tabIndex={0}
              role="region"
              aria-label={t('rec.section.dryRun')}
              className="overflow-x-auto"
            >
              <table className="w-full min-w-[32rem] text-left text-sm" data-testid="dry-run-table">
                <caption className="sr-only">{t('rec.dryRun.caption', { count: dryRun.diff.length })}</caption>
                <thead className="border-b border-line text-xs text-ink-700">
                  <tr>
                    <th scope="col" className="py-1 pr-3 font-semibold">{t('rec.dryRun.entity')}</th>
                    <th scope="col" className="py-1 pr-3 font-semibold">{t('rec.dryRun.field')}</th>
                    <th scope="col" className="py-1 pr-3 font-semibold">{t('rec.dryRun.before')}</th>
                    <th scope="col" className="py-1 pr-3 font-semibold">{t('rec.dryRun.after')}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {dryRun.diff.map((row) => {
                    const changed = diffChanged(row.before, row.after);
                    return (
                      <tr key={`${row.entity}:${row.field}`} data-testid="dry-run-row" data-changed={changed ? 'true' : undefined}>
                        <td className="py-1.5 pr-3 font-mono text-xs text-ink-900">{row.entity}</td>
                        <td className="py-1.5 pr-3 font-mono text-xs">{row.field}</td>
                        <td className="py-1.5 pr-3 font-mono text-xs text-ink-700">
                          {changed ? <del>{diffValue(row.before)}</del> : diffValue(row.before)}
                        </td>
                        <td className="py-1.5 pr-3 font-mono text-xs font-semibold text-ink-900">
                          {changed ? <ins className="no-underline">{diffValue(row.after)}</ins> : diffValue(row.after)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ) : null}
    </Card>
  );
}

const STEP_TONE: Record<string, string> = {
  APPLIED: 'border-info-line bg-info-bg text-info-fg',
  VERIFIED: 'border-success-line bg-success-bg text-success-fg',
  COMPENSATED: 'border-warning-line bg-warning-bg text-warning-fg',
  FAILED: 'border-danger-line bg-danger-bg text-danger-fg',
};

function params(p: Record<string, unknown> | null | undefined): string {
  if (!p || Object.keys(p).length === 0) return '-';
  return Object.entries(p)
    .map(([k, v]) => `${k}=${diffValue(v)}`)
    .join(', ');
}

export function ExecutionPlanCard({ rec }: { rec: Recommendation }) {
  const steps = rec.execution_plan?.steps ?? [];
  const status = new Map((rec.execution?.steps ?? []).map((s) => [s.seq, s]));
  return (
    <Card title={t('rec.section.plan')} testId="rec-plan">
      {steps.length === 0 ? (
        <p className="text-sm text-ink-600">{t('rec.plan.empty')}</p>
      ) : (
        <ol className="flex flex-col gap-2">
          {steps.map((s) => {
            const live = status.get(s.seq);
            return (
              <li key={s.seq} data-testid="rec-plan-step" data-step-status={live?.status} className="rounded-control border border-line p-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-xs text-ink-600">#{s.seq}</span>
                  <span className="font-mono text-xs font-semibold text-ink-900">{s.command_type}</span>
                  <span className="font-mono text-xs text-ink-700">{s.target}</span>
                  {live ? (
                    <span
                      className={`ml-auto rounded-md border px-1.5 py-0.5 font-mono text-[0.7rem] font-semibold ${STEP_TONE[live.status] ?? 'border-line bg-surface-sunken text-ink-700'}`}
                    >
                      {live.status}
                    </span>
                  ) : null}
                </div>
                <p className="mt-1 font-mono text-xs break-words text-ink-700">{params(s.params)}</p>
                <p className="mt-1 text-xs text-ink-600">
                  {t('rec.plan.compensation')}:{' '}
                  <span className="font-mono">
                    {s.compensation ? `${s.compensation.command_type} (${params(s.compensation.params)})` : t('rec.plan.noCompensation')}
                  </span>
                </p>
                {live?.observed_event_id ? (
                  <p className="mt-1 text-xs text-ink-600">
                    {t('rec.plan.observed')}: <span className="font-mono">{live.observed_event_id}</span>
                  </p>
                ) : null}
              </li>
            );
          })}
        </ol>
      )}
    </Card>
  );
}

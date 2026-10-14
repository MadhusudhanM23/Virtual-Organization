import { Link, useParams } from 'react-router';
import { ArrowLeft, Lightbulb } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { LoadState } from '../kpi/parts';
import { ApprovalPanel } from './ApprovalPanel';
import { ExecutionPanel } from './ExecutionPanel';
import { RecStatusBadge } from './parts';
import type { Recommendation } from './recommendationApi';
import { recTypeLabel } from './recommendationModel';
import {
  AffectedEntitiesCard,
  ConstraintsCard,
  DryRunCard,
  ExecutionPlanCard,
  FeasibilityCard,
  ObjectiveCard,
  RationaleCard,
} from './RecommendationSections';
import { useRecommendationDetail, type RecommendationDetail } from './useRecommendationDetail';
import { useRecommendationPermissions } from './useRecommendationPermissions';

/**
 * Route `/recommendations/:id` (REQ-090, REQ-093..096, Plan 8.4): rationale and evidence, feasibility, objective
 * before -> after, constraints, affected entities, the dry-run diff (shown before Approve is enabled), the execution
 * plan with compensation, the approval panel and the execution outcome. Every value is read from the API.
 */
export default function RecommendationDetailPage() {
  const { id } = useParams();
  // Keyed by id: following "supersedes" or a re-evaluation starts from a clean state.
  return <RecommendationRoute key={id ?? ''} id={id} />;
}

function RecommendationRoute({ id }: { id: string | undefined }) {
  const detail = useRecommendationDetail(id);
  const rec = detail.rec;
  useDocumentTitle(rec ? recTypeLabel(rec.type) : t('rec.list.title'));

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 p-4 md:p-8" data-testid="recommendation-detail">
      <Link
        to="/recommendations"
        className="inline-flex items-center gap-1.5 self-start text-sm font-semibold text-accent-700 hover:underline"
      >
        <ArrowLeft aria-hidden="true" className="size-4" />
        {t('rec.back')}
      </Link>
      {detail.loading ? (
        <LoadState loading error={null} onRetry={() => undefined} label={t('rec.loading')} />
      ) : !rec ? (
        detail.error ? (
          <LoadState loading={false} error={detail.error} onRetry={() => void detail.reload()} label={t('rec.loading')} />
        ) : (
          <EmptyState icon={Lightbulb} description={t('rec.error.notFound')} />
        )
      ) : (
        <Detail rec={rec} detail={detail} />
      )}
    </div>
  );
}

function Detail({ rec, detail }: { rec: Recommendation; detail: RecommendationDetail }) {
  const fmt = useSiteFormat();
  const perms = useRecommendationPermissions();
  const reload = () => void detail.reload();

  return (
    <>
      <header className="flex flex-col gap-3">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <h1 className="min-w-0 text-2xl font-semibold tracking-tight text-ink-900">{recTypeLabel(rec.type)}</h1>
          <div className="flex flex-wrap items-center gap-2" data-testid="rec-badges">
            <RecStatusBadge status={rec.status} />
            <DataModeBadge dataMode={rec.data_mode} />
          </div>
        </div>
        <p className="text-xs text-ink-600">
          {t('rec.meta', {
            created: fmt.dateTime(rec.created_at),
            proposer: rec.proposed_by === perms.actorRef ? t('rec.you') : rec.proposed_by,
            version: rec.version,
          })}
        </p>
        {rec.supersedes_id ? (
          <p className="text-xs text-ink-600">
            {t('rec.supersedes')}{' '}
            <Link
              to={`/recommendations/${encodeURIComponent(rec.supersedes_id)}`}
              className="font-mono font-semibold text-accent-700 hover:underline"
            >
              {rec.supersedes_id}
            </Link>
          </p>
        ) : null}
        <p className="text-xs text-ink-600">{t('rec.truthNote')}</p>
      </header>

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
        <div className="flex min-w-0 flex-col gap-5">
          <RationaleCard rec={rec} />
          <DryRunCard
            dryRun={detail.dryRun}
            loading={detail.dryRunLoading}
            error={detail.dryRunError}
            onRefresh={() => void detail.reloadDryRun()}
          />
          <ConstraintsCard rec={rec} />
          <ExecutionPlanCard rec={rec} />
        </div>
        <div className="flex min-w-0 flex-col gap-5">
          <ApprovalPanel
            rec={rec}
            approval={detail.approval}
            dryRun={detail.dryRun}
            perms={perms}
            onChanged={reload}
          />
          <ExecutionPanel
            rec={rec}
            dryRun={detail.dryRun}
            perms={perms}
            polling={detail.polling}
            onExecuted={() => {
              reload();
              detail.startPolling();
            }}
            onConflict={reload}
          />
          <ObjectiveCard rec={rec} />
          <FeasibilityCard rec={rec} />
          <AffectedEntitiesCard rec={rec} />
        </div>
      </div>
    </>
  );
}

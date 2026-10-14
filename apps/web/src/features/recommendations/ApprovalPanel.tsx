import { useId, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { Check, Clock, RotateCcw, ShieldCheck, X } from 'lucide-react';
import { t } from '../../i18n';
import { newIdempotencyKey } from '../../lib/api/commands';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { Card, Row } from '../incidents/DetailSections';
import { BUTTON_DANGER, BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT, useCommand } from '../incidents/commandKit';
import { useNow } from '../live/useNow';
import {
  approveApproval,
  postLink,
  proposedId,
  proposeRecommendation,
  rejectApproval,
  type Approval,
  type DryRun,
  type ProposeResult,
  type Recommendation,
} from './recommendationApi';
import {
  APPROVAL_LABEL,
  commandErrorId,
  expiryInfo,
  formatCountdown,
  isFeasible,
  parseExpiredProblem,
  sodMessageId,
  type ExpiredProblem,
} from './recommendationModel';
import type { RecommendationPermissions } from './useRecommendationPermissions';

/** The API's limit for a decision reason (docs/api/recommendations.md: at most 500 characters). */
const MAX_REASON = 500;

/**
 * Approval panel (REQ-094): approve / reject (reason required), the expiry countdown from the served `expires_at`, the
 * separation-of-duties message when the viewer proposed it, and re-evaluation after expiry. Approve stays disabled
 * until the dry-run diff has been computed and shown (REQ-093).
 */
export function ApprovalPanel({
  rec,
  approval,
  dryRun,
  perms,
  onChanged,
}: {
  rec: Recommendation;
  approval: Approval | null;
  dryRun: DryRun | null;
  perms: RecommendationPermissions;
  onChanged: () => void;
}) {
  const fmt = useSiteFormat();
  const navigate = useNavigate();
  const now = useNow(1000);
  const reasonId = useId();
  const approve = useCommand(onChanged);
  const reject = useCommand(onChanged);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState(false);
  const expiredProblem: ExpiredProblem | null =
    parseExpiredProblem(approve.error) ?? parseExpiredProblem(reject.error);
  const [reEvaluating, setReEvaluating] = useState(false);
  const [reEvalError, setReEvalError] = useState<unknown>(null);
  const [reEvalKey] = useState(newIdempotencyKey);
  const [done, setDone] = useState<string | null>(null);

  const expiresAt = approval?.expires_at ?? rec.expires_at;
  const expiry = expiryInfo(expiresAt, now);
  const approvalStatus = approval?.status ?? null;
  const pending = approvalStatus ? approvalStatus === 'PENDING' : rec.status === 'PROPOSED';
  const expired =
    expiry.expired || approvalStatus === 'EXPIRED' || rec.status === 'EXPIRED' || expiredProblem !== null;
  const sodId = sodMessageId(rec, perms.actorRef);
  const dryRunShown = dryRun !== null && dryRun.stale.length === 0;
  const feasible = isFeasible(rec.feasibility.status);
  const approvalId = rec.approval_id;
  const busy = approve.pending || reject.pending;
  const canDecide = perms.canApprove && pending && !expired && approvalId !== null;
  const approveEnabled = canDecide && sodId === null && dryRunShown && feasible && !busy;

  const blockers: string[] = [];
  if (canDecide && !dryRunShown) blockers.push(t('rec.approve.needsDryRun'));
  if (canDecide && !feasible) blockers.push(t('rec.approve.notFeasible'));

  const onApprove = async () => {
    if (!approvalId) return;
    setDone(null);
    const ack = await approve.run((key) => approveApproval(approvalId, key));
    if (ack) {
      setDone(t('rec.approve.done'));
      onChanged();
    }
  };

  const onReject = async (e: FormEvent) => {
    e.preventDefault();
    if (!approvalId) return;
    if (!reason.trim()) {
      setReasonError(true);
      return;
    }
    setReasonError(false);
    setDone(null);
    const ack = await reject.run((key) => rejectApproval(approvalId, reason.trim(), key));
    if (ack) {
      setRejecting(false);
      setDone(t('rec.reject.done'));
      onChanged();
    }
  };

  const runRef = rec.evidence_refs.find((r) => r.kind === 'SIMULATION_RUN')?.ref ?? null;
  const canReEvaluate = perms.canPropose && (expiredProblem?.href || runRef);
  const onReEvaluate = async () => {
    setReEvaluating(true);
    setReEvalError(null);
    try {
      const ack =
        expiredProblem?.href
          ? await postLink<ProposeResult>(
              expiredProblem.href,
              expiredProblem.body ?? { site_id: rec.site_id, simulation_run_id: runRef },
              reEvalKey,
            )
          : await proposeRecommendation(rec.site_id, runRef ?? '', reEvalKey);
      const id = proposedId(ack);
      void navigate(id ? `/recommendations/${encodeURIComponent(id)}` : '/recommendations');
    } catch (e) {
      setReEvalError(e);
    } finally {
      setReEvaluating(false);
    }
  };

  return (
    <Card title={t('rec.section.approval')} testId="rec-approval">
      <div className="flex flex-col gap-3">
        <dl>
          <Row label={t('rec.approval.status')}>
            <span data-testid="approval-status" data-status={approvalStatus ?? undefined}>
              {approvalStatus ? t(APPROVAL_LABEL[approvalStatus]) : rec.requires_approval.required ? '-' : t('rec.approval.notRequired')}
            </span>
          </Row>
          <Row label={t('rec.approval.class')}>
            <span className="font-mono text-xs">{rec.requires_approval.approval_class || '-'}</span>
          </Row>
          <Row label={t('rec.approval.sod')}>
            {rec.requires_approval.separation_of_duties ? t('rec.approval.sodYes') : t('rec.approval.sodNo')}
          </Row>
          {approval?.decided_at ? (
            <Row label={t('rec.approval.decided')}>
              {fmt.dateTime(approval.decided_at)}
              {approval.decided_by ? ` · ${approval.decided_by === perms.actorRef ? t('rec.you') : approval.decided_by}` : ''}
            </Row>
          ) : null}
          {approval?.reason ? <Row label={t('rec.approval.reason')}>{approval.reason}</Row> : null}
          <Row label={t('rec.approval.expiry')}>
            <span
              className={`inline-flex items-center gap-1 ${expired ? 'font-semibold text-warning-fg' : ''}`}
              data-testid="approval-expiry"
            >
              <Clock aria-hidden="true" className="size-3.5" />
              {t(expired ? 'rec.expiry.expired' : expiry.messageId, {
                time: expiry.remainingMs !== null ? formatCountdown(expiry.remainingMs) : '',
              })}
              {expiresAt ? <span className="text-xs text-ink-600">({fmt.dateTime(expiresAt)})</span> : null}
            </span>
          </Row>
        </dl>

        {pending && sodId ? (
          <p role="note" data-testid="sod-message" className="flex items-start gap-1.5 rounded-control border border-info-line bg-info-bg px-3 py-2 text-sm text-info-fg">
            <ShieldCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            {t(sodId)}
          </p>
        ) : null}

        {done ? (
          <p role="status" className="text-sm font-semibold text-success-fg" data-testid="approval-done">
            {done}
          </p>
        ) : null}

        {canDecide ? (
          <>
            {blockers.length > 0 ? (
              <ul className="list-disc pl-5 text-xs text-ink-700" id={`${reasonId}-blockers`}>
                {blockers.map((b) => (
                  <li key={b}>{b}</li>
                ))}
              </ul>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => void onApprove()}
                disabled={!approveEnabled}
                aria-describedby={blockers.length > 0 ? `${reasonId}-blockers` : undefined}
                className={BUTTON_PRIMARY}
                data-testid="approve"
              >
                <Check aria-hidden="true" className="size-4" />
                {approve.pending ? t('rec.sending') : t('rec.approve')}
              </button>
              <button
                type="button"
                onClick={() => setRejecting((v) => !v)}
                aria-expanded={rejecting}
                disabled={busy}
                className={BUTTON_SECONDARY}
                data-testid="reject-open"
              >
                <X aria-hidden="true" className="size-4" />
                {t('rec.reject')}
              </button>
            </div>
            {rejecting ? (
              <form onSubmit={(e) => void onReject(e)} className="flex flex-col gap-2" noValidate>
                <label htmlFor={reasonId} className="text-xs font-semibold text-ink-900">
                  {t('rec.reject.reason')}
                </label>
                <textarea
                  id={reasonId}
                  value={reason}
                  required
                  maxLength={MAX_REASON}
                  rows={3}
                  aria-invalid={reasonError}
                  aria-describedby={reasonError ? `${reasonId}-err` : undefined}
                  onChange={(e) => setReason(e.target.value)}
                  className={INPUT}
                  data-testid="reject-reason"
                />
                {reasonError ? (
                  <p id={`${reasonId}-err`} role="alert" className="text-xs text-danger-fg">
                    {t('rec.reject.reasonRequired')}
                  </p>
                ) : null}
                <div>
                  <button type="submit" disabled={busy} className={BUTTON_DANGER} data-testid="reject-submit">
                    {reject.pending ? t('rec.sending') : t('rec.reject.submit')}
                  </button>
                </div>
              </form>
            ) : null}
          </>
        ) : pending && !expired && !perms.canApprove ? (
          <p className="text-sm text-ink-600">{t('rec.approve.viewOnly')}</p>
        ) : null}

        {approve.error && !parseExpiredProblem(approve.error) ? (
          <p role="alert" className="text-sm text-danger-fg" data-testid="approval-error">
            {t(commandErrorId(approve.error))}
          </p>
        ) : null}
        {reject.error && !parseExpiredProblem(reject.error) ? (
          <p role="alert" className="text-sm text-danger-fg">
            {t(commandErrorId(reject.error))}
          </p>
        ) : null}

        {expired && (pending || rec.status === 'EXPIRED' || expiredProblem) ? (
          <div role="alert" data-testid="approval-expired" className="flex flex-col gap-2 rounded-control border border-warning-line bg-warning-bg px-3 py-2 text-sm text-warning-fg">
            <p>{t('rec.error.expired')}</p>
            {canReEvaluate ? (
              <div>
                <button
                  type="button"
                  onClick={() => void onReEvaluate()}
                  disabled={reEvaluating}
                  className={BUTTON_SECONDARY}
                  data-testid="re-evaluate"
                >
                  <RotateCcw aria-hidden="true" className="size-4" />
                  {reEvaluating ? t('rec.sending') : t('rec.reEvaluate')}
                </button>
              </div>
            ) : null}
            {reEvalError ? <p className="text-danger-fg">{t(commandErrorId(reEvalError))}</p> : null}
          </div>
        ) : null}
      </div>
    </Card>
  );
}

import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Lightbulb, Loader } from 'lucide-react';
import { StatusPill } from '../../components/StatusPill';
import { t } from '../../i18n';
import { newIdempotencyKey } from '../../lib/api/commands';
import { BUTTON_PRIMARY } from '../incidents/commandKit';
import { proposedId, proposeRecommendation } from './recommendationApi';
import { commandErrorId, statusLabelId, statusTone } from './recommendationModel';
import { useRecommendationPermissions } from './useRecommendationPermissions';

export function RecStatusBadge({ status }: { status: string }) {
  const id = statusLabelId(status);
  return (
    <span data-testid="rec-status" data-status={status}>
      <StatusPill tone={statusTone(status)}>{id ? t(id) : status}</StatusPill>
    </span>
  );
}

/**
 * "Generate recommendation" from a SUCCEEDED simulation run (`POST /api/recommendations`, PROPOSE_RECOMMENDATION).
 * One Idempotency-Key per run: a retry of the same intent reuses it. Opens the new recommendation on success.
 */
export function GenerateRecommendationButton({ siteId, runId }: { siteId: string; runId: string }) {
  const perms = useRecommendationPermissions();
  const navigate = useNavigate();
  const [keyFor, setKeyFor] = useState(() => ({ runId, key: newIdempotencyKey() }));
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  if (!perms.canPropose) return null;

  const key = keyFor.runId === runId ? keyFor.key : null;
  const propose = async () => {
    const k = key ?? newIdempotencyKey();
    if (!key) setKeyFor({ runId, key: k });
    setPending(true);
    setError(null);
    try {
      const ack = await proposeRecommendation(siteId, runId, k);
      const id = proposedId(ack);
      void navigate(id ? `/recommendations/${encodeURIComponent(id)}` : '/recommendations');
    } catch (e) {
      setError(e);
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="flex flex-col items-end gap-1">
      <button
        type="button"
        onClick={() => void propose()}
        disabled={pending}
        className={BUTTON_PRIMARY}
        data-testid="generate-recommendation"
      >
        {pending ? (
          <Loader aria-hidden="true" className="size-4 motion-safe:animate-spin" />
        ) : (
          <Lightbulb aria-hidden="true" className="size-4" />
        )}
        {pending ? t('rec.generate.pending') : t('rec.generate')}
      </button>
      {error ? (
        <p role="alert" className="text-xs text-danger-fg" data-testid="generate-error">
          {t(commandErrorId(error))}
        </p>
      ) : null}
    </div>
  );
}

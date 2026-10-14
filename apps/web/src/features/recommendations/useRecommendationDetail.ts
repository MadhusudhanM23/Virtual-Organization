import { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchApproval,
  fetchDryRun,
  fetchRecommendation,
  type Approval,
  type DryRun,
  type Recommendation,
} from './recommendationApi';
import { isExecutionSettled, nextPollDelayMs } from './recommendationModel';

export interface RecommendationDetail {
  rec: Recommendation | null;
  loading: boolean;
  error: unknown;
  reload: () => Promise<void>;
  approval: Approval | null;
  dryRun: DryRun | null;
  dryRunLoading: boolean;
  dryRunError: unknown;
  reloadDryRun: () => Promise<void>;
  /** Start polling `GET /api/recommendations/{id}` until VERIFIED / PARTIAL / FAILED / STALE. */
  startPolling: () => void;
  polling: boolean;
}

/** Statuses whose dry-run diff is meaningful (the plan has not been applied yet). */
const DRY_RUN_STATUSES = new Set(['PROPOSED', 'APPROVED', 'STALE']);

async function loadApproval(r: Recommendation): Promise<Approval | null> {
  if (!r.approval_id) return null;
  try {
    return await fetchApproval(r.approval_id);
  } catch {
    // Non-fatal: the recommendation's own status and expires_at still drive the panel.
    return null;
  }
}

interface DryRunResult {
  dryRun: DryRun | null;
  error: unknown;
}

async function loadDryRun(id: string): Promise<DryRunResult> {
  try {
    return { dryRun: await fetchDryRun(id), error: null };
  } catch (error) {
    return { dryRun: null, error };
  }
}

interface Loaded {
  rec: Recommendation;
  approval: Approval | null;
  dry: DryRunResult | null;
}

async function loadAll(id: string): Promise<Loaded> {
  const rec = await fetchRecommendation(id);
  const [approval, dry] = await Promise.all([
    loadApproval(rec),
    DRY_RUN_STATUSES.has(rec.status) ? loadDryRun(id) : Promise.resolve(null),
  ]);
  return { rec, approval, dry };
}

/**
 * The recommendation, its approval and its dry-run diff. Mount it per id (`key={id}`) so state never leaks between
 * recommendations. While EXECUTING (or after Execute) it polls until a settled status.
 */
export function useRecommendationDetail(id: string | undefined): RecommendationDetail {
  const [rec, setRec] = useState<Recommendation | null>(null);
  const [loading, setLoading] = useState(Boolean(id));
  const [error, setError] = useState<unknown>(null);
  const [approval, setApproval] = useState<Approval | null>(null);
  const [dryRun, setDryRun] = useState<DryRun | null>(null);
  const [dryRunLoading, setDryRunLoading] = useState(false);
  const [dryRunError, setDryRunError] = useState<unknown>(null);
  const [polling, setPolling] = useState(false);
  const pollStart = useRef(0);

  const apply = useCallback((l: Loaded) => {
    setRec(l.rec);
    setError(null);
    setApproval(l.approval);
    if (l.dry) {
      setDryRun(l.dry.dryRun);
      setDryRunError(l.dry.error);
    }
    if (l.rec.status === 'EXECUTING') {
      pollStart.current = Date.now();
      setPolling(true);
    }
  }, []);

  const reload = useCallback(async () => {
    if (!id) return;
    try {
      apply(await loadAll(id));
    } catch (e) {
      setError(e);
    }
  }, [id, apply]);

  const reloadDryRun = useCallback(async () => {
    if (!id) return;
    setDryRunLoading(true);
    const r = await loadDryRun(id);
    setDryRun(r.dryRun);
    setDryRunError(r.error);
    setDryRunLoading(false);
  }, [id]);

  useEffect(() => {
    if (!id) return undefined;
    let cancelled = false;
    loadAll(id)
      .then((l) => {
        if (!cancelled) apply(l);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [id, apply]);

  useEffect(() => {
    if (!polling || !id) return undefined;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const tick = () => {
      fetchRecommendation(id)
        .then(async (r) => {
          if (cancelled) return;
          setRec(r);
          if (isExecutionSettled(r.status)) {
            setPolling(false);
            const a = await loadApproval(r);
            if (cancelled) return;
            setApproval(a);
            if (r.status === 'STALE') {
              const d = await loadDryRun(id);
              if (cancelled) return;
              setDryRun(d.dryRun);
              setDryRunError(d.error);
            }
            return;
          }
          timer = setTimeout(tick, nextPollDelayMs(Date.now() - pollStart.current));
        })
        .catch((e: unknown) => {
          if (cancelled) return;
          setError(e);
          setPolling(false);
        });
    };
    timer = setTimeout(tick, 500);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [polling, id]);

  const startPolling = useCallback(() => {
    pollStart.current = Date.now();
    setPolling(true);
  }, []);

  return {
    rec,
    loading,
    error,
    reload,
    approval,
    dryRun,
    dryRunLoading,
    dryRunError,
    reloadDryRun,
    startPolling,
    polling,
  };
}

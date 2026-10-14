import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Video } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useUiStore } from '../../stores/uiStore';
import { LoadState } from '../kpi/parts';
import { fetchCameras, type CameraList, type CameraView } from './cameraApi';
import { parseMediaTime } from './cameraModel';
import { CameraPanel } from './CameraPanel';

type ListState = { siteId: string; list: CameraList | null; error: unknown };

const STATUS_LABEL = {
  ACTIVE: 'cameras.status.ACTIVE',
  PLACEHOLDER: 'cameras.status.PLACEHOLDER',
  DISABLED: 'cameras.status.DISABLED',
} as const;

/**
 * Physical camera mode (BLUEPRINT section 5, REQ-085): the site's camera registry and one camera's media with its
 * labelled detection overlays. Deep links `?camera=&session=&t=` come from incident evidence.
 */
export default function CamerasPage() {
  useDocumentTitle(t('cameras.title'));
  const siteId = useUiStore((s) => s.selectedSiteId);
  const site = useUiStore((s) => s.sites.find((x) => x.id === s.selectedSiteId) ?? null);
  const fmt = useSiteFormat();
  const [params, setParams] = useSearchParams();
  const [state, setState] = useState<ListState | null>(null);

  const load = useCallback(() => {
    if (!siteId) return;
    fetchCameras(siteId)
      .then((list) => setState({ siteId, list, error: null }))
      .catch((error: unknown) => setState({ siteId, list: null, error }));
  }, [siteId]);

  useEffect(() => {
    load();
  }, [load]);

  const current = state && state.siteId === siteId ? state : null;
  const cameras = current?.list?.items ?? [];
  const requested = params.get('camera');
  const selected: CameraView | null =
    cameras.find((c) => c.camera_id === requested) ??
    cameras.find((c) => c.status === 'ACTIVE') ??
    cameras[0] ??
    null;
  const sessionId = params.get('session');
  const initialTime = parseMediaTime(params.get('t'));

  const select = (cameraId: string) => setParams({ camera: cameraId });

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 p-4 md:p-8">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight text-ink-900">{t('cameras.title')}</h1>
        <p className="mt-1 max-w-3xl text-sm text-ink-600">
          {site?.name ? `${site.name}. ` : ''}
          {t('page.cameras.description')}
        </p>
      </header>

      {!siteId ? (
        <EmptyState icon={Video} description={t('cameras.noSite')} />
      ) : !current || current.error ? (
        <LoadState
          loading={!current}
          error={current?.error}
          onRetry={load}
          label={t('cameras.loading')}
        />
      ) : cameras.length === 0 ? (
        <EmptyState icon={Video} description={t('cameras.empty')} />
      ) : (
        <div className="grid gap-5 lg:grid-cols-[18rem_1fr]">
          <nav aria-label={t('cameras.list')}>
            <ul className="flex flex-col gap-2" data-testid="camera-list">
              {cameras.map((c) => {
                const active = selected?.camera_id === c.camera_id;
                return (
                  <li key={c.camera_id}>
                    <button
                      type="button"
                      onClick={() => select(c.camera_id)}
                      aria-current={active ? 'true' : undefined}
                      className={cn(
                        'flex w-full flex-col items-start gap-1 rounded-card border bg-surface p-3 text-left shadow-card hover:bg-surface-sunken',
                        active ? 'border-accent-600' : 'border-line',
                      )}
                    >
                      <span className="flex w-full items-center gap-2">
                        <span className="font-mono text-sm font-semibold text-ink-900">
                          {c.code}
                        </span>
                        <span className="text-xs text-ink-700">{t(STATUS_LABEL[c.status])}</span>
                        <DataModeBadge dataMode={c.data_mode} className="ml-auto" />
                      </span>
                      <span className="text-xs text-ink-600">
                        {c.calibrated
                          ? t('cameras.calibrated', { points: c.control_points })
                          : t('cameras.uncalibrated')}
                      </span>
                      <span className="text-xs text-ink-600">
                        {c.last_observation_at
                          ? t('cameras.lastObservation', {
                              at: fmt.dateTime(c.last_observation_at),
                            })
                          : t('cameras.noObservations')}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </nav>
          {selected ? (
            <CameraPanel
              key={`${selected.camera_id}|${sessionId ?? ''}`}
              camera={selected}
              sessionId={sessionId}
              initialTime={selected.camera_id === requested ? initialTime : null}
              onSessionChange={(s) => setParams({ camera: selected.camera_id, session: s })}
            />
          ) : null}
        </div>
      )}
    </div>
  );
}

import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { Video } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useUiStore } from '../../stores/uiStore';
import { fetchCameras, type CameraList } from './cameraApi';

type State = { siteId: string; list: CameraList | null; failed: boolean };

/**
 * Right-column camera panel (BLUEPRINT section 15): the site's registered cameras with their truth label and last
 * observation, each opening the Cameras view. It replaced a stale "arrives in Phase 7" placeholder (plan 10.2 audit).
 */
export function CameraSummaryPanel() {
  const siteId = useUiStore((s) => s.selectedSiteId);
  const fmt = useSiteFormat();
  const [state, setState] = useState<State | null>(null);

  useEffect(() => {
    if (!siteId) return undefined;
    let cancelled = false;
    fetchCameras(siteId)
      .then((list) => !cancelled && setState({ siteId, list, failed: false }))
      .catch(() => !cancelled && setState({ siteId, list: null, failed: true }));
    return () => {
      cancelled = true;
    };
  }, [siteId]);

  if (!siteId) return <EmptyState compact icon={Video} description={t('cameras.noSite')} />;
  const current = state && state.siteId === siteId ? state : null;
  if (!current) {
    return (
      <p role="status" className="p-4 text-sm text-ink-600">
        {t('cameras.loading')}
      </p>
    );
  }
  if (current.failed || !current.list) {
    return <p className="p-4 text-sm text-ink-600">{t('cameras.panel.unavailable')}</p>;
  }
  if (current.list.items.length === 0) {
    return <EmptyState compact icon={Video} description={t('cameras.empty')} />;
  }
  return (
    <div className="flex flex-col gap-2 p-4" data-testid="camera-summary">
      <ul className="flex flex-col gap-2">
        {current.list.items.map((c) => (
          <li key={c.camera_id}>
            <Link
              to={`/cameras?camera=${encodeURIComponent(c.camera_id)}`}
              className="flex flex-col gap-0.5 rounded-control border border-line px-3 py-2 hover:bg-surface-sunken"
            >
              <span className="flex items-center gap-2">
                <span className="font-mono text-sm font-semibold text-ink-900">{c.code}</span>
                <span className="text-xs text-ink-700">{t(`cameras.status.${c.status}`)}</span>
                <DataModeBadge dataMode={c.data_mode} className="ml-auto" />
              </span>
              <span className="text-xs text-ink-600">
                {c.last_observation_at
                  ? t('cameras.lastObservation', { at: fmt.dateTime(c.last_observation_at) })
                  : t('cameras.noObservations')}
              </span>
            </Link>
          </li>
        ))}
      </ul>
      <Link
        to="/cameras"
        className="self-start text-xs font-semibold text-accent-700 underline-offset-2 hover:underline"
      >
        {t('cameras.panel.open')}
      </Link>
    </div>
  );
}

import { useEffect, useId, useState } from 'react';
import { Link } from 'react-router';
import { Video } from 'lucide-react';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';
import { useUiStore } from '../../stores/uiStore';
import { fetchCameras, type CameraView } from './cameraApi';
import { CameraPanel } from './CameraPanel';

type State = { siteId: string; items: CameraView[] | null; failed: boolean };

/** Video cameras first, then stills: the right column should open on moving footage when there is any. */
function playable(items: readonly CameraView[]): CameraView[] {
  const withMedia = items.filter((c) => c.feed.media_href !== null && c.feed.kind !== 'NONE');
  return [...withMedia].sort(
    (a, b) =>
      Number(b.feed.media_kind === 'VIDEO') - Number(a.feed.media_kind === 'VIDEO') ||
      a.code.localeCompare(b.code),
  );
}

/** Right-column live camera feed: one camera's media with its detection overlays, switchable between cameras. */
export function LiveCameraCard() {
  const siteId = useUiStore((s) => s.selectedSiteId);
  const [state, setState] = useState<State | null>(null);
  const [cameraId, setCameraId] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const selectId = useId();

  useEffect(() => {
    if (!siteId) return undefined;
    let cancelled = false;
    fetchCameras(siteId)
      .then((list) => !cancelled && setState({ siteId, items: list.items, failed: false }))
      .catch(() => !cancelled && setState({ siteId, items: null, failed: true }));
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
  if (current.failed || !current.items) {
    return <p className="p-4 text-sm text-ink-600">{t('cameras.panel.unavailable')}</p>;
  }
  const cameras = playable(current.items);
  const camera = cameras.find((c) => c.camera_id === cameraId) ?? cameras[0];
  if (!camera) return <EmptyState compact icon={Video} description={t('cameras.empty')} />;

  return (
    <div className="flex flex-col gap-2 p-3" data-testid="live-camera-card">
      <div className="flex items-center gap-2">
        <label htmlFor={selectId} className="sr-only">
          {t('cameras.panel.select')}
        </label>
        <select
          id={selectId}
          value={camera.camera_id}
          onChange={(e) => {
            setCameraId(e.target.value);
            setSessionId(null);
          }}
          className="min-w-0 flex-1 rounded-control border border-line-strong bg-navy-900 px-2 py-1.5 text-xs font-semibold text-white"
        >
          {cameras.map((c) => (
            <option key={c.camera_id} value={c.camera_id}>
              {`${c.code} · ${c.name}`}
            </option>
          ))}
        </select>
        <Link
          to={`/cameras?camera=${encodeURIComponent(camera.camera_id)}`}
          className="shrink-0 text-xs font-semibold text-accent-700 underline-offset-2 hover:underline"
        >
          {t('cameras.panel.open')}
        </Link>
      </div>
      <CameraPanel
        key={`${camera.camera_id}|${sessionId ?? ''}`}
        camera={camera}
        sessionId={sessionId}
        initialTime={null}
        onSessionChange={setSessionId}
      />
    </div>
  );
}

import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { Pause, Play, ShieldCheck, VideoOff } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t } from '../../i18n';
import { useReducedMotion } from '../../lib/motion';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { LoadState } from '../kpi/parts';
import {
  fetchCameraMedia,
  fetchObservations,
  type CameraView,
  type Observations,
  type ObservationView,
} from './cameraApi';
import { classColor, mediaRange, observationsAt, toPixels, trackCounts } from './cameraModel';

type Loaded<T> =
  { status: 'loading' } | { status: 'ok'; value: T } | { status: 'error'; error: unknown };

/** A result tagged with the request it answers; a stale tag reads as loading (no synchronous setState in effects). */
type Keyed<T> = { key: string; state: Loaded<T> };

const LOADING = { status: 'loading' } as const;

function drawOverlays(canvas: HTMLCanvasElement, items: readonly ObservationView[]) {
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const ratio = window.devicePixelRatio || 1;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  if (canvas.width !== Math.round(w * ratio) || canvas.height !== Math.round(h * ratio)) {
    canvas.width = Math.round(w * ratio);
    canvas.height = Math.round(h * ratio);
  }
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, w, h);
  ctx.font = '600 12px system-ui, sans-serif';
  for (const o of items) {
    const r = toPixels(o.bbox, w, h);
    const color = classColor(o.object_class);
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.setLineDash(o.identity_status === 'STABLE' ? [] : [6, 4]);
    ctx.strokeRect(r.x, r.y, r.w, r.h);
    ctx.setLineDash([]);
    const label = `${o.object_class} #${o.track_id} ${o.confidence.toFixed(2)}${
      o.identity_status === 'STABLE' ? '' : ' ?'
    }`;
    const tw = ctx.measureText(label).width + 8;
    const ty = r.y >= 18 ? r.y - 18 : r.y + r.h;
    ctx.fillStyle = color;
    ctx.fillRect(r.x, ty, tw, 18);
    ctx.fillStyle = '#ffffff';
    ctx.fillText(label, r.x + 4, ty + 13);
  }
}

/** Media (video or still) of one camera with its observation overlays at the current media time (REQ-085). */
export function CameraPanel({
  camera,
  sessionId,
  initialTime,
  onSessionChange,
}: {
  camera: CameraView;
  sessionId: string | null;
  initialTime: number | null;
  onSessionChange: (sessionId: string) => void;
}) {
  const fmt = useSiteFormat();
  const reducedMotion = useReducedMotion();
  const sessionSelectId = useId();
  const sliderId = useId();
  const [obsResult, setObsResult] = useState<Keyed<Observations> | null>(null);
  const [mediaResult, setMediaResult] = useState<Keyed<string> | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [time, setTime] = useState(initialTime ?? 0);
  const [playing, setPlaying] = useState(false);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const hasMedia = camera.feed.media_href !== null && camera.feed.kind !== 'NONE';
  const isVideo = camera.feed.media_kind === 'VIDEO';

  const obsKey = `${camera.camera_id}|${sessionId ?? ''}|${attempt}`;
  const obs: Loaded<Observations> = obsResult?.key === obsKey ? obsResult.state : LOADING;
  const mediaKey = camera.camera_id;
  const media: Loaded<string> = mediaResult?.key === mediaKey ? mediaResult.state : LOADING;

  useEffect(() => {
    let cancelled = false;
    fetchObservations(camera.camera_id, sessionId)
      .then((value) => {
        if (!cancelled) setObsResult({ key: obsKey, state: { status: 'ok', value } });
      })
      .catch((error: unknown) => {
        if (!cancelled) setObsResult({ key: obsKey, state: { status: 'error', error } });
      });
    return () => {
      cancelled = true;
    };
  }, [camera.camera_id, sessionId, obsKey]);

  // Media: fetched with the bearer token (audited server side), shown through a short-lived object URL.
  useEffect(() => {
    if (!hasMedia) return undefined;
    let url: string | null = null;
    let cancelled = false;
    fetchCameraMedia(camera.camera_id)
      .then((blob) => {
        if (cancelled) return;
        url = URL.createObjectURL(blob);
        setMediaResult({ key: mediaKey, state: { status: 'ok', value: url } });
      })
      .catch((error: unknown) => {
        if (!cancelled) setMediaResult({ key: mediaKey, state: { status: 'error', error } });
      });
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [camera.camera_id, hasMedia, mediaKey]);

  const items = useMemo(() => (obs.status === 'ok' ? obs.value.items : []), [obs]);
  const range = useMemo(() => mediaRange(items), [items]);

  const redraw = useCallback(
    (at: number) => {
      if (canvasRef.current) drawOverlays(canvasRef.current, observationsAt(items, at));
    },
    [items],
  );

  useEffect(() => redraw(time), [redraw, time]);

  // Video: follow the playhead. Reduced motion: redraw on timeupdate only (a few times per second).
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !isVideo) return undefined;
    let frame = 0;
    const tick = () => {
      setTime(video.currentTime);
      if (!video.paused && !reducedMotion) frame = requestAnimationFrame(tick);
    };
    const onPlay = () => {
      if (!reducedMotion) frame = requestAnimationFrame(tick);
    };
    const onTime = () => setTime(video.currentTime);
    video.addEventListener('play', onPlay);
    video.addEventListener('timeupdate', onTime);
    video.addEventListener('seeked', onTime);
    return () => {
      cancelAnimationFrame(frame);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('timeupdate', onTime);
      video.removeEventListener('seeked', onTime);
    };
  }, [isVideo, reducedMotion, media]);

  // Jump to the deep-linked time once the video is ready.
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !isVideo || initialTime === null || media.status !== 'ok') return undefined;
    const seek = () => {
      video.currentTime = initialTime;
    };
    if (video.readyState >= 1) seek();
    else video.addEventListener('loadedmetadata', seek, { once: true });
    return () => video.removeEventListener('loadedmetadata', seek);
  }, [initialTime, isVideo, media]);

  // Still: the user can step through the observation timeline (user-started, pausable).
  useEffect(() => {
    if (!playing || isVideo || !range) return undefined;
    const timer = setInterval(() => {
      setTime((prev) => {
        const next = prev + 0.2;
        if (next > range.to) {
          setPlaying(false);
          return range.to;
        }
        return next;
      });
    }, 200);
    return () => clearInterval(timer);
  }, [playing, isVideo, range]);

  const width = camera.calibration?.image_width ?? 16;
  const height = camera.calibration?.image_height ?? 9;
  const session =
    obs.status === 'ok'
      ? obs.value.sessions.find((s) => s.session_id === obs.value.session_id)
      : null;
  const counts = trackCounts(items);
  const visible = observationsAt(items, time);

  return (
    <section
      aria-label={t('cameras.panel.label', { code: camera.code })}
      className="flex flex-col gap-3 rounded-card border border-line bg-surface p-4 shadow-card"
      data-testid="camera-panel"
    >
      <header className="flex flex-wrap items-center gap-2">
        <h2 className="text-lg font-semibold text-ink-900">
          {camera.code} <span className="text-sm font-normal text-ink-600">{camera.name}</span>
        </h2>
        <DataModeBadge dataMode={camera.data_mode} />
        <span className="rounded-control border border-line px-2 py-0.5 text-xs font-semibold text-ink-700">
          {t('cameras.source', { source: camera.source })}
        </span>
        {session?.detector ? (
          <span className="text-xs text-ink-600">
            {t('cameras.detector', { detector: session.detector })}
          </span>
        ) : null}
      </header>

      <div
        className="relative w-full overflow-hidden rounded-control bg-surface-sunken"
        style={{ aspectRatio: `${width} / ${height}` }}
      >
        {!hasMedia ? (
          <div className="flex h-full items-center justify-center gap-2 p-4 text-sm text-ink-600">
            <VideoOff aria-hidden="true" className="size-5" />
            {t('cameras.noFeed')}
          </div>
        ) : media.status === 'loading' ? (
          <p role="status" className="p-4 text-sm text-ink-600">
            {t('cameras.media.loading')}
          </p>
        ) : media.status === 'error' ? (
          <div className="flex h-full items-center justify-center gap-2 p-4 text-sm text-ink-600">
            <VideoOff aria-hidden="true" className="size-5" />
            {t('cameras.media.unavailable')}
          </div>
        ) : isVideo ? (
          <video
            ref={videoRef}
            src={media.value}
            controls
            muted
            playsInline
            preload="metadata"
            className="absolute inset-0 size-full object-fill"
            aria-label={t('cameras.media.videoLabel', { code: camera.code })}
          />
        ) : (
          <img
            src={media.value}
            alt={t('cameras.media.stillAlt', { code: camera.code })}
            className="absolute inset-0 size-full object-fill"
          />
        )}
        {hasMedia && media.status === 'ok' ? (
          <canvas
            ref={canvasRef}
            aria-hidden="true"
            className="pointer-events-none absolute inset-0 size-full"
            data-testid="camera-overlay"
          />
        ) : null}
      </div>

      {!isVideo && range ? (
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={() => {
              if (!playing && time >= range.to) setTime(range.from);
              setPlaying((p) => !p);
            }}
            className="inline-flex items-center gap-1.5 rounded-control border border-line-strong bg-surface px-3 py-1 text-sm font-semibold text-ink-900 hover:bg-surface-sunken"
          >
            {playing ? (
              <Pause aria-hidden="true" className="size-4" />
            ) : (
              <Play aria-hidden="true" className="size-4" />
            )}
            {playing ? t('cameras.timeline.pause') : t('cameras.timeline.play')}
          </button>
          <label htmlFor={sliderId} className="text-xs font-semibold text-ink-700">
            {t('cameras.timeline.label')}
          </label>
          <input
            id={sliderId}
            type="range"
            min={range.from}
            max={range.to}
            step={0.04}
            value={Math.min(Math.max(time, range.from), range.to)}
            onChange={(e) => {
              setPlaying(false);
              setTime(Number(e.target.value));
            }}
            className="min-w-48 flex-1 accent-accent-600"
          />
        </div>
      ) : null}

      <p role="status" className="font-mono text-xs text-ink-700">
        {t('cameras.timeline.at', { t: time.toFixed(2), count: visible.length })}
      </p>

      {obs.status === 'loading' || obs.status === 'error' ? (
        <LoadState
          loading={obs.status === 'loading'}
          error={obs.status === 'error' ? obs.error : null}
          onRetry={() => setAttempt((a) => a + 1)}
          label={t('cameras.observations.loading')}
        />
      ) : obs.value.items.length === 0 ? (
        <p className="text-sm text-ink-600">{t('cameras.observations.empty')}</p>
      ) : (
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm">
          {obs.value.sessions.length > 0 ? (
            <span className="inline-flex items-center gap-2">
              <label htmlFor={sessionSelectId} className="text-xs font-semibold text-ink-700">
                {t('cameras.session')}
              </label>
              <select
                id={sessionSelectId}
                value={obs.value.session_id ?? ''}
                onChange={(e) => onSessionChange(e.target.value)}
                className="rounded-control border border-line-strong bg-surface px-2 py-1 text-xs"
              >
                {obs.value.sessions.map((s) => (
                  <option key={s.session_id} value={s.session_id}>
                    {`${s.session_id} (${fmt.dateTime(s.first_observed_at)}, ${t(
                      'cameras.session.count',
                      {
                        count: s.count,
                      },
                    )})`}
                  </option>
                ))}
              </select>
            </span>
          ) : null}
          <ul className="flex flex-wrap gap-3" aria-label={t('cameras.legend')}>
            {Object.entries(counts).map(([cls, n]) => (
              <li key={cls} className="inline-flex items-center gap-1.5 text-xs text-ink-900">
                <span
                  aria-hidden="true"
                  className="inline-block size-3 rounded-sm"
                  style={{ background: classColor(cls) }}
                />
                {t('cameras.legend.item', { cls, count: n })}
              </li>
            ))}
          </ul>
          <span className="text-xs text-ink-600">{t('cameras.legend.provisional')}</span>
          {obs.value.truncated ? (
            <span className="text-xs text-warning-fg">{t('cameras.truncated')}</span>
          ) : null}
        </div>
      )}

      <p className="flex items-start gap-2 rounded-control border border-line bg-surface-sunken p-2 text-xs text-ink-700">
        <ShieldCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-accent-600" />
        <span>
          {t('cameras.privacy', { days: camera.privacy.retention_days })} {t('cameras.advisory')}
        </span>
      </p>
    </section>
  );
}

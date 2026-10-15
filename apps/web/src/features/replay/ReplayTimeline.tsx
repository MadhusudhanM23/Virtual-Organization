import { useMemo, useRef, type KeyboardEvent, type PointerEvent } from 'react';
import {
  ChevronsRight,
  Clock,
  Loader,
  OctagonAlert,
  Pause,
  Play,
  StepBack,
  StepForward,
  TriangleAlert,
} from 'lucide-react';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { useSiteFormat } from '../../lib/useSiteFormat';
import type { ReplayEngine } from './replayEngine';
import { useReplayStore } from './replayStore';
import {
  SPEEDS,
  STEP_MS,
  fractionOf,
  instantAt,
  keyStep,
  placeMarkers,
  snapshotTicks,
  toIso,
  type TimelineMarker,
} from './timelineModel';

const segGroup = 'inline-flex rounded-control border border-lens-replay-line bg-surface p-0.5';
const seg = (active: boolean) =>
  cn(
    'rounded-[0.5rem] px-2.5 py-1 text-sm font-semibold',
    active ? 'bg-navy-900 text-white' : 'text-ink-700 hover:bg-surface-sunken',
  );
const iconButton =
  'inline-flex h-9 items-center gap-1.5 rounded-control border border-line-strong bg-surface px-2.5 text-sm font-semibold text-ink-900 hover:bg-surface-sunken disabled:cursor-not-allowed disabled:opacity-50';

export interface ReplayTimelineProps {
  engine: ReplayEngine;
  markers: TimelineMarker[];
}

/**
 * Replay timeline (REQ-062): range earliest..latest, a scrubber (role="slider": arrow keys +-5 s, Shift +-60 s,
 * Home/End, Space plays/pauses), play/pause, speed, step +-5 s, jump to latest, snapshot ticks and incident/alert
 * markers that seek. The replay time is shown in the site time zone under an explicit "Replay time" label.
 */
export function ReplayTimeline({ engine, markers }: ReplayTimelineProps) {
  const fmt = useSiteFormat();
  const bounds = useReplayStore((s) => s.bounds);
  const at = useReplayStore((s) => s.at);
  const playing = useReplayStore((s) => s.playing);
  const speed = useReplayStore((s) => s.speed);
  const buffering = useReplayStore((s) => s.buffering);
  const seeking = useReplayStore((s) => s.seeking);
  const range = useReplayStore((s) => s.range);
  const track = useRef<HTMLDivElement>(null);
  const dragging = useRef(false);

  const placed = useMemo(() => (bounds ? placeMarkers(markers, bounds) : []), [markers, bounds]);
  const ticks = useMemo(
    () => (bounds && range ? snapshotTicks(range.snapshots.map((s) => s.snapshot_at), bounds) : []),
    [bounds, range],
  );

  if (!bounds || at === null) return null;
  const atIso = toIso(at);
  const fraction = fractionOf(at, bounds);
  const settled = range?.settled_until ? Date.parse(range.settled_until) : null;
  const unsettled = settled !== null && at > settled;

  const seekFromPointer = (clientX: number) => {
    const el = track.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    engine.seek(instantAt((clientX - rect.left) / Math.max(1, rect.width), bounds));
  };

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    dragging.current = true;
    e.currentTarget.setPointerCapture(e.pointerId);
    if (playing) engine.pause();
    seekFromPointer(e.clientX);
  };
  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    if (dragging.current) seekFromPointer(e.clientX);
  };
  const onPointerUp = (e: PointerEvent<HTMLDivElement>) => {
    dragging.current = false;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === ' ' || e.key === 'Spacebar') {
      e.preventDefault();
      engine.toggle();
      return;
    }
    const next = keyStep(e.key, e.shiftKey, at, bounds);
    if (next === null) return;
    e.preventDefault();
    if (playing) engine.pause();
    engine.seek(next);
  };

  const step = (delta: number) => {
    if (playing) engine.pause();
    engine.seek(at + delta);
  };

  return (
    <section
      aria-label={t('replay.timeline.label')}
      data-testid="replay-timeline"
      className="flex flex-col gap-3 rounded-card border-2 border-lens-replay-line bg-lens-replay-bg p-3 shadow-card"
    >
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div className="flex min-w-0 items-center gap-2">
          <Clock aria-hidden="true" className="size-5 shrink-0 text-lens-replay-fg" />
          <div className="min-w-0">
            <p className="text-xs font-bold tracking-wide text-lens-replay-fg uppercase">
              {t('replay.time.label')}
            </p>
            <p
              className="font-mono text-base font-semibold text-ink-900 tabular-nums"
              data-testid="replay-time"
              data-at={atIso}
            >
              <time dateTime={atIso}>{fmt.dateTimeSeconds(atIso)}</time>
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-xs" aria-live="polite">
          {seeking ? (
            <span className="inline-flex items-center gap-1 font-semibold text-ink-700" data-testid="replay-seeking">
              <Loader aria-hidden="true" className="size-3.5 animate-spin motion-reduce:animate-none" />
              {t('replay.status.seeking')}
            </span>
          ) : null}
          {buffering && !seeking ? (
            <span className="inline-flex items-center gap-1 font-semibold text-ink-700">
              <Loader aria-hidden="true" className="size-3.5 animate-spin motion-reduce:animate-none" />
              {t('replay.status.buffering')}
            </span>
          ) : null}
          {unsettled ? <span className="text-warning-fg">{t('replay.status.unsettled')}</span> : null}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => step(-STEP_MS)}
          className={iconButton}
          aria-label={t('replay.control.back')}
          title={t('replay.control.back')}
        >
          <StepBack aria-hidden="true" className="size-4" />
          <span className="hidden sm:inline">-5 s</span>
        </button>
        <button
          type="button"
          onClick={() => engine.toggle()}
          aria-pressed={playing}
          data-testid="replay-play"
          className="inline-flex h-9 items-center gap-1.5 rounded-control bg-navy-900 px-3.5 text-sm font-semibold text-white hover:bg-navy-800"
        >
          {playing ? (
            <Pause aria-hidden="true" className="size-4" />
          ) : (
            <Play aria-hidden="true" className="size-4" />
          )}
          {playing ? t('replay.control.pause') : t('replay.control.play')}
        </button>
        <button
          type="button"
          onClick={() => step(STEP_MS)}
          className={iconButton}
          aria-label={t('replay.control.forward')}
          title={t('replay.control.forward')}
        >
          <StepForward aria-hidden="true" className="size-4" />
          <span className="hidden sm:inline">+5 s</span>
        </button>
        <div role="group" aria-label={t('replay.control.speed')} className={segGroup}>
          {SPEEDS.map((s) => (
            <button
              key={s}
              type="button"
              aria-pressed={speed === s}
              onClick={() => engine.setSpeed(s)}
              className={seg(speed === s)}
            >
              {`${s}x`}
            </button>
          ))}
        </div>
        <button type="button" onClick={() => void engine.jumpToLatest()} className={iconButton}>
          <ChevronsRight aria-hidden="true" className="size-4" />
          {t('replay.control.latest')}
        </button>
      </div>

      <div className="px-2">
        {/* Two rows: incidents above, alerts below, so an incident and its alert at the same instant never overlap. */}
        <div className="relative h-12" aria-label={t('replay.markers.label')} role="group">
          {placed.map((m) => {
            const Icon = m.kind === 'incident' ? OctagonAlert : TriangleAlert;
            const mIso = toIso(m.at);
            return (
              <button
                key={`${m.kind}:${m.id}`}
                type="button"
                data-testid={`replay-marker-${m.kind}`}
                onClick={() => {
                  if (playing) engine.pause();
                  engine.seek(m.at, true);
                }}
                aria-label={t(m.kind === 'incident' ? 'replay.markers.incident' : 'replay.markers.alert', {
                  severity: m.severity,
                  label: m.label,
                  time: fmt.timeSeconds(mIso),
                })}
                title={`${m.label} - ${fmt.timeSeconds(mIso)}`}
                className={cn(
                  // 24 px target (WCAG 2.5.8); the two rows are 24 px apart.
                  'absolute grid size-6 -translate-x-1/2 place-items-center rounded-full border bg-surface shadow-card hover:z-10 hover:scale-110',
                  m.kind === 'incident'
                    ? 'top-0 border-danger-line text-danger-fg'
                    : 'top-6 border-warning-line text-warning-fg',
                )}
                style={{ left: `${fractionOf(m.at, bounds) * 100}%` }}
              >
                <Icon aria-hidden="true" className="size-3" />
              </button>
            );
          })}
        </div>
        <div
          ref={track}
          role="slider"
          tabIndex={0}
          aria-label={t('replay.scrubber.label')}
          aria-valuemin={bounds.start}
          aria-valuemax={bounds.end}
          aria-valuenow={at}
          aria-valuetext={t('replay.scrubber.value', { time: fmt.dateTimeSeconds(atIso) })}
          aria-keyshortcuts="ArrowLeft ArrowRight Shift+ArrowLeft Shift+ArrowRight Home End Space"
          data-testid="replay-scrubber"
          onKeyDown={onKeyDown}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
          className="relative mt-1 h-8 cursor-pointer touch-none rounded-control outline-none focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-500"
        >
          <div className="absolute inset-x-0 top-3 h-2 rounded-full bg-surface ring-1 ring-lens-replay-line" />
          <div
            className="absolute top-3 left-0 h-2 rounded-full bg-lens-replay-solid"
            style={{ width: `${fraction * 100}%` }}
          />
          {ticks.map((ms) => (
            <span
              key={ms}
              aria-hidden="true"
              data-testid="replay-snapshot-tick"
              className="absolute top-1 h-6 w-px bg-ink-500"
              style={{ left: `${fractionOf(ms, bounds) * 100}%` }}
            />
          ))}
          <span
            aria-hidden="true"
            className="absolute top-1 size-6 -translate-x-1/2 rounded-full border-2 border-white bg-lens-replay-fg shadow-float"
            style={{ left: `${fraction * 100}%` }}
          />
        </div>
        <div className="mt-1 flex justify-between gap-2 text-xs text-ink-700">
          <span>
            {t('replay.range.start')}: <time dateTime={toIso(bounds.start)}>{fmt.dateTimeSeconds(toIso(bounds.start))}</time>
          </span>
          <span className="hidden text-center md:inline">{t('replay.keys.hint')}</span>
          <span className="text-right">
            {t('replay.range.end')}: <time dateTime={toIso(bounds.end)}>{fmt.dateTimeSeconds(toIso(bounds.end))}</time>
          </span>
        </div>
        <p className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-ink-700">
          <span className="inline-flex items-center gap-1">
            <span aria-hidden="true" className="inline-block h-3 w-px bg-ink-500" />
            {t('replay.legend.snapshot', { count: range?.snapshot_count ?? 0 })}
          </span>
          <span className="inline-flex items-center gap-1">
            <OctagonAlert aria-hidden="true" className="size-3 text-danger-fg" />
            {t('replay.legend.incident')}
          </span>
          <span className="inline-flex items-center gap-1">
            <TriangleAlert aria-hidden="true" className="size-3 text-warning-fg" />
            {t('replay.legend.alert')}
          </span>
        </p>
      </div>
    </section>
  );
}

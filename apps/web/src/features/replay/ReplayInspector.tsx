import type { ReactNode } from 'react';
import { History, X } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t } from '../../i18n';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { ENTITY_STYLES } from '../twin/sceneModel';
import { useTwinStore } from '../twin/twinStore';
import type { ReplayEntity } from './replayApi';
import { useReplayStore, type ReplayBase } from './replayStore';
import { toIso } from './timelineModel';

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[8.5rem_1fr] gap-2 border-t border-line py-1.5 first:border-t-0">
      <dt className="text-xs font-medium text-ink-600">{label}</dt>
      <dd className="min-w-0 text-sm break-words text-ink-900">{children}</dd>
    </div>
  );
}

/** "Reconstructed from snapshot X + N events" (ADR-46 `reconstructed_from`), then "advanced by playback frames". */
export function ProvenanceNote({
  base,
  at,
  advanced,
}: {
  base: ReplayBase;
  at: number;
  advanced: boolean;
}) {
  const fmt = useSiteFormat();
  const r = base.reconstructedFrom;
  return (
    <div
      data-testid="replay-provenance"
      className="rounded-control border border-lens-replay-line bg-lens-replay-bg px-3 py-2 text-xs text-lens-replay-fg"
    >
      <p className="font-semibold">
        {t('replay.provenance.from', {
          snapshot: r.snapshot_id.slice(0, 8),
          snapshotAt: fmt.dateTimeSeconds(r.snapshot_at),
          events: r.events_applied,
        })}
      </p>
      <p className="mt-0.5">
        {t('replay.provenance.read', {
          at: fmt.dateTimeSeconds(toIso(base.at)),
          read: r.events_read,
          ignored: r.events_ignored,
          skipped: r.events_skipped,
        })}
      </p>
      {advanced && at > base.at ? (
        <p className="mt-0.5">{t('replay.provenance.frames', { at: fmt.dateTimeSeconds(toIso(at)) })}</p>
      ) : null}
    </div>
  );
}

/** The selected entity as of the replay time (the frame state, not interpolated), with replay provenance. */
export function ReplayInspectorBody({ entity, onClear }: { entity: ReplayEntity | null; onClear: () => void }) {
  const fmt = useSiteFormat();
  const base = useReplayStore((s) => s.base);
  const at = useReplayStore((s) => s.at);
  const advanced = useReplayStore((s) => s.advancedByFrames);
  const world = useReplayStore((s) => s.world);
  const floorName = entity?.floor_id ? world?.floors.find((f) => f.id === entity.floor_id)?.name : null;
  const zoneName = entity?.zone_id ? world?.zones.find((z) => z.id === entity.zone_id)?.name : null;

  return (
    <div data-testid="twin-inspector" data-lens="REPLAY" className="flex min-w-0 flex-col">
      <p className="mx-3 mt-3 flex items-center gap-1.5 rounded-control bg-lens-replay-bg px-2 py-1 text-xs font-semibold text-lens-replay-fg">
        <History aria-hidden="true" className="size-3.5" />
        {at !== null
          ? t('replay.inspector.asOf', { time: fmt.dateTimeSeconds(toIso(at)) })
          : t('replay.inspector.noTime')}
      </p>
      {entity ? (
        <div className="flex justify-end px-3 pt-2">
          <button
            type="button"
            onClick={onClear}
            aria-label={t('twin.inspector.clear')}
            title={t('twin.inspector.clear')}
            className="grid size-7 place-items-center rounded-control text-ink-600 hover:bg-surface-sunken hover:text-ink-900"
          >
            <X aria-hidden="true" className="size-4" />
          </button>
        </div>
      ) : null}
      {!entity ? (
        <p className="p-4 text-sm text-ink-600">{t('twin.inspector.empty')}</p>
      ) : (
        <div className="p-4 pt-1">
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <span className="text-base font-semibold text-ink-900">{entity.name}</span>
            <DataModeBadge dataMode={entity.data_mode} lens="REPLAY" />
          </div>
          <dl>
            <Row label={t('twin.inspector.code')}>
              <span className="font-mono text-xs">{entity.code}</span>
            </Row>
            <Row label={t('twin.inspector.type')}>{ENTITY_STYLES[entity.entity_type].label}</Row>
            <Row label={t('twin.inspector.status')}>{entity.status}</Row>
            <Row label={t('twin.inspector.floor')}>{floorName ?? '-'}</Row>
            <Row label={t('twin.inspector.zone')}>{zoneName ?? '-'}</Row>
          </dl>
          <h3 className="mt-3 mb-1 text-xs font-bold tracking-wide text-ink-700 uppercase">
            {t('twin.inspector.pose')}
          </h3>
          {entity.pose ? (
            <dl>
              <Row label={t('twin.inspector.position')}>
                <span className="font-mono text-xs" data-testid="replay-inspector-position">
                  {entity.pose.x.toFixed(2)}, {entity.pose.y.toFixed(2)}, {entity.pose.z.toFixed(2)}
                </span>
              </Row>
              <Row label={t('twin.inspector.heading')}>{entity.pose.heading_deg.toFixed(0)} deg</Row>
              <Row label={t('twin.inspector.poseTime')}>{fmt.dateTimeSeconds(entity.pose.occurred_at)}</Row>
              <Row label={t('twin.inspector.poseSource')}>
                <span className="mr-2">{entity.pose.source}</span>
                <DataModeBadge dataMode={entity.pose.data_mode} lens="REPLAY" />
              </Row>
              <Row label={t('twin.inspector.confidence')}>
                {entity.pose.spatial_confidence === null
                  ? t('twin.inspector.confidenceNone')
                  : fmt.number(entity.pose.spatial_confidence, { maximumFractionDigits: 2 })}
              </Row>
            </dl>
          ) : (
            <p className="text-sm text-ink-600">{t('replay.inspector.noPose')}</p>
          )}
          <h3 className="mt-3 mb-1 text-xs font-bold tracking-wide text-ink-700 uppercase">
            {t('replay.inspector.provenance')}
          </h3>
          {base && at !== null ? <ProvenanceNote base={base} at={at} advanced={advanced} /> : null}
          <p className="mt-2 text-xs text-ink-600">{t('replay.inspector.catalogNote')}</p>
        </div>
      )}
    </div>
  );
}

/** Inspector wired to the twin selection and the replay scene (right panel and mobile drawer, REPLAY lens). */
export function ConnectedReplayInspector() {
  const scene = useReplayStore((s) => s.scene);
  const selectedId = useTwinStore((s) => s.selectedEntityId);
  const select = useTwinStore((s) => s.select);
  const entity = selectedId ? (scene?.entities.get(selectedId) ?? null) : null;
  return <ReplayInspectorBody entity={entity} onClear={() => select(null)} />;
}

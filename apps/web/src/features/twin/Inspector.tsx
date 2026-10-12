import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import type { Entity, EntityDetail } from '@vo/twin-schema';
import { DataModeBadge } from '../../components/DataModeBadge';
import { t } from '../../i18n';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useUiStore } from '../../stores/uiStore';
import { ENTITY_STYLES } from './sceneModel';
import { useTwinStore } from './twinStore';
import { fetchEntityDetail } from './twinApi';
import { formatAge } from '../live/formatAge';
import { useNow } from '../live/useNow';

type DetailState =
  { id: string; status: 'ok'; detail: EntityDetail } | { id: string; status: 'failed' };

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[8.5rem_1fr] gap-2 border-t border-line py-1.5 first:border-t-0">
      <dt className="text-xs font-medium text-ink-600">{label}</dt>
      <dd className="min-w-0 text-sm break-words text-ink-900">{children}</dd>
    </div>
  );
}

/** Entity inspector (REQ-021, REQ-023): identity, status, pose with provenance, external IDs, truth label. */
export function InspectorBody({ entity, onClear }: { entity: Entity | null; onClear: () => void }) {
  const fmt = useSiteFormat();
  const lens = useUiStore((s) => s.lens);
  const [settled, setSettled] = useState<DetailState | null>(null);
  const now = useNow();
  const id = entity?.id ?? null;

  useEffect(() => {
    if (!id) return undefined;
    let cancelled = false;
    fetchEntityDetail(id)
      .then((detail) => {
        if (!cancelled) setSettled({ id, status: 'ok', detail });
      })
      .catch(() => {
        if (!cancelled) setSettled({ id, status: 'failed' });
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const current = settled && settled.id === id ? settled : null;
  const detail = current?.status === 'ok' ? current.detail : null;
  const loading = id !== null && current === null;

  return (
    <div data-testid="twin-inspector" className="flex min-w-0 flex-col">
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
        <div className="p-4">
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <span className="text-base font-semibold text-ink-900">{entity.name}</span>
            <DataModeBadge dataMode={entity.data_mode} lens={lens} />
          </div>
          {loading ? (
            <p className="mb-2 text-xs text-ink-600">{t('twin.inspector.loadingDetail')}</p>
          ) : null}
          {current?.status === 'failed' ? (
            <p role="status" className="mb-2 text-xs text-warning-fg">
              {t('twin.inspector.detailFailed')}
            </p>
          ) : null}
          <dl>
            <Row label={t('twin.inspector.code')}>
              <span className="font-mono text-xs">{entity.code}</span>
            </Row>
            <Row label={t('twin.inspector.type')}>{ENTITY_STYLES[entity.entity_type].label}</Row>
            <Row label={t('twin.inspector.status')}>{entity.status}</Row>
            <Row label={t('twin.inspector.floor')}>{detail?.floor_name ?? '-'}</Row>
            <Row label={t('twin.inspector.zone')}>{detail?.zone_name ?? '-'}</Row>
            <Row label={t('twin.inspector.version')}>{entity.version}</Row>
          </dl>
          <h3 className="mt-3 mb-1 text-xs font-bold tracking-wide text-ink-700 uppercase">
            {t('twin.inspector.pose')}
          </h3>
          <dl>
            <Row label={t('twin.inspector.position')}>
              <span className="font-mono text-xs">
                {entity.pose.x.toFixed(2)}, {entity.pose.y.toFixed(2)}, {entity.pose.z.toFixed(2)}
              </span>
            </Row>
            <Row label={t('twin.inspector.heading')}>{entity.pose.heading_deg.toFixed(0)} deg</Row>
            <Row label={t('twin.inspector.poseTime')}>{fmt.dateTime(entity.pose.occurred_at)}</Row>
            <Row label={t('live.inspector.lastUpdate')}>
              <span data-testid="inspector-age">
                {t('live.freshness.updated', {
                  age: formatAge(now - Date.parse(entity.pose.occurred_at)),
                })}
              </span>
            </Row>
            <Row label={t('twin.inspector.poseSource')}>
              <span className="mr-2">{entity.pose.source}</span>
              <DataModeBadge dataMode={entity.pose.data_mode} lens={lens} />
            </Row>
            <Row label={t('twin.inspector.confidence')}>
              {entity.pose.spatial_confidence === null
                ? t('twin.inspector.confidenceNone')
                : fmt.number(entity.pose.spatial_confidence, { maximumFractionDigits: 2 })}
            </Row>
          </dl>
          <h3 className="mt-3 mb-1 text-xs font-bold tracking-wide text-ink-700 uppercase">
            {t('twin.inspector.external')}
          </h3>
          {detail ? (
            detail.external_ids.length > 0 ? (
              <ul className="space-y-1">
                {detail.external_ids.map((x) => (
                  <li
                    key={`${x.source_system}:${x.source_record_id}`}
                    className="font-mono text-xs text-ink-900"
                  >
                    {x.source_system}: {x.source_record_id}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-ink-600">{t('twin.inspector.externalNone')}</p>
            )
          ) : (
            <p className="text-sm text-ink-600">-</p>
          )}
          {Object.keys(entity.attributes).length > 0 ? (
            <>
              <h3 className="mt-3 mb-1 text-xs font-bold tracking-wide text-ink-700 uppercase">
                {t('twin.inspector.attributes')}
              </h3>
              <dl>
                {Object.entries(entity.attributes).map(([k, v]) => (
                  <Row key={k} label={k}>
                    <span className="font-mono text-xs">
                      {typeof v === 'object' ? JSON.stringify(v) : String(v)}
                    </span>
                  </Row>
                ))}
              </dl>
            </>
          ) : null}
        </div>
      )}
    </div>
  );
}

/** Inspector wired to the twin selection; lives in the shell's right panel and the mobile drawer. */
export function ConnectedInspector() {
  const entities = useTwinStore((s) => s.entities);
  const selectedId = useTwinStore((s) => s.selectedEntityId);
  const select = useTwinStore((s) => s.select);
  const entity = selectedId ? (entities.find((e) => e.id === selectedId) ?? null) : null;
  return <InspectorBody entity={entity} onClear={() => select(null)} />;
}

import { useMemo } from 'react';
import { t } from '../../i18n';
import { FloorMap2D } from '../twin/FloorMap2D';
import { sortFloors } from '../twin/sceneModel';
import { useTwinData } from '../twin/useTwinData';
import type { Evidence, Incident } from './incidentApi';
import { overlayFromIncident, pairAtDetection } from './incidentOverlay';

export function IncidentSpatialMap({ incident, evidence }: { incident: Incident; evidence: Evidence | null }) {
  const { state } = useTwinData(incident.site_id);
  const overlay = useMemo(() => overlayFromIncident(incident), [incident]);
  const pair = useMemo(
    () => (state.status === 'ready' ? pairAtDetection(state.entities, evidence) : []),
    [state, evidence],
  );
  if (state.status !== 'ready') {
    return (
      <p role="status" className="text-sm text-ink-600">
        {state.status === 'error' ? state.message : t('twin.loading')}
      </p>
    );
  }
  const floors = sortFloors(state.world.floors);
  const floor = floors.find((f) => f.id === incident.floor_id) ?? floors[0];
  if (!floor) return null;
  return (
    <figure className="flex flex-col gap-2">
      <div className="h-72 overflow-hidden rounded-control border border-line" data-testid="incident-spatial-map">
        <FloorMap2D
          floor={floor}
          zones={state.world.zones}
          racks={state.world.racks}
          docks={state.world.docks}
          entities={pair}
          selectedId={null}
          siteName={state.world.site.name}
          incident={overlay}
        />
      </div>
      <figcaption className="text-xs text-ink-600">{t('incident.spatial.caption')}</figcaption>
    </figure>
  );
}

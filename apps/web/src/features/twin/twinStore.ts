import { create } from 'zustand';
import type { Entity, EntityType } from '@vo/twin-schema';
import type { CameraMode } from './cameraModes';

/**
 * A safety incident (or its first alert) shown in the twin: a marker at the spatial evidence and a highlight on the
 * forklift/worker pair. Advisory information only.
 */
export interface IncidentOverlay {
  /** Alert pair key or incident id: what this overlay stands for. */
  key: string;
  incidentId: string | null;
  severity: string;
  /** Site-local meters (camera focus target or incident location). */
  location: { x: number; y: number; z: number } | null;
  floorId: string | null;
  /** The pair: forklift first, then worker. */
  entityIds: string[];
  forkliftCode: string | null;
  workerCode: string | null;
  /** Set by an explicit "Focus": a later alert of another pair does not replace it. */
  pinned?: boolean;
}

export interface TwinViewState {
  /** Chosen by the user; null = first floor of the world. */
  floorId: string | null;
  selectedEntityId: string | null;
  /** Entities of the loaded site (shared so the shell's inspector panel can resolve the selection). */
  entities: Entity[];
  cameraMode: CameraMode;
  hiddenTypes: EntityType[];
  /** One-shot request from the minimap/2D map: move the camera target to this world point (meters). */
  panRequest: { x: number; y: number; nonce: number } | null;
  incident: IncidentOverlay | null;
  /** One-shot request: fly the camera to frame this world point (cinematic incident focus, REQ-053). */
  focusRequest: { x: number; y: number; z: number; nonce: number } | null;

  setFloorId: (id: string | null) => void;
  select: (id: string | null) => void;
  setEntities: (entities: Entity[]) => void;
  setCameraMode: (mode: CameraMode) => void;
  toggleType: (type: EntityType) => void;
  requestPan: (x: number, y: number) => void;
  /** Highlights an incident without moving the camera; never replaces a pinned (focused) overlay of another key. */
  showIncident: (overlay: IncidentOverlay) => void;
  /** Highlights, switches to its floor, selects the forklift and requests the camera flight. */
  focusIncident: (overlay: IncidentOverlay) => void;
  clearIncident: () => void;
  reset: () => void;
}

const initial = {
  floorId: null,
  selectedEntityId: null,
  entities: [] as Entity[],
  cameraMode: 'overview' as CameraMode,
  hiddenTypes: [] as EntityType[],
  panRequest: null,
  incident: null as IncidentOverlay | null,
  focusRequest: null as TwinViewState['focusRequest'],
};

export const useTwinStore = create<TwinViewState>()((set) => ({
  ...initial,
  setEntities: (entities) => set({ entities }),
  setFloorId: (floorId) => set({ floorId, selectedEntityId: null }),
  select: (selectedEntityId) =>
    set((s) => ({
      selectedEntityId,
      // Focus and follow need a subject: dropping the selection returns to free exploration.
      cameraMode:
        selectedEntityId === null && (s.cameraMode === 'focus' || s.cameraMode === 'follow')
          ? 'explore'
          : s.cameraMode,
    })),
  setCameraMode: (cameraMode) => set({ cameraMode }),
  toggleType: (type) =>
    set((s) => ({
      hiddenTypes: s.hiddenTypes.includes(type)
        ? s.hiddenTypes.filter((x) => x !== type)
        : [...s.hiddenTypes, type],
    })),
  requestPan: (x, y) =>
    set((s) => ({
      cameraMode: 'explore',
      panRequest: { x, y, nonce: (s.panRequest?.nonce ?? 0) + 1 },
    })),
  showIncident: (overlay) =>
    set((s) => {
      if (s.incident?.pinned && s.incident.key !== overlay.key) return s;
      return {
        incident: { ...overlay, pinned: Boolean(s.incident?.key === overlay.key && s.incident.pinned) },
      };
    }),
  focusIncident: (overlay) =>
    set((s) => ({
      incident: { ...overlay, pinned: true },
      floorId: overlay.floorId ?? s.floorId,
      selectedEntityId: overlay.entityIds[0] ?? s.selectedEntityId,
      // Explore: the camera is placed by the focus request, then stays where the user leaves it.
      cameraMode: 'explore',
      focusRequest: overlay.location
        ? { ...overlay.location, nonce: (s.focusRequest?.nonce ?? 0) + 1 }
        : s.focusRequest,
    })),
  clearIncident: () => set({ incident: null }),
  reset: () => set(initial),
}));

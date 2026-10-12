import { create } from 'zustand';
import type { Entity, EntityType } from '@vo/twin-schema';
import type { CameraMode } from './cameraModes';

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

  setFloorId: (id: string | null) => void;
  select: (id: string | null) => void;
  setEntities: (entities: Entity[]) => void;
  setCameraMode: (mode: CameraMode) => void;
  toggleType: (type: EntityType) => void;
  requestPan: (x: number, y: number) => void;
  reset: () => void;
}

const initial = {
  floorId: null,
  selectedEntityId: null,
  entities: [] as Entity[],
  cameraMode: 'overview' as CameraMode,
  hiddenTypes: [] as EntityType[],
  panRequest: null,
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
  reset: () => set(initial),
}));

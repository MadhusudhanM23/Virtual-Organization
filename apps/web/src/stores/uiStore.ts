import { create } from 'zustand';

/** Time lens of the experience (ADR-27). Separate from the data mode of individual records. */
export type Lens = 'LIVE' | 'REPLAY' | 'SIMULATE';

export type PanelId =
  'inspector' | 'camera' | 'events' | 'assistant' | 'performance' | 'heatmap' | 'orderFlow';

export const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

/** Reads the OS-level preference; false where matchMedia is unavailable (SSR, some test envs). */
export function systemPrefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia(REDUCED_MOTION_QUERY).matches;
}

export interface UiState {
  /** Active lens. The shell is LIVE by default; Replay/Simulate UIs set this in later phases. */
  lens: Lens;
  /** Initialised from `prefers-reduced-motion`; user-toggleable in the top bar. */
  reducedMotion: boolean;
  sidebarCollapsed: boolean;
  rightPanelCollapsed: boolean;
  bottomPanelCollapsed: boolean;
  /** Narrow screens: off-canvas navigation. */
  mobileNavOpen: boolean;
  /** Narrow screens: tabbed drawer that replaces the right and bottom panels. */
  drawerOpen: boolean;
  drawerTab: PanelId;
  /** Placeholder until the world model exists (Phase 2); never derived from client-side guesses. */
  selectedSiteId: string | null;

  setLens: (lens: Lens) => void;
  setReducedMotion: (value: boolean) => void;
  toggleReducedMotion: () => void;
  toggleSidebar: () => void;
  toggleRightPanel: () => void;
  toggleBottomPanel: () => void;
  setMobileNavOpen: (open: boolean) => void;
  setDrawerOpen: (open: boolean) => void;
  setDrawerTab: (tab: PanelId) => void;
  setSelectedSiteId: (siteId: string | null) => void;
}

// UI-only state. Authentication state does NOT belong here: the access token will live in memory in a
// dedicated auth store (Plan 1.6) and never in localStorage/sessionStorage (BLUEPRINT §18).
export const useUiStore = create<UiState>()((set) => ({
  lens: 'LIVE',
  reducedMotion: systemPrefersReducedMotion(),
  sidebarCollapsed: false,
  rightPanelCollapsed: false,
  bottomPanelCollapsed: false,
  mobileNavOpen: false,
  drawerOpen: false,
  drawerTab: 'inspector',
  selectedSiteId: null,

  setLens: (lens) => set({ lens }),
  setReducedMotion: (reducedMotion) => set({ reducedMotion }),
  toggleReducedMotion: () => set((s) => ({ reducedMotion: !s.reducedMotion })),
  toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
  toggleRightPanel: () => set((s) => ({ rightPanelCollapsed: !s.rightPanelCollapsed })),
  toggleBottomPanel: () => set((s) => ({ bottomPanelCollapsed: !s.bottomPanelCollapsed })),
  setMobileNavOpen: (mobileNavOpen) => set({ mobileNavOpen }),
  setDrawerOpen: (drawerOpen) => set({ drawerOpen }),
  setDrawerTab: (drawerTab) => set({ drawerTab }),
  setSelectedSiteId: (selectedSiteId) => set({ selectedSiteId }),
}));

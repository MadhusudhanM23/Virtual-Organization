import { afterEach, describe, expect, it, vi } from 'vitest';

function stubReducedMotion(matches: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('prefers-reduced-motion') && matches,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
}

async function freshStore() {
  vi.resetModules();
  return (await import('./uiStore')).useUiStore;
}

describe('uiStore', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('initialises reducedMotion from prefers-reduced-motion: reduce', async () => {
    stubReducedMotion(true);
    expect((await freshStore()).getState().reducedMotion).toBe(true);
  });

  it('defaults to full motion when the OS does not ask for less', async () => {
    stubReducedMotion(false);
    expect((await freshStore()).getState().reducedMotion).toBe(false);
  });

  it('lets the user toggle reduced motion regardless of the OS preference', async () => {
    stubReducedMotion(false);
    const store = await freshStore();
    store.getState().toggleReducedMotion();
    expect(store.getState().reducedMotion).toBe(true);
    store.getState().toggleReducedMotion();
    expect(store.getState().reducedMotion).toBe(false);
  });

  it('starts on the LIVE lens with every panel expanded and no site selected', async () => {
    const state = (await freshStore()).getState();
    expect(state.lens).toBe('LIVE');
    expect(state.sidebarCollapsed).toBe(false);
    expect(state.rightPanelCollapsed).toBe(false);
    expect(state.bottomPanelCollapsed).toBe(false);
    expect(state.selectedSiteId).toBeNull();
  });

  it('collapses and restores panels independently', async () => {
    const store = await freshStore();
    store.getState().toggleRightPanel();
    expect(store.getState().rightPanelCollapsed).toBe(true);
    expect(store.getState().bottomPanelCollapsed).toBe(false);
    store.getState().toggleRightPanel();
    expect(store.getState().rightPanelCollapsed).toBe(false);
  });
});

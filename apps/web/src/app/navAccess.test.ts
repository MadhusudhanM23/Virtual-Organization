import { describe, expect, it } from 'vitest';
import { visibleNavEntries } from './navAccess';
import type { NavEntry } from './navigation';

function ids(entries: NavEntry[]): string[] {
  return entries.flatMap((e) => (e.kind === 'group' ? e.items.map((i) => i.id) : [e.id]));
}

describe('visibleNavEntries (UX hints from the permission matrix)', () => {
  it('Driver / Operator sees only Overview and the live digital twin', () => {
    expect(ids(visibleNavEntries(['DRIVER_OPERATOR']))).toEqual([
      'overview',
      'twin-live',
      'twin-map',
    ]);
  });

  it('Platform Administrator sees Overview and Settings, not business sections', () => {
    expect(ids(visibleNavEntries(['PLATFORM_ADMIN']))).toEqual(['overview', 'settings']);
  });

  it('Executive sees business sections but not Settings', () => {
    const visible = ids(visibleNavEntries(['EXECUTIVE']));
    expect(visible).toContain('orders');
    expect(visible).toContain('twin-simulation');
    expect(visible).not.toContain('settings');
  });

  it('unions multiple roles', () => {
    const visible = ids(visibleNavEntries(['DRIVER_OPERATOR', 'PLATFORM_ADMIN']));
    expect(visible).toEqual(['overview', 'twin-live', 'twin-map', 'settings']);
  });

  it('Incidents: every viewer of operational state except Driver/Operator and Platform Administrator', () => {
    for (const role of ['EXECUTIVE', 'REGIONAL_MANAGER', 'WAREHOUSE_MANAGER', 'ANALYST', 'SAFETY_COMPLIANCE']) {
      expect(ids(visibleNavEntries([role]))).toContain('incidents');
    }
    expect(ids(visibleNavEntries(['DRIVER_OPERATOR']))).not.toContain('incidents');
    expect(ids(visibleNavEntries(['PLATFORM_ADMIN']))).not.toContain('incidents');
  });

  it('Recommendations: the roles that may read them (PROPOSE_RECOMMENDATION), never Driver/Operator', () => {
    for (const role of ['EXECUTIVE', 'REGIONAL_MANAGER', 'WAREHOUSE_MANAGER', 'ANALYST', 'SAFETY_COMPLIANCE']) {
      expect(ids(visibleNavEntries([role]))).toContain('recommendations');
    }
    expect(ids(visibleNavEntries(['DRIVER_OPERATOR']))).not.toContain('recommendations');
    expect(ids(visibleNavEntries(['PLATFORM_ADMIN']))).not.toContain('recommendations');
  });

  it('shows only Overview with no roles', () => {
    expect(ids(visibleNavEntries([]))).toEqual(['overview']);
  });
});

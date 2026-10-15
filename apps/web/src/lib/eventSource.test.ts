import { describe, expect, it } from 'vitest';
import { sourceLabel, sourceName } from './eventSource';

describe('event source names', () => {
  it('never prints the SIMULATION code, which would read as the scenario-result truth label', () => {
    expect(sourceName('SIMULATION')).toBe('simulator');
    expect(sourceLabel('SIMULATION')).toBe('Source: simulator');
    expect(sourceLabel('SIMULATION')).not.toMatch(/SIMULATION/);
  });

  it('names every envelope source and keeps unknown values', () => {
    expect(['API', 'CAMERA', 'DEVICE', 'IMPORT', 'SYSTEM', 'HUMAN'].map(sourceName)).toEqual([
      'API',
      'camera',
      'device',
      'import',
      'system',
      'person',
    ]);
    expect(sourceName('SIM-TMS')).toBe('SIM-TMS');
    expect(sourceName(null)).toBe('');
  });
});

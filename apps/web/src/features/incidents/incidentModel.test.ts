import { describe, expect, it } from 'vitest';
import { ApiError } from '../../lib/api/client';
import { incidentListQuery } from './incidentApi';
import {
  commandErrorMessage,
  formatDistance,
  isOpen,
  offeredActions,
  severityTone,
  statusTone,
} from './incidentModel';

describe('offeredActions', () => {
  it('maps allowed_actions of an UNCONFIRMED incident for a handler', () => {
    const o = offeredActions(
      ['acknowledge', 'false-positive', 'assign', 'estop-request', 'notes'],
      true,
      true,
    );
    expect(o.lifecycle.map((l) => l.action)).toEqual(['acknowledge', 'false-positive']);
    expect(o).toMatchObject({ assign: true, notes: true, estop: true });
    expect(o.lifecycle.find((l) => l.action === 'false-positive')!.reason).toBe('required');
    expect(o.lifecycle.find((l) => l.action === 'acknowledge')!.reason).toBe('optional');
  });

  it('offers only what the state allows (CONFIRMED: resolve, never close)', () => {
    const o = offeredActions(['resolve', 'assign', 'estop-request', 'notes'], true, true);
    expect(o.lifecycle.map((l) => l.action)).toEqual(['resolve']);
    expect(o.lifecycle[0]!.reason).toBe('required');
  });

  it('a CLOSED incident offers nothing', () => {
    expect(offeredActions([], true, true)).toEqual({
      lifecycle: [],
      assign: false,
      notes: false,
      estop: false,
    });
  });

  it('view-only roles get no command buttons even when the state allows them', () => {
    const o = offeredActions(['acknowledge', 'assign', 'estop-request', 'notes'], false, false);
    expect(o).toEqual({ lifecycle: [], assign: false, notes: false, estop: false });
  });

  it('e-stop depends on its own permission, not on incident handling', () => {
    expect(offeredActions(['estop-request'], true, false).estop).toBe(false);
    expect(offeredActions(['estop-request'], false, true).estop).toBe(true);
  });

  it('ignores unknown action names', () => {
    expect(offeredActions(['explode'], true, true).lifecycle).toEqual([]);
  });
});

describe('labels and tones', () => {
  it('never relies on color alone: tones map to distinct icons in StatusPill', () => {
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('HIGH')).toBe('warning');
    expect(statusTone('UNCONFIRMED')).toBe('warning');
    expect(statusTone('RESOLVED')).toBe('success');
    expect(statusTone('CLOSED')).toBe('neutral');
  });

  it('open states', () => {
    expect(['DETECTED', 'UNCONFIRMED', 'CONFIRMED'].every(isOpen)).toBe(true);
    expect(['REJECTED', 'RESOLVED', 'CLOSED'].some(isOpen)).toBe(false);
  });

  it('formats distances without inventing values', () => {
    expect(formatDistance(1.234)).toBe('1.2 m');
    expect(formatDistance(null)).toBe('-');
  });
});

describe('commandErrorMessage', () => {
  it('distinguishes 403, 409, 400 and network failures', () => {
    expect(commandErrorMessage(new ApiError(403, { title: 'Forbidden' }, 'c')).id).toBe(
      'incident.error.forbidden',
    );
    expect(commandErrorMessage(new ApiError(409, { detail: 'Illegal' }, 'c'))).toEqual({
      id: 'incident.error.conflict',
      detail: 'Illegal',
    });
    expect(commandErrorMessage(new ApiError(400, { detail: 'reason required' }, 'c')).id).toBe(
      'incident.error.invalid',
    );
    expect(commandErrorMessage(new TypeError('fetch failed')).id).toBe('incident.error.network');
  });
});

describe('incidentListQuery', () => {
  it('encodes filters as comma lists and the cursor', () => {
    const q = incidentListQuery('s1', { status: ['UNCONFIRMED', 'CONFIRMED'], severity: ['CRITICAL'] }, 'abc');
    const qs = new URLSearchParams(q.split('?')[1]);
    expect(q.startsWith('/incidents?')).toBe(true);
    expect(qs.get('site_id')).toBe('s1');
    expect(qs.get('status')).toBe('UNCONFIRMED,CONFIRMED');
    expect(qs.get('severity')).toBe('CRITICAL');
    expect(qs.get('cursor')).toBe('abc');
  });

  it('omits empty filters', () => {
    const qs = new URLSearchParams(incidentListQuery('s1', { status: [], severity: [] }, null).split('?')[1]);
    expect(qs.has('status')).toBe(false);
    expect(qs.has('cursor')).toBe(false);
  });
});

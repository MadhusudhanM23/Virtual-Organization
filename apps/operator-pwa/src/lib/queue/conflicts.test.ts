import { describe, expect, it } from 'vitest';
import { classifyAck, classifyError } from './conflicts';

const cmd = { kind: 'TASK_TRANSITION' } as const;

describe('section 12 conflict classification', () => {
  it('rule 4: an ack with applied=false / already_terminal is a CONFLICT, not applied twice', () => {
    const out = classifyAck(cmd, {
      result: { applied: false, conflict: 'already_terminal', status: 'COMPLETED' },
    });
    expect(out.status).toBe('CONFLICT');
    expect(out.conflict?.kind).toBe('already_terminal');
  });

  it('a normal ack is SYNCED and keeps the result', () => {
    const out = classifyAck(cmd, { result: { applied: true, status: 'COMPLETED' } });
    expect(out.status).toBe('SYNCED');
    expect(out.result?.status).toBe('COMPLETED');
  });

  it('rule 1: 409 task_reassigned is STALE', () => {
    const out = classifyError(cmd, {
      status: 409,
      problem: { code: 'task_reassigned' },
      detail: 'x',
    });
    expect(out.status).toBe('STALE');
    expect(out.conflict?.kind).toBe('task_reassigned');
  });

  it('rule 2: an inventory scan answered with an EXCEPTION is a CONFLICT (quantity untouched)', () => {
    const out = classifyAck(
      { kind: 'INVENTORY_SCAN' },
      {
        result: {
          outcome: 'EXCEPTION',
          reason: 'inventory_version_changed',
          quantity_overwritten: false,
        },
      },
    );
    expect(out.status).toBe('CONFLICT');
    expect(out.conflict?.kind).toBe('inventory_exception');
    expect(
      classifyAck({ kind: 'INVENTORY_SCAN' }, { result: { outcome: 'RECORDED' } }).status,
    ).toBe('SYNCED');
  });

  it('rule 3: evidence is only SYNCED once the server confirms', () => {
    const out = classifyAck(
      { kind: 'EVIDENCE' },
      { result: { sync_state: 'SYNCED', evidence_id: 'e' } },
    );
    expect(out.status).toBe('SYNCED');
  });

  it('other 409s are CONFLICTs; permission, missing, size and metadata refusals are REJECTED', () => {
    expect(classifyError(cmd, { status: 409, problem: {} }).status).toBe('CONFLICT');
    expect(classifyError(cmd, { status: 403 }).conflict?.kind).toBe('not_permitted');
    expect(classifyError(cmd, { status: 404 }).conflict?.kind).toBe('not_found');
    expect(classifyError(cmd, { status: 413 }).conflict?.kind).toBe('too_large');
    expect(
      classifyError(cmd, { status: 422, problem: { code: 'exif_present' } }).conflict?.kind,
    ).toBe('exif_present');
    expect(classifyError(cmd, { status: 400 }).status).toBe('REJECTED');
  });

  it('network errors and 5xx/429 retry; 401 pauses for sign-in', () => {
    expect(classifyError(cmd, new TypeError('Failed to fetch')).status).toBe('RETRY');
    expect(classifyError(cmd, { status: 503 }).status).toBe('RETRY');
    expect(classifyError(cmd, { status: 429 }).status).toBe('RETRY');
    expect(classifyError(cmd, { status: 401 }).status).toBe('AUTH');
  });
});

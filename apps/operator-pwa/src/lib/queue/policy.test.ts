import { describe, expect, it } from 'vitest';
import { assertQueueable, OfflineRefusalError, refusalOfStoredItem, refusedKindOf } from './policy';

describe('offline refusal policy', () => {
  it('classifies approvals and emergency-stop execution as refused', () => {
    expect(refusedKindOf('APPROVAL')).toBe('APPROVAL');
    expect(refusedKindOf('TASK_TRANSITION', 'approve')).toBe('APPROVAL');
    expect(refusedKindOf('TASK_TRANSITION', 'reject')).toBe('APPROVAL');
    expect(refusedKindOf('EMERGENCY_STOP')).toBe('EMERGENCY_STOP');
    expect(refusedKindOf('X', 'emergency-stop')).toBe('EMERGENCY_STOP');
    expect(refusedKindOf('X', 'estop')).toBe('EMERGENCY_STOP');
  });

  it('lets the ordinary kinds through', () => {
    for (const kind of ['TASK_ISSUE', 'EVIDENCE', 'INVENTORY_SCAN', 'URGENT_RECORD']) {
      expect(() => assertQueueable(kind)).not.toThrow();
    }
    for (const action of ['accept', 'start', 'pause', 'resume', 'complete', 'fail']) {
      expect(() => assertQueueable('TASK_TRANSITION', action)).not.toThrow();
    }
  });

  it('throws typed refusals with stable codes', () => {
    expect(() => assertQueueable('APPROVAL')).toThrowError(OfflineRefusalError);
    try {
      assertQueueable('EMERGENCY_STOP');
      expect.unreachable('must refuse');
    } catch (e) {
      expect((e as OfflineRefusalError).code).toBe('offline_emergency_stop_not_allowed');
    }
  });

  it('refuses unknown kinds and task actions that are not operator actions (cancel is manager-only)', () => {
    expect(() => assertQueueable('SOMETHING')).toThrow();
    expect(() => assertQueueable('TASK_TRANSITION', 'cancel')).toThrow();
  });

  it('the sync-side gate refuses a stored row that claims to be an approval', () => {
    expect(refusalOfStoredItem({ kind: 'APPROVAL' as never, action: null })?.code).toBe(
      'offline_approval_not_allowed',
    );
    expect(refusalOfStoredItem({ kind: 'TASK_TRANSITION', action: 'complete' })).toBeNull();
  });
});

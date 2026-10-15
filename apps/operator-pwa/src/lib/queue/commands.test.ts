import { describe, expect, it } from 'vitest';
import { buildRequest } from './commands';
import { OfflineRefusalError } from './policy';
import { freshStore, make } from './testing';

describe('request building (docs/api/field-sync.md)', () => {
  it('task commands carry queued_offline, captured_at and the base version', async () => {
    const store = await freshStore();
    const item = await make(store, { action: 'complete', taskId: 'abc', body: { base_version: 3 } });
    expect(buildRequest(item)).toEqual({
      path: '/tasks/abc/complete',
      body: { base_version: 3, queued_offline: true, captured_at: '2026-10-15T08:00:00.000Z' },
    });
  });

  it('issues go to /issues, with the offline context', async () => {
    const store = await freshStore();
    const item = await make(store, {
      kind: 'TASK_ISSUE',
      action: null,
      taskId: 'abc',
      body: { issue_type: 'BLOCKED', description: 'pallet in the aisle' },
    });
    const req = buildRequest(item);
    expect(req.path).toBe('/tasks/abc/issues');
    expect(req.body).toMatchObject({ issue_type: 'BLOCKED', queued_offline: true });
  });

  it('evidence uses its stable id as client_ref and sends base64 bytes with the hash', async () => {
    const store = await freshStore();
    const item = await make(store, { kind: 'EVIDENCE', action: null, taskId: 'abc' });
    const req = buildRequest(item);
    expect(req.path).toBe('/tasks/abc/evidence');
    expect(req.body).toMatchObject({
      client_ref: item.id,
      content_type: 'image/jpeg',
      data_base64: 'AQID',
      sha256: 'ab'.repeat(32),
    });
  });

  it('scans and urgent records are site-level', async () => {
    const store = await freshStore();
    const scan = await make(store, { kind: 'INVENTORY_SCAN', action: null, taskId: null, body: { sku: 'S' } });
    expect(buildRequest(scan)).toMatchObject({ path: '/field/inventory-scans', body: { sku: 'S', site_id: 'site-1' } });
    const urgent = await make(store, { kind: 'URGENT_RECORD', action: null, taskId: null, body: { category: 'SAFETY' } });
    expect(buildRequest(urgent).path).toBe('/field/urgent-records');
  });

  it('refuses to build a request for an approval row', async () => {
    const store = await freshStore();
    const item = await make(store);
    expect(() => buildRequest({ ...item, kind: 'APPROVAL' as never })).toThrowError(OfflineRefusalError);
  });
});

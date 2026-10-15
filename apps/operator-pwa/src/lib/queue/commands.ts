import { refusalOfStoredItem } from './policy';
import type { QueueItem } from './types';

/** The HTTP request an item turns into (docs/api/field-sync.md). Pure, so it is unit-tested. */
export interface BuiltRequest {
  path: string;
  body: Record<string, unknown>;
}

function toBase64(data: ArrayBuffer): string {
  const bytes = new Uint8Array(data);
  let binary = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

/** Builds the request for an item; throws OfflineRefusalError for a refused kind (the engine's second gate). */
export function buildRequest(item: QueueItem): BuiltRequest {
  const refusal = refusalOfStoredItem(item);
  if (refusal) throw refusal;
  switch (item.kind) {
    case 'TASK_TRANSITION': {
      if (!item.taskId || !item.action) throw new Error('A task command needs a task and an action');
      return {
        path: `/tasks/${item.taskId}/${item.action}`,
        body: { ...item.body, queued_offline: true, captured_at: item.capturedAt },
      };
    }
    case 'TASK_ISSUE': {
      if (!item.taskId) throw new Error('An issue needs a task');
      return {
        path: `/tasks/${item.taskId}/issues`,
        body: { ...item.body, queued_offline: true, captured_at: item.capturedAt },
      };
    }
    case 'EVIDENCE': {
      if (!item.taskId || !item.blob) throw new Error('Evidence needs a task and a photo');
      return {
        path: `/tasks/${item.taskId}/evidence`,
        body: {
          ...item.body,
          client_ref: item.id,
          content_type: item.blob.contentType,
          data_base64: toBase64(item.blob.data),
          sha256: item.blob.sha256,
          captured_at: item.capturedAt,
        },
      };
    }
    case 'INVENTORY_SCAN':
      return {
        path: '/field/inventory-scans',
        body: { ...item.body, site_id: item.siteId, task_id: item.taskId, captured_at: item.capturedAt },
      };
    case 'URGENT_RECORD':
      return {
        path: '/field/urgent-records',
        body: { ...item.body, site_id: item.siteId, task_id: item.taskId, captured_at: item.capturedAt },
      };
  }
}

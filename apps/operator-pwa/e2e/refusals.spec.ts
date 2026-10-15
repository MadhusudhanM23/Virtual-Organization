import { expect, test } from '@playwright/test';
import { signIn, waitForServiceWorker } from './support/helpers';
import { MockApi } from './support/mockApi';

let mock: MockApi;

test.beforeEach(async ({ context }) => {
  mock = new MockApi();
  await mock.install(context);
});

test('the UI states that approvals and emergency-stop execution do not work offline', async ({ page, context }) => {
  await signIn(page);
  await waitForServiceWorker(page);
  await mock.goOffline(context);
  // Tasks screen: collapsed by default, one tap away.
  await page.getByTestId('offline-limits').getByText('What does not work offline').click();
  await expect(page.getByText('Approvals are not available offline')).toBeVisible();
  await expect(page.getByText('Emergency-stop execution is not available offline')).toBeVisible();

  await page.getByRole('link', { name: 'Sync queue' }).click();
  await expect(page.getByText('Approvals are not available offline')).toBeVisible();
  await page.getByRole('link', { name: 'Urgent' }).click();
  await expect(page.getByText('Emergency-stop execution is not available offline')).toBeVisible();
  await expect(page.getByText('does not stop or start anything')).toBeVisible();

  // There is no approval or emergency-stop control anywhere in the app.
  for (const path of ['/', '/queue', '/urgent']) {
    await page.goto(path);
    await expect(page.getByRole('button', { name: /approve|reject|emergency stop|e-stop/i })).toHaveCount(0);
  }
});

test('an urgent record is queued as advisory and pending, then filed - never executed', async ({ page, context }) => {
  await signIn(page);
  await mock.goOffline(context);
  await page.getByRole('link', { name: 'Urgent' }).click();
  await expect(page.getByTestId('truth-label')).toContainText('LOCAL DEVICE QUEUE');
  await page.getByLabel('Category').selectOption('EMERGENCY');
  await page.getByLabel('What is happening?').fill('Smoke near rack B-12');
  await page.getByLabel('Last known instruction (optional)').fill('Hold position at dock 2');
  await page.getByRole('button', { name: 'Save urgent record' }).click();
  await expect(page.getByTestId('urgent-saved')).toContainText('Nothing was executed');

  await page.getByRole('link', { name: 'Sync queue' }).click();
  const item = page.getByTestId('queue-item');
  await expect(item).toHaveCount(1);
  await expect(item).toContainText('ADVISORY');
  await expect(item.getByTestId('queue-status')).toHaveText('PENDING_SYNC');
  await expect(page.getByTestId('pending-count')).toHaveText('1 pending');

  await mock.goOnline(context);
  await expect(page.getByTestId('pending-count')).toHaveText('All synced', { timeout: 20_000 });
  expect(mock.commands.map((c) => c.path)).toEqual(['/api/field/urgent-records']);
  expect(mock.commands[0]?.body).toMatchObject({
    category: 'EMERGENCY',
    message: 'Smoke near rack B-12',
    last_known_instruction: 'Hold position at dock 2',
    site_id: 'site-1',
  });
  // No request ever went to an approval or emergency-stop endpoint.
  expect(mock.commands.some((c) => /approv|emergency-stop|estop/i.test(c.path))).toBe(false);
});

test('the sync engine refuses an approval or emergency-stop row planted in IndexedDB', async ({ page, context }) => {
  await signIn(page);
  await mock.goOffline(context);
  await page.getByRole('link', { name: 'Sync queue' }).click();
  await page.evaluate(
    () =>
      new Promise<void>((resolve, reject) => {
        const open = indexedDB.open('vo-operator');
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const db = open.result;
          const tx = db.transaction('queue', 'readwrite');
          const base = {
            idempotencyKey: 'planted',
            ownerActorRef: '018f0000-0000-7000-8000-00000000a104',
            taskId: null,
            siteId: 'site-1',
            action: null,
            body: {},
            blob: null,
            capturedAt: new Date().toISOString(),
            status: 'PENDING_SYNC',
            attempts: 0,
            nextAttemptAt: 0,
            createdAt: Date.now(),
            updatedAt: Date.now(),
            lastError: null,
            conflict: null,
            result: null,
          };
          tx.objectStore('queue').add({ ...base, id: 'p1', kind: 'APPROVAL', label: 'Planted approval' });
          tx.objectStore('queue').add({ ...base, id: 'p2', kind: 'EMERGENCY_STOP', label: 'Planted e-stop' });
          tx.oncomplete = () => {
            db.close();
            resolve();
          };
        };
      }),
  );
  await mock.goOnline(context);
  await page.reload();
  await expect(page.getByTestId('queue-item')).toHaveCount(2);
  await expect(page.getByTestId('queue-status').first()).toHaveText('REFUSED', { timeout: 20_000 });
  await expect(page.getByTestId('queue-status').nth(1)).toHaveText('REFUSED');
  await expect(page.getByTestId('conflict-explanation').first()).toContainText('never sent');
  expect(mock.commands).toHaveLength(0);
});

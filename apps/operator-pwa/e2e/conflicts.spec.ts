import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { signIn } from './support/helpers';
import { MockApi, task } from './support/mockApi';

let mock: MockApi;

test.beforeEach(async ({ context }) => {
  mock = new MockApi();
  await mock.install(context);
});

async function queueOffline(page: Page, context: BrowserContext, actions: string[]) {
  await signIn(page);
  await mock.goOffline(context);
  await page.getByRole('link', { name: /Open/ }).first().click();
  for (const a of actions) await page.getByTestId(`action-${a}`).click();
}

test('rule 1: task reassigned while offline - server assignment wins, the item is STALE and the operator must refresh', async ({
  page,
  context,
}) => {
  mock.override = (rec) =>
    rec.path.endsWith('/accept') || rec.path.endsWith('/start')
      ? {
          status: 409,
          body: { title: 'Task reassigned', status: 409, code: 'task_reassigned', detail: 'The task is assigned to someone else now.' },
        }
      : null;
  await queueOffline(page, context, ['accept', 'start']);
  await mock.goOnline(context);

  await expect(page.getByTestId('stale-banner')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByTestId('stale-banner')).toContainText('Refresh before continuing');
  await expect(page.getByTestId('queue-status').first()).toHaveText('STALE');
  await expect(page.getByTestId('queue-status').nth(1)).toHaveText('STALE'); // the later command is blocked too
  await expect(page.getByTestId('conflict-explanation').last()).toContainText('server assignment wins');
  await expect(page.getByTestId('action-start')).toHaveCount(0);
  expect(mock.commands.map((c) => c.path)).toEqual(['/api/tasks/task-1/accept']); // the 2nd was never sent

  // After the operator discards the stale items the pending count clears.
  for (let i = 0; i < 2; i += 1) {
    await page.getByRole('button', { name: 'Discard' }).first().click();
    await page.getByRole('button', { name: /Discard \?/ }).click();
  }
  await expect(page.getByTestId('queue-item')).toHaveCount(0);
});

test('rule 1 on refresh: a task that left my list marks its saved commands stale at once', async ({ page, context }) => {
  await signIn(page);
  await mock.goOffline(context);
  await page.getByRole('link', { name: /Open/ }).first().click();
  await page.getByTestId('action-accept').click();
  await mock.goOnline(context);
  // The server no longer lists the task for me (reassigned) and refuses the command.
  mock.tasks = [task('task-2', 'Other task')];
  mock.override = () => ({ status: 409, body: { title: 'Task reassigned', status: 409, code: 'task_reassigned' } });
  await page.getByRole('link', { name: 'Sync queue' }).click();
  await expect(page.getByTestId('queue-status').first()).toHaveText('STALE', { timeout: 20_000 });
});

test('rule 4: two operators complete the same task - the later command is a CONFLICT, not applied twice', async ({
  page,
  context,
}) => {
  mock.override = (rec) =>
    rec.path.endsWith('/complete')
      ? {
          status: 200,
          body: {
            command_id: 'c',
            status: 'ACCEPTED',
            correlation_id: 'x',
            result: { applied: false, conflict: 'already_terminal', status: 'COMPLETED', version: 5 },
          },
        }
      : null;
  mock.tasks = [task('task-1', 'Pick pallet P-100', { status: 'STARTED', version: 3 })];
  await signIn(page);
  await mock.goOffline(context);
  await page.getByRole('link', { name: /Open/ }).first().click();
  await page.getByTestId('action-complete').click();
  await mock.goOnline(context);

  await expect(page.getByTestId('conflict-banner')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByTestId('queue-status').first()).toHaveText('CONFLICT');
  await expect(page.getByTestId('conflict-explanation')).toContainText('first valid server command won');
  expect(mock.commands.filter((c) => c.path.endsWith('/complete'))).toHaveLength(1); // never retried
});

test('rule 2: an inventory scan against a changed version becomes an exception; the quantity is not overwritten', async ({
  page,
  context,
}) => {
  mock.override = (rec) =>
    rec.path.endsWith('/inventory-scans')
      ? {
          status: 200,
          body: {
            command_id: 'c',
            status: 'ACCEPTED',
            correlation_id: 'x',
            result: {
              outcome: 'EXCEPTION',
              reason: 'inventory_version_changed',
              current_inventory_version: 6,
              quantity_overwritten: false,
            },
          },
        }
      : null;
  await signIn(page);
  await mock.goOffline(context);
  await page.getByRole('link', { name: /Open/ }).first().click();
  await page.getByLabel('SKU').fill('SKU-7');
  await page.getByLabel('Location code').fill('A-01-01');
  await page.getByLabel('Counted quantity').fill('12');
  await page.getByLabel('Inventory version you scanned against').fill('5');
  await page.getByRole('button', { name: 'Save scan' }).click();
  await expect(page.getByTestId('scan-saved')).toBeVisible();
  await mock.goOnline(context);
  await page.getByRole('link', { name: 'Sync queue' }).click();
  await expect(page.getByTestId('queue-status').first()).toHaveText('CONFLICT', { timeout: 20_000 });
  await expect(page.getByTestId('conflict-explanation')).toContainText('The system quantity was not changed');
  expect(mock.commands[0]?.body).toMatchObject({ sku: 'SKU-7', base_inventory_version: 5, counted_quantity: 12 });
});

test('rule 3: proof of delivery stays PENDING_SYNC until the server confirms it', async ({ page, context }) => {
  await signIn(page);
  await mock.goOffline(context);
  await page.getByRole('link', { name: /Open/ }).first().click();
  await page.getByLabel('Take or choose a photo').setInputFiles({
    name: 'pod.png',
    mimeType: 'image/png',
    buffer: (await import('./support/helpers')).pngWithMetadata(),
  });
  await page.getByTestId('photo-save').click();
  await expect(page.getByTestId('task-pending')).toHaveCount(0); // a photo does not change the task status
  await page.getByRole('link', { name: 'Sync queue' }).click();
  await expect(page.getByTestId('queue-status').first()).toHaveText('PENDING_SYNC');
  mock.override = () => ({ status: 503, body: { title: 'Unavailable', status: 503 } });
  await mock.goOnline(context);
  await expect(page.getByText(/1 attempt/)).toBeVisible({ timeout: 20_000 });
  await expect(page.getByTestId('queue-status').first()).toHaveText('PENDING_SYNC'); // not confirmed: still pending
  mock.override = null;
  await page.getByTestId('sync-now').click();
  await expect(page.getByTestId('queue-status').first()).toHaveText('SYNCED', { timeout: 20_000 });
});

test('retry with backoff keeps one Idempotency-Key across every attempt', async ({ page, context }) => {
  mock.override = () => ({ status: 503, body: { title: 'Unavailable', status: 503 } });
  await queueOffline(page, context, ['accept']);
  await mock.goOnline(context);
  await expect(page.getByText(/1 attempt/)).toBeVisible({ timeout: 20_000 });
  await page.getByTestId('sync-now').click();
  await expect(page.getByText(/2 attempt/)).toBeVisible({ timeout: 20_000 });
  mock.override = null;
  await page.getByTestId('sync-now').click();
  await expect(page.getByTestId('pending-count')).toHaveText('All synced', { timeout: 20_000 });
  const keys = mock.commands.map((c) => c.key);
  expect(keys.length).toBeGreaterThanOrEqual(3);
  expect(new Set(keys).size).toBe(1);
});

import { createHash } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { fakeJpegWithExif, pngWithMetadata, signIn, waitForServiceWorker } from './support/helpers';
import { MockApi } from './support/mockApi';

let mock: MockApi;

test.beforeEach(async ({ context }) => {
  mock = new MockApi();
  await mock.install(context);
});

test('queue offline, then sync in order with stable keys; photo is stripped, hashed and bounded', async ({
  page,
  context,
}) => {
  await signIn(page);
  await waitForServiceWorker(page);
  await mock.goOffline(context);
  await expect(page.getByTestId('net-state')).toHaveAttribute('data-online', 'false');

  await page.getByRole('link', { name: /Open/ }).first().click();
  await expect(page.getByRole('heading', { name: 'Pick pallet P-100', level: 1 })).toBeVisible();
  await expect(page.getByTestId('truth-label')).toContainText('SIMULATED');

  await page.getByTestId('action-accept').click();
  await page.getByTestId('action-start').click();
  await page.getByTestId('action-complete').click();
  await expect(page.getByTestId('task-status')).toHaveText('Completed');
  await expect(page.getByTestId('task-pending')).toHaveText('PENDING_SYNC');

  await page.getByLabel('Issue type').selectOption('DAMAGED_GOODS');
  await page.getByLabel('What happened?').fill('Two cartons crushed on the pallet');
  await page.getByRole('button', { name: 'Save issue' }).click();
  await expect(page.getByTestId('issue-saved')).toBeVisible();

  await expect(page.getByTestId('photo-privacy-notice')).toContainText('location (GPS)');
  await page.getByLabel('Take or choose a photo').setInputFiles({
    name: 'pod.png',
    mimeType: 'image/png',
    buffer: pngWithMetadata(),
  });
  await expect(page.getByAltText('Preview of the photo to be saved')).toBeVisible();
  await page.getByLabel('Note (optional)').fill('left at dock 4');
  await page.getByTestId('photo-save').click();
  await expect(page.getByTestId('photo-saved')).toBeVisible();

  await expect(page.getByTestId('pending-count')).toHaveText('5 pending');
  expect(mock.commands).toHaveLength(0); // nothing leaves the device while offline

  await page.getByRole('link', { name: 'Sync queue' }).click();
  await expect(page.getByTestId('queue-item')).toHaveCount(5);
  await expect(page.getByTestId('queue-status').first()).toHaveText('PENDING_SYNC');
  const shownKeys = await page.getByText(/^Idempotency-Key /).allTextContents();
  expect(new Set(shownKeys).size).toBe(5);

  await mock.goOnline(context);
  await expect(page.getByTestId('pending-count')).toHaveText('All synced', { timeout: 20_000 });
  await expect(page.getByTestId('queue-status').filter({ hasText: 'SYNCED' })).toHaveCount(5);

  // Order of capture, each command once, each carrying its own stable Idempotency-Key and the offline context.
  expect(mock.commands.map((c) => c.path)).toEqual([
    '/api/tasks/task-1/accept',
    '/api/tasks/task-1/start',
    '/api/tasks/task-1/complete',
    '/api/tasks/task-1/issues',
    '/api/tasks/task-1/evidence',
  ]);
  const keys = mock.commands.map((c) => c.key);
  expect(new Set(keys).size).toBe(5);
  expect(shownKeys.map((k) => k.replace('Idempotency-Key ', '')).sort()).toEqual([...keys].sort());
  for (const c of mock.commands.slice(0, 4)) expect(c.body?.queued_offline).toBe(true);
  expect(mock.commands[0]?.body?.base_version).toBe(1);
  expect(mock.commands[2]?.body?.base_version).toBe(3); // accept and start were applied locally first
  expect(mock.commands[3]?.body).toMatchObject({ issue_type: 'DAMAGED_GOODS' });

  // Evidence: no hidden metadata or GPS, hash matches, size bounded, same client_ref as the queue item.
  const evidence = mock.commands[4]!.body as Record<string, string>;
  const bytes = Buffer.from(evidence.data_base64!, 'base64');
  const text = bytes.toString('latin1');
  for (const needle of ['tEXt', 'eXIf', 'Exif', 'GPSLatitude', 'Jane Operator'])
    expect(text).not.toContain(needle);
  expect(evidence.sha256).toBe(createHash('sha256').update(bytes).digest('hex'));
  expect(bytes.length).toBeLessThanOrEqual(2 * 1024 * 1024);
  expect(['image/jpeg', 'image/png']).toContain(evidence.content_type);
  expect(evidence.client_ref).toMatch(/^[0-9a-f-]{36}$/);
  expect(evidence.note).toBe('left at dock 4');
});

test('a photo the browser cannot decode is still stripped at byte level before queueing', async ({
  page,
  context,
}) => {
  await signIn(page);
  await mock.goOffline(context);
  await page.getByRole('link', { name: /Open/ }).first().click();
  await page.getByLabel('Take or choose a photo').setInputFiles({
    name: 'raw.jpg',
    mimeType: 'image/jpeg',
    buffer: fakeJpegWithExif(),
  });
  await expect(page.getByTestId('photo-save')).toBeVisible();
  await page.getByTestId('photo-save').click();
  await expect(page.getByTestId('pending-count')).toHaveText('1 pending');
  await mock.goOnline(context);
  await expect(page.getByTestId('pending-count')).toHaveText('All synced', { timeout: 20_000 });
  const body = mock.commands[0]!.body as Record<string, string>;
  const text = Buffer.from(body.data_base64!, 'base64').toString('latin1');
  expect(text).not.toContain('GPSLatitude');
  expect(text).not.toContain('Exif');
  expect(text).toContain('JFIF');
});

test('an unsupported file is refused with a clear message and nothing is queued', async ({
  page,
}) => {
  await signIn(page);
  await page.getByRole('link', { name: /Open/ }).first().click();
  await page.getByLabel('Take or choose a photo').setInputFiles({
    name: 'notes.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('not an image'),
  });
  await expect(page.getByRole('alert').filter({ hasText: 'Only JPEG, PNG or WebP' })).toBeVisible();
  await expect(page.getByTestId('photo-save')).toHaveCount(0);
});

test('nothing is uploaded without a signed-in user; saved work waits and is sent after sign-in', async ({
  page,
  context,
}) => {
  await signIn(page);
  await waitForServiceWorker(page);
  await mock.goOffline(context);
  await page.getByRole('link', { name: /Open/ }).first().click();
  await page.getByTestId('action-accept').click();
  await expect(page.getByTestId('pending-count')).toHaveText('1 pending');

  // The session expires while offline (the refresh cookie is gone), then the connection returns.
  await page.reload();
  await expect(page.getByTestId('pending-count')).toHaveText('1 pending');
  mock.loggedIn = false;
  await mock.goOnline(context);
  await expect(page.getByRole('heading', { name: 'Sign in', level: 1 })).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByTestId('login-pending-notice')).toContainText('1 saved item');
  await page.waitForTimeout(1500);
  expect(mock.commands).toHaveLength(0);

  await page.getByLabel('Organization').fill('demo');
  await page.getByLabel('Email').fill('driver.blr@demo.invalid');
  await page.getByLabel('Password').fill('demo-password-for-tests');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByTestId('pending-count')).toHaveText('All synced', { timeout: 20_000 });
  expect(mock.commands.map((c) => c.path)).toEqual(['/api/tasks/task-1/accept']);
});

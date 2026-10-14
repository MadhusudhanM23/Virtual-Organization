import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, type APIRequestContext, type Page } from '@playwright/test';

/**
 * Shared helpers of the MCD suite (plan 10.1). Everything talks to the real stack through the Vite dev server of
 * playwright.mcd.config.ts. Values come from the SYNTHETIC demo seed (docs/scenarios/demo-tenants.md); nothing here
 * is a measured or invented business number.
 */

export const PASSWORD = process.env.E2E_DEMO_PASSWORD ?? '';
export const TENANT = 'marutiflex-demo';
/** Bengaluru Warehouse 01 (DemoIds). */
export const SITE_ID = '018f0000-0000-7000-8000-00000000a021';
export const SITE_NAME = 'Bengaluru Warehouse 01';

export const USERS = {
  exec: 'exec@demo.invalid',
  wm: 'wm.blr@demo.invalid',
  regional: 'regional.south@demo.invalid',
  safety: 'safety@demo.invalid',
  analyst: 'analyst@demo.invalid',
} as const;

const here = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(here, '../../..');
export const RESULTS = path.resolve(here, '../test-results');

export const VISION_SECRET = process.env.VO_VISION_INGEST_SECRET ?? '';
export const VISION_PYTHON =
  process.env.E2E_VISION_PYTHON ?? path.join(REPO_ROOT, 'services/vision/.venv/Scripts/python.exe');
export const API_ORIGIN = process.env.E2E_API_ORIGIN ?? 'http://localhost:8080';

export async function signIn(page: Page, email: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Organization (tenant)').fill(TENANT);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible({ timeout: 30_000 });
}

/** Selects the Bengaluru site in the top-bar site switcher (only needed for org-wide roles with several sites). */
export async function selectBengaluru(page: Page): Promise<void> {
  const switcher = page.locator('#site-switcher');
  await expect(switcher).toBeVisible({ timeout: 15_000 });
  await switcher.selectOption({ label: SITE_NAME });
  await expect(switcher.locator('option:checked')).toHaveText(SITE_NAME);
}

/** A bearer token for direct API checks (the same login the UI uses). */
export async function token(request: APIRequestContext, email: string): Promise<string> {
  const r = await request.post('/api/auth/login', {
    data: { tenant: TENANT, email, password: PASSWORD },
  });
  expect(r.ok(), `login ${email}: ${r.status()}`).toBe(true);
  return ((await r.json()) as { access_token: string }).access_token;
}

export async function apiGet<T>(request: APIRequestContext, tok: string, url: string): Promise<T> {
  const r = await request.get(url, { headers: { Authorization: `Bearer ${tok}` } });
  expect(r.ok(), `GET ${url}: ${r.status()} ${await r.text()}`).toBe(true);
  return (await r.json()) as T;
}

export async function apiCommand<T>(
  request: APIRequestContext,
  tok: string,
  url: string,
  data: unknown,
): Promise<{ status: number; body: T }> {
  const r = await request.post(url, {
    data,
    headers: { Authorization: `Bearer ${tok}`, 'Idempotency-Key': crypto.randomUUID() },
  });
  return { status: r.status(), body: (await r.json()) as T };
}

/** Every visible truth label inside `scope` (DataModeBadge renders data-truth-label). */
export async function truthLabels(page: Page, scope = 'main'): Promise<string[]> {
  return page
    .locator(`${scope} [data-truth-label]`)
    .evaluateAll((els) =>
      els
        .filter((e) => (e as HTMLElement).offsetParent !== null)
        .map((e) => e.getAttribute('data-truth-label') ?? ''),
    );
}

/** Asserts the global honesty rule of the MCD: no LIVE truth label anywhere, since nothing is a real integration. */
export async function expectNoLiveLabel(page: Page): Promise<void> {
  await expect(page.locator('[data-truth-label="live"]')).toHaveCount(0);
}

export async function shot(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: path.join(RESULTS, `mcd-${name}.png`) });
}

/** Facts measured while the suite runs; written to test-results/mcd-measurements.json for the SLO report. */
export function measure(key: string, value: unknown): void {
  const file = path.join(RESULTS, 'mcd-measurements.json');
  if (!existsSync(RESULTS)) mkdirSync(RESULTS, { recursive: true });
  const all = existsSync(file)
    ? (JSON.parse(readFileSync(file, 'utf-8')) as Record<string, unknown>)
    : {};
  all[key] = value;
  console.log(`[measure] ${key} = ${JSON.stringify(value)}`);
  writeFileSync(file, JSON.stringify(all, null, 2));
}

export interface VisionRun {
  code: number | null;
  stdout: string;
  stderr: string;
  ms: number;
}

/** Runs the vision CLI (services/vision) once on CAM-01's deterministic synthetic scene, in real time (about 12 s). */
export function runVision(cameraId: string): Promise<VisionRun> {
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const child = spawn(
      VISION_PYTHON,
      [
        '-m',
        'vorg_vision',
        'run',
        '--camera-id',
        cameraId,
        '--synthetic',
        '--media-name',
        'synthetic-cam-01.png',
        '--data-mode',
        'SYNTHETIC',
        '--api',
        API_ORIGIN,
        '--realtime',
      ],
      {
        cwd: path.join(REPO_ROOT, 'services/vision'),
        env: { ...process.env, VO_VISION_INGEST_SECRET: VISION_SECRET },
      },
    );
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d: Buffer) => (stdout += d.toString()));
    child.stderr.on('data', (d: Buffer) => (stderr += d.toString()));
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr, ms: Date.now() - started }));
  });
}

export interface Story {
  demoIncidentId?: string;
  visionIncidentId?: string;
  scenarioName?: string;
  scenarioId?: string;
  runId?: string;
  recommendationId?: string;
  approvalId?: string;
}

/**
 * Shared state of the story: each step hands its result to the next. Kept in a file because Playwright restarts the
 * worker after a failed test (module state is lost), and a later step should still report its own pass/fail.
 */
const STORY_FILE = path.join(RESULTS, 'mcd-story.json');
export const story: Story = {};

export function resetStory(): void {
  if (!existsSync(RESULTS)) mkdirSync(RESULTS, { recursive: true });
  writeFileSync(STORY_FILE, '{}');
}

export function loadStory(): Story {
  if (existsSync(STORY_FILE))
    Object.assign(story, JSON.parse(readFileSync(STORY_FILE, 'utf-8')) as Story);
  return story;
}

export function saveStory(patch: Partial<Story>): void {
  Object.assign(story, patch);
  if (!existsSync(RESULTS)) mkdirSync(RESULTS, { recursive: true });
  writeFileSync(STORY_FILE, JSON.stringify(story, null, 2));
}

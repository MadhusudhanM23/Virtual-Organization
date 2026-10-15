import AxeBuilder from '@axe-core/playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

/**
 * Accessibility sweep (plan 11.4, REQ-130): axe-core (WCAG 2.0/2.1/2.2 A and AA plus best-practice rules) on EVERY routed
 * screen at desktop (1280x800) and phone (390x844), as the org-wide Executive, on the real stack. On top of axe:
 *   - reflow: no horizontal scrolling at 390 px (WCAG 1.4.10);
 *   - landmarks: exactly one main, a labelled primary navigation, a banner;
 *   - keyboard (desktop): Tab reaches both the navigation and the main content, every focus stop has a visible focus
 *     indicator, and there is no keyboard trap;
 *   - reduced motion: the running CSS animations with prefers-reduced-motion are listed; Escape leaves no modal open.
 * Everything is written to test-results/a11y-audit.json (source of docs/reports/hardening-a11y-report.md). The final test
 * fails on any SERIOUS or CRITICAL axe violation or on a failed keyboard / reflow / landmark check.
 */
const PASSWORD = process.env.E2E_DEMO_PASSWORD ?? '';
const TENANT = 'marutiflex-demo';
const SITE_ID = '018f0000-0000-7000-8000-00000000a021';
const SITE_NAME = 'Bengaluru Warehouse 01';
test.skip(!PASSWORD, 'E2E_DEMO_PASSWORD is not set');

const VIEWPORTS = {
  desktop: { width: 1280, height: 800 },
  phone: { width: 390, height: 844 },
} as const;
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'];
const RESULTS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../test-results');

interface Screen {
  name: string;
  path: string;
  ready?: string;
}
interface Violation {
  id: string;
  impact: string;
  help: string;
  nodes: number;
  targets: string[];
}
interface Finding {
  screen: string;
  path: string;
  viewport: string;
  violations: Violation[];
  checks: Record<string, unknown>;
  failures: string[];
}
const findings: Finding[] = [];

async function login(page: Page, email: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Organization (tenant)').fill(TENANT);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible({ timeout: 30_000 });
}

async function selectSite(page: Page): Promise<void> {
  const switcher = page.locator('#site-switcher');
  if (await switcher.isVisible().catch(() => false)) {
    await switcher.selectOption({ label: SITE_NAME });
  }
}

async function navigate(page: Page, to: string): Promise<void> {
  await page.evaluate((url) => {
    window.history.pushState({}, '', url);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, to);
  await expect.poll(() => new URL(page.url()).pathname).toBe(to.split('?')[0]);
}

async function token(request: APIRequestContext, email: string): Promise<string> {
  const r = await request.post('/api/auth/login', { data: { tenant: TENANT, email, password: PASSWORD } });
  expect(r.ok()).toBe(true);
  return ((await r.json()) as { access_token: string }).access_token;
}

async function screens(request: APIRequestContext): Promise<Screen[]> {
  const list: Screen[] = [
    { name: 'Overview', path: '/overview', ready: '[data-kpi]' },
    { name: 'Organization', path: '/organization' },
    { name: 'Operations', path: '/operations' },
    { name: 'Incidents', path: '/incidents' },
    { name: 'Cameras', path: '/cameras', ready: '[data-testid="camera-panel"]' },
    { name: 'Recommendations', path: '/recommendations' },
    { name: 'Twin live', path: '/twin/live', ready: '[data-testid="twin-truth"]' },
    { name: 'Twin map', path: '/twin/map', ready: '[data-testid="twin-map-2d"]' },
    { name: 'Simulation', path: '/twin/simulation', ready: '[data-testid="simulate-truth"]' },
    { name: 'Replay', path: '/twin/replay', ready: '[data-testid="replay-time"]' },
    { name: 'Orders', path: '/orders' },
    { name: 'Inventory', path: '/inventory' },
    { name: 'Assets', path: '/assets' },
    { name: 'People', path: '/people' },
    { name: 'Fleet', path: '/fleet' },
    { name: 'Analytics', path: '/analytics' },
    { name: 'Assistant', path: '/assistant' },
    { name: 'Reports', path: '/reports' },
    { name: 'Settings', path: '/settings' },
    { name: 'Not found', path: '/no-such-page' },
  ];
  const tok = await token(request, 'safety@demo.invalid');
  const inc = await request.get(`/api/incidents?site_id=${SITE_ID}&limit=1`, {
    headers: { Authorization: `Bearer ${tok}` },
  });
  const incidents = ((await inc.json()) as { items?: { id: string }[] }).items ?? [];
  if (incidents[0]) {
    list.push({
      name: 'Incident detail',
      path: `/incidents/${incidents[0].id}`,
      ready: '[data-testid="incident-badges"]',
    });
  }
  const wm = await token(request, 'wm.blr@demo.invalid');
  const rec = await request.get(`/api/recommendations?site_id=${SITE_ID}&limit=1`, {
    headers: { Authorization: `Bearer ${wm}` },
  });
  const recs = ((await rec.json()) as { items?: { recommendation_id: string }[] }).items ?? [];
  if (recs[0]) {
    list.push({
      name: 'Recommendation detail',
      path: `/recommendations/${recs[0].recommendation_id}`,
      ready: 'main',
    });
  }
  return list;
}

async function checks(
  page: Page,
  viewport: string,
): Promise<{ data: Record<string, unknown>; failures: string[] }> {
  const failures: string[] = [];
  const data: Record<string, unknown> = {};
  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
  }));
  data.horizontal_overflow_px = Math.max(0, overflow.scrollWidth - overflow.innerWidth);
  if (viewport === 'phone' && overflow.scrollWidth > overflow.innerWidth + 1) {
    failures.push(
      `reflow: page scrolls horizontally (${overflow.scrollWidth}px wide in ${overflow.innerWidth}px)`,
    );
  }
  const lm = await page.evaluate(() => ({
    main: document.querySelectorAll('main, [role="main"]').length,
    banner: document.querySelectorAll('header, [role="banner"]').length,
    navPrimary: document.querySelectorAll('nav[aria-label="Primary"]').length,
  }));
  data.landmarks = lm;
  if (lm.main !== 1) failures.push(`landmarks: ${lm.main} main landmarks`);
  if (lm.banner < 1) failures.push('landmarks: no banner');
  // On a phone the navigation is an off-canvas menu that exists only while it is open.
  if (viewport === 'desktop' && lm.navPrimary < 1) failures.push('landmarks: no Primary navigation');
  return { data, failures };
}

/** Tab through the page and judge focus visibility, traps and reachability. */
async function keyboard(page: Page): Promise<{ data: Record<string, unknown>; failures: string[] }> {
  const failures: string[] = [];
  // Start from the top of the document: focusing the body resets the sequential-focus starting point.
  await page.evaluate(() => {
    document.body.setAttribute('tabindex', '-1');
    document.body.focus();
    document.body.removeAttribute('tabindex');
    window.scrollTo(0, 0);
  });
  const stops: { desc: string; visible: boolean; inNav: boolean; inMain: boolean; key: string }[] = [];
  let sameInRow = 0;
  let last = '';
  for (let i = 0; i < 70; i++) {
    await page.keyboard.press('Tab');
    const s = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body) return null;
      const cs = getComputedStyle(el);
      const outline = cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0;
      const shadow = cs.boxShadow !== 'none' && cs.boxShadow !== '';
      const label = el.getAttribute('aria-label') ?? (el.textContent ?? '').trim().slice(0, 30);
      const r = el.getBoundingClientRect();
      return {
        desc: `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''} "${label}"`,
        visible: outline || shadow,
        inNav: el.closest('nav') !== null,
        inMain: el.closest('main') !== null,
        key: `${el.tagName}|${el.id}|${label}|${Math.round(r.x)}|${Math.round(r.y)}`,
      };
    });
    if (!s) break; // focus left the document (to the browser UI): the cycle is complete
    stops.push(s);
    sameInRow = s.key === last ? sameInRow + 1 : 0;
    last = s.key;
    if (sameInRow >= 3) {
      failures.push(`keyboard trap: focus stuck on ${s.desc}`);
      break;
    }
  }
  const invisible = stops.filter((s) => !s.visible).map((s) => s.desc);
  if (invisible.length) {
    failures.push(`no visible focus indicator on: ${[...new Set(invisible)].slice(0, 6).join('; ')}`);
  }
  if (!stops.some((s) => s.inNav)) failures.push('keyboard: Tab never reaches the navigation');
  if (!stops.some((s) => s.inMain)) failures.push('keyboard: Tab never reaches the main content');
  return {
    data: {
      tab_stops: stops.length,
      first_stop: stops[0]?.desc ?? null,
      invisible_focus: [...new Set(invisible)].slice(0, 6),
    },
    failures,
  };
}

test.describe.configure({ mode: 'serial' });

test('axe sweep, desktop and 390 px, every routed screen', async ({ page, request }) => {
  const list = await screens(request);
  await page.setViewportSize(VIEWPORTS.desktop);
  await login(page, 'exec@demo.invalid');
  await selectSite(page);

  for (const [vpName, size] of Object.entries(VIEWPORTS)) {
    await page.setViewportSize(size);
    for (const s of list) {
      await navigate(page, s.path);
      if (s.ready) await page.locator(s.ready).first().waitFor({ timeout: 30_000 }).catch(() => undefined);
      await page.waitForTimeout(1200);
      const axe = await new AxeBuilder({ page }).withTags(TAGS).analyze();
      const violations: Violation[] = axe.violations.map((v) => ({
        id: v.id,
        impact: v.impact ?? 'minor',
        help: v.help,
        nodes: v.nodes.length,
        targets: v.nodes.slice(0, 3).map((n) => n.target.join(' ')),
      }));
      const c = await checks(page, vpName);
      const failures = [...c.failures];
      const data = { ...c.data };
      if (vpName === 'desktop') {
        const k = await keyboard(page);
        failures.push(...k.failures);
        Object.assign(data, k.data);
      }
      findings.push({ screen: s.name, path: s.path, viewport: vpName, violations, checks: data, failures });
      const bad = violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
      console.log(
        `${s.name.padEnd(22)} ${vpName.padEnd(8)} axe: ${violations.length} (serious/critical ${bad.length}) checks: ${
          failures.length ? failures.join(' | ') : 'ok'
        }`,
      );
    }
  }
  mkdirSync(RESULTS, { recursive: true });
  writeFileSync(
    path.join(RESULTS, 'a11y-audit.json'),
    JSON.stringify({ at: new Date().toISOString(), findings }, null, 2),
  );
});

test('reduced motion: running animations are listed, and Escape leaves no modal open', async ({ page }) => {
  await page.setViewportSize(VIEWPORTS.desktop);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await login(page, 'exec@demo.invalid');
  await selectSite(page);
  const out: Record<string, unknown> = {};
  for (const p of ['/overview', '/incidents', '/twin/map', '/assistant']) {
    await navigate(page, p);
    await page.waitForTimeout(1500);
    out[p] = await page.evaluate(() =>
      document
        .getAnimations()
        .filter((a) => a.playState === 'running')
        .map(
          (a) =>
            ((a as CSSAnimation).animationName ??
              (a as CSSTransition).transitionProperty ??
              'unknown') as string,
        ),
    );
  }
  findings.push({
    screen: 'reduced-motion',
    path: '*',
    viewport: 'desktop',
    violations: [],
    checks: out,
    failures: [],
  });
  await page.keyboard.press('Escape');
  expect(await page.locator('[role="dialog"][aria-modal="true"]').count()).toBe(0);
  mkdirSync(RESULTS, { recursive: true });
  writeFileSync(
    path.join(RESULTS, 'a11y-audit.json'),
    JSON.stringify({ at: new Date().toISOString(), findings }, null, 2),
  );
});

test('no serious or critical violation and no failed check on any screen', () => {
  const bad = findings.flatMap((f) => [
    ...f.violations
      .filter((v) => v.impact === 'serious' || v.impact === 'critical')
      .map((v) => `${f.screen} @${f.viewport}: ${v.id} (${v.impact}) ${v.targets.join(', ')}`),
    ...f.failures.map((m) => `${f.screen} @${f.viewport}: ${m}`),
  ]);
  expect(bad, bad.join('\n')).toEqual([]);
});

import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  PASSWORD,
  RESULTS,
  SITE_ID,
  USERS,
  apiGet,
  selectBengaluru,
  signIn,
  token,
} from './support';

/**
 * Truth-label audit of every screen (plan 10.2, REQ-111, BLUEPRINT section 22, ADR-27/46) on the REAL stack. For each
 * routed screen it records every DataModeBadge (data-truth-label) in the whole document and checks:
 *   1. no LIVE label anywhere: the MCD has no real customer integration, so LIVE would be a false claim;
 *   2. every badge's visible text is the label of its kind (no badge says one thing and means another);
 *   3. the lens rules: SIMULATE lens -> only SIMULATION in the page; REPLAY lens -> a REPLAY label, items keep their
 *      recorded SIMULATED/SYNTHETIC mode (ADR-46); LIVE lens -> SIMULATED/SYNTHETIC/MIXED only;
 *   4. data screens carry at least one label, and record collections (KPI cards, incident rows, camera list, events)
 *      carry one per item; placeholder screens render no number at all.
 * The per-screen findings are written to test-results/truth-label-audit.json (source of docs/reports/mcd-slo-report.md).
 */

test.skip(!PASSWORD, 'E2E_DEMO_PASSWORD is not set');

const TEXT: Record<string, string> = {
  live: 'LIVE',
  simulated: 'SIMULATED',
  synthetic: 'SYNTHETIC',
  replay: 'REPLAY',
  simulation: 'SIMULATION',
  mixed: 'MIXED',
};

type Lens = 'LIVE' | 'REPLAY' | 'SIMULATE';
interface Screen {
  name: string;
  path: string;
  lens: Lens;
  /** A data screen must show at least one truth label. */
  data: boolean;
  /** Record collections that need a label per item: [item selector, label selector inside it]. */
  perItem?: string;
  /** Wait for this before auditing (the data has arrived). */
  ready?: string;
}

interface Finding {
  screen: string;
  path: string;
  lens: string;
  labels: Record<string, number>;
  items_checked: number;
  violations: string[];
}

const findings: Finding[] = [];

/** Client-side navigation (React Router listens to popstate): no full reload of the dev bundle per screen. */
async function navigate(page: Page, to: string): Promise<void> {
  await page.evaluate((url) => {
    window.history.pushState({}, '', url);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, to);
  await expect.poll(() => new URL(page.url()).pathname + new URL(page.url()).search).toBe(to);
}

async function audit(page: Page, s: Screen): Promise<Finding> {
  await navigate(page, s.path);
  if (s.ready) await expect(page.locator(s.ready).first()).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(1500);
  const lens =
    (await page.locator('[role="status"][data-lens]').first().getAttribute('data-lens')) ?? '';
  const badges = await page.locator('[data-truth-label]').evaluateAll((els) =>
    els
      .filter((e) => (e as HTMLElement).offsetParent !== null || e.closest('details') !== null)
      .map((e) => ({
        kind: e.getAttribute('data-truth-label') ?? '',
        text: (e.textContent ?? '').trim(),
        inMain: e.closest('main') !== null,
        // Scenario results outside the SIMULATE lens: the assistant's citations of scenarios / simulation runs.
        inScenarioResult: e.closest('[data-testid="assistant-answer"]') !== null,
      })),
  );
  const violations: string[] = [];
  const labels: Record<string, number> = {};
  for (const b of badges) labels[b.kind] = (labels[b.kind] ?? 0) + 1;

  if (lens !== s.lens) violations.push(`lens banner says ${lens}, expected ${s.lens}`);
  for (const b of badges) {
    if (b.kind === 'live') violations.push(`LIVE label "${b.text}" on synthetic/simulated data`);
    if (!b.text.startsWith(TEXT[b.kind] ?? '?'))
      violations.push(`badge kind ${b.kind} shows "${b.text}"`);
  }
  const main = badges.filter((b) => b.inMain).map((b) => b.kind);
  if (s.lens === 'SIMULATE' && main.some((k) => k !== 'simulation')) {
    violations.push(
      `SIMULATE lens shows non-SIMULATION labels in the page: ${[...new Set(main)].join(', ')}`,
    );
  }
  if (s.lens === 'REPLAY') {
    if (!main.includes('replay')) violations.push('REPLAY lens without a REPLAY label');
    if (main.some((k) => !['replay', 'simulated', 'synthetic'].includes(k))) {
      violations.push(`REPLAY lens shows ${[...new Set(main)].join(', ')}`);
    }
  }
  const liveMain = badges.filter(
    (b) => b.inMain && !(b.inScenarioResult && b.kind === 'simulation'),
  );
  if (
    s.lens === 'LIVE' &&
    liveMain.some((b) => !['simulated', 'synthetic', 'mixed'].includes(b.kind))
  ) {
    violations.push(`LIVE lens page shows ${[...new Set(liveMain.map((b) => b.kind))].join(', ')}`);
  }
  // Outside the SIMULATE lens no visible text may say SIMULATION except a SIMULATION truth label on a scenario
  // result: the raw event source code SIMULATION reads like that label (fixed in plan 10.2).
  if (s.lens !== 'SIMULATE') {
    const stray = await page.evaluate(() => {
      const hits: string[] = [];
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        const el = n.parentElement;
        if (!el || el.offsetParent === null) continue;
        if (!/\bSIMULATION\b/.test(n.textContent ?? '')) continue;
        if (el.closest('[data-truth-label="simulation"]')) continue;
        hits.push((n.textContent ?? '').trim().slice(0, 60));
      }
      return hits;
    });
    for (const h of stray.slice(0, 5))
      violations.push(`text "${h}" says SIMULATION outside a scenario result`);
  }
  if (s.data && main.length === 0) violations.push('data screen without any truth label');
  if (!s.data) {
    const text = (await page.locator('main').innerText()).replace(/Phase \d+/g, '');
    if (/\d/.test(text))
      violations.push(
        `placeholder screen renders a number: "${text.match(/.{0,30}\d.{0,30}/)?.[0]}"`,
      );
  }
  let items = 0;
  if (s.perItem) {
    // An item that states "Insufficient data" shows no value, so it has nothing to label.
    const unlabeled = await page.locator(s.perItem).evaluateAll((els) =>
      els
        .filter((e) => e.querySelector('[data-truth-label]') === null)
        .filter((e) => !(e.textContent ?? '').includes('Insufficient data'))
        .map((e) => (e.textContent ?? '').slice(0, 60)),
    );
    items = await page.locator(s.perItem).count();
    for (const u of unlabeled) violations.push(`item without a truth label: "${u.trim()}"`);
  }
  const f = { screen: s.name, path: s.path, lens, labels, items_checked: items, violations };
  findings.push(f);
  console.log(
    `${s.name.padEnd(28)} lens=${lens.padEnd(8)} labels=${JSON.stringify(labels)} items=${items} ${
      violations.length ? 'VIOLATIONS: ' + violations.join(' | ') : 'ok'
    }`,
  );
  return f;
}

async function screensFor(request: APIRequestContext): Promise<Screen[]> {
  const tok = await token(request, USERS.safety);
  const incidents = await apiGet<{ items: { id: string }[] }>(
    request,
    tok,
    `/api/incidents?site_id=${SITE_ID}&limit=1`,
  );
  const wmTok = await token(request, USERS.wm);
  const recs = await apiGet<{ items: { recommendation_id: string }[] }>(
    request,
    wmTok,
    `/api/recommendations?site_id=${SITE_ID}&limit=1`,
  );
  const screens: Screen[] = [
    {
      name: 'Overview (KPIs)',
      path: '/overview',
      lens: 'LIVE',
      data: true,
      perItem: '[data-kpi]',
      ready: '[data-kpi]',
    },
    { name: 'Organization', path: '/organization', lens: 'LIVE', data: false },
    { name: 'Operations', path: '/operations', lens: 'LIVE', data: false },
    {
      name: 'Incidents list',
      path: '/incidents',
      lens: 'LIVE',
      data: true,
      perItem: '[data-testid="incident-row"]',
      ready: '[data-testid="incident-row"]',
    },
    {
      name: 'Cameras',
      path: '/cameras',
      lens: 'LIVE',
      data: true,
      perItem: '[data-testid="camera-list"] li',
      ready: '[data-testid="camera-panel"]',
    },
    {
      name: 'Recommendations list',
      path: '/recommendations',
      lens: 'LIVE',
      data: true,
      perItem: '[data-testid="rec-row"]',
      ready: '[data-testid="rec-row"]',
    },
    {
      name: 'Twin live (3D)',
      path: '/twin/live',
      lens: 'LIVE',
      data: true,
      ready: '[data-testid="twin-truth"]',
    },
    {
      name: 'Twin map (2D)',
      path: '/twin/map',
      lens: 'LIVE',
      data: true,
      ready: '[data-testid="twin-map-2d"]',
    },
    {
      name: 'Simulation',
      path: '/twin/simulation',
      lens: 'SIMULATE',
      data: true,
      ready: '[data-testid="simulate-truth"]',
    },
    {
      name: 'Replay',
      path: '/twin/replay',
      lens: 'REPLAY',
      data: true,
      ready: '[data-testid="replay-time"]',
    },
    { name: 'Orders', path: '/orders', lens: 'LIVE', data: false },
    { name: 'Inventory', path: '/inventory', lens: 'LIVE', data: false },
    { name: 'Assets', path: '/assets', lens: 'LIVE', data: false },
    { name: 'People', path: '/people', lens: 'LIVE', data: false },
    { name: 'Fleet', path: '/fleet', lens: 'LIVE', data: false },
    { name: 'Analytics', path: '/analytics', lens: 'LIVE', data: false },
    { name: 'Assistant (empty)', path: '/assistant', lens: 'LIVE', data: false },
    { name: 'Reports', path: '/reports', lens: 'LIVE', data: false },
    { name: 'Settings', path: '/settings', lens: 'LIVE', data: false },
  ];
  if (incidents.items[0]) {
    screens.push({
      name: 'Incident detail',
      path: `/incidents/${incidents.items[0].id}`,
      lens: 'LIVE',
      data: true,
      ready: '[data-testid="incident-badges"]',
    });
  }
  if (recs.items[0]) {
    screens.push({
      name: 'Recommendation detail',
      path: `/recommendations/${recs.items[0].recommendation_id}`,
      lens: 'LIVE',
      data: true,
      ready: '[data-testid="rec-status"]',
    });
  }

  return screens;
}

function writeFindings(): void {
  mkdirSync(RESULTS, { recursive: true });
  writeFileSync(
    path.join(RESULTS, `truth-label-audit-${test.info().title.split(' ').pop()}.json`),
    JSON.stringify(findings, null, 2),
  );
}

for (const user of [USERS.exec, USERS.wm]) {
  const who = user.split('@')[0];
  test(`truth labels on every screen of the demo path as ${who}`, async ({ page, request }) => {
    test.setTimeout(300_000);
    findings.length = 0;
    const screens = await screensFor(request);
    await signIn(page, user);
    if (user === USERS.exec) await selectBengaluru(page);
    for (const s of screens) await audit(page, { ...s, name: `${s.name} [${who}]` });

    // The events panel in the right column: every event row is labeled.
    await navigate(page, '/twin/live');
    const panel = page.getByTestId('events-panel');
    if (await panel.count()) {
      await panel.getByRole('checkbox', { name: 'Movement' }).check();
      await expect
        .poll(async () => panel.getByTestId('event-row').count(), { timeout: 60_000 })
        .toBeGreaterThan(3);
      const rows = panel.getByTestId('event-row');
      const unlabeled = await rows.evaluateAll(
        (els) => els.filter((e) => !e.querySelector('[data-truth-label]')).length,
      );
      const live = await panel.locator('[data-truth-label="live"]').count();
      const f: Finding = {
        screen: `Events panel [${who}]`,
        path: '/twin/live (right column)',
        lens: 'LIVE',
        labels: {},
        items_checked: await rows.count(),
        violations: [
          ...(unlabeled ? [`${unlabeled} event rows without a truth label`] : []),
          ...(live ? [`${live} LIVE labels in the events panel`] : []),
        ],
      };
      findings.push(f);
      console.log(
        `${f.screen.padEnd(28)} items=${f.items_checked} ${f.violations.length ? f.violations.join(' | ') : 'ok'}`,
      );
    }
    writeFindings();
    const bad = findings.filter((f) => f.violations.length > 0);
    expect(bad.map((f) => `${f.screen}: ${f.violations.join(' | ')}`)).toEqual([]);
  });
}

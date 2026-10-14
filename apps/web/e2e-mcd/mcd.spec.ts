import { expect, test, type Page } from '@playwright/test';
import {
  PASSWORD,
  SITE_ID,
  SITE_NAME,
  USERS,
  VISION_PYTHON,
  VISION_SECRET,
  apiCommand,
  apiGet,
  expectNoLiveLabel,
  loadStory,
  measure,
  runVision,
  saveStory,
  selectBengaluru,
  shot,
  signIn,
  story,
  token,
  truthLabels,
} from './support';
import { existsSync } from 'node:fs';

/**
 * The Minimum Credible Demonstration (BLUEPRINT section 22, REQ-110), one test per MCD step, against the REAL local
 * stack (scripts/mcd-up.ps1): API with the local profile (SYNTHETIC seed, SIMULATED WMS/TMS and movement), AI gateway
 * (offline provider), vision CLI. Nothing is mocked. The steps share one story (incident -> replay, scenario -> run ->
 * recommendation -> execution); see docs/scenarios/mcd-runbook.md for the presenter script of the same steps.
 * Screenshots (1440x900) go to test-results/mcd-*.png.
 */

test.skip(!PASSWORD, 'E2E_DEMO_PASSWORD is not set');
test.describe.configure({ mode: 'default' });

const pageErrors = new WeakMap<Page, string[]>();
test.beforeEach(({ page }) => {
  const errors: string[] = [];
  pageErrors.set(page, errors);
  page.on('pageerror', (e) => errors.push(e.message));
  loadStory();
});
test.afterEach(({ page }) => {
  expect(pageErrors.get(page) ?? [], 'uncaught page errors').toEqual([]);
});

/** IN-02 closed from shift start for five hours: actionable at every SIM-TMS phase (see the runbook, step 8). */
const CLOSURE = { dock: 'IN-02', start: 0, end: 300 };
/** The seeded SIM-TMS inbound bookings; a previous MCD run may have moved them (the execution is real). */
const SEEDED_BOOKINGS: Record<string, string> = {
  'TRIP-00001': 'IN-01',
  'TRIP-00002': 'IN-02',
  'TRIP-00005': 'IN-03',
};

test('MCD 1: organization overview as an Executive (KPIs with truth labels, both sites in scope)', async ({
  page,
}) => {
  await signIn(page, USERS.exec);
  await page
    .getByRole('navigation', { name: 'Primary' })
    .getByRole('link', { name: 'Overview' })
    .click();
  await expect(page).toHaveURL(/\/overview$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Overview' })).toBeVisible();
  // The executive sees the organization: both demo sites are in the site switcher.
  const options = await page.locator('#site-switcher option').allTextContents();
  expect(options).toEqual(expect.arrayContaining([SITE_NAME, 'Mumbai Warehouse 02']));
  // KPI cards come from the API, each with its own truth label.
  await expect
    .poll(async () => page.locator('[data-kpi]').count(), { timeout: 30_000 })
    .toBeGreaterThan(3);
  const labels = await truthLabels(page);
  expect(labels.length).toBeGreaterThan(0);
  expect(labels.every((l) => l === 'simulated' || l === 'synthetic' || l === 'mixed')).toBe(true);
  await expectNoLiveLabel(page);
  await shot(page, '01-overview');
});

test('MCD 2: navigate into the simulated Bengaluru warehouse', async ({ page }) => {
  await signIn(page, USERS.exec);
  await selectBengaluru(page);
  await page
    .getByRole('link', { name: /live view/i })
    .first()
    .click();
  await expect(page).toHaveURL(/\/twin\/live/);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByTestId('twin-truth').getByRole('img', { name: /simulated/i })).toBeVisible(
    {
      timeout: 30_000,
    },
  );
  await expect(page.locator('[data-lens="LIVE"][role="status"]')).toContainText('LIVE lens');
  await expectNoLiveLabel(page);
  await shot(page, '02-bengaluru');
});

test('MCD 3: live 3D twin with forklifts, workers, pallets and docks; entities move', async ({
  page,
}) => {
  await signIn(page, USERS.wm);
  await page
    .getByRole('link', { name: /live view/i })
    .first()
    .click();
  await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live', {
    timeout: 30_000,
  });
  await expect(page.getByTestId('twin-canvas')).toBeVisible({ timeout: 30_000 });
  const legend = page.getByTestId('twin-legend');
  for (const type of ['Forklift', 'Worker', 'Pallet']) {
    await expect(
      legend.getByRole('button', { name: new RegExp(`^${type}\\s+\\d+`) }),
    ).toBeVisible();
  }
  await expect(legend).toContainText(/dock/i);
  await expect(
    page.getByTestId('twin-live-stream').getByRole('img', { name: /simulated/i }),
  ).toBeVisible({
    timeout: 30_000,
  });
  await page.waitForTimeout(2500);
  await shot(page, '03-twin-3d');

  // Movement: the 2D map markers change position between two looks (SIMULATED movement source).
  await page
    .getByRole('link', { name: /map view/i })
    .first()
    .click();
  await expect(page.getByTestId('twin-map-2d')).toBeVisible({ timeout: 30_000 });
  const positions = () =>
    page.getByTestId('twin-map-2d').evaluate((root) => {
      const out: Record<string, string> = {};
      root.querySelectorAll('[data-entity-id]').forEach((el) => {
        out[el.getAttribute('data-entity-id') ?? ''] = el.getAttribute('transform') ?? '';
      });
      return out;
    });
  const before = await positions();
  await page.waitForTimeout(6000);
  const after = await positions();
  const moved = Object.keys(after).filter(
    (id) => before[id] !== undefined && before[id] !== after[id],
  );
  console.log(`2D markers ${Object.keys(after).length}, moved in 6 s: ${moved.length}`);
  expect(moved.length).toBeGreaterThan(3);
  await expectNoLiveLabel(page);
});

test('MCD 4: a simulated camera feed with labeled detections (vision pipeline -> camera panel)', async ({
  page,
  request,
}) => {
  test.skip(VISION_SECRET.length < 24, 'VO_VISION_INGEST_SECRET (24+ characters) is not set');
  test.skip(!existsSync(VISION_PYTHON), `vision venv not found at ${VISION_PYTHON}`);
  const tok = await token(request, USERS.wm);
  const cams = await apiGet<{ items: { camera_id: string; code: string }[] }>(
    request,
    tok,
    `/api/cameras?site_id=${SITE_ID}`,
  );
  const cam01 = cams.items.find((c) => c.code === 'CAM-01');
  expect(cam01, 'CAM-01 in the registry').toBeTruthy();
  const since = new Date(Date.now() - 1000).toISOString();

  // The real pipeline: deterministic synthetic scene (worker + forklift) -> tracker -> homography -> observations.
  const run = await runVision(cam01!.camera_id);
  console.log(run.stdout.trim(), run.stderr.trim());
  expect(run.code, run.stderr).toBe(0);
  const summary = JSON.parse(run.stdout.trim().split('\n').pop() ?? '{}') as {
    observations?: number;
  };
  expect(summary.observations ?? 0).toBeGreaterThan(0);

  // The vision proximity rule raised an incident from camera observations (source CAMERA, SYNTHETIC).
  await expect
    .poll(
      async () => {
        // The pair may already have an open incident from an earlier run: the new alert then joins it (alert_count
        // grows, last_alert_at moves) instead of opening a second one, so match on the latest alert time.
        const list = await apiGet<{
          items: { id: string; source: string; last_alert_at: string }[];
        }>(request, tok, `/api/incidents?site_id=${SITE_ID}&limit=20`);
        const hit = list.items.find((i) => i.source === 'CAMERA' && i.last_alert_at >= since);
        if (hit) saveStory({ visionIncidentId: hit.id });
        return Boolean(hit);
      },
      { timeout: 30_000 },
    )
    .toBe(true);

  await signIn(page, USERS.wm);
  await page.goto(`/cameras?camera=${cam01!.camera_id}`);
  const panel = page.getByTestId('camera-panel');
  await expect(panel).toBeVisible({ timeout: 30_000 });
  await expect(panel.getByRole('heading', { level: 2 })).toContainText('CAM-01');
  await expect(panel.getByRole('img', { name: /synthetic/i }).first()).toBeVisible();
  await expect(page.getByTestId('camera-overlay')).toBeVisible({ timeout: 30_000 });
  const legend = panel.getByRole('list', { name: 'Tracked objects by class' });
  await expect(legend).toContainText(/person: \d+ tracks/);
  await expect(legend).toContainText(/forklift: \d+ tracks/);
  await expect(panel).toContainText('synthetic-ground-truth');
  await expectNoLiveLabel(page);
  await shot(page, '04-camera');
});

test('MCD 5: trigger a worker/forklift proximity situation; the alert appears at once as DETECTED/UNCONFIRMED', async ({
  page,
  request,
}) => {
  await signIn(page, USERS.safety);
  await selectBengaluru(page);
  await page
    .getByRole('link', { name: /live view/i })
    .first()
    .click();
  await expect(page.getByTestId('live-connection')).toHaveAttribute('data-connection', 'live', {
    timeout: 30_000,
  });
  const demo = page.getByTestId('demo-control');
  await expect(demo).toContainText('DEMO CONTROL');
  await demo.getByRole('button', { name: 'Trigger proximity situation' }).click();
  await expect(demo.getByTestId('demo-countdown')).toContainText(/Alert expected in about \d+ s/);
  const triggered = Date.now();

  const toast = page.getByTestId('proximity-alert').first();
  await expect(toast).toBeVisible({ timeout: 30_000 });
  const alertMs = Date.now() - triggered;
  measure('mcd5_trigger_to_alert_visible_ms', alertMs);
  await expect(toast).toContainText(/Proximity alert: (HIGH|CRITICAL)/);
  await expect(toast.getByRole('img', { name: /synthetic/i })).toBeVisible();
  await shot(page, '05-alert');

  // The incident behind the alert is UNCONFIRMED (detected, not yet confirmed by a person).
  const tok = await token(request, USERS.safety);
  const list = await apiGet<{
    items: { id: string; status: string; source: string; data_mode: string }[];
  }>(request, tok, `/api/incidents?site_id=${SITE_ID}&limit=5`);
  const latest = list.items.find((i) => i.source !== 'CAMERA');
  expect(latest?.status).toBe('UNCONFIRMED');
  expect(latest?.data_mode).toBe('SYNTHETIC');
  saveStory({ demoIncidentId: latest!.id });
  await expectNoLiveLabel(page);
});

test('MCD 6: open the incident; the camera focuses the spatial evidence and the timeline explains the sequence', async ({
  page,
}) => {
  expect(story.demoIncidentId, 'incident from MCD 5').toBeTruthy();
  await signIn(page, USERS.safety);
  await page.goto(`/incidents/${story.demoIncidentId}`);
  const badges = page.getByTestId('incident-badges');
  await expect(badges).toBeVisible({ timeout: 15_000 });
  await expect(badges.getByRole('img', { name: 'Status: Unconfirmed' })).toBeVisible();
  await expect(badges.getByRole('img', { name: /synthetic/i })).toBeVisible();
  await expect
    .poll(async () => page.getByTestId('timeline-item').count(), { timeout: 15_000 })
    .toBeGreaterThan(1);
  await expect(page.getByTestId('incident-timeline')).toContainText(
    /PROXIMITY_ALERT_RAISED|Proximity alert/i,
  );
  await expect
    .poll(async () => page.getByTestId('evidence-item').count(), { timeout: 15_000 })
    .toBeGreaterThan(0);
  await expect(page.getByTestId('incident-spatial-map')).toBeVisible();
  await shot(page, '06-incident');

  // The vision incident links its evidence back to the observing camera at the incident time.
  if (story.visionIncidentId) {
    await page.goto(`/incidents/${story.visionIncidentId}`);
    const camLink = page.getByTestId('evidence-camera-link').first();
    await expect(camLink).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('incident-evidence')).toContainText(/Observed by camera CAM-01/);
    await shot(page, '06-incident-vision-evidence');
    await camLink.click();
    await expect(page).toHaveURL(/\/cameras\?camera=.*&session=.*&t=/);
    await expect(page.getByTestId('camera-panel')).toBeVisible({ timeout: 15_000 });
    await shot(page, '06-camera-at-incident');
  }

  // Focus: the twin camera flies to the incident from the alert / the incident page.
  await page.goto(`/incidents/${story.demoIncidentId}`);
  await expect(badges).toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: 'Focus in the 3D twin' }).click();
  await expect(page).toHaveURL(/\/twin\/live/);
  await expect(page.getByTestId('twin-incident-banner')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('twin-viewport')).toHaveAttribute('data-camera-target', /\d/, {
    timeout: 15_000,
  });
  await page.waitForTimeout(3000);
  await shot(page, '06-focus');
  await expectNoLiveLabel(page);
});

test('MCD 7: replay the incident from the last snapshot plus state-delta events', async ({
  page,
}) => {
  expect(story.demoIncidentId, 'incident from MCD 5').toBeTruthy();
  await signIn(page, USERS.safety);
  await page.goto(`/incidents/${story.demoIncidentId}`);
  await page.getByTestId('incident-replay').click();
  await expect(page).toHaveURL(/\/twin\/replay\?at=.*&incident=/);
  const started = Date.now();
  await expect(page.locator('[data-lens="REPLAY"][role="status"]')).toContainText('REPLAY');
  await expect(
    page.getByTestId('replay-truth').getByRole('img', { name: /replay/i }),
  ).toBeVisible();
  await expect(page.getByTestId('replay-time')).toBeVisible({ timeout: 30_000 });
  measure('mcd7_replay_open_to_time_visible_ms', Date.now() - started);
  // Provenance: reconstructed from a snapshot plus events.
  await expect(page.getByTestId('replay-provenance')).toContainText(/snapshot/i, {
    timeout: 30_000,
  });
  await shot(page, '07-replay-before');
  await page.getByRole('button', { name: '2x' }).click();
  await page.getByTestId('replay-play').click();
  await expect(page.getByTestId('replay-incidents')).toContainText(
    /Incidents at replay time \([1-9]/,
    {
      timeout: 60_000,
    },
  );
  await page.getByTestId('replay-play').click();
  await shot(page, '07-replay-incident');
  // In REPLAY nothing is labeled LIVE; the page is labeled REPLAY and each replayed item keeps its recorded data mode
  // (ADR-46: lens and data mode are shown separately), which is SIMULATED or SYNTHETIC in the MCD.
  await expectNoLiveLabel(page);
  const labels = await truthLabels(page);
  expect(labels).toContain('replay');
  expect(labels.every((l) => ['replay', 'simulated', 'synthetic'].includes(l))).toBe(true);
});

test('MCD 8: create a scenario that closes a dock for a defined interval', async ({
  page,
  request,
}) => {
  const tok = await token(request, USERS.wm);
  // Restore the seeded SIM-TMS bookings through the DEMO CONTROL if a previous run's execution moved them.
  type Rec = { source_record_id: string; payload: { dock_name?: string; direction?: string } };
  const bookings = async () => {
    const r = await apiGet<{ items: Rec[] }>(
      request,
      tok,
      `/api/integration/records?site_id=${SITE_ID}&kind=SHIPMENT&limit=50`,
    );
    return Object.fromEntries(r.items.map((i) => [i.source_record_id, i.payload.dock_name ?? '']));
  };
  const now = await bookings();
  for (const [trip, dock] of Object.entries(SEEDED_BOOKINGS)) {
    if (now[trip] !== dock) {
      const r = await apiCommand(request, tok, '/api/demo/integration/tms-change', {
        site_id: SITE_ID,
        trip_code: trip,
        to_dock: dock,
      });
      expect(r.status, JSON.stringify(r.body)).toBe(200);
    }
  }
  await expect
    .poll(
      async () => {
        const b = await bookings();
        return Object.entries(SEEDED_BOOKINGS).every(([trip, dock]) => b[trip] === dock);
      },
      { timeout: 60_000, intervals: [2000] },
    )
    .toBe(true);

  await signIn(page, USERS.wm);
  await page
    .getByRole('link', { name: /simulation/i })
    .first()
    .click();
  await expect(page).toHaveURL(/\/twin\/simulation/);
  await expect(
    page.getByTestId('simulate-truth').getByRole('img', { name: /simulation/i }),
  ).toBeVisible();
  const name = `MCD close ${CLOSURE.dock} ${new Date().toISOString().slice(0, 19)}`;
  await page.getByTestId('scenario-create').click();
  await expect(page.getByTestId('scenario-baseline')).toContainText('bengaluru-warehouse-01');
  await page.getByLabel('Name').fill(name);
  await page.getByRole('button', { name: 'Add dock closure' }).click();
  const row = page.getByTestId('dock-closure-row');
  await row.getByLabel('Dock', { exact: true }).selectOption(CLOSURE.dock);
  await row.getByLabel('Start').fill(String(CLOSURE.start));
  await row.getByLabel('End').fill(String(CLOSURE.end));
  await shot(page, '08-scenario-form');
  await page.getByTestId('scenario-submit').click();
  const created = page.getByTestId('scenario-row').filter({ hasText: name });
  await expect(created).toBeVisible({ timeout: 15_000 });
  await expect(created).toContainText('1 dock closure');
  await expect(created).toContainText(CLOSURE.dock);
  saveStory({ scenarioName: name });
  await expectNoLiveLabel(page);
  await shot(page, '08-scenario-created');
});

test('MCD 9: run a deterministic simulation; compare baseline vs scenario throughput and queue outcomes', async ({
  page,
  request,
}) => {
  expect(story.scenarioName, 'scenario from MCD 8').toBeTruthy();
  await signIn(page, USERS.wm);
  await page.goto('/twin/simulation');
  await page.getByTestId('scenario-row').filter({ hasText: story.scenarioName! }).click();
  await expect(page.getByTestId('run-panel')).toBeVisible();
  const clicked = Date.now();
  await page.getByTestId('run-trigger').click();
  await expect(page.getByTestId('compare-results')).toBeVisible({ timeout: 60_000 });
  measure('mcd9_run_click_to_compare_visible_ms', Date.now() - clicked);
  const deltas = page.getByTestId('kpi-delta');
  // Queue and throughput outcomes, baseline vs scenario (headings of the compare view).
  const wait = deltas.filter({
    has: page.getByRole('heading', { name: 'Inbound truck dock wait (mean)' }),
  });
  await expect(wait).toContainText('Worse');
  await expect(
    deltas.filter({
      has: page.getByRole('heading', { name: 'Inbound yard queue (time average)' }),
    }),
  ).toBeVisible();
  await expect(
    deltas.filter({ has: page.getByRole('heading', { name: 'Inbound throughput' }) }),
  ).toBeVisible();
  await expect(
    deltas.filter({ has: page.getByRole('heading', { name: 'Orders shipped' }) }),
  ).toBeVisible();
  await expect(page.getByTestId('affected-entities')).toContainText(CLOSURE.dock);
  const hash1 = (await page.getByTestId('comparison-hash').textContent()) ?? '';
  expect(hash1).toMatch(/[0-9a-f]{8}/);
  await shot(page, '09-compare');
  // Every label in the SIMULATE lens is SIMULATION; never LIVE.
  const labels = await truthLabels(page);
  expect(labels.length).toBeGreaterThan(0);
  expect(labels.every((l) => l === 'simulation')).toBe(true);
  await expectNoLiveLabel(page);

  // Determinism (REQ-072): the same scenario and seed give byte-identical results.
  const tok = await token(request, USERS.wm);
  const scenarios = await apiGet<{ items: { id: string; name: string }[] }>(
    request,
    tok,
    `/api/scenarios?site_id=${SITE_ID}&limit=50`,
  );
  const sc = scenarios.items.find((s) => s.name === story.scenarioName);
  expect(sc).toBeTruthy();
  const runs = await apiGet<{
    items: { run_id: string; status: string; results_hash: string | null }[];
  }>(request, tok, `/api/simulation-runs?scenario_id=${sc!.id}`);
  const first = runs.items.find((r) => r.status === 'SUCCEEDED');
  expect(first?.results_hash).toBeTruthy();
  saveStory({ scenarioId: sc!.id, runId: first!.run_id });
  const again = await apiCommand<{ result: { run_id: string } }>(
    request,
    tok,
    `/api/scenarios/${sc!.id}/runs`,
    {},
  );
  expect(again.status).toBe(200);
  let second: { status: string; results_hash: string | null } | null = null;
  await expect
    .poll(
      async () => {
        second = await apiGet(request, tok, `/api/simulation-runs/${again.body.result.run_id}`);
        return second!.status;
      },
      { timeout: 60_000 },
    )
    .toBe('SUCCEEDED');
  expect(second!.results_hash).toBe(first!.results_hash);
});

test('MCD 10: ask the assistant why the bottleneck matters; it cites authorized evidence and points to the zone', async ({
  page,
}) => {
  await signIn(page, USERS.wm);
  await page
    .getByRole('navigation', { name: 'Primary' })
    .getByRole('link', { name: 'Assistant' })
    .click();
  const panel = page.getByTestId('assistant-panel').first();
  const asked = Date.now();
  await panel.getByRole('button', { name: 'Why does the bottleneck matter?' }).click();
  const answer = panel.getByTestId('assistant-answer').first();
  await expect(answer).toBeVisible({ timeout: 30_000 });
  measure('mcd10_ask_to_answer_visible_ms', Date.now() - asked);
  await expect(answer).toContainText('Grounded: every number is cited');
  await expect(answer.getByRole('link', { name: /^Source \d+$/ }).first()).toBeVisible();
  await expect(answer).toContainText(/Sources \(\d+\)/);
  await expect(answer).toContainText(CLOSURE.dock);
  await expect(answer.getByTestId('assistant-provider')).toContainText('OFFLINE');
  const labels = await truthLabels(page, '[data-testid="assistant-answer"]');
  expect(labels.length).toBeGreaterThan(0);
  expect(labels).not.toContain('live');
  // The cited scenario and simulation run are scenario results: SIMULATION, not SIMULATED (ADR-27).
  expect(labels).toContain('simulation');
  await shot(page, '10-assistant');

  // Points to the affected zone: "Show in twin" highlights it in the live twin.
  await answer.getByRole('button', { name: 'Show in twin' }).click();
  await expect(page).toHaveURL(/\/twin\/live/);
  await expect(page.getByTestId('twin-assistant-banner')).toContainText(
    'Showing what the assistant pointed at',
  );
  await page.waitForTimeout(2500);
  await shot(page, '10-assistant-twin');
  await expectNoLiveLabel(page);
});

test('MCD 11: generate a recommendation; approval succeeds only for a second eligible user on an unchanged baseline', async ({
  page,
  browser,
}) => {
  expect(story.scenarioName, 'scenario from MCD 8').toBeTruthy();
  await signIn(page, USERS.wm);
  await page.goto('/twin/simulation');
  await page.getByTestId('scenario-row').filter({ hasText: story.scenarioName! }).click();
  await page.getByTestId('run-trigger').click();
  await expect(page.getByTestId('compare-results')).toBeVisible({ timeout: 60_000 });
  await page.getByTestId('generate-recommendation').click();
  await expect(page).toHaveURL(/\/recommendations\/[0-9a-f-]{36}$/, { timeout: 30_000 });
  const recId = page.url().split('/').pop()!;
  saveStory({ recommendationId: recId });
  await expect(page.getByTestId('rec-status')).toHaveAttribute('data-status', 'PROPOSED');
  await expect(page.getByTestId('rec-badges').getByRole('img', { name: /simulat/i })).toBeVisible();
  await expect(page.getByTestId('rec-feasibility')).toContainText(/OPTIMAL|FEASIBLE/);
  await expect(page.getByTestId('rec-plan-step').first()).toContainText('REASSIGN_TRIP_DOCK');
  await expect
    .poll(async () => page.getByTestId('dry-run-row').count(), { timeout: 15_000 })
    .toBeGreaterThan(0);
  await expect(page.getByTestId('dry-run-row').first()).toContainText('dock_code');
  // Separation of duties: the proposer cannot approve.
  await expect(page.getByTestId('sod-message')).toBeVisible();
  await expect(page.getByTestId('approve')).toBeDisabled();
  await expect(page.locator('main')).not.toContainText(/confidence\s*[:=]/i);
  await expectNoLiveLabel(page);
  await shot(page, '11-recommendation-proposed');

  // A second eligible person (Regional Manager South) approves after seeing the dry-run diff.
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const approver = await ctx.newPage();
  await signIn(approver, USERS.regional);
  await approver.goto(`/recommendations/${recId}`);
  await expect
    .poll(async () => approver.getByTestId('dry-run-row').count(), { timeout: 15_000 })
    .toBeGreaterThan(0);
  await expect(approver.getByTestId('sod-message')).toHaveCount(0);
  await expect(approver.getByTestId('approve')).toBeEnabled();
  await approver.getByTestId('approve').click();
  await expect(approver.getByTestId('approval-done')).toHaveText('Approved.', { timeout: 15_000 });
  await expect(approver.getByTestId('approval-status')).toHaveAttribute('data-status', 'APPROVED');
  await expect(approver.getByTestId('rec-status')).toHaveAttribute('data-status', 'APPROVED');
  await approver.screenshot({ path: 'test-results/mcd-11-approved.png' });
  await ctx.close();
});

test('MCD 12: execute the safe demo command against the simulated source adapter and verify the state/event chain', async ({
  page,
  request,
}) => {
  expect(story.recommendationId, 'recommendation from MCD 11').toBeTruthy();
  await signIn(page, USERS.wm);
  await page.goto(`/recommendations/${story.recommendationId}`);
  await expect(page.getByTestId('rec-status')).toHaveAttribute('data-status', 'APPROVED', {
    timeout: 15_000,
  });
  const clicked = Date.now();
  await page.getByTestId('execute').click();
  await expect(page.getByTestId('execution-outcome')).toHaveAttribute('data-outcome', 'VERIFIED', {
    timeout: 90_000,
  });
  measure('mcd12_execute_click_to_verified_ms', Date.now() - clicked);
  await expect(page.getByTestId('rec-status')).toHaveAttribute('data-status', 'VERIFIED');
  const steps = page.getByTestId('execution-step');
  await expect.poll(async () => steps.count()).toBeGreaterThan(0);
  for (const s of await steps.all()) await expect(s).toHaveAttribute('data-status', 'VERIFIED');
  await shot(page, '12-verified');
  await expectNoLiveLabel(page);

  // The state/event chain on the API: execution started -> step(s) applied -> source change observed -> verified.
  const tok = await token(request, USERS.wm);
  type Env = { event_type: string; data_mode: string; source: string };
  const ev = await apiGet<{ items: Env[] }>(
    request,
    tok,
    `/api/events?site_id=${SITE_ID}&aggregate_id=${story.recommendationId}&limit=50`,
  );
  const types = ev.items.map((e) => e.event_type);
  for (const t of [
    'RECOMMENDATION_PROPOSED',
    'RECOMMENDATION_EXECUTION_STARTED',
    'RECOMMENDATION_STEP_APPLIED',
    'RECOMMENDATION_VERIFIED',
  ]) {
    expect(types, `event chain contains ${t}`).toContain(t);
  }
  expect(ev.items.every((e) => e.data_mode !== 'LIVE')).toBe(true);
  const rec = await apiGet<{
    status: string;
    execution: { steps: { status: string; observed_event_id: string }[] };
  }>(request, tok, `/api/recommendations/${story.recommendationId}`);
  expect(rec.status).toBe('VERIFIED');
  for (const s of rec.execution.steps) expect(s.observed_event_id).toBeTruthy();
});

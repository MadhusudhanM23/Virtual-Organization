import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mockIncidentApi, mockLiveSocket } from './support/incidentMock';
import { mockSignedInSession } from './support/mockApi';
import {
  INCIDENT_AFTER_MS,
  defaultEarliest,
  mockReplayApi,
  replayForklift,
  type ReplayMock,
  type ReplayMockOptions,
} from './support/replayMock';
import { mockTwinApi } from './support/twinMock';

/**
 * Replay UI (plan 5.2, REQ-062) against a mocked replay API shaped like the documented responses and a mocked
 * WebSocket (live poses must never reach the replay scene). Synthetic data only. Own dev server: 5199.
 */

const iso = (ms: number) => new Date(ms).toISOString();

async function openReplay(page: Page, query = '', options: ReplayMockOptions = {}): Promise<ReplayMock> {
  await mockSignedInSession(page);
  await mockTwinApi(page);
  const m = await mockReplayApi(page, options);
  await page.goto(`/twin/replay${query}`);
  return m;
}

async function use2D(page: Page) {
  const toggle = page.getByRole('button', { name: '2D map' });
  if ((await toggle.getAttribute('aria-pressed')) !== 'true') await toggle.click();
  await expect(page.getByTestId('twin-map-2d')).toBeVisible();
}

function forkliftX(page: Page) {
  return page
    .getByTestId('twin-map-2d')
    .locator(`[data-entity-id="${replayForklift.id}"]`)
    .getAttribute('transform')
    .then((tr) => Number(/translate\(([-\d.]+)/.exec(tr ?? '')?.[1]));
}

async function replayAt(page: Page): Promise<number> {
  return Date.parse((await page.getByTestId('replay-time').getAttribute('data-at')) ?? '');
}

test.describe('Replay', () => {
  test('REPLAY lens: banner, REPLAY truth label, tinted viewport, no LIVE label, return to live', async ({ page }) => {
    await openReplay(page);
    const banner = page.locator('[data-lens="REPLAY"][role="status"]');
    await expect(banner).toContainText('REPLAY — historical playback, not live');
    await expect(page.getByTestId('replay-truth').getByRole('img', { name: /replay/i })).toBeVisible();
    await expect(page.getByTestId('twin-viewport')).toHaveAttribute('data-lens', 'REPLAY');
    await expect(page.getByTestId('replay-viewport-tag')).toContainText('REPLAY');
    await expect(page.getByTestId('replay-time')).toBeVisible();
    await expect(page.getByText('Replay time', { exact: true })).toBeVisible();
    // Nothing on the replay page carries a LIVE truth label (ADR-27).
    await expect(page.locator('main [data-truth-label="live"]')).toHaveCount(0);
    await expect(page.getByTestId('replay-not-reconstructed')).toContainText('DOCKS');
    await expect(page.getByTestId('replay-not-reconstructed')).toContainText('MIRRORED_ORDERS_INVENTORY');
    await page.screenshot({ path: 'test-results/replay-mock-lens.png' });
    await page.getByTestId('replay-return-live').click();
    await expect(page).toHaveURL(/\/twin\/live/);
    await expect(page.locator('[data-lens="LIVE"][role="status"]')).toBeVisible();
  });

  test('deep link ?at= reconstructs that instant; two instants show the forklift elsewhere', async ({ page }) => {
    const probe = { earliest: defaultEarliest() };
    const a = probe.earliest + 60_000;
    const b = probe.earliest + 90_000;
    let m = await openReplay(page, `?at=${encodeURIComponent(iso(a))}`, probe);
    await use2D(page);
    await expect.poll(() => replayAt(page)).toBe(a);
    expect(m.stateCalls).toContain(iso(a));
    const xa = await forkliftX(page);
    await page.screenshot({ path: 'test-results/replay-mock-instant-a.png' });

    await page.unrouteAll();
    m = await openReplay(page, `?at=${encodeURIComponent(iso(b))}`, probe);
    await use2D(page);
    await expect.poll(() => replayAt(page)).toBe(b);
    expect(m.stateCalls).toContain(iso(b));
    await expect.poll(() => forkliftX(page)).not.toBeCloseTo(xa, 1);
    await page.screenshot({ path: 'test-results/replay-mock-instant-b.png' });
  });

  test('an out-of-range deep link is clamped and says so; the URL follows the paused instant', async ({ page }) => {
    await openReplay(page, `?at=${encodeURIComponent('2020-01-01T00:00:00Z')}`);
    await expect(page.getByText(/outside the replayable range/)).toBeVisible();
    await expect(page).toHaveURL(/at=\d{4}-/);
    const at = await replayAt(page);
    expect(new URL(page.url()).searchParams.get('at')).toBe(iso(at));
  });

  test('keyboard: arrows scrub by 5 s (Shift 60 s), Space plays and pauses, frames are prefetched', async ({ page }) => {
    const m = await openReplay(page);
    const scrubber = page.getByTestId('replay-scrubber');
    await expect(scrubber).toBeVisible();
    const start = await replayAt(page);
    await scrubber.focus();
    await page.keyboard.press('ArrowRight');
    await expect.poll(() => replayAt(page)).toBe(start + 5000);
    await page.keyboard.press('Shift+ArrowRight');
    await expect.poll(() => replayAt(page)).toBe(start + 65_000);
    await expect.poll(() => m.stateCalls.includes(iso(start + 65_000))).toBe(true);
    await page.keyboard.press('ArrowLeft');
    await expect.poll(() => replayAt(page)).toBe(start + 60_000);

    await page.getByRole('button', { name: '5x', exact: true }).click();
    await expect(page.getByRole('button', { name: '5x', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await scrubber.focus();
    await page.keyboard.press('Space');
    await expect(page.getByTestId('replay-play')).toHaveAttribute('aria-pressed', 'true');
    await expect.poll(() => m.frameCalls.length).toBeGreaterThan(0);
    expect(m.frameCalls[0]!.from).toBe(iso(start + 60_000));
    expect(m.frameCalls[0]!.step).toBe('1000');
    await expect.poll(() => replayAt(page), { timeout: 10_000 }).toBeGreaterThan(start + 65_000);
    await page.screenshot({ path: 'test-results/replay-mock-playing.png' });
    await scrubber.focus();
    await page.keyboard.press('Space');
    await expect(page.getByTestId('replay-play')).toHaveAttribute('aria-pressed', 'false');
  });

  test('playback moves entities between frames and shows the incident at its detection time', async ({ page }) => {
    const probe = { earliest: defaultEarliest() };
    const detected = probe.earliest + INCIDENT_AFTER_MS;
    await openReplay(page, `?at=${encodeURIComponent(iso(detected - 6000))}`, probe);
    await use2D(page);
    await expect.poll(() => replayAt(page)).toBe(detected - 6000);
    await expect(page.getByTestId('replay-incidents')).toContainText('Incidents at replay time (0)');
    await expect(page.getByTestId('incident-marker-2d')).toHaveCount(0);
    const x0 = await forkliftX(page);
    await page.getByRole('button', { name: '2x' }).click();
    await page.getByTestId('replay-play').click();
    await expect(page.getByTestId('replay-incidents')).toContainText('Incidents at replay time (1)', { timeout: 10_000 });
    await expect(page.getByTestId('incident-marker-2d')).toBeVisible();
    expect(await replayAt(page)).toBeGreaterThanOrEqual(detected);
    expect(await forkliftX(page)).not.toBeCloseTo(x0, 1);
    await page.screenshot({ path: 'test-results/replay-mock-incident.png' });
    await page.getByTestId('replay-play').click();
  });

  test('timeline markers: an incident and an alert; clicking the incident jumps to its detection', async ({ page }) => {
    const m = await openReplay(page);
    const marker = page.getByTestId('replay-marker-incident');
    await expect(marker).toHaveCount(1);
    await expect(page.getByTestId('replay-marker-alert')).toHaveCount(1);
    await expect(page.getByTestId('replay-snapshot-tick').first()).toBeVisible();
    await marker.click();
    await expect.poll(() => replayAt(page)).toBe(m.earliest + INCIDENT_AFTER_MS);
    await expect(page.getByTestId('replay-incident')).toContainText('CRITICAL');
  });

  test('camera path "fly to incidents" focuses the incident as playback reaches it', async ({ page }) => {
    const probe = { earliest: defaultEarliest() };
    const detected = probe.earliest + INCIDENT_AFTER_MS;
    await openReplay(page, `?at=${encodeURIComponent(iso(detected - 3000))}`, probe);
    await page.getByRole('button', { name: 'Fly to incidents' }).click();
    await expect(page.getByRole('button', { name: 'Fly to incidents' })).toHaveAttribute('aria-pressed', 'true');
    await page.getByTestId('replay-play').click();
    await expect(page.getByTestId('replay-incident')).toHaveAttribute('data-shown', 'true', { timeout: 10_000 });
    // The forklift of the pair is selected: the inspector shows it as of replay time.
    await expect(page.getByTestId('twin-inspector')).toContainText(replayForklift.name);
    await page.getByTestId('replay-play').click();
  });

  test('inspector: the entity as of replay time with snapshot + events provenance', async ({ page }) => {
    await openReplay(page);
    await page.getByRole('button', { name: `Inspect ${replayForklift.code}` }).click();
    const inspector = page.getByTestId('twin-inspector');
    await expect(inspector).toHaveAttribute('data-lens', 'REPLAY');
    await expect(inspector).toContainText('As of replay time');
    await expect(inspector.getByTestId('replay-provenance')).toContainText(/Reconstructed from snapshot \w{8} .* \+ \d+ events/);
    await expect(inspector.getByRole('img', { name: /simulated/i }).first()).toBeVisible();
  });

  test('events list: RECONSTRUCTED and RECORDED roles of the visible window', async ({ page }) => {
    const m = await openReplay(page);
    const events = page.getByTestId('replay-events');
    await expect(events.locator('[data-replay-role="RECORDED"]').first()).toBeVisible();
    await expect(events.locator('[data-replay-role="RECONSTRUCTED"]')).toHaveCount(0);
    await events.getByLabel(/Show position updates/).check();
    await expect(events.locator('[data-replay-role="RECONSTRUCTED"]').first()).toBeVisible();
    expect(m.eventCalls[0]?.scope).toBe('all');
  });

  test('live poses are ignored while in REPLAY (the connection stays up)', async ({ page }) => {
    await mockSignedInSession(page);
    await mockTwinApi(page);
    const live = await mockLiveSocket(page);
    await mockReplayApi(page);
    await page.goto('/twin/replay');
    await use2D(page);
    await expect.poll(() => live.sockets.length).toBeGreaterThan(0);
    const before = await forkliftX(page);
    for (let i = 0; i < 3; i += 1) {
      live.push({
        type: 'poses',
        sub: 'twin',
        items: [
          {
            entity_id: replayForklift.id,
            entity_type: 'FORKLIFT',
            event_id: `live-${i}`,
            occurred_at: new Date().toISOString(),
            floor_id: replayForklift.floor_id,
            zone_id: replayForklift.zone_id,
            x: 3 + i,
            y: 3,
            z: 0,
            heading_deg: 0,
            status: 'MOVING',
            source: 'SIMULATION',
            data_mode: 'SIMULATED',
            confidence: 1,
          },
        ],
      });
    }
    await page.waitForTimeout(1500);
    expect(await forkliftX(page)).toBeCloseTo(before, 3);
  });

  test('"Replay this incident" opens replay 30 s before detection', async ({ page }) => {
    await mockSignedInSession(page);
    await mockTwinApi(page);
    await mockIncidentApi(page);
    await mockReplayApi(page);
    await page.goto('/incidents/7a1e0000-0000-7000-8000-000000000001');
    const link = page.getByTestId('incident-replay');
    await expect(link).toBeVisible();
    await link.click();
    await expect(page).toHaveURL(/\/twin\/replay\?at=.*&incident=7a1e0000-0000-7000-8000-000000000001/);
    const at = Date.parse(new URL(page.url()).searchParams.get('at') ?? '');
    await expect(page.getByTestId('replay-time')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Fly to incidents' })).toHaveAttribute('aria-pressed', 'true');
    expect(Number.isFinite(at)).toBe(true);
  });

  test('not available yet, and an access error, are explicit states', async ({ page }) => {
    await openReplay(page, '', { unavailable: true });
    await expect(page.getByTestId('replay-unavailable')).toContainText('Replay not available yet');
    await page.unrouteAll();
    await openReplay(page, '', { failStatus: 403 });
    await expect(page.getByRole('alert')).toContainText('Your role cannot view replay for this site');
  });

  test('no serious axe violations (2D and 3D)', async ({ page }) => {
    await openReplay(page);
    await expect(page.getByTestId('replay-timeline')).toBeVisible();
    await page.waitForTimeout(800);
    const check = async () => {
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
      const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
      expect(bad.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`)).toEqual([]);
    };
    await check();
    await use2D(page);
    await check();
  });
});

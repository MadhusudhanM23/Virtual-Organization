import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mockSignedInSession } from './support/mockApi';
import {
  mockRecommendationsApi,
  mockResimulation,
  REC_ID,
  type RecommendationsMockOptions,
} from './support/recommendationsMock';
import { mockSimulationApi } from './support/simulateMock';

/**
 * Recommendations UI (Plan 8.4, REQ-090..096) against a mocked recommendation / approval / execution API shaped like
 * docs/api/recommendations.md: propose from a run, the dry-run diff before approve, approve -> execute -> VERIFIED,
 * the separation-of-duties message, stale 409 -> re-simulate, axe and a phone viewport. Synthetic data only. Own dev
 * server: 5201.
 */

async function openDetail(page: Page, options: RecommendationsMockOptions = {}) {
  await mockSignedInSession(page);
  const mock = await mockRecommendationsApi(page, options);
  await page.goto(`/recommendations/${REC_ID}`);
  await expect(page.getByTestId('recommendation-detail')).toBeVisible();
  await expect(page.getByTestId('rec-rationale')).toBeVisible();
  return mock;
}

async function expectNoSeriousAxeViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(bad.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
}

test.describe('Recommendations', () => {
  test('propose from a succeeded simulation run opens the new recommendation', async ({ page }) => {
    await mockSignedInSession(page);
    await mockSimulationApi(page);
    const mock = await mockRecommendationsApi(page);
    await page.goto('/twin/simulation');
    await page.getByTestId('scenario-row').click();
    await page.getByTestId('run-trigger').click();
    await expect(page.getByTestId('compare-results')).toBeVisible({ timeout: 10_000 });
    await page.getByTestId('generate-recommendation').click();

    await expect(page).toHaveURL(new RegExp(`/recommendations/${REC_ID}$`));
    await expect(page.getByTestId('rec-status')).toHaveAttribute('data-status', 'PROPOSED');
    expect(mock.calls).toContain('POST /api/recommendations');
    await expect(page.getByTestId('rec-evidence')).toContainText('Simulation run');
    await expect(page.getByTestId('rec-evidence').getByRole('link').first()).toHaveAttribute('href', '/twin/simulation');
  });

  test('the detail shows measured values, the plan, and the dry-run diff before Approve', async ({ page }) => {
    await openDetail(page);
    await expect(page.getByTestId('rec-badges')).toContainText('Proposed');
    await expect(page.getByTestId('rec-badges').getByRole('img', { name: /simulat/i })).toBeVisible();
    await expect(page.getByTestId('objective-before')).toContainText('180');
    await expect(page.getByTestId('objective-after')).toContainText('15');
    await expect(page.getByTestId('rec-feasibility')).toContainText('OPTIMAL');
    await expect(page.getByTestId('rec-feasibility')).toContainText('OR-Tools CP-SAT');
    await expect(page.getByTestId('rec-constraint')).toHaveCount(2);
    await expect(page.getByTestId('rec-plan-step')).toContainText('REASSIGN_TRIP_DOCK');
    await expect(page.getByTestId('rec-plan-step')).toContainText('IN-02');
    // REQ-091: no confidence percentage anywhere (the objective note may say what the numbers are not).
    await expect(page.locator('main')).not.toContainText(/%|confidence\s*[:=]/i);

    const row = page.getByTestId('dry-run-row');
    await expect(row).toHaveCount(1);
    await expect(row).toContainText('TRIP-00002');
    await expect(row).toContainText('dock_code');
    await expect(row).toContainText('IN-02');
    await expect(row).toContainText('IN-03');
    await expect(row).toHaveAttribute('data-changed', 'true');
    // Approve is enabled only once the diff is on screen.
    await expect(page.getByTestId('approve')).toBeEnabled();
    await expect(page.getByTestId('sod-message')).toHaveCount(0);
  });

  test('Approve stays disabled until the dry-run diff is on screen', async ({ page }) => {
    const mock = await openDetail(page, { dryRunUnavailable: true });
    await expect(page.getByTestId('rec-dry-run')).toContainText('The dry run could not be computed.');
    await expect(page.getByTestId('dry-run-row')).toHaveCount(0);
    await expect(page.getByTestId('approve')).toBeDisabled();
    await expect(page.getByTestId('rec-approval')).toContainText(
      'Approve is enabled once the dry-run diff has been computed with no stale entities.',
    );
    mock.restoreDryRun();
    await page.getByRole('button', { name: 'Recompute' }).click();
    await expect(page.getByTestId('dry-run-row')).toHaveCount(1);
    await expect(page.getByTestId('approve')).toBeEnabled();
  });

  test('approve -> execute -> VERIFIED with the observed evidence', async ({ page }) => {
    const mock = await openDetail(page);
    await expect(page.getByTestId('dry-run-row')).toHaveCount(1);
    await page.getByTestId('approve').click();
    await expect(page.getByTestId('approval-done')).toHaveText('Approved.');
    await expect(page.getByTestId('approval-status')).toHaveAttribute('data-status', 'APPROVED');
    await expect(page.getByTestId('rec-status')).toHaveAttribute('data-status', 'APPROVED');

    await page.getByTestId('execute').click();
    await expect(page.getByTestId('execution-outcome')).toHaveAttribute('data-outcome', 'VERIFIED', {
      timeout: 15_000,
    });
    await expect(page.getByTestId('execution-outcome')).toContainText('every target was observed');
    await expect(page.getByTestId('rec-status')).toHaveAttribute('data-status', 'VERIFIED');
    await expect(page.getByTestId('execution-step')).toHaveAttribute('data-status', 'VERIFIED');
    await expect(page.getByTestId('execution-step')).toContainText('7a2e0000-0000-7000-8000-0000000000e1');
    await expect(page.getByTestId('execute')).toHaveCount(0);
    const order = mock.calls.filter((c) => c.startsWith('POST'));
    expect(order).toEqual([
      `POST /api/approvals/7a2e0000-0000-7000-8000-00000000d001/approve`,
      `POST /api/recommendations/${REC_ID}/execute`,
    ]);
  });

  test('a refused step ends FAILED and shows the failure as evidence', async ({ page }) => {
    await openDetail(page, { status: 'APPROVED', failOnExecute: true });
    await page.getByTestId('execute').click();
    await expect(page.getByTestId('execution-outcome')).toHaveAttribute('data-outcome', 'FAILED');
    await expect(page.getByTestId('rec-status')).toHaveAttribute('data-status', 'FAILED');
    await expect(page.getByTestId('execution-step')).toHaveAttribute('data-status', 'FAILED');
    const evidence = page.getByTestId('execution-failure-evidence');
    await expect(evidence).toContainText('Step #1 (TRIP-00002) was refused by SIM-TMS: dock_kind_mismatch.');
    await expect(evidence).toContainText('Every applied step was compensated.');
  });

  test('the proposer sees the separation-of-duties message and cannot approve', async ({ page }) => {
    const mock = await openDetail(page, { proposedByViewer: true });
    await expect(page.getByTestId('sod-message')).toContainText(
      'Separation of duties: you proposed this recommendation, so a different eligible person must approve it.',
    );
    await expect(page.getByTestId('approve')).toBeDisabled();
    expect(mock.calls.filter((c) => c.endsWith('/approve'))).toEqual([]);
  });

  test('stale 409 on execute lists the stale entities and re-simulates', async ({ page }) => {
    await mockResimulation(page);
    await openDetail(page, { status: 'APPROVED', staleOnExecute: true });
    await page.getByTestId('execute').click();

    const panel = page.getByTestId('stale-panel');
    await expect(panel).toBeVisible();
    await expect(panel).toContainText('The baseline changed since the plan was computed');
    const stale = page.getByTestId('stale-row');
    await expect(stale).toHaveCount(1);
    await expect(stale).toContainText('TRIP-00002');
    await expect(stale).toContainText('1');
    await expect(stale).toContainText('2');
    await expect(page.getByTestId('rec-status')).toHaveAttribute('data-status', 'STALE');
    await expect(page.getByTestId('execute')).toHaveCount(0);

    await page.getByTestId('resimulate').click();
    await expect(page.getByTestId('resimulate-status')).toHaveText('Re-simulation succeeded', { timeout: 15_000 });
    await expect(page.getByRole('link', { name: 'Open in Simulation' })).toHaveAttribute('href', '/twin/simulation');
    await expect(page.getByTestId('stale-panel').getByTestId('generate-recommendation')).toBeVisible();
  });

  test('no serious or critical axe violations on the list and the detail', async ({ page }) => {
    await openDetail(page);
    await expect(page.getByTestId('dry-run-row')).toHaveCount(1);
    await expectNoSeriousAxeViolations(page);

    await page.getByTestId('reject-open').click();
    await expect(page.getByTestId('reject-reason')).toBeVisible();
    await expectNoSeriousAxeViolations(page);

    await page.goto('/recommendations');
    await expect(page.getByTestId('rec-row')).toHaveCount(1);
    await expectNoSeriousAxeViolations(page);
  });

  test.describe('at 390px', () => {
    test.use({ viewport: { width: 390, height: 844 } });

    test('the detail and the list fit a phone without horizontal page scroll', async ({ page }) => {
      await openDetail(page);
      await expect(page.getByTestId('dry-run-row')).toHaveCount(1);
      await expect(page.getByTestId('approve')).toBeVisible();
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(overflow).toBeLessThanOrEqual(0);
      await page.getByTestId('approve').scrollIntoViewIfNeeded();
      await page.getByTestId('approve').click();
      await expect(page.getByTestId('rec-status')).toHaveAttribute('data-status', 'APPROVED');

      await page.goto('/recommendations');
      await expect(page.getByTestId('rec-row')).toHaveCount(1);
      const listOverflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(listOverflow).toBeLessThanOrEqual(0);
    });
  });
});

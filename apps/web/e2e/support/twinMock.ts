import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { Page } from '@playwright/test';
import type { Entity, EntityDetail, EntityList, WorldResponse } from '@vo/twin-schema';

/**
 * Network-layer stand-in for the twin endpoints. The payloads are the committed contract samples
 * (tests/contract/world-samples), i.e. real responses of the Java API, so the UI is exercised against the
 * true wire shape. Everything is SYNTHETIC. Tests run with cwd = apps/web.
 */
const SAMPLES = resolve(process.cwd(), '../../tests/contract/world-samples');

function load<T>(name: string): T {
  return JSON.parse(readFileSync(resolve(SAMPLES, name), 'utf8')) as T;
}

export const worldFixture = load<WorldResponse>('world.json');
export const entityFixtures = load<EntityList>('entity-list.json').items;
const detailFixture = load<EntityDetail>('entity-detail.json');

export interface TwinMockOptions {
  /** Return HTTP 500 for the world request (error-state test). */
  failWorld?: boolean;
  /** Entities served per page; the mock follows `cursor` like the real API. */
  pageSize?: number;
}

function detailFor(entity: Entity): EntityDetail {
  if (entity.id === detailFixture.id) return detailFixture;
  const floor = worldFixture.floors.find((f) => f.id === entity.floor_id);
  const zone = worldFixture.zones.find((z) => z.id === entity.zone_id);
  return {
    ...entity,
    floor_name: floor?.name ?? null,
    zone_name: zone?.name ?? null,
    external_ids: [{ source_system: 'SIM_TMS', source_record_id: `ID-${entity.code}` }],
    created_at: entity.pose.occurred_at,
    updated_at: entity.pose.occurred_at,
  };
}

export async function mockTwinApi(page: Page, options: TwinMockOptions = {}): Promise<void> {
  await page.route('**/api/twin/world?**', (route) =>
    options.failWorld
      ? route.fulfill({
          status: 500,
          contentType: 'application/problem+json',
          body: JSON.stringify({ title: 'Internal Server Error', detail: 'World model unavailable' }),
        })
      : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(worldFixture) }),
  );
  await page.route('**/api/twin/entities?**', (route) => {
    const url = new URL(route.request().url());
    const size = options.pageSize ?? entityFixtures.length;
    const start = Number(url.searchParams.get('cursor') ?? 0);
    const items = entityFixtures.slice(start, start + size);
    const next = start + size < entityFixtures.length ? String(start + size) : null;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items, next_cursor: next }),
    });
  });
  await page.route('**/api/entities/*', (route) => {
    const id = new URL(route.request().url()).pathname.split('/').pop();
    const entity = entityFixtures.find((e) => e.id === id);
    if (!entity) return route.fulfill({ status: 404, contentType: 'application/problem+json', body: '{}' });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detailFor(entity)) });
  });
}

/** Makes `canvas.getContext('webgl'|'webgl2')` return null, as on a machine without WebGL. */
export async function disableWebGL(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const original = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, type: string, ...rest: unknown[]) {
      if (type === 'webgl' || type === 'webgl2' || type === 'experimental-webgl') return null;
      return (original as (...a: unknown[]) => unknown).call(this, type, ...rest);
    } as typeof original;
  });
}

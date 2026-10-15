import type { BrowserContext, Route } from '@playwright/test';

/**
 * A stateful, in-test mock of the parts of the API the Operator PWA uses (docs/api/README.md, docs/api/field-sync.md).
 * It records every command (path, Idempotency-Key, body) so a spec can assert ordering, key stability and the
 * section 12 conflict answers. Install with `mock.install(context)`.
 */
export const ME = {
  actor_ref: '018f0000-0000-7000-8000-00000000a104',
  display_name: 'Dev Driver',
  tenant: { id: 't1', slug: 'demo', name: 'Demo Org', demo_mode: true },
  roles: [{ role: 'DRIVER_OPERATOR', scope_type: 'SITE', scope_id: 'site-1' }],
};

export interface MockTask {
  id: string;
  site_id: string;
  task_type: 'PICK' | 'PUTAWAY' | 'DOCK';
  status: string;
  priority: number;
  title: string;
  assigned_actor_ref: string | null;
  from: { label: string; x: number; y: number };
  to: { label: string; x: number; y: number };
  data_mode: 'SIMULATED' | 'LIVE';
  version: number;
  updated_at: string;
}

export interface Recorded {
  method: string;
  path: string;
  key: string | null;
  body: Record<string, unknown> | null;
}

export function task(id: string, title: string, over: Partial<MockTask> = {}): MockTask {
  return {
    id,
    site_id: 'site-1',
    task_type: 'PICK',
    status: 'ASSIGNED',
    priority: 2,
    title,
    assigned_actor_ref: ME.actor_ref,
    from: { label: 'Rack A-01-01', x: 7, y: 22 },
    to: { label: 'Staging OUT-01', x: 72, y: 74 },
    data_mode: 'SIMULATED',
    version: 1,
    updated_at: '2026-10-15T08:00:00Z',
    ...over,
  };
}

type Handler = (rec: Recorded) => { status: number; body: unknown } | null;

export class MockApi {
  loggedIn = false;
  /** While true every request fails like a dead network (Playwright routes would otherwise still answer). */
  offline = false;
  tasks: MockTask[] = [task('task-1', 'Pick pallet P-100')];
  commands: Recorded[] = [];
  /** Return a response to override the default for a command (e.g. a 409 task_reassigned). */
  override: Handler | null = null;
  private acks = new Map<string, unknown>();

  async goOffline(context: BrowserContext): Promise<void> {
    this.offline = true;
    await context.setOffline(true);
  }

  async goOnline(context: BrowserContext): Promise<void> {
    this.offline = false;
    await context.setOffline(false);
  }

  commandsTo(suffix: string): Recorded[] {
    return this.commands.filter((c) => c.path.endsWith(suffix));
  }

  async install(context: BrowserContext): Promise<void> {
    await context.route('**/api/**', (route) => this.handle(route));
  }

  private json(route: Route, status: number, body: unknown) {
    return route.fulfill({
      status,
      contentType: status >= 400 ? 'application/problem+json' : 'application/json',
      body: JSON.stringify(body),
    });
  }

  private async handle(route: Route): Promise<void> {
    if (this.offline) return route.abort('internetdisconnected');
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    const key = request.headers()['idempotency-key'] ?? null;
    let body: Record<string, unknown> | null = null;
    try {
      body = request.postDataJSON() as Record<string, unknown> | null;
    } catch {
      body = null;
    }

    if (path === '/api/auth/login' && method === 'POST') {
      this.loggedIn = true;
      return this.json(route, 200, {
        access_token: 'tok-1',
        token_type: 'Bearer',
        expires_in: 900,
      });
    }
    if (path === '/api/auth/refresh') {
      return this.loggedIn
        ? this.json(route, 200, { access_token: 'tok-2', token_type: 'Bearer', expires_in: 900 })
        : this.json(route, 401, { title: 'Unauthorized', status: 401 });
    }
    if (path === '/api/auth/logout') {
      this.loggedIn = false;
      return this.json(route, 200, {});
    }
    if (path === '/api/me') return this.json(route, 200, ME);
    if (path === '/api/organizations') {
      return this.json(route, 200, {
        technical_metadata_only: false,
        organizations: [
          {
            id: 'org-1',
            name: 'Org',
            regions: [
              {
                id: 'r1',
                name: 'South',
                sites: [{ id: 'site-1', name: 'Bengaluru Warehouse 01' }],
              },
            ],
          },
        ],
      });
    }
    if (path === '/api/tasks' && method === 'GET') {
      return this.json(route, 200, { items: this.tasks, next_cursor: null });
    }

    if (method === 'POST') {
      const rec: Recorded = { method, path, key, body };
      const overridden = this.override?.(rec);
      if (overridden) {
        this.commands.push(rec);
        return this.json(route, overridden.status, overridden.body);
      }
      if (key && this.acks.has(key)) return this.json(route, 200, this.acks.get(key));
      this.commands.push(rec);
      const ack = {
        command_id: `cmd-${this.commands.length}`,
        status: 'ACCEPTED',
        correlation_id: 'corr',
        result: this.resultFor(path, body),
      };
      if (key) this.acks.set(key, ack);
      return this.json(route, 200, ack);
    }
    return this.json(route, 404, { title: 'Not found', status: 404 });
  }

  private resultFor(path: string, body: Record<string, unknown> | null): Record<string, unknown> {
    if (path.endsWith('/evidence')) {
      return {
        evidence_id: 'ev-1',
        duplicate: false,
        sync_state: 'SYNCED',
        client_ref: body?.client_ref,
      };
    }
    if (path.endsWith('/inventory-scans'))
      return { outcome: 'RECORDED', applied: true, quantity_overwritten: false };
    if (path.endsWith('/urgent-records'))
      return { kind: 'URGENT', advisory: true, executed: false };
    if (path.endsWith('/issues')) return { issue_id: 'iss-1' };
    return { applied: true, changed: true, status: 'OK', version: 2 };
  }
}

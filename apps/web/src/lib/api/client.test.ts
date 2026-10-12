import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch, bootstrapSession, login, logout } from './client';
import type { MeResponse } from './types';
import { useSessionStore } from '../../stores/sessionStore';

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

const ME: MeResponse = {
  actor_ref: 'a',
  display_name: 'Exec',
  tenant: { id: 't', slug: 'x', name: 'X', demo_mode: true },
  roles: [{ role: 'EXECUTIVE', scope_type: 'ORG', scope_id: null }],
};

const TOKEN = (t: string) => json({ access_token: t, token_type: 'Bearer', expires_in: 900 });

beforeEach(() => {
  useSessionStore.setState({ status: 'unknown', accessToken: null, me: null });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('apiFetch', () => {
  it('sends bearer token and a correlation id', async () => {
    useSessionStore.setState({ accessToken: 'tok' });
    const fetchMock = vi.fn().mockResolvedValue(json({ ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    await apiFetch('/me');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(init.headers);
    expect(url).toBe('/api/me');
    expect(headers.get('Authorization')).toBe('Bearer tok');
    expect(headers.get('X-Correlation-Id')).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('surfaces problem+json as a typed ApiError', async () => {
    useSessionStore.setState({ accessToken: 'tok' });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(json({ title: 'Forbidden', status: 403, detail: 'Nope' }, 403)),
    );
    const error = await apiFetch('/x').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(403);
    expect((error as ApiError).title).toBe('Forbidden');
    expect((error as ApiError).detail).toBe('Nope');
  });

  it('concurrent 401s trigger exactly one refresh, then each request retries once', async () => {
    useSessionStore.setState({ accessToken: 'old', status: 'authenticated' });
    let refreshCalls = 0;
    const fetchMock = vi.fn((input: unknown, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/auth/refresh') {
        refreshCalls += 1;
        expect(new Headers(init?.headers).get('X-Requested-With')).toBe('vorg-web');
        expect(init?.credentials).toBe('include');
        return Promise.resolve(TOKEN('new'));
      }
      const auth = new Headers(init?.headers).get('Authorization');
      return Promise.resolve(
        auth === 'Bearer new' ? json({ ok: url }) : json({ title: 'Unauthorized' }, 401),
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    const results = await Promise.all([apiFetch('/a'), apiFetch('/b'), apiFetch('/c')]);

    expect(results).toHaveLength(3);
    expect(refreshCalls).toBe(1);
    expect(useSessionStore.getState().accessToken).toBe('new');
    expect(fetchMock).toHaveBeenCalledTimes(3 + 1 + 3);
  });

  it('a failed refresh clears the session', async () => {
    useSessionStore.setState({ accessToken: 'old', status: 'authenticated', me: ME });
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(json({ title: 'Unauthorized' }, 401))));
    await expect(apiFetch('/me')).rejects.toBeInstanceOf(ApiError);
    const s = useSessionStore.getState();
    expect(s.status).toBe('anonymous');
    expect(s.accessToken).toBeNull();
    expect(s.me).toBeNull();
  });

  it('does not attempt refresh for login failures', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ title: 'Unauthorized' }, 401));
    vi.stubGlobal('fetch', fetchMock);
    await expect(
      login({ tenant: 't', email: 'e@demo.invalid', password: 'bad' }),
    ).rejects.toBeInstanceOf(ApiError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('session lifecycle', () => {
  it('bootstrap restores the session with one silent refresh and /api/me', async () => {
    const fetchMock = vi.fn((input: unknown) =>
      Promise.resolve(String(input) === '/api/auth/refresh' ? TOKEN('tok') : json(ME)),
    );
    vi.stubGlobal('fetch', fetchMock);
    await Promise.all([bootstrapSession(), bootstrapSession()]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(useSessionStore.getState().status).toBe('authenticated');
  });

  it('bootstrap without a cookie ends anonymous', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ title: 'Unauthorized' }, 401)));
    await bootstrapSession();
    expect(useSessionStore.getState().status).toBe('anonymous');
  });

  it('logout clears local state even when the call fails', async () => {
    useSessionStore.setState({ accessToken: 'tok', status: 'authenticated', me: ME });
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')));
    await logout();
    expect(useSessionStore.getState().status).toBe('anonymous');
  });

  it('never writes the token to web storage', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    vi.stubGlobal(
      'fetch',
      vi.fn((input: unknown) =>
        Promise.resolve(String(input) === '/api/me' ? json(ME) : TOKEN('secret-token')),
      ),
    );
    await login({ tenant: 't', email: 'e', password: 'p' });
    expect(setItem).not.toHaveBeenCalled();
    expect(JSON.stringify(window.localStorage)).not.toContain('secret-token');
    expect(JSON.stringify(window.sessionStorage)).not.toContain('secret-token');
  });
});

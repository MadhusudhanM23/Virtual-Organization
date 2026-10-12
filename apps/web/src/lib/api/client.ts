import { useSessionStore } from '../../stores/sessionStore';
import type {
  LoginRequest,
  MeResponse,
  OrganizationsResponse,
  ProblemDetail,
  TokenResponse,
} from './types';

/**
 * Typed `fetch` wrapper for the API (BLUEPRINT section 18, Plan 1.6).
 *  - base path `/api` (same origin; the dev server proxies it, so the refresh cookie stays first-party);
 *  - `Authorization: Bearer` from the in-memory session store, `X-Correlation-Id` (UUID) on every request;
 *  - problem+json responses surface as {@link ApiError};
 *  - a 401 triggers ONE shared (single-flight) `POST /api/auth/refresh`, then one retry; if refresh fails the
 *    session is cleared and the route guard sends the user to /login.
 */

const API_BASE = '/api';
const CSRF_HEADER = 'X-Requested-With';
const CSRF_VALUE = 'vorg-web';

export class ApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly detail: string | undefined;
  readonly correlationId: string;

  constructor(status: number, problem: ProblemDetail, correlationId: string) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.title = problem.title ?? `HTTP ${status}`;
    this.detail = problem.detail;
    this.correlationId = correlationId;
  }
}

async function toApiError(res: Response, sentId: string): Promise<ApiError> {
  let problem: ProblemDetail = {};
  try {
    const body: unknown = await res.json();
    if (body && typeof body === 'object') problem = body as ProblemDetail;
  } catch {
    // Non-JSON error body (proxy error page, empty body): the status alone is reported.
  }
  return new ApiError(res.status, problem, res.headers.get('X-Correlation-Id') ?? sentId);
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** false: never attach the bearer token and never attempt refresh-on-401 (login, refresh, logout). */
  auth?: boolean;
  /** Send the CSRF guard header and cookies (login, refresh, logout). */
  cookieCall?: boolean;
}

interface Sent {
  res: Response;
  id: string;
}

async function send(path: string, opts: RequestOptions, token: string | null): Promise<Sent> {
  const id = globalThis.crypto.randomUUID();
  const headers: Record<string, string> = {
    Accept: 'application/json, application/problem+json',
    'X-Correlation-Id': id,
  };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (token && opts.auth !== false) headers.Authorization = `Bearer ${token}`;
  if (opts.cookieCall) headers[CSRF_HEADER] = CSRF_VALUE;
  const init: RequestInit = {
    method: opts.method ?? 'GET',
    headers,
    credentials: opts.cookieCall ? 'include' : 'same-origin',
  };
  if (opts.body !== undefined) init.body = JSON.stringify(opts.body);
  return { res: await fetch(`${API_BASE}${path}`, init), id };
}

let refreshInFlight: Promise<string | null> | null = null;

async function doRefresh(): Promise<string | null> {
  const store = useSessionStore.getState();
  try {
    const { res } = await send('/auth/refresh', { method: 'POST', auth: false, cookieCall: true }, null);
    if (!res.ok) {
      store.clear();
      return null;
    }
    const body = (await res.json()) as TokenResponse;
    store.setAccessToken(body.access_token);
    return body.access_token;
  } catch {
    store.clear();
    return null;
  }
}

/** Single-flight: concurrent callers share one refresh request. Resolves to the new token, or null. */
export function refreshAccessToken(): Promise<string | null> {
  refreshInFlight ??= doRefresh().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

export async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const usedToken = useSessionStore.getState().accessToken;
  let sent = await send(path, opts, usedToken);

  if (sent.res.status === 401 && opts.auth !== false) {
    // If another request already rotated the token since this one was sent, just retry with it.
    const current = useSessionStore.getState().accessToken;
    const fresh = current && current !== usedToken ? current : await refreshAccessToken();
    if (!fresh) throw await toApiError(sent.res, sent.id);
    sent = await send(path, opts, fresh);
    if (sent.res.status === 401) useSessionStore.getState().clear();
  }

  if (!sent.res.ok) throw await toApiError(sent.res, sent.id);
  if (sent.res.status === 204) return undefined as T;
  return (await sent.res.json()) as T;
}

export function fetchMe(): Promise<MeResponse> {
  return apiFetch<MeResponse>('/me');
}

export function fetchOrganizations(): Promise<OrganizationsResponse> {
  return apiFetch<OrganizationsResponse>('/organizations');
}

/** Logs in and loads the profile. Throws {@link ApiError} (401 for any bad credentials: no enumeration). */
export async function login(request: LoginRequest): Promise<MeResponse> {
  const token = await apiFetch<TokenResponse>('/auth/login', {
    method: 'POST',
    body: request,
    auth: false,
    cookieCall: true,
  });
  const store = useSessionStore.getState();
  store.setAccessToken(token.access_token);
  try {
    const me = await fetchMe();
    store.setMe(me);
    return me;
  } catch (error) {
    store.clear();
    throw error;
  }
}

/** Revokes the refresh cookie server side, then always clears local state, even if the call fails. */
export async function logout(): Promise<void> {
  try {
    await apiFetch<void>('/auth/logout', { method: 'POST', auth: false, cookieCall: true });
  } catch {
    // Offline or already expired: local sign-out still proceeds.
  } finally {
    useSessionStore.getState().clear();
  }
}

let bootstrapInFlight: Promise<void> | null = null;

/**
 * Page-load session restore: ONE silent refresh (httpOnly cookie), then `/api/me`. Leaves the store
 * `authenticated` or `anonymous`. Idempotent while in flight, and a no-op once the status is known.
 */
export function bootstrapSession(): Promise<void> {
  if (useSessionStore.getState().status !== 'unknown') return Promise.resolve();
  bootstrapInFlight ??= (async () => {
    const store = useSessionStore.getState();
    const token = await refreshAccessToken();
    if (!token) {
      store.clear();
      return;
    }
    try {
      store.setMe(await fetchMe());
    } catch {
      store.clear();
    }
  })().finally(() => {
    bootstrapInFlight = null;
  });
  return bootstrapInFlight;
}

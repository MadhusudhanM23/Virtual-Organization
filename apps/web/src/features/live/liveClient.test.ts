import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LiveClient, type SocketLike } from './liveClient';
import type { ConnectionState, ServerMessage } from './liveProtocol';

class FakeSocket implements SocketLike {
  static all: FakeSocket[] = [];
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: ((event: { code: number; reason?: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  sent: Array<Record<string, unknown>> = [];
  constructor(readonly url: string) {
    FakeSocket.all.push(this);
  }
  send(data: string) {
    this.sent.push(JSON.parse(data) as Record<string, unknown>);
  }
  close(code = 1000) {
    this.readyState = 3;
    this.onclose?.({ code });
  }
  open() {
    this.readyState = 1;
    this.onopen?.();
  }
  serverSend(message: unknown) {
    this.onmessage?.({ data: JSON.stringify(message) });
  }
  serverClose(code: number) {
    this.readyState = 3;
    this.onclose?.({ code });
  }
}

const READY = {
  type: 'ready',
  heartbeat_s: 15,
  expires_at: new Date(Date.now() + 15 * 60_000).toISOString(),
  server_time: new Date().toISOString(),
};

function setup(overrides: Partial<ConstructorParameters<typeof LiveClient>[0]> = {}) {
  const states: ConnectionState[] = [];
  const messages: ServerMessage[] = [];
  const resyncs: string[] = [];
  let token: string | null = 'tok-1';
  const refresh = vi.fn(() => {
    token = 'tok-2';
    return Promise.resolve<string | null>('tok-2');
  });
  const client = new LiveClient({
    url: () => 'ws://test/ws/live',
    getToken: () => token,
    refreshToken: refresh,
    onMessage: (m) => messages.push(m),
    onState: (s) => states.push(s),
    onResync: (r) => resyncs.push(r),
    createSocket: (url) => new FakeSocket(url),
    random: () => 1,
    ...overrides,
  });
  return {
    client,
    states,
    messages,
    resyncs,
    refresh,
    setToken: (t: string | null) => {
      token = t;
    },
  };
}

const last = () => FakeSocket.all[FakeSocket.all.length - 1]!;

beforeEach(() => {
  vi.useFakeTimers();
  FakeSocket.all = [];
});
afterEach(() => {
  vi.useRealTimers();
});

describe('LiveClient', () => {
  it('authenticates with the first message, never via the URL, then subscribes', async () => {
    const { client, states } = setup();
    client.subscribe({ id: 'twin', site_id: 's1' });
    client.start();
    expect(states).toEqual(['connecting']);
    last().open();
    await vi.advanceTimersByTimeAsync(0);
    expect(last().url).not.toContain('tok');
    expect(last().sent[0]).toEqual({ type: 'auth', token: 'tok-1' });
    last().serverSend(READY);
    expect(states.at(-1)).toBe('live');
    expect(last().sent[1]).toMatchObject({ type: 'subscribe', id: 'twin', site_id: 's1' });
  });

  it('reconnects with backoff, resubscribes and requests a REST resync', async () => {
    const { client, states, resyncs } = setup({ baseDelayMs: 1000, maxDelayMs: 4000 });
    client.subscribe({ id: 'twin', site_id: 's1' });
    client.start();
    last().open();
    await vi.advanceTimersByTimeAsync(0);
    last().serverSend(READY);
    expect(resyncs).toEqual([]); // first connect: the initial REST load covers it

    last().serverClose(1006);
    expect(states.at(-1)).toBe('reconnecting');
    expect(FakeSocket.all).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(999);
    expect(FakeSocket.all).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(2);
    expect(FakeSocket.all).toHaveLength(2);

    // Second failure doubles the delay (random() = 1 means the full ceiling).
    last().serverClose(1006);
    await vi.advanceTimersByTimeAsync(1999);
    expect(FakeSocket.all).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(2);
    expect(FakeSocket.all).toHaveLength(3);

    last().open();
    await vi.advanceTimersByTimeAsync(0);
    last().serverSend(READY);
    expect(states.at(-1)).toBe('live');
    expect(last().sent.some((m) => m.type === 'subscribe' && m.id === 'twin')).toBe(true);
    expect(resyncs).toEqual(['reconnect']);
  });

  it('caps the backoff', async () => {
    const { client } = setup({ baseDelayMs: 1000, maxDelayMs: 3000 });
    client.start();
    for (let i = 0; i < 6; i += 1) {
      last().serverClose(1006);
      await vi.advanceTimersByTimeAsync(3001);
    }
    expect(FakeSocket.all.length).toBe(7);
  });

  it('asks for a resync when the server reports a gap and answers pings', async () => {
    const { client, resyncs } = setup();
    client.start();
    last().open();
    await vi.advanceTimersByTimeAsync(0);
    last().serverSend(READY);
    last().serverSend({ type: 'gap', sub: 'ops', dropped: 4 });
    expect(resyncs).toEqual(['gap']);
    last().serverSend({ type: 'ping', server_time: 'x', expires_at: READY.expires_at });
    expect(last().sent.at(-1)).toEqual({ type: 'pong' });
  });

  it('refreshes the token and reconnects at once after an expiry close (4401)', async () => {
    const { client, refresh, states } = setup();
    client.start();
    last().open();
    await vi.advanceTimersByTimeAsync(0);
    last().serverSend(READY);
    last().serverClose(4401);
    await vi.advanceTimersByTimeAsync(0);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(FakeSocket.all).toHaveLength(2);
    last().open();
    await vi.advanceTimersByTimeAsync(0);
    expect(last().sent[0]).toEqual({ type: 'auth', token: 'tok-2' });
    expect(states).toContain('reconnecting');
  });

  it('gives up (closed) when the session cannot be refreshed', async () => {
    const { client, states } = setup({ refreshToken: () => Promise.resolve(null) });
    client.start();
    last().open();
    await vi.advanceTimersByTimeAsync(0);
    last().serverSend(READY);
    last().serverClose(4401);
    await vi.advanceTimersByTimeAsync(0);
    expect(states.at(-1)).toBe('closed');
    await vi.advanceTimersByTimeAsync(60_000);
    expect(FakeSocket.all).toHaveLength(1);
  });

  it('re-authenticates before the token expires', async () => {
    const soon = new Date(Date.now() + 90_000).toISOString();
    const { client, refresh } = setup({ reauthLeadMs: 60_000 });
    client.start();
    last().open();
    await vi.advanceTimersByTimeAsync(0);
    last().serverSend({ ...READY, expires_at: soon });
    await vi.advanceTimersByTimeAsync(31_000);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(last().sent.at(-1)).toEqual({ type: 'reauth', token: 'tok-2' });
  });

  it('treats a silent socket as dead (watchdog) and reconnects', async () => {
    const { client, states } = setup({ watchdogMs: 5000, baseDelayMs: 100 });
    client.start();
    last().open();
    await vi.advanceTimersByTimeAsync(0);
    last().serverSend(READY);
    await vi.advanceTimersByTimeAsync(5100);
    expect(states.at(-1)).toBe('reconnecting');
    await vi.advanceTimersByTimeAsync(200);
    expect(FakeSocket.all.length).toBeGreaterThanOrEqual(2);
  });

  it('stop() closes the socket and never reconnects', async () => {
    const { client, states } = setup();
    client.start();
    last().open();
    await vi.advanceTimersByTimeAsync(0);
    client.stop();
    expect(states.at(-1)).toBe('idle');
    await vi.advanceTimersByTimeAsync(120_000);
    expect(FakeSocket.all).toHaveLength(1);
  });
});

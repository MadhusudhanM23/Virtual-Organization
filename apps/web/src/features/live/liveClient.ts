import type { ConnectionState, ServerMessage, SubscribeSpec } from './liveProtocol';

/** The subset of the browser WebSocket the client uses (so tests can inject a fake). */
export interface SocketLike {
  readyState: number;
  onopen: (() => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
  onclose: ((event: { code: number; reason?: string }) => void) | null;
  onerror: (() => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export type ResyncReason = 'reconnect' | 'gap';

export interface LiveClientOptions {
  /** Absolute or same-origin ws URL, evaluated at every connect. */
  url: () => string;
  /** Current access token (memory only). */
  getToken: () => string | null;
  /** Obtains a fresh token (refresh cookie); null when the session is gone. */
  refreshToken: () => Promise<string | null>;
  onMessage: (message: ServerMessage) => void;
  onState: (state: ConnectionState) => void;
  /** REST must be re-read: the stream may have missed events (after a reconnect, or when the server reports a gap). */
  onResync: (reason: ResyncReason) => void;
  createSocket?: (url: string) => SocketLike;
  baseDelayMs?: number;
  maxDelayMs?: number;
  /** Silence longer than this on an open socket means a dead connection (the server pings every 15 s). */
  watchdogMs?: number;
  /** Refresh and re-authenticate this long before the token expires. */
  reauthLeadMs?: number;
  /** 0..1, injectable for tests. */
  random?: () => number;
  now?: () => number;
}

const OPEN = 1;
const CLOSE_UNAUTHENTICATED = 4401;

/**
 * Resilient client of `/ws/live` (docs/api/websocket.md): authenticates with the first message, (re)subscribes after
 * every connect, answers pings, re-authenticates before the token expires, reconnects with exponential backoff and
 * full jitter, and asks the owner to resync over REST after any reconnect or reported gap.
 */
export class LiveClient {
  private readonly opts: Required<
    Pick<
      LiveClientOptions,
      'baseDelayMs' | 'maxDelayMs' | 'watchdogMs' | 'reauthLeadMs' | 'random' | 'now'
    >
  > &
    LiveClientOptions;
  private socket: SocketLike | null = null;
  private state: ConnectionState = 'idle';
  private attempt = 0;
  private everLive = false;
  private stopped = true;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private watchdogTimer: ReturnType<typeof setTimeout> | null = null;
  private reauthTimer: ReturnType<typeof setTimeout> | null = null;
  private generation = 0;
  private sentToken: string | null = null;
  private readonly subs = new Map<string, SubscribeSpec>();

  constructor(options: LiveClientOptions) {
    this.opts = {
      baseDelayMs: 1000,
      maxDelayMs: 30_000,
      watchdogMs: 40_000,
      reauthLeadMs: 60_000,
      random: Math.random,
      now: Date.now,
      ...options,
    };
  }

  get connectionState(): ConnectionState {
    return this.state;
  }

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    this.attempt = 0;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    this.generation += 1;
    this.clearTimers();
    const s = this.socket;
    this.socket = null;
    if (s) {
      s.onopen = s.onmessage = s.onclose = s.onerror = null;
      try {
        s.close(1000, 'client_stop');
      } catch {
        // already closed
      }
    }
    this.setState('idle');
  }

  /** Adds or replaces a subscription; sent immediately when live and re-sent after every reconnect. */
  subscribe(spec: SubscribeSpec): void {
    this.subs.set(spec.id, spec);
    if (this.state === 'live') this.sendJson({ type: 'subscribe', ...spec });
  }

  unsubscribe(id: string): void {
    if (!this.subs.delete(id)) return;
    if (this.state === 'live') this.sendJson({ type: 'unsubscribe', id });
  }

  /** The session token changed elsewhere (REST refresh): tell the server so the socket outlives the old token. */
  tokenChanged(): void {
    if (this.state !== 'live') return;
    const token = this.opts.getToken();
    if (token && token !== this.sentToken) {
      this.sentToken = token;
      this.sendJson({ type: 'reauth', token });
    }
  }

  // ---- internals ------------------------------------------------------------------------------------------------

  private setState(next: ConnectionState): void {
    if (this.state === next) return;
    this.state = next;
    this.opts.onState(next);
  }

  private clearTimers(): void {
    for (const t of [this.reconnectTimer, this.watchdogTimer, this.reauthTimer]) {
      if (t) clearTimeout(t);
    }
    this.reconnectTimer = this.watchdogTimer = this.reauthTimer = null;
  }

  private connect(): void {
    if (this.stopped) return;
    this.setState(this.everLive || this.attempt > 0 ? 'reconnecting' : 'connecting');
    const gen = ++this.generation;
    let socket: SocketLike;
    try {
      const url = this.opts.url();
      socket = this.opts.createSocket
        ? this.opts.createSocket(url)
        : (new WebSocket(url) as unknown as SocketLike);
    } catch {
      this.scheduleReconnect(false);
      return;
    }
    this.socket = socket;
    socket.onopen = () => {
      if (gen !== this.generation) return;
      void this.authenticate(socket, gen);
    };
    socket.onmessage = (event) => {
      if (gen !== this.generation) return;
      this.armWatchdog();
      this.handleRaw(event.data);
    };
    socket.onerror = () => {
      // The close event that follows drives the reconnect.
    };
    socket.onclose = (event) => {
      if (gen !== this.generation) return;
      this.socket = null;
      this.clearTimers();
      this.scheduleReconnect(event.code === CLOSE_UNAUTHENTICATED);
    };
    this.armWatchdog();
  }

  private async authenticate(socket: SocketLike, gen: number): Promise<void> {
    let token = this.opts.getToken();
    if (!token) token = await this.opts.refreshToken();
    if (gen !== this.generation || socket !== this.socket) return;
    if (!token) {
      this.giveUp(socket);
      return;
    }
    this.sentToken = token;
    this.sendJson({ type: 'auth', token });
  }

  private giveUp(socket: SocketLike): void {
    this.stopped = true;
    this.generation += 1;
    this.clearTimers();
    socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null;
    try {
      socket.close(1000, 'no_session');
    } catch {
      // ignore
    }
    this.socket = null;
    this.setState('closed');
  }

  private armWatchdog(): void {
    if (this.watchdogTimer) clearTimeout(this.watchdogTimer);
    this.watchdogTimer = setTimeout(() => {
      const s = this.socket;
      if (!s) return;
      try {
        s.close(4000, 'watchdog');
      } catch {
        // ignore
      }
      // Some stacks never deliver onclose for a dead socket: force the reconnect path.
      if (this.socket === s) {
        this.generation += 1;
        s.onopen = s.onmessage = s.onclose = s.onerror = null;
        this.socket = null;
        this.scheduleReconnect(false);
      }
    }, this.opts.watchdogMs);
  }

  private scheduleReconnect(authExpired: boolean): void {
    if (this.stopped) return;
    this.setState('reconnecting');
    if (this.watchdogTimer) clearTimeout(this.watchdogTimer);
    this.watchdogTimer = null;
    if (authExpired) {
      // Token expired server side: fetch a fresh one, then reconnect at once (no backoff for an expected close).
      void this.opts.refreshToken().then((token) => {
        if (this.stopped) return;
        if (!token) {
          this.stopped = true;
          this.setState('closed');
          return;
        }
        this.connect();
      });
      return;
    }
    const ceiling = Math.min(this.opts.maxDelayMs, this.opts.baseDelayMs * 2 ** this.attempt);
    const delay = Math.round(ceiling / 2 + (this.opts.random() * ceiling) / 2);
    this.attempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay);
  }

  private sendJson(payload: unknown): void {
    const s = this.socket;
    if (!s || s.readyState !== OPEN) return;
    try {
      s.send(JSON.stringify(payload));
    } catch {
      // the close handler recovers
    }
  }

  private handleRaw(data: unknown): void {
    if (typeof data !== 'string') return;
    let message: ServerMessage;
    try {
      message = JSON.parse(data) as ServerMessage;
    } catch {
      return;
    }
    switch (message.type) {
      case 'ready': {
        const reconnected = this.everLive;
        this.everLive = true;
        this.attempt = 0;
        this.setState('live');
        this.scheduleReauth(message.expires_at);
        for (const spec of this.subs.values()) this.sendJson({ type: 'subscribe', ...spec });
        if (reconnected) this.opts.onResync('reconnect');
        break;
      }
      case 'reauthenticated':
        this.scheduleReauth(message.expires_at);
        break;
      case 'ping':
        this.sendJson({ type: 'pong' });
        this.scheduleReauth(message.expires_at);
        break;
      case 'gap':
        this.opts.onResync('gap');
        break;
      default:
        break;
    }
    this.opts.onMessage(message);
  }

  private scheduleReauth(expiresAt: string): void {
    const expiry = Date.parse(expiresAt);
    if (Number.isNaN(expiry)) return;
    if (this.reauthTimer) clearTimeout(this.reauthTimer);
    const wait = Math.max(1000, expiry - this.opts.now() - this.opts.reauthLeadMs);
    this.reauthTimer = setTimeout(() => {
      this.reauthTimer = null;
      void this.opts.refreshToken().then((token) => {
        if (token && this.state === 'live') {
          this.sentToken = token;
          this.sendJson({ type: 'reauth', token });
        }
      });
    }, wait);
  }
}

import { create } from 'zustand';
import type { MeResponse } from '../lib/api/types';

/**
 * Session state. BLUEPRINT section 18: the access token lives in this in-memory store ONLY. It is never written to
 * localStorage, sessionStorage, IndexedDB or a cookie; the long-lived credential is the httpOnly refresh cookie,
 * which script cannot read. A page reload therefore starts `unknown` and restores the session by a silent refresh.
 *
 * `offline` is the PWA addition (ADR-78): the device is offline, the silent refresh could not run, and the profile
 * remembered from the last sign-in is used so the operator can still read cached tasks and queue work. There is no
 * token in this state, so the sync engine refuses to send anything until the session is `authenticated` again.
 */
export type SessionStatus = 'unknown' | 'authenticated' | 'offline' | 'anonymous';

export interface SessionState {
  status: SessionStatus;
  accessToken: string | null;
  me: MeResponse | null;

  setAccessToken: (token: string) => void;
  setMe: (me: MeResponse) => void;
  /** Offline with a remembered profile: readable and queueable, but never authorized to upload. */
  setOffline: (me: MeResponse) => void;
  /** Drops token and profile; the route guard then redirects to /login. */
  clear: () => void;
}

export const useSessionStore = create<SessionState>()((set) => ({
  status: 'unknown',
  accessToken: null,
  me: null,

  setAccessToken: (accessToken) => set({ accessToken }),
  setMe: (me) => set({ me, status: 'authenticated' }),
  setOffline: (me) => set({ me, accessToken: null, status: 'offline' }),
  clear: () => set({ accessToken: null, me: null, status: 'anonymous' }),
}));

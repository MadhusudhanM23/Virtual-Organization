import { create } from 'zustand';
import type { MeResponse } from '../lib/api/types';

/**
 * Session state. BLUEPRINT section 18: the access token lives in this in-memory store ONLY. It is never
 * written to localStorage, sessionStorage, IndexedDB or a cookie; the long-lived credential is the httpOnly
 * refresh cookie, which script cannot read. A page reload therefore starts `unknown` and restores the
 * session by a silent refresh.
 */
export type SessionStatus = 'unknown' | 'authenticated' | 'anonymous';

export interface SessionState {
  status: SessionStatus;
  accessToken: string | null;
  me: MeResponse | null;

  setAccessToken: (token: string) => void;
  setMe: (me: MeResponse) => void;
  /** Drops token and profile; the route guard then redirects to /login. */
  clear: () => void;
}

export const useSessionStore = create<SessionState>()((set) => ({
  status: 'unknown',
  accessToken: null,
  me: null,

  setAccessToken: (accessToken) => set({ accessToken }),
  setMe: (me) => set({ me, status: 'authenticated' }),
  clear: () => set({ accessToken: null, me: null, status: 'anonymous' }),
}));

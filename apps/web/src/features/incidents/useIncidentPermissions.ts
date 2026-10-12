import { useMemo } from 'react';
import { useSessionStore } from '../../stores/sessionStore';
import { canUse, canViewIncidents } from '../auth/permissionMatrix';

export interface IncidentPermissions {
  roles: string[];
  canView: boolean;
  canHandle: boolean;
  canEstop: boolean;
  actorRef: string | null;
  demoMode: boolean;
}

/** UX hints only (ADR-06): the server enforces every query and command. */
export function useIncidentPermissions(): IncidentPermissions {
  const me = useSessionStore((s) => s.me);
  return useMemo(() => {
    const roles = (me?.roles ?? []).map((r) => r.role);
    return {
      roles,
      canView: canViewIncidents(roles),
      canHandle: canUse(roles, 'HANDLE_SAFETY_INCIDENT'),
      canEstop: canUse(roles, 'EMERGENCY_STOP_REQUEST'),
      actorRef: me?.actor_ref ?? null,
      demoMode: me?.tenant.demo_mode ?? false,
    };
  }, [me]);
}

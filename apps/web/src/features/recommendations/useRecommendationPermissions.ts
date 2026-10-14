import { useMemo } from 'react';
import { useSessionStore } from '../../stores/sessionStore';
import { canUse } from '../auth/permissionMatrix';

export interface RecommendationPermissions {
  canView: boolean;
  canPropose: boolean;
  canApprove: boolean;
  canExecute: boolean;
  actorRef: string | null;
}

/** UX hints only (ADR-06): the server enforces every query and command, including separation of duties. */
export function useRecommendationPermissions(): RecommendationPermissions {
  const me = useSessionStore((s) => s.me);
  return useMemo(() => {
    const roles = (me?.roles ?? []).map((r) => r.role);
    return {
      canView: canUse(roles, 'VIEW_OPERATIONAL_STATE'),
      canPropose: canUse(roles, 'PROPOSE_RECOMMENDATION'),
      canApprove: canUse(roles, 'APPROVE_CONSEQUENTIAL_ACTION'),
      canExecute: canUse(roles, 'EXECUTE_STANDARD_COMMAND'),
      actorRef: me?.actor_ref ?? null,
    };
  }, [me]);
}

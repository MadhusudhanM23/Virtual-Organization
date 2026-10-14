import { canUse, canViewIncidents } from '../features/auth/permissionMatrix';
import { NAV_ENTRIES, type NavEntry } from './navigation';

/**
 * Role-aware navigation. UX ONLY: this hides sections a role can never use, driven by the permission
 * matrix contract. It is not access control; the API enforces every decision (ADR-06), so a hidden route
 * reached by URL still gets 403 from the server.
 *
 * Each nav item id maps to the matrix action that gates it (`null`: every signed-in role).
 */
const REQUIRED_ACTION: Record<string, string | null | ((roles: readonly string[]) => boolean)> = {
  overview: null,
  organization: 'ANALYZE_QUERY',
  operations: 'ANALYZE_QUERY',
  incidents: canViewIncidents,
  // Reading recommendations needs PROPOSE_RECOMMENDATION on the site (docs/api/recommendations.md), so a role that
  // only views operational state (Driver/Operator) would get 403 from every call.
  recommendations: (roles) => canUse(roles, 'VIEW_OPERATIONAL_STATE') && canUse(roles, 'PROPOSE_RECOMMENDATION'),
  'twin-live': 'VIEW_OPERATIONAL_STATE',
  'twin-map': 'VIEW_OPERATIONAL_STATE',
  'twin-simulation': 'CREATE_SIMULATION',
  'twin-replay': 'ANALYZE_QUERY',
  orders: 'ANALYZE_QUERY',
  inventory: 'ANALYZE_QUERY',
  assets: 'ANALYZE_QUERY',
  people: 'ANALYZE_QUERY',
  fleet: 'ANALYZE_QUERY',
  analytics: 'ANALYZE_QUERY',
  assistant: 'PROPOSE_RECOMMENDATION',
  reports: 'ANALYZE_QUERY',
  settings: 'MANAGE_PLATFORM_CONFIGURATION',
};

export function canSeeNavItem(roles: readonly string[], itemId: string): boolean {
  if (!(itemId in REQUIRED_ACTION)) return false; // unknown sections stay hidden until classified
  const action = REQUIRED_ACTION[itemId];
  if (typeof action === 'function') return action(roles);
  return action === null || action === undefined || canUse(roles, action);
}

/** Nav entries visible to the given roles; groups with no visible child disappear. */
export function visibleNavEntries(
  roles: readonly string[],
  entries: readonly NavEntry[] = NAV_ENTRIES,
): NavEntry[] {
  const result: NavEntry[] = [];
  for (const entry of entries) {
    if (entry.kind === 'item') {
      if (canSeeNavItem(roles, entry.id)) result.push(entry);
    } else {
      const items = entry.items.filter((item) => canSeeNavItem(roles, item.id));
      if (items.length > 0) result.push({ ...entry, items });
    }
  }
  return result;
}

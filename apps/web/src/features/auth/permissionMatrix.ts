import matrixJson from '../../../../../packages/contracts/authz/permission-matrix.v1.json';
import type { RoleName } from '../../lib/api/types';

/**
 * UX hints derived from the authorization matrix contract (packages/contracts/authz). This is NOT access
 * control: the server enforces every decision (ADR-06). It only lets the UI avoid offering things a role can
 * never use.
 */

interface MatrixFile {
  roles: { role: string; label: string }[];
  actions: { action: string; decisions: Record<string, string> }[];
}

const matrix = matrixJson as unknown as MatrixFile;

/** Decisions under which a role can use an action in some form (anything but DENY or technical-only). */
const USABLE = new Set([
  'ALLOW',
  'ALLOW_SUBJECT_TO_POLICY',
  'GATED',
  'ASSIGNED_ONLY',
  'LIMITED',
]);

export function roleLabel(role: RoleName | string): string {
  return matrix.roles.find((r) => r.role === role)?.label ?? role;
}

/** True if any of the roles can use `action` (union of roles). ADMIN_TECH counts as not usable. */
export function canUse(roles: readonly string[], action: string): boolean {
  const row = matrix.actions.find((a) => a.action === action);
  if (!row) return false;
  return roles.some((role) => USABLE.has(row.decisions[role] ?? 'DENY'));
}

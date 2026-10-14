/**
 * Wire types for the identity and organization APIs (Plan 1.4). The API speaks snake_case JSON
 * (ARCHITECTURE.md), so these mirror it field for field; there is no client-side renaming layer.
 */

export type RoleName =
  | 'EXECUTIVE'
  | 'REGIONAL_MANAGER'
  | 'WAREHOUSE_MANAGER'
  | 'DRIVER_OPERATOR'
  | 'ANALYST'
  | 'SAFETY_COMPLIANCE'
  | 'PLATFORM_ADMIN';

export interface LoginRequest {
  tenant: string;
  email: string;
  password: string;
}

export interface TokenResponse {
  access_token: string;
  token_type: string;
  /** Lifetime of the access token in seconds. */
  expires_in: number;
}

export interface TenantInfo {
  id: string;
  slug: string;
  name: string;
  demo_mode: boolean;
  /** Tenant LLM policy (REQ-101): external LLM transmission allowed for this tenant. */
  llm_external_allowed?: boolean;
}

export interface RoleInfo {
  role: RoleName;
  scope_type: string;
  scope_id: string | null;
}

export interface MeResponse {
  actor_ref: string;
  display_name: string;
  tenant: TenantInfo;
  roles: RoleInfo[];
}

export type UnitSystemWire = 'METRIC' | 'IMPERIAL';

/**
 * A site. For callers limited to technical metadata (Platform Administrator) the API returns ids only, so
 * every descriptive field may be absent or null.
 */
export interface SiteView {
  id: string;
  name?: string | null;
  kind?: string | null;
  time_zone?: string | null;
  locale?: string | null;
  currency?: string | null;
  unit_system?: UnitSystemWire | null;
}

export interface RegionView {
  id: string;
  name?: string | null;
  sites: SiteView[];
}

export interface OrganizationView {
  id: string;
  name?: string | null;
  regions: RegionView[];
}

export interface OrganizationsResponse {
  technical_metadata_only: boolean;
  organizations: OrganizationView[];
}

/** RFC 9457 problem+json body (fields the client uses). */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  /** Field-level validation errors (e.g. the scenario/simulation-run API's 400s, docs/api/simulation.md). */
  errors?: { field: string; message: string }[];
  /** Problem extension members (RFC 9457 §3.2), read through typed parsers. */
  [extension: string]: unknown;
}

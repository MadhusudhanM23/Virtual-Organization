import type { OrganizationsResponse, SiteView } from '../../lib/api/types';

/** Sites with descriptive data. Technical-metadata-only callers get ids only and therefore no entries. */
export function flattenSites(response: OrganizationsResponse): SiteView[] {
  return response.organizations
    .flatMap((org) => org.regions.flatMap((region) => region.sites))
    .filter((site) => Boolean(site.name));
}

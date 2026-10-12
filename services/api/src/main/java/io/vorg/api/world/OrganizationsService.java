package io.vorg.api.world;

import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.PolicyService;
import io.vorg.api.platform.authz.ResourceKind;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.identity.Role;
import io.vorg.api.platform.identity.Subject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Organization hierarchy visible to a caller. Every node is filtered through the policy engine
 * (VIEW_OPERATIONAL_STATE); the tenant boundary itself is enforced by RLS on the underlying tables.
 * A caller that may only see technical state (Platform Administrator, ADMIN_TECH) receives ids only: no names,
 * time zones, locales or currencies, which are business configuration.
 */
@Service
public class OrganizationsService {

    public record SiteView(UUID id, String name, String kind, String timeZone, String locale, String currency,
            String unitSystem) {}

    public record RegionView(UUID id, String name, List<SiteView> sites) {}

    public record OrganizationView(UUID id, String name, List<RegionView> regions) {}

    public record OrganizationsResponse(boolean technicalMetadataOnly, List<OrganizationView> organizations) {}

    private record Org(UUID id, String name) {}

    private record Region(UUID id, UUID orgId, String name) {}

    private record Site(UUID id, UUID regionId, String name, String kind, String timeZone, String locale,
            String currency, String unitSystem) {}

    private final JdbcClient jdbc;
    private final PolicyService policy;

    public OrganizationsService(JdbcClient jdbc, PolicyService policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    public OrganizationsResponse visibleTo(Subject subject) {
        UUID tenant = subject.tenantId();
        // Platform-admin-only callers get technical metadata (ADMIN_TECH); never business configuration.
        boolean technicalOnly = !subject.roles().isEmpty()
                && subject.roles().stream().allMatch(r -> r.role() == Role.PLATFORM_ADMIN);
        ResourceKind kind = technicalOnly ? ResourceKind.TECHNICAL_STATE : ResourceKind.BUSINESS_DATA;

        List<Org> orgs = jdbc.sql("select id, name from world.organizations order by name, id")
                .query((rs, n) -> new Org(rs.getObject("id", UUID.class), rs.getString("name"))).list();
        List<Region> regions = jdbc.sql("select id, organization_id, name from world.regions order by name, id")
                .query((rs, n) -> new Region(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getString("name"))).list();
        List<Site> sites = jdbc.sql("""
                select id, region_id, name, kind, time_zone, locale, currency, unit_system
                from world.sites order by name, id
                """)
                .query((rs, n) -> new Site(rs.getObject("id", UUID.class), rs.getObject("region_id", UUID.class),
                        rs.getString("name"), rs.getString("kind"), rs.getString("time_zone"), rs.getString("locale"),
                        rs.getString("currency"), rs.getString("unit_system"))).list();

        List<OrganizationView> result = new ArrayList<>();
        for (Org org : orgs) {
            List<RegionView> regionViews = new ArrayList<>();
            for (Region region : regions) {
                if (!region.orgId().equals(org.id())) {
                    continue;
                }
                List<SiteView> siteViews = new ArrayList<>();
                for (Site site : sites) {
                    if (site.regionId().equals(region.id()) && canView(subject, new ResourceRef(kind, tenant, site.id(), null))) {
                        siteViews.add(technicalOnly
                                ? new SiteView(site.id(), null, null, null, null, null, null)
                                : new SiteView(site.id(), site.name(), site.kind(), site.timeZone(), site.locale(),
                                        site.currency(), site.unitSystem()));
                    }
                }
                if (!siteViews.isEmpty() || canView(subject, new ResourceRef(kind, tenant, null, region.id()))) {
                    regionViews.add(new RegionView(region.id(), technicalOnly ? null : region.name(), siteViews));
                }
            }
            if (!regionViews.isEmpty() || canView(subject, new ResourceRef(kind, tenant, null, null))) {
                result.add(new OrganizationView(org.id(), technicalOnly ? null : org.name(), regionViews));
            }
        }
        return new OrganizationsResponse(technicalOnly, result);
    }

    private boolean canView(Subject subject, ResourceRef resource) {
        return policy.authorize(subject, Action.VIEW_OPERATIONAL_STATE, resource, PolicyContext.none()).allowed();
    }
}

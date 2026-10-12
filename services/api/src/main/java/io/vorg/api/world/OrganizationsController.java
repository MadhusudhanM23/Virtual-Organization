package io.vorg.api.world;

import io.vorg.api.platform.identity.Subject;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrganizationsController {

    private final OrganizationsService organizations;

    public OrganizationsController(OrganizationsService organizations) {
        this.organizations = organizations;
    }

    /** Organization, regions and sites visible to the caller's scope (policy VIEW_OPERATIONAL_STATE). */
    @GetMapping("/api/organizations")
    public OrganizationsService.OrganizationsResponse organizations(@AuthenticationPrincipal Subject subject) {
        return organizations.visibleTo(subject);
    }
}

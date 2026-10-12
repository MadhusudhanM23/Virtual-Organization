package io.vorg.api.platform.identity.seed;

import java.util.UUID;

/** Fixed identifiers for the SYNTHETIC demo tenants so seeding is reproducible across runs and machines. */
public final class DemoIds {

    private DemoIds() {}

    private static UUID id(String suffix) {
        return UUID.fromString("018f0000-0000-7000-8000-" + suffix);
    }

    // Tenant A: MarutiFlex Traders (Demo)
    public static final String TENANT_A_SLUG = "marutiflex-demo";
    public static final UUID TENANT_A = id("00000000a001");
    public static final UUID ORG_A = id("00000000a002");
    public static final UUID REGION_SOUTH = id("00000000a011");
    public static final UUID REGION_WEST = id("00000000a012");
    public static final UUID SITE_BLR = id("00000000a021");
    public static final UUID SITE_MUM = id("00000000a022");

    public static final UUID EXEC_A = id("00000000a101");
    public static final UUID REGIONAL_SOUTH = id("00000000a102");
    public static final UUID WM_BLR = id("00000000a103");
    public static final UUID DRIVER_BLR = id("00000000a104");
    public static final UUID ANALYST_A = id("00000000a105");
    public static final UUID SAFETY_A = id("00000000a106");
    public static final UUID ADMIN_A = id("00000000a107");

    // Tenant B: Tenant B Logistics (Demo)
    public static final String TENANT_B_SLUG = "tenant-b-demo";
    public static final UUID TENANT_B = id("00000000b001");
    public static final UUID ORG_B = id("00000000b002");
    public static final UUID REGION_B = id("00000000b011");
    public static final UUID SITE_B = id("00000000b021");
    public static final UUID EXEC_B = id("00000000b101");
    public static final UUID WM_B = id("00000000b102");
}

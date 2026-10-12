package io.vorg.api.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantKeysTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NIL_TENANT = new UUID(0L, 0L);

    @Test
    void redisKeyJoinsPartsUnderTenantNamespace() {
        assertThat(TenantKeys.redisKey(TENANT, "site", "abc")).isEqualTo("t:" + TENANT + ":site:abc");
        assertThat(TenantKeys.redisKey(TENANT)).isEqualTo("t:" + TENANT);
    }

    @Test
    void storagePrefixEndsWithSlash() {
        assertThat(TenantKeys.storagePrefix(TENANT, "evidence")).isEqualTo("t:" + TENANT + ":evidence/");
    }

    @Test
    void wsChannelAndEventRouteUseDistinctNamespaces() {
        assertThat(TenantKeys.wsChannel(TENANT, "site-1")).isEqualTo("t:" + TENANT + ":ws:site-1");
        assertThat(TenantKeys.eventRoute(TENANT, "FORKLIFT_POSITION_UPDATED")).isEqualTo(
                "t:" + TENANT + ":evt:FORKLIFT_POSITION_UPDATED");
    }

    @Test
    void rejectsNullTenant() {
        assertThatThrownBy(() -> TenantKeys.redisKey(null, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNilTenant() {
        assertThatThrownBy(() -> TenantKeys.redisKey(NIL_TENANT, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankKeyPart() {
        assertThatThrownBy(() -> TenantKeys.redisKey(TENANT, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantKeys.redisKey(TENANT, (String) null)).isInstanceOf(IllegalArgumentException.class);
    }
}

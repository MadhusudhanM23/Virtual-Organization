package io.vorg.api.integration.core;

import java.time.Instant;
import java.util.UUID;

/** What an adapter is asked for: whose site, which cursor position (batch number, starting at 0) and the time now. */
public record AdapterContext(UUID tenantId, UUID siteId, long batchNumber, Instant now) {}

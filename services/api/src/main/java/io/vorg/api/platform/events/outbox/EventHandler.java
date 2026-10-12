package io.vorg.api.platform.events.outbox;

import io.vorg.api.platform.events.EventEnvelope;

/**
 * An in-process consumer of domain events (ADR-02, ADR-03). Delivery is at-least-once; the poller records
 * {@code (consumer, event_id)} in {@code evt.processed_events} in the same transaction as {@link #handle}, so the
 * handler's database side effects happen exactly once per consumer. Handlers run with the event's tenant bound and
 * inside a transaction, so they may emit follow-up events with {@code DomainEvents} (set {@code causationId} to the
 * handled event id). A handler must not retain the transaction or perform non-database side effects it cannot repeat.
 */
public interface EventHandler {

    /** Stable consumer name, the idempotency scope in {@code evt.processed_events}. */
    String consumer();

    boolean supports(String eventType);

    void handle(EventEnvelope event);
}

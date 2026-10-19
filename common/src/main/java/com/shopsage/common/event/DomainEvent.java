package com.shopsage.common.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope for every Saga event published to Kafka (serialized as JSON text, record key = orderId).
 *
 * <p>{@code eventId} is the idempotency key consumers dedupe on ({@code processed_events}, Day 4) and
 * {@code schemaVersion} lets a consumer evolve its DTO without a flag day. Trace context deliberately does
 * NOT live here: {@code traceparent} travels as a Kafka header so tracing stays transport-level.
 *
 * @param eventId       unique id of this event (not of the aggregate)
 * @param eventType     topic-style type name, e.g. {@code order.created}
 * @param schemaVersion payload schema version, starting at 1
 * @param aggregateId   id of the aggregate the event is about (orderId for every Saga event)
 * @param occurredAt    when the state change happened (UTC instant)
 * @param payload       event-specific body
 * @param <T>           payload type
 */
public record DomainEvent<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateId,
        Instant occurredAt,
        T payload) {

    /** Schema version stamped by {@link #of}; bump per event type when its payload changes incompatibly. */
    public static final int INITIAL_SCHEMA_VERSION = 1;

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (schemaVersion < INITIAL_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be >= 1, was " + schemaVersion);
        }
    }

    /** Creates a new event with a random id, the current instant and schema version 1. */
    public static <T> DomainEvent<T> of(String eventType, String aggregateId, T payload) {
        return new DomainEvent<>(UUID.randomUUID(), eventType, INITIAL_SCHEMA_VERSION, aggregateId, Instant.now(), payload);
    }
}

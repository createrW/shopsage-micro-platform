package com.shopsage.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainEventTest {

    /** Mirrors Boot's defaults: java.time support, ISO-8601 instead of epoch numbers. */
    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    record OrderCreatedPayload(String orderId, String userId, int itemCount) {
    }

    @Test
    void factoryFillsEnvelopeFields() {
        Instant before = Instant.now();

        DomainEvent<OrderCreatedPayload> event =
                DomainEvent.of(Topics.ORDER_CREATED, "order-1", new OrderCreatedPayload("order-1", "user-1", 2));

        assertThat(event.eventId()).isNotNull();
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.occurredAt()).isBetween(before, Instant.now());
        assertThat(event.eventType()).isEqualTo("order.created");
        assertThat(event.aggregateId()).isEqualTo("order-1");
    }

    @Test
    void factoryGeneratesDistinctEventIds() {
        assertThat(DomainEvent.of(Topics.ORDER_CREATED, "o", "x").eventId())
                .isNotEqualTo(DomainEvent.of(Topics.ORDER_CREATED, "o", "x").eventId());
    }

    @Test
    void jacksonRoundTripPreservesEveryField() throws Exception {
        DomainEvent<OrderCreatedPayload> original =
                DomainEvent.of(Topics.ORDER_CREATED, "order-42", new OrderCreatedPayload("order-42", "user-7", 3));

        String json = mapper.writeValueAsString(original);
        DomainEvent<OrderCreatedPayload> restored =
                mapper.readValue(json, new TypeReference<DomainEvent<OrderCreatedPayload>>() { });

        assertThat(json).contains("\"schemaVersion\":1").contains("\"occurredAt\":\"");
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void rejectsSchemaVersionBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new DomainEvent<>(UUID.randomUUID(), Topics.ORDER_CREATED, 0, "order-1", Instant.now(), "payload"));
    }
}

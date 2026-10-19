package com.shopsage.common.event;

import java.util.List;
import java.util.Objects;

/**
 * Kafka topic names of the order Saga (choreography, key = orderId). Single source of truth for Java code;
 * {@code configs/kafka/create-topics.sh} creates the same ten topics plus their {@code .DLT} twins, and the
 * dev broker has auto-create disabled so a misspelled name fails loudly.
 */
public final class Topics {

    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_REJECTED = "inventory.rejected";
    public static final String INVENTORY_RELEASED = "inventory.released";
    public static final String PAYMENT_CAPTURED = "payment.captured";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String PAYMENT_REFUNDED = "payment.refunded";
    public static final String SHIPPING_DISPATCHED = "shipping.dispatched";
    public static final String SHIPPING_FAILED = "shipping.failed";

    /** Matches Spring Kafka's DeadLetterPublishingRecoverer default, so no custom destination resolver is needed. */
    public static final String DLT_SUFFIX = ".DLT";

    private static final List<String> ALL = List.of(
            ORDER_CREATED, ORDER_CANCELLED,
            INVENTORY_RESERVED, INVENTORY_REJECTED, INVENTORY_RELEASED,
            PAYMENT_CAPTURED, PAYMENT_FAILED, PAYMENT_REFUNDED,
            SHIPPING_DISPATCHED, SHIPPING_FAILED);

    private Topics() {
    }

    /** The ten base topics (without DLTs), immutable. */
    public static List<String> all() {
        return ALL;
    }

    /** Dead-letter topic for a base topic, e.g. {@code order.created -> order.created.DLT}. */
    public static String dltOf(String topic) {
        Objects.requireNonNull(topic, "topic");
        if (topic.isBlank() || topic.endsWith(DLT_SUFFIX)) {
            throw new IllegalArgumentException("Not a base topic: '" + topic + "'");
        }
        return topic + DLT_SUFFIX;
    }
}

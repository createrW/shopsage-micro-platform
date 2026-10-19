package com.shopsage.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Smoke test: the real application context boots against real PostgreSQL (Flyway runs) and a real Kafka broker,
 * the actuator surface the platform relies on is exposed, and the shared ProblemDetail handler is active.
 *
 * <p>Deliberately no {@code disabledWithoutDocker}: a missing Docker daemon must fail the build loudly;
 * skip on purpose with {@code -DskipITs}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Metrics export on (so /actuator/prometheus exists in tests), tracing off (no OTLP collector in tests).
@AutoConfigureObservability(tracing = false)
@Testcontainers
class PaymentServiceApplicationIT {

    private static final String SMOKE_TOPIC = "shopsage.smoke";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");

    // Same image as docker-compose. Boot 3.2 has no @ServiceConnection for this KafkaContainer (added in 3.4).
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.2");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Test
    void healthIsUp() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void actuatorSurfaceExposed() {
        // Readiness is what the compose healthcheck polls; it is also actuator traffic that must stay out of the
        // server request metrics asserted below.
        ResponseEntity<String> readiness = rest.getForEntity("/actuator/health/readiness", String.class);
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).contains("\"status\":\"UP\"");

        ResponseEntity<String> metrics = rest.getForEntity("/actuator/metrics", String.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody()).contains("jvm.memory.used");

        ResponseEntity<String> info = rest.getForEntity("/actuator/info", String.class);
        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(info.getBody()).contains("\"artifact\":\"payment-service\"");

        ResponseEntity<String> prometheus = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody())
                .contains("jvm_memory_used_bytes")
                // shopsage-common's ObservationPredicate keeps probe/scrape traffic out of server request metrics
                // (TestRestTemplate's own http_client_requests series are expected and not matched here).
                .doesNotContainPattern("http_server_requests_seconds_count\\{[^}]*uri=\"/actuator/");
    }

    @Test
    void kafkaRoundTrip() throws Exception {
        // Produces and consumes with the application's own serializer/deserializer configuration.
        String key = UUID.randomUUID().toString();
        RecordMetadata sent = kafkaTemplate.send(SMOKE_TOPIC, key, "{\"ok\":true}")
                .get(30, TimeUnit.SECONDS)
                .getRecordMetadata();
        assertThat(sent.offset()).isGreaterThanOrEqualTo(0);

        TopicPartition partition = new TopicPartition(SMOKE_TOPIC, sent.partition());
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("smoke-it", null)) {
            consumer.assign(List.of(partition));
            consumer.seek(partition, sent.offset());
            ConsumerRecord<String, String> received = pollFor(consumer, partition, key, Duration.ofSeconds(30));
            assertThat(received.value()).isEqualTo("{\"ok\":true}");
        }
    }

    @Test
    void unknownRouteIsProblemDetail() {
        ResponseEntity<String> response = rest.getForEntity("/nope", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private static ConsumerRecord<String, String> pollFor(
            Consumer<String, String> consumer, TopicPartition partition, String key, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500)).records(partition)) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + partition + " within " + timeout);
    }
}

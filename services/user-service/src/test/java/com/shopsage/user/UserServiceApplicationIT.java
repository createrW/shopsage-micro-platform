package com.shopsage.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: the real application context boots against real PostgreSQL (Flyway runs), the actuator surface
 * the platform relies on is exposed, and the shared ProblemDetail handler is active. No Kafka broker: user-service
 * is HTTP-only by design.
 *
 * <p>Deliberately no {@code disabledWithoutDocker}: a missing Docker daemon must fail the build loudly;
 * skip on purpose with {@code -DskipITs}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Metrics export on (so /actuator/prometheus exists in tests), tracing off (no OTLP collector in tests).
@AutoConfigureObservability(tracing = false)
@Testcontainers
class UserServiceApplicationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");

    @Autowired
    TestRestTemplate rest;

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
        assertThat(info.getBody()).contains("\"artifact\":\"user-service\"");

        ResponseEntity<String> prometheus = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody())
                .contains("jvm_memory_used_bytes")
                // shopsage-common's ObservationPredicate keeps probe/scrape traffic out of server request metrics
                // (TestRestTemplate's own http_client_requests series are expected and not matched here).
                .doesNotContainPattern("http_server_requests_seconds_count\\{[^}]*uri=\"/actuator/");
    }

    @Test
    void unknownRouteIsProblemDetail() {
        ResponseEntity<String> response = rest.getForEntity("/nope", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }
}

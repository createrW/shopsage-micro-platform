package com.shopsage.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ObservabilityAutoConfigurationTest {

    private static final String SECURITY_OBSERVATION = "spring.security.filterchains";

    private final List<Observation.Context> started = new ArrayList<>();
    private ObservationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationPredicate(new ObservabilityAutoConfiguration()
                        .actuatorObservationPredicate(new MockEnvironment()))
                .observationHandler(new RecordingHandler(started));
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void actuatorServerRequestIsNotObserved() {
        Observation observation = serverObservation(request("", "/actuator/health"));

        assertThat(observation.isNoop()).isTrue();
        assertThat(started).isEmpty();
    }

    @Test
    void observationStartedInsideAnActuatorRequestIsNotObserved() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request("", "/actuator/health")));

        Observation observation = plainObservation(SECURITY_OBSERVATION);

        assertThat(observation.isNoop()).isTrue();
        assertThat(started).isEmpty();
    }

    @Test
    void apiServerRequestIsObserved() {
        Observation observation = serverObservation(request("", "/api/v1/orders"));

        assertThat(observation.isNoop()).isFalse();
        assertThat(started).hasSize(1);
    }

    @Test
    void observationStartedInsideAnApiRequestIsObserved() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request("", "/api/v1/orders")));

        Observation observation = plainObservation(SECURITY_OBSERVATION);

        assertThat(observation.isNoop()).isFalse();
        assertThat(started).hasSize(1);
    }

    @Test
    void observationWithoutABoundRequestIsObserved() {
        // Kafka listener and scheduler threads have no request bound.
        Observation observation = plainObservation("spring.kafka.listener");

        assertThat(observation.isNoop()).isFalse();
    }

    @Test
    void actuatorRequestUnderAContextPathIsStillFiltered() {
        MockHttpServletRequest actuator = request("/ctx", "/ctx/actuator/health");

        assertThat(serverObservation(actuator).isNoop()).isTrue();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(actuator));
        assertThat(plainObservation(SECURITY_OBSERVATION).isNoop()).isTrue();
        assertThat(started).isEmpty();
    }

    @Test
    void pathThatOnlySharesThePrefixIsObserved() {
        assertThat(serverObservation(request("", "/actuatorish")).isNoop()).isFalse();
    }

    private Observation serverObservation(MockHttpServletRequest request) {
        ServerRequestObservationContext context =
                new ServerRequestObservationContext(request, new MockHttpServletResponse());
        return Observation.start("http.server.requests", () -> context, registry);
    }

    private Observation plainObservation(String name) {
        return Observation.start(name, registry);
    }

    private static MockHttpServletRequest request(String contextPath, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setContextPath(contextPath);
        return request;
    }

    private record RecordingHandler(List<Observation.Context> started) implements ObservationHandler<Observation.Context> {

        @Override
        public void onStart(Observation.Context context) {
            started.add(context);
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }
}

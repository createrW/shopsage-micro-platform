package com.shopsage.common.observability;

import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Keeps actuator traffic (container healthchecks every few seconds, Prometheus scrapes every 5 s) out of
 * observations. Without it Jaeger fills up with health-probe traces and {@code http.server.requests}
 * percentiles mix probe latency into the benchmark numbers; with it both only reflect real API traffic.
 *
 * <p>Rejecting only the {@code http.server.requests} observation is not enough. In Micrometer 1.12 a rejected
 * observation is a NOOP that does not become the current observation, so every observation started further down
 * the same request (Spring Security's {@code spring.security.filterchains} /
 * {@code spring.security.http.secured.requests} from Day 2, health-indicator calls) finds no parent and would be
 * exported as its own root trace. The predicate therefore also rejects any observation started while the current
 * thread is serving an actuator request. {@code OrderedRequestContextFilter} (order -105) binds the request before
 * the security filter chain (-100) runs, so {@link RequestContextHolder} sees it. Threads without a bound request
 * (Kafka listeners, schedulers) are unaffected.
 *
 * <p>Boot's actuator applies every {@link ObservationPredicate} bean to the registry. Common deliberately does
 * not depend on actuator: spring-web supplies {@link ServerRequestObservationContext}, {@link RequestContextHolder}
 * and micrometer-observation.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({ObservationPredicate.class, ServerRequestObservationContext.class})
public class ObservabilityAutoConfiguration {

    static final String BASE_PATH_PROPERTY = "management.endpoints.web.base-path";
    static final String DEFAULT_BASE_PATH = "/actuator";

    @Bean
    @ConditionalOnMissingBean(name = "actuatorObservationPredicate")
    public ObservationPredicate actuatorObservationPredicate(Environment environment) {
        String basePath = environment.getProperty(BASE_PATH_PROPERTY, DEFAULT_BASE_PATH);
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                return !isActuatorRequest(serverContext.getCarrier(), basePath);
            }
            // Observations started inside an actuator request would have a NOOP parent and become orphan root
            // traces (see class Javadoc), so they are judged by the request bound to the current thread.
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            return !(attributes instanceof ServletRequestAttributes servletAttributes
                    && isActuatorRequest(servletAttributes.getRequest(), basePath));
        };
    }

    private static boolean isActuatorRequest(HttpServletRequest request, String basePath) {
        // Strip the servlet context path so a non-root server.servlet.context-path does not defeat the filter.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals(basePath) || path.startsWith(basePath + "/");
    }
}

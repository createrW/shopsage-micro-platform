package com.shopsage.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Registers {@link GlobalExceptionHandler} in every servlet service that has shopsage-common on its classpath,
 * without services having to component-scan {@code com.shopsage.common}.
 *
 * <p>Runs before {@link WebMvcAutoConfiguration} so Boot's own ProblemDetails handler (also conditional on a
 * missing {@link ResponseEntityExceptionHandler}) backs off; a service that defines its own subclass wins
 * over this one.
 */
@AutoConfiguration(before = WebMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
// Bean Validation is optional in common but required by the handler's ConstraintViolationException mapping.
@ConditionalOnClass(value = ResponseEntityExceptionHandler.class, name = "jakarta.validation.ConstraintViolationException")
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}

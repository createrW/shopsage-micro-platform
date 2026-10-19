package com.shopsage.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shopsage.common.observability.ObservabilityAutoConfiguration;
import com.shopsage.common.testapp.TestApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// TestApplication sits in its own package and does not scan com.shopsage.common.web, so GlobalExceptionHandler can
// only arrive through CommonWebAutoConfiguration - the same path a service takes.
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
// Imported explicitly: Boot's TestTypeExcludeFilter keeps classes nested in tests out of component scanning.
@Import({GlobalExceptionHandlerTest.ProblemTestController.class, GlobalExceptionHandlerTest.ParamTestController.class})
class GlobalExceptionHandlerTest {

    private static final String SECRET = "jdbc password=hunter2";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    record NameRequest(@NotBlank String name) {
    }

    @RestController
    static class ProblemTestController {

        @GetMapping("/t/not-found")
        String notFound() {
            throw new ResourceNotFoundException("Order 42 not found");
        }

        @GetMapping("/t/conflict")
        String conflict() {
            throw new BusinessRuleException("Order 42 is already cancelled");
        }

        @GetMapping("/t/boom")
        String boom() {
            throw new IllegalStateException(SECRET);
        }

        @PostMapping("/t/validate")
        String validate(@Valid @RequestBody NameRequest request) {
            return request.name();
        }
    }

    /** Class-level @Validated: constrained parameters are checked by the method-validation proxy. */
    @RestController
    @Validated
    static class ParamTestController {

        @GetMapping("/t/page")
        public String page(@RequestParam @Min(1) int page) {
            return "page " + page;
        }
    }

    @Test
    void resourceNotFoundBecomes404ProblemDetail() throws Exception {
        mvc.perform(get("/t/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:shopsage:problem:not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Order 42 not found"))
                .andExpect(jsonPath("$.instance").value("/t/not-found"));
    }

    @Test
    void businessRuleViolationBecomes409() throws Exception {
        mvc.perform(get("/t/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:shopsage:problem:business-rule"));
    }

    @Test
    void unexpectedExceptionBecomes500WithoutLeakingItsMessage() throws Exception {
        mvc.perform(get("/t/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Unexpected server error"))
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void invalidBodyBecomes400WithFieldErrors() throws Exception {
        mvc.perform(post("/t/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:shopsage:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void constraintViolationBecomes400WithParameterErrors() throws Exception {
        mvc.perform(get("/t/page").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:shopsage:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("page.page"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void validParameterPassesThrough() throws Exception {
        mvc.perform(get("/t/page").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string("page 2"));
    }

    @Test
    void unknownRouteIsHandledByBaseClassAs404() throws Exception {
        mvc.perform(get("/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unsupportedMethodIsHandledByBaseClassAs405() throws Exception {
        mvc.perform(delete("/t/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void exactlyOneHandlerIsRegistered() {
        assertThat(context.getBeansOfType(ResponseEntityExceptionHandler.class))
                .hasSize(1)
                .allSatisfy((name, bean) -> assertThat(bean).isInstanceOf(GlobalExceptionHandler.class));
    }

    @Test
    void autoConfigurationsAreListedInImportsFile() {
        // Services never component-scan com.shopsage.common; this file is the only way they get these beans.
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains(CommonWebAutoConfiguration.class.getName(), ObservabilityAutoConfiguration.class.getName());
    }
}

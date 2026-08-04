package com.workflowplatform.controlplane.api.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.workflowplatform.contracts.problem.ProblemCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalProblemDetailsHandlerTests {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String JSON_CODE = "$.code";
    private static final String MOCK_MVC_READY = "MockMvc must be initialized";

    private MockMvc mockMvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc =
                MockMvcBuilders.standaloneSetup(new ProbeController())
                        .setControllerAdvice(
                                new GlobalProblemDetailsHandler(
                                        new ProblemDetailsResponseFactory()))
                        .setValidator(validator)
                        .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void returnsSortedFieldErrorsWithoutRejectedValues() throws Exception {
        assertNotNull(mockMvc, MOCK_MVC_READY);
        mockMvc.perform(
                        post("/problems/validate?secret=query-value")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\",\"token\":\"top-secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists(ProblemDetailsResponseFactory.TRACE_ID_HEADER))
                .andExpect(
                        jsonPath("$.type")
                                .value(
                                        "https://api.workflow-platform.example/problems/invalid-field"))
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/problems/validate"))
                .andExpect(jsonPath(JSON_CODE).value("COMMON_VALIDATION_INVALID_FIELD"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("required"))
                .andExpect(jsonPath("$.errors[1].field").value("token"))
                .andExpect(jsonPath("$.errors[1].message").value("format is invalid"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(content().string(not(containsString("top-secret"))))
                .andExpect(content().string(not(containsString("query-value"))))
                .andExpect(content().string(not(containsString("rejectedValue"))));
    }

    @Test
    void propagatesAValidW3cTraceId() throws Exception {
        assertNotNull(mockMvc, MOCK_MVC_READY);
        mockMvc.perform(
                        get("/problems/not-found")
                                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(ProblemDetailsResponseFactory.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath(JSON_CODE).value("COMMON_NOT_FOUND_RESOURCE"))
                .andExpect(jsonPath("$.errors", hasSize(0)));
    }

    @Test
    void returnsAuthenticationAndRetryHeaders() throws Exception {
        assertNotNull(mockMvc, MOCK_MVC_READY);
        mockMvc.perform(get("/problems/authentication"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath(JSON_CODE).value("AUTH_AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/problems/rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retryAfterSeconds").value(30));
    }

    @Test
    void mapsMalformedMissingAndTypeMismatchRequests() throws Exception {
        assertNotNull(mockMvc, MOCK_MVC_READY);
        mockMvc.perform(
                        post("/problems/validate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(JSON_CODE).value("COMMON_VALIDATION_MALFORMED_REQUEST"));

        mockMvc.perform(get("/problems/number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(JSON_CODE).value("COMMON_VALIDATION_MISSING_PARAMETER"))
                .andExpect(jsonPath("$.errors[0].field").value("count"))
                .andExpect(jsonPath("$.errors[0].code").value("required"));

        mockMvc.perform(get("/problems/number?count=wrong"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(JSON_CODE).value("COMMON_VALIDATION_TYPE_MISMATCH"))
                .andExpect(jsonPath("$.errors[0].code").value("invalid_type"));
    }

    @Test
    void neverLeaksUnexpectedExceptionDetails() throws Exception {
        assertNotNull(mockMvc, MOCK_MVC_READY);
        mockMvc.perform(get("/problems/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath(JSON_CODE).value("COMMON_INTERNAL_UNEXPECTED"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("database-password"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    @RestController
    static final class ProbeController {

        @PostMapping("/problems/validate")
        void validate(@Valid @RequestBody ProbeRequest request) {}

        @GetMapping("/problems/not-found")
        void notFound() {
            throw ApiProblemException.of(ProblemCode.COMMON_NOT_FOUND_RESOURCE);
        }

        @GetMapping("/problems/authentication")
        void authentication() {
            throw ApiProblemException.of(ProblemCode.AUTH_AUTHENTICATION_REQUIRED);
        }

        @GetMapping("/problems/rate-limit")
        void rateLimit() {
            throw ApiProblemException.retryAfter(ProblemCode.COMMON_RATE_LIMIT_EXCEEDED, 30);
        }

        @GetMapping("/problems/number")
        void number(@RequestParam int count) {}

        @GetMapping("/problems/internal")
        void internal() {
            throw new IllegalStateException(
                    String.join("", "database-password=", "must-", "never-", "appear"));
        }
    }

    private record ProbeRequest(
            @NotBlank String name, @Pattern(regexp = "[A-Z]{2}") String token) {}
}

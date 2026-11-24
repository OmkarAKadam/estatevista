package com.estatevista.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private static final String SYNTHETIC_SECRET = "SYNTHETIC_SECRET_9f3c";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void unexpectedExceptionReturnsSafeInternalErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.path").value("/api/v1/probe/boom"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString("secret detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void apiExceptionMapsToDeclaredStatusAndCode() throws Exception {
        mockMvc.perform(get("/api/v1/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Property not found"));
    }

    @Test
    void beanValidationFailureReturnsFieldDetails() throws Exception {
        mockMvc.perform(post("/api/v1/probe/validate")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"))
                .andExpect(jsonPath("$.error.details[0].message").value("must not be blank"));
    }

    @Test
    void malformedJsonReturnsBadRequestEnvelopeWithoutEchoingBody() throws Exception {
        mockMvc.perform(post("/api/v1/probe/validate")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + SYNTHETIC_SECRET + "\""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("The request could not be processed"))
                .andExpect(jsonPath("$.error.details", empty()))
                .andExpect(content().string(not(containsString(SYNTHETIC_SECRET))));
    }

    @Test
    void malformedRequestLogDoesNotContainRejectedBody() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);

        try {
            HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                    "JSON parse error near " + SYNTHETIC_SECRET,
                    new MockHttpInputMessage("{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/probe/validate");

            var response = new GlobalExceptionHandler().handleMalformedRequest(exception, request);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list)
                    .allSatisfy(event -> assertThat(event.getFormattedMessage())
                            .doesNotContain(SYNTHETIC_SECRET));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void unsupportedContentTypeReturnsUnsupportedMediaTypeEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/probe/validate")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"name\":\"value\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.error.message").value("The media type is not supported"))
                .andExpect(jsonPath("$.error.details", empty()));
    }

    @Test
    void unsupportedMethodReturnsMethodNotAllowedEnvelope() throws Exception {
        mockMvc.perform(put("/api/v1/probe/validate")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.message")
                        .value("The HTTP method is not supported for this resource"))
                .andExpect(jsonPath("$.error.details", empty()));
    }

    @Test
    void missingRequestParameterReturnsBadRequestEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/probe/requires-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.error.details", empty()));
    }

    @Test
    void missingRequestHeaderReturnsBadRequestEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/probe/requires-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.error.details", empty()));
    }

    @Test
    void responseStatusExceptionMapsToDeclaredClientStatusAndReason() throws Exception {
        mockMvc.perform(get("/api/v1/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message")
                        .value("The resource changed during the request"));
    }

    @Test
    void serverResponseStatusExceptionHidesInternalReason() throws Exception {
        mockMvc.perform(get("/api/v1/probe/server-status"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("synthetic internal reason"))));
    }

    @Test
    void typeMismatchReturnsBadRequestEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/probe/number/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.error.details", empty()));
    }

    @Test
    void parameterConstraintViolationReturnsFieldDetails() throws Exception {
        mockMvc.perform(get("/api/v1/probe/limited").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("size"))
                .andExpect(jsonPath("$.error.details[0].message").value("must be greater than or equal to 1"));
    }

    @Test
    void payloadTooLargeReturnsPayloadTooLargeEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/probe/too-large")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5500"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.error.message").value("The request payload is too large"))
                .andExpect(jsonPath("$.error.details", empty()));
    }

    @Test
    void missingResourceReturnsNotFoundEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.details", empty()));
    }

    @Test
    void accessDeniedReturnsForbiddenEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/probe/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("Access is denied"))
                .andExpect(jsonPath("$.error.details", empty()))
                .andExpect(content().string(not(containsString("synthetic denial"))));
    }

    @RestController
    @RequestMapping("/api/v1/probe")
    static class ProbeController {

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret detail");
        }

        @GetMapping("/missing")
        String missing() {
            throw new ApiException(ErrorCode.NOT_FOUND, "Property not found");
        }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody Payload payload) {
            return "ok";
        }

        @GetMapping("/number/{id}")
        String number(@PathVariable long id) {
            return "ok";
        }

        @GetMapping("/requires-param")
        String requiresParam(@RequestParam String value) {
            return value;
        }

        @GetMapping("/requires-header")
        String requiresHeader(@RequestHeader String token) {
            return token;
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The resource changed during the request");
        }

        @GetMapping("/server-status")
        String serverStatus() {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "synthetic internal reason");
        }

        @GetMapping("/limited")
        String limited(@RequestParam @Min(1) int size) {
            return "ok";
        }

        @PostMapping("/too-large")
        String tooLarge() {
            throw new MaxUploadSizeExceededException(1024L);
        }

        @GetMapping("/not-found")
        String notFound() throws NoResourceFoundException {
            throw new NoResourceFoundException(
                    HttpMethod.GET, "/api/v1/probe/not-found", "not-found");
        }

        @GetMapping("/denied")
        String denied() {
            throw new AccessDeniedException("synthetic denial");
        }
    }

    record Payload(@NotBlank String name) {
    }
}

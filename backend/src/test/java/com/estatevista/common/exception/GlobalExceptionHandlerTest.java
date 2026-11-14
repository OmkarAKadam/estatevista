package com.estatevista.common.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"))
                .andExpect(jsonPath("$.error.details[0].message").value("must not be blank"));
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
    }

    record Payload(@NotBlank String name) {
    }
}

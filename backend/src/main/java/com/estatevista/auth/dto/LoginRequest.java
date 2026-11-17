package com.estatevista.auth.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login request ({@code POST /auth/login}, API §3.3.2).
 *
 * <p>Unknown fields are captured and rejected so a client cannot smuggle role,
 * account status, or identifier material into an authentication request.</p>
 */
public class LoginRequest {

    @NotBlank(message = "must not be blank")
    @Email(message = "must be a valid email address")
    @Size(max = 255, message = "must be at most 255 characters")
    private String email;

    @NotBlank(message = "must not be blank")
    private String password;

    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public LoginRequest() {
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public Map<String, Object> unknownFields() {
        return Map.copyOf(unknownFields);
    }
}

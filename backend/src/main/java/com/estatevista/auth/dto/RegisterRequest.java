package com.estatevista.auth.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.estatevista.auth.validation.ValidPassword;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Buyer registration request ({@code POST /auth/register}, API §3.3.1).
 *
 * <p>The DTO intentionally contains no {@code role}, {@code accountStatus},
 * {@code id}, or {@code passwordHash} property. Any such field submitted by a
 * client is captured by {@link #captureUnknownField(String, Object)} and
 * rejected by the controller, so mass assignment is not merely ignored.</p>
 */
public class RegisterRequest {

    @NotBlank(message = "must not be blank")
    @Size(max = 100, message = "must be at most 100 characters")
    private String firstName;

    @NotBlank(message = "must not be blank")
    @Size(max = 100, message = "must be at most 100 characters")
    private String lastName;

    @NotBlank(message = "must not be blank")
    @Email(message = "must be a valid email address")
    @Size(max = 255, message = "must be at most 255 characters")
    private String email;

    @NotEmpty(message = "must not be empty")
    @ValidPassword
    private String password;

    @Size(max = 20, message = "must be at most 20 characters")
    private String phone;

    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public RegisterRequest() {
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        // Surrounding whitespace is not part of an email address and the
        // approved contract normalizes by trimming (SECURITY_ARCHITECTURE.md
        // §8.8.1). Trimming here lets @Email validate the normalized value, so a
        // pasted address with trailing spaces is accepted instead of rejected.
        this.email = email == null ? null : email.trim();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public Map<String, Object> unknownFields() {
        return Map.copyOf(unknownFields);
    }
}

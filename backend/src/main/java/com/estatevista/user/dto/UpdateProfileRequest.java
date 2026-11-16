package com.estatevista.user.dto;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Self-service profile update request ({@code PATCH /users/me}, API §4.1).
 *
 * <p>Only the approved profile fields are accepted. Jackson calls a setter only
 * for properties that are actually present in the JSON body, which lets the
 * service distinguish an omitted field from an explicit {@code null} without
 * treating the request as a full replacement.</p>
 *
 * <p>Role, account status, password hash, email, identifier, verification, and
 * audit fields are not properties of this DTO; if supplied they are captured and
 * rejected rather than silently ignored.</p>
 */
public class UpdateProfileRequest {

    private String firstName;
    private String lastName;
    private String phone;

    private final Set<String> presentFields = new LinkedHashSet<>();
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public UpdateProfileRequest() {
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
        presentFields.add("firstName");
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
        presentFields.add("lastName");
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
        presentFields.add("phone");
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    public boolean hasField(String field) {
        return presentFields.contains(field);
    }

    public boolean hasAnyUpdate() {
        return !presentFields.isEmpty();
    }

    public Map<String, Object> unknownFields() {
        return Map.copyOf(unknownFields);
    }
}

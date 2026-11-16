package com.estatevista.auth.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Enforces the structural password rules approved in
 * {@code SECURITY_ARCHITECTURE.md} §9.2 (SD-10).
 *
 * <p>Applies to the raw password before hashing. Context-word and breach-list
 * checks are separate so this constraint can be reused by password change and
 * reset without duplicating the structural rules.</p>
 */
@Documented
@Constraint(validatedBy = PasswordConstraintValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    String message() default "must be 10 to 64 characters, at most 72 bytes, "
            + "and must not contain null characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

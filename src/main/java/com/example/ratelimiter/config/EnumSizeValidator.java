package com.example.ratelimiter.config;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.constraints.Size;

/**
 * Makes {@code @Size} applicable to enums, measured over the constant's wire value.
 *
 * <p>Workaround for the OpenAPI spec, which puts {@code minLength}/{@code maxLength} on the
 * {@code resource} enum. The generator faithfully emits {@code @Size} on the generated
 * {@code CheckRateRequest.ResourceEnum} field, and Hibernate Validator has no validator for
 * {@code @Size} on an enum - so without this every request fails with HV000030 and a 500.
 *
 * <p>Registered in {@link ValidationConfig}. Delete both once the spec drops the length bounds from
 * the enum, where they mean nothing anyway.
 */
public class EnumSizeValidator implements ConstraintValidator<Size, Enum<?>> {

    private int min;
    private int max;

    @Override
    public void initialize(Size constraint) {
        this.min = constraint.min();
        this.max = constraint.max();
    }

    @Override
    public boolean isValid(Enum<?> value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // @NotNull's job
        }
        int length = String.valueOf(value).length();
        return length >= min && length <= max;
    }
}

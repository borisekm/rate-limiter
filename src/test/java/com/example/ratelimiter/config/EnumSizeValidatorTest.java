package com.example.ratelimiter.config;

import com.example.ratelimiter.core.Resource;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code @Size} on an enum, measured over its wire value ("subjectSearch" is 13 characters). */
class EnumSizeValidatorTest {

    @Size(min = 5, max = 13)
    private static final Object BOUNDS = null;

    private static EnumSizeValidator validator() throws NoSuchFieldException {
        EnumSizeValidator validator = new EnumSizeValidator();
        validator.initialize(EnumSizeValidatorTest.class.getDeclaredField("BOUNDS").getAnnotation(Size.class));
        return validator;
    }

    @Test
    void measuresTheWireValue() throws Exception {
        EnumSizeValidator validator = validator();
        assertThat(validator.isValid(Resource.SUBJECT_SEARCH, null)).isTrue();   // 13, the upper bound
        assertThat(validator.isValid(Resource.MAX_CALLS_SESS, null)).isTrue();   // 12
    }

    @Test
    void rejectsValuesOutsideTheBounds() throws Exception {
        EnumSizeValidator validator = validator();
        assertThat(validator.isValid(Shortest.AB, null)).isFalse();
        assertThat(validator.isValid(Shortest.FOURTEEN_CHARS, null)).isFalse();
    }

    @Test
    void leavesNullToNotNull() throws Exception {
        assertThat(validator().isValid(null, null)).isTrue();
    }

    private enum Shortest { AB, FOURTEEN_CHARS }
}

package com.example.ratelimiter.config;

import jakarta.validation.Configuration;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.HibernateValidatorConfiguration;
import org.hibernate.validator.cfg.ConstraintMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Replaces Boot's default validator with one that also accepts {@code @Size} on enums.
 * See {@link EnumSizeValidator} for why that is needed.
 */
@org.springframework.context.annotation.Configuration
public class ValidationConfig {

    @Bean
    public LocalValidatorFactoryBean defaultValidator() {
        return new LocalValidatorFactoryBean() {
            @Override
            protected void postProcessConfiguration(Configuration<?> configuration) {
                if (configuration instanceof HibernateValidatorConfiguration hibernate) {
                    ConstraintMapping mapping = hibernate.createConstraintMapping();
                    mapping.constraintDefinition(Size.class)
                            .includeExistingValidators(true)
                            .validatedBy(EnumSizeValidator.class);
                    hibernate.addMapping(mapping);
                }
            }
        };
    }
}

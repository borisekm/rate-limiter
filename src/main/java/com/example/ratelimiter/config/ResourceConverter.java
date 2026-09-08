package com.example.ratelimiter.config;

import com.example.ratelimiter.core.Resource;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Lets {@code ratelimiter.resources} be keyed by the spec's wire values ("subjectSearch") rather
 * than by Java constant names, which Spring's default relaxed binding would not match.
 */
@Component
@ConfigurationPropertiesBinding
public class ResourceConverter implements Converter<String, Resource> {

    @Override
    public Resource convert(String source) {
        return Resource.fromValue(source);
    }
}

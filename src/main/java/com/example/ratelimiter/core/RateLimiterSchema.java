package com.example.ratelimiter.core;

import org.infinispan.protostream.GeneratedSchema;
import org.infinispan.protostream.annotations.ProtoSchema;

/**
 * protostream-processor generates {@code RateLimiterSchemaImpl} from this at compile time.
 */
@ProtoSchema(
        includeClasses = {Bucket.class, ConsumeTokens.class},
        schemaPackageName = "ratelimiter",
        schemaFileName = "ratelimiter.proto",
        schemaFilePath = "proto")
public interface RateLimiterSchema extends GeneratedSchema {
}

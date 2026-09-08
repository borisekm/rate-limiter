package com.example.ratelimiter.core;

import org.infinispan.protostream.annotations.Proto;

/**
 * Token-bucket state stored in Infinispan, one entry per {@code <resource>@<identifier>} key.
 *
 * @param tokens            whole tokens currently available
 * @param refillAnchorNanos start of the refill period the bucket is currently in; refills happen in
 *                          discrete steps, so this only ever moves forward by whole periods
 * @param lastAllowed       outcome of the most recent consume attempt (lets compute() return the decision)
 */
@Proto
public record Bucket(long tokens, long refillAnchorNanos, boolean lastAllowed) {
}

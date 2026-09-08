package com.example.ratelimiter.core;

import org.infinispan.protostream.annotations.Proto;

/**
 * Token-bucket state stored in Infinispan, one entry per {@code <resource>@<identifier>} key.
 *
 * @param tokens                  whole tokens currently available
 * @param refillAnchorEpochMillis start of the refill period the bucket is currently in, in epoch
 *                                milliseconds; refills happen in discrete steps, so this only ever
 *                                moves forward by whole periods. Wall-clock rather than
 *                                {@code nanoTime()} so it still means something after a restart and
 *                                on another node
 * @param lastAllowed             outcome of the most recent consume attempt (lets compute() return the decision)
 */
@Proto
public record Bucket(long tokens, long refillAnchorEpochMillis, boolean lastAllowed) {
}

# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

A distributed token-bucket rate limiter: Spring Boot 3 (Java 21), embedded Infinispan for the shared
bucket state, API generated from an OpenAPI spec. See README.md for the user-facing description.

## Commands

```bash
mvn -o test                 # unit tests (RateLimiterTest drives the limiter through a fake clock)
mvn -o clean verify
mvn -o spring-boot:run      # port 8051
curl -s -X POST localhost:8051/v1/rate/check -H 'Content-Type: application/json' \
  -d '{"resource":"subjectSearch","identifier":"10.0.0.7"}'
```

## Rules of the road

- **Do not edit `src/main/resources/openapi/rate-limiter-api.yaml`.** It is owned by the API contract,
  not by this codebase. If the generator chokes on it, ask user what to do.
- **`core/Resource` mirrors the spec's `resource` enum by hand.** The generator emits its own
  `CheckRateRequest.ResourceEnum` with the same values; `core/Resource` is the domain-side copy, so
  the limiter and the configuration do not depend on generated code. When a value is added to the
  spec, add it here, add a policy in `application.yml`, and
  `RateLimiterTest.resourceMirrorsTheGeneratedApiEnum` will hold the two in sync. The core deliberately does not import generated classes; the API layer
  crosses over via `Resource.fromValue(request.getResource().getValue())`.
- **`@Size` on the `resource` enum comes from the spec's `minLength`/`maxLength`** and would 500 every
  request (`HV000030`); `config/ValidationConfig` + `config/EnumSizeValidator` make it a defined check
  over the wire value. Remove both if the spec ever drops those bounds.
- **Only `RateLimitApiDelegateImpl` is hand-written in `api/`.** Everything else there is generated
  into `target/generated-sources/openapi` - never edit or commit generated files.
- **Bucket keys are `<resource>@<identifier>`** (`RateLimiter.bucketName`). Anything that touches the
  cache key format touches every running node's data.
- **Refill is stepwise, not a continuous drip** - whole `refill-tokens` at each `refill-period`
  boundary. `Bucket.refillAnchorEpochMillis` is the start of the current period and only ever advances
  by whole periods; `retryAfterMillis` is the time to the next boundary. If you switch to a fractional
  drip, `Bucket.tokens` has to go back to `double` and the retry-after maths changes with it.
- **The user marshaller must stay ProtoStream** (set explicitly in `InfinispanConfig`; an explicitly
  supplied marshaller ignores `addContextInitializer`, so the schema is registered on the instance).
  The starter's default is `JavaSerializationMarshaller`, under which any cross-node `compute()` dies
  with ISPN000936 - invisible on a single node, which is what `ClusteredMarshallingTest` exists to
  catch. Anything touching marshalling or clustering needs that two-node test, not just the unit tests.
- **All mutation happens inside `ConsumeTokens`, via `cache.compute()`.** That is what makes the
  refill-and-consume atomic across the cluster - it runs on the key's primary owner. Do not read a
  bucket, decide, and write it back. `ConsumeTokens` and `Bucket` are ProtoStream-marshalled
  (`RateLimiterSchema`, generated at compile time), so any field added to them must be marshallable.
- **Bucket lifespan is derived** in `RateLimiterProperties.bucketLifespan()`, not configured. Do not
  turn it into a knob: a lifespan shorter than a policy's time-to-full silently resets that quota. It
  must stay `lifespan`, not `maxIdle` - Infinispan rejects max-idle alongside a non-passivating store
  (ISPN000651), and every check writes the entry, so lifespan refreshes on use anyway.
- **Time is wall-clock epoch millis** (`java.time.Clock` bean in `InfinispanConfig`, `clock.millis()`),
  never `System.nanoTime()`. Buckets are persisted and read by other nodes; `nanoTime()`'s zero point
  is per-JVM, so anchors written with it are meaningless after a restart and wrong across machines.
  `ConsumeTokens` re-anchors when the clock steps backwards rather than stalling refills.
- **The JGroups transport is TCP** (`ratelimiter.jgroups-config`), not Infinispan's UDP default -
  multicast is unavailable on OpenShift. The bundled TCP stack still *discovers* over multicast
  (MPING), so OpenShift needs `default-jgroups-kubernetes.xml` (DNS_PING) plus `jgroups.dns.query`.
  Do not go back to the UDP stack to silence a local warning.
- **Buckets are persisted** to a per-node `SoftIndexFileStore` (`ratelimiter.persistence.location`).
  Two nodes must never share a location. The store is not shared, so a restart with a different node
  count can orphan buckets - see README before changing anything here.

## Testing

`RateLimiterTest` runs against a real single-node `DefaultCacheManager` with `MutableClock` (the shared
test clock in `src/test/java/com/example/ratelimiter`) - prefer extending it over mocking the cache. It
covers exact capacity under concurrency, stepwise refill, capping, per-resource/per-identifier
isolation, the daily quota, retry-after in all its forms, and config completeness.

`ClusteredMarshallingTest` starts two real nodes in one JVM from the actual `InfinispanConfig`, so
marshalling of `Bucket` and `ConsumeTokens` is genuinely exercised. `PersistenceRestartTest` starts a
node, stops it, and starts another against the same directory - the only cover for state surviving a
deploy. Both need their own `ratelimiter.cluster-name` and a `@TempDir` store location, or they join a
locally running instance and hang in state transfer.

`RateLimitApiTest` is the Spring context test over MockMvc: the wire contract plus proof that
configuration binding works. After changing configuration binding, run it - and for anything involving
real timing, still boot the app and hit `/v1/rate/check`.

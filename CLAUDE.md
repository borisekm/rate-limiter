# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

A distributed token-bucket rate limiter: Spring Boot 4 (Java 21), a remote Infinispan / Red Hat Data
Grid server (Infinispan Spring Boot 4 remote starter, Hot Rod client 16) for the shared bucket state, API generated from an OpenAPI spec. See
README.md for the user-facing description.

## Commands

```bash
mvn -o test                 # needs Docker: the tests start an Infinispan server container
mvn -o clean verify
docker compose up -d infinispan
INFINISPAN_REMOTE_CLIENT_INTELLIGENCE=BASIC mvn -o spring-boot:run      # port 8051
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
- **The algorithm is Bucket4j** (`bucket4j_jdk17-core` only), over a Hot Rod `RemoteCache` via
  `core/HotRodProxyManager`, a subclass of Bucket4j's `AbstractCompareAndSwapBasedProxyManager`.
  Bucket4j's `bucket4j_jdk17-infinispan` module is embedded-only (entry processor on a functional
  map) and cannot work over Hot Rod. Refill is stepwise (`refillIntervally`, whole `refill-tokens` per
  `refill-period`), not the smooth `refillGreedy` drip. Policies become `BucketConfiguration`s once at
  startup in `core/RateLimiter`.
- **Every bucket write is a versioned compare-and-swap**: `getWithMetadata` for state + version, then
  `replaceWithVersion` (or `putIfAbsent` with `FORCE_RETURN_VALUE` - without the flag Hot Rod returns
  null either way), and Bucket4j retries a lost race. That is what makes refill-and-consume atomic
  across replicas. Never write a bucket with a plain `put`/`replace`: a lost update over-admits
  silently - `admitsExactlyCapacityUnderConcurrency` and `twoInstancesShareOneBucket` are what would
  catch it.
- **Values are Bucket4j's own `byte[]`, keys are strings** - both native to ProtoStream, so no schema
  is registered on the server and the project defines no `@Proto` types. Keep it that way: we do not
  own the Data Grid server, and anything that needs a schema or code deployed there is a negotiation.
- **The `RemoteCacheManager` is the Infinispan remote starter's** (`infinispan-spring-boot4-starter-remote`),
  configured by `infinispan.remote.*` in `application.yml`; `HotRodConfig` only adds the buckets
  cache through an `InfinispanRemoteCacheCustomizer`. Keep `infinispan.remote.cache.enabled: false`:
  the starter's Spring `CacheManager` is bound by Boot's cache metrics at startup, which calls the
  server and stops the app from starting without the store (`spring.autoconfigure.exclude` does not
  help - the starter component-scans that class in). The starter defaults to Java serialization, so
  the buckets cache pins `ProtoStreamMarshaller` per cache. Not every key in the starter's examples
  binds: there is no setter for `java-serial-whitelist` (it is `java-serial-allow-list`) or
  `sni-hostname-validation`, and Spring ignores unknown keys silently.
- **The app creates `rate-limit-buckets` itself** from `HotRodConfig.BUCKETS_CACHE_DEFINITION` when
  the server lacks it; an existing cache is used as it is, so changing the definition does nothing on
  a server that already has the cache.
- **Bucket lifespan is derived** in `RateLimiterProperties.bucketLifespan()`, not configured, and sent
  as the lifespan of every write, not in the cache definition. Do not turn it into a knob: a lifespan
  shorter than a policy's time-to-full silently resets that quota.
- **Time is wall-clock epoch millis** (`java.time.Clock` bean in `HotRodConfig`), fed to Bucket4j as
  the client clock in `RateLimiter`. Never use Bucket4j's default `nanoTime` meter: bucket state is
  read by other replicas, and `nanoTime()`'s zero point is per-JVM.
- **An unreachable store is a fallback, not an error.** `RateLimiter` catches `HotRodClientException`
  and answers per `ratelimiter.when-store-unavailable` (`allow`/`deny`, no code default - each
  environment sets it), marks the `Decision` as `degraded` (-> `X-RateLimit-Degraded` header) and logs
  a throttled warning. The cache is looked up lazily so the app starts without the server.
  `bucketStore` health must stay out of the readiness group - with the store down every pod is
  equally degraded, and failing readiness would turn fail-open into an outage.
- **On OpenShift the store is Red Hat Data Grid 8.6 (operator), same namespace.** The Kubernetes
  document of `application.yml` turns on TLS trusting the pod's
  `/var/run/secrets/kubernetes.io/serviceaccount/service-ca.crt` (the operator's default certificate
  is signed by the service CA); the server address, its SNI host name and `when-store-unavailable`
  come from `k8s/configmap.yaml`, credentials (`INFINISPAN_USERNAME` / `_PASSWORD`) from the
  `rate-limiter-datagrid` Secret. Data Grid 8.6 is an older
  server generation than our 16.x client; Hot Rod negotiates the protocol, and the tests run against
  a 15.2 server for that reason.
- **The OpenShift shape lives in `application.yml`, not in the pod spec.** A second document guarded
  by `spring.config.activate.on-cloud-platform: kubernetes` moves the app to port 8080 with the
  actuator on 8081 and turns on TLS to Data Grid. We do not own the Deployment in the target
  namespace, so anything the app can settle for itself is one less thing to negotiate - keep new
  deployment-shaped settings there rather than in `k8s/configmap.yaml`. Local runs and the tests are
  unaffected: the document activates only when `KUBERNETES_SERVICE_HOST` is set.

## Testing

Every test that touches the store runs against a real Infinispan server in a container
(`HotRodTestServer`: Testcontainers, `quay.io/infinispan/server:15.2`, one per JVM, SCRAM
credentials, clients carrying the app's cache definition via `HotRodConfig.addBucketsCache`). So `mvn test` needs
Docker. Do not replace it with an in-JVM `HotRodServer`: Infinispan 16's server modules are Java 25
class files and this project is on 21. Test clients use `BASIC` intelligence because the container
advertises an address the host may not reach.

`RateLimiterTest` drives the limiter over that server with `MutableClock` (the shared test clock in
`src/test/java/com/example/ratelimiter`), clearing the cache between tests - prefer extending it over
mocking the cache. It covers exact capacity under concurrency, two clients sharing one bucket,
stepwise refill, capping, per-resource/per-identifier isolation, the daily quota, retry-after in all
its forms, the per-write lifespan, both fallback directions against an address nothing listens on,
and config completeness.

`RateLimitApiTest` is the Spring context test over MockMvc: the wire contract plus proof that
configuration binding works. `StoreUnavailableApiTest` boots the app with no reachable server and
checks it still starts, fails open with the degraded header, and keeps readiness up while
`bucketStore` health is down. After changing configuration binding, run them - and for anything
involving real timing, still boot the app and hit `/v1/rate/check`.

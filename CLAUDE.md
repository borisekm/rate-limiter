# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

A distributed token-bucket rate limiter: Spring Boot 4 (Java 21), embedded Infinispan 16 for the
shared bucket state, API generated from an OpenAPI spec. See README.md for the user-facing
description.

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
- **The algorithm is Bucket4j**, distributed over the Infinispan cache via `InfinispanProxyManager`.
  The `ReadWriteMap` it runs on comes from `FunctionalMap.create(cache.getAdvancedCache())` - Infinispan
  16 made `ReadWriteMapImpl`/`FunctionalMapImpl.create` package-private.
  Refill is stepwise (`refillIntervally`, whole `refill-tokens` per `refill-period`), not the smooth
  `refillGreedy` drip. Policies become `BucketConfiguration`s once at startup in `core/RateLimiter`.
- **The user marshaller must stay ProtoStream**, with Bucket4j's `Bucket4jProtobufContextInitializer`
  added as a configured context initializer (`serialization().addContextInitializer(...)`), which
  lands in both the user and the global ProtoStream context. Since Infinispan 16 a ProtoStream user
  marshaller makes the global marshaller skip the user marshaller and write user objects with the
  global context, so registering the schema on the marshaller instance instead - the Infinispan 15
  arrangement - fails every cross-node bucket operation with "No marshaller registered for object of
  Java type ... InfinispanProcessor". The starter's default marshaller is `JavaSerializationMarshaller`,
  under which cross-node operations die with ISPN000936 instead - invisible on a single node, which is
  what `ClusteredMarshallingTest` exists to catch. Anything touching marshalling or clustering needs
  that two-node test, not just the unit tests.
- **All mutation happens inside Bucket4j's entry processor**, which runs on the key's primary owner -
  that is what makes refill-and-consume atomic across the cluster. Do not read a bucket, decide, and
  write it back. Values in the cache are `byte[]` (Bucket4j serialises its own state), so the project
  defines no `@Proto` types of its own.
- **Bucket lifespan is derived** in `RateLimiterProperties.bucketLifespan()`, not configured. Do not
  turn it into a knob: a lifespan shorter than a policy's time-to-full silently resets that quota. It
  must stay `lifespan`, not `maxIdle` - Infinispan rejects max-idle alongside a non-passivating store
  (ISPN000651), and every check writes the entry, so lifespan refreshes on use anyway.
- **Time is wall-clock epoch millis** (`java.time.Clock` bean in `InfinispanConfig`), fed to Bucket4j
  as a custom `TimeMeter` in `RateLimiter`. Never use Bucket4j's default `nanoTime` meter: buckets are
  persisted and read by other nodes, and `nanoTime()`'s zero point is per-JVM, so state written with it
  is meaningless after a restart and wrong across machines.
- **The JGroups transport is TCP** (`ratelimiter.jgroups-config`), not Infinispan's UDP default -
  multicast is unavailable on OpenShift. The bundled TCP stack still *discovers* over multicast
  (MPING), so a cluster needs a different stack. Infinispan 16 moved the bundled stacks from
  `default-configs/` to `org/infinispan/configuration/`; the old path fails startup with ISPN000365.
  Do not go back to the UDP stack to silence a local warning.
- **On Kubernetes discovery is KUBE_PING, not DNS_PING** (`src/main/resources/jgroups-kubeping.xml`,
  `org.jgroups.kubernetes:jgroups-kubernetes`). The target namespace's Service comes from a central
  Helm chart and is a plain ClusterIP: its DNS answers with the virtual IP, so DNS_PING discovery is
  load-balanced to one arbitrary pod and every pod forms a cluster of one - which looks like a
  working service until you count. KUBE_PING asks the API server for pods by label instead, so it
  needs `k8s/rbac.yaml` (`get`/`list` on pods; a 403 means nobody finds anybody), a bind port fixed
  at 7800, and pod labels matching the selector in the stack file. The namespace comes from
  `config/PodNamespace`, which reads the ServiceAccount mount rather than requiring a downward-API
  env var. `KubePingStackTest` parses the stack: it is loaded only inside a pod, so a typo there is
  otherwise invisible until deployment. Switch back to `default-jgroups-kubernetes.xml` + DNS_PING
  only alongside a headless Service.
- **The OpenShift shape lives in `application.yml`, not in the pod spec.** A second document guarded
  by `spring.config.activate.on-cloud-platform: kubernetes` moves the app to port 8080 with the
  actuator on 8081, selects the KUBE_PING stack, and turns persistence off. We do not own the
  Deployment in the target namespace, so anything the app can settle for itself is one less thing to
  negotiate - keep new deployment-shaped settings there rather than in `k8s/configmap.yaml`. Local
  runs and the tests are unaffected: the document activates only when `KUBERNETES_SERVICE_HOST` is
  set.
- **A change to the persisted bucket representation needs `STATE_FORMAT` bumped** in
  `InfinispanConfig`. The store is a subdirectory named after it, because old entries whose marshaller
  no longer exists fail every request that touches such a key with "No marshaller registered for
  Protobuf type ..." - and the tests never catch it, since they all use fresh `@TempDir` stores. A
  changed format means: bump, and the old directory is inert.
- **Buckets are persisted** to a per-node `SoftIndexFileStore` (`ratelimiter.persistence.location`).
  Two nodes must never share a location. The store is not shared, so a restart with a different node
  count can orphan buckets - see README before changing anything here. On Kubernetes the store is off
  (the chart's pods get no volume) and buckets survive only a rolling restart, carried by the second
  owner; do not "fix" that with a store on the container filesystem, which is a fresh empty store on
  every restart and, worse, a shared one if two pods land on one node.

## Testing

`RateLimiterTest` runs against a real single-node `DefaultCacheManager` with `MutableClock` (the shared
test clock in `src/test/java/com/example/ratelimiter`) - prefer extending it over mocking the cache. It
covers exact capacity under concurrency, stepwise refill, capping, per-resource/per-identifier
isolation, the daily quota, retry-after in all its forms, and config completeness.

`ClusteredMarshallingTest` starts two real nodes in one JVM from the actual `InfinispanConfig`, so
marshalling of Bucket4j's state and entry processor is genuinely exercised. `PersistenceRestartTest` starts a
node, stops it, and starts another against the same directory - the only cover for state surviving a
deploy. Both need their own `ratelimiter.cluster-name` and a `@TempDir` store location, or they join a
locally running instance and hang in state transfer.

Both also run on `src/test/resources/jgroups-test-tcpping.xml` - the bundled TCP stack with multicast
discovery replaced by static loopback TCPPING. Never point them at the production stack: it discovers
with MPING over the first site-local address, which on a machine with a VPN or a VMware/Hyper-V
adapter is a virtual NIC multicast never crosses, so the nodes each form a cluster of one and the
shared-bucket assertions fail on that machine only. `ClusteredMarshallingTest.awaitOneClusterOfTwo`
asserts the two nodes actually found each other, so that failure names itself instead of surfacing as
a confusing assertion in the test body.

`KubePingStackTest` builds a JChannel from the production Kubernetes stack without binding a socket
or calling the API server, which is enough to catch a misspelled protocol, a missing
jgroups-kubernetes jar, or a bind port left at 0.

`RateLimitApiTest` is the Spring context test over MockMvc: the wire contract plus proof that
configuration binding works. After changing configuration binding, run it - and for anything involving
real timing, still boot the app and hit `/v1/rate/check`.

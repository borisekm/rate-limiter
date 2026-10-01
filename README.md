# rate-limiter

Token-bucket rate limiter service on Spring Boot 4, with the buckets held on a remote Infinispan /
Red Hat Data Grid server (Infinispan remote starter, Hot Rod client 16).

```bash
mvn clean verify          # generates the API, compiles, runs tests, enforces 95% coverage (no Docker needed)
docker compose up -d infinispan
INFINISPAN_REMOTE_CLIENT_INTELLIGENCE=BASIC mvn spring-boot:run

curl -s -X POST localhost:8051/v1/rate/check \
  -H 'Content-Type: application/json' \
  -d '{"resource":"subjectSearch","identifier":"10.0.0.7"}'
# -> 200 {"allowed":true,"limit":15,"remaining":14,"retryAfterMillis":60000}
# -> 429 {"allowed":false,"limit":15,"remaining":0,"retryAfterMillis":53413}   + Retry-After header
```

## Buckets

A bucket is identified by **`<resource>@<identifier>`**, e.g. `subjectSearch@10.0.0.7`.

- **resource** - a fixed set of limited things (calls into the subject service, calls into our own
  service, ...). Declared as the `resource` enum in the OpenAPI spec and mirrored by
  `com.example.ratelimiter.core.Resource`. Each resource carries its own capacity and refill quota.
- **identifier** - freeform: an IP, a user id, an organisation id, a session id. A resource's policy
  applies to every identifier independently.

## Policies

Configured under `ratelimiter.resources` in `application.yml`, keyed by the resource's wire value:

```yaml
ratelimiter:
  resources:
    subjectSearch:
      capacity: 15          # max burst; a fresh bucket starts full
      refill-tokens: 6      # tokens granted per period
      refill-period: 60s
    maxCallsIp:
      capacity: 100000
      refill-tokens: 100000
      refill-period: 24h
```

Refill is **stepwise, not a continuous drip**: at each period boundary the bucket gains
`refill-tokens`, capped at `capacity`. Mid-period nothing accrues. In Bucket4j terms that is
`refillIntervally`; `refillGreedy` would be the smooth drip.

The algorithm itself is [Bucket4j](https://bucket4j.com) (`bucket4j_jdk17-core`), with its state in
the remote cache - see [Atomicity](#atomicity). Each policy becomes a `BucketConfiguration` with one
`Bandwidth`, built once at startup in `core/RateLimiter`.

`retryAfterMillis` is always the wait until the next refill that helps, on allowed and refused calls
alike: when refused, until enough tokens are back; when allowed, until the next refill lands. It is
never 0 (the spec's "0 otherwise" wording is out of date), so a caller can pace itself before it is
refused. The `Retry-After` header is still sent on 429s only. Every value of `Resource` must have a policy - a missing one fails the startup
bind, not the first request.

Buckets are dropped from the cache after the longest time-to-full across all policies (24h with the
config above). Anything older has refilled to capacity anyway, so it is indistinguishable from an
absent bucket - which is why this is derived rather than configured; a shorter one would silently
reset the daily quotas. It is sent as the `lifespan` of every write, so it holds whatever the
server's cache definition says, and since every check writes the entry it is refreshed on each use.

## Bucket store

The buckets live in the cache `rate-limit-buckets` on an Infinispan server, reached over Hot Rod -
on OpenShift the namespace's Red Hat Data Grid cluster. The service itself is stateless: replicas
never talk to each other and share their limits only through that cache.

The client is the Infinispan Spring Boot 4 remote starter's `RemoteCacheManager`, configured under
`infinispan.remote` (any key can come from the environment, e.g. `INFINISPAN_REMOTE_SERVER_LIST`):

```yaml
infinispan:
  remote:
    server-list: localhost:11222   # host:port[;host:port...]
    use-auth: true                 # SCRAM
    auth-username: ${INFINISPAN_USERNAME:ratelimiter}
    auth-password: ${INFINISPAN_PASSWORD:ratelimiter}
    use-ssl: true                  # on OpenShift, with:
    trust-store-type: PEM
    trust-store-file-name: /path/to/ca.crt
    sni-host-name: datagrid        # the name the certificate is checked against
    client-intelligence: HASH_DISTRIBUTION_AWARE   # BASIC when the server pods' own IPs are not reachable
    connect-timeout: 500           # ms
    socket-timeout: 2000           # ms
    marshaller: org.infinispan.commons.marshall.ProtoStreamMarshaller
    cache:
      enabled: false               # no Spring CacheManager - see below
```

Two of those are not the starter's defaults on purpose. Its default marshaller is Java serialization;
the buckets cache is pinned to ProtoStream in code anyway (`HotRodConfig.addBucketsCache`). And its
Spring `CacheManager` lists the server's caches at startup for cache metrics, which would stop the
app from starting without the store.

The app creates the cache on first use if the server does not have it, with this definition
(`HotRodConfig.BUCKETS_CACHE_DEFINITION`); an existing cache of that name is used as it is, so the
server's owner can equally create it up front:

```json
{"distributed-cache": {"mode": "SYNC", "owners": 2, "statistics": true,
  "encoding": {"media-type": "application/x-protostream"}}}
```

Keys are strings and values are Bucket4j's own `byte[]` state, both native to ProtoStream, so no
schema has to be registered on the server and nothing of ours is deployed into it. The client (16.x)
negotiates the Hot Rod protocol version, so it works with the older servers Data Grid 8.x ships.

Durability is the server's business now: two owners carry the buckets through the loss of one Data
Grid pod, and only a restart of the whole Data Grid cluster resets quotas - unless the cache is
given a persistent store there.

Refill anchors are epoch milliseconds, not `System.nanoTime()`, so state written by one JVM still
means the same thing to another replica, on another machine, or after a restart.

## When the store is unavailable

Every check needs the server. When it cannot be reached (or refuses the request), the check does
not fail: it answers with the fallback in `ratelimiter.when-store-unavailable`, which every
environment sets deliberately - the code has no default:

| value | answer |
|---|---|
| `allow` | fail open: 200, `allowed: true`, nothing counted |
| `deny` | fail closed: 429, `allowed: false`, `retryAfterMillis: 1000` |

Either way the response carries `X-RateLimit-Degraded: store-unavailable`, and the log gets a
`Bucket store unavailable, allowing|denying all calls ...` warning with the cause - once when the outage
starts and then at most every 30s with the number of degraded answers in between, so an outage under
load does not flood the log. `Bucket store reachable again` marks the end. The Hot Rod client logs
its own connection errors too.

The app also starts without the server (the cache is looked up on first use), and
`/actuator/health` lists `bucketStore` as `UP`/`DOWN`. That indicator is deliberately left out of
the readiness group: with the store down every replica is equally degraded, and taking them all out
of the Service would turn fail-open into an outage.

## Layout

| | |
|---|---|
| `core/Resource` | the limited resources; domain counterpart of the spec's `resource` enum |
| `core/RateLimiter` | bucket naming, the Bucket4j configuration per resource, the decision, the outage fallback |
| `core/HotRodProxyManager` | Bucket4j over a Hot Rod `RemoteCache` by versioned compare-and-swap |
| `config/RateLimiterProperties` | per-resource policies, derived bucket lifespan, `when-store-unavailable` |
| `config/HotRodConfig` | the buckets cache definition, added to the starter's `RemoteCacheManager`, and the wall `Clock` bean |
| `config/BucketStoreHealthIndicator` | `bucketStore` in `/actuator/health` |
| `config/ResourceConverter` | binds `ratelimiter.resources` keys by wire value |
| `config/ValidationConfig`, `config/EnumSizeValidator` | let `@Size` apply to an enum - see below |
| `api/RateLimitApiDelegateImpl` | the only hand-written piece of the API layer |

Generated sources land in `target/generated-sources/openapi` (`RateLimitApi`,
`RateLimitApiController`, `RateLimitApiDelegate`, models).

The spec puts `minLength`/`maxLength` on the `resource` enum, so the generator emits `@Size` on the
generated `ResourceEnum` field - a constraint Hibernate Validator has no validator for, which turns
every request into a 500 (`HV000030`). `ValidationConfig` registers `EnumSizeValidator` to make
`@Size` on an enum measure the constant's wire value instead. Both classes can go once the spec drops
the length bounds from the enum, where they mean nothing anyway.

## Atomicity

Bucket4j's own Infinispan module ships an entry processor to the key's owner, which only an
*embedded* cache can run; Hot Rod cannot send code to the server. `core/HotRodProxyManager` uses
Bucket4j's generic compare-and-swap instead:

1. `getWithMetadata(key)` - the bucket state and its version (or nothing, for a new bucket)
2. Bucket4j applies the refill-and-consume locally
3. `replaceWithVersion(key, state, version, lifespan)` - or `putIfAbsent` for a new bucket - which
   the server applies only if nobody wrote the bucket in between

A lost race returns `false`, and Bucket4j re-reads and retries. Every check therefore costs two
round trips, more under contention on the same bucket, and no lock is held anywhere - some writer
always wins, so a hot bucket cannot deadlock. `admitsExactlyCapacityUnderConcurrency` and
`twoInstancesShareOneBucket` hold this to exactly the capacity. Never write a bucket unversioned:
that is a lost update and quietly over-admits.

## Tests

The tests need no server and no container runtime. The buckets cache is `InMemoryRemoteCache`, an
in-JVM fake of the `RemoteCache` that keeps the Hot Rod semantics the limiter relies on (versioned
`replaceWithVersion`, `putIfAbsent` returning null without `FORCE_RETURN_VALUE`, per-write lifespans,
a switchable outage) and refuses plain `put`/`replace`, so a lost-update bug still fails
`RateLimiterTest`'s concurrency tests. `RateLimitApiTest` boots the real context with the starter's
`RemoteCacheManager` mocked to hand out that fake; `StoreUnavailableApiTest` and the fallback tests use
a real client pointed at an address nothing listens on.

What the fake cannot prove is compatibility with the actual Data Grid server - check that by running
the app against one (`docker compose up -d infinispan`, or a port-forward to Data Grid). An in-JVM Hot
Rod server is not an option: Infinispan 16's server modules are built for Java 25, this project for 21.

`mvn verify` fails below 95% line or branch coverage of the hand-written code (JaCoCo; report in
`target/site/jacoco/index.html`).

## Containers and Kubernetes

`Dockerfile` builds the service (the OpenAPI generation runs inside the build stage) onto a JRE
image with no state of its own; `compose.yaml` runs it next to an Infinispan server.

```bash
docker compose up -d
curl -s -X POST localhost:8051/v1/rate/check -H 'Content-Type: application/json' \
  -d '{"resource":"subjectSearch","identifier":"10.0.0.7"}'
```

Every response carries an `X-Served-By` header naming the node that answered (the pod name on
Kubernetes, the hostname otherwise; override with `ratelimiter.instance-id`). It is a header and not
a response field because the response schema belongs to the API contract.

`k8s/` deploys two replicas as a Deployment, shaped for an OpenShift namespace whose Service and
Deployment come from a central Helm chart: ports 8080 (traffic) and 8081 (actuator), no volume, and
Data Grid in the same namespace as the store. See [k8s/README.md](k8s/README.md).

Locally the app stays on 8051 without TLS; the Kubernetes settings (ports, TLS to Data Grid with the
service CA) apply only when Spring detects the platform, which it does from the
`KUBERNETES_SERVICE_HOST` every pod gets.
